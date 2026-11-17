package com.fseven.padmouse

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** 启动链路阶段；Activity 与 [MouseService] 通过 [MouseRuntime] 共享。 */
enum class RunStage {
    IDLE,
    STARTING_SERVICE,
    BINDING_SHIZUKU,
    READING_GAMEPAD,
    RUNNING,
    FAILED,
}

/** 手柄按键回显的一条记录；时间戳在展示时格式化。 */
data class GamepadKeyEntry(
    val code: Int,
    val value: Int,
    val atMillis: Long,
)

/**
 * Activity 与前台服务之间的唯一共享状态。
 *
 * 映射引擎（[Mapper]）与手柄监听全部运行在 [MouseService] 内，主界面只读取本对象做展示，
 * 因此 Activity 退出、切后台都不会影响映射；同时避免两个 Mapper 实例互相抢注入。
 *
 * 所有写入方法都可在任意线程调用；界面回调统一切回主线程。
 */
object MouseRuntime {

    private const val KEY_EVENT_LIMIT = 10

    @Volatile
    var stage: RunStage = RunStage.IDLE
        private set

    /** 失败/进行中的补充说明，直接展示给用户（可操作提示）。 */
    @Volatile
    var stageDetail: String? = null
        private set

    @Volatile
    var userServiceState: UserServiceState = UserServiceState.DISCONNECTED

    @Volatile
    var mappingRunning: Boolean = false

    /**
     * 本 App 的界面是否在前台。
     *
     * UHID 模式下系统在部分 App（微信、微信读书等）里不画系统光标
     * （CursorInputMapper 的 DisplayId 停在 -1），此时需要用自绘光标兜底；
     * 但回到我们自己的界面时系统光标是显示的，必须收回自绘光标，
     * 否则会出现两个箭头。
     */
    @Volatile
    var appForeground: Boolean = false

    @Volatile
    var cursorX: Float = 0f

    @Volatile
    var cursorY: Float = 0f

    @Volatile
    var leftX: Float = 0f

    @Volatile
    var leftY: Float = 0f

    @Volatile
    var rightX: Float = 0f

    @Volatile
    var rightY: Float = 0f

    @Volatile
    var lastAction: MapperAction? = null

    @Volatile
    var lastActionAt: Long = 0L

    /** 当前手柄设备摘要（UserService 侧 GamepadReader 回传）。 */
    @Volatile
    var gamepadSummary: String? = null

    /** 服务当前使用的映射配置，供界面展示手感参数。 */
    @Volatile
    var mapperConfig: MapperConfig? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val axisLock = Any()
    private val axisValues = LinkedHashMap<Int, Float>()

    private val eventLock = Any()
    private val keyEvents = ArrayDeque<GamepadKeyEntry>()

    @Volatile
    private var serviceManager: ShizukuServiceManager? = null

    /**
     * 进程内唯一的 UserService 绑定管理器。Activity 与 MouseService 共用同一实例，
     * 避免重复绑定，也让 daemon 进程得以复用。可在任意线程调用。
     */
    fun ensureManager(context: Context): ShizukuServiceManager = synchronized(this) {
        val existing = serviceManager
        if (existing != null) {
            existing.start()
            return@synchronized existing
        }
        val created = ShizukuServiceManager(context.applicationContext)
        created.addListener { state ->
            userServiceState = state
            notifyChanged()
        }
        serviceManager = created
        created.start()
        created
    }

    /** 服务销毁时解绑；daemon 进程保留（remove=false），下次启动直接复用。 */
    fun releaseManager() {
        val current = synchronized(this) {
            val value = serviceManager
            serviceManager = null
            value
        }
        current?.stop()
        userServiceState = UserServiceState.DISCONNECTED
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 状态变更通知；非主线程调用会自动切到主线程。 */
    fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fire()
        } else {
            mainHandler.post { fire() }
        }
    }

    private fun fire() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }

    fun setStage(stage: RunStage, detail: String? = null) {
        this.stage = stage
        this.stageDetail = detail
        notifyChanged()
    }

    fun setCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
    }

    fun setVector(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        this.leftX = leftX
        this.leftY = leftY
        this.rightX = rightX
        this.rightY = rightY
    }

    fun setAction(action: MapperAction, atMillis: Long) {
        lastAction = action
        lastActionAt = atMillis
    }

    fun updateAxes(code: Int, normalized: Float) {
        synchronized(axisLock) { axisValues[code] = normalized }
        when (code) {
            GamepadAxes.ABS_X -> leftX = normalized
            GamepadAxes.ABS_Y -> leftY = normalized
            GamepadAxes.ABS_RX -> rightX = normalized
            GamepadAxes.ABS_RY -> rightY = normalized
        }
        // 轴事件频率高（~60Hz），只写快照由界面轮询读取，避免每次事件都触发 UI 刷新。
    }

    fun axesSnapshot(): List<Pair<Int, Float>> =
        synchronized(axisLock) { axisValues.entries.map { it.key to it.value } }

    fun addKeyEvent(code: Int, value: Int, atMillis: Long) {
        synchronized(eventLock) {
            keyEvents.addFirst(GamepadKeyEntry(code, value, atMillis))
            while (keyEvents.size > KEY_EVENT_LIMIT) keyEvents.removeLast()
        }
    }

    fun keyEventsSnapshot(): List<GamepadKeyEntry> =
        synchronized(eventLock) { keyEvents.toList() }

    fun clearGamepadDebug() {
        synchronized(eventLock) { keyEvents.clear() }
        synchronized(axisLock) { axisValues.clear() }
        notifyChanged()
    }

    /** 停止/重启时复位所有运行态，避免界面残留上一次的数据。 */
    fun resetRunningState() {
        mappingRunning = false
        lastAction = null
        lastActionAt = 0L
        notifyChanged()
    }
}
