package com.dizzle.chessai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.dizzle.chessai.core.vision.BoardRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draws two pulsing glow dots (piece to move + target square) over the board. Not touchable. */
class HighlightView(context: Context) : View(context) {
    private var from = -1
    private var to = -1
    private var pulse = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val animator = ValueAnimator.ofFloat(0.8f, 1.1f).apply {
        duration = 750
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    fun show(fromVisual: Int, toVisual: Int) {
        from = fromVisual; to = toVisual
        if (!animator.isStarted) animator.start()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun center(v: Int, q: Float) = floatArrayOf((v % 8 + 0.5f) * q, (v / 8 + 0.5f) * q)

    override fun onDraw(canvas: Canvas) {
        if (from < 0 || to < 0) return
        val q = width / 8f
        val a = center(from, q)
        val b = center(to, q)
        line.strokeWidth = q * 0.07f
        line.color = Color.argb(150, 255, 214, 0)
        canvas.drawLine(a[0], a[1], b[0], b[1], line)
        glow(canvas, a, q, Color.rgb(0, 229, 255))
        glow(canvas, b, q, Color.rgb(255, 214, 0))
    }

    private fun glow(canvas: Canvas, c: FloatArray, q: Float, color: Int) {
        val r = q * 0.48f * pulse
        val rr = Color.red(color); val gg = Color.green(color); val bb = Color.blue(color)
        paint.shader = RadialGradient(
            c[0], c[1], r,
            intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(235, rr, gg, bb), Color.argb(110, rr, gg, bb), Color.argb(0, rr, gg, bb)),
            floatArrayOf(0f, 0.22f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(c[0], c[1], r, paint)
        paint.shader = null
    }
}

/**
 * Full-screen calibration overlay: shows an 8x8 grid that the user drags (move) or pulls at the
 * corner handle (resize) until it covers the board.
 */
@SuppressLint("ViewConstructor")
class CalibrationView(context: Context, initial: BoardRect) : View(context) {
    var rect = RectF(initial.x.toFloat(), initial.y.toFloat(), (initial.x + initial.size).toFloat(), (initial.y + initial.size).toFloat())
        private set
    private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(255, 214, 0); strokeWidth = 6f }
    private val grid = Paint().apply { style = Paint.Style.STROKE; color = Color.argb(170, 255, 214, 0); strokeWidth = 2f }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 0) }
    private var mode = 0 // 1 = move, 2 = resize
    private var lx = 0f
    private var ly = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, rect.top, dim)
        canvas.drawRect(0f, rect.bottom, w, h, dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
        val q = rect.width() / 8
        for (i in 1 until 8) {
            canvas.drawLine(rect.left + i * q, rect.top, rect.left + i * q, rect.bottom, grid)
            canvas.drawLine(rect.left, rect.top + i * q, rect.right, rect.top + i * q, grid)
        }
        canvas.drawRect(rect, frame)
        canvas.drawCircle(rect.right, rect.bottom, 28f, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = if (abs(e.x - rect.right) < 90 && abs(e.y - rect.bottom) < 90) 2 else 1
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lx; val dy = e.y - ly
                lx = e.x; ly = e.y
                if (mode == 1) rect.offset(dx, dy)
                else if (mode == 2) {
                    val s = max(120f, min(rect.width() + (dx + dy) / 2, width.toFloat()))
                    rect.right = rect.left + s; rect.bottom = rect.top + s
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    fun result() = BoardRect(rect.left.toInt(), rect.top.toInt(), rect.width().toInt())
}
