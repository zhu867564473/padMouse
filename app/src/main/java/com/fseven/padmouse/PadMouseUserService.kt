package com.fseven.padmouse

import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku UserService 进程（shell uid）内的注入实现。
 *
 * 通过反射获取隐藏的 InputManager#getInstance() 与 injectInputEvent(InputEvent, int)，
 * 以 shell 权限直接向系统注入触摸/滚轮事件。sendKey 与 shellExec 走 sh 命令。
 */
class PadMouseUserService : IPadMouse.Stub() {

    private val inputManager: Any? by lazy { resolveInputManager() }
    private val injectMethod: Method? by lazy { resolveInjectMethod() }

    /** 手柄读取器：捕获线程、热插拔重扫全部在 UserService 进程内。 */
    private val gamepadLock = Any()
    private var gamepadReader: GamepadReader? = null

    /** 保留监听器，独占开关变化时用新配置重建读取器。 */
    private var gamepadListener: IGamepadListener? = null

    /** App 提供的加载路径，供 GamepadReader 显式 System.load 原生 ioctl 库。 */
    private var libPaths: NativeLibPaths? = null

    /** 用户设置：独占手柄（默认开启）。 */
    private var exclusiveMode = true

    /** 当前模式：true = 鼠标模式（独占 + 映射），false = 游戏模式（交还系统）。 */
    private var mouseMode = true

    /** 触摸手势状态，保证 MOVE/UP 复用 DOWN 的 downTime。 */
    private val gestureLock = Any()
    private var pointerDown = false
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** 鼠标键手势状态（M5）：保证 MOVE/UP 复用 DOWN 的 downTime 与 buttonState。 */
    private val mouseLock = Any()
    private var mouseDown = false
    private var mouseDownTime = 0L
    private var mouseButtonState = 0

    /** M12：UHID 虚拟鼠标；fd、上报与销毁全部在 shell 进程内。 */
    private val uhidLock = Any()
    /** 主屏设备（uniq 空、BUS_VIRTUAL）与副屏设备（uniq=副屏 id、BUS_USB）同时存在。 */
    private var primaryMouse: UhidMouse? = null
    private var secondaryMouse: UhidMouse? = null
    /** 0 = 主屏设备在发事件；1 = 副屏设备。 */
    private var activeIndex = 0

