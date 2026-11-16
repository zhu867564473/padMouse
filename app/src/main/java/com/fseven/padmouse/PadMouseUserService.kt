package com.fseven.padmouse

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 运行在 Shizuku UserService 进程（shell uid）内的注入实现。
 *
 * 通过反射获取隐藏的 InputManager#getInstance() 与 injectInputEvent(InputEvent, int)，
 * 以 shell 权限直接向系统注入触摸/滚轮事件。sendKey 与 shellExec 走 sh 命令。
 */
class PadMouseUserService : IPadMouse.Stub() {

    private val inputManager: Any? by lazy { resolveInputManager() }
    private val injectMethod: Method? by lazy { resolveInjectMethod() }

    /** 触摸手势状态，保证 MOVE/UP 复用 DOWN 的 downTime。 */
    private val gestureLock = Any()
    private var pointerDown = false
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    override fun tap(x: Float, y: Float) {
        try {
            down(x, y)
            up(x, y)
        } catch (t: Throwable) {
            // 兜底：反射注入不可用时退回 shell 的 input 命令（同 uid 能力）。
            val result = shellExec("input tap ${x.toInt()} ${y.toInt()}")
            if (result.isBlank() || result.startsWith("ERROR")) throw t
        }
    }

    override fun down(x: Float, y: Float) {
        synchronized(gestureLock) {
            val now = SystemClock.uptimeMillis()
            pointerDown = true
            downTime = now
            lastX = x
            lastY = y
            injectTouch(MotionEvent.ACTION_DOWN, now, x, y)
        }
    }

    override fun move(x: Float, y: Float) {
        synchronized(gestureLock) {
            val now = SystemClock.uptimeMillis()
            if (!pointerDown) {
                // 没有 DOWN 的孤立 MOVE：用当前时间作为 downTime 尽力注入。
                downTime = now
                lastX = x
                lastY = y
            }
            injectTouch(MotionEvent.ACTION_MOVE, now, x, y)
        }
    }

    override fun up(x: Float, y: Float) {
        synchronized(gestureLock) {
            val now = SystemClock.uptimeMillis()
            if (!pointerDown) downTime = now
            pointerDown = false
            lastX = x
            lastY = y
            injectTouch(MotionEvent.ACTION_UP, now, x, y)
        }
    }

    override fun scroll(x: Float, y: Float, h: Float, v: Float) {
        val now = SystemClock.uptimeMillis()
        // MotionEvent#setAxisValue 为隐藏 API，这里走公开的 PointerCoords#setAxisValue。
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
                setAxisValue(MotionEvent.AXIS_HSCROLL, h)
                setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            }
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL,
            1, properties, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    override fun sendKey(keyCode: Int) {
        val result = shellExec("input keyevent $keyCode")
        if (result.startsWith("ERROR")) throw IllegalStateException(result)
    }

    override fun shellExec(cmd: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = process.waitFor()
            if (exit == 0) output else if (output.isEmpty()) "ERROR: exit=$exit" else "$output\nERROR: exit=$exit"
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun selfTest(): String {
        val sb = StringBuilder()
        sb.append("uid=").append(Process.myUid()).append(" (shell=2000)  pid=").append(Process.myPid())
        sb.append('\n')

        val manager = inputManager
        sb.append("InputManager: ").append(manager?.javaClass?.name ?: "null（反射 getInstance 失败）")
        sb.append('\n')

        val method = injectMethod
        if (method == null) {
            sb.append("injectInputEvent: 未找到（反射失败）")
        } else {
            val accessible = try {
                method.isAccessible = true
                true
            } catch (t: Throwable) {
                false
            }
            sb.append("injectInputEvent: ").append(method.declaringClass.name)
                .append("#" ).append(method.name)
                .append(" 可调用=").append(accessible)
        }
        sb.append('\n')

        sb.append("sh: ").append(if (File("/system/bin/sh").exists()) "ok" else "missing")
        sb.append('\n')

        val inputCmd = shellExec("command -v input")
        sb.append("input 命令: ").append(if (inputCmd.isBlank()) "missing" else inputCmd)
        sb.append('\n')

        sb.append("结论: ").append(
            if (manager != null && method != null) "可注入" else "不可注入（检查 Shizuku 授权/系统版本）"
        )
        return sb.toString()
    }

    private fun injectTouch(action: Int, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            inject(event)
        } finally {
            event.recycle()
        }
    }

    private fun inject(event: InputEvent): Boolean {
        val method = injectMethod
            ?: throw IllegalStateException("injectInputEvent 不可用：${selfTest()}")
        val target = inputManager
            ?: throw IllegalStateException("InputManager 实例不可用：${selfTest()}")
        try {
            return method.invoke(target, event, INJECT_MODE_ASYNC) as? Boolean ?: false
        } catch (e: InvocationTargetException) {
            throw (e.targetException as? Exception) ?: e
        }
    }

    /** 反射 InputManager#getInstance()；失败时退回 ServiceManager + IInputManager.Stub。 */
    private fun resolveInputManager(): Any? {
        try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val getInstance = clazz.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            getInstance.invoke(null)?.let { return it }
        } catch (_: Throwable) {
            // 继续尝试 IInputManager。
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder ?: return null
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.isAccessible = true
            asInterface.invoke(null, binder)
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射查找 injectInputEvent(InputEvent, int)。 */
    private fun resolveInjectMethod(): Method? {
        val target = inputManager ?: return null
        val signature = arrayOf<Class<*>>(InputEvent::class.java, Int::class.javaPrimitiveType!!)
        try {
            val method = target.javaClass.getMethod("injectInputEvent", *signature)
            method.isAccessible = true
            return method
        } catch (_: Throwable) {
            // Proxy/隐藏接口场景退回 IInputManager 接口声明。
        }
        return try {
            val iface = Class.forName("android.hardware.input.IInputManager")
            val method = iface.getMethod("injectInputEvent", *signature)
            method.isAccessible = true
            method
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        private const val INJECT_MODE_ASYNC = 0
    }
}
