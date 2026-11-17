package com.fseven.padmouse

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 手柄鼠标前台服务：光标悬浮窗 + 映射引擎 + 手柄监听全部常驻于此。
 *
 * M9 起映射不再依赖 Activity：
 * - [Mapper] 由服务持有，用 Handler 60Hz 驱动，切桌面/别的 App 仍持续工作；
 * - IGamepadListener 在服务里注册，Activity 退出不会断流；
 * - 屏幕尺寸由服务的 WindowManager 读取，不经过 Activity。
 */
class MouseService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var systemActions: SystemActions
    private lateinit var serviceManager: ShizukuServiceManager
    private lateinit var mapper: Mapper

    /** 注入与 shell 命令放到单线程，保证 down/move/up 顺序。 */
    private val injectExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cursorView: CursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorWidthPx = 0f
    private var cursorHeightPx = 0f
    private var cursorSizeDp = CursorView.DEFAULT_SIZE_DP
    private var cursorColor = COLOR_WHITE
    private var idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS

    /** 窗口是否已经成功 addView，避免重复 remove。 */
    private var overlayAdded = false

    /** 服务每次启动只做一次权限自检。 */
    private var permissionSelfCheckStarted = false

    /** 手柄监听是否已注册，避免重复 startGamepadCapture。 */
    private var gamepadCaptureStarted = false

    /** 等待 UserService 就绪的重试次数。 */
    private var engineStartAttempts = 0

    private var rebindScheduled = false

    /** 窗口级透明度动画；同时决定输入使用的 opacity。 */
    private var windowAlphaAnimator: ValueAnimator? = null

    private val idleHideRunnable = Runnable {
        // 静止超时：降低窗口 alpha（不是 View alpha），保留一个可感知的落点。
        animateWindowAlpha(IDLE_WINDOW_ALPHA, FADE_OUT_MS)
    }

    /**
     * 悬浮窗权限被撤销时立即停止服务并提示，避免留下没有光标的空服务。
     * 系统设置页撤销权限不会通知 App，只能轮询。
     */
    private val permissionWatchdog = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@MouseService)) {
                Toast.makeText(
                    this@MouseService,
                    R.string.cursor_toast_overlay_revoked,
                    Toast.LENGTH_LONG
                ).show()
                stopEverything()
                return
            }
            handler.postDelayed(this, PERMISSION_CHECK_INTERVAL_MS)
        }
    }

    /** 映射监听：只写共享快照，界面由自己的轮询刷新，避免主线程抖动。 */
    private val mapperListener = object : Mapper.Listener {
        override fun onCursor(x: Float, y: Float) {
            MouseRuntime.setCursor(x, y)
        }

        override fun onVector(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
            MouseRuntime.setVector(leftX, leftY, rightX, rightY)
        }

        override fun onAction(action: MapperAction, atMillis: Long) {
            MouseRuntime.setAction(action, atMillis)
        }
    }

    /** 注入实现；所有调用串行化，避免手势事件乱序。 */
    private val mapperInjector = object : Mapper.Injector {
        override fun tap(x: Float, y: Float) = dispatchInjection { serviceManager.tap(x, y) }

        override fun rightClick(x: Float, y: Float) = dispatchInjection { serviceManager.rightClick(x, y) }

        override fun middleClick(x: Float, y: Float) = dispatchInjection { serviceManager.middleClick(x, y) }

        override fun touchDown(x: Float, y: Float) = dispatchInjection { serviceManager.down(x, y) }

        override fun touchMove(x: Float, y: Float) = dispatchInjection { serviceManager.move(x, y) }

        override fun touchUp(x: Float, y: Float) = dispatchInjection { serviceManager.up(x, y) }

        override fun mouseDown(x: Float, y: Float, button: Int) =
            dispatchInjection { serviceManager.mouseDown(x, y, button) }

        override fun mouseUp(x: Float, y: Float, button: Int) =
            dispatchInjection { serviceManager.mouseUp(x, y, button) }

        override fun mouseMove(x: Float, y: Float, buttonState: Int) =
            dispatchInjection { serviceManager.mouseMove(x, y, buttonState) }

        override fun scroll(x: Float, y: Float, h: Float, v: Float) =
            dispatchInjection { serviceManager.scroll(x, y, h, v) }

        override fun systemAction(action: SystemAction) = dispatchInjection {
            if (!serviceManager.isReady) {
                Log.w(TAG, "系统动作 " + action + " 跳过：UserService 未就绪")
                return@dispatchInjection
            }
            val result = systemActions.run(action)
            if (result.success) {
                Log.i(TAG, "系统动作 " + action + " 完成: " + result.commands.joinToString("; "))
            } else {
                Log.w(TAG, "系统动作 " + action + " 失败: " + result.output)
            }
        }
    }

    /** 手柄事件：UserService（shell 侧）经 Binder 回传，统一切回服务主线程。 */
    private val gamepadListener = object : IGamepadListener.Stub() {
        override fun onEvent(type: Int, code: Int, value: Int) {
            if (type != EV_KEY) return
            MouseRuntime.addKeyEvent(code, value, SystemClock.uptimeMillis())
            handler.post { if (mapper.isRunning) mapper.onKey(code, value) }
        }

        override fun onAxis(code: Int, normalized: Float) {
            handler.post {
                MouseRuntime.updateAxes(code, normalized)
                if (mapper.isRunning) mapper.onAxis(code, normalized)
            }
        }

        override fun onDeviceInfo(summary: String?) {
            if (summary.isNullOrBlank()) return
            MouseRuntime.gamepadSummary = summary
            MouseRuntime.notifyChanged()
        }
    }

    /** UserService 连接状态；服务是唯一驱动者：就绪即启动映射，断开即停并重试。 */
    private val userServiceStateListener: (UserServiceState) -> Unit = { state ->
        handler.post { onUserServiceStateChanged(state) }
    }

    inner class LocalBinder : Binder() {
        fun moveBy(dx: Float, dy: Float) = this@MouseService.moveBy(dx, dy)
        fun moveTo(x: Float, y: Float) = this@MouseService.moveTo(x, y)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(this)
        systemActions = SystemActions { command -> serviceManager.shellExec(command) }
        createNotificationChannel()

        serviceManager = MouseRuntime.ensureManager(this)
        serviceManager.addListener(userServiceStateListener)

        val config = pendingConfig ?: currentConfig()
        pendingConfig = null
        mapper = Mapper(mapperInjector, config, mapperListener)
        MouseRuntime.mapperConfig = mapper.configSnapshot
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_UPDATE_CONFIG -> {
                reloadMapperConfig()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                ensureForeground()
                runPermissionSelfCheck()
                val sizeDp = intent.getIntExtra(EXTRA_SIZE_DP, cursorSizeDp)
                val color = intent.getIntExtra(EXTRA_COLOR, cursorColor)
                val timeoutMs = intent.getLongExtra(EXTRA_IDLE_TIMEOUT_MS, idleTimeoutMs)
                showCursor(sizeDp, color, timeoutMs)
                return START_NOT_STICKY
            }
        }

        ensureForeground()
        runPermissionSelfCheck()

        val sizeDp = intent?.getIntExtra(EXTRA_SIZE_DP, cursorSizeDp) ?: cursorSizeDp
        val color = intent?.getIntExtra(EXTRA_COLOR, cursorColor) ?: cursorColor
        val timeoutMs = intent?.getLongExtra(EXTRA_IDLE_TIMEOUT_MS, idleTimeoutMs) ?: idleTimeoutMs
        showCursor(sizeDp, color, timeoutMs)
        if (cursorView != null) startEngineFlow()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenBounds()
        if (cursorView != null) {
            applyPosition(cursorX, cursorY, updateLayout = true)
        }
    }

    override fun onDestroy() {
        // 先停引擎再清 Handler，保证 stop() 的收尾（松键/抬笔）有机会执行。
        stopMappingEngine()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        MouseRuntime.resetRunningState()
        MouseRuntime.setStage(RunStage.IDLE)
        MouseRuntime.userServiceState = UserServiceState.DISCONNECTED
        serviceManager.removeListener(userServiceStateListener)
        // 服务停止时解绑；daemon 进程保留，下次启动复用同一个 :input。
        MouseRuntime.releaseManager()
        injectExecutor.shutdownNow()
        instance = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 启动链路

    /**
     * 一个开关的完整链路：服务已是前台 → 绑定 Shizuku UserService →
     * 注册手柄监听 → 读取屏幕尺寸 → 启动 60Hz 映射。
     */
    private fun startEngineFlow() {
        if (cursorView == null) {
            MouseRuntime.setStage(RunStage.FAILED, getString(R.string.run_detail_overlay_missing))
            return
        }
        engineStartAttempts = 0
        MouseRuntime.setStage(RunStage.BINDING_SHIZUKU)
        serviceManager.start()
        tryStartEngine()
    }

    private fun tryStartEngine() {
        if (cursorView == null) return
        if (serviceManager.isReady) {
            startMappingEngine()
            return
        }

        MouseRuntime.setStage(RunStage.BINDING_SHIZUKU)
        if (engineStartAttempts >= MAX_ENGINE_START_ATTEMPTS) {
            MouseRuntime.setStage(
                RunStage.FAILED,
                getString(R.string.run_detail_shizuku_unavailable)
            )
            return
        }
        engineStartAttempts++
        serviceManager.bindNow()
        handler.postDelayed({
            if (cursorView != null && !serviceManager.isReady) tryStartEngine()
        }, ENGINE_RETRY_MS)
    }

    /** UserService 就绪后注册手柄监听并启动映射；重复调用安全。 */
    private fun startMappingEngine() {
        if (cursorView == null || !serviceManager.isReady) return

        MouseRuntime.setStage(RunStage.READING_GAMEPAD)
        if (!startGamepadCapture()) {
            MouseRuntime.setStage(
                RunStage.FAILED,
                getString(R.string.run_detail_gamepad_failed)
            )
            return
        }

        refreshScreenBounds()
        // 留出「正在读取手柄…」的可见时间，同时确认服务没有被中途停止。
        handler.postDelayed({
            if (cursorView == null || !serviceManager.isReady) return@postDelayed
            mapper.updateConfig(currentConfig())
            if (!mapper.isRunning) mapper.start(screenWidth, screenHeight)
            MouseRuntime.mapperConfig = mapper.configSnapshot
            MouseRuntime.mappingRunning = mapper.isRunning
            MouseRuntime.setStage(
                if (mapper.isRunning) RunStage.RUNNING else RunStage.FAILED,
                if (mapper.isRunning) null else getString(R.string.run_detail_gamepad_failed)
            )
        }, GAMEPAD_WARMUP_MS)
    }

    private fun startGamepadCapture(): Boolean {
        if (gamepadCaptureStarted) return true
        return try {
            serviceManager.startGamepadCapture(gamepadListener)
            gamepadCaptureStarted = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "注册手柄监听失败", t)
            gamepadCaptureStarted = false
            false
        }
    }

    /** 停映射与手柄监听；服务本身可以继续存活等待重连。 */
    private fun stopMappingEngine() {
        if (gamepadCaptureStarted) {
            runCatching { serviceManager.stopGamepadCapture() }
            gamepadCaptureStarted = false
        }
        if (::mapper.isInitialized && mapper.isRunning) mapper.stop()
        MouseRuntime.mappingRunning = false
    }

    private fun onUserServiceStateChanged(state: UserServiceState) {
        if (!::mapper.isInitialized) return
        MouseRuntime.userServiceState = state
        when (state) {
            UserServiceState.READY -> {
                if (cursorView != null) startMappingEngine()
            }

            UserServiceState.DISCONNECTED, UserServiceState.FAILED -> {
                if (cursorView == null) return
                stopMappingEngine()
                MouseRuntime.mappingRunning = false
                MouseRuntime.setStage(RunStage.BINDING_SHIZUKU)
                scheduleRebind()
            }

            UserServiceState.CONNECTING -> Unit
        }
    }

    /** 断线重连：每秒 bind 一次，最多 [MAX_REBIND_ATTEMPTS] 次。 */
    private fun scheduleRebind(attempt: Int = 0) {
        if (rebindScheduled) return
        rebindScheduled = true
        handler.postDelayed({
            rebindScheduled = false
            if (cursorView == null || serviceManager.isReady) return@postDelayed
            if (attempt >= MAX_REBIND_ATTEMPTS) {
                MouseRuntime.setStage(
                    RunStage.FAILED,
                    getString(R.string.run_detail_shizuku_unavailable)
                )
                return@postDelayed
            }
            serviceManager.bindNow()
            scheduleRebind(attempt + 1)
        }, REBIND_DELAY_MS)
    }

    private fun currentConfig(): MapperConfig =
        settingsRepository.loadMapperConfig(settingsRepository.loadSystemBindings())

    /** Activity 修改手感/绑定时通过 [applyConfig] 热更新。 */
    private fun applyMapperConfig(config: MapperConfig) {
        if (!::mapper.isInitialized) return
        mapper.updateConfig(config)
        MouseRuntime.mapperConfig = mapper.configSnapshot
        MouseRuntime.notifyChanged()
    }

    private fun reloadMapperConfig() {
        if (!::mapper.isInitialized) return
        pendingConfig = null
        applyMapperConfig(currentConfig())
    }

    private fun dispatchInjection(block: () -> Unit) {
        if (injectExecutor.isShutdown) return
        injectExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "映射注入失败", t)
            }
        }
    }

    // ---------------------------------------------------------------- 光标窗口

    private fun showCursor(sizeDp: Int, color: Int, timeoutMs: Long) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "缺少悬浮窗权限，无法显示光标")
            MouseRuntime.setStage(RunStage.FAILED, getString(R.string.run_detail_overlay_missing))
            stopEverything()
            return
        }

        idleTimeoutMs = timeoutMs.coerceIn(MIN_IDLE_TIMEOUT_MS, MAX_IDLE_TIMEOUT_MS)
        cursorSizeDp = sizeDp.coerceIn(CursorView.MIN_SIZE_DP, CursorView.MAX_SIZE_DP)
        cursorColor = color
        updateCursorMetrics()

        val existing = cursorView
        if (existing != null) {
            applyAppearance(existing)
            clampAndApplyPosition()
            showCursorNow()
            schedulePermissionWatchdog()
            return
        }

        refreshScreenBounds()
        val view = CursorView(this)
        applyAppearance(view)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(0, ((screenWidth - cursorWidthPx) / 2f).roundToInt())
            y = max(0, ((screenHeight - cursorHeightPx) / 2f).roundToInt())
            windowAnimations = 0
            // Android 12+ 会拦截不经系统授信的 overlay 的穿透触摸，
            // 当窗口 opacity > 0.8 时箭头矩形内的点击会被吞掉；保持 0.79 以确保 FLAG_NOT_TOUCHABLE 生效。
            alpha = ACTIVE_WINDOW_ALPHA
        }

        cursorX = params.x.toFloat()
        cursorY = params.y.toFloat()

        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            Log.e(TAG, "添加光标悬浮窗失败", t)
            MouseRuntime.setStage(RunStage.FAILED, getString(R.string.run_detail_overlay_missing))
            stopEverything()
            return
        }

        cursorView = view
        layoutParams = params
        overlayAdded = true
        isRunning = true
        scheduleIdleHide()
        schedulePermissionWatchdog()
    }

    private fun applyAppearance(view: CursorView) {
        view.setCursorSizeDp(cursorSizeDp)
        view.setCursorColor(cursorColor)
        updateCursorMetrics()
    }

    private fun updateCursorMetrics() {
        val density = resources.displayMetrics.density
        cursorHeightPx = cursorSizeDp * density
        cursorWidthPx = cursorHeightPx * CursorView.WIDTH_RATIO
    }

    private fun refreshScreenBounds() {
        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        if (screenWidth <= 0 || screenHeight <= 0) {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun clampAndApplyPosition() {
        applyPosition(cursorX, cursorY, updateLayout = true)
    }

    private fun applyPosition(x: Float, y: Float, updateLayout: Boolean) {
        val clampedX = x.coerceIn(0f, max(0f, screenWidth - cursorWidthPx))
        val clampedY = y.coerceIn(0f, max(0f, screenHeight - cursorHeightPx))
        cursorX = clampedX
        cursorY = clampedY

        val params = layoutParams ?: return
        params.x = clampedX.roundToInt()
        params.y = clampedY.roundToInt()
        val view = cursorView ?: return
        if (!updateLayout || !overlayAdded) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "更新光标位置失败（视图可能已 detach）", e)
        } catch (t: Throwable) {
            Log.w(TAG, "更新光标位置异常", t)
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(idleHideRunnable)
        handler.removeCallbacks(permissionWatchdog)
        windowAlphaAnimator?.cancel()
        windowAlphaAnimator = null
        val view = cursorView
        cursorView = null
        layoutParams = null
        if (view == null) {
            overlayAdded = false
            return
        }
        if (overlayAdded && view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "光标视图已 detach，跳过 removeView", e)
            } catch (t: Throwable) {
                Log.w(TAG, "移除光标视图异常", t)
            }
        }
        overlayAdded = false
    }

    // ---------------------------------------------------------------- 移动与自动隐藏

    /** 移动到屏幕坐标（光标尖端）。可从任意线程调用。 */
    fun moveTo(x: Float, y: Float) {
        handler.post { moveToInternal(x, y) }
    }

    /** 相对移动。可从任意线程调用。 */
    fun moveBy(dx: Float, dy: Float) {
        handler.post { moveToInternal(cursorX + dx, cursorY + dy) }
    }

    /** 当前光标坐标（窗口原点/箭头热点）；窗口未显示时返回 null。 */
    fun currentPosition(): FloatArray? =
        if (cursorView != null) floatArrayOf(cursorX, cursorY) else null

    private fun moveToInternal(x: Float, y: Float) {
        if (cursorView == null) return
        applyPosition(x, y, updateLayout = true)
        showCursorNow()
    }

    private fun showCursorNow() {
        if (cursorView == null) return
        handler.removeCallbacks(idleHideRunnable)
        animateWindowAlpha(ACTIVE_WINDOW_ALPHA, FADE_IN_MS)
        scheduleIdleHide()
    }

    /**
     * 修改 WindowManager.LayoutParams.alpha（不是 View alpha），
     * 这样静止变淡时窗口的输入透明度也同步下降。
     */
    private fun animateWindowAlpha(targetAlpha: Float, durationMs: Long) {
        val params = layoutParams ?: return
        val view = cursorView ?: return
        windowAlphaAnimator?.cancel()
        val startAlpha = params.alpha
        if (abs(startAlpha - targetAlpha) < 0.01f) {
            params.alpha = targetAlpha
            if (overlayAdded && view.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            return
        }
        val animator = ValueAnimator.ofFloat(startAlpha, targetAlpha).apply {
            duration = durationMs
            addUpdateListener { animation ->
                params.alpha = animation.animatedValue as Float
                if (overlayAdded && view.isAttachedToWindow) {
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: IllegalArgumentException) {
                        Log.d(TAG, "窗口 alpha 动画期间视图已 detach", e)
                        cancel()
                    }
                }
            }
        }
        animator.start()
        windowAlphaAnimator = animator
    }

    private fun scheduleIdleHide() {
        handler.removeCallbacks(idleHideRunnable)
        if (idleTimeoutMs > 0) {
            handler.postDelayed(idleHideRunnable, idleTimeoutMs)
        }
    }

    private fun schedulePermissionWatchdog() {
        handler.removeCallbacks(permissionWatchdog)
        handler.postDelayed(permissionWatchdog, PERMISSION_CHECK_INTERVAL_MS)
    }

    // ---------------------------------------------------------------- 权限自检

    /**
     * 服务启动时的一次性权限自检：悬浮窗/通知被系统回收且 Shizuku 仍可用时自动补开。
     *
     * 悬浮窗缺失时 [showCursor] 会自行停服；这里主要补开通知权限，
     * 并把结果写成界面提示交给 MainActivity（不弹 Toast 打扰）。
     */
    private fun runPermissionSelfCheck() {
        if (permissionSelfCheckStarted) return
        permissionSelfCheckStarted = true
        Thread({
            try {
                val shizuku = ShizukuManager(this)
                if (shizuku.getStatus() != ShizukuStatus.GRANTED) return@Thread
                val needsOverlay = !shizuku.canDrawOverlays()
                val needsNotification = shizuku.supportsNotificationPermission() &&
                    !shizuku.hasNotificationPermission()
                if (!needsOverlay && !needsNotification) return@Thread

                val results = shizuku.autoGrantPermissions(shell = { command ->
                    shizuku.shell(command)
                })
                if (results.any { it.changed && it.status == AutoGrantStatus.GRANTED }) {
                    lastPermissionNotice = getString(R.string.permission_self_heal_notice)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "权限自检失败", t)
            }
        }, "permission-self-check").start()
    }

    // ---------------------------------------------------------------- 前台服务

    private fun ensureForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    /** 单一出口：停映射/手柄 → 移除悬浮窗 → 撤前台通知 → 停服务。 */
    private fun stopEverything() {
        stopMappingEngine()
        MouseRuntime.resetRunningState()
        MouseRuntime.setStage(RunStage.IDLE)
        removeOverlay()
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, MouseService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_cursor),
            getString(R.string.notification_action_stop),
            stopPendingIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cursor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    companion object {
        private const val TAG = "MouseService"

        const val ACTION_START = "com.fseven.padmouse.action.START_CURSOR"
        const val ACTION_UPDATE = "com.fseven.padmouse.action.UPDATE_CURSOR"
        const val ACTION_UPDATE_CONFIG = "com.fseven.padmouse.action.UPDATE_CONFIG"
        const val ACTION_STOP = "com.fseven.padmouse.action.STOP_CURSOR"

        const val EXTRA_SIZE_DP = "extra_size_dp"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_IDLE_TIMEOUT_MS = "extra_idle_timeout_ms"

        const val MIN_SIZE_DP = CursorView.MIN_SIZE_DP
        const val MAX_SIZE_DP = CursorView.MAX_SIZE_DP
        const val DEFAULT_SIZE_DP = CursorView.DEFAULT_SIZE_DP
        const val DEFAULT_IDLE_TIMEOUT_MS = 3000L
        const val MIN_IDLE_TIMEOUT_MS = 1000L
        const val MAX_IDLE_TIMEOUT_MS = 10000L

        val COLOR_WHITE: Int = 0xFFFFFFFF.toInt()
        val COLOR_BLACK: Int = 0xFF000000.toInt()
        val COLOR_YELLOW: Int = 0xFFFFEB3B.toInt()

        private const val CHANNEL_ID = "padmouse_service"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        private const val EV_KEY = 1

        /** 激活态窗口透明度必须 <= 0.8，否则 Android 12+ 会拦截穿透触摸。 */
        private const val ACTIVE_WINDOW_ALPHA = 0.79f
        private const val IDLE_WINDOW_ALPHA = 0.15f
        private const val FADE_IN_MS = 120L
        private const val FADE_OUT_MS = 200L
        private const val PERMISSION_CHECK_INTERVAL_MS = 2000L
        private const val ENGINE_RETRY_MS = 250L
        private const val GAMEPAD_WARMUP_MS = 400L
        private const val MAX_ENGINE_START_ATTEMPTS = 24
        private const val REBIND_DELAY_MS = 1000L
        private const val MAX_REBIND_ATTEMPTS = 5

        /** 服务/光标是否在运行，供主界面同步开关状态。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: MouseService? = null

        /** Activity 在服务未启动时修改的参数，服务启动时应用。 */
        @Volatile
        private var pendingConfig: MapperConfig? = null

        /** 供映射引擎（M5）驱动光标，不需要自行绑定服务。 */
        fun moveTo(x: Float, y: Float) {
            instance?.moveTo(x, y)
        }

        fun moveBy(dx: Float, dy: Float) {
            instance?.moveBy(dx, dy)
        }

        /** 供映射引擎（M5）读取当前光标坐标。 */
        fun currentPosition(): FloatArray? = instance?.currentPosition()

        /** 设置页热更新映射参数；服务未运行时仅记录，下次启动生效。 */
        fun applyConfig(config: MapperConfig) {
            pendingConfig = config
            instance?.handler?.post { instance?.applyMapperConfig(config) }
        }

        /** 服务启动自检补开权限后的界面提示；MainActivity 读取后清空，避免重复展示。 */
        @Volatile
        private var lastPermissionNotice: String? = null

        fun takePermissionNotice(): String? {
            val notice = lastPermissionNotice
            lastPermissionNotice = null
            return notice
        }
    }
}
