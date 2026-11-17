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
        var releaseFailed = false
        for (session in snapshot) {
            if (!enabled) {
                if (!session.ungrab()) {
                    releaseFailed = true
                    recordExclusiveFailure(session.info.name, "EVIOCGRAB(0) 失败，设备仍被占用")
                }
            } else if (exclusiveEnabled) {
                val error = session.grab()
                if (error != null) recordExclusiveFailure(session.info.name, error) else clearExclusiveFailure()
            }
        }
        // 主动交还系统且确认释放成功时才清除失败原因，避免「显示已释放、实际仍占用」。
        if (!enabled && !releaseFailed) exclusiveFailure = null
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
        Log.w(TAG, "EVIOCGRAB 失败，回退旁听模式：$exclusiveFailure")
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

        /** 独占设备；返回 null 表示成功，否则为失败原因（含 ioctl 非 0 返回）。 */
        fun grab(): String? {
            if (grabbed) return null
            val fd = osFd ?: return "设备未打开"
            return try {
                val result = evIoctlInt(fd, EVIOCGRAB, 1)
                if (result == 0) {
                    grabbed = true
                    null
                } else {
                    grabbed = false
                    "EVIOCGRAB 返回 $result"
                }
            } catch (t: Throwable) {
                grabbed = false
                detailOf(t, "EVIOCGRAB 失败")
            }
        }

        /**
         * 释放独占；返回 true 表示 ioctl 确认成功。
         *
         * 失败时不改 grabbed（内核仍持有抓取），由 [closeQuietly] 关闭 fd 兜底，
         * 并把原因交给调用方展示，避免「界面显示已释放、实际仍被占用」。
         */
        fun ungrab(): Boolean {
            if (!grabbed) return true
            val fd = osFd ?: return false
            return try {
                val result = evIoctlInt(fd, EVIOCGRAB, 0)
                if (result == 0) {
                    grabbed = false
                    true
                } else {
                    Log.w(TAG, "EVIOCGRAB(0) 返回 $result，保留抓取状态直至 fd 关闭")
                    false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "EVIOCGRAB(0) 失败：${detailOf(t, "EVIOCGRAB 释放失败")}")
                false
            }
        }

        fun closeQuietly() {
            if (closed) return
            closed = true
            // 先释放抓取再关 fd；释放失败时内核仍会随 close 强制释放。
            if (!ungrab() && grabbed) {
                Log.w(TAG, "关闭 fd 前未能 ungrab，依赖内核随 close 释放")
            }
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
            // fd 已关闭：内核保证释放 EVIOCGRAB。
            grabbed = false
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

        private const val TAG = "GamepadReader"

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
         * 调用 ioctl(fd, request, value)，返回目标方法的结果（0 = 成功）。
         *
         * android.system.Os#ioctlInt 是 @hide，且不同 Android 版本/机型签名不同：
         * - 3 参 int（多数版本，含 R~15）
         * - 3 参 android.util.MutableInt（部分旧版本）
         * - Android 16（API 36）真机只暴露 2 参版本，arg 固定为 0
         * 这里在运行时枚举可用签名并逐个尝试，全部不可用时抛出「已发现签名」清单，
         * 由 [DeviceSession.grab] 转成界面上可见的失败原因。
         */
        private fun evIoctlInt(fd: FileDescriptor, request: Int, value: Int): Int {
            var lastError: Throwable? = null
            for (candidate in IoctlBridge.candidates) {
                if (candidate.argMode == ArgMode.NONE && value != 0) continue
                try {
                    return candidate.invoke(fd, request, value)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    // 已进入目标方法：errno 等设备级错误直接上抛，不再换签名重试。
                    throw e.targetException ?: e
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            val found = IoctlBridge.signatures
            val detail = if (found.isEmpty()) "未发现 ioctlInt" else found.joinToString("; ")
            throw UnsupportedOperationException(
                "无可用 ioctlInt(FileDescriptor,int,arg) 签名（已发现: $detail）" +
                    (lastError?.let { "；最后错误: ${it.javaClass.simpleName}: ${it.message}" } ?: "")
            )
        }

        /** ioctl 参数传递方式（不同 Android 版本签名不同）。 */
        private enum class ArgMode { INT, LONG, MUTABLE_INT, NONE }

        private class IoctlCandidate(
            private val target: Any?,
            private val method: java.lang.reflect.Method,
            val argMode: ArgMode,
        ) {
            fun invoke(fd: FileDescriptor, request: Int, value: Int): Int {
                method.isAccessible = true
                val result = when (argMode) {
                    ArgMode.INT -> method.invoke(target, fd, request, value)
                    ArgMode.LONG -> method.invoke(target, fd, request, value.toLong())
                    ArgMode.MUTABLE_INT -> {
                        val arg = IoctlBridge.mutableInt(value)
                            ?: throw UnsupportedOperationException("android.util.MutableInt 不可用")
                        method.invoke(target, fd, request, arg)
                    }
                    ArgMode.NONE -> method.invoke(target, fd, request)
                }
                return (result as? Int) ?: 0
            }
        }

        /**
         * 运行时枚举 ioctlInt 候选：
         * android.system.Os（静态）→ libcore.io.Libcore.os（实例）→ libcore.io.Linux（实例）。
         * 优先 3 参 int > 3 参 MutableInt > 2 参（仅 arg=0 时可用）。
         */
        private object IoctlBridge {

            private val owners: List<Pair<Class<*>, Any?>> by lazy {
                val result = ArrayList<Pair<Class<*>, Any?>>()
                runCatching { Class.forName("android.system.Os") }.getOrNull()?.let {
                    result += it to null
                }
                runCatching {
                    val libcore = Class.forName("libcore.io.Libcore")
                    val field = libcore.getDeclaredField("os").apply { isAccessible = true }
                    field.get(null)
                }.getOrNull()?.let { result += it.javaClass to it }
                runCatching {
                    Class.forName("libcore.io.Linux").getDeclaredConstructor().newInstance()
                }.getOrNull()?.let { result += it.javaClass to it }
                result
            }

            val candidates: List<IoctlCandidate> by lazy {
                val list = ArrayList<IoctlCandidate>()
                for ((owner, target) in owners) {
                    val methods = runCatching {
                        (owner.declaredMethods.asList() + owner.methods.asList())
                            .filter { it.name == "ioctlInt" }
                    }.getOrDefault(emptyList())
                        .distinctBy { it.parameterTypes.joinToString(",") + "|" + it.parameterCount }
                    for (method in methods) {
                        val mode = argModeOf(method.parameterTypes) ?: continue
                        list += IoctlCandidate(target, method, mode)
                    }
                }
                list.sortedBy { it.argMode.ordinal }
            }

            /** 诊断用：当前进程实际可见的 ioctlInt 签名。 */
            val signatures: List<String> by lazy {
                runCatching {
                    owners.flatMap { (owner, _) ->
                        owner.declaredMethods.filter { it.name == "ioctlInt" }.map { method ->
                            owner.simpleName + "#ioctlInt(" +
                                method.parameterTypes.joinToString(",") { it.simpleName } + ")"
                        }
                    }.distinct()
                }.getOrDefault(emptyList())
            }

            private val mutableIntCtor: java.lang.reflect.Constructor<*>? by lazy {
                runCatching {
                    Class.forName("android.util.MutableInt")
                        .getDeclaredConstructor(Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
            }

            fun mutableInt(value: Int): Any? =
                runCatching { mutableIntCtor?.newInstance(value) }.getOrNull()

            private fun argModeOf(types: Array<Class<*>>): ArgMode? = when {
                types.size == 3 && types[0] == FileDescriptor::class.java &&
                    types[1] == Int::class.javaPrimitiveType &&
                    types[2] == Int::class.javaPrimitiveType -> ArgMode.INT

                types.size == 3 && types[0] == FileDescriptor::class.java &&
                    types[1] == Int::class.javaPrimitiveType &&
                    types[2] == java.lang.Long.TYPE -> ArgMode.LONG

                types.size == 3 && types[0] == FileDescriptor::class.java &&
                    types[1] == Int::class.javaPrimitiveType &&
                    types[2].name == "android.util.MutableInt" -> ArgMode.MUTABLE_INT

                types.size == 2 && types[0] == FileDescriptor::class.java &&
                    types[1] == Int::class.javaPrimitiveType -> ArgMode.NONE

                else -> null
            }
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
