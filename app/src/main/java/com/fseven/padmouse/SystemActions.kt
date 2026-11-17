package com.fseven.padmouse

/**
 * M6 系统动作：Back / Home / Recents / 音量 / 亮度 / 截图。
 *
 * 所有动作都翻译成 shell 命令，由 Shizuku UserService（shell uid）的
 * [IPadMouse.shellExec] 执行；本文件不依赖 Android API，便于核对命令。
 */
enum class SystemAction {
    BACK,
    HOME,
    RECENTS,
    VOLUME_UP,
    VOLUME_DOWN,
    BRIGHTNESS_UP,
    BRIGHTNESS_DOWN,
    SCREENSHOT,
}

/**
 * 一个系统动作的触发器。
 *
 * @param code 按键 code（[direction] = 0）或轴 code（[direction] = ±1）
 * @param direction 0=按键；-1=轴向负方向（十字键上）；+1=轴向正方向（十字键下）
 * @param chordCode 组合键的第二个按键；0 表示单键
 */
data class ActionBinding(
    val code: Int,
    val direction: Int = 0,
    val chordCode: Int = 0,
) {
    val isChord: Boolean
        get() = chordCode != 0

    /** 是否为该按键的单键绑定（组合键不在此列）。 */
    fun matchesKey(code: Int): Boolean =
        !isChord && direction == 0 && this.code == code

    /** 该键是否参与此绑定（单键本身或组合键任一成员）。 */
    fun involvesKey(code: Int): Boolean =
        this.code == code || (isChord && chordCode == code)

    /** 组合键成员是否都已按下。 */
    fun isChordSatisfied(heldKeys: Set<Int>): Boolean =
        isChord && heldKeys.contains(code) && heldKeys.contains(chordCode)

    /** 轴是否越过触发阈值；十字键为数字轴，通常只上报 -1/0/1。 */
    fun matchesAxis(code: Int, normalized: Float): Boolean {
        if (direction == 0 || this.code != code) return false
        return if (direction < 0) {
            normalized <= -AXIS_TRIGGER_THRESHOLD
        } else {
            normalized >= AXIS_TRIGGER_THRESHOLD
        }
    }

    /** SharedPreferences 存储格式：code:direction:chordCode。 */
    fun encode(): String = "$code:$direction:$chordCode"

    companion object {
        private const val AXIS_TRIGGER_THRESHOLD = 0.5f

        fun decode(raw: String?): ActionBinding? {
            val parts = raw?.split(':') ?: return null
            if (parts.size != 3) return null
            val code = parts[0].toIntOrNull() ?: return null
            val direction = parts[1].toIntOrNull() ?: return null
            val chordCode = parts[2].toIntOrNull() ?: 0
            return ActionBinding(code, direction, chordCode)
        }
    }
}

/**
 * 执行系统动作。
 *
 * @param shell shell 命令执行器，生产环境接 [IPadMouse.shellExec]；
 *   必须在后台线程调用（Binder + 子进程）。
 */
class SystemActions(private val shell: (String) -> String) {

    data class Result(
        val action: SystemAction,
        val commands: List<String>,
        val success: Boolean,
        val output: String,
        /** 失败原因（供界面/Toast 提示）；成功时为 null。 */
        val failureReason: String? = null,
    )

