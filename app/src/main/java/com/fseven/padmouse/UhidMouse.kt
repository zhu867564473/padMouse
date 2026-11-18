package com.fseven.padmouse

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M12：UHID 虚拟鼠标（运行在 Shizuku UserService / shell 进程）。
 *
 * 通过 /dev/uhid 创建一台系统级 HID 相对鼠标，由 Android 自己绘制光标、处理滚轮与悬停，
 * 解决「触摸注入模式下大量 App 不认鼠标滚轮」的兼容性问题。
 *
 * 常量与结构体布局已对照内核头文件 `include/uapi/linux/uhid.h`
 * （本机 NDK 28.2 的 sysroot/usr/include/linux/uhid.h）核对：
 *
 * ```
 * enum uhid_event_type {
 *   __UHID_LEGACY_CREATE, UHID_DESTROY = 1, UHID_START = 2, UHID_STOP = 3,
 *   UHID_OPEN = 4, UHID_CLOSE = 5, UHID_OUTPUT = 6, __UHID_LEGACY_OUTPUT_EV = 7,
 *   __UHID_LEGACY_INPUT = 8, UHID_GET_REPORT = 9, UHID_GET_REPORT_REPLY = 10,
 *   UHID_CREATE2 = 11, UHID_INPUT2 = 12, UHID_SET_REPORT = 13,
 *   UHID_SET_REPORT_REPLY = 14,
 * };
 * struct uhid_create2_req {          // __packed__
 *   __u8 name[128]; __u8 phys[64]; __u8 uniq[64];
 *   __u16 rd_size; __u16 bus; __u32 vendor; __u32 product;
 *   __u32 version; __u32 country; __u8 rd_data[HID_MAX_DESCRIPTOR_SIZE];
 * };
 * struct uhid_input2_req { __u16 size; __u8 data[UHID_DATA_MAX]; }; // __packed__
 * ```
 *
 * 主机侧只写必要长度（内核 uhid_char_write 先用 min(count, sizeof(uhid_event)) 拷贝并清零剩余部分，
 * 见 drivers/hid/uhid.c），因此 create2 写 280 + descriptor 字节、input2 写 10 字节即可。
 *
 * 本类只写 /dev/uhid：鼠标没有 feature/output report，不需要读内核事件队列
 * （UHID_START/OPEN 等事件由内核排队，32 条容量对单只鼠标足够）。
 *
 * 线程安全：create/destroy/move/button/wheel 可从不同线程调用；所有 fd 写入串行化。
 */
class UhidMouse {

    private val lock = Any()
    private val writeLock = Any()

    private var devicePfd: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null

    /** 消费 uhid 内核事件队列的读线程。 */
    @Volatile
    private var eventThread: Thread? = null

    @Volatile
    private var created = false

    @Volatile
    private var closing = false

    @Volatile
    private var buttonMask = 0

    @Volatile
    private var lastError: String? = null

