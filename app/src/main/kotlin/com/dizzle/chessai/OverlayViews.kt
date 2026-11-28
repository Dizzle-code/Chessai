package com.dizzle.chessai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.dizzle.chessai.core.vision.BoardRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-art move marker over the board (not touchable): cyan pixel brackets around the piece to
 * move, a gold pixel target on the destination and a dotted pixel trail between them.
 * Plays a short pop-in animation and then stays perfectly still – the continuous scan compares
 * frames, so a looping animation would look like board changes.
 */
class HighlightView(context: Context) : View(context) {
    private var from = -1
    private var to = -1
    private var t = 1f
    private val paint = Paint()
    private var animator: ValueAnimator? = null

    fun show(fromVisual: Int, toVisual: Int) {
        val changed = fromVisual != from || toVisual != to
        from = fromVisual; to = toVisual
        if (changed) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        } else invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (from < 0 || to < 0) return
        Themes.current(context).drawMarker(canvas, width.toFloat(), from, to, t)
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
    private val dim = Paint().apply { color = Color.argb(140, 10, 11, 14) }
    private val accent = Themes.current(context).accent
    private val frame = Paint().apply { style = Paint.Style.STROKE; color = accent; strokeWidth = 8f }
    private val frameInk = Paint().apply { style = Paint.Style.STROKE; color = Pal.INK; strokeWidth = 16f }
    private val grid = Paint().apply { style = Paint.Style.STROKE; color = alpha(accent, 190); strokeWidth = 3f }
    private val handle = Paint().apply { color = accent }
    private val ink = Paint().apply { color = Pal.INK }
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
        canvas.drawRect(rect, frameInk)
        canvas.drawRect(rect, frame)
        // square pixel handle bottom-right
        canvas.drawRect(rect.right - 30f, rect.bottom - 30f, rect.right + 30f, rect.bottom + 30f, ink)
        canvas.drawRect(rect.right - 22f, rect.bottom - 22f, rect.right + 22f, rect.bottom + 22f, handle)
        canvas.drawRect(rect.right - 8f, rect.bottom - 8f, rect.right + 8f, rect.bottom + 8f, ink)
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
