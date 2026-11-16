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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * M4 光标悬浮窗前台服务。
 *
 * - 用 TYPE_APPLICATION_OVERLAY 创建不可触摸、不可聚焦的悬浮窗，自绘鼠标箭头；
 * - 通过 [moveTo]/[moveBy] 更新窗口位置，坐标钳制在屏幕范围内；
 * - 静止超时后把 alpha 降到 0.15，下一次移动立即恢复；
 * - 服务销毁时移除窗口，重复移除有异常保护。
 */
class MouseService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

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
                stopSelfSafely()
                return
            }
            handler.postDelayed(this, PERMISSION_CHECK_INTERVAL_MS)
        }
    }

    inner class LocalBinder : Binder() {
        fun moveBy(dx: Float, dy: Float) = this@MouseService.moveBy(dx, dy)
        fun moveTo(x: Float, y: Float) = this@MouseService.moveTo(x, y)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        ensureForeground()
        runPermissionSelfCheck()

        val sizeDp = intent?.getIntExtra(EXTRA_SIZE_DP, cursorSizeDp) ?: cursorSizeDp
        val color = intent?.getIntExtra(EXTRA_COLOR, cursorColor) ?: cursorColor
        val timeoutMs = intent?.getLongExtra(EXTRA_IDLE_TIMEOUT_MS, idleTimeoutMs) ?: idleTimeoutMs
        showCursor(sizeDp, color, timeoutMs)
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
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        instance = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 光标窗口

    private fun showCursor(sizeDp: Int, color: Int, timeoutMs: Long) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "缺少悬浮窗权限，无法显示光标")
            stopSelfSafely()
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
            stopSelfSafely()
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

    /**
     * 移动到屏幕坐标（光标尖端）。可从任意线程调用。
     */
    fun moveTo(x: Float, y: Float) {
        handler.post { moveToInternal(x, y) }
    }

    /**
     * 相对移动。可从任意线程调用。
     */
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

    private fun stopSelfSafely() {
        handler.removeCallbacksAndMessages(null)
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

        /** 激活态窗口透明度必须 <= 0.8，否则 Android 12+ 会拦截穿透触摸。 */
        private const val ACTIVE_WINDOW_ALPHA = 0.79f
        private const val IDLE_WINDOW_ALPHA = 0.15f
        private const val FADE_IN_MS = 120L
        private const val FADE_OUT_MS = 200L
        private const val PERMISSION_CHECK_INTERVAL_MS = 2000L

        /** 服务/光标是否在运行，供主界面同步开关状态。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: MouseService? = null

        /** 供后续摇杆卡片直接驱动光标，不需要自行绑定服务。 */
        fun moveTo(x: Float, y: Float) {
            instance?.moveTo(x, y)
        }

        fun moveBy(dx: Float, dy: Float) {
            instance?.moveBy(dx, dy)
        }

        /** 供映射引擎（M5）读取当前光标坐标。 */
        fun currentPosition(): FloatArray? = instance?.currentPosition()

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
