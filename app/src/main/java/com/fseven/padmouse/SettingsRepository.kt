package com.fseven.padmouse

import android.content.Context

/**
 * M7 设置持久化：光标外观、手感参数、按键绑定统一写入 SharedPreferences。
 *
 * 所有读取都带默认值与范围钳制，旧版本数据损坏或缺失时不会出现空配置；
 * 首次升级会从 M4 的 `cursor_settings` 迁移光标外观，用户旧设置不丢。
 */
class SettingsRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyCursorPrefs()
        migrateModeToggleDefaults()
    }

    // ---------------------------------------------------------------- 光标外观

    fun loadCursorSizeDp(): Int = prefs.getInt(KEY_CURSOR_SIZE, MouseService.DEFAULT_SIZE_DP)
        .coerceIn(MouseService.MIN_SIZE_DP, MouseService.MAX_SIZE_DP)

    fun saveCursorSizeDp(sizeDp: Int) {
        prefs.edit()
            .putInt(KEY_CURSOR_SIZE, sizeDp.coerceIn(MouseService.MIN_SIZE_DP, MouseService.MAX_SIZE_DP))
            .apply()
    }

    fun loadCursorColor(): Int = prefs.getInt(KEY_CURSOR_COLOR, MouseService.COLOR_WHITE)

    fun saveCursorColor(color: Int) {
        prefs.edit().putInt(KEY_CURSOR_COLOR, color).apply()
    }

    fun loadCursorIdleSeconds(): Int =
        prefs.getInt(KEY_CURSOR_IDLE_SECONDS, DEFAULT_IDLE_SECONDS)
            .coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS)

    fun saveCursorIdleSeconds(seconds: Int) {
        prefs.edit()
            .putInt(KEY_CURSOR_IDLE_SECONDS, seconds.coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS))
            .apply()
    }

    // ---------------------------------------------------------------- M10 手柄独占 / 模式切换

    /** 独占手柄（当鼠标时不被前台 App 抢事件）：默认开启。 */
    fun loadExclusiveMode(): Boolean = prefs.getBoolean(KEY_EXCLUSIVE_MODE, true)

    fun saveExclusiveMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXCLUSIVE_MODE, enabled).apply()
    }

    /**
     * 模式切换热键（长按切换鼠标/游戏模式）。
     * 不允许清空：空值、轴绑定或组合键一律回退默认 L3，避免切到游戏模式后无法用热键切回。
     */
    fun loadModeToggleBinding(): ActionBinding =
        ActionBinding.decode(prefs.getString(KEY_MODE_TOGGLE, null))
            ?.takeIf { it.direction == 0 && !it.isChord }
            ?: DefaultBindings.MODE_TOGGLE_BINDING

    fun saveModeToggleBinding(binding: ActionBinding) {
        if (binding.direction != 0 || binding.isChord) return
        prefs.edit().putString(KEY_MODE_TOGGLE, binding.encode()).apply()
    }

    // ---------------------------------------------------------------- 手感参数

    fun loadDeadzone(): Float = prefs.getFloat(KEY_DEADZONE, DefaultBindings.DEADZONE)
        .coerceIn(MIN_DEADZONE, MAX_DEADZONE)

    fun saveDeadzone(value: Float) {
        prefs.edit().putFloat(KEY_DEADZONE, value.coerceIn(MIN_DEADZONE, MAX_DEADZONE)).apply()
    }

    fun loadSpeedPxPerSec(): Float =
        prefs.getFloat(KEY_SPEED_PX_PER_SEC, DefaultBindings.SPEED_PX_PER_SEC)
            .coerceIn(MIN_SPEED_PX_PER_SEC, MAX_SPEED_PX_PER_SEC)

    fun saveSpeedPxPerSec(value: Float) {
        prefs.edit()
            .putFloat(KEY_SPEED_PX_PER_SEC, value.coerceIn(MIN_SPEED_PX_PER_SEC, MAX_SPEED_PX_PER_SEC))
            .apply()
    }

    fun loadAccelExponent(): Float =
        prefs.getFloat(KEY_ACCEL_EXPONENT, DefaultBindings.ACCEL_EXPONENT)
            .coerceIn(MIN_ACCEL_EXPONENT, MAX_ACCEL_EXPONENT)

    fun saveAccelExponent(value: Float) {
        prefs.edit()
            .putFloat(KEY_ACCEL_EXPONENT, value.coerceIn(MIN_ACCEL_EXPONENT, MAX_ACCEL_EXPONENT))
            .apply()
    }

    /**
     * 组装 Mapper 配置：手感参数来自持久化，按键绑定由调用方传入
     * （系统动作绑定也一并持久化在同一个文件里）。
     */
    fun loadMapperConfig(
        systemBindings: Map<SystemAction, ActionBinding>
    ): MapperConfig = MapperConfig(
        deadzone = loadDeadzone(),
        speedPxPerSec = loadSpeedPxPerSec(),
        exponent = loadAccelExponent(),
        systemBindings = systemBindings,
    )

    // ---------------------------------------------------------------- 按键绑定

    /** 按 [SystemAction] 枚举顺序返回当前绑定（含默认值补齐）。 */
    fun loadSystemBindings(): Map<SystemAction, ActionBinding> {
        val result = LinkedHashMap<SystemAction, ActionBinding>()
        for (action in SystemAction.values()) {
            val fallback = DefaultBindings.SYSTEM_ACTIONS[action] ?: continue
            result[action] = ActionBinding.decode(prefs.getString(keyOf(action), null)) ?: fallback
        }
        return result
    }

    fun saveSystemBinding(action: SystemAction, binding: ActionBinding) {
        prefs.edit().putString(keyOf(action), binding.encode()).apply()
    }

    /** 清除全部自定义绑定，恢复 [DefaultBindings.SYSTEM_ACTIONS]。 */
    fun resetSystemBindings() {
        val editor = prefs.edit()
        for (action in SystemAction.values()) editor.remove(keyOf(action))
        editor.remove(KEY_MODE_TOGGLE)
        editor.apply()
    }

    private fun keyOf(action: SystemAction): String = "system_action_${action.name.lowercase()}"

    /**
     * M10：L3 由「音量减」让位给模式切换热键，音量改绑十字键左右。
     * 只在旧默认值原样存在时迁移，避免覆盖用户自定义。
     */
    private fun migrateModeToggleDefaults() {
        if (prefs.getBoolean(KEY_MODE_TOGGLE_MIGRATED, false)) return
        val editor = prefs.edit()
        val legacy = mapOf(
            SystemAction.VOLUME_DOWN to ActionBinding(DefaultBindings.BTN_THUMBL),
            SystemAction.VOLUME_UP to ActionBinding(DefaultBindings.BTN_THUMBR),
        )
        for ((action, old) in legacy) {
            val stored = ActionBinding.decode(prefs.getString(keyOf(action), null))
            if (stored == old) {
                DefaultBindings.SYSTEM_ACTIONS[action]?.let {
                    editor.putString(keyOf(action), it.encode())
                }
            }
        }
        editor.putBoolean(KEY_MODE_TOGGLE_MIGRATED, true)
        editor.apply()
    }

    /**
     * M4 使用独立的 `cursor_settings` 文件；升级到 M7 后统一到本仓库。
     * 只在目标键缺失时迁移，避免覆盖用户在新版本里改过的值。
     */
    private fun migrateLegacyCursorPrefs() {
        val legacy = runCatching {
            appContext.getSharedPreferences(LEGACY_CURSOR_PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        if (!legacy.contains(KEY_CURSOR_SIZE) &&
            !legacy.contains(KEY_CURSOR_COLOR) &&
            !legacy.contains(KEY_CURSOR_IDLE_SECONDS)
        ) {
            return
        }
        val editor = prefs.edit()
        if (!prefs.contains(KEY_CURSOR_SIZE) && legacy.contains(KEY_CURSOR_SIZE)) {
            editor.putInt(KEY_CURSOR_SIZE, legacy.getInt(KEY_CURSOR_SIZE, MouseService.DEFAULT_SIZE_DP))
        }
        if (!prefs.contains(KEY_CURSOR_COLOR) && legacy.contains(KEY_CURSOR_COLOR)) {
            editor.putInt(KEY_CURSOR_COLOR, legacy.getInt(KEY_CURSOR_COLOR, MouseService.COLOR_WHITE))
        }
        if (!prefs.contains(KEY_CURSOR_IDLE_SECONDS) && legacy.contains(KEY_CURSOR_IDLE_SECONDS)) {
            editor.putInt(
                KEY_CURSOR_IDLE_SECONDS,
                legacy.getInt(KEY_CURSOR_IDLE_SECONDS, DEFAULT_IDLE_SECONDS)
            )
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "padmouse_settings"
        private const val LEGACY_CURSOR_PREFS = "cursor_settings"

        private const val KEY_CURSOR_SIZE = "cursor_size_dp"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_CURSOR_IDLE_SECONDS = "cursor_idle_seconds"
        private const val KEY_DEADZONE = "feel_deadzone"
        private const val KEY_SPEED_PX_PER_SEC = "feel_speed_px_per_sec"
        private const val KEY_ACCEL_EXPONENT = "feel_accel_exponent"
        private const val KEY_EXCLUSIVE_MODE = "gamepad_exclusive_mode"
        private const val KEY_MODE_TOGGLE = "gamepad_mode_toggle_binding"
        private const val KEY_MODE_TOGGLE_MIGRATED = "gamepad_mode_toggle_migrated_v1"

        const val MIN_IDLE_SECONDS = 1
        const val MAX_IDLE_SECONDS = 10
        const val DEFAULT_IDLE_SECONDS = 3

        const val MIN_SPEED_PX_PER_SEC = 200f
        const val MAX_SPEED_PX_PER_SEC = 2000f
        const val SPEED_STEP = 50f

        const val MIN_DEADZONE = 0.02f
        const val MAX_DEADZONE = 0.40f
        const val DEADZONE_STEP = 0.01f

        const val MIN_ACCEL_EXPONENT = 1.0f
        const val MAX_ACCEL_EXPONENT = 3.0f
        const val EXPONENT_STEP = 0.1f
    }
}
