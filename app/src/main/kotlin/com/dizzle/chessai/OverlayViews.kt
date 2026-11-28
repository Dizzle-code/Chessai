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
        val q = width / 8f
        val u = q / 16f                       // one "pixel" of the marker
        val fx = (from % 8) * q; val fy = (from / 8) * q
        val tx = (to % 8) * q; val ty = (to / 8) * q

        // dotted trail (appears progressively)
        val ax = fx + q / 2; val ay = fy + q / 2
        val bx = tx + q / 2; val by = ty + q / 2
        val dist = kotlin.math.hypot(bx - ax, by - ay)
        val n = (dist / (q * 0.3f)).toInt()
        for (i in 1 until n) {
            val f = i.toFloat() / n
            if (f > t) break
            val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
            // keep the trail outside the start and target squares
            if (kotlin.math.hypot(x - ax, y - ay) < q * 0.62f || kotlin.math.hypot(x - bx, y - by) < q * 0.5f) continue
            val sx = (x / u).toInt() * u; val sy = (y / u).toInt() * u   // snap to the pixel grid
            paint.color = Pal.INK
            canvas.drawRect(sx - u, sy - u, sx + 2 * u, sy + 2 * u, paint)
            paint.color = Pal.GOLD
            canvas.drawRect(sx - u * 0.0f, sy - u * 0.0f, sx + u, sy + u, paint)
        }

        // cyan corner brackets around the piece (slide in from outside)
        val inset = (1.5f + (1f - t) * -3f) * u
        brackets(canvas, fx + inset, fy + inset, fx + q - inset, fy + q - inset, u)

        // gold target on the destination (pops in)
        val s = (0.7f + 0.45f * t)
        target(canvas, tx + q / 2, ty + q / 2, u * s)
    }

    private fun brackets(c: Canvas, l: Float, t0: Float, r: Float, b: Float, u: Float) {
        val len = 4 * u
        val th = 1.5f * u
        // outline first, then colour on top
        for ((color, grow) in listOf(Pal.INK to u * 0.75f, Pal.CYAN to 0f)) {
            paint.color = color
            // top-left
            c.drawRect(l - grow, t0 - grow, l + len + grow, t0 + th + grow, paint)
            c.drawRect(l - grow, t0 - grow, l + th + grow, t0 + len + grow, paint)
            // top-right
            c.drawRect(r - len - grow, t0 - grow, r + grow, t0 + th + grow, paint)
            c.drawRect(r - th - grow, t0 - grow, r + grow, t0 + len + grow, paint)
            // bottom-left
            c.drawRect(l - grow, b - th - grow, l + len + grow, b + grow, paint)
            c.drawRect(l - grow, b - len - grow, l + th + grow, b + grow, paint)
            // bottom-right
            c.drawRect(r - len - grow, b - th - grow, r + grow, b + grow, paint)
            c.drawRect(r - th - grow, b - len - grow, r + grow, b + grow, paint)
        }
        // soft cyan tint inside
        paint.color = (0x1A52E3FF)
        c.drawRect(l + th, t0 + th, r - th, b - th, paint)
    }

    /** Pixel diamond ring with a solid core. */
    private fun target(c: Canvas, cx: Float, cy: Float, u: Float) {
        val ring = listOf(
            "....kkkk....",
            "..kkggggkk..",
            ".kggkkkkggk.",
            ".kgk....kgk.",
            "kgk..kk..kgk",
            "kgk.kggk.kgk",
            "kgk.kggk.kgk",
            "kgk..kk..kgk",
            ".kgk....kgk.",
            ".kggkkkkggk.",
            "..kkggggkk..",
            "....kkkk....",
        )
        val n = ring.size
        val ox = cx - n * u / 2; val oy = cy - n * u / 2
        for ((y, row) in ring.withIndex()) for ((x, ch) in row.withIndex()) {
            paint.color = when (ch) { 'k' -> Pal.INK; 'g' -> Pal.GOLD; else -> continue }
            c.drawRect(ox + x * u, oy + y * u, ox + (x + 1) * u, oy + (y + 1) * u, paint)
        }
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
    private val frame = Paint().apply { style = Paint.Style.STROKE; color = Pal.GOLD; strokeWidth = 8f }
    private val frameInk = Paint().apply { style = Paint.Style.STROKE; color = Pal.INK; strokeWidth = 16f }
    private val grid = Paint().apply { style = Paint.Style.STROKE; color = Color.argb(190, 255, 200, 69); strokeWidth = 3f }
    private val handle = Paint().apply { color = Pal.GOLD }
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
