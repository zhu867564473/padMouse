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

/**
 * 主界面：展示 Shizuku 状态，并提供刷新、申请授权、未安装引导入口。
 * 不包含输入注入与无障碍逻辑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shizukuManager: ShizukuManager
    private lateinit var statusValue: TextView
    private lateinit var hintText: TextView
    private lateinit var refreshButton: Button
    private lateinit var requestButton: Button
    private lateinit var installButton: Button

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

        refreshButton.setOnClickListener { renderStatus() }
        requestButton.setOnClickListener { requestShizukuPermission() }
        installButton.setOnClickListener { openShizukuInstallPage() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 应用/授权框返回后自动刷新状态。
        renderStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
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
