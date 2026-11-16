package com.fseven.padmouse

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.pow

/** 按键/轴绑定的鼠标动作。 */
enum class MouseAction {
    TAP,
    RIGHT_CLICK,
    MIDDLE_CLICK,
    SCROLL_UP,
    SCROLL_DOWN,
    LEFT_HOLD,
    RIGHT_HOLD,
}

/** 映射产生、供调试面板展示的动作。 */
enum class MapperAction {
    TAP,
    RIGHT_CLICK,
    MIDDLE_CLICK,
    DRAG_START,
    DRAG_END,
    SCROLL_UP,
    SCROLL_DOWN,
    PRIMARY_HOLD,
    PRIMARY_RELEASE,
    SECONDARY_HOLD,
    SECONDARY_RELEASE,
}

/**
 * 可配置映射参数。默认值集中在 [DefaultBindings]，后续设置页（M7）可直接覆盖。
 *
 * @param deadzone 摇杆死区（0..1，按合成幅值判定）
 * @param speedPxPerSec 摇杆推满时的光标速度
 * @param exponent 加速曲线指数：speed = base * remapped^exponent
 * @param longPressMs 长按判定阈值（A 键长按进入拖拽）
 * @param triggerThreshold 扳机按下阈值（归一化后）
 * @param scrollNotchesPerSecond 右摇杆推满时每秒滚轮格数
 */
data class MapperConfig(
    val deadzone: Float = DefaultBindings.DEADZONE,
    val speedPxPerSec: Float = DefaultBindings.SPEED_PX_PER_SEC,
    val exponent: Float = DefaultBindings.ACCEL_EXPONENT,
    val longPressMs: Long = DefaultBindings.LONG_PRESS_MS,
    val triggerThreshold: Float = DefaultBindings.TRIGGER_THRESHOLD,
    val scrollNotchesPerSecond: Float = DefaultBindings.SCROLL_NOTCHES_PER_SEC,
    val moveAxisX: Int = DefaultBindings.MOVE_AXIS_X,
    val moveAxisY: Int = DefaultBindings.MOVE_AXIS_Y,
    val scrollAxisX: Int = DefaultBindings.SCROLL_AXIS_X,
    val scrollAxisY: Int = DefaultBindings.SCROLL_AXIS_Y,
    val buttonBindings: Map<Int, MouseAction> = DefaultBindings.BUTTON_ACTIONS,
    val axisBindings: Map<Int, MouseAction> = DefaultBindings.AXIS_ACTIONS,
)

/**
 * M5 映射引擎（App 侧）。
 *
 * 以 [Choreographer] 按屏幕刷新率（通常 60Hz）驱动：
 * - 左摇杆（ABS_X/ABS_Y）→ 死区 + 加速曲线 → 光标位移 → [MouseService.moveTo]；
 * - 右摇杆纵向（ABS_RY）→ 每帧累积，满一格注入一次 scroll；
 * - 按键绑定 → tap / 右键 / 中键 / 滚轮 / 长按拖拽；
 * - 扳机（ABS_Z/ABS_RZ）> 阈值 → 按住左/右键，松开释放。
 *
 * 手柄事件由 UserService 的 GamepadReader 经 IGamepadListener 回传到 App 进程，
 * 本类只消费归一化数值，不直接读设备；必须在有 Looper 的线程（主线程）使用。
 */
