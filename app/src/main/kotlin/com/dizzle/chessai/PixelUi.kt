package com.dizzle.chessai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel-art chess palette. */
object Pal {
    const val BG = 0xFF14161B.toInt()
    const val PANEL = 0xFF1F232B.toInt()
    const val PANEL_HI = 0xFF2B303B.toInt()
    const val INK = 0xFF0A0B0E.toInt()
    const val CREAM = 0xFFF2E8CF.toInt()
    const val CREAM_SHADE = 0xFFC9BFA4.toInt()
    const val MOSS = 0xFF7BA05B.toInt()
    const val MOSS_DARK = 0xFF4E6B3A.toInt()
    const val GOLD = 0xFFFFC845.toInt()
    const val CYAN = 0xFF52E3FF.toInt()
    const val CORAL = 0xFFFF6B5E.toInt()
    const val MUTED = 0xFF8E97A8.toInt()
    const val SLATE = 0xFF3A404D.toInt()
}

object PixelFonts {
    @Volatile private var title: Typeface? = null
    @Volatile private var body: Typeface? = null

    /** "Press Start 2P" – chunky 8-bit capitals for titles and buttons. */
    fun title(ctx: Context): Typeface = title ?: runCatching { ctx.resources.getFont(R.font.pixel_title) }
        .getOrDefault(Typeface.MONOSPACE).also { title = it }

    /** "VT323" – readable terminal pixel font for body text. */
    fun body(ctx: Context): Typeface = body ?: runCatching { ctx.resources.getFont(R.font.pixel_body) }
        .getOrDefault(Typeface.MONOSPACE).also { body = it }
}

fun Context.dpf(v: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

fun Context.dpi(v: Number): Int = dpf(v).roundToInt()

/** Draws a rectangle with "stepped" pixel corners. */
internal fun Canvas.steppedRect(l: Float, t: Float, r: Float, b: Float, step: Float, paint: Paint) {
    if (r - l <= 2 * step || b - t <= 2 * step) { drawRect(l, t, r, b, paint); return }
    drawRect(l + step, t, r - step, b, paint)
    drawRect(l, t + step, r, b - step, paint)
}

/**
 * Pixel panel: 1-pixel border, stepped corners and a hard drop shadow (no blur). Pressed state
 * pushes the box into its shadow like an old-school button.
 */
class PixelBox(
    var fill: Int,
    var border: Int = Pal.INK,
    private val px: Float,
    var shadow: Int = Pal.INK,
    private val shadowSteps: Int = 1,
) : Drawable() {
    private val paint = Paint()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = shadowSteps * px
        val off = if (pressed) s else 0f
        val l = b.left + off
        val t = b.top + off
        val r = b.right - s + off
        val bt = b.bottom - s + off
        if (!pressed && s > 0) {
            paint.color = shadow
            canvas.steppedRect(l + s, t + s, r + s, bt + s, px, paint)
        }
        paint.color = border
        canvas.steppedRect(l, t, r, bt, px, paint)
        paint.color = fill
        canvas.steppedRect(l + px, t + px, r - px, bt - px, px, paint)
        // top highlight line for a bit of depth
        paint.color = lighten(fill)
        canvas.drawRect(l + 2 * px, t + px, r - 2 * px, t + 2 * px, paint)
    }

    private fun lighten(c: Int): Int {
        val a = c ushr 24
        val r = min(255, ((c shr 16) and 255) + 22)
        val g = min(255, ((c shr 8) and 255) + 22)
        val bl = min(255, (c and 255) + 22)
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p != pressed) { pressed = p; invalidateSelf(); return true }
        return false
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A sprite made of characters; each char maps to a color ('.' = transparent). */
class PixelSprite(private val rows: List<String>, private val colors: Map<Char, Int>) : Drawable() {
    private val paint = Paint()
    val cols = rows.maxOf { it.length }
    val lines = rows.size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cell = max(1f, floor(min(b.width().toFloat() / cols, b.height().toFloat() / lines)))
        val ox = b.left + (b.width() - cell * cols) / 2
        val oy = b.top + (b.height() - cell * lines) / 2
        for ((y, row) in rows.withIndex()) {
            var x = 0
            while (x < row.length) {
                val ch = row[x]
                val color = colors[ch]
                if (ch == '.' || color == null) { x++; continue }
                var e = x
                while (e < row.length && row[e] == ch) e++
                paint.color = color
                canvas.drawRect(ox + x * cell, oy + y * cell, ox + e * cell, oy + (y + 1) * cell, paint)
                x = e
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

object Sprites {
    val KNIGHT = listOf(
        "................",
        "......kk.kk.....",
        ".....kwwkwwk....",
        "....kwwwwwwwk...",
        "...kwwkwwwwwsk..",
        "..kwwwwwwwwwsk..",
        ".kwwwwwwwwwwwsk.",
        ".kwwwwkwwwwwwsk.",
        "..kkkk.kwwwwwsk.",
        ".......kwwwwssk.",
        "......kwwwwwssk.",
        ".....kwwwwwwssk.",
        "....kkkkkkkkkkkk",
        "....kwwwwwwwwssk",
        "....kkkkkkkkkkkk",
        "................",
    )
    val PAWN = listOf(
        "....kkkk....",
        "...kwwwwk...",
        "...kwwwsk...",
        "....kwsk....",
        "...kwwwsk...",
        "....kwsk....",
        "...kwwwsk...",
        "..kwwwwwsk..",
        ".kkkkkkkkkk.",
        ".kwwwwwwwsk.",
        ".kkkkkkkkkk.",
    )
    val HALF_PAWN = listOf(
        "....kkkk....",
        "...kwwddk...",
        "...kwwddk...",
        "....kwdk....",
        "...kwwddk...",
        "....kwdk....",
        "...kwwddk...",
        "..kwwwdddk..",
        ".kkkkkkkkkk.",
        ".kwwwwddddk.",
        ".kkkkkkkkkk.",
    )

    fun knight() = PixelSprite(KNIGHT, mapOf('k' to Pal.INK, 'w' to Pal.CREAM, 's' to Pal.CREAM_SHADE))
    fun whitePawn() = PixelSprite(PAWN, mapOf('k' to Pal.INK, 'w' to Pal.CREAM, 's' to Pal.CREAM_SHADE))
    fun blackPawn() = PixelSprite(PAWN, mapOf('k' to Pal.INK, 'w' to 0xFF4A5060.toInt(), 's' to 0xFF2E333D.toInt()))
    fun autoPawn() = PixelSprite(HALF_PAWN, mapOf('k' to Pal.INK, 'w' to Pal.CREAM, 'd' to 0xFF4A5060.toInt()))
}

/** Row of alternating board squares – used as a divider. */
class CheckerStrip(ctx: Context, private val cells: Int = 24) : View(ctx) {
    private val paint = Paint()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, max(1, width / cells / 2))
    }
    override fun onDraw(canvas: Canvas) {
        val cw = width.toFloat() / cells
        for (i in 0 until cells) {
            paint.color = if (i % 2 == 0) Pal.MOSS else Pal.CREAM
            canvas.drawRect(i * cw, 0f, (i + 1) * cw, height.toFloat(), paint)
        }
    }
}

/** Chunky on/off switch. */
@SuppressLint("ViewConstructor")
class PixelToggle(ctx: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = initial
        private set
    private val paint = Paint()
    private val u = ctx.dpf(2.5f)

    init {
        isClickable = true
        contentDescription = "Schalter"
        setOnClickListener { checked = !checked; invalidate(); onChange(checked) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(58), context.dpi(30))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Pal.INK
        canvas.steppedRect(u, u, w, h, u, paint)                       // shadow
        canvas.steppedRect(0f, 0f, w - u, h - u, u, paint)             // border
        paint.color = if (checked) Pal.MOSS else Pal.SLATE
        canvas.steppedRect(u, u, w - 2 * u, h - 2 * u, u, paint)
        // knob
        val ks = h - 5 * u
        val kx = if (checked) w - 3 * u - ks else 2 * u
        paint.color = Pal.INK
        canvas.drawRect(kx, 2 * u, kx + ks, 2 * u + ks, paint)
        paint.color = if (checked) Pal.CREAM else Pal.MUTED
        canvas.drawRect(kx + u, 3 * u, kx + ks - u, 1 * u + ks, paint)
    }
}

/** Segmented pixel slider (value 0..max). */
@SuppressLint("ViewConstructor")
class PixelSlider(ctx: Context, private val max: Int, initial: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    var value = initial.coerceIn(0, max)
        private set
    private val paint = Paint()
    private val u = ctx.dpf(2.5f)
    private val segments = 24

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(30))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Pal.INK
        canvas.steppedRect(u, u, w, h, u, paint)
        canvas.steppedRect(0f, 0f, w - u, h - u, u, paint)
        paint.color = Pal.PANEL_HI
        canvas.steppedRect(u, u, w - 2 * u, h - 2 * u, u, paint)
        val inner = w - 5 * u
        val sw = inner / segments
        val filled = ((value.toFloat() / max) * segments).roundToInt().coerceAtLeast(1)
        for (i in 0 until segments) {
            paint.color = when {
                i >= filled -> Pal.SLATE
                i == filled - 1 -> Pal.CREAM
                else -> Pal.GOLD
            }
            val x = 2 * u + i * sw
            canvas.drawRect(x + u * 0.4f, 2.5f * u, x + sw - u * 0.4f, h - 3.5f * u, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = ((e.x / width) * max).roundToInt().coerceIn(0, max)
                if (v != value) { value = v; invalidate(); onChange(v) }
            }
        }
        return true
    }
}

