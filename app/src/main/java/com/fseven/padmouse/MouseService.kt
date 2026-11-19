package com.fseven.padmouse

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * M11 震动反馈设置（与 SettingsRepository 共用 padmouse_settings 文件）。
 *
 * 放在本文件而非 SettingsRepository，避免超出本轮影响范围。
 *
 * @param enabled 总开关
 * @param intensity 强度百分比（0 / 25 / 50 / 75 / 100）；0 等价关闭
 * @param clickEnabled 点击震动（左键 / 右键）
 * @param modeToggleEnabled 模式切换震动
 * @param screenshotEnabled 截图震动
 */
data class VibrationSettings(
    val enabled: Boolean = true,
    val intensity: Int = 100,
    val clickEnabled: Boolean = true,
    val modeToggleEnabled: Boolean = true,
    val screenshotEnabled: Boolean = true,
) {
    /** VibrationEffect amplitude（0..255）；0 表示不震。 */
    val amplitude: Int
        get() = (MAX_AMPLITUDE * intensity / 100).coerceIn(0, MAX_AMPLITUDE)

    companion object {
        /** VibrationEffect 的 amplitude 合法上界；不要用 DEFAULT_AMPLITUDE（可能为 -1）。 */
        const val MAX_AMPLITUDE = 255

        /** 滑杆 0..4 档对应的百分比。 */
        val INTENSITY_STEPS = intArrayOf(0, 25, 50, 75, 100)
    }
}

private const val VIBRATION_PREFS = "padmouse_settings"
private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
private const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
private const val KEY_VIBRATION_CLICK = "vibration_click"
private const val KEY_VIBRATION_MODE_TOGGLE = "vibration_mode_toggle"
private const val KEY_VIBRATION_SCREENSHOT = "vibration_screenshot"

/** 读取 M11 震动设置；缺失/损坏的值按默认值兜底。 */
fun loadVibrationSettings(context: Context): VibrationSettings {
    val prefs = context.applicationContext
        .getSharedPreferences(VIBRATION_PREFS, Context.MODE_PRIVATE)
    return VibrationSettings(
        enabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true),
        intensity = prefs.getInt(KEY_VIBRATION_INTENSITY, 100).coerceIn(0, 100),
        clickEnabled = prefs.getBoolean(KEY_VIBRATION_CLICK, true),
        modeToggleEnabled = prefs.getBoolean(KEY_VIBRATION_MODE_TOGGLE, true),
        screenshotEnabled = prefs.getBoolean(KEY_VIBRATION_SCREENSHOT, true),
    )
}