    /**
     * 创建虚拟鼠标；重复调用返回当前状态。
     *
     * @return 状态串：成功 `active=true ...`，失败 `ERROR: 原因`（调用方据此决定是否回退）。
     */
    fun create(uniq: String = "", secondary: Boolean = false): String {
        synchronized(lock) {
            if (created && output != null) return statusLocked()

            lastError = null
            closing = false
            return try {
                val device = File(UHID_PATH)
                if (!device.exists()) {
                    lastError = "/dev/uhid 不存在"
                    return "ERROR: ${lastError}"
                }

                // uhid 是双向字符设备：内核通过同一 fd 的读队列回送 UHID_START/OPEN 等事件。
                // 必须用 O_RDWR 打开（MODE_WRITE_ONLY 会导致创建路径失败，且无法消费事件队列）。
                val pfd = ParcelFileDescriptor.open(device, ParcelFileDescriptor.MODE_READ_WRITE)
                devicePfd = pfd
                val stream = FileOutputStream(pfd.fileDescriptor)
                output = stream

                // 写 UHID_CREATE2：内核返回错误（EINVAL 等）时 FileOutputStream 抛 IOException。
                val packet = buildCreate2Packet(uniq, secondary)
                Log.i(TAG, "create: writing UHID_CREATE2 ${packet.size}B (header=$CREATE2_HEADER_SIZE desc=${REPORT_DESCRIPTOR.size})")
                stream.write(packet)
                stream.flush()
                created = true
                startEventReader(pfd)
                Log.i(TAG, "create: OK ${statusLocked()}")
                statusLocked()
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "create: FAILED $lastError", t)
                created = false
                closeQuietly()
                "ERROR: ${lastError}"
            }
        }
    }

    /** 写 UHID_DESTROY 并关闭 fd；重复调用安全。 */
    fun destroy() {
        synchronized(lock) {
            if (closing) return
            closing = true
            if (created) {
                runCatching { writeRaw(buildDestroyPacket()) }
            }
            created = false
            buttonMask = 0
            closeQuietly()
            closing = false
        }
    }

    /** 相对位移；超过 ±127 的位移自动拆成多份 report 上报。 */
    fun move(dx: Int, dy: Int) {
        synchronized(writeLock) {
            if (!created || output == null) return
            var remainX = dx
            var remainY = dy
            var guard = 0
            while ((remainX != 0 || remainY != 0) && guard++ < MAX_SPLITS) {
                val stepX = remainX.coerceIn(-HID_MAX_STEP, HID_MAX_STEP)
                val stepY = remainY.coerceIn(-HID_MAX_STEP, HID_MAX_STEP)
                writeRawLocked(buildInputPacket(buttonMask, stepX, stepY, 0))
                remainX -= stepX
                remainY -= stepY
            }
        }
    }

    /** 按键位掩码：bit0 左键 / bit1 右键 / bit2 中键。 */
    fun setButtons(mask: Int) {
        synchronized(writeLock) {
            val next = mask and BUTTON_MASK
            if (next == buttonMask) return
            Log.d(TAG, "setButtons mask=$next (prev=$buttonMask)")
            buttonMask = next
            if (!created || output == null) return
            writeRawLocked(buildInputPacket(buttonMask, 0, 0, 0))
        }
    }

    /** 滚轮：正数向上、负数向下（HID Usage Wheel 语义）；超过 ±127 自动拆分。 */
    fun wheel(delta: Int) {
        synchronized(writeLock) {
            if (!created || output == null || delta == 0) return
            var remain = delta
            var guard = 0
            while (remain != 0 && guard++ < MAX_SPLITS) {
                val step = remain.coerceIn(-HID_MAX_STEP, HID_MAX_STEP)
                writeRawLocked(buildInputPacket(buttonMask, 0, 0, step))
                remain -= step
            }
        }
    }

    /** 当前设备状态；供 AIDL uhidStatus 与设置页展示。 */
    fun status(): String = synchronized(lock) { statusLocked() }

    // ---------------------------------------------------------------- 内部实现

    private fun statusLocked(): String {
        if (!created) {
            val error = lastError
            return if (error != null) "ERROR: $error" else "active=false"
        }
        return "active=true name=$DEVICE_NAME path=$UHID_PATH report=${REPORT_SIZE}B " +
            "buttons=$buttonMask descriptor=${REPORT_DESCRIPTOR.size}B"
    }

    /**
     * 读线程：消费内核回送的 UHID_START / UHID_OPEN / UHID_OUTPUT 等事件。
     * uhid 是双向设备，读队列（32 条）长期不消费会丢事件，且无法感知设备是否真正 ready。
     */
    private fun startEventReader(pfd: ParcelFileDescriptor) {
        stopEventReader()
        val fd = pfd.fileDescriptor
        eventThread = Thread({
            val input = try {
                FileInputStream(fd)
            } catch (t: Throwable) {
                Log.w(TAG, "event reader open failed: ${t.message}")
                return@Thread
            }
            val buf = ByteArray(4384)
            while (!closing) {
                try {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n >= 4) {
                        val type = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        Log.d(TAG, "uhid event type=$type len=$n")
                    }
                } catch (t: Throwable) {
                    if (!closing) Log.d(TAG, "event reader end: ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
            }
        }, "uhid-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopEventReader() {
        eventThread?.interrupt()
        eventThread = null
    }

    private fun closeQuietly() {
        stopEventReader()
        runCatching { output?.close() }
        runCatching { devicePfd?.close() }
        output = null
        devicePfd = null
    }

    private fun writeRaw(packet: ByteArray) {
        synchronized(writeLock) { writeRawLocked(packet) }
    }

    private fun writeRawLocked(packet: ByteArray) {
        val stream = output ?: return
        try {
            stream.write(packet)
            stream.flush()
        } catch (t: Throwable) {
            if (!closing) {
                lastError = "写入 /dev/uhid 失败: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, lastError!!, t)
            }
        }
    }

    /** type + name[128] + phys[64] + uniq[64] + rd_size + bus + vendor/product/version/country + rd。 */
    private fun buildCreate2Packet(uniq: String, secondary: Boolean): ByteArray {
        val descriptor = REPORT_DESCRIPTOR
        val buffer = ByteBuffer.allocate(CREATE2_HEADER_SIZE + descriptor.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(UHID_CREATE2)
        buffer.put(fixedString(DEVICE_NAME, 128))
        buffer.put(fixedString(PHYS, 64))
        // uniq 决定设备关联到哪块显示：传副屏的 uniqueId → 光标画到副屏；
        // 空串 → 不关联任何显示 → 光标固定在主屏。
        buffer.put(fixedString(uniq, 64))
        buffer.putShort(descriptor.size.toShort())
        // 主/副屏两个设备必须显式区分（不能用 uniq 是否为空判断，
        // 主屏设备也会带自己的 uniqueId）：
        // - 主屏：BUS_VIRTUAL → IsExternal=false，系统把光标画在主屏；
        // - 副屏：BUS_USB + 副屏 uniq → IsExternal=true，系统关联到副屏；
        // product 也区分开，避免两个设备 descriptor 相同。
        val bus = if (secondary) BUS_USB else BUS_VIRTUAL
        buffer.putShort(bus.toShort())
        buffer.putInt(VENDOR_ID)
        buffer.putInt(if (secondary) PRODUCT_ID_SECONDARY else PRODUCT_ID)
        buffer.putInt(VERSION)
        buffer.putInt(COUNTRY)
        buffer.put(descriptor)
        return buffer.array()
    }

    /** type + size(u16) + [buttons, dx, dy, wheel]。 */
    private fun buildInputPacket(buttons: Int, dx: Int, dy: Int, wheel: Int, hwheel: Int = 0): ByteArray {
        val data = byteArrayOf(
            (buttons and BUTTON_MASK).toByte(),
            dx.coerceIn(-HID_MAX_STEP, HID_MAX_STEP).toByte(),
            dy.coerceIn(-HID_MAX_STEP, HID_MAX_STEP).toByte(),
            wheel.coerceIn(-HID_MAX_STEP, HID_MAX_STEP).toByte(),
            hwheel.coerceIn(-HID_MAX_STEP, HID_MAX_STEP).toByte(),
        )
        val buffer = ByteBuffer.allocate(INPUT2_HEADER_SIZE + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(UHID_INPUT2)
        buffer.putShort(data.size.toShort())
        buffer.put(data)
        return buffer.array()
    }

    private fun buildDestroyPacket(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array()

    private fun fixedString(value: String, size: Int): ByteArray {
        val field = ByteArray(size)
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, field, 0, minOf(bytes.size, size - 1))
        return field
    }

    companion object {
        private const val TAG = "UhidMouse"

        private const val UHID_PATH = "/dev/uhid"
        private const val DEVICE_NAME = "PadMouse Virtual Mouse"
        // phys 留空，与 scrcpy 完全一致。
        // 填 "padmouse/uhid" 会让 InputReader 记录成自定义 Location，
        // 实测会导致部分 App（微信/微信读书）不绘制系统光标。
        private const val PHYS = ""
        /** 诊断用：XREAL 眼镜副屏的 uniqueId（displayId=2）。留空则光标固定在主屏。 */
        private const val DEBUG_UNIQ_EXTERNAL = "local:4626964072776107781"

        // 常量取自 include/uapi/linux/uhid.h（见类注释）。
        private const val UHID_DESTROY = 1
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12

        private const val BUS_USB = 0x03
        private const val BUS_BT = 0x05
        private const val BUS_VIRTUAL = 0x06
        // 与 scrcpy 的 UHID 鼠标完全对齐：vendor/product 都用 0。
        // 实测 0x1209(pid.codes 测试用 ID) 会被部分 App 当作未知设备，
        // 不做指针光标渲染；换成 0/0 后与 scrcpy 行为一致（微信/微信读书内可见系统光标）。
        private const val VENDOR_ID = 0x0000
        private const val PRODUCT_ID = 0x0000
        /** 副屏模式用不同的 vendor/product，让 descriptor 与主屏模式不同。 */
        private const val VENDOR_ID_SECONDARY = 0x0000
        private const val PRODUCT_ID_SECONDARY = 0x0002
        private const val VERSION = 0
        private const val COUNTRY = 0

        /** uhid_create2_req 在 rd_data 之前的固定长度：4+128+64+64+2+2+4*4 = 280。 */
        private const val CREATE2_HEADER_SIZE = 280
        private const val INPUT2_HEADER_SIZE = 6

        private const val BUTTON_MASK = 0x07
        private const val HID_MAX_STEP = 127
        private const val MAX_SPLITS = 64

        /** 4 字节 report：[buttons][dx][dy][wheel]。 */
        private const val REPORT_SIZE = 5

        /**
         * 标准「3 键 + 滚轮」相对鼠标 descriptor。
         *
         * buttons(3bit) + padding(5bit) + X/Y/Wheel(int8 relative) = 4 字节。
         */
        private val REPORT_DESCRIPTOR = intArrayOf(
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x02,        // Usage (Mouse)
            0xA1, 0x01,        // Collection (Application)
            0x09, 0x01,        //   Usage (Pointer)
            0xA1, 0x00,        //   Collection (Physical)
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (Button 1)
            0x29, 0x03,        //     Usage Maximum (Button 3)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x95, 0x03,        //     Report Count (3)
            0x75, 0x01,        //     Report Size (1)
            0x81, 0x02,        //     Input (Data, Variable, Absolute)
            0x95, 0x01,        //     Report Count (1)
            0x75, 0x05,        //     Report Size (5)
            0x81, 0x01,        //     Input (Constant) —— 5bit padding
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x09, 0x38,        //     Usage (Wheel) —— 垂直滚轮 → REL_WHEEL
            0x15, 0x81,        //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x03,        //     Report Count (3)
            0x81, 0x06,        //     Input (Data, Variable, Relative)
            // 横向滚轮（AC Pan）：由 hid-input 映射成 REL_HWHEEL。
            // 加上它之后设备能力与 scrcpy 的 UHID 鼠标完全一致（HaveHWheel=true）。
            0x05, 0x0C,        //     Usage Page (Consumer)
            0x0A, 0x38, 0x02,  //     Usage (AC Pan)
            0x15, 0x81,        //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x01,        //     Report Count (1)
            0x81, 0x06,        //     Input (Data, Variable, Relative)
            0xC0,              //   End Collection (Physical)
            0xC0,              // End Collection (Application)
        ).map { it.toByte() }.toByteArray()
    }
}