/** The floating overlay button: a pixel tile with the knight sprite and a state light. */
class PixelFab(ctx: Context) : View(ctx) {
    enum class State { MANUAL, SCANNING, BUSY }

    var state = State.MANUAL
        set(v) { field = v; invalidate() }
    private val knight = Sprites.knight()
    private val paint = Paint()
    private val u = ctx.dpf(2.5f)
    private var frame = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0x66000000
        canvas.steppedRect(u, u, w, h, 2 * u, paint)
        paint.color = Pal.INK
        canvas.steppedRect(0f, 0f, w - u, h - u, 2 * u, paint)
        paint.color = when (state) {
            State.SCANNING -> Pal.MOSS_DARK
            State.BUSY -> Pal.SLATE
            State.MANUAL -> Pal.PANEL_HI
        }
        canvas.steppedRect(u, u, w - 2 * u, h - 2 * u, 2 * u, paint)
        knight.setBounds((3 * u).toInt(), (3 * u).toInt(), (w - 4 * u).toInt(), (h - 4 * u).toInt())
        knight.draw(canvas)
        // state light (top-right) / busy dots (bottom)
        when (state) {
            State.SCANNING -> {
                paint.color = Pal.GOLD
                canvas.drawRect(w - 5 * u, 2 * u, w - 3 * u, 4 * u, paint)
            }
            State.BUSY -> {
                for (i in 0 until 3) {
                    paint.color = if (i == frame % 3) Pal.GOLD else Pal.CREAM
                    val x = w / 2 - 4 * u + i * 3 * u
                    canvas.drawRect(x, h - 5 * u, x + 2 * u, h - 3 * u, paint)
                }
                frame++
                postInvalidateDelayed(220)
            }
            State.MANUAL -> {}
        }
    }
}
