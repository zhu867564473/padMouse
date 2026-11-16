package com.fseven.padmouse

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
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

    /** binder 调用与 shell 命令放到后台线程，避免阻塞 UI。 */
    private val injectExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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

        serviceManager = ShizukuServiceManager(this) { state ->
            runOnUiThread { renderUserServiceState(state) }
        }

        refreshButton.setOnClickListener { renderStatus() }
        requestButton.setOnClickListener { requestShizukuPermission() }
        installButton.setOnClickListener { openShizukuInstallPage() }
        selfTestButton.setOnClickListener { runSelfTest() }
        testTapButton.setOnClickListener { runTestTap() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        renderUserServiceState(UserServiceState.CONNECTING)
        serviceManager.start()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 应用/授权框返回后自动刷新状态。
        renderStatus()
        serviceManager.bindNow()
    }

    override fun onDestroy() {
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
}
