package com.fseven.padmouse

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 主界面：Shizuku 状态/授权 + 注入引擎自检与测试入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shizukuManager: ShizukuManager
    private lateinit var serviceManager: ShizukuServiceManager
    private lateinit var statusValue: TextView
    private lateinit var hintText: TextView
    private lateinit var refreshButton: Button
    private lateinit var requestButton: Button
    private lateinit var installButton: Button
    private lateinit var injectServiceStatus: TextView
    private lateinit var selfTestButton: Button
    private lateinit var testTapButton: Button
    private lateinit var injectResult: TextView
    private lateinit var gamepadSummary: TextView
    private lateinit var gamepadAxes: TextView
    private lateinit var gamepadEvents: TextView
    private lateinit var gamepadRefreshButton: Button

    // M4 光标悬浮窗
    private lateinit var overlayStatusValue: TextView
    private lateinit var grantOverlayButton: Button
    private lateinit var cursorToggle: SwitchCompat
    private lateinit var cursorSizeSeek: SeekBar
    private lateinit var cursorSizeValue: TextView
    private lateinit var cursorIdleSeek: SeekBar
    private lateinit var cursorIdleValue: TextView
    private lateinit var cursorColorGroup: RadioGroup

    /** 程序化同步开关时避免触发启动/停止逻辑。 */
    private var suppressCursorToggle = false

    private val cursorPrefs by lazy {
        getSharedPreferences(PREFS_CURSOR, Context.MODE_PRIVATE)
    }

    /** binder 调用与 shell 命令放到后台线程，避免阻塞 UI。 */
    private val injectExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val gamepadTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val gamepadKeyEvents = ArrayDeque<String>()
    private val gamepadAxisValues = LinkedHashMap<Int, Float>()
    private var gamepadCaptureStarted = false
    private var axisRenderScheduled = false

    /** shell 侧回传的手柄事件；oneway 回调可能来自 Binder 线程，统一切回 UI。 */
    private val gamepadListener = object : IGamepadListener.Stub() {
        override fun onEvent(type: Int, code: Int, value: Int) {
            if (type != 1) return // EV_KEY
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                addGamepadKeyEvent(code, value)
            }
        }

        override fun onAxis(code: Int, normalized: Float) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gamepadAxisValues[code] = normalized
                scheduleAxisRender()
            }
        }

        override fun onDeviceInfo(summary: String?) {
            if (summary.isNullOrBlank()) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gamepadSummary.text = summary
            }
        }
    }

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
                if (granted) {
                    renderUserServiceState(UserServiceState.CONNECTING)
                    serviceManager.bindNow()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuManager = ShizukuManager(this)

        statusValue = findViewById(R.id.shizuku_status_value)
        hintText = findViewById(R.id.shizuku_hint)
        refreshButton = findViewById(R.id.button_refresh_shizuku)
        requestButton = findViewById(R.id.button_request_shizuku)
        installButton = findViewById(R.id.button_install_shizuku)
        injectServiceStatus = findViewById(R.id.inject_service_status)
        selfTestButton = findViewById(R.id.button_self_test)
        testTapButton = findViewById(R.id.button_test_tap)
        injectResult = findViewById(R.id.inject_result)
        gamepadSummary = findViewById(R.id.gamepad_summary)
        gamepadAxes = findViewById(R.id.gamepad_axes)
        gamepadEvents = findViewById(R.id.gamepad_events)
        gamepadRefreshButton = findViewById(R.id.button_refresh_gamepad)
        overlayStatusValue = findViewById(R.id.overlay_status_value)
        grantOverlayButton = findViewById(R.id.button_grant_overlay)
        cursorToggle = findViewById(R.id.switch_cursor)
        cursorSizeSeek = findViewById(R.id.seek_cursor_size)
        cursorSizeValue = findViewById(R.id.cursor_size_value)
        cursorIdleSeek = findViewById(R.id.seek_cursor_idle)
        cursorIdleValue = findViewById(R.id.cursor_idle_value)
        cursorColorGroup = findViewById(R.id.radio_group_cursor_color)

        serviceManager = ShizukuServiceManager(this) { state ->
            runOnUiThread { renderUserServiceState(state) }
        }

        refreshButton.setOnClickListener { renderStatus() }
        requestButton.setOnClickListener { requestShizukuPermission() }
        installButton.setOnClickListener { openShizukuInstallPage() }
        selfTestButton.setOnClickListener { runSelfTest() }
        testTapButton.setOnClickListener { runTestTap() }
        gamepadRefreshButton.setOnClickListener { refreshGamepadInfo() }
        setupCursorControls()

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        renderUserServiceState(UserServiceState.CONNECTING)
        serviceManager.start()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 应用/授权框返回后自动刷新状态。
        renderStatus()
        updateOverlayPermissionUi()
        syncCursorToggle()
        serviceManager.bindNow()
    }

    override fun onDestroy() {
        gamepadCaptureStarted = false
        runCatching { serviceManager.stopGamepadCapture() }
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        serviceManager.stop()
        injectExecutor.shutdownNow()
        super.onDestroy()
    }

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
        val ready = state == UserServiceState.READY
        selfTestButton.isEnabled = ready
        testTapButton.isEnabled = ready
        gamepadRefreshButton.isEnabled = ready
        if (ready) {
            startGamepadCaptureIfNeeded()
        } else {
            gamepadCaptureStarted = false
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

    private fun startGamepadCaptureIfNeeded() {
        if (gamepadCaptureStarted || !serviceManager.isReady) return
        gamepadKeyEvents.clear()
        gamepadAxisValues.clear()
        gamepadEvents.setText(R.string.gamepad_events_idle)
        gamepadAxes.setText(R.string.gamepad_axes_idle)
        gamepadSummary.setText(R.string.gamepad_summary_scanning)
        try {
            serviceManager.startGamepadCapture(gamepadListener)
            gamepadCaptureStarted = true
            refreshGamepadInfo()
        } catch (_: Throwable) {
            gamepadCaptureStarted = false
        }
    }

    private fun refreshGamepadInfo() {
        if (!serviceManager.isReady) {
            Toast.makeText(this, R.string.gamepad_toast_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (!gamepadCaptureStarted) startGamepadCaptureIfNeeded()
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

    private fun addGamepadKeyEvent(code: Int, value: Int) {
        val time = gamepadTimeFormat.format(Date())
        gamepadKeyEvents.addFirst(getString(R.string.gamepad_key_event, time, code, value))
        while (gamepadKeyEvents.size > GAMEPAD_EVENT_LIMIT) gamepadKeyEvents.removeLast()
        gamepadEvents.text = gamepadKeyEvents.joinToString("\n")
    }

    /** 摇杆事件高频，合并到约 20fps 再刷新 UI。 */
    private fun scheduleAxisRender() {
        if (axisRenderScheduled) return
        axisRenderScheduled = true
        gamepadAxes.postDelayed({
            axisRenderScheduled = false
            if (isFinishing || isDestroyed) return@postDelayed
            renderAxes()
        }, AXIS_RENDER_INTERVAL_MS)
    }

    private fun renderAxes() {
        if (gamepadAxisValues.isEmpty()) return
        val values = gamepadAxisValues.entries
            .sortedBy { it.key }
            .joinToString("   ") { (code, value) ->
                val name = GamepadAxes.NAMES[code] ?: "code=0x${code.toString(16)}"
                "$name=${String.format(Locale.US, "%.2f", value)}"
            }
        gamepadAxes.text = getString(R.string.gamepad_axes_value, values)
    }

    private fun showHint(resId: Int) {
        hintText.setText(resId)
        hintText.visibility = View.VISIBLE
    }

    private fun requestShizukuPermission() {
        if (!shizukuManager.requestPermission(ShizukuManager.REQUEST_CODE_PERMISSION)) {
            // 状态可能已变化（服务被杀/已授权），刷新界面而非静默失败。
            renderStatus()
        }
    }

    // ---------------------------------------------------------------- M4 光标悬浮窗

    private fun setupCursorControls() {
        grantOverlayButton.setOnClickListener { requestOverlayPermission() }
        cursorToggle.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressCursorToggle) setCursorEnabled(isChecked)
        }

        // 大小滑杆：20-48dp
        cursorSizeSeek.max = MouseService.MAX_SIZE_DP - MouseService.MIN_SIZE_DP
        cursorSizeSeek.progress = savedCursorSizeDp() - MouseService.MIN_SIZE_DP
        updateCursorSizeLabel()
        cursorSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCursorSizeLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cursorPrefs.edit().putInt(KEY_CURSOR_SIZE, selectedCursorSizeDp()).apply()
                pushCursorAppearance()
            }
        })

        // 自动隐藏滑杆：1-10 秒
        cursorIdleSeek.max = MAX_IDLE_SECONDS - MIN_IDLE_SECONDS
        cursorIdleSeek.progress = savedCursorIdleSeconds() - MIN_IDLE_SECONDS
        updateCursorIdleLabel()
        cursorIdleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCursorIdleLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cursorPrefs.edit().putInt(KEY_CURSOR_IDLE_SECONDS, selectedCursorIdleSeconds()).apply()
                pushCursorAppearance()
            }
        })

        cursorColorGroup.check(
            when (savedCursorColor()) {
                MouseService.COLOR_BLACK -> R.id.radio_cursor_black
                MouseService.COLOR_YELLOW -> R.id.radio_cursor_yellow
                else -> R.id.radio_cursor_white
            }
        )
        cursorColorGroup.setOnCheckedChangeListener { _, checkedId ->
            cursorPrefs.edit().putInt(KEY_CURSOR_COLOR, colorForRadio(checkedId)).apply()
            pushCursorAppearance()
        }
    }

    private fun updateOverlayPermissionUi() {
        val granted = Settings.canDrawOverlays(this)
        overlayStatusValue.setText(
            if (granted) R.string.cursor_overlay_granted else R.string.cursor_overlay_denied
        )
        overlayStatusValue.setTextColor(
            getColor(if (granted) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        grantOverlayButton.visibility = if (granted) View.GONE else View.VISIBLE
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

    private fun setCursorEnabled(enabled: Boolean) {
        if (!enabled) {
            stopService(Intent(this, MouseService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.cursor_toast_need_overlay, Toast.LENGTH_SHORT).show()
            syncCursorToggle()
            requestOverlayPermission()
            return
        }
        startCursorService()
    }

    private fun startCursorService() {
        val intent = Intent(this, MouseService::class.java)
            .setAction(MouseService.ACTION_START)
            .putExtra(MouseService.EXTRA_SIZE_DP, selectedCursorSizeDp())
            .putExtra(MouseService.EXTRA_COLOR, savedCursorColor())
            .putExtra(MouseService.EXTRA_IDLE_TIMEOUT_MS, selectedCursorIdleSeconds() * 1000L)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.cursor_toast_start_failed, Toast.LENGTH_SHORT).show()
            syncCursorToggle()
        }
    }

    /** 服务已在运行时，把最新的外观设置推给服务，不需要重启。 */
    private fun pushCursorAppearance() {
        if (!MouseService.isRunning) return
        val intent = Intent(this, MouseService::class.java)
            .setAction(MouseService.ACTION_UPDATE)
            .putExtra(MouseService.EXTRA_SIZE_DP, selectedCursorSizeDp())
            .putExtra(MouseService.EXTRA_COLOR, savedCursorColor())
            .putExtra(MouseService.EXTRA_IDLE_TIMEOUT_MS, selectedCursorIdleSeconds() * 1000L)
        try {
            startService(intent)
        } catch (_: Throwable) {
            // 服务可能刚好停止，外观设置已持久化，下次启动会生效。
        }
    }

    private fun syncCursorToggle() {
        suppressCursorToggle = true
        cursorToggle.isChecked = MouseService.isRunning
        suppressCursorToggle = false
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
        cursorIdleSeek.progress + MIN_IDLE_SECONDS

    private fun savedCursorSizeDp(): Int = cursorPrefs
        .getInt(KEY_CURSOR_SIZE, MouseService.DEFAULT_SIZE_DP)
        .coerceIn(MouseService.MIN_SIZE_DP, MouseService.MAX_SIZE_DP)

    private fun savedCursorColor(): Int = cursorPrefs
        .getInt(KEY_CURSOR_COLOR, MouseService.COLOR_WHITE)

    private fun savedCursorIdleSeconds(): Int = cursorPrefs
        .getInt(KEY_CURSOR_IDLE_SECONDS, DEFAULT_IDLE_SECONDS)
        .coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS)

    private fun colorForRadio(checkedId: Int): Int = when (checkedId) {
        R.id.radio_cursor_black -> MouseService.COLOR_BLACK
        R.id.radio_cursor_yellow -> MouseService.COLOR_YELLOW
        else -> MouseService.COLOR_WHITE
    }

    companion object {
        private const val GAMEPAD_EVENT_LIMIT = 10
        private const val AXIS_RENDER_INTERVAL_MS = 50L

        private const val PREFS_CURSOR = "cursor_settings"
        private const val KEY_CURSOR_SIZE = "cursor_size_dp"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_CURSOR_IDLE_SECONDS = "cursor_idle_seconds"
        private const val MIN_IDLE_SECONDS = 1
        private const val MAX_IDLE_SECONDS = 10
        private const val DEFAULT_IDLE_SECONDS = 3
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
}