/** 保存 M11 震动设置。 */
fun saveVibrationSettings(context: Context, settings: VibrationSettings) {
    context.applicationContext
        .getSharedPreferences(VIBRATION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_VIBRATION_ENABLED, settings.enabled)
        .putInt(KEY_VIBRATION_INTENSITY, settings.intensity.coerceIn(0, 100))
        .putBoolean(KEY_VIBRATION_CLICK, settings.clickEnabled)
        .putBoolean(KEY_VIBRATION_MODE_TOGGLE, settings.modeToggleEnabled)
        .putBoolean(KEY_VIBRATION_SCREENSHOT, settings.screenshotEnabled)
        .apply()
}

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

    /** M13：手柄 FF_RUMBLE 走独立线程，震动 Binder 调用不阻塞注入/主线程。 */
    private val rumbleExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gamepad-rumble").apply { isDaemon = true }
    }

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

    // ---------------------------------------------------------------- M12 输入后端

    /** 用户设置：UHID 鼠标 / 触摸注入。 */
    private var inputMode = InputMode.UHID

    /** 实际生效的后端：UHID 创建失败时会临时回退触摸注入。 */
    private var effectiveInputMode = InputMode.TOUCH

    /** 已应用的后端；避免每次推手感配置都重复创建 /dev/uhid 设备。 */
    private var appliedInputMode: InputMode? = null

    // ---------------------------------------------------------------- M10 独占 / 模式

    /** 游戏模式：设备已交还系统，不抓取、不映射、不注入。 */
    private var inGameMode = false

    /** 进入游戏模式前的光标位置，切回鼠标模式时原位恢复。 */
    private var savedCursorX = 0f
    private var savedCursorY = 0f

    /** 游戏模式下窗口 alpha 归零，避免残留光标。 */
    private var overlayHidden = false

    /** 手柄震动不可用提示的节流时间戳。 */
    private var lastHapticsToastAt = 0L

    /** 模式切换热键绑定（缓存，避免每个按键事件都读 SharedPreferences）。 */
    private var modeToggleBinding: ActionBinding = SettingsRepository.DEFAULT_MODE_TOGGLE_BINDING

    /** 长按判定：按下后延时触发，松开取消（短按不动作）。 */
    private val modeToggleRunnable = Runnable { toggleMouseMode() }

    /** M11 震动设置缓存；设置页修改后经 ACTION_UPDATE_VIBRATION 立即生效。 */
    private var vibrationSettings = VibrationSettings()

    /** 独占状态轮询；Binder 查询放到注入线程，避免阻塞主线程。 */
    private val exclusiveStatusPoller = object : Runnable {
        override fun run() {
            refreshExclusiveStatus()
            refreshHapticsStatus()
            handler.postDelayed(this, EXCLUSIVE_POLL_MS)
        }
    }

    /** 窗口级透明度动画；同时决定输入使用的 opacity。 */
    private var windowAlphaAnimator: ValueAnimator? = null

    private val idleHideRunnable = Runnable {
        // 静止超时：窗口 alpha 归零（不是 View alpha），光标完全消失且不拦截触摸。
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
            syncOverlayForForeground()
        }

        override fun onVector(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
            MouseRuntime.setVector(leftX, leftY, rightX, rightY)
        }

        override fun onTriggerChord() {
            toggleCursorDisplay()
        }

        override fun onAction(action: MapperAction, atMillis: Long) {
            MouseRuntime.setAction(action, atMillis)
            // M11：点击震动（左键 / 右键），受分项开关控制。
            when (action) {
                MapperAction.TAP,
                MapperAction.RIGHT_CLICK -> vibrateOnce(VibrationKind.CLICK, VIBRATE_CLICK_MS)

                else -> Unit
            }
        }
    }

    /** 注入实现；所有调用串行化，避免手势事件乱序。 */
    private val mapperInjector = object : Mapper.Injector {
        override fun tap(x: Float, y: Float) = dispatchInjection {
            Log.d(TAG, "INJECT tap($x,$y)")
            serviceManager.tap(x, y)
        }

        override fun rightClick(x: Float, y: Float) = dispatchInjection {
            Log.d(TAG, "INJECT rightClick($x,$y)")
            serviceManager.rightClick(x, y)
        }

        override fun middleClick(x: Float, y: Float) = dispatchInjection {
            Log.d(TAG, "INJECT middleClick($x,$y)")
            serviceManager.middleClick(x, y)
        }

        override fun touchDown(x: Float, y: Float) = dispatchInjection {
            Log.d(TAG, "INJECT touchDown($x,$y)")
            serviceManager.down(x, y)
        }

        override fun touchMove(x: Float, y: Float) = dispatchInjection { serviceManager.move(x, y) }

        override fun touchUp(x: Float, y: Float) = dispatchInjection {
            Log.d(TAG, "INJECT touchUp($x,$y)")
            serviceManager.up(x, y)
        }

        override fun mouseDown(x: Float, y: Float, button: Int) = dispatchInjection {
            Log.d(TAG, "INJECT mouseDown($x,$y,btn=$button)")
            serviceManager.mouseDown(x, y, button)
        }

        override fun mouseUp(x: Float, y: Float, button: Int) = dispatchInjection {
            Log.d(TAG, "INJECT mouseUp($x,$y,btn=$button)")
            serviceManager.mouseUp(x, y, button)
        }

        override fun mouseMove(x: Float, y: Float, buttonState: Int) =
            dispatchInjection { serviceManager.mouseMove(x, y, buttonState) }

        override fun scroll(x: Float, y: Float, h: Float, v: Float) =
            dispatchInjection { serviceManager.scroll(x, y, h, v) }

        // M12 UHID 后端：全部落到 UserService（shell 进程）的 /dev/uhid 写入。
        override fun uhidMove(dx: Int, dy: Int) = dispatchInjection {
            uhidBinder()?.let { binder ->
                runCatching { binder.uhidMouseMove(dx, dy) }
                    .onFailure { Log.d(TAG, "UHID move 失败", it) }
            }
        }

        override fun uhidButton(mask: Int) = dispatchInjection {
            uhidBinder()?.let { binder ->
                runCatching { binder.uhidMouseButton(mask) }
                    .onFailure { Log.d(TAG, "UHID button 失败", it) }
            }
        }

        override fun uhidWheel(delta: Int) = dispatchInjection {
            uhidBinder()?.let { binder ->
                runCatching { binder.uhidMouseWheel(delta) }
                    .onFailure { Log.d(TAG, "UHID wheel 失败", it) }
            }
        }

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
            postSystemActionFeedback(action, result)
        }
    }

    /** 系统动作结果反馈：截图成功/失败 Toast + 震动，亮度变化成功震动。 */
    private fun postSystemActionFeedback(action: SystemAction, result: SystemActions.Result) {
        handler.post {
            when (action) {
                SystemAction.SCREENSHOT -> {
                    if (result.success) {
                        vibrateOnce(VibrationKind.SCREENSHOT, VIBRATE_SCREENSHOT_MS)
                        Toast.makeText(
                            this@MouseService,
                            R.string.toast_screenshot_ok,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val reason = result.failureReason
                            ?: getString(R.string.toast_screenshot_failed_unknown)
                        Toast.makeText(
                            this@MouseService,
                            getString(R.string.toast_screenshot_failed, reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                SystemAction.BRIGHTNESS_UP, SystemAction.BRIGHTNESS_DOWN -> {
                    if (result.success) vibrateOnce(VibrationKind.BRIGHTNESS, VIBRATE_BRIGHTNESS_MS)
                }

                else -> Unit
            }
        }
    }

    /** 手柄事件：UserService（shell 侧）经 Binder 回传，统一切回服务主线程。 */
    private val gamepadListener = object : IGamepadListener.Stub() {
        override fun onEvent(type: Int, code: Int, value: Int) {
            if (type != EV_KEY) return
            MouseRuntime.addKeyEvent(code, value, SystemClock.uptimeMillis())
            handler.post {
                // 任意按键事件都立即恢复光标可见（静止 3 秒后是完全透明）。
                showCursorNow()
                // 模式切换热键优先消费：游戏模式下映射已停，仍能靠这里切回。
                if (handleModeToggleKey(code, value)) return@post
                if (mapper.isRunning) mapper.onKey(code, value)
            }
        }

        override fun onAxis(code: Int, normalized: Float) {
            handler.post {
                // 摇杆/扳机/十字键任一轴事件都立即恢复光标可见。
                showCursorNow()
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
        vibrationSettings = loadVibrationSettings(this)
        modeToggleBinding = settingsRepository.loadModeToggleBinding()
        inputMode = settingsRepository.loadInputMode()
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

            ACTION_UPDATE_EXCLUSIVE -> {
                applyExclusiveSetting()
                return START_NOT_STICKY
            }

            ACTION_UPDATE_VIBRATION -> {
                // 改动立即生效：重新读持久化设置，下次触发即使用新参数，无需重启服务/重连手柄。
                vibrationSettings = loadVibrationSettings(this)
                return START_NOT_STICKY
            }

            ACTION_UPDATE_INPUT_MODE -> {
                // 输入后端切换：UHID 需要创建/销毁 /dev/uhid 设备，触摸模式恢复自绘光标。
                if (cursorView != null) applyInputBackend(settingsRepository.loadInputMode(), force = true)
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
        // 每次启动都从鼠标模式开始：设备重新抓取。
        if (inGameMode) {
            inGameMode = false
            MouseService.setGameModeState(false)
        }
        // UserService 进程是 daemon，模式状态会跨服务重启保留；启动时强制回到鼠标模式。
        runCatching { serviceManager.setMouseMode(true) }
        // M12：按用户设置创建 UHID 或恢复自绘光标；force 保证每次都重新对齐。
        inputMode = settingsRepository.loadInputMode()
        appliedInputMode = null
        applyInputBackend(inputMode, force = true)
        if (!startGamepadCapture()) {
            MouseRuntime.setStage(
                RunStage.FAILED,
                getString(R.string.run_detail_gamepad_failed)
            )
            return
        }

        refreshScreenBounds()
        handler.removeCallbacks(exclusiveStatusPoller)
        handler.postDelayed(exclusiveStatusPoller, EXCLUSIVE_POLL_MS)
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
            // UserService 是 shell uid 的 app_process，java.library.path 不含 APK lib 目录；
            // 把 nativeLibraryDir / codeCacheDir / APK 路径传过去，供显式 System.load 原生 ioctl 库
            // （APK 为 extractNativeLibs=false，必要时从 APK 解压到 codeCacheDir）。
            runCatching {
                serviceManager.setNativeLibPaths(
                    applicationInfo.nativeLibraryDir ?: "",
                    codeCacheDir.absolutePath,
                    applicationInfo.sourceDir ?: ""
                )
            }
            // 先下发独占开关：UserService 用该配置创建读取器（打开即 EVIOCGRAB）。
            runCatching { serviceManager.setExclusiveMode(settingsRepository.loadExclusiveMode()) }
            modeToggleBinding = settingsRepository.loadModeToggleBinding()
            serviceManager.startGamepadCapture(gamepadListener)
            gamepadCaptureStarted = true
            refreshHapticsStatus()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "注册手柄监听失败", t)
            gamepadCaptureStarted = false
            false
        }
    }

    /** 停映射与手柄监听；服务本身可以继续存活等待重连。 */
    private fun stopMappingEngine() {
        handler.removeCallbacks(modeToggleRunnable)
        handler.removeCallbacks(exclusiveStatusPoller)
        // M12：先销毁虚拟鼠标，系统输入层不残留设备。
        destroyUhidDevice()
        appliedInputMode = null
        MouseService.setUhidState(false, "")
        if (gamepadCaptureStarted) {
            // reader.stop() 内部对每个 fd 先 EVIOCGRAB 0 再关闭，系统立即恢复接收。
            runCatching { serviceManager.stopGamepadCapture() }
            gamepadCaptureStarted = false
        }
        if (::mapper.isInitialized && mapper.isRunning) mapper.stop()
        MouseRuntime.mappingRunning = false
        inGameMode = false
        // UHID 模式下即使连接暂时中断也不显示自绘箭头，避免和系统光标重叠；
        // 触摸模式（或已回退）才恢复自绘光标。
        if (effectiveInputMode == InputMode.TOUCH) setOverlayVisible(true)
        MouseService.setGameModeState(false)
        MouseService.setExclusiveState(false, null)
        MouseService.setHapticsState(null)
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
        modeToggleBinding = settingsRepository.loadModeToggleBinding()
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

    // ---------------------------------------------------------------- M12 UHID 后端

    /**
     * 取回 IPadMouse binder。
     *
     * 本轮影响范围未包含 ShizukuServiceManager.kt，这里按字段类型从管理器取回
     * 唯一一个 IPadMouse 类型的字段（即当前 UserService 代理），避免修改范围外文件。
     * 未连接时返回 null，调用方静默跳过。
     */
    private val uhidBinderField by lazy {
        ShizukuServiceManager::class.java.declaredFields
            .firstOrNull { IPadMouse::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
    }

    private fun uhidBinder(): IPadMouse? = runCatching {
        uhidBinderField?.get(serviceManager) as? IPadMouse
    }.getOrNull()

    private fun createUhidDevice(uniq: String = ""): String {
        Log.i(TAG, "createUhidDevice uniq=[$uniq] bus=${if (uniq.isEmpty()) "VIRTUAL" else "USB"}")
        val binder = uhidBinder() ?: return "ERROR: UserService 未连接"
        // AIDL 返回值是平台类型（String!），实际可能为 null；必须显式兜底，
        // 否则后续 status.startsWith("ERROR") 会抛 NPE 让整个服务启动崩溃。
        val status = runCatching { binder.createUhidMouse(uniq) }.getOrNull()
        if (status.isNullOrBlank()) return "ERROR: UHID 创建未返回状态（UserService 未就绪）"
        return status
    }

    private fun destroyUhidDevice() {
        uhidBinder()?.let { binder ->
            runCatching { binder.destroyUhidMouse() }
                .onFailure { Log.d(TAG, "销毁 UHID 设备失败（可能已断开）", it) }
        }
    }

    // ---------------------------------------------------------------- 主屏 / 副屏切换

    /** 光标当前是否在副屏（决定哪个 UHID 设备在发事件）。 */
    private var onSecondary = false

    /**
     * LT + RT 同时按下 → 在主屏与副屏之间切换光标。
     *
     * UHID 设备的 DisplayId 在创建时由 uniq 决定，运行时改不了，
     * 所以这里销毁设备后用新的 uniq 重建。
     */
    private fun toggleCursorDisplay() {
        if (inputMode != InputMode.UHID) return
        val binder = uhidBinder() ?: return

        if (!onSecondary) {
            // 主屏 → 副屏：确保副屏设备已创建（启动时没插眼镜则此刻补建）。
            if (!ensureSecondaryUhid(binder)) {
                // 切屏失败时把实际探测到的屏幕信息直接弹出来，方便用户不看 adb 也能反馈。
                val diag = runCatching { binder.displayDiagnostics() }.getOrNull().orEmpty()
                Log.w(TAG, "切屏失败，屏幕诊断:\n$diag")
                handler.post {
                    Toast.makeText(this, "未检测到副屏\n$diag", Toast.LENGTH_LONG).show()
                }
                return
            }
            onSecondary = true
            runCatching { binder.setActiveUhid(1) }
        } else {
            onSecondary = false
            runCatching { binder.setActiveUhid(0) }
        }
        Log.i(TAG, "光标切屏 → ${if (onSecondary) "副屏" else "主屏"}")
        handler.post {
            Toast.makeText(
                this,
                if (onSecondary) R.string.cursor_display_secondary else R.string.cursor_display_primary,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 确保副屏 UHID 设备已建好（主屏设备 + 副屏设备同时存在）。
     *
     * 两个设备只在启动时创建一次；切换只改活跃指针，不销毁重建，
     * 避免 descriptor 相同导致系统复用旧的显示关联。
     */
    private fun ensureSecondaryUhid(binder: IPadMouse): Boolean {
        val status = runCatching { binder.uhidStatus() }.getOrNull().orEmpty()
        if (uhidSecondaryReady(binder)) return true
        val uniq = findExternalDisplayUniqueId() ?: return false
        // 重建两个设备：createUhidMouse 内部会同时创建主屏与副屏。
        destroyUhidDevice()
        appliedInputMode = null
        applyInputBackend(InputMode.UHID, force = true)
        return uhidSecondaryReady(binder)
    }

    /** 副屏设备是否就绪（以 UserService 的实际日志状态为准）。 */
    private fun uhidSecondaryReady(binder: IPadMouse): Boolean =
        runCatching { binder.secondaryUhidReady() }.getOrDefault(false)

    /** 查外接屏的 uniqueId（交给 UserService：hidden API 在 shell 进程里可用）。 */
    private fun findExternalDisplayUniqueId(): String? {
        val binder = uhidBinder() ?: return null
        val uniq = runCatching { binder.externalDisplayUniqueId() }.getOrNull()
        Log.i(TAG, "外接屏 uniqueId=$uniq")
        return uniq?.takeIf { it.isNotBlank() }
    }

    /**
     * 应用输入后端：
     * - UHID：创建 /dev/uhid 设备、隐藏自绘光标、让 Mapper 走相对位移；
     *   创建失败时回退触摸注入并保留错误状态，保证至少还能用。
     * - TOUCH：销毁 UHID 设备、恢复自绘光标。
     */
    private fun applyInputBackend(mode: InputMode, force: Boolean = false) {
        inputMode = mode
        if (!force && appliedInputMode == mode) return
        if (!serviceManager.isReady) {
            appliedInputMode = null
            return
        }
        appliedInputMode = mode

        if (mode == InputMode.UHID) {
            // 不要用 create() 的返回值判定成败：AIDL 返回值是平台类型，
            // UserService 侧可能已成功创建但返回空串，会导致「设备存在却判定失败」，
            // 于是触摸注入继续跑、自绘光标不隐藏，出现两个输入源打架（乱点击 + 滚轮失效）。
            // 一律以 UserService 里的实际设备状态为准。
            // createUhidMouse 内部同时建主屏 + 副屏两个设备；这里把副屏 uniq 传下去。
            val created = createUhidDevice(findExternalDisplayUniqueId() ?: "")
            val actual = runCatching { uhidBinder()?.uhidStatus() }.getOrNull().orEmpty().trim()
            val ok = actual.startsWith("active=true")
            Log.i(TAG, "applyInputBackend UHID: create=[$created] actual=[$actual] ok=$ok")
            if (ok) {
                effectiveInputMode = InputMode.UHID
                if (::mapper.isInitialized) mapper.updateInputMode(InputMode.UHID)
                setOverlayVisible(false)
                MouseService.setUhidState(true, actual)
                Log.i(TAG, "UHID 虚拟鼠标就绪: $actual")
                // 恢复切屏前的活跃设备（两个设备都已创建）。
                runCatching { uhidBinder()?.setActiveUhid(if (onSecondary) 1 else 0) }
                // 设备刚就绪时发一次 1px 的往返移动。
                // 系统（尤其 HyperOS 的 PointerChoreographer）要收到一次真实的指针事件
                // 才会为这个设备建立光标状态；否则首次切到微信/微信读书时光标不出现，
                // 用户必须下拉通知栏"唤"一次才行。
                handler.postDelayed({
                    runCatching {
                        mapperInjector.uhidMove(1, 0)
                        mapperInjector.uhidMove(-1, 0)
                    }.onFailure { Log.w(TAG, "UHID 预热移动失败", it) }
                }, 200)
            } else {
                val reason = when {
                    actual.isNotEmpty() -> actual
                    created.isNotEmpty() -> created
                    else -> "UHID 状态未知"
                }
                effectiveInputMode = InputMode.TOUCH
                if (::mapper.isInitialized) mapper.updateInputMode(InputMode.TOUCH)
                setOverlayVisible(true)
                MouseService.setUhidState(false, reason)
                MouseRuntime.setStage(
                    RunStage.RUNNING,
                    getString(R.string.run_detail_uhid_fallback, reason)
                )
                Log.w(TAG, "UHID 创建失败，回退触摸注入: $reason")
            }
        } else {
            destroyUhidDevice()
            effectiveInputMode = InputMode.TOUCH
            if (::mapper.isInitialized) mapper.updateInputMode(InputMode.TOUCH)
            setOverlayVisible(true)
            MouseService.setUhidState(false, "")
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
            // UHID 模式下用系统光标，自绘窗口一开始就不挂载，避免出现两个光标。
            if (settingsRepository.loadInputMode() == InputMode.UHID) {
                Log.d(TAG, "addOverlay：UHID 模式，跳过自绘光标挂载")
            } else {
                windowManager.addView(view, params)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "添加光标悬浮窗失败", t)
            MouseRuntime.setStage(RunStage.FAILED, getString(R.string.run_detail_overlay_missing))
            stopEverything()
            return
        }

        cursorView = view
        layoutParams = params
        // 以「窗口是否真的挂上了」为准，而不是无条件 true：
        // UHID 模式下我们跳过了 addView，标志必须同步为 false。
        overlayAdded = view.isAttachedToWindow
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
        // 游戏模式：光标隐藏且不注入，忽略任何移动请求。
        if (overlayHidden) return
        applyPosition(x, y, updateLayout = true)
        showCursorNow()
    }

    private fun showCursorNow() {
        if (cursorView == null) return
        if (overlayHidden) return
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

    // ---------------------------------------------------------------- M10 独占 / 模式切换

    /**
     * 模式切换热键：命中绑定且按下时开始 [SettingsRepository.MODE_TOGGLE_LONG_PRESS_MS] 计时，
     * 松开取消（短按不动作）。返回 true 表示事件已被消费，不再交给映射引擎。
     */
    private fun handleModeToggleKey(code: Int, value: Int): Boolean {
        val binding = modeToggleBinding
        if (binding.direction != 0 || binding.isChord || binding.code != code) return false
        when (value) {
            1 -> {
                handler.removeCallbacks(modeToggleRunnable)
                handler.postDelayed(modeToggleRunnable, SettingsRepository.MODE_TOGGLE_LONG_PRESS_MS)
            }

            0 -> handler.removeCallbacks(modeToggleRunnable)
        }
        return true
    }

    private fun toggleMouseMode() {
        if (cursorView == null) return
        if (inGameMode) leaveGameMode() else enterGameMode()
    }

    /** 鼠标模式 → 游戏模式：释放抓取、停映射、隐藏光标，不注入任何事件。 */
    private fun enterGameMode() {
        if (inGameMode) return
        inGameMode = true
        savedCursorX = cursorX
        savedCursorY = cursorY
        // 切换动作发生时立即震动；后续 mapper.stop() 不会取消已提交的震动。
        vibrateOnce(VibrationKind.MODE_TOGGLE)
        if (::mapper.isInitialized && mapper.isRunning) mapper.stop()
        MouseRuntime.mappingRunning = false
        // 只 ungrab，不关闭 fd：热键仍能读到来切回。
        runCatching { serviceManager.setMouseMode(false) }
        // M12：游戏模式释放 UHID 设备，避免虚拟鼠标继续占用系统输入。
        destroyUhidDevice()
        appliedInputMode = null
        effectiveInputMode = InputMode.TOUCH
        MouseService.setUhidState(false, "game mode")
        setOverlayVisible(false)
        MouseService.setGameModeState(true)
        MouseRuntime.notifyChanged()
        refreshExclusiveStatus()
    }

    /** 游戏模式 → 鼠标模式：重新 EVIOCGRAB，映射与光标回到切换前的位置。 */
    private fun leaveGameMode() {
        if (!inGameMode) return
        inGameMode = false
        vibrateOnce(VibrationKind.MODE_TOGGLE)
        runCatching { serviceManager.setMouseMode(true) }
        // M12：按设置重新创建 UHID（或恢复自绘光标），再恢复光标位置。
        appliedInputMode = null
        applyInputBackend(inputMode, force = true)
        applyPosition(savedCursorX, savedCursorY, updateLayout = true)
        if (::mapper.isInitialized && !mapper.isRunning && cursorView != null) {
            mapper.updateConfig(currentConfig())
            mapper.start(screenWidth, screenHeight)
        }
        MouseRuntime.mappingRunning = ::mapper.isInitialized && mapper.isRunning
        MouseService.setGameModeState(false)
        MouseRuntime.notifyChanged()
        refreshExclusiveStatus()
    }

    /** 游戏模式隐藏光标：窗口 alpha 归零（保留视图），切回时原位淡入。 */
    /** UHID 模式下系统可能不画指针，切到其它 App 时用自绘光标兜底。 */
    private var overlayForOtherApp = false

    private fun syncOverlayForForeground() {
        // 只要不是 UHID 后端，自绘光标本来就是主光标，不需要切换。
        if (inputMode != InputMode.UHID) return
        val inOtherApp = !MouseRuntime.appForeground
        if (inOtherApp == overlayForOtherApp) return
        overlayForOtherApp = inOtherApp
        if (!inOtherApp) return
        // 刚切到别的 App 时，HyperOS 的 PointerChoreographer 会丢掉光标状态
        // （日志：Invalid device id -1），表现为光标消失、必须下拉状态栏才回来。
        // 这里发一次 1px 往返移动，效果等同于用户下拉状态栏的那次唤醒，
        // 但不会画自绘光标，用户只会看到光标"闪一下就出现"。
        runCatching {
            mapperInjector.uhidMove(1, 0)
            mapperInjector.uhidMove(-1, 0)
        }.onFailure { Log.w(TAG, "切换 App 预热移动失败", it) }
    }

    private fun setOverlayVisible(visible: Boolean, autoHide: Boolean = true) {
        overlayHidden = !visible
        val params = layoutParams ?: return
        val view = cursorView ?: return
        windowAlphaAnimator?.cancel()
        windowAlphaAnimator = null
        if (visible) {
            // UHID 模式下窗口是被真正移除的，切回触摸模式时要重新挂上。
            if (!overlayAdded) {
                runCatching {
                    windowManager.addView(view, params)
                    overlayAdded = true
                }.onFailure { Log.w(TAG, "重新添加光标窗口失败", it) }
            }
            params.alpha = ACTIVE_WINDOW_ALPHA
            // autoHide=false：切换 App 的兜底光标不能静止 3 秒就消失，
            // 否则用户在其他 App 里会看不到指针。
            if (autoHide) scheduleIdleHide() else handler.removeCallbacks(idleHideRunnable)
            if (overlayAdded && view.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        } else {
            // 彻底移除窗口，而不是把 alpha 设为 0：
            // alpha=0 的窗口会被后续的淡入/移图逻辑重新点亮，导致 UHID 模式下
            // 系统光标和自绘光标同时出现。
            // 这里不看 overlayAdded 标志——它可能与实际窗口状态不同步（实测窗口还在但标志已 false）。
            handler.removeCallbacks(idleHideRunnable)
            runCatching { windowManager.removeView(view) }
                .onFailure { Log.d(TAG, "移除自绘光标失败（可能本就未挂载）", it) }
            overlayAdded = false
        }
    }

    /** M11 震动类型；对应设置页的分项开关。 */
    private enum class VibrationKind { CLICK, MODE_TOGGLE, SCREENSHOT, BRIGHTNESS }

    /**
     * 按当前震动设置触发一次震动。
     *
     * M13：默认优先手柄（UserService 内 FF_RUMBLE 直接写 /dev/input/eventX）；
     * 手柄不可用/ioctl 失败时才回退手机马达（M11 实现保留为回退）。
     * 震动在专用线程执行，不阻塞注入线程。
     */
    private fun vibrateOnce(kind: VibrationKind, durationMs: Long = VIBRATE_MS) {
        // 震动功能已整体移除。
        //
        // 原因：手柄 FF_RUMBLE 走 EVIOCSFF 被 SELinux 拒绝（errno=EPERM），
        // 每次触发都会回退手机马达并弹「手柄震动不可用」Toast，5 秒一条，
        // 持续骚扰用户。产品决定不做震动，这里直接短路，
        // 不再下发 rumble、不再回退手机震动、也不再弹提示。
        return
    }

    /** 手机马达回退（M11 实现）：仅在无手柄或 FF 下发失败时使用。 */
    private fun vibratePhone(durationMs: Long = VIBRATE_MS) {
        val settings = vibrationSettings
        if (!settings.enabled || settings.amplitude <= 0) return
        val vibrator = resolveVibrator()
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG_VIBRATE, "没有可用的震动马达，跳过手机回退")
            return
        }
        // 注意：VibrationEffect.DEFAULT_AMPLITUDE 在部分 android.jar 中为 -1，
        // 不能当作上界（coerceIn 在 max < min 时会抛 IllegalArgumentException）。
        // amplitude 的合法范围固定为 1..255（VibrationSettings.MAX_AMPLITUDE）。
        val amplitude = settings.amplitude.coerceIn(1, VibrationSettings.MAX_AMPLITUDE)
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } catch (t: Throwable) {
            Log.w(TAG_VIBRATE, "amplitude 震动失败，降级到 vibrate(long)", t)
            try {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            } catch (t2: Throwable) {
                Log.w(TAG_VIBRATE, "震动失败", t2)
            }
        }
    }

    /** 刷新手柄震动可用性（界面上报「手柄震动不可用：<原因>」）。 */
    private fun refreshHapticsStatus() {
        if (!serviceManager.isReady) {
            MouseService.setHapticsState(getString(R.string.haptics_unavailable_service))
            return
        }
        if (rumbleExecutor.isShutdown) return
        rumbleExecutor.execute {
            val status = runCatching { serviceManager.hapticsStatus() }.getOrNull()
            MouseService.setHapticsState(status)
        }
    }

    /** API 31+ 用 VibratorManager.defaultVibrator，失败/为空回退 Vibrator 服务。 */
    private fun resolveVibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = runCatching { getSystemService(VibratorManager::class.java) }.getOrNull()
            val default = runCatching { manager?.defaultVibrator }.getOrNull()
            if (default != null) return default
            Log.w(TAG_VIBRATE, "VibratorManager.defaultVibrator 为空，回退 Vibrator 服务")
        }
        return runCatching { getSystemService(Vibrator::class.java) }.getOrNull()
    }

    /** 拉取独占状态（Binder 调用放注入线程），界面据此显示「独占中/旁听中/独占失败」。 */
    private fun refreshExclusiveStatus() {
        if (!serviceManager.isReady) {
            MouseService.setExclusiveState(false, null)
            return
        }
        dispatchInjection {
            val active = runCatching { serviceManager.isExclusiveActive() }.getOrDefault(false)
            val error = runCatching { serviceManager.getExclusiveError() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            handler.post { MouseService.setExclusiveState(active, error) }
        }
    }

    /** 独占开关变化：按新配置重建读取器（先 ungrab 关闭旧 fd，再重开）。 */
    private fun applyExclusiveSetting() {
        modeToggleBinding = settingsRepository.loadModeToggleBinding()
        if (!gamepadCaptureStarted || !serviceManager.isReady) return
        runCatching { serviceManager.setExclusiveMode(settingsRepository.loadExclusiveMode()) }
        refreshExclusiveStatus()
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
        const val ACTION_UPDATE_EXCLUSIVE = "com.fseven.padmouse.action.UPDATE_EXCLUSIVE"
        const val ACTION_UPDATE_VIBRATION = "com.fseven.padmouse.action.UPDATE_VIBRATION"
        const val ACTION_UPDATE_INPUT_MODE = "com.fseven.padmouse.action.UPDATE_INPUT_MODE"
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

        /** M11：静止后窗口完全透明（不是 View alpha），用户要求直接消失。 */
        private const val IDLE_WINDOW_ALPHA = 0f
        private const val FADE_IN_MS = 120L
        private const val FADE_OUT_MS = 200L
        private const val PERMISSION_CHECK_INTERVAL_MS = 2000L
        private const val ENGINE_RETRY_MS = 250L
        private const val GAMEPAD_WARMUP_MS = 400L
        private const val MAX_ENGINE_START_ATTEMPTS = 24
        private const val REBIND_DELAY_MS = 1000L
        private const val MAX_REBIND_ATTEMPTS = 5
        private const val EXCLUSIVE_POLL_MS = 1_500L

        /** 震动日志统一用 TAG=Vibrator，方便真机排查。 */
        private const val TAG_VIBRATE = "Vibrator"

        /** M11/M13：模式切换 180ms 才能明显感到；点击 40ms；截图 60ms。 */
        private const val VIBRATE_MS = 180L
        private const val VIBRATE_CLICK_MS = 40L
        private const val VIBRATE_SCREENSHOT_MS = 60L
        private const val VIBRATE_BRIGHTNESS_MS = 40L

        /** FF_RUMBLE strong/weak_magnitude 的满量程。 */
        private const val MAX_FF_STRENGTH = 0xFFFF

        /** 「手柄震动不可用」Toast 去重间隔。 */
        private const val HAPTICS_TOAST_INTERVAL_MS = 5_000L

        /**
         * 震动能力查询：设置页据此决定是否置灰「手柄震动」区块。
         * API 31+ 先看 VibratorManager.defaultVibrator，失败回退 Vibrator 服务。
         */
        fun isVibrationSupported(context: Context): Boolean {
            // M13：手柄 FF_RUMBLE 是首选通道。服务已在上报不可用原因，或此刻能直接查到
            // 不可用原因时，设置区置灰（避免只在「服务已运行很久」时才生效）。
            val reason = hapticsUnavailableReason ?: runCatching {
                val manager = MouseRuntime.ensureManager(context)
                if (!manager.isReady) null
                else manager.hapticsStatus().takeIf { it.isNotBlank() }
            }.getOrNull()
            if (reason != null) {
                setHapticsState(reason)
                return false
            }
            val fromManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                }.getOrNull()
            } else {
                null
            }
            val vibrator = fromManager
                ?: runCatching { context.getSystemService(Vibrator::class.java) }.getOrNull()
            return vibrator?.hasVibrator() == true
        }

        /** 服务/光标是否在运行，供主界面同步开关状态。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 至少一台设备已被 EVIOCGRAB 独占。 */
        @Volatile
        var exclusiveActive: Boolean = false
            private set

        /** 独占失败原因；成功或未启用时为 null。 */
        @Volatile
        var exclusiveError: String? = null
            private set

        /** 当前是否处于游戏模式（设备已交还系统）。 */
        @Volatile
        var gameMode: Boolean = false
            private set

        /** M12：UHID 虚拟鼠标是否已创建并注册到系统。 */
        @Volatile
        var uhidActive: Boolean = false
            private set

        /** M12：UHID 状态/错误串，供设置页展示。 */
        @Volatile
        var uhidStatus: String = ""
            private set

        fun setUhidState(active: Boolean, status: String) {
            uhidActive = active
            uhidStatus = status
            MouseRuntime.notifyChanged()
        }

        fun setExclusiveState(active: Boolean, error: String?) {
            exclusiveActive = active
            exclusiveError = error
        }

        /** M13：手柄震动不可用原因；null = 可用。 */
        @Volatile
        var hapticsUnavailableReason: String? = null
            private set

        fun setHapticsState(reason: String?) {
            val value = reason?.takeIf { it.isNotBlank() }
            if (value == hapticsUnavailableReason) return
            hapticsUnavailableReason = value
            MouseRuntime.notifyChanged()
        }

        fun setGameModeState(enabled: Boolean) {
            gameMode = enabled
        }

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
