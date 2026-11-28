package com.dizzle.chessai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

/** Full-screen theme background (animated for themes that want it). */
class ThemeBackgroundView(ctx: Context, private val theme: UiTheme) : View(ctx) {
    private var phase = 0f
    private val anim = if (theme.animatedBackground) ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 24_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    } else null

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim?.start() }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) = theme.drawBackground(canvas, width.toFloat(), height.toFloat(), phase, resources.displayMetrics.density)
}

/** On/off switch drawn by the theme. */
@SuppressLint("ViewConstructor")
class ThemedToggle(ctx: Context, private val theme: UiTheme, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = initial
        private set

    init {
        isClickable = true
        contentDescription = "Schalter"
        setOnClickListener { checked = !checked; invalidate(); onChange(checked) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(62), context.dpi(32))
    override fun onDraw(canvas: Canvas) = theme.drawToggle(canvas, width.toFloat(), height.toFloat(), checked, resources.displayMetrics.density)
}

/** Slider (value 0..max) drawn by the theme. */
@SuppressLint("ViewConstructor")
class ThemedSlider(ctx: Context, private val theme: UiTheme, private val max: Int, initial: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    private var value = initial.coerceIn(0, max)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(34))
    override fun onDraw(canvas: Canvas) =
        theme.drawSlider(canvas, width.toFloat(), height.toFloat(), value.toFloat() / max, resources.displayMetrics.density)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val v = ((e.x / width) * max).roundToInt().coerceIn(0, max)
            if (v != value) { value = v; invalidate(); onChange(v) }
        }
        return true
    }
}

/** Floating overlay button; asks the current theme how to look. */
class ThemedFab(ctx: Context) : View(ctx) {
    var state = FabState.MANUAL
        set(v) { field = v; invalidate() }
    private var frame = 0

    override fun onDraw(canvas: Canvas) {
        Themes.current(context).drawFab(canvas, width.toFloat(), height.toFloat(), state, frame, resources.displayMetrics.density)
        if (state == FabState.BUSY) { frame++; postInvalidateDelayed(180) }
    }
}

/** App logo in the theme's style. */
@SuppressLint("ViewConstructor")
class ThemeLogo(ctx: Context, private val theme: UiTheme) : View(ctx) {
    override fun onDraw(canvas: Canvas) = theme.drawLogo(canvas, width.toFloat(), height.toFloat(), resources.displayMetrics.density)
}

/** Small live preview card of a theme: background, a panel, a mini board with the move marker. */
@SuppressLint("ViewConstructor")
class ThemePreview(ctx: Context, private val theme: UiTheme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.62f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        c.save()
        c.clipRect(0f, 0f, w, h)
        theme.drawBackground(c, w, h, 0.2f, d)
        // mini board (4x4 corner of a board, scaled so a full 8x8 marker fits)
        val bs = h * 0.78f; val bx = w - bs - h * 0.11f; val by = h * 0.11f
        val q = bs / 4
        for (y in 0 until 4) for (x in 0 until 4) {
            p.color = if ((x + y) % 2 == 0) 0xFFEEEED2.toInt() else 0xFF769656.toInt()
            c.drawRect(bx + x * q, by + y * q, bx + (x + 1) * q, by + (y + 1) * q, p)
        }
        // pawn glyph on the from square
        p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; p.textSize = q * 0.8f
        p.setShadowLayer(2f, 0f, 1f, 0x88000000.toInt())
        c.drawText("♙", bx + 1.5f * q, by + 3.5f * q + q * 0.28f, p); p.clearShadowLayer()
        c.save()
        c.translate(bx, by); c.clipRect(0f, 0f, bs, bs)
        // marker: from (row 3, col 1) to (row 1, col 1) in a 4x4 grid == board of size bs*2
        theme.drawMarker(c, bs * 2, 3 * 8 + 1, 1 * 8 + 1, 1f)
        c.restore()
        // panel + text lines
        val panel = theme.panel(context)
        panel.setBounds((h * 0.1f).toInt(), (h * 0.14f).toInt(), (bx - h * 0.1f).toInt(), (h * 0.86f).toInt())
        panel.draw(c)
        val tl = h * 0.1f + 14 * d
        p.color = theme.text; p.textAlign = Paint.Align.LEFT; p.typeface = theme.titleFont(context); p.textSize = 11 * d
        c.drawText(theme.t("Chess AI"), tl, h * 0.34f, p)
        p.color = theme.muted
        val lineW = bx - h * 0.1f - tl - 18 * d
        c.drawRoundRect(RectF(tl, h * 0.46f, tl + lineW, h * 0.46f + 4 * d), 2 * d, 2 * d, p)
        c.drawRoundRect(RectF(tl, h * 0.55f, tl + lineW * 0.7f, h * 0.55f + 4 * d), 2 * d, 2 * d, p)
        val btn = theme.button(context)
        btn.setBounds(tl.toInt(), (h * 0.64f).toInt(), (tl + lineW).toInt(), (h * 0.78f).toInt())
        btn.draw(c)
        c.restore()
    }
}