class Mapper(
    private val injector: Injector,
    private var config: MapperConfig = MapperConfig(),
    private val listener: Listener? = null,
) {

    /** 实际注入动作的抽象，MainActivity 用 ShizukuServiceManager 实现。 */
    interface Injector {
        fun tap(x: Float, y: Float)
        fun rightClick(x: Float, y: Float)
        fun middleClick(x: Float, y: Float)
        fun touchDown(x: Float, y: Float)
        fun touchMove(x: Float, y: Float)
        fun touchUp(x: Float, y: Float)
        fun mouseDown(x: Float, y: Float, button: Int)
        fun mouseUp(x: Float, y: Float, button: Int)
        fun mouseMove(x: Float, y: Float, buttonState: Int)
        fun scroll(x: Float, y: Float, h: Float, v: Float)
    }

    /** 调试面板监听；全部在主线程回调。 */
    interface Listener {
        fun onCursor(x: Float, y: Float) {}
        fun onVector(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {}
        fun onAction(action: MapperAction, atMillis: Long) {}
    }

    private val choreographer: Choreographer = Choreographer.getInstance()

    private var running = false
    private var lastFrameNanos = 0L
    private var screenWidth = 1
    private var screenHeight = 1

    private var cursorX = 0f
    private var cursorY = 0f

    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f

    private var scrollAccumulator = 0f

    /** A 键（TAP 绑定）当前是否按下；长按到达阈值后进入拖拽。 */
    private var tapKeyActive = false
    private var tapKeyDownAt = 0L
    private var dragging = false

    private var primaryHeld = false
    private var secondaryHeld = false

    val isRunning: Boolean
        get() = running

    val configSnapshot: MapperConfig
        get() = config

    fun updateConfig(newConfig: MapperConfig) {
        config = newConfig
    }

    /**
     * 开始 60Hz 驱动。屏幕尺寸用于钳制光标；初始位置取当前悬浮光标，
     * 光标服务尚未就绪时退回屏幕中心。
     */
    fun start(width: Int, height: Int) {
        if (running) return
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        val position = MouseService.currentPosition()
        if (position != null && position.size >= 2) {
            cursorX = position[0]
            cursorY = position[1]
        } else {
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
        }

        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f
        scrollAccumulator = 0f
        tapKeyActive = false
        dragging = false
        primaryHeld = false
        secondaryHeld = false
        lastFrameNanos = 0L
        running = true

        listener?.onCursor(cursorX, cursorY)
        listener?.onVector(0f, 0f, 0f, 0f)
        choreographer.postFrameCallback(frameCallback)
    }

    /** 停止驱动；若仍在拖拽/按住状态，先补发 up，避免按键卡住。 */
    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
        releaseAll()
    }

    // ---------------------------------------------------------------- 手柄输入

    /** 按键事件：value = 1 按下 / 0 松开 / 2 自动重复。 */
    fun onKey(code: Int, value: Int) {
        if (!running || value == 2) return
        val action = config.buttonBindings[code] ?: return
        when (action) {
            MouseAction.TAP -> handleTapKey(value)
            MouseAction.RIGHT_CLICK -> if (value == 0) {
                injector.rightClick(cursorX, cursorY)
                emit(MapperAction.RIGHT_CLICK)
            }

            MouseAction.MIDDLE_CLICK -> if (value == 0) {
                injector.middleClick(cursorX, cursorY)
                emit(MapperAction.MIDDLE_CLICK)
            }

            MouseAction.SCROLL_UP -> if (value == 1) {
                injector.scroll(cursorX, cursorY, 0f, -1f)
                emit(MapperAction.SCROLL_UP)
            }

            MouseAction.SCROLL_DOWN -> if (value == 1) {
                injector.scroll(cursorX, cursorY, 0f, 1f)
                emit(MapperAction.SCROLL_DOWN)
            }

            MouseAction.LEFT_HOLD -> updateHold(action, if (value == 1) 1f else 0f)
            MouseAction.RIGHT_HOLD -> updateHold(action, if (value == 1) 1f else 0f)
        }
    }

    /** 轴事件：normalized 已由 GamepadReader 按 getevent 的 min/max 映射到 -1..1。 */
    fun onAxis(code: Int, normalized: Float) {
        if (!running) return

        val axisAction = config.axisBindings[code]
        if (axisAction != null) {
            if (axisAction == MouseAction.LEFT_HOLD || axisAction == MouseAction.RIGHT_HOLD) {
                updateHold(axisAction, normalized)
            }
            return
        }

        when (code) {
            config.moveAxisX -> leftStickX = normalized
            config.moveAxisY -> leftStickY = normalized
            config.scrollAxisX -> rightStickX = normalized
            config.scrollAxisY -> rightStickY = normalized
            else -> return
        }
        listener?.onVector(leftStickX, leftStickY, rightStickX, rightStickY)
    }

    private fun handleTapKey(value: Int) {
        if (value == 1) {
            if (dragging) return
            tapKeyActive = true
            tapKeyDownAt = SystemClock.uptimeMillis()
        } else if (value == 0) {
            if (dragging) {
                dragging = false
                injector.touchUp(cursorX, cursorY)
                emit(MapperAction.DRAG_END)
            } else if (tapKeyActive) {
                injector.tap(cursorX, cursorY)
                emit(MapperAction.TAP)
            }
            tapKeyActive = false
        }
    }

    /** 扳机轴：超过阈值按下、回落到阈值的 70% 以下才释放（滞回防抖）。 */
    private fun updateHold(action: MouseAction, normalized: Float) {
        val press = normalized >= config.triggerThreshold
        val release = normalized <= config.triggerThreshold * DefaultBindings.TRIGGER_RELEASE_RATIO
        when (action) {
            MouseAction.LEFT_HOLD -> {
                if (press && !primaryHeld) {
                    primaryHeld = true
                    injector.mouseDown(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    emit(MapperAction.PRIMARY_HOLD)
                } else if (release && primaryHeld) {
                    primaryHeld = false
                    injector.mouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    emit(MapperAction.PRIMARY_RELEASE)
                }
            }

            MouseAction.RIGHT_HOLD -> {
                if (press && !secondaryHeld) {
                    secondaryHeld = true
                    injector.mouseDown(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY)
                    emit(MapperAction.SECONDARY_HOLD)
                } else if (release && secondaryHeld) {
                    secondaryHeld = false
                    injector.mouseUp(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY)
                    emit(MapperAction.SECONDARY_RELEASE)
                }
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 帧驱动

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, MAX_FRAME_DT)
            }
            lastFrameNanos = frameTimeNanos
            step(dt, SystemClock.uptimeMillis())
            if (running) choreographer.postFrameCallback(this)
        }
    }

    private fun step(dt: Float, now: Long) {
        // 长按判定：A 键按住超过阈值仍未松开 → 光标处按下并进入拖拽态。
        if (tapKeyActive && !dragging && now - tapKeyDownAt >= config.longPressMs) {
            dragging = true
            injector.touchDown(cursorX, cursorY)
            emit(MapperAction.DRAG_START, now)
        }

        val previousX = cursorX
        val previousY = cursorY
        if (dt > 0f) {
            val speed = currentSpeed()
            if (speed > 0f) {
                val magnitude = hypot(leftStickX, leftStickY)
                if (magnitude > 0f) {
                    cursorX = (cursorX + leftStickX / magnitude * speed * dt)
                        .coerceIn(0f, screenWidth.toFloat())
                    cursorY = (cursorY + leftStickY / magnitude * speed * dt)
                        .coerceIn(0f, screenHeight.toFloat())
                }
            }
            updateScroll(dt)
        }

        if (cursorX == previousX && cursorY == previousY) return

        MouseService.moveTo(cursorX, cursorY)
        if (dragging) injector.touchMove(cursorX, cursorY)
        val buttonState = mouseButtonState()
        if (buttonState != 0) injector.mouseMove(cursorX, cursorY, buttonState)
        listener?.onCursor(cursorX, cursorY)
    }

    /** 死区重映射 + 加速曲线：speed = base * ((mag - dz) / (1 - dz))^exponent。 */
    private fun currentSpeed(): Float {
        val magnitude = hypot(leftStickX, leftStickY)
        if (magnitude <= config.deadzone) return 0f
        val remapped = ((magnitude - config.deadzone) / (1f - config.deadzone)).coerceIn(0f, 1f)
        return config.speedPxPerSec * remapped.pow(config.exponent)
    }

    /** 右摇杆纵向累积滚动：满 1 格注入一次 scroll，余量保留到下一帧。 */
    private fun updateScroll(dt: Float) {
        if (kotlin.math.abs(rightStickY) <= config.deadzone) {
            scrollAccumulator = 0f
            return
        }
        scrollAccumulator += rightStickY * config.scrollNotchesPerSecond * dt
        while (scrollAccumulator >= 1f) {
            scrollAccumulator -= 1f
            injector.scroll(cursorX, cursorY, 0f, 1f)
            emit(MapperAction.SCROLL_DOWN)
        }
        while (scrollAccumulator <= -1f) {
            scrollAccumulator += 1f
            injector.scroll(cursorX, cursorY, 0f, -1f)
            emit(MapperAction.SCROLL_UP)
        }
    }

    private fun mouseButtonState(): Int {
        var state = 0
        if (primaryHeld) state = state or MotionEvent.BUTTON_PRIMARY
        if (secondaryHeld) state = state or MotionEvent.BUTTON_SECONDARY
        return state
    }

    private fun releaseAll() {
        if (dragging) {
            dragging = false
            runCatching { injector.touchUp(cursorX, cursorY) }
        }
        if (primaryHeld) {
            primaryHeld = false
            runCatching { injector.mouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY) }
        }
        if (secondaryHeld) {
            secondaryHeld = false
            runCatching { injector.mouseUp(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY) }
        }
        tapKeyActive = false
        scrollAccumulator = 0f
    }

    private fun emit(action: MapperAction, atMillis: Long = SystemClock.uptimeMillis()) {
        listener?.onAction(action, atMillis)
    }

    companion object {
        /** 单帧最大步长，避免卡顿/恢复后光标瞬移。 */
        private const val MAX_FRAME_DT = 0.1f
    }
}
