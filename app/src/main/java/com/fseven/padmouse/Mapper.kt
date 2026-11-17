package com.fseven.padmouse

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    SYSTEM_BACK,
    SYSTEM_HOME,
    SYSTEM_RECENTS,
    SYSTEM_VOLUME_UP,
    SYSTEM_VOLUME_DOWN,
    SYSTEM_BRIGHTNESS_UP,
    SYSTEM_BRIGHTNESS_DOWN,
    SYSTEM_SCREENSHOT,
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
    /** M6 系统动作绑定；设置页修改后通过 [Mapper.updateConfig] 热更新。 */
    val systemBindings: Map<SystemAction, ActionBinding> = DefaultBindings.SYSTEM_ACTIONS,
    /** 同一系统动作的重复触发间隔（防误触）。 */
    val systemDebounceMs: Long = DefaultBindings.SYSTEM_ACTION_DEBOUNCE_MS,
    /** 组合键判定窗口。 */
    val chordGraceMs: Long = DefaultBindings.CHORD_GRACE_MS,
)

/**
 * M5 映射引擎（App 侧）。
 *
 * 以 [Handler] 按固定 60Hz tick 驱动（不依赖 Activity 的 UI 帧，前台/后台持续运行）：
 * - 左摇杆（ABS_X/ABS_Y）→ 死区 + 加速曲线 → 光标位移 → [MouseService.moveTo]；
 * - 右摇杆纵向（ABS_RY）→ 每帧累积，满一格注入一次 scroll；
 * - 按键绑定 → tap / 右键 / 中键 / 滚轮 / 长按拖拽；
 * - 扳机（ABS_Z/ABS_RZ）> 阈值 → 按住左/右键，松开释放。
 *
 * 手柄事件由 UserService 的 GamepadReader 经 IGamepadListener 回传到 App 进程，
 * 本类只消费归一化数值，不直接读设备；必须在有 Looper 的线程（主线程）使用。
 * 屏幕尺寸由宿主（[MouseService]）通过 WindowManager 读取后经 [start] 传入。
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

        /** M6 系统动作；实现方负责转到后台线程执行 shell 命令。 */
        fun systemAction(action: SystemAction)
    }

    /** 调试面板监听；全部在主线程回调。 */
    interface Listener {
        fun onCursor(x: Float, y: Float) {}
        fun onVector(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {}
        fun onAction(action: MapperAction, atMillis: Long) {}
    }

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

    /** 当前物理按下的键（用于组合键判定）。 */
    private val heldKeys = LinkedHashSet<Int>()

    /** 已由组合键消费的键：松开前不再触发单键动作。 */
    private val chordConsumedKeys = HashSet<Int>()

    /** 组合判定窗口内延迟执行的单键动作。 */
    private val pendingKeyActions = HashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    /** 每个系统动作最近一次触发时间，用于 200ms 防抖。 */
    private val lastSystemActionAt = HashMap<SystemAction, Long>()

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
        heldKeys.clear()
        chordConsumedKeys.clear()
        pendingKeyActions.values.forEach { handler.removeCallbacks(it) }
        pendingKeyActions.clear()
        lastSystemActionAt.clear()
        lastFrameNanos = 0L
        running = true

        listener?.onCursor(cursorX, cursorY)
        listener?.onVector(0f, 0f, 0f, 0f)
        handler.post(frameRunnable)
    }

    /** 停止驱动；若仍在拖拽/按住状态，先补发 up，避免按键卡住。 */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(frameRunnable)
        pendingKeyActions.values.forEach { handler.removeCallbacks(it) }
        pendingKeyActions.clear()
        heldKeys.clear()
        chordConsumedKeys.clear()
        releaseAll()
    }

    // ---------------------------------------------------------------- 手柄输入

    /** 按键事件：value = 1 按下 / 0 松开 / 2 自动重复。 */
    fun onKey(code: Int, value: Int) {
        if (!running || value == 2) return
        if (value == 1) {
            if (!heldKeys.add(code)) return
            onKeyDown(code)
        } else {
            heldKeys.remove(code)
            onKeyUp(code)
        }
    }

    /**
     * 按下处理顺序：组合键 → 单键系统动作 → M5 鼠标映射。
     *
     * 参与组合键的键先延迟 [MapperConfig.chordGraceMs] 再执行单键动作；
     * 组合在窗口内成立时，只触发组合动作并吞掉成员的单键动作。
     */
    private fun onKeyDown(code: Int) {
        val chord = config.systemBindings.entries.firstOrNull { (_, binding) ->
            binding.isChordSatisfied(heldKeys)
        }
        if (chord != null) {
            val binding = chord.value
            cancelPending(binding.code)
            cancelPending(binding.chordCode)
            chordConsumedKeys.add(binding.code)
            chordConsumedKeys.add(binding.chordCode)
            triggerSystem(chord.key)
            return
        }

        val system = systemBindingForKey(code)
        if (system != null) {
            if (participatesInChord(code)) {
                schedulePending(code, config.chordGraceMs) { triggerSystem(system.key) }
            } else {
                triggerSystem(system.key)
            }
            return
        }

        val action = config.buttonBindings[code] ?: return
        when (action) {
            MouseAction.TAP -> handleTapKey(1)
            // 右键/中键在松开时触发，避免组合判定窗口引入点击延迟。
            MouseAction.RIGHT_CLICK, MouseAction.MIDDLE_CLICK -> Unit
            MouseAction.SCROLL_UP, MouseAction.SCROLL_DOWN -> {
                if (participatesInChord(code)) {
                    schedulePending(code, config.chordGraceMs) { fireScroll(action) }
                } else {
                    fireScroll(action)
                }
            }

            MouseAction.LEFT_HOLD, MouseAction.RIGHT_HOLD -> updateHold(action, 1f)
        }
    }

    private fun onKeyUp(code: Int) {
        if (chordConsumedKeys.remove(code)) return
        // 组合未成立就松开：立即补发被延迟的单键动作，短按不丢。
        flushPending(code)
        // 系统动作优先于鼠标映射：该键绑定系统动作时，松开不再触发鼠标动作。
        if (systemBindingForKey(code) != null) return
        when (config.buttonBindings[code]) {
            MouseAction.TAP -> handleTapKey(0)
            MouseAction.RIGHT_CLICK -> {
                injector.rightClick(cursorX, cursorY)
                emit(MapperAction.RIGHT_CLICK)
            }

            MouseAction.MIDDLE_CLICK -> {
                injector.middleClick(cursorX, cursorY)
                emit(MapperAction.MIDDLE_CLICK)
            }

            MouseAction.LEFT_HOLD -> updateHold(MouseAction.LEFT_HOLD, 0f)
            MouseAction.RIGHT_HOLD -> updateHold(MouseAction.RIGHT_HOLD, 0f)
            else -> Unit
        }
    }

    private fun fireScroll(action: MouseAction) {
        when (action) {
            MouseAction.SCROLL_UP -> {
                injector.scroll(cursorX, cursorY, 0f, -1f)
                emit(MapperAction.SCROLL_UP)
            }

            MouseAction.SCROLL_DOWN -> {
                injector.scroll(cursorX, cursorY, 0f, 1f)
                emit(MapperAction.SCROLL_DOWN)
            }

            else -> Unit
        }
    }

    /** 组合键判定窗口：延迟执行，组合成立时由 [cancelPending] 取消。 */
    private fun schedulePending(code: Int, delayMs: Long, block: () -> Unit) {
        cancelPending(code)
        val runnable = Runnable {
            pendingKeyActions.remove(code)
            if (running) block()
        }
        pendingKeyActions[code] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelPending(code: Int) {
        pendingKeyActions.remove(code)?.let { handler.removeCallbacks(it) }
    }

    private fun flushPending(code: Int) {
        val runnable = pendingKeyActions.remove(code) ?: return
        handler.removeCallbacks(runnable)
        runnable.run()
    }

    private fun participatesInChord(code: Int): Boolean =
        config.systemBindings.values.any { it.isChord && it.involvesKey(code) }

    /**
     * 按键查找系统动作绑定。
     *
     * 除了直接匹配（如 Select → Back），还会把「十字键按键」别名到等价的
     * 数字轴绑定（BTN_DPAD_UP → ABS_HAT0Y -1），这样同一份亮度绑定
     * 在轴上报/键上报两种手柄上都能生效。
     */
    private fun systemBindingForKey(code: Int): Map.Entry<SystemAction, ActionBinding>? {
        config.systemBindings.entries.firstOrNull { (_, binding) -> binding.matchesKey(code) }
            ?.let { return it }
        val alias = DefaultBindings.DPAD_KEY_AXIS_ALIAS[code] ?: return null
        val axisCode = alias.first
        val direction = alias.second
        return config.systemBindings.entries.firstOrNull { (_, binding) ->
            binding.direction == direction && binding.code == axisCode
        }
    }

    /** 触发系统动作；同一动作 200ms 内只执行一次。 */
    private fun triggerSystem(action: SystemAction) {
        val now = SystemClock.uptimeMillis()
        val last = lastSystemActionAt[action]
        if (last != null && now - last < config.systemDebounceMs) return
        lastSystemActionAt[action] = now
        injector.systemAction(action)
        emit(mapperActionFor(action), now)
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

        // M6 系统动作轴触发（十字键上/下 → 亮度）：只有越过阈值才触发一次。
        val systemAxis = config.systemBindings.entries.firstOrNull { (_, binding) ->
            binding.matchesAxis(code, normalized)
        }
        if (systemAxis != null) {
            triggerSystem(systemAxis.key)
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

    /**
     * 固定 60Hz tick。前台服务的主线程即使没有界面帧也会持续收到 Handler 消息，
     * 因此切到桌面/别的 App 后映射不会停摆。
     */
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val nowNanos = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((nowNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, MAX_FRAME_DT)
            }
            lastFrameNanos = nowNanos
            step(dt, SystemClock.uptimeMillis())
            if (running) handler.postDelayed(this, TICK_MS)
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

    private fun mapperActionFor(action: SystemAction): MapperAction = when (action) {
        SystemAction.BACK -> MapperAction.SYSTEM_BACK
        SystemAction.HOME -> MapperAction.SYSTEM_HOME
        SystemAction.RECENTS -> MapperAction.SYSTEM_RECENTS
        SystemAction.VOLUME_UP -> MapperAction.SYSTEM_VOLUME_UP
        SystemAction.VOLUME_DOWN -> MapperAction.SYSTEM_VOLUME_DOWN
        SystemAction.BRIGHTNESS_UP -> MapperAction.SYSTEM_BRIGHTNESS_UP
        SystemAction.BRIGHTNESS_DOWN -> MapperAction.SYSTEM_BRIGHTNESS_DOWN
        SystemAction.SCREENSHOT -> MapperAction.SYSTEM_SCREENSHOT
    }

    companion object {
        /** 单帧最大步长，避免卡顿/恢复后光标瞬移。 */
        private const val MAX_FRAME_DT = 0.1f

        /** 60Hz tick；Handler 驱动，不依赖界面帧。 */
        private const val TICK_MS = 16L
    }
}
