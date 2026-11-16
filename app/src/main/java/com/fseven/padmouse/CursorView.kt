package com.fseven.padmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 自绘鼠标光标视图。
 *
 * 热点（箭头尖端）固定在视图左上角 (0,0)，所以悬浮窗的 x/y 就是光标的屏幕坐标。
 * 视图宽度比高度窄，避免窗口在水平方向留下多余的不可见区域。
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 光标箭头高度（像素），由 dp 设置换算。 */
    private var cursorHeightPx = DEFAULT_SIZE_DP * density

    /** 外描边宽度：1 物理像素，保证深/浅背景上都有轮廓。 */
    private var strokeWidthPx = 1f

    private var fillColor = Color.WHITE

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
    }

    private val path = Path()

    init {
        updatePaints()
    }

    /**
     * 设置光标高度（dp）。范围外的值会被钳制。
     */
    fun setCursorSizeDp(sizeDp: Int) {
        val clamped = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        val newHeightPx = clamped * density
        if (newHeightPx == cursorHeightPx) return
        cursorHeightPx = newHeightPx
        requestLayout()
        invalidate()
    }

    /**
     * 设置光标填充色。描边色按填充色亮度自动取深/浅，保证可见。
     */
    fun setCursorColor(color: Int) {
        if (fillColor == color) return
        fillColor = color
        updatePaints()
        invalidate()
    }

    private fun updatePaints() {
        fillPaint.color = fillColor
        strokePaint.color = if (Color.luminance(fillColor) > 0.5f) DARK_OUTLINE else LIGHT_OUTLINE
        strokePaint.strokeWidth = strokeWidthPx
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = max(1, (cursorHeightPx * WIDTH_RATIO).roundToInt())
        val height = max(1, cursorHeightPx.roundToInt())
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildPath(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    private fun buildPath(w: Float, h: Float) {
        val inset = strokeWidthPx / 2f
        val availableWidth = max(1f, w - inset * 2f)
        val availableHeight = max(1f, h - inset * 2f)
        path.reset()
        var index = 0
        while (index < UNIT_POINTS.size) {
            val x = inset + UNIT_POINTS[index] * availableWidth
            val y = inset + UNIT_POINTS[index + 1] * availableHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            index += 2
        }
        path.close()
    }

    companion object {
        const val MIN_SIZE_DP = 20
        const val MAX_SIZE_DP = 48
        const val DEFAULT_SIZE_DP = 28

        /** 视图宽度 / 高度：标准指针轮廓的宽高比约 0.73，取 0.75 留一点余量。 */
        const val WIDTH_RATIO = 0.75f

        private const val DARK_OUTLINE = 0xCC000000.toInt()
        private const val LIGHT_OUTLINE = 0xCCFFFFFF.toInt()

        /**
         * 标准鼠标指针轮廓，归一化到 [0,1] x [0,1]，尖端位于 (0,0)。
         * 由 24x24 的标准指针路径（尖端 5,3）换算而来。
         */
        private val UNIT_POINTS = floatArrayOf(
            0f, 0f,
            0f, 0.914f,
            0.338f, 0.667f,
            0.559f, 1f,
            0.750f, 0.930f,
            0.529f, 0.602f,
            1f, 0.602f
        )
    }
}
