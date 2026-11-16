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
    const val TRIGGER_THRESHOLD = 0.5f

    /** 扳机释放阈值 = 按下阈值 * 该比例（滞回）。 */
    const val TRIGGER_RELEASE_RATIO = 0.7f

    /** 右摇杆推满时每秒滚轮格数。 */
    const val SCROLL_NOTCHES_PER_SEC = 8f

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
}
