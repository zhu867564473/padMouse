package com.fseven.padmouse

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App 侧 Shizuku UserService 绑定管理（进程内单例，由 [MouseRuntime] 持有）。
 *
 * - 使用 daemon UserService：进程常驻，反复绑定不会每次新建 shell 进程；
 * - Activity 与 MouseService 共用同一个绑定，避免重复连接；
 * - 解绑统一走 [unbindNow]（remove=false，保留 daemon 进程供复用）。
 */
class ShizukuServiceManager(
    context: Context,
) {

    private val appContext: Context = context.applicationContext
    private var service: IPadMouse? = null
    private var bound = false

    @Volatile
    private var started = false

    private val listeners = CopyOnWriteArrayList<(UserServiceState) -> Unit>()

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, PadMouseUserService::class.java.name)
        )
            // daemon 复用：进程常驻，重复 bind 不会累积 :input 进程。
            .daemon(true)
            .processNameSuffix("input")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            service = binder?.let { IPadMouse.Stub.asInterface(it) }
            notifyState(if (service != null) UserServiceState.READY else UserServiceState.FAILED)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            notifyState(UserServiceState.DISCONNECTED)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        bindNow()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bound = false
        service = null
        notifyState(UserServiceState.DISCONNECTED)
    }

    fun addListener(listener: (UserServiceState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (UserServiceState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyState(state: UserServiceState) {
        for (listener in listeners) {
            runCatching { listener(state) }
        }
    }

    /** 注册 Shizuku binder 监听并尝试立即绑定；重复调用无副作用。 */
    fun start() {
        if (started) {
            bindNow()
            return
        }
        started = true
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        bindNow()
    }

    /** 解绑并移除监听。daemon 进程不会因此被杀，下次启动可直接复用。 */
    fun stop() {
        started = false
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        unbindNow()
    }

    /** 在权限已授予的前提下绑定 UserService；失败路径也保证连接状态被清理。 */
    fun bindNow() {
        if (bound) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            Log.w(TAG, "绑定 UserService 失败", t)
            // 异常路径也要解绑，避免留下半连接状态。
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
            bound = false
            service = null
            notifyState(UserServiceState.FAILED)
        }
    }

    fun unbindNow() {
        try {
            // remove=false：保留 daemon 进程，重复启动复用同一个 :input 进程。
            Shizuku.unbindUserService(userServiceArgs, connection, false)
        } catch (t: Throwable) {
            Log.d(TAG, "解绑 UserService 失败（可能已断开）", t)
        } finally {
            bound = false
            service = null
            notifyState(UserServiceState.DISCONNECTED)
        }
    }

    val isReady: Boolean
        get() = service != null

    fun selfTest(): String = requireService().selfTest()

    fun tap(x: Float, y: Float) = requireService().tap(x, y)

    fun down(x: Float, y: Float) = requireService().down(x, y)

    fun move(x: Float, y: Float) = requireService().move(x, y)

    fun up(x: Float, y: Float) = requireService().up(x, y)

    fun scroll(x: Float, y: Float, h: Float, v: Float) = requireService().scroll(x, y, h, v)

    fun rightClick(x: Float, y: Float) = requireService().rightClick(x, y)

    fun middleClick(x: Float, y: Float) = requireService().middleClick(x, y)

    fun mouseDown(x: Float, y: Float, button: Int) = requireService().mouseDown(x, y, button)

    fun mouseUp(x: Float, y: Float, button: Int) = requireService().mouseUp(x, y, button)

    fun mouseMove(x: Float, y: Float, buttonState: Int) = requireService().mouseMove(x, y, buttonState)

    fun sendKey(keyCode: Int) = requireService().sendKey(keyCode)

    fun shellExec(cmd: String): String = requireService().shellExec(cmd)

    fun startGamepadCapture(listener: IGamepadListener) = requireService().startGamepadCapture(listener)

    fun stopGamepadCapture() = requireService().stopGamepadCapture()

    fun describeGamepads(): String = requireService().describeGamepads()

    // M10 手柄独占 / 模式切换：全部转发到 UserService（shell 侧持有 fd 与 EVIOCGRAB）。
    fun setExclusiveMode(enabled: Boolean) = requireService().setExclusiveMode(enabled)

    fun isExclusiveActive(): Boolean = requireService().isExclusiveActive()

    fun getExclusiveError(): String = requireService().getExclusiveError()

    fun setMouseMode(mouseMode: Boolean) = requireService().setMouseMode(mouseMode)

    fun isGameMode(): Boolean = requireService().isGameMode()

    private fun requireService(): IPadMouse =
        service ?: throw IllegalStateException("UserService 未连接，请先完成 Shizuku 授权")

    private companion object {
        private const val TAG = "ShizukuServiceManager"
    }
}

/** UserService 连接状态。 */
enum class UserServiceState {
    CONNECTING,
    READY,
    DISCONNECTED,
    FAILED,
}
