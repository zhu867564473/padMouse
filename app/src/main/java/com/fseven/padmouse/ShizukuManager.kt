package com.fseven.padmouse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态检测与授权入口。
 *
 * 状态判定顺序：未安装 -> 服务未运行 -> 未授权 -> 已授权。
 * 仅依赖 Shizuku 官方 API，不包含任何无障碍兜底逻辑。
 *
 * 授权后置动作（M8）：Shizuku 已授权时用 shell 权限代开悬浮窗与通知权限，
 * 每条命令执行后都用系统 API 复核，命令返回 0 不等于权限已生效。
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

    // ------------------------------------------------------------ 权限校验（以系统 API 为准）

    /** 悬浮窗权限当前是否真的可用。 */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /** 通知权限当前是否真的可用；Android 13 以下没有该运行时权限，视为可用。 */
    fun hasNotificationPermission(): Boolean =
        if (!supportsNotificationPermission()) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 当前系统是否存在通知权限这一运行时权限（Android 13+）。 */
    fun supportsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 授权后置动作：用 Shizuku 的 shell 权限依次代开悬浮窗与通知权限。
     *
     * 只在 Shizuku 已授权时调用。[shell] 负责执行命令并返回合并输出，
     * 非 0 退出由实现方包装为 `ERROR: exit=`。每一步的最终状态一律用
     * [canDrawOverlays] / [hasNotificationPermission] 复核，命令返回 0 不作为授权依据。
     *
     * @param onStep 步骤状态回调，可在任意线程触发，用于逐行刷新界面。
     */
    fun autoGrantPermissions(
        shell: (String) -> String,
        onStep: (AutoGrantStep, AutoGrantStatus, String) -> Unit = { _, _, _ -> },
    ): List<AutoGrantResult> {
        val results = ArrayList<AutoGrantResult>(2)

        // 1) 悬浮窗：仅当当前确实未授予时执行 appops set。
        if (canDrawOverlays()) {
            results += AutoGrantResult(AutoGrantStep.OVERLAY, AutoGrantStatus.GRANTED, changed = false)
        } else {
            onStep(AutoGrantStep.OVERLAY, AutoGrantStatus.REQUESTING, "")
            val command = "appops set ${appContext.packageName} SYSTEM_ALERT_WINDOW allow"
            val output = runShell(shell, command)
            // 系统 API 复核，而不是只看命令返回码。
            val granted = canDrawOverlays()
            val result = AutoGrantResult(
                step = AutoGrantStep.OVERLAY,
                status = if (granted) AutoGrantStatus.GRANTED else AutoGrantStatus.FAILED,
                changed = granted,
                detail = output,
            )
            results += result
            onStep(result.step, result.status, output)
        }

        // 2) 通知：仅 Android 13+ 且当前未授予时执行 pm grant。
        when {
            !supportsNotificationPermission() ->
                results += AutoGrantResult(
                    AutoGrantStep.NOTIFICATION,
                    AutoGrantStatus.UNSUPPORTED,
                    changed = false,
                )

            hasNotificationPermission() ->
                results += AutoGrantResult(AutoGrantStep.NOTIFICATION, AutoGrantStatus.GRANTED, false)

            else -> {
                onStep(AutoGrantStep.NOTIFICATION, AutoGrantStatus.REQUESTING, "")
                val command =
                    "pm grant ${appContext.packageName} android.permission.POST_NOTIFICATIONS"
                val output = runShell(shell, command)
                val granted = hasNotificationPermission()
                val result = AutoGrantResult(
                    step = AutoGrantStep.NOTIFICATION,
                    status = if (granted) AutoGrantStatus.GRANTED else AutoGrantStatus.FAILED,
                    changed = granted,
                    detail = output,
                )
                results += result
                onStep(result.step, result.status, output)
            }
        }

        return results
    }

    /**
     * 在未绑定 UserService 的组件（如 MouseService）里以 Shizuku shell 身份执行一次性命令。
     *
     * Shizuku 13.x 把 Shizuku.newProcess 改为 private，但仍与 UserService 共用同一 shell 权限；
     * 这里通过反射走同一通道，避免前台服务为了自检再绑一次 UserService、
     * 解绑时误杀主界面正在使用的注入进程。反射不可用时返回 ERROR，由调用方走手动兜底。
     */
    fun shell(command: String): String = runShell({ execShellDirect(it) }, command)

    private fun execShellDirect(command: String): String =
        try {
            if (!isGranted()) {
                "ERROR: Shizuku 未授权"
            } else {
                val method = newProcessMethod
                    ?: return "ERROR: Shizuku.newProcess 不可用"
                val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exit = process.waitFor()
                when {
                    exit == 0 -> output
                    output.isEmpty() -> "ERROR: exit=$exit"
                    else -> "$output\nERROR: exit=$exit"
                }
            }
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }

    /** Shizuku.newProcess 的反射句柄（private static）；只初始化一次。 */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    private fun runShell(shell: (String) -> String, command: String): String =
        try {
            shell(command).trim()
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
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

/** 一键授权覆盖的权限项。 */
enum class AutoGrantStep {
    OVERLAY,
    NOTIFICATION,
}

/** 单步授权状态；界面文案由 MainActivity 映射。 */
enum class AutoGrantStatus {
    REQUESTING,
    GRANTED,
    FAILED,
    UNSUPPORTED,
}

/**
 * 单步授权结果。
 *
 * [changed] 表示本次命令确实把权限从无变有（用于「已自动补开」提示）；
 * 已授予 / 不支持的情况下为 false。
 */
data class AutoGrantResult(
    val step: AutoGrantStep,
    val status: AutoGrantStatus,
    val changed: Boolean,
    val detail: String = "",
)
