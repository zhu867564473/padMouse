package com.fseven.padmouse

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态检测与授权入口。
 *
 * 状态判定顺序：未安装 -> 服务未运行 -> 未授权 -> 已授权。
 * 仅依赖 Shizuku 官方 API，不包含任何无障碍兜底逻辑。
 */
class ShizukuManager(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 检测当前 Shizuku 接入状态。 */
    fun getStatus(): ShizukuStatus {
        if (!isInstalled()) return ShizukuStatus.NOT_INSTALLED
        if (!isServiceRunning()) return ShizukuStatus.NOT_RUNNING
        return if (isGranted()) ShizukuStatus.GRANTED else ShizukuStatus.NOT_GRANTED
    }

    /** Shizuku 应用是否已安装。 */
    fun isInstalled(): Boolean =
        try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Shizuku 服务 binder 是否可达，即服务是否在运行。 */
    fun isServiceRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }

    /** 本应用是否已获得 Shizuku 授权。 */
    fun isGranted(): Boolean =
        try {
            if (Shizuku.isPreV11()) {
                // v11 之前的 Shizuku 没有 checkSelfPermission，无法在此判断授权状态。
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (t: Throwable) {
            false
        }

    /**
     * 发起授权请求，结果通过
     * [Shizuku.OnRequestPermissionResultListener] 回调到 Activity。
     *
     * @return true 表示请求已发出；false 表示当前不满足申请条件（未运行/已授权/调用异常）。
     */
    fun requestPermission(requestCode: Int): Boolean =
        try {
            if (!isServiceRunning() || isGranted()) {
                false
            } else {
                Shizuku.requestPermission(requestCode)
                true
            }
        } catch (t: Throwable) {
            false
        }

    companion object {
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val REQUEST_CODE_PERMISSION = 1001
        const val SHIZUKU_WEBSITE = "https://shizuku.rikka.app/"
        const val SHIZUKU_MARKET_URI = "market://details?id=$SHIZUKU_PACKAGE_NAME"
    }
}

/** Shizuku 接入状态。 */
enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    NOT_GRANTED,
    GRANTED,
}
