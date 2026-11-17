package com.fseven.padmouse

import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 在 UserService（Shizuku shell uid）进程内读取手柄原始输入。
 *
 * 数据来源是 /dev/input/eventX，而不是 Activity 的按键焦点，因此不会抢占前台焦点。
 * 每个手柄用独立线程阻塞读取 24 字节 input_event；监控线程负责扫描设备、热插拔与读失败后重连。
 */
class GamepadReader(
    private val listener: IGamepadListener?,
    /** M10：打开设备后是否立刻 EVIOCGRAB 独占（系统输入层不再派发）。 */
    exclusive: Boolean = true,
    /** 鼠标模式 = 独占 + 映射；游戏模式 = 释放抓取但 fd 保持打开（热键仍可读）。 */
    mouseMode: Boolean = true,
) {

    private val scanLock = Any()
    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, DeviceSession>()

    @Volatile private var running = false
    @Volatile private var refreshRequested = false
    @Volatile private var lastSummary: String = ""
    @Volatile private var lastScanError: String? = null

    /** 独占开关（用户设置）；关闭后行为退回旁听。 */
    @Volatile private var exclusiveEnabled: Boolean = exclusive

    /** true = 鼠标模式，false = 游戏模式（不抓取，事件交给系统）。 */
    @Volatile private var mouseMode: Boolean = mouseMode

    /** 最近一次抓取失败的原因；没有失败时为 null。 */
    @Volatile private var exclusiveFailure: String? = null

    private var monitorThread: Thread? = null
    private var fileObserver: FileObserver? = null

    /** 启动监控 + 独立读取线程；立即返回，不阻塞 Binder。 */
    fun start() {
        synchronized(sessionLock) {
            if (running) return
            running = true
        }
        startHotplugObserver()
        refreshRequested = true
        monitorThread = Thread({ monitorLoop() }, "gamepad-monitor").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        val thread: Thread?
        synchronized(sessionLock) {
            if (!running) return
            running = false
            thread = monitorThread
            monitorThread = null
            sessions.values.toList().forEach { it.closeQuietly() }
            sessions.clear()
        }
        fileObserver?.let { runCatching { it.stopWatching() } }
        fileObserver = null
        thread?.interrupt()
    }

    /** 是否至少有一台设备被 EVIOCGRAB 独占。 */
    fun isExclusiveActive(): Boolean = synchronized(sessionLock) {
        sessions.values.any { it.grabbed }
    }

    /** 独占失败原因；无失败时为 null。 */
    fun exclusiveError(): String? = exclusiveFailure

    /** 当前是否处于游戏模式（设备已交还系统）。 */
    fun isGameMode(): Boolean = !mouseMode

    /**
     * 鼠标模式 / 游戏模式切换。
     *
     * 游戏模式只做 `ioctl(fd, EVIOCGRAB, 0)` 释放抓取，**不关闭 fd**：
     * 这样仍能读到 BTN_THUMBL，长按热键才能切回鼠标模式。
     */
    fun setMouseMode(enabled: Boolean) {
        if (mouseMode == enabled) return
        mouseMode = enabled
        val snapshot = synchronized(sessionLock) { sessions.values.toList() }
        for (session in snapshot) {
            if (!enabled) {
                session.ungrab()
            } else if (exclusiveEnabled) {
                val error = session.grab()
                if (error != null) recordExclusiveFailure(session.info.name, error) else clearExclusiveFailure()
            }
        }
        // 游戏模式是主动交还给系统，不算独占失败。
        if (!enabled) exclusiveFailure = null
        refreshRequested = true
    }

    /** 扫描 /dev/input 并返回手柄信息；不启动读取线程。 */
    fun describe(): String {
        val devices = scanDevices()
        val gamepads = devices.filter { it.isGamepad }
        val openPaths = synchronized(sessionLock) { sessions.keys.toList() }
        return formatSummary(devices.size, gamepads, openPaths, lastScanError.takeIf { devices.isEmpty() })
    }

    // ---------------- 扫描与热插拔 ----------------

    private fun monitorLoop() {
        while (running) {
            refreshDevices()
            var waited = 0L
            while (running && waited < SCAN_INTERVAL_MS) {
                if (refreshRequested) break
                try {
                    Thread.sleep(POLL_SLICE_MS)
                } catch (_: InterruptedException) {
                    return
                }
                waited += POLL_SLICE_MS
            }
            refreshRequested = false
        }
    }

    /** FileObserver 监听 /dev/input 的增删；部分机型不允许时由轮询兜底。 */
    private fun startHotplugObserver() {
        try {
            val observer = object : FileObserver(
                File(INPUT_DIR),
                FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM
            ) {
                override fun onEvent(event: Int, path: String?) {
                    refreshRequested = true
                }
            }
            observer.startWatching()
            fileObserver = observer
        } catch (_: Throwable) {
            // 忽略：监控线程每 2 秒仍会重新扫描。
        }
    }

    private fun refreshDevices() {
        val devices = scanDevices()
        if (!running) return
        val gamepads = devices.filter { it.isGamepad }
        val active = gamepads.associateBy { it.path }
        val toClose = ArrayList<DeviceSession>()
        val toStart = ArrayList<DeviceSession>()
        val summary: String
        synchronized(sessionLock) {
            if (!running) return
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val latest = active[entry.key]
                if (latest == null) {
                    toClose.add(entry.value)
                    iterator.remove()
                } else {
                    entry.value.info = latest
                }
            }
            for (device in gamepads) {
                if (!sessions.containsKey(device.path)) {
                    val session = DeviceSession(device)
                    sessions[device.path] = session
                    toStart.add(session)
                }
            }
            summary = formatSummary(devices.size, gamepads, sessions.keys.toList(), null)
        }
        toClose.forEach { it.closeQuietly() }
        toStart.forEach { openAndRead(it) }
        if (summary != lastSummary) {
            lastSummary = summary
            notifyDeviceInfo(summary)
        }
    }

    private fun openAndRead(session: DeviceSession) {
        val opened = try {
            session.open()
            true
        } catch (_: Throwable) {
            false
        }
        if (!opened || !running) {
            session.closeQuietly()
            removeSession(session)
            return
        }
        // M10：打开后立即独占；失败时记录原因并退回旁听（不阻断读取）。
        if (exclusiveEnabled && mouseMode) {
            val error = session.grab()
            if (error != null) recordExclusiveFailure(session.info.name, error) else clearExclusiveFailure()
        }
        val thread = Thread({ readLoop(session) }, "gamepad-${session.path.substringAfterLast('/')}")
        session.thread = thread
        thread.isDaemon = true
        thread.start()
    }

    // ---------------- 读取 24 字节 input_event ----------------

    private fun readLoop(session: DeviceSession) {
        val stream = session.stream
        if (stream == null) {
            removeSession(session)
            return
        }
        val buffer = ByteArray(EVENT_SIZE)
        val bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (running && !session.closed) {
                var read = 0
                while (read < EVENT_SIZE) {
                    val n = stream.read(buffer, read, EVENT_SIZE - read)
                    if (n < 0) throw IOException("input device closed")
                    read += n
                }
                // arm64 小端：timeval(16) + type u16@16 + code u16@18 + value i32@20
                val type = bytes.getShort(EVENT_TYPE_OFFSET).toInt() and 0xFFFF
                val code = bytes.getShort(EVENT_CODE_OFFSET).toInt() and 0xFFFF
                val value = bytes.getInt(EVENT_VALUE_OFFSET)
                dispatch(session, type, code, value)
            }
        } catch (_: Throwable) {
            // 设备拔出（ENODEV）/关闭：退出线程，由监控线程重新扫描并重连。
        } finally {
            session.closeQuietly()
            removeSession(session)
        }
    }

    private fun dispatch(session: DeviceSession, type: Int, code: Int, value: Int) {
        // 只处理 EV_KEY / EV_ABS，EV_SYN 等一律忽略。
        if (type != EV_KEY && type != EV_ABS) return
        val target = listener ?: return
        if (type == EV_ABS) {
            // 摇杆静止时内核会持续上报同值，做一次去重避免刷屏。
            if (session.lastAbsValues[code] == value) return
            session.lastAbsValues[code] = value
        }
        runCatching { target.onEvent(type, code, value) }
        if (type == EV_ABS) {
            val range = session.info.axes[code] ?: return
            val normalized = normalize(value, range.min, range.max)
            runCatching { target.onAxis(code, normalized) }
        }
    }

    private fun recordExclusiveFailure(deviceName: String, reason: String) {
        exclusiveFailure = (if (deviceName.isBlank()) "" else "$deviceName: ") + reason
        Log.w("GamepadReader", "EVIOCGRAB 失败，回退旁听模式：$exclusiveFailure")
    }

    private fun clearExclusiveFailure() {
        // 只有所有已打开设备都抓取成功才清除失败原因，避免多设备时互相覆盖。
        val allGrabbed = synchronized(sessionLock) {
            sessions.values.isNotEmpty() && sessions.values.all { it.grabbed }
        }
        if (allGrabbed) exclusiveFailure = null
    }

    private fun removeSession(session: DeviceSession) {
        synchronized(sessionLock) {
            if (sessions[session.path] === session) sessions.remove(session.path)
        }
    }

    // ---------------- getevent -pl 解析 ----------------

    private fun scanDevices(): List<DeviceDescriptor> = synchronized(scanLock) {
        val output = runGetevent()
        if (output.isBlank()) {
            if (lastScanError == null) {
                lastScanError = "getevent -pl 无输出：无法读取 /dev/input（请确认 Shizuku shell 权限）"
            }
            emptyList()
        } else {
            lastScanError = null
            parseGetevent(output)
        }
    }

    private fun runGetevent(): String {
        val attempts = listOf(
            arrayOf("/system/bin/getevent", "-pl"),
            arrayOf("sh", "-c", "getevent -pl 2>&1"),
        )
        var error: String? = null
        for (cmd in attempts) {
            try {
                val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroy()
                if (output.contains("add device")) return output
                if (output.isNotBlank()) {
                    error = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                }
            } catch (t: Throwable) {
                error = "执行 getevent 失败: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        lastScanError = error
        return ""
    }

    private fun parseGetevent(output: String): List<DeviceDescriptor> {
        val devices = ArrayList<MutableDevice>()
        var current: MutableDevice? = null
        var section = 0
        for (line in output.lineSequence()) {
            val add = ADD_DEVICE_RE.find(line)
            if (add != null) {
                current = MutableDevice(add.groupValues[1])
                devices.add(current)
                section = 0
                continue
            }
            val device = current ?: continue
            val nameMatch = NAME_RE.find(line)
            if (nameMatch != null) {
                device.name = nameMatch.groupValues[1]
                continue
            }
            val sectionMatch = SECTION_RE.find(line)
            if (sectionMatch != null) {
                section = when (sectionMatch.groupValues[1]) {
                    "KEY" -> EV_KEY
                    "ABS" -> EV_ABS
                    else -> 0
                }
                val rest = sectionMatch.groupValues[3]
                if (section == EV_KEY) collectKeyNames(rest, device.keyNames)
                if (section == EV_ABS) collectAxes(rest, device.axes)
                continue
            }
            if (section == EV_KEY) collectKeyNames(line, device.keyNames)
            if (section == EV_ABS) collectAxes(line, device.axes)
        }
        return devices.map { it.toDescriptor() }
    }

    private fun collectAxes(text: String, axes: MutableMap<Int, AxisRange>) {
        AXIS_DEF_RE.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val code = GamepadAxes.CODES[name] ?: return@forEach
            val min = match.groupValues[3].toIntOrNull() ?: return@forEach
            val max = match.groupValues[4].toIntOrNull() ?: return@forEach
            axes[code] = AxisRange(code, name, min, max)
        }
    }

    private fun collectKeyNames(text: String, names: MutableSet<String>) {
        KEY_NAME_RE.findAll(text).forEach { names.add(it.value) }
    }

    private fun normalize(value: Int, min: Int, max: Int): Float {
        if (max <= min) return 0f
        val clamped = value.coerceIn(min, max)
        return (((clamped - min).toFloat() / (max - min).toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
    }

    private fun notifyDeviceInfo(summary: String) {
        val target = listener ?: return
        runCatching { target.onDeviceInfo(summary) }
    }

    private fun formatSummary(
        scanned: Int,
        gamepads: List<DeviceDescriptor>,
        openPaths: List<String>,
        error: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("已扫描 ").append(scanned).append(" 个 /dev/input 设备；识别到手柄 ")
            .append(gamepads.size).append(" 个")
        if (gamepads.isEmpty()) {
            sb.append("\n未发现同时具备 ABS_X/ABS_Y 与 BTN_SOUTH/BTN_A/BTN_GAMEPAD 的设备")
        } else {
            for (gamepad in gamepads) {
                sb.append("\n\n• ").append(gamepad.name).append("  (").append(gamepad.path).append(')')
                if (gamepad.axes.isNotEmpty()) {
                    sb.append("\n  轴: ").append(
                        gamepad.axes.values.joinToString(", ") { "${it.name}[${it.min}..${it.max}]" }
                    )
                }
                if (gamepad.keyNames.isNotEmpty()) {
                    sb.append("\n  键: ").append(gamepad.keyNames.sorted().joinToString(", "))
                }
            }
        }
        if (openPaths.isNotEmpty()) {
            sb.append("\n\n读取中: ").append(openPaths.joinToString(", "))
        }
        // M10：模式与独占状态（界面手柄区块直接展示）。
        val grabbedCount = synchronized(sessionLock) { sessions.values.count { it.grabbed } }
        sb.append("\n\n模式: ").append(if (mouseMode) "鼠标模式" else "游戏模式（已交还系统）")
        sb.append(" · 独占: ").append(
            when {
                !exclusiveEnabled -> "已关闭（旁听）"
                !mouseMode -> "已释放（游戏模式）"
                grabbedCount > 0 -> "独占中（$grabbedCount 台）"
                else -> "旁听中"
            }
        )
        exclusiveFailure?.let { sb.append("\n独占失败: ").append(it) }
        if (error != null) sb.append("\n").append(error)
        return sb.toString()
    }

    // ---------------- 会话与描述结构 ----------------

    private class DeviceSession(initial: DeviceDescriptor) {
        @Volatile var info: DeviceDescriptor = initial
        val path: String = initial.path
        var stream: FileInputStream? = null
        var thread: Thread? = null

        @Volatile var closed: Boolean = false
        val lastAbsValues = HashMap<Int, Int>()

        private var pfd: ParcelFileDescriptor? = null

        /** Os.open 返回的 FileDescriptor，供 ioctl 使用；关闭时置空避免误用。 */
        private var osFd: FileDescriptor? = null

        /** 当前是否已被 EVIOCGRAB 独占。 */
        @Volatile var grabbed: Boolean = false
            private set

        fun open() {
            // Os.open 返回 FileDescriptor；adoptFd 需要裸 fd，这里取出其内部 descriptor。
            val fileDescriptor = Os.open(info.path, OsConstants.O_RDONLY, 0)
            val rawFd = try {
                descriptorInt(fileDescriptor)
            } catch (t: Throwable) {
                runCatching { Os.close(fileDescriptor) }
                throw t
            }
            osFd = fileDescriptor
            val descriptor = ParcelFileDescriptor.adoptFd(rawFd)
            pfd = descriptor
            stream = FileInputStream(descriptor.fileDescriptor)
        }

        /** 独占设备；返回 null 表示成功，否则为失败原因。 */
        fun grab(): String? {
            if (grabbed) return null
            val fd = osFd ?: return "设备未打开"
            return try {
                evIoctlInt(fd, EVIOCGRAB, 1)
                grabbed = true
                null
            } catch (t: Throwable) {
                grabbed = false
                detailOf(t, "EVIOCGRAB 失败")
            }
        }

        /** 释放独占；与 [grab] 成对，关闭 fd 前必须先调用。 */
        fun ungrab() {
            if (!grabbed) return
            grabbed = false
            val fd = osFd ?: return
            runCatching { evIoctlInt(fd, EVIOCGRAB, 0) }
        }

        fun closeQuietly() {
            if (closed) return
            closed = true
            // 先释放抓取再关 fd；异常路径下内核也会随 close 自动释放。
            ungrab()
            osFd = null
            val input = stream
            stream = null
            val descriptor = pfd
            pfd = null
            var detached = false
            if (descriptor != null) {
                try {
                    val rawFd = descriptor.detachFd()
                    detached = true
                    // 没有 FileInputStream 接管时（open 中途失败），重新包装后关闭裸 fd。
                    if (input == null) runCatching { ParcelFileDescriptor.adoptFd(rawFd).close() }
                } catch (_: Throwable) {
                    // detach 失败通常意味着 fd 已关闭。
                }
            }
            if (input != null) {
                if (detached || descriptor == null) runCatching { input.close() }
            }
        }
    }

    private class MutableDevice(val path: String) {
        var name: String = ""
        val keyNames = LinkedHashSet<String>()
        val axes = LinkedHashMap<Int, AxisRange>()

        fun toDescriptor(): DeviceDescriptor {
            val codes = keyNames.mapNotNull { KEY_CODES[it] }.toSet()
            return DeviceDescriptor(
                path = path,
                name = name.ifBlank { path.substringAfterLast('/') },
                axes = axes.toMap(),
                keyNames = keyNames.toSet(),
                keyCodes = codes,
            )
        }
    }

    companion object {
        private const val INPUT_DIR = "/dev/input"
        private const val EVENT_SIZE = 24
        private const val EVENT_TYPE_OFFSET = 16
        private const val EVENT_CODE_OFFSET = 18
        private const val EVENT_VALUE_OFFSET = 20
        private const val EV_KEY = 1
        private const val EV_ABS = 3
        private const val SCAN_INTERVAL_MS = 2_000L

        /** _IOW('E', 0x90, int)：Linux 输入子系统 EVIOCGRAB。 */
        const val EVIOCGRAB = 0x40044590
        private const val POLL_SLICE_MS = 200L
        private const val SCAN_TIMEOUT_MS = 4_000L

        private val ADD_DEVICE_RE = Regex("^add device \\d+:\\s*(\\S+)")
        private val NAME_RE = Regex("^\\s*name:\\s*\"(.*)\"")
        private val SECTION_RE = Regex("^\\s*([A-Z]+) \\(([0-9a-fA-F]{4})\\):(.*)$")
        private val AXIS_DEF_RE = Regex(
            "(ABS_[A-Z0-9_]+)\\s*:\\s*value\\s*(-?\\d+)\\s*,\\s*min\\s*(-?\\d+)\\s*,\\s*max\\s*(-?\\d+)"
        )
        private val KEY_NAME_RE = Regex("\\b(?:BTN|KEY)_[A-Z0-9_]+\\b")

        private val KEY_CODES: Map<String, Int> = mapOf(
            "BTN_SOUTH" to 0x130,
            "BTN_A" to 0x130,
            "BTN_GAMEPAD" to 0x130,
            "BTN_EAST" to 0x131,
            "BTN_B" to 0x131,
            "BTN_C" to 0x132,
            "BTN_NORTH" to 0x133,
            "BTN_X" to 0x133,
            "BTN_WEST" to 0x134,
            "BTN_Y" to 0x134,
            "BTN_Z" to 0x135,
            "BTN_TL" to 0x136,
            "BTN_TR" to 0x137,
            "BTN_TL2" to 0x138,
            "BTN_TR2" to 0x139,
            "BTN_SELECT" to 0x13a,
            "BTN_START" to 0x13b,
            "BTN_MODE" to 0x13c,
            "BTN_THUMBL" to 0x13d,
            "BTN_THUMBR" to 0x13e,
            "BTN_DPAD_UP" to 0x220,
            "BTN_DPAD_DOWN" to 0x221,
            "BTN_DPAD_LEFT" to 0x222,
            "BTN_DPAD_RIGHT" to 0x223,
            "BTN_TRIGGER_HAPPY1" to 0x2c0,
        )

        /**
         * 调用 @hide 的 android.system.Os#ioctlInt(FileDescriptor, int, int)。
         * 反射不可用时抛 [UnsupportedOperationException]，由 [DeviceSession.grab] 转成可见的失败原因。
         */
        private fun evIoctlInt(fd: FileDescriptor, request: Int, value: Int) {
            val method = OsIoctl.method
                ?: throw UnsupportedOperationException("android.system.Os#ioctlInt 不可用（反射失败）")
            try {
                method.invoke(null, fd, request, value)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }

        /** 反射结果缓存；Android 上 ioctlInt 为 @hide，只能反射调用。 */
        private object OsIoctl {
            val method: java.lang.reflect.Method? = runCatching {
                Class.forName("android.system.Os")
                    .getMethod(
                        "ioctlInt",
                        FileDescriptor::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .apply { isAccessible = true }
            }.getOrNull()
        }

        /** 异常 → 可展示的失败原因（带 errno 文案）。 */
        private fun detailOf(t: Throwable, fallback: String): String {
            val message = t.message?.takeIf { it.isNotBlank() }
            return if (message != null) "${t.javaClass.simpleName}: $message" else "${t.javaClass.simpleName}: $fallback"
        }

        /** 取出 FileDescriptor 的内部 fd；失败时由调用方关闭原描述符。 */
        private fun descriptorInt(fd: FileDescriptor): Int {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            return field.getInt(fd)
        }

        /** 无监听实例时的即时扫描，供 describeGamepads() 使用。 */
        fun describeOnce(): String = GamepadReader(null).describe()
    }
}

/** 手柄轴 code -> 名称，供 shell 侧归一化与 UI 展示共用。 */
object GamepadAxes {
    const val ABS_X = 0
    const val ABS_Y = 1
    const val ABS_Z = 2
    const val ABS_RX = 3
    const val ABS_RY = 4
    const val ABS_RZ = 5
    const val ABS_HAT0X = 16
    const val ABS_HAT0Y = 17
    const val BTN_SOUTH = 0x130

    val NAMES: Map<Int, String> = linkedMapOf(
        ABS_X to "ABS_X",
        ABS_Y to "ABS_Y",
        ABS_Z to "ABS_Z",
        ABS_RX to "ABS_RX",
        ABS_RY to "ABS_RY",
        ABS_RZ to "ABS_RZ",
        6 to "ABS_THROTTLE",
        7 to "ABS_RUDDER",
        8 to "ABS_WHEEL",
        9 to "ABS_GAS",
        10 to "ABS_BRAKE",
        ABS_HAT0X to "ABS_HAT0X",
        ABS_HAT0Y to "ABS_HAT0Y",
    )

    val CODES: Map<String, Int> = NAMES.entries.associate { (code, name) -> name to code }
}

/** /dev/input 设备描述（getevent -pl 解析结果）。 */
data class DeviceDescriptor(
    val path: String,
    val name: String,
    val axes: Map<Int, AxisRange>,
    val keyNames: Set<String>,
    val keyCodes: Set<Int>,
) {
    val isGamepad: Boolean
        get() = axes.containsKey(GamepadAxes.ABS_X) &&
            axes.containsKey(GamepadAxes.ABS_Y) &&
            (keyCodes.contains(GamepadAxes.BTN_SOUTH) ||
                keyNames.any { it == "BTN_SOUTH" || it == "BTN_A" || it == "BTN_GAMEPAD" })
}

/** 轴的原始范围。 */
data class AxisRange(val code: Int, val name: String, val min: Int, val max: Int)
