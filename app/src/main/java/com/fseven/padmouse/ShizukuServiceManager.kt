package com.fseven.padmouse

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * App 侧 Shizuku UserService 绑定管理。
 *
 * 只负责绑定/解绑与状态回调；具体注入能力在 [PadMouseUserService]。
 */
class ShizukuServiceManager(
    context: Context,
    private val onStateChanged: (UserServiceState) -> Unit,
) {

    private val appContext: Context = context.applicationContext
    private var service: IPadMouse? = null
    private var bound = false

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, PadMouseUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            service = binder?.let { IPadMouse.Stub.asInterface(it) }
            onStateChanged(if (service != null) UserServiceState.READY else UserServiceState.FAILED)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            onStateChanged(UserServiceState.DISCONNECTED)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        bindNow()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bound = false
        service = null
        onStateChanged(UserServiceState.DISCONNECTED)
    }

    /** 注册 Shizuku binder 监听并尝试立即绑定。 */
    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        bindNow()
    }

    /** 解绑并移除监听，Activity 销毁时调用。 */
    fun stop() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        unbindNow()
    }

    /** 在权限已授予的前提下绑定 UserService。 */
    fun bindNow() {
        if (bound) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            onStateChanged(UserServiceState.FAILED)
        }
    }

    fun unbindNow() {
        try {
            if (bound) Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Throwable) {
            // 已断开时忽略。
        }
        bound = false
        service = null
    }

    val isReady: Boolean
        get() = service != null

    fun selfTest(): String = requireService().selfTest()

    fun tap(x: Float, y: Float) = requireService().tap(x, y)

    fun down(x: Float, y: Float) = requireService().down(x, y)

    fun move(x: Float, y: Float) = requireService().move(x, y)

    fun up(x: Float, y: Float) = requireService().up(x, y)

    fun scroll(x: Float, y: Float, h: Float, v: Float) = requireService().scroll(x, y, h, v)

    fun sendKey(keyCode: Int) = requireService().sendKey(keyCode)

    fun shellExec(cmd: String): String = requireService().shellExec(cmd)

    fun startGamepadCapture(listener: IGamepadListener) = requireService().startGamepadCapture(listener)

    fun stopGamepadCapture() = requireService().stopGamepadCapture()

    fun describeGamepads(): String = requireService().describeGamepads()

    private fun requireService(): IPadMouse =
        service ?: throw IllegalStateException("UserService 未连接，请先完成 Shizuku 授权")
}

/** UserService 连接状态。 */
enum class UserServiceState {
    CONNECTING,
    READY,
    DISCONNECTED,
    FAILED,
}