    fun run(action: SystemAction): Result {
        val commands = ArrayList<String>()
        val output = StringBuilder()
        var failed = false
        var failureReason: String? = null

        val exec: (String) -> String = { cmd ->
            commands.add(cmd)
            val out = try {
                shell(cmd)
            } catch (t: Throwable) {
                "ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
            if (out.isNotBlank()) {
                if (output.isNotEmpty()) output.append('\n')
                output.append(out)
            }
            if (isErrorOutput(out)) failed = true
            out
        }

        when (action) {
            SystemAction.BACK -> exec(CMD_KEY_BACK)
            SystemAction.HOME -> exec(CMD_KEY_HOME)
            SystemAction.RECENTS -> exec(CMD_KEY_RECENTS)
            SystemAction.VOLUME_UP -> exec(CMD_VOLUME_UP)
            SystemAction.VOLUME_DOWN -> exec(CMD_VOLUME_DOWN)
            SystemAction.BRIGHTNESS_UP -> adjustBrightness(BRIGHTNESS_STEP, exec)
            SystemAction.BRIGHTNESS_DOWN -> adjustBrightness(-BRIGHTNESS_STEP, exec)
            SystemAction.SCREENSHOT -> {
                val reason = takeScreenshot(exec)
                if (reason != null) {
                    failed = true
                    failureReason = reason
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(reason)
                } else {
                    // 文件已存在且非空即视为截图成功；媒体扫描失败的 ERROR 不再翻盘。
                    failed = false
                }
            }
        }

        return Result(action, commands.toList(), !failed, output.toString().trim(), failureReason)
    }

    /**
     * 先切手动亮度，再读当前值 ±[delta]，钳制 0..255 后写回。
     * 读值失败（空/null/权限）时回退 [DEFAULT_BRIGHTNESS]，不会让整条链路失败。
     */
    private fun adjustBrightness(delta: Int, exec: (String) -> String) {
        exec(CMD_BRIGHTNESS_MODE_MANUAL)
        val current = parseBrightness(exec(CMD_BRIGHTNESS_GET))
        val target = (current + delta).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
        exec(cmdSetBrightness(target))
    }

    /**
     * screencap 落盘后先确认文件存在且非空，再发媒体扫描广播让相册立即可见。
     *
     * @return null 表示成功；否则返回可直接展示给用户的失败原因。
     */
    private fun takeScreenshot(exec: (String) -> String): String? {
        val mkdir = exec(CMD_MKDIR_SCREENSHOTS)
        if (isErrorOutput(mkdir)) {
            val detail = mkdir.lineSequence().lastOrNull { it.isNotBlank() }?.take(160)
            return if (detail.isNullOrBlank()) "无法创建截图目录" else "无法创建截图目录：$detail"
        }
        val path = screenshotPath(System.currentTimeMillis())
        val capture = exec(cmdScreencap(path))
        if (isErrorOutput(capture)) {
            val detail = capture.lineSequence().lastOrNull { it.isNotBlank() }?.take(160)
            return if (detail.isNullOrBlank()) "screencap 执行失败" else "screencap 执行失败：$detail"
        }
        val check = exec(cmdCheckScreenshot(path)).trim()
        return when {
            check.contains(FILE_OK) -> {
                exec(cmdMediaScan(path))
                null
            }

            check.contains(FILE_EMPTY) -> "截图文件为空（$path）"
            else -> "截图文件不存在（$path）"
        }
    }

    /** 读取亮度：跳过空行与 "null" 等非数字输出，取最后一个合法整数。 */
    private fun parseBrightness(output: String): Int {
        val value = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.toIntOrNull() != null }
            ?.toIntOrNull()
        return value?.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX) ?: DEFAULT_BRIGHTNESS
    }

    /** shellExec 失败时返回 "ERROR..." 或追加 "ERROR: exit=N"。 */
    private fun isErrorOutput(out: String): Boolean {
        val text = out.trim()
        return text.startsWith("ERROR") || text.contains("\nERROR")
    }

    companion object {
        const val CMD_KEY_BACK = "input keyevent 4"
        const val CMD_KEY_HOME = "input keyevent 3"
        const val CMD_KEY_RECENTS = "input keyevent 187"
        const val CMD_VOLUME_UP = "input keyevent 24"
        const val CMD_VOLUME_DOWN = "input keyevent 25"

        const val CMD_BRIGHTNESS_MODE_MANUAL = "settings put system screen_brightness_mode 0"
        const val CMD_BRIGHTNESS_GET = "settings get system screen_brightness"
        const val BRIGHTNESS_STEP = 10
        const val BRIGHTNESS_MIN = 0
        const val BRIGHTNESS_MAX = 255
        const val DEFAULT_BRIGHTNESS = 128

        const val SCREENSHOT_DIR = "/sdcard/DCIM/Screenshots"
        const val SCREENSHOT_FILE_PREFIX = "pm_"
        const val MEDIA_SCAN_ACTION = "android.intent.action.MEDIA_SCANNER_SCAN_FILE"
        const val CMD_MKDIR_SCREENSHOTS = "mkdir -p $SCREENSHOT_DIR"

        private const val FILE_OK = "PM_FILE_OK"
        private const val FILE_EMPTY = "PM_FILE_EMPTY"

        fun cmdSetBrightness(value: Int): String =
            "settings put system screen_brightness $value"

        fun screenshotPath(timestampMs: Long): String =
            "$SCREENSHOT_DIR/$SCREENSHOT_FILE_PREFIX$timestampMs.png"

        fun cmdScreencap(path: String): String = "screencap -p $path"

        /** 校验截图结果：非空 / 存在但为空 / 不存在。 */
        fun cmdCheckScreenshot(path: String): String =
            "if [ -s $path ]; then echo PM_FILE_OK; " +
                "elif [ -f $path ]; then echo PM_FILE_EMPTY; else echo PM_FILE_MISSING; fi"

        fun cmdMediaScan(path: String): String =
            "am broadcast -a $MEDIA_SCAN_ACTION -d file://$path"
    }
}