    /** M13：手柄 FF_RUMBLE 下发状态；到时自动 stop + destroy。 */
    private val rumbleLock = Any()
    private var lastRumbleAt = 0L
    private var activeEffectId = -1
    private var rumbleError: String? = null
    private val rumbleExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gamepad-rumble").apply { isDaemon = true }
    }

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

    override fun rightClick(x: Float, y: Float) {
        clickMouse(x, y, MotionEvent.BUTTON_SECONDARY)
    }

    override fun middleClick(x: Float, y: Float) {
        clickMouse(x, y, MotionEvent.BUTTON_TERTIARY)
    }

    override fun mouseDown(x: Float, y: Float, button: Int) {
        synchronized(mouseLock) {
            val now = SystemClock.uptimeMillis()
            val first = !mouseDown
            if (first) {
                mouseDown = true
                mouseDownTime = now
            }
            mouseButtonState = mouseButtonState or button
            injectMouse(
                if (first) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                mouseDownTime, now, x, y, mouseButtonState
            )
        }
    }

    override fun mouseUp(x: Float, y: Float, button: Int) {
        synchronized(mouseLock) {
            val now = SystemClock.uptimeMillis()
            if (!mouseDown) mouseDownTime = now
            mouseButtonState = mouseButtonState and button.inv()
            // UP 事件携带释放后的 buttonState（通常为 0）。
            injectMouse(MotionEvent.ACTION_UP, mouseDownTime, now, x, y, mouseButtonState)
            mouseDown = false
            mouseButtonState = 0
        }
    }

    override fun mouseMove(x: Float, y: Float, buttonState: Int) {
        synchronized(mouseLock) {
            val now = SystemClock.uptimeMillis()
            val down = mouseDown || buttonState != 0
            if (down && !mouseDown) {
                mouseDown = true
                mouseDownTime = now
            }
            if (down) mouseButtonState = buttonState
            injectMouse(
                if (down) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
                if (down) mouseDownTime else now,
                now, x, y, buttonState
            )
        }
    }

    override fun sendKey(keyCode: Int) {
        val result = shellExec("input keyevent $keyCode")
        if (result.startsWith("ERROR")) throw IllegalStateException(result)
    }

    override fun setNativeLibPaths(nativeLibraryDir: String?, codeCacheDir: String?, apkPath: String?) {
        synchronized(gamepadLock) {
            libPaths = NativeLibPaths(nativeLibraryDir, codeCacheDir, apkPath)
        }
    }

    override fun startGamepadCapture(listener: IGamepadListener?) {
        val target = listener ?: return
        synchronized(gamepadLock) {
            gamepadReader?.stop()
            gamepadListener = target
            gamepadReader = GamepadReader(target, exclusiveMode, mouseMode, libPaths)
                .also { it.start() }
        }
    }

    override fun stopGamepadCapture() {
        synchronized(gamepadLock) {
            gamepadReader?.stop()
            gamepadReader = null
            gamepadListener = null
            // 停止后回到鼠标模式，下次启动不会残留「游戏模式」。
            mouseMode = true
        }
        // 捕获停止通常意味着服务收尾：一并销毁虚拟鼠标，避免系统里残留设备。
        destroyUhidMouse()
    }

    /**
     * 设置独占开关：读取器必须重建（先 ungrab 关闭旧 fd，再按新配置重开）。
     * 运行中的映射不受影响，只有抓取行为变化。
     */
    override fun setExclusiveMode(enabled: Boolean) {
        synchronized(gamepadLock) {
            if (exclusiveMode == enabled) return
            exclusiveMode = enabled
            val target = gamepadListener ?: return
            gamepadReader?.stop()
            gamepadReader = GamepadReader(target, exclusiveMode, mouseMode, libPaths)
                .also { it.start() }
        }
    }

    override fun isExclusiveActive(): Boolean {
        val reader = synchronized(gamepadLock) { gamepadReader } ?: return false
        return reader.isExclusiveActive()
    }

    override fun getExclusiveError(): String {
        val reader = synchronized(gamepadLock) { gamepadReader } ?: return ""
        return reader.exclusiveError() ?: ""
    }

    /** 鼠标模式 / 游戏模式切换：只释放或恢复 EVIOCGRAB，fd 始终保持打开。 */
    override fun setMouseMode(mouseMode: Boolean) {
        synchronized(gamepadLock) {
            this.mouseMode = mouseMode
            gamepadReader?.setMouseMode(mouseMode)
        }
        // 游戏模式不允许 UHID 继续占用设备；切回鼠标模式后由 App 重新 create。
        if (!mouseMode) destroyUhidMouse()
    }

    override fun isGameMode(): Boolean = synchronized(gamepadLock) { !mouseMode }

    // ---------------------------------------------------------------- M12 UHID 虚拟鼠标

    override fun createUhidMouse(uniq: String): String = synchronized(uhidLock) {
        destroyLocked()
        // 主屏设备必须用主屏自己的 uniqueId，不能用空串：
        // 空 uniq 在系统里表示「未指定」，会关联到最近的活动显示（往往是副屏），
        // 导致切回主屏时光标仍留在副屏。
        val primaryUniq = primaryDisplayUniqueId()
        val primary = UhidMouse()
        val primaryStatus = primary.create(primaryUniq, secondary = false)
        primaryMouse = if (primaryStatus.startsWith("active=true")) primary else null
        if (primaryMouse == null) runCatching { primary.destroy() }

        // 副屏设备：uniq 非空时才创建。两个设备同时存在，切换只改活跃指针。
        if (uniq.isNotEmpty()) {
            val secondary = UhidMouse()
            val secStatus = secondary.create(uniq, secondary = true)
            secondaryMouse = if (secStatus.startsWith("active=true")) secondary else null
            if (secondaryMouse == null) runCatching { secondary.destroy() }
        } else {
            secondaryMouse = null
        }
        activeIndex = 0
        Log.i(
            "PadMouseUser",
            "UHID 双设备：primary=${primaryMouse != null}($primaryUniq) secondary=${secondaryMouse != null}($uniq)"
        )
        primaryMouse?.status() ?: "ERROR: 主屏设备创建失败"
    }

    /** 主屏（displayId 0）的 uniqueId；空串表示取不到。 */
    private fun primaryDisplayUniqueId(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys display 2>/dev/null"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        Regex("displayId\\s+0[\\s\\S]{0,6000}?uniqueId\\s+\"(local:[^\"]+)\"")
            .find(out)?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    override fun setActiveUhid(which: Int) {
        synchronized(uhidLock) {
            activeIndex = if (which == 1 && secondaryMouse != null) 1 else 0
            Log.i("PadMouseUser", "活跃设备 → ${if (activeIndex == 1) "副屏" else "主屏"}")
        }
    }

    override fun secondaryUhidReady(): Boolean = synchronized(uhidLock) {
        secondaryMouse?.status()?.startsWith("active=true") == true
    }

    /**
     * 查外接屏（副屏）的 uniqueId。
     *
     * Display.getUniqueId() 是 hidden API，普通 App 反射会被 hiddenapi 拦截；
     * 这里跑在 shell 进程里，直接执行 dumpsys display 解析，最稳。
     */
    override fun externalDisplayUniqueId(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys display 2>/dev/null"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        // DisplayInfo 里形如：displayId 2, ... uniqueId "local:4626..."
        val pairs = Regex("displayId\\s+(\\d+)[\\s\\S]{0,4000}?uniqueId\\s+\"(local:[^\"]+)\"")
            .findAll(out)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .toList()
        Log.i("PadMouseUser", "display uniqueIds=" + pairs.joinToString { "${it.first}:${it.second}" })
        pairs.firstOrNull { it.first != 0 }?.second ?: ""
    }.getOrDefault("")

    override fun destroyUhidMouse() {
        synchronized(uhidLock) { destroyLocked() }
    }

    private fun destroyLocked() {
        primaryMouse?.destroy()
        primaryMouse = null
        secondaryMouse?.destroy()
        secondaryMouse = null
        activeIndex = 0
    }

    private fun activeMouseLocked(): UhidMouse? =
        if (activeIndex == 1) secondaryMouse else primaryMouse

    override fun uhidMouseMove(dx: Int, dy: Int) {
        synchronized(uhidLock) { activeMouseLocked()?.move(dx, dy) }
    }

    override fun uhidMouseButton(buttonMask: Int) {
        synchronized(uhidLock) { activeMouseLocked()?.setButtons(buttonMask) }
    }

    override fun uhidMouseWheel(delta: Int) {
        synchronized(uhidLock) { activeMouseLocked()?.wheel(delta) }
    }

    override fun uhidStatus(): String = synchronized(uhidLock) {
        activeMouseLocked()?.status() ?: "active=false (not created)"
    }

    override fun describeGamepads(): String {
        val reader = synchronized(gamepadLock) { gamepadReader }
        return reader?.describe() ?: GamepadReader.describeOnce()
    }

    // ---------------------------------------------------------------- M13 手柄震动（FF_RUMBLE）

    /**
     * 直接向手柄写 FF_RUMBLE：EVIOCSFF 分配 effect → EV_FF 启动。
     *
     * 连续触发节流 60ms（避免点击抖动把内核 effect 表打满）；strength 0 直接跳过。
     * 成功返回 effect id（>= 0）；负值表示失败（-1 不可用、-3 参数、-4 节流）。
     */
    override fun rumble(durationMs: Int, strength: Int): Int {
        if (durationMs <= 0 || strength <= 0) return -3
        synchronized(rumbleLock) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRumbleAt < RUMBLE_THROTTLE_MS) return -4
            lastRumbleAt = now
        }
        val reader = synchronized(gamepadLock) { gamepadReader }
            ?: return unavailableRumble("未启动手柄读取")
        val target = reader.rumbleTarget()
            ?: return unavailableRumble(
                reader.hapticsStatus().removePrefix(HAPTICS_PREFIX).ifBlank { "设备不支持 FF_RUMBLE" }
            )
        if (!NativeIoctl.ensureLoaded(libPaths)) {
            return unavailableRumble("原生 ioctl 库不可用：" + (NativeIoctl.loadError ?: "未知原因"))
        }
        val effectId = try {
            NativeIoctl.nativeRumble(target.fd, durationMs, strength)
        } catch (t: Throwable) {
            return unavailableRumble(t.javaClass.simpleName + ": " + (t.message ?: "nativeRumble 调用失败"))
        }
        if (effectId < 0) {
            return unavailableRumble(errnoText(-effectId))
        }
        synchronized(rumbleLock) {
            rumbleError = null
            activeEffectId = effectId
        }
        // 到期回收：先写 EV_FF 0 停震，再 EVIOCRMFF 释放 effect。
        rumbleExecutor.schedule({
            runCatching { NativeIoctl.nativeRumbleStop(target.fd, effectId) }
            runCatching { NativeIoctl.nativeRumbleDestroy(target.fd, effectId) }
            synchronized(rumbleLock) {
                if (activeEffectId == effectId) activeEffectId = -1
            }
        }, durationMs.toLong() + RUMBLE_STOP_DELAY_MS, TimeUnit.MILLISECONDS)
        return effectId
    }

    override fun rumbleStop(effectId: Int) {
        if (effectId < 0) return
        val reader = synchronized(gamepadLock) { gamepadReader } ?: return
        val target = reader.rumbleTarget() ?: return
        runCatching { NativeIoctl.nativeRumbleStop(target.fd, effectId) }
        synchronized(rumbleLock) {
            if (activeEffectId == effectId) activeEffectId = -1
        }
    }

    override fun hapticsStatus(): String {
        val reader = synchronized(gamepadLock) { gamepadReader }
            ?: return HAPTICS_PREFIX + "未启动手柄读取"
        val deviceStatus = reader.hapticsStatus()
        if (deviceStatus.isNotBlank()) return deviceStatus
        synchronized(rumbleLock) {
            rumbleError?.let { return HAPTICS_PREFIX + it }
        }
        return ""
    }

    /** 记录失败原因供 hapticsStatus() 展示，并返回失败码。 */
    private fun unavailableRumble(reason: String): Int {
        synchronized(rumbleLock) { rumbleError = reason }
        return -1
    }

    /** errno → 可读原因。 */
    private fun errnoText(errno: Int): String = when (errno) {
        1 -> "EPERM(1)"
        6 -> "ENXIO(6)"
        13 -> "EACCES(13)"
        16 -> "EBUSY(16)"
        19 -> "ENODEV(19)"
        22 -> "EINVAL(22)"
        25 -> "ENOTTY(25)"
        else -> "errno=" + errno
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
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("  android=").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")")
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

    /** 独立的右键/中键点击：DOWN + UP 一对，不干扰按住手势状态。 */
    private fun clickMouse(x: Float, y: Float, button: Int) {
        synchronized(mouseLock) {
            val now = SystemClock.uptimeMillis()
            injectMouse(MotionEvent.ACTION_DOWN, now, now, x, y, button)
            injectMouse(MotionEvent.ACTION_UP, now, now + 1, x, y, 0)
        }
    }

    /** 以 SOURCE_MOUSE 注入带 buttonState 的指针事件。 */
    private fun injectMouse(
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
        buttonState: Int,
    ) {
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
            }
        )
        val event = MotionEvent.obtain(
            downTime, eventTime, action,
            1, properties, coords,
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            inject(event)
        } finally {
            event.recycle()
        }
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

        /** M13 手柄震动：连续触发节流 >= 60ms，避免内核 effect 表抖动。 */
        private const val RUMBLE_THROTTLE_MS = 60L

        /** 到期回收前多等一点，确保内核 replay.length 已走完。 */
        private const val RUMBLE_STOP_DELAY_MS = 20L

        private const val HAPTICS_PREFIX = "手柄震动不可用："
    }
}
