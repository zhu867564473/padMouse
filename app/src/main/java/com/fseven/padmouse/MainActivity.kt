package com.fseven.padmouse

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 主界面：环境状态、运行开关、手柄回显、设置四段。
 *
 * - 首次启动/权限缺失时顶部显示可点击引导条，直接跳到对应授权；
 * - Shizuku UserService 断开后自动重绑，界面状态与提示由
 *   [ShizukuServiceManager] 的状态回调驱动；
 * - 悬浮窗权限被撤销时停止光标服务并提示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shizukuManager: ShizukuManager
    private lateinit var serviceManager: ShizukuServiceManager
    private lateinit var settingsRepository: SettingsRepository

    // 引导条
    private lateinit var onboardingBanner: MaterialCardView
    private lateinit var onboardingBannerText: TextView
    private var onboardingAction: (() -> Unit)? = null

    // 一、环境状态
    private lateinit var statusValue: TextView
    private lateinit var hintText: TextView
    private lateinit var refreshButton: Button
    private lateinit var requestButton: Button
    private lateinit var installButton: Button
    private lateinit var overlayStatusValue: TextView
    private lateinit var grantOverlayButton: Button

    // M8 一键授权：悬浮窗 / 通知状态行与兜底入口
    private lateinit var grantAllButton: Button
    private lateinit var permissionNotice: TextView
    private lateinit var notificationStatusValue: TextView
    private lateinit var notificationSettingsButton: Button
    private var overlayRequesting = false
    private var notificationRequesting = false
    private var overlayManualFallback = false
    private var notificationManualFallback = false
    private var autoGrantRunning = false
    private var autoGrantStartedByOneTap = false

    private lateinit var injectServiceStatus: TextView
    private lateinit var selfTestButton: Button
    private lateinit var testTapButton: Button
    private lateinit var injectResult: TextView

    // 二、运行开关
    private lateinit var runStatusValue: TextView
    private lateinit var runToggleButton: Button
    private lateinit var mappingStatus: TextView
    private lateinit var mappingCursorValue: TextView
    private lateinit var mappingVectorValue: TextView
    private lateinit var mappingActionValue: TextView

    // 三、手柄
    private lateinit var gamepadSummary: TextView
    private lateinit var gamepadModeValue: TextView
    private lateinit var gamepadAxes: TextView
    private lateinit var gamepadEvents: TextView
    private lateinit var gamepadRefreshButton: Button

    // M10 独占：开关 + 模式切换热键
    private lateinit var exclusiveSwitch: SwitchMaterial
    private lateinit var modeToggleSpinner: Spinner
    private var modeToggleBinding: ActionBinding = SettingsRepository.DEFAULT_MODE_TOGGLE_BINDING

    // 四、设置：光标外观
    private lateinit var cursorSizeSeek: SeekBar
    private lateinit var cursorSizeValue: TextView
    private lateinit var cursorIdleSeek: SeekBar
    private lateinit var cursorIdleValue: TextView
    private lateinit var cursorColorGroup: RadioGroup

    // 四、设置：手感
    private lateinit var feelSpeedSeek: SeekBar
    private lateinit var feelSpeedValue: TextView
    private lateinit var feelDeadzoneSeek: SeekBar
    private lateinit var feelDeadzoneValue: TextView
    private lateinit var feelExponentSeek: SeekBar
    private lateinit var feelExponentValue: TextView

    // 四、设置：M11 震动反馈
    private lateinit var vibrationSwitch: SwitchMaterial
    private lateinit var vibrationIntensitySeek: SeekBar
    private lateinit var vibrationIntensityValue: TextView
    private lateinit var vibrationClickCheck: CheckBox
    private lateinit var vibrationModeCheck: CheckBox
    private lateinit var vibrationScreenshotCheck: CheckBox
    private lateinit var vibrationUnsupported: TextView

    // 四、设置：按键绑定
    private lateinit var systemBindingsResetButton: Button
    private val systemSpinners = LinkedHashMap<SystemAction, Spinner>()
    private var systemBindings: Map<SystemAction, ActionBinding> = SettingsRepository.SYSTEM_ACTION_DEFAULTS

    private var lastUserServiceState: UserServiceState? = null
    private var disconnectNotified = false
    private var mappingRenderScheduled = false

    /** 用户在「未授权」时点了启动：授权链路完成后自动继续启动。 */
    private var startAfterGrant = false

    /** binder 调用与 shell 命令放到后台线程，避免阻塞 UI。 */
    private val injectExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 运行状态轮询：服务被系统/通知栏停止时同步大按钮。 */
    private val runStatePoller = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderRunState()
            runStatusValue.postDelayed(this, RUN_STATE_POLL_MS)
        }
    }

    /** 映射/手柄调试面板轮询：读取 [MouseRuntime] 快照，避免高频事件刷 UI。 */
    private val mappingPoller = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderMappingStatus()
            renderMappingPanel()
            renderGamepadPanel()
            mappingStatus.postDelayed(this, MAPPING_POLL_MS)
        }
    }

    /** 共享状态变更回调（主线程）：Service 是唯一写入方，界面只读。 */
    private val runtimeListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) {
            renderUserServiceState(MouseRuntime.userServiceState)
            renderRunState()
            scheduleMappingRender()
        }
    }

    private val gamepadTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuManager.REQUEST_CODE_PERMISSION) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Toast.makeText(
                    this,
                    if (granted) R.string.shizuku_toast_granted else R.string.shizuku_toast_denied,
                    Toast.LENGTH_SHORT
                ).show()
                renderStatus()
                renderOnboarding()
                if (granted) {
                    renderUserServiceState(UserServiceState.CONNECTING)
                    serviceManager.bindNow()
                    // Shizuku 授权后置动作：依次代开悬浮窗与通知，全程不进系统设置。
                    beginAutoGrant(fromOneTap = autoGrantStartedByOneTap)
                } else {
                    abortAutoGrant()
                }
                updatePermissionStatusUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuManager = ShizukuManager(this)

        onboardingBanner = findViewById(R.id.onboarding_banner)
        onboardingBannerText = findViewById(R.id.onboarding_banner_text)
        statusValue = findViewById(R.id.shizuku_status_value)
        hintText = findViewById(R.id.shizuku_hint)
        refreshButton = findViewById(R.id.button_refresh_shizuku)
        requestButton = findViewById(R.id.button_request_shizuku)
        installButton = findViewById(R.id.button_install_shizuku)
        overlayStatusValue = findViewById(R.id.overlay_status_value)
        grantOverlayButton = findViewById(R.id.button_grant_overlay)
        grantAllButton = findViewById(R.id.button_grant_all)
        permissionNotice = findViewById(R.id.permission_notice)
        notificationStatusValue = findViewById(R.id.notification_status_value)
        notificationSettingsButton = findViewById(R.id.button_notification_settings)
        injectServiceStatus = findViewById(R.id.inject_service_status)
        selfTestButton = findViewById(R.id.button_self_test)
        testTapButton = findViewById(R.id.button_test_tap)
        injectResult = findViewById(R.id.inject_result)
        runStatusValue = findViewById(R.id.run_status_value)
        runToggleButton = findViewById(R.id.button_run_toggle)
        mappingStatus = findViewById(R.id.mapping_status)
        mappingCursorValue = findViewById(R.id.mapping_cursor_value)
        mappingVectorValue = findViewById(R.id.mapping_vector_value)
        mappingActionValue = findViewById(R.id.mapping_action_value)
        gamepadSummary = findViewById(R.id.gamepad_summary)
        gamepadModeValue = findViewById(R.id.gamepad_mode_value)
        gamepadAxes = findViewById(R.id.gamepad_axes)
        gamepadEvents = findViewById(R.id.gamepad_events)
        gamepadRefreshButton = findViewById(R.id.button_refresh_gamepad)
        cursorSizeSeek = findViewById(R.id.seek_cursor_size)
        cursorSizeValue = findViewById(R.id.cursor_size_value)
        cursorIdleSeek = findViewById(R.id.seek_cursor_idle)
        cursorIdleValue = findViewById(R.id.cursor_idle_value)
        cursorColorGroup = findViewById(R.id.radio_group_cursor_color)
        feelSpeedSeek = findViewById(R.id.seek_feel_speed)
        feelSpeedValue = findViewById(R.id.feel_speed_value)
        feelDeadzoneSeek = findViewById(R.id.seek_feel_deadzone)
        feelDeadzoneValue = findViewById(R.id.feel_deadzone_value)
        feelExponentSeek = findViewById(R.id.seek_feel_exponent)
        feelExponentValue = findViewById(R.id.feel_exponent_value)
        exclusiveSwitch = findViewById(R.id.switch_exclusive_mode)
        modeToggleSpinner = findViewById(R.id.spinner_mode_toggle)

        settingsRepository = SettingsRepository(this)

        // 与 MouseService 共用同一个 UserService 绑定管理器（daemon 复用，进程内单例）。
        serviceManager = MouseRuntime.ensureManager(this)
        systemBindings = settingsRepository.loadSystemBindings()
        MouseRuntime.addListener(runtimeListener)

        onboardingBanner.setOnClickListener { onboardingAction?.invoke() }
        refreshButton.setOnClickListener {
            renderStatus()
            renderOnboarding()
        }
        requestButton.setOnClickListener { requestShizukuPermission() }
        installButton.setOnClickListener { openShizukuInstallPage() }
        grantOverlayButton.setOnClickListener { requestOverlayPermission() }
        grantAllButton.setOnClickListener { runOneTapGrant() }
        notificationSettingsButton.setOnClickListener { openNotificationSettings() }
        selfTestButton.setOnClickListener { runSelfTest() }
        testTapButton.setOnClickListener { runTestTap() }
        gamepadRefreshButton.setOnClickListener { refreshGamepadInfo() }
        runToggleButton.setOnClickListener { setCursorEnabled(!MouseService.isRunning) }

        setupSystemBindings()
        setupModeToggleBinding()
        setupExclusiveControls()
        setupCursorControls()
        setupFeelControls()
        setupVibrationControls()

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        renderUserServiceState(MouseRuntime.userServiceState)
        serviceManager.start()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku/悬浮窗设置页返回后自动刷新状态。
        renderStatus()
        updatePermissionStatusUi()
        renderRunState()
        stopCursorIfOverlayRevoked()
        serviceManager = MouseRuntime.ensureManager(this)
        serviceManager.bindNow()
        renderUserServiceState(MouseRuntime.userServiceState)
        renderRunState()
        renderMappingPanel()
        renderGamepadPanel()
        renderOnboarding()
        // MouseService 启动时自检补开的界面提示（不弹 Toast 打扰）。
        val persistedNotice = MouseService.takePermissionNotice()
        if (persistedNotice != null) {
            showPermissionNotice(persistedNotice)
        } else if (!autoGrantRunning && allPermissionsGranted()) {
            // 权限已通过手动入口补齐时，清掉上一轮的失败提示。
            permissionNotice.visibility = View.GONE
        }
        // 权限自愈：悬浮窗/通知被回收且 Shizuku 仍可用时自动补开。
        selfHealPermissions()
        // 从系统设置返回：若正在等待权限补齐后启动，这里继续完成启动。
        if (startAfterGrant && Settings.canDrawOverlays(this) &&
            shizukuManager.getStatus() == ShizukuStatus.GRANTED
        ) {
            startAfterGrant = false
            requestNotificationPermissionIfNeeded()
            startCursorService()
        }
        runStatusValue.removeCallbacks(runStatePoller)
        runStatusValue.postDelayed(runStatePoller, RUN_STATE_POLL_MS)
        mappingStatus.removeCallbacks(mappingPoller)
        mappingStatus.postDelayed(mappingPoller, MAPPING_POLL_MS)
    }

    override fun onPause() {
        runStatusValue.removeCallbacks(runStatePoller)
        mappingStatus.removeCallbacks(mappingPoller)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.cursor_toast_notification_denied, Toast.LENGTH_LONG).show()
        }
        updatePermissionStatusUi()
    }

    override fun onDestroy() {
        runStatusValue.removeCallbacks(runStatePoller)
        mappingStatus.removeCallbacks(mappingPoller)
        MouseRuntime.removeListener(runtimeListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        // 不停止服务/映射/UserService：前台服务继续运行，退出界面与切后台都不受影响。
        injectExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 引导条

    /**
     * 首次启动/权限缺失时在顶部展示一条引导：
     * 按「Shizuku 未安装 → 未运行 → 未授权 → 悬浮窗未授予」的优先级给出点击动作。
     */
    private fun renderOnboarding() {
        if (!::onboardingBanner.isInitialized) return
        val issues = ArrayList<String>()
        var action: (() -> Unit)? = null

        when (shizukuManager.getStatus()) {
            ShizukuStatus.NOT_INSTALLED -> {
                issues.add(getString(R.string.onboarding_install_shizuku))
                action = { openShizukuInstallPage() }
            }

            ShizukuStatus.NOT_RUNNING -> {
                issues.add(getString(R.string.onboarding_start_shizuku))
                action = { openShizukuApp() }
            }

            ShizukuStatus.NOT_GRANTED -> {
                issues.add(getString(R.string.onboarding_grant_shizuku))
                action = { requestShizukuPermission() }
            }

            ShizukuStatus.GRANTED -> Unit
        }

        if (!Settings.canDrawOverlays(this)) {
            issues.add(getString(R.string.onboarding_grant_overlay))
            if (action == null) action = { requestOverlayPermission() }
        }

        if (issues.isEmpty()) {
            onboardingAction = null
            onboardingBanner.visibility = View.GONE
            return
        }
        onboardingAction = action
        onboardingBannerText.text = getString(R.string.onboarding_message, issues.joinToString(" · "))
        onboardingBanner.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 一、环境状态

    private fun renderStatus() {
        when (shizukuManager.getStatus()) {
            ShizukuStatus.NOT_INSTALLED -> {
                statusValue.setText(R.string.shizuku_status_not_installed)
                showHint(R.string.shizuku_hint_not_installed)
                requestButton.visibility = View.GONE
                installButton.visibility = View.VISIBLE
            }

            ShizukuStatus.NOT_RUNNING -> {
                statusValue.setText(R.string.shizuku_status_not_running)
                showHint(R.string.shizuku_hint_not_running)
                requestButton.visibility = View.GONE
                installButton.visibility = View.GONE
            }

            ShizukuStatus.NOT_GRANTED -> {
                statusValue.setText(R.string.shizuku_status_not_granted)
                showHint(R.string.shizuku_hint_not_granted)
                requestButton.visibility = View.VISIBLE
                installButton.visibility = View.GONE
            }

            ShizukuStatus.GRANTED -> {
                statusValue.setText(R.string.shizuku_status_granted)
                hintText.visibility = View.GONE
                requestButton.visibility = View.GONE
                installButton.visibility = View.GONE
            }
        }
    }

    private fun renderUserServiceState(state: UserServiceState) {
        val wasReady = lastUserServiceState == UserServiceState.READY
        lastUserServiceState = state
        val ready = state == UserServiceState.READY
        selfTestButton.isEnabled = ready
        testTapButton.isEnabled = ready
        gamepadRefreshButton.isEnabled = ready
        if (ready) {
            if (disconnectNotified) {
                disconnectNotified = false
                Toast.makeText(this, R.string.shizuku_toast_reconnected, Toast.LENGTH_SHORT).show()
            }
        } else if (state == UserServiceState.DISCONNECTED && wasReady &&
            !disconnectNotified && MouseService.isRunning
        ) {
            // 重连由 MouseService 负责；只在实际运行中掉线时提示，主动停止不打扰。
            disconnectNotified = true
            Toast.makeText(this, R.string.shizuku_toast_reconnecting, Toast.LENGTH_LONG).show()
        }
        injectServiceStatus.setText(
            when (state) {
                UserServiceState.CONNECTING -> R.string.inject_service_status_connecting
                UserServiceState.READY -> R.string.inject_service_status_ready
                UserServiceState.DISCONNECTED -> R.string.inject_service_status_disconnected
                UserServiceState.FAILED -> R.string.inject_service_status_failed
            }
        )
    }

    private fun runSelfTest() {
        if (!serviceManager.isReady) {
            Toast.makeText(this, R.string.inject_toast_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        injectResult.setText(R.string.inject_result_running)
        injectExecutor.execute {
            val text = try {
                serviceManager.selfTest()
            } catch (t: Throwable) {
                "自检失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            runOnUiThread { injectResult.text = text }
        }
    }

    private fun runTestTap() {
        if (!serviceManager.isReady) {
            Toast.makeText(this, R.string.inject_toast_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        injectExecutor.execute {
            val message = try {
                serviceManager.tap(centerX, centerY)
                getString(R.string.inject_result_tap_ok, centerX.toInt(), centerY.toInt())
            } catch (t: Throwable) {
                "点击注入失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            runOnUiThread {
                injectResult.text = message
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showHint(resId: Int) {
        hintText.setText(resId)
        hintText.visibility = View.VISIBLE
    }

    private fun requestShizukuPermission() {
        if (!shizukuManager.requestPermission(ShizukuManager.REQUEST_CODE_PERMISSION)) {
            // 状态可能已变化（服务被杀/已授权），刷新界面而非静默失败。
            renderStatus()
            renderOnboarding()
        }
    }

    private fun openShizukuInstallPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuManager.SHIZUKU_MARKET_URI)))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuManager.SHIZUKU_WEBSITE)))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.shizuku_toast_no_store, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Shizuku 已安装但服务未启动：直接打开 Shizuku 应用让用户启动服务。 */
    private fun openShizukuApp() {
        val launch = packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE_NAME)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launch)
                return
            } catch (_: ActivityNotFoundException) {
                // 回落到提示。
            }
        }
        Toast.makeText(this, R.string.onboarding_toast_open_shizuku, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- 二、运行开关

    private fun setCursorEnabled(enabled: Boolean) {
        if (!enabled) {
            startAfterGrant = false
            stopCursorService()
            return
        }
        when (shizukuManager.getStatus()) {
            ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING -> {
                Toast.makeText(this, R.string.run_toast_need_shizuku, Toast.LENGTH_LONG).show()
                MouseRuntime.setStage(
                    RunStage.FAILED,
                    getString(R.string.run_detail_shizuku_unavailable)
                )
                renderStatus()
                renderOnboarding()
                return
            }

            ShizukuStatus.NOT_GRANTED -> {
                // 单主开关的第一环：先发起授权，授权完成后自动继续启动。
                startAfterGrant = true
                Toast.makeText(this, R.string.run_toast_request_shizuku, Toast.LENGTH_LONG).show()
                if (!shizukuManager.requestPermission(ShizukuManager.REQUEST_CODE_PERMISSION)) {
                    startAfterGrant = false
                    renderStatus()
                    renderOnboarding()
                }
                return
            }

            ShizukuStatus.GRANTED -> Unit
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.cursor_toast_need_overlay, Toast.LENGTH_SHORT).show()
            MouseRuntime.setStage(
                RunStage.FAILED,
                getString(R.string.run_detail_overlay_missing)
            )
            startAfterGrant = true
            requestOverlayPermission()
            return
        }
        requestNotificationPermissionIfNeeded()
        startCursorService()
    }

    /**
     * 单主开关：只负责启动前台服务。绑定 UserService、注册手柄监听、
     * 启动映射全部由 [MouseService] 串起来，失败时通过 [MouseRuntime] 回传原因。
     */
    private fun startCursorService() {
        // 服务销毁时共享绑定会被释放；启动前重新确保拿到当前单例。
        serviceManager = MouseRuntime.ensureManager(this)
        MouseRuntime.setStage(RunStage.STARTING_SERVICE)
        val intent = Intent(this, MouseService::class.java)
            .setAction(MouseService.ACTION_START)
            .putExtra(MouseService.EXTRA_SIZE_DP, settingsRepository.loadCursorSizeDp())
            .putExtra(MouseService.EXTRA_COLOR, settingsRepository.loadCursorColor())
            .putExtra(
                MouseService.EXTRA_IDLE_TIMEOUT_MS,
                settingsRepository.loadCursorIdleSeconds() * 1000L
            )
        try {
            ContextCompat.startForegroundService(this, intent)
            renderRunState()
        } catch (t: Throwable) {
            Log.e(TAG, "启动手柄鼠标失败", t)
            MouseRuntime.setStage(RunStage.FAILED, getString(R.string.run_detail_start_failed))
            Toast.makeText(this, R.string.cursor_toast_start_failed, Toast.LENGTH_SHORT).show()
            renderRunState()
        }
    }

    private fun stopCursorService(showToast: Boolean = false) {
        stopService(Intent(this, MouseService::class.java))
        MouseRuntime.setStage(RunStage.IDLE)
        MouseRuntime.resetRunningState()
        renderRunState()
        renderMappingPanel()
        if (showToast) {
            Toast.makeText(this, R.string.cursor_toast_overlay_revoked, Toast.LENGTH_LONG).show()
        }
    }

    /** 权限被撤销（停留在系统设置时）→ 回前台立即停服，避免无光标服务残留。 */
    private fun stopCursorIfOverlayRevoked() {
        if (!MouseService.isRunning) return
        if (Settings.canDrawOverlays(this)) return
        stopCursorService(showToast = true)
        updatePermissionStatusUi()
    }

    /** 运行状态与大按钮文案；阶段文案来自 MouseService 的共享状态。 */
    private fun renderRunState() {
        val running = MouseService.isRunning
        val stage = MouseRuntime.stage
        val detail = MouseRuntime.stageDetail
        val statusText = when {
            stage == RunStage.FAILED ->
                getString(R.string.run_status_failed, detail ?: "")

            running && stage == RunStage.BINDING_SHIZUKU -> getString(R.string.run_status_binding)
            running && stage == RunStage.READING_GAMEPAD ->
                getString(R.string.run_status_reading_gamepad)

            running && stage == RunStage.RUNNING -> getString(R.string.run_status_running)
            stage == RunStage.STARTING_SERVICE -> getString(R.string.run_status_starting)
            else -> getString(R.string.run_status_stopped)
        }
        // 只在文案变化时 setText，轮询不产生多余重排。
        if (runStatusValue.text != statusText) runStatusValue.text = statusText
        val toggleText =
            getString(if (running) R.string.run_action_stop else R.string.run_action_start)
        if (runToggleButton.text != toggleText) runToggleButton.text = toggleText
    }

    /** Android 13+ 需要运行时授权才能展示前台服务通知（含「停止」action）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS
        )
    }

    /**
     * 统一刷新权限状态区：每一行都以系统 API 复核结果为准。
     * 只有确实不可用的权限才显示「请手动授予」，并保留手动跳转按钮。
     */
    private fun updatePermissionStatusUi() {
        if (!::overlayStatusValue.isInitialized) return

        val overlayGranted = Settings.canDrawOverlays(this)
        if (overlayGranted) overlayManualFallback = false
        overlayStatusValue.setText(
            when {
                overlayGranted -> R.string.permission_state_granted
                overlayRequesting -> R.string.permission_state_requesting
                overlayManualFallback -> R.string.permission_state_manual
                else -> R.string.permission_state_denied
            }
        )
        overlayStatusValue.setTextColor(
            getColor(if (overlayGranted) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        grantOverlayButton.visibility = if (overlayGranted) View.GONE else View.VISIBLE

        val supported = shizukuManager.supportsNotificationPermission()
        val notificationGranted = shizukuManager.hasNotificationPermission()
        if (notificationGranted) notificationManualFallback = false
        notificationStatusValue.setText(
            when {
                !supported -> R.string.permission_state_unsupported
                notificationGranted -> R.string.permission_state_granted
                notificationRequesting -> R.string.permission_state_requesting
                notificationManualFallback -> R.string.permission_state_manual
                else -> R.string.permission_state_denied
            }
        )
        notificationStatusValue.setTextColor(
            getColor(
                if (notificationGranted || !supported) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )
        // 兜底入口：命令失败或复核失败时始终保留手动跳转。
        notificationSettingsButton.visibility =
            if (notificationGranted || !supported) View.GONE else View.VISIBLE
    }

    private fun showPermissionNotice(message: String) {
        permissionNotice.text = message
        permissionNotice.visibility = View.VISIBLE
    }

    /** 一键授权：Shizuku 授权 → 悬浮窗 → 通知，全程不跳系统设置。 */
    private fun runOneTapGrant() {
        if (autoGrantRunning) return
        when (shizukuManager.getStatus()) {
            ShizukuStatus.NOT_INSTALLED -> {
                showPermissionNotice(getString(R.string.permission_notice_need_shizuku))
                openShizukuInstallPage()
            }

            ShizukuStatus.NOT_RUNNING -> {
                showPermissionNotice(getString(R.string.permission_notice_need_shizuku))
                openShizukuApp()
            }

            ShizukuStatus.NOT_GRANTED -> {
                showPermissionNotice(getString(R.string.permission_notice_waiting_shizuku))
                if (!shizukuManager.requestPermission(ShizukuManager.REQUEST_CODE_PERMISSION)) {
                    // 状态刚好变化（已授权/服务被杀），刷新而不是静默失败。
                    renderStatus()
                    renderOnboarding()
                    updatePermissionStatusUi()
                }
            }

            ShizukuStatus.GRANTED -> beginAutoGrant(fromOneTap = true)
        }
    }

    /**
     * 在单线程执行器上串行执行两条 shell 命令；先等 UserService 就绪，
     * 超时则直接用 Shizuku shell 兜底，保证刚授权时也能立即一键完成。
     */
    private fun beginAutoGrant(fromOneTap: Boolean) {
        if (autoGrantRunning) return
        if (shizukuManager.getStatus() != ShizukuStatus.GRANTED) return
        // 服务停止会释放共享绑定；授权流程开始前重新确保单例。
        serviceManager = MouseRuntime.ensureManager(this)
        autoGrantRunning = true
        if (fromOneTap) {
            autoGrantStartedByOneTap = true
            grantAllButton.isEnabled = false
            grantAllButton.setText(R.string.permission_action_grant_all_running)
        }
        injectExecutor.execute {
            awaitUserService(USER_SERVICE_WAIT_MS)
            val results = shizukuManager.autoGrantPermissions(
                shell = { command -> execGrantShell(command) },
                onStep = { step, status, _ ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) renderGrantStep(step, status)
                    }
                },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyGrantResults(results)
                completeAutoGrant()
            }
        }
    }

    /** 等待 UserService 绑定；期间周期性重试绑定，避免刚授权时拿不到 shell。 */
    private fun awaitUserService(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastBindAt = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (serviceManager.isReady) return true
            if (SystemClock.elapsedRealtime() - lastBindAt > USER_SERVICE_REBIND_MS) {
                lastBindAt = SystemClock.elapsedRealtime()
                serviceManager.bindNow()
            }
            try {
                Thread.sleep(USER_SERVICE_POLL_MS)
            } catch (_: InterruptedException) {
                return serviceManager.isReady
            }
        }
        return serviceManager.isReady
    }

    /** 统一走已有的 IPadMouse.shellExec（UserService 未就绪时命令会失败并走手动兜底）。 */
    private fun execGrantShell(command: String): String = serviceManager.shellExec(command)

    private fun renderGrantStep(step: AutoGrantStep, status: AutoGrantStatus) {
        when (step) {
            AutoGrantStep.OVERLAY -> {
                overlayRequesting = status == AutoGrantStatus.REQUESTING
                if (status == AutoGrantStatus.FAILED) overlayManualFallback = true
            }

            AutoGrantStep.NOTIFICATION -> {
                notificationRequesting = status == AutoGrantStatus.REQUESTING
                if (status == AutoGrantStatus.FAILED) notificationManualFallback = true
            }
        }
        updatePermissionStatusUi()
    }

    private fun applyGrantResults(results: List<AutoGrantResult>) {
        for (result in results) {
            when (result.step) {
                AutoGrantStep.OVERLAY -> {
                    overlayRequesting = false
                    overlayManualFallback = result.status == AutoGrantStatus.FAILED
                }

                AutoGrantStep.NOTIFICATION -> {
                    notificationRequesting = false
                    notificationManualFallback = result.status == AutoGrantStatus.FAILED
                }
            }
        }
        val changedLabels = results
            .filter { it.changed && it.status == AutoGrantStatus.GRANTED }
            .map {
                getString(
                    if (it.step == AutoGrantStep.OVERLAY) R.string.permission_overlay_short
                    else R.string.permission_notification_short
                )
            }
        val failed = results.any { it.status == AutoGrantStatus.FAILED }
        when {
            failed -> showPermissionNotice(getString(R.string.permission_notice_manual_required))
            changedLabels.isNotEmpty() -> showPermissionNotice(
                getString(R.string.permission_notice_auto_granted, changedLabels.joinToString("、"))
            )

            else -> permissionNotice.visibility = View.GONE
        }
    }

    private fun completeAutoGrant() {
        val wasOneTap = autoGrantStartedByOneTap
        autoGrantRunning = false
        autoGrantStartedByOneTap = false
        if (wasOneTap) {
            grantAllButton.isEnabled = true
            grantAllButton.setText(R.string.permission_action_grant_all)
        }
        updatePermissionStatusUi()
        renderOnboarding()
        renderRunState()
        // 用户从主开关进入的授权链路：授权齐全后自动继续启动，无需再点一次。
        if (startAfterGrant && Settings.canDrawOverlays(this) &&
            shizukuManager.getStatus() == ShizukuStatus.GRANTED
        ) {
            startAfterGrant = false
            requestNotificationPermissionIfNeeded()
            startCursorService()
        }
    }

    /** 一键流程中用户拒绝/取消 Shizuku 授权时复位按钮。 */
    private fun abortAutoGrant() {
        startAfterGrant = false
        if (!autoGrantStartedByOneTap) return
        autoGrantRunning = false
        autoGrantStartedByOneTap = false
        grantAllButton.isEnabled = true
        grantAllButton.setText(R.string.permission_action_grant_all)
        updatePermissionStatusUi()
    }

    /**
     * 权限自愈：回到前台时若悬浮窗/通知被系统回收且 Shizuku 仍可用，自动补开。
     * 补开结果只写界面提示，不弹 Toast。
     */
    private fun allPermissionsGranted(): Boolean =
        Settings.canDrawOverlays(this) &&
            (!shizukuManager.supportsNotificationPermission() ||
                shizukuManager.hasNotificationPermission())

    private fun selfHealPermissions() {
        if (autoGrantRunning) return
        if (shizukuManager.getStatus() != ShizukuStatus.GRANTED) return
        val needsOverlay = !Settings.canDrawOverlays(this)
        val needsNotification = shizukuManager.supportsNotificationPermission() &&
            !shizukuManager.hasNotificationPermission()
        if (!needsOverlay && !needsNotification) return
        beginAutoGrant(fromOneTap = false)
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.cursor_toast_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cursor_toast_no_settings, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 三、手柄

    /** 手柄扫描由 MouseService 负责；这里只触发一次设备摘要查询。 */
    private fun refreshGamepadInfo() {
        if (!serviceManager.isReady) {
            Toast.makeText(this, R.string.gamepad_toast_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        gamepadSummary.setText(R.string.gamepad_summary_scanning)
        injectExecutor.execute {
            val text = try {
                serviceManager.describeGamepads()
            } catch (t: Throwable) {
                "扫描手柄失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gamepadSummary.text = text
            }
        }
    }

    /** 手柄调试面板：读取 MouseService 写入的快照。 */
    private fun renderGamepadPanel() {
        if (!::gamepadSummary.isInitialized) return
        val summary = MouseRuntime.gamepadSummary
        if (!summary.isNullOrBlank()) {
            if (gamepadSummary.text != summary) gamepadSummary.text = summary
        }
        // M10：设备名之外再显示独占/模式状态，独占失败必须可见。
        val modeText = when {
            !MouseService.isRunning -> getString(R.string.gamepad_mode_idle)
            MouseService.gameMode -> getString(R.string.gamepad_mode_game)
            MouseService.exclusiveError != null ->
                getString(R.string.gamepad_mode_exclusive_failed, MouseService.exclusiveError ?: "")

            MouseService.exclusiveActive -> getString(R.string.gamepad_mode_exclusive)
            else -> getString(R.string.gamepad_mode_listen)
        }
        if (gamepadModeValue.text != modeText) gamepadModeValue.text = modeText
        val axes = MouseRuntime.axesSnapshot().sortedBy { it.first }
        gamepadAxes.text = if (axes.isEmpty()) {
            getString(R.string.gamepad_axes_idle)
        } else {
            val values = axes.joinToString("   ") { (code, value) ->
                val name = GamepadAxes.NAMES[code] ?: "code=0x${code.toString(16)}"
                "$name=${String.format(Locale.US, "%.2f", value)}"
            }
            getString(R.string.gamepad_axes_value, values)
        }
        val events = MouseRuntime.keyEventsSnapshot()
        gamepadEvents.text = if (events.isEmpty()) {
            getString(R.string.gamepad_events_idle)
        } else {
            events.joinToString("\n") { entry ->
                getString(
                    R.string.gamepad_key_event,
                    gamepadTimeFormat.format(Date(entry.atMillis)),
                    entry.code,
                    entry.value
                )
            }
        }
    }

    // ---------------------------------------------------------------- 四、设置：光标外观

    private fun setupCursorControls() {
        // 大小滑杆：20-48dp
        cursorSizeSeek.max = MouseService.MAX_SIZE_DP - MouseService.MIN_SIZE_DP
        cursorSizeSeek.progress = settingsRepository.loadCursorSizeDp() - MouseService.MIN_SIZE_DP
        updateCursorSizeLabel()
        cursorSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCursorSizeLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                settingsRepository.saveCursorSizeDp(selectedCursorSizeDp())
                pushCursorAppearance()
            }
        })

        // 自动隐藏滑杆：1-10 秒
        cursorIdleSeek.max = SettingsRepository.MAX_IDLE_SECONDS - SettingsRepository.MIN_IDLE_SECONDS
        cursorIdleSeek.progress =
            settingsRepository.loadCursorIdleSeconds() - SettingsRepository.MIN_IDLE_SECONDS
        updateCursorIdleLabel()
        cursorIdleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCursorIdleLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                settingsRepository.saveCursorIdleSeconds(selectedCursorIdleSeconds())
                pushCursorAppearance()
            }
        })

        cursorColorGroup.check(
            when (settingsRepository.loadCursorColor()) {
                MouseService.COLOR_BLACK -> R.id.radio_cursor_black
                MouseService.COLOR_YELLOW -> R.id.radio_cursor_yellow
                else -> R.id.radio_cursor_white
            }
        )
        cursorColorGroup.setOnCheckedChangeListener { _, checkedId ->
            settingsRepository.saveCursorColor(colorForRadio(checkedId))
            pushCursorAppearance()
        }
    }

    /** 服务已在运行时，把最新的外观设置推给服务，不需要重启。 */
    private fun pushCursorAppearance() {
        if (!MouseService.isRunning) return
        val intent = Intent(this, MouseService::class.java)
            .setAction(MouseService.ACTION_UPDATE)
            .putExtra(MouseService.EXTRA_SIZE_DP, settingsRepository.loadCursorSizeDp())
            .putExtra(MouseService.EXTRA_COLOR, settingsRepository.loadCursorColor())
            .putExtra(
                MouseService.EXTRA_IDLE_TIMEOUT_MS,
                settingsRepository.loadCursorIdleSeconds() * 1000L
            )
        try {
            startService(intent)
        } catch (_: Throwable) {
            // 服务可能刚好停止，外观设置已持久化，下次启动会生效。
        }
    }

    private fun updateCursorSizeLabel() {
        cursorSizeValue.text = getString(R.string.cursor_size_value, selectedCursorSizeDp())
    }

    private fun updateCursorIdleLabel() {
        cursorIdleValue.text = getString(R.string.cursor_idle_value, selectedCursorIdleSeconds())
    }

    private fun selectedCursorSizeDp(): Int =
        cursorSizeSeek.progress + MouseService.MIN_SIZE_DP

    private fun selectedCursorIdleSeconds(): Int =
        cursorIdleSeek.progress + SettingsRepository.MIN_IDLE_SECONDS

    private fun colorForRadio(checkedId: Int): Int = when (checkedId) {
        R.id.radio_cursor_black -> MouseService.COLOR_BLACK
        R.id.radio_cursor_yellow -> MouseService.COLOR_YELLOW
        else -> MouseService.COLOR_WHITE
    }

    // ---------------------------------------------------------------- 四、设置：手感

    private fun setupFeelControls() {
        val speedSteps =
            ((SettingsRepository.MAX_SPEED_PX_PER_SEC - SettingsRepository.MIN_SPEED_PX_PER_SEC) /
                SettingsRepository.SPEED_STEP).roundToInt()
        feelSpeedSeek.max = speedSteps
        feelSpeedSeek.progress = progressFor(
            settingsRepository.loadSpeedPxPerSec(),
            SettingsRepository.MIN_SPEED_PX_PER_SEC,
            SettingsRepository.SPEED_STEP,
            speedSteps
        )

        val deadzoneSteps =
            ((SettingsRepository.MAX_DEADZONE - SettingsRepository.MIN_DEADZONE) /
                SettingsRepository.DEADZONE_STEP).roundToInt()
        feelDeadzoneSeek.max = deadzoneSteps
        feelDeadzoneSeek.progress = progressFor(
            settingsRepository.loadDeadzone(),
            SettingsRepository.MIN_DEADZONE,
            SettingsRepository.DEADZONE_STEP,
            deadzoneSteps
        )

        val exponentSteps =
            ((SettingsRepository.MAX_ACCEL_EXPONENT - SettingsRepository.MIN_ACCEL_EXPONENT) /
                SettingsRepository.EXPONENT_STEP).roundToInt()
        feelExponentSeek.max = exponentSteps
        feelExponentSeek.progress = progressFor(
            settingsRepository.loadAccelExponent(),
            SettingsRepository.MIN_ACCEL_EXPONENT,
            SettingsRepository.EXPONENT_STEP,
            exponentSteps
        )

        updateFeelLabels()
        feelSpeedSeek.setOnSeekBarChangeListener(feelListener())
        feelDeadzoneSeek.setOnSeekBarChangeListener(feelListener())
        feelExponentSeek.setOnSeekBarChangeListener(feelListener())
    }

    private fun feelListener() = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            updateFeelLabels()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            settingsRepository.saveSpeedPxPerSec(selectedSpeedPxPerSec())
            settingsRepository.saveDeadzone(selectedDeadzone())
            settingsRepository.saveAccelExponent(selectedAccelExponent())
            applyFeelConfig()
        }
    }

    private fun progressFor(value: Float, min: Float, step: Float, maxProgress: Int): Int =
        ((value - min) / step).roundToInt().coerceIn(0, maxProgress)

    private fun selectedSpeedPxPerSec(): Float =
        SettingsRepository.MIN_SPEED_PX_PER_SEC +
            feelSpeedSeek.progress * SettingsRepository.SPEED_STEP

    private fun selectedDeadzone(): Float =
        SettingsRepository.MIN_DEADZONE + feelDeadzoneSeek.progress * SettingsRepository.DEADZONE_STEP

    private fun selectedAccelExponent(): Float =
        SettingsRepository.MIN_ACCEL_EXPONENT + feelExponentSeek.progress * SettingsRepository.EXPONENT_STEP

    private fun updateFeelLabels() {
        feelSpeedValue.text =
            getString(R.string.feel_speed_value, selectedSpeedPxPerSec().roundToInt())
        feelDeadzoneValue.text =
            getString(R.string.feel_deadzone_value, selectedDeadzone())
        feelExponentValue.text =
            getString(R.string.feel_exponent_value, selectedAccelExponent())
    }

    /** 手感参数热更新到正在运行的映射引擎（服务侧），不需要重启。 */
    private fun applyFeelConfig() {
        pushMapperConfig()
        renderMappingStatus()
    }

    /** 映射配置由 MouseService 持有；这里把最新设置推给服务。 */
    private fun pushMapperConfig() {
        MouseService.applyConfig(settingsRepository.loadMapperConfig(systemBindings))
    }

    // ---------------------------------------------------------------- 四、设置：M11 震动反馈

    /**
     * 震动区块：总开关 + 0/25/50/75/100% 强度 + 三个分项开关。
     *
     * 程序化构建（不新增布局文件），放在设置卡片末尾；
     * 改动写入 padmouse_settings 后立刻通过 ACTION_UPDATE_VIBRATION 推给运行中的服务。
     */
    private fun setupVibrationControls() {
        val settings = loadVibrationSettings(this)
        val supported = MouseService.isVibrationSupported(this)
        val container = exclusiveSwitch.parent as? LinearLayout

        vibrationSwitch = SwitchMaterial(this).apply {
            text = getString(R.string.vibration_enabled_label)
            isChecked = settings.enabled
            setPadding(0, dp(8), 0, 0)
        }
        vibrationIntensityValue = TextView(this)
        vibrationIntensitySeek = SeekBar(this).apply {
            max = VibrationSettings.INTENSITY_STEPS.size - 1
            progress = intensityIndex(settings.intensity)
        }
        vibrationClickCheck = CheckBox(this).apply {
            text = getString(R.string.vibration_click_label)
            isChecked = settings.clickEnabled
        }
        vibrationModeCheck = CheckBox(this).apply {
            text = getString(R.string.vibration_mode_toggle_label)
            isChecked = settings.modeToggleEnabled
        }
        vibrationScreenshotCheck = CheckBox(this).apply {
            text = getString(R.string.vibration_screenshot_label)
            isChecked = settings.screenshotEnabled
        }
        vibrationUnsupported = TextView(this).apply {
            text = getString(R.string.vibration_unsupported)
            visibility = if (supported) View.GONE else View.VISIBLE
        }

        container?.let { parent ->
            parent.addView(
                TextView(this).apply {
                    text = getString(R.string.vibration_section_title)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(16), 0, 0)
                },
                matchWrap()
            )
            parent.addView(vibrationSwitch, matchWrap())

            val intensityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.vibration_intensity_label)
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    vibrationIntensityValue,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            parent.addView(intensityRow, matchWrap(topMargin = 8))
            parent.addView(vibrationIntensitySeek, matchWrap())
            parent.addView(vibrationClickCheck, matchWrap())
            parent.addView(vibrationModeCheck, matchWrap())
            parent.addView(vibrationScreenshotCheck, matchWrap())
            parent.addView(vibrationUnsupported, matchWrap(topMargin = 4))
            parent.addView(
                TextView(this).apply {
                    text = getString(R.string.vibration_hint)
                    setPadding(0, dp(4), 0, dp(8))
                },
                matchWrap()
            )
        }

        updateVibrationLabels()
        vibrationSwitch.isEnabled = supported
        if (supported) applyVibrationEnabledState(settings.enabled) else {
            vibrationIntensitySeek.isEnabled = false
            vibrationClickCheck.isEnabled = false
            vibrationModeCheck.isEnabled = false
            vibrationScreenshotCheck.isEnabled = false
        }

        vibrationSwitch.setOnCheckedChangeListener { _, checked ->
            saveVibrationSettings()
            applyVibrationEnabledState(checked)
        }
        vibrationIntensitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateVibrationLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveVibrationSettings()
            }
        })
        val checkListener = CompoundButton.OnCheckedChangeListener { _, _ -> saveVibrationSettings() }
        vibrationClickCheck.setOnCheckedChangeListener(checkListener)
        vibrationModeCheck.setOnCheckedChangeListener(checkListener)
        vibrationScreenshotCheck.setOnCheckedChangeListener(checkListener)
    }

    /** 总开关关闭时，强度与分项开关一并置灰（保留已保存值）。 */
    private fun applyVibrationEnabledState(enabled: Boolean) {
        val active = enabled && vibrationSwitch.isEnabled
        vibrationIntensitySeek.isEnabled = active
        vibrationClickCheck.isEnabled = active
        vibrationModeCheck.isEnabled = active
        vibrationScreenshotCheck.isEnabled = active
    }

    private fun saveVibrationSettings() {
        saveVibrationSettings(
            this,
            VibrationSettings(
                enabled = vibrationSwitch.isChecked,
                intensity = selectedVibrationIntensity(),
                clickEnabled = vibrationClickCheck.isChecked,
                modeToggleEnabled = vibrationModeCheck.isChecked,
                screenshotEnabled = vibrationScreenshotCheck.isChecked,
            )
        )
        pushVibrationSettings()
    }

    /** 立即推给运行中的 MouseService，不重启服务、不重连手柄。 */
    private fun pushVibrationSettings() {
        if (!MouseService.isRunning) return
        try {
            startService(
                Intent(this, MouseService::class.java)
                    .setAction(MouseService.ACTION_UPDATE_VIBRATION)
            )
        } catch (_: Throwable) {
            // 服务可能刚好停止；设置已持久化，下次启动生效。
        }
    }

    private fun updateVibrationLabels() {
        vibrationIntensityValue.text =
            getString(R.string.vibration_intensity_value, selectedVibrationIntensity())
    }

    private fun selectedVibrationIntensity(): Int {
        val steps = VibrationSettings.INTENSITY_STEPS
        return steps[vibrationIntensitySeek.progress.coerceIn(0, steps.size - 1)]
    }

    private fun intensityIndex(intensity: Int): Int {
        val steps = VibrationSettings.INTENSITY_STEPS
        return steps.indexOf(intensity).takeIf { it >= 0 } ?: steps.lastIndex
    }

    /** 程序化设置项的通用布局参数（match_parent × wrap_content + 上边距）。 */
    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- 四、设置：按键绑定

    /** 8 个系统动作各一个下拉框；选中即保存并热更新 Mapper。 */
    private fun setupSystemBindings() {
        systemBindingsResetButton = findViewById(R.id.button_reset_system_bindings)
        systemSpinners[SystemAction.BACK] = findViewById(R.id.spinner_system_back)
        systemSpinners[SystemAction.HOME] = findViewById(R.id.spinner_system_home)
        systemSpinners[SystemAction.RECENTS] = findViewById(R.id.spinner_system_recents)
        systemSpinners[SystemAction.VOLUME_UP] = findViewById(R.id.spinner_system_volume_up)
        systemSpinners[SystemAction.VOLUME_DOWN] = findViewById(R.id.spinner_system_volume_down)
        systemSpinners[SystemAction.BRIGHTNESS_UP] = findViewById(R.id.spinner_system_brightness_up)
        systemSpinners[SystemAction.BRIGHTNESS_DOWN] = findViewById(R.id.spinner_system_brightness_down)
        systemSpinners[SystemAction.SCREENSHOT] = findViewById(R.id.spinner_system_screenshot)

        for (action in SystemAction.values()) {
            val spinner = systemSpinners[action] ?: continue
            bindSystemActionSpinner(action, spinner)
        }
        systemBindingsResetButton.setOnClickListener {
            settingsRepository.resetSystemBindings()
            systemBindings = SettingsRepository.SYSTEM_ACTION_DEFAULTS
            applySystemBindings()
            for ((action, spinner) in systemSpinners) bindSystemActionSpinner(action, spinner)
            Toast.makeText(this, R.string.system_action_reset_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSystemActionSpinner(action: SystemAction, spinner: Spinner) {
        val options = bindingOptionsFor(action)
        val labels = options.map { getString(it.labelRes) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val current = systemBindings[action] ?: SettingsRepository.SYSTEM_ACTION_DEFAULTS.getValue(action)
        spinner.setSelection(options.indexOfFirst { it.binding == current }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val option = options.getOrNull(position) ?: return
                if (systemBindings[action] == option.binding) return
                systemBindings = systemBindings + (action to option.binding)
                settingsRepository.saveSystemBinding(action, option.binding)
                applySystemBindings()
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.system_action_saved,
                        getString(systemActionLabelRes(action)),
                        getString(option.labelRes)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 可选项：手柄实体键与十字键方向；截图额外提供 LB+RB 组合。 */
    private fun bindingOptionsFor(action: SystemAction): List<BindingOption> {
        val options = ArrayList<BindingOption>()
        if (action == SystemAction.SCREENSHOT) {
            options.add(
                BindingOption(
                    ActionBinding(DefaultBindings.BTN_TL, chordCode = DefaultBindings.BTN_TR),
                    R.string.system_bind_chord_lb_rb
                )
            )
        }
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_START), R.string.system_bind_start))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_SELECT), R.string.system_bind_select))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_MODE), R.string.system_bind_mode))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_THUMBL), R.string.system_bind_thumbl))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_THUMBR), R.string.system_bind_thumbr))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_TL), R.string.system_bind_tl))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_TR), R.string.system_bind_tr))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_A), R.string.system_bind_a))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_B), R.string.system_bind_b))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_X), R.string.system_bind_x))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_UP), R.string.system_bind_dpad_up))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_DOWN), R.string.system_bind_dpad_down))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_LEFT), R.string.system_bind_dpad_left))
        options.add(BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_RIGHT), R.string.system_bind_dpad_right))
        options.add(
            BindingOption(ActionBinding(DefaultBindings.AXIS_HAT0Y, direction = -1), R.string.system_bind_hat_up)
        )
        options.add(
            BindingOption(ActionBinding(DefaultBindings.AXIS_HAT0Y, direction = 1), R.string.system_bind_hat_down)
        )
        options.add(
            BindingOption(ActionBinding(DefaultBindings.AXIS_HAT0X, direction = -1), R.string.system_bind_hat_left)
        )
        options.add(
            BindingOption(ActionBinding(DefaultBindings.AXIS_HAT0X, direction = 1), R.string.system_bind_hat_right)
        )
        // 已保存但不在候选表里的绑定（例如旧版本自定义）仍可见、不被覆盖。
        val current = systemBindings[action]
        if (current != null && options.none { it.binding == current }) {
            options.add(0, BindingOption(current, R.string.system_bind_custom))
        }
        return options
    }

    private fun applySystemBindings() {
        pushMapperConfig()
    }

    // ---------------------------------------------------------------- M10 独占 / 模式热键

    /** 独占手柄开关：持久化后通知服务按新配置重建读取器（立即生效）。 */
    private fun setupExclusiveControls() {
        exclusiveSwitch.isChecked = settingsRepository.loadExclusiveMode()
        exclusiveSwitch.setOnCheckedChangeListener { _, checked ->
            settingsRepository.saveExclusiveMode(checked)
            pushExclusiveMode()
        }
    }

    private fun pushExclusiveMode() {
        if (!MouseService.isRunning) return
        try {
            startService(
                Intent(this, MouseService::class.java)
                    .setAction(MouseService.ACTION_UPDATE_EXCLUSIVE)
            )
        } catch (_: Throwable) {
            // 服务可能刚好停止；设置已持久化，下次启动生效。
        }
    }

    /**
     * 「切换鼠标/游戏模式」热键：长按 800ms 触发。
     * 候选项只有实体按键、没有「无」——不允许清空，否则切到游戏模式后不便切回。
     */
    private fun setupModeToggleBinding() {
        modeToggleBinding = settingsRepository.loadModeToggleBinding()
        val options = modeToggleOptions()
        val labels = options.map { getString(it.labelRes) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeToggleSpinner.adapter = adapter
        modeToggleSpinner.setSelection(
            options.indexOfFirst { it.binding == modeToggleBinding }.coerceAtLeast(0),
            false
        )
        modeToggleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val option = options.getOrNull(position) ?: return
                if (modeToggleBinding == option.binding) return
                modeToggleBinding = option.binding
                settingsRepository.saveModeToggleBinding(option.binding)
                pushMapperConfig()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.mode_toggle_saved, getString(option.labelRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 模式切换热键候选项：只列按键，不含轴/组合/清空。 */
    private fun modeToggleOptions(): List<BindingOption> = listOf(
        BindingOption(ActionBinding(DefaultBindings.BTN_THUMBL), R.string.system_bind_thumbl),
        BindingOption(ActionBinding(DefaultBindings.BTN_THUMBR), R.string.system_bind_thumbr),
        BindingOption(ActionBinding(DefaultBindings.BTN_START), R.string.system_bind_start),
        BindingOption(ActionBinding(DefaultBindings.BTN_SELECT), R.string.system_bind_select),
        BindingOption(ActionBinding(DefaultBindings.BTN_MODE), R.string.system_bind_mode),
        BindingOption(ActionBinding(DefaultBindings.BTN_TL), R.string.system_bind_tl),
        BindingOption(ActionBinding(DefaultBindings.BTN_TR), R.string.system_bind_tr),
        BindingOption(ActionBinding(DefaultBindings.BTN_A), R.string.system_bind_a),
        BindingOption(ActionBinding(DefaultBindings.BTN_B), R.string.system_bind_b),
        BindingOption(ActionBinding(DefaultBindings.BTN_X), R.string.system_bind_x),
        BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_UP), R.string.system_bind_dpad_up),
        BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_DOWN), R.string.system_bind_dpad_down),
        BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_LEFT), R.string.system_bind_dpad_left),
        BindingOption(ActionBinding(DefaultBindings.BTN_DPAD_RIGHT), R.string.system_bind_dpad_right),
    )

    private fun systemActionLabelRes(action: SystemAction): Int = when (action) {
        SystemAction.BACK -> R.string.system_action_back
        SystemAction.HOME -> R.string.system_action_home
        SystemAction.RECENTS -> R.string.system_action_recents
        SystemAction.VOLUME_UP -> R.string.system_action_volume_up
        SystemAction.VOLUME_DOWN -> R.string.system_action_volume_down
        SystemAction.BRIGHTNESS_UP -> R.string.system_action_brightness_up
        SystemAction.BRIGHTNESS_DOWN -> R.string.system_action_brightness_down
        SystemAction.SCREENSHOT -> R.string.system_action_screenshot
    }

    // ---------------------------------------------------------------- 映射状态（只读）

    /** 映射运行在 MouseService 内；这里只做状态展示。 */
    private fun renderMappingStatus() {
        if (!MouseRuntime.mappingRunning) {
            mappingStatus.setText(
                if (MouseService.gameMode) R.string.mapping_status_game_mode
                else R.string.mapping_status_idle
            )
            return
        }
        val config = MouseRuntime.mapperConfig ?: MapperConfig()
        mappingStatus.text = getString(
            R.string.mapping_status_running,
            config.deadzone,
            config.speedPxPerSec.toInt(),
            config.exponent
        )
    }

    private fun scheduleMappingRender() {
        if (mappingRenderScheduled) return
        mappingRenderScheduled = true
        mappingCursorValue.postDelayed({
            mappingRenderScheduled = false
            if (isFinishing || isDestroyed) return@postDelayed
            renderMappingPanel()
            renderGamepadPanel()
        }, MAPPING_RENDER_INTERVAL_MS)
    }

    private fun renderMappingPanel() {
        if (!::mappingCursorValue.isInitialized) return
        val running = MouseRuntime.mappingRunning
        mappingCursorValue.text = if (running) {
            getString(R.string.mapping_cursor_value, MouseRuntime.cursorX, MouseRuntime.cursorY)
        } else {
            getString(R.string.mapping_cursor_idle)
        }
        mappingVectorValue.text = getString(
            R.string.mapping_vector_value,
            MouseRuntime.leftX,
            MouseRuntime.leftY,
            MouseRuntime.rightX,
            MouseRuntime.rightY
        )
        val action = MouseRuntime.lastAction
        mappingActionValue.text = if (action == null) {
            getString(R.string.mapping_action_idle)
        } else {
            getString(
                R.string.mapping_action_value,
                getString(actionLabelRes(action)),
                gamepadTimeFormat.format(Date(MouseRuntime.lastActionAt))
            )
        }
    }

    private fun actionLabelRes(action: MapperAction): Int = when (action) {
        MapperAction.TAP -> R.string.mapping_action_tap
        MapperAction.RIGHT_CLICK -> R.string.mapping_action_right_click
        MapperAction.MIDDLE_CLICK -> R.string.mapping_action_middle_click
        MapperAction.DRAG_START -> R.string.mapping_action_drag_start
        MapperAction.DRAG_END -> R.string.mapping_action_drag_end
        MapperAction.SCROLL_UP -> R.string.mapping_action_scroll_up
        MapperAction.SCROLL_DOWN -> R.string.mapping_action_scroll_down
        MapperAction.PRIMARY_HOLD -> R.string.mapping_action_primary_hold
        MapperAction.PRIMARY_RELEASE -> R.string.mapping_action_primary_release
        MapperAction.SECONDARY_HOLD -> R.string.mapping_action_secondary_hold
        MapperAction.SECONDARY_RELEASE -> R.string.mapping_action_secondary_release
        MapperAction.SYSTEM_BACK -> R.string.system_action_back
        MapperAction.SYSTEM_HOME -> R.string.system_action_home
        MapperAction.SYSTEM_RECENTS -> R.string.system_action_recents
        MapperAction.SYSTEM_VOLUME_UP -> R.string.system_action_volume_up
        MapperAction.SYSTEM_VOLUME_DOWN -> R.string.system_action_volume_down
        MapperAction.SYSTEM_BRIGHTNESS_UP -> R.string.system_action_brightness_up
        MapperAction.SYSTEM_BRIGHTNESS_DOWN -> R.string.system_action_brightness_down
        MapperAction.SYSTEM_SCREENSHOT -> R.string.system_action_screenshot
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MAPPING_RENDER_INTERVAL_MS = 50L
        private const val MAPPING_POLL_MS = 100L
        private const val RUN_STATE_POLL_MS = 1000L
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val USER_SERVICE_WAIT_MS = 4000L
        private const val USER_SERVICE_POLL_MS = 100L
        private const val USER_SERVICE_REBIND_MS = 800L
    }
}

/** 系统动作下拉框候选项。 */
private data class BindingOption(val binding: ActionBinding, val labelRes: Int)
