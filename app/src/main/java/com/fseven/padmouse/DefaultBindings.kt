package com.fseven.padmouse

/**
 * M5 默认映射表（Xbox 布局，Linux input-event-codes）。
 *
 * 全部默认值集中在此，方便后续设置页（M7）读取/覆盖；
 * [MapperConfig] 的构造参数默认就引用这里。
 */
object DefaultBindings {

    /** 摇杆死区：合成幅值低于该值不移动。 */
    const val DEADZONE = 0.15f

    /** 摇杆推满时的光标速度（px/s）。 */
    const val SPEED_PX_PER_SEC = 900f

    /** 加速曲线指数：speed = base * (deadzoneRemap)^exponent。 */
    const val ACCEL_EXPONENT = 1.8f

    /** A 键长按进入拖拽的阈值。 */
    const val LONG_PRESS_MS = 350L

    /** 扳机按下阈值（归一化后）。 */
    const val TRIGGER_THRESHOLD = 0.85f

    /** 扳机释放阈值 = 按下阈值 * 该比例（滞回）。 */
    const val TRIGGER_RELEASE_RATIO = 0.7f

    /** 右摇杆推满时每秒滚轮格数。 */
    const val SCROLL_NOTCHES_PER_SEC = 8f

    /** 系统动作防误触：同一动作触发后该时间内不重复触发。 */
    const val SYSTEM_ACTION_DEBOUNCE_MS = 200L

    /**
     * 组合键判定窗口：参与组合的键按下后先等这么久，
     * 期间组合成立则只触发组合动作，否则补发单键动作。
     *
     * M11：90ms 对真人拇指来说太严，放宽到 250ms；组合判定不要求两键
     * 同时落下，先按住的键会一直参与判定（见 Mapper 的 heldKeys）。
     */
    const val CHORD_GRACE_MS = 250L

    const val MOVE_AXIS_X = GamepadAxes.ABS_X
    const val MOVE_AXIS_Y = GamepadAxes.ABS_Y
    const val SCROLL_AXIS_X = GamepadAxes.ABS_RX
    const val SCROLL_AXIS_Y = GamepadAxes.ABS_RY

    // Linux input-event-codes：Xbox 手柄的物理键位。
    const val BTN_A = 0x130 // BTN_SOUTH
    const val BTN_B = 0x131 // BTN_EAST
    const val BTN_X = 0x134 // BTN_WEST
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e

    /** 十字键数字轴（上/左=-1，下/右=+1）。 */
    const val AXIS_HAT0X = 16
    const val AXIS_HAT0Y = 17

    /** 部分手柄把十字键上报为独立按键。 */
    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221
    const val BTN_DPAD_LEFT = 0x222
    const val BTN_DPAD_RIGHT = 0x223

    /**
     * 十字键上报方式的别名：部分手柄上报 EV_KEY + BTN_DPAD_UP/DOWN，
     * 另一些上报 EV_ABS + ABS_HAT0Y。这里把按键映射到等价的（轴 code, 方向），
     * 让同一份「十字键上/下 → 亮度」绑定同时兼容两种设备。
     */
    val DPAD_KEY_AXIS_ALIAS: Map<Int, Pair<Int, Int>> = mapOf(
        BTN_DPAD_UP to (AXIS_HAT0Y to -1),
        BTN_DPAD_DOWN to (AXIS_HAT0Y to 1),
    )

    /** 按键 → 鼠标动作。 */
    val BUTTON_ACTIONS: Map<Int, MouseAction> = linkedMapOf(
        BTN_A to MouseAction.TAP,
        BTN_B to MouseAction.RIGHT_CLICK,
        BTN_X to MouseAction.MIDDLE_CLICK,
        BTN_TL to MouseAction.SCROLL_UP,
        BTN_TR to MouseAction.SCROLL_DOWN,
    )

    /** 扳机轴 → 按住动作。 */
    val AXIS_ACTIONS: Map<Int, MouseAction> = linkedMapOf(
        GamepadAxes.ABS_Z to MouseAction.LEFT_HOLD,
        GamepadAxes.ABS_RZ to MouseAction.RIGHT_HOLD,
    )

    /**
     * M6 系统动作默认绑定（Xbox 布局）：
     * - Start → Home，Select → Back，Xbox 键 → Recents
     * - 左摇杆按下 → 音量减，右摇杆按下 → 音量加
     * - 十字键上/下 → 亮度加/减（ABS_HAT0Y 为数字轴）
     * - LB+RB 同时按下 → 截图
     *
     * 单独按 LB/RB 仍是滚轮上/下；组合判定窗口见 [CHORD_GRACE_MS]。
     */
    val SYSTEM_ACTIONS: Map<SystemAction, ActionBinding> = linkedMapOf(
        SystemAction.BACK to ActionBinding(BTN_SELECT),
        SystemAction.HOME to ActionBinding(BTN_START),
        SystemAction.RECENTS to ActionBinding(BTN_MODE),
        SystemAction.VOLUME_DOWN to ActionBinding(BTN_THUMBL),
        SystemAction.VOLUME_UP to ActionBinding(BTN_THUMBR),
        SystemAction.BRIGHTNESS_UP to ActionBinding(AXIS_HAT0Y, direction = -1),
        SystemAction.BRIGHTNESS_DOWN to ActionBinding(AXIS_HAT0Y, direction = 1),
        SystemAction.SCREENSHOT to ActionBinding(BTN_TL, chordCode = BTN_TR),
    )
}
