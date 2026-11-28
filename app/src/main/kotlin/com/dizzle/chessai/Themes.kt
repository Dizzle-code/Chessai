package com.dizzle.chessai

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class ThemeId(val title: String, val tagline: String) {
    PIXEL("Pixel", "8-Bit-Schach mit harten Pixel-Kanten"),
    VIBE_LOW("Soft Glass", "Vibe-Coded · Stufe 1 – ruhig & gläsern"),
    VIBE_MID("Neon Glass", "Vibe-Coded · Stufe 2 – Glas, Verläufe, Glow"),
    VIBE_HIGH("Hyper Aurora", "Vibe-Coded · Stufe 3 – Aurora, Hologramm, alles leuchtet"),
    TURNIER("Turnier", "Holz, Messing & klassische Analyse-Pfeile"),
    TERMINAL("Terminal", "Grüner Röhrenmonitor, Scanlines, > _"),
    SKIZZE("Skizze", "Notizbuch – von Hand gezeichnete Kreise & Pfeile"),
}

enum class FabState { MANUAL, SCANNING, BUSY }

/** Everything that changes with the theme – app screens and the overlay. */
abstract class UiTheme(val id: ThemeId) {
    abstract val bg: Int
    abstract val surface: Int
    abstract val text: Int
    abstract val muted: Int
    abstract val accent: Int
    abstract val onAccent: Int
    abstract val fromColor: Int
    abstract val toColor: Int
    abstract val danger: Int
    abstract val success: Int
    open val outline: Int get() = muted
    open val isLight = false
    open val upperCase = false

    // text sizes in sp
    open val h1 = 28f
    open val h2 = 18f
    open val label = 12f
    open val buttonSize = 17f
    open val tileSize = 13f
    open val body = 15f
    open val small = 13f

    abstract fun titleFont(ctx: Context): Typeface
    open fun bodyFont(ctx: Context): Typeface = titleFont(ctx)

    fun t(s: String) = if (upperCase) s.uppercase() else s

    open fun styleTitle(tv: TextView, size: Float, color: Int = text) {
        tv.typeface = titleFont(tv.context); tv.textSize = size; tv.setTextColor(color); tv.includeFontPadding = false
    }
    open fun styleBody(tv: TextView, size: Float = body, color: Int = text) {
        tv.typeface = bodyFont(tv.context); tv.textSize = size; tv.setTextColor(color)
    }

    /** Full-screen background; phase 0..1 for animated themes. */
    abstract fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float)
    open val animatedBackground = false

    abstract fun panel(ctx: Context, overlay: Boolean = false): Drawable
    abstract fun button(ctx: Context, color: Int = accent): Drawable
    abstract fun tile(ctx: Context, selected: Boolean): Drawable
    open fun tileTextColor(selected: Boolean) = if (selected) onAccent else muted
    abstract fun field(ctx: Context): Drawable
    open fun panelPadding(ctx: Context) = ctx.dpi(18)

    abstract fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float)
    abstract fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float)
    abstract fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float)
    /** Move marker on a board of [size] px, pop-in progress [t] 0..1 (static at 1). */
    abstract fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float)
    abstract fun drawLogo(c: Canvas, w: Float, h: Float, d: Float)

    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    protected fun center(v: Int, q: Float) = floatArrayOf((v % 8 + 0.5f) * q, (v / 8 + 0.5f) * q)
}

object Themes {
    @Volatile private var cached: UiTheme? = null

    fun of(id: ThemeId): UiTheme = when (id) {
        ThemeId.PIXEL -> PixelTheme
        ThemeId.VIBE_LOW -> VibeTheme(1)
        ThemeId.VIBE_MID -> VibeTheme(2)
        ThemeId.VIBE_HIGH -> VibeTheme(3)
        ThemeId.TURNIER -> TurnierTheme
        ThemeId.TERMINAL -> TerminalTheme
        ThemeId.SKIZZE -> SkizzeTheme
    }

    fun current(ctx: Context): UiTheme {
        val id = Prefs(ctx).themeId
        cached?.let { if (it.id == id) return it }
        return of(id).also { cached = it }
    }

    fun invalidate() { cached = null }
}

// ---------------------------------------------------------------------------- helpers

internal fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

internal fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

internal fun pressable(normal: Drawable, pressed: Drawable) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), pressed)
    addState(intArrayOf(), normal)
}

/** Generic drawable that delegates to a lambda (used for custom theme shapes). */
internal class LambdaDrawable(private val stateful: Boolean = false, private val block: (Canvas, RectF, Boolean) -> Unit) : Drawable() {
    private var pressed = false
    override fun draw(canvas: Canvas) = block(canvas, RectF(bounds), pressed)
    override fun isStateful() = stateful
    override fun onStateChange(state: IntArray): Boolean {
        val pr = state.contains(android.R.attr.state_pressed)
        if (pr != pressed) { pressed = pr; invalidateSelf(); return true }
        return false
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun glyph(c: Canvas, paint: Paint, s: String, cx: Float, cy: Float, size: Float, color: Int, tf: Typeface = Typeface.DEFAULT_BOLD) {
    paint.typeface = tf; paint.textSize = size; paint.color = color; paint.textAlign = Paint.Align.CENTER
    paint.shader = null
    val fm = paint.fontMetrics
    c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, paint)
}

private fun arrowHead(path: Path, ax: Float, ay: Float, bx: Float, by: Float, len: Float, width: Float) {
    val ang = kotlin.math.atan2(by - ay, bx - ax)
    path.moveTo(bx, by)
    path.lineTo(bx - len * cos(ang) + width * sin(ang), by - len * sin(ang) - width * cos(ang))
    path.lineTo(bx - len * cos(ang) - width * sin(ang), by - len * sin(ang) + width * cos(ang))
    path.close()
}

// ---------------------------------------------------------------------------- PIXEL

object PixelTheme : UiTheme(ThemeId.PIXEL) {
    override val bg = Pal.BG
    override val surface = Pal.PANEL
    override val text = Pal.CREAM
    override val muted = Pal.MUTED
    override val accent = Pal.MOSS
    override val onAccent = Pal.INK
    override val fromColor = Pal.CYAN
    override val toColor = Pal.GOLD
    override val danger = Pal.CORAL
    override val success = Pal.MOSS
    override val outline = Pal.SLATE
    override val upperCase = true
    override val h1 = 18f; override val h2 = 12f; override val label = 9f
    override val buttonSize = 14f; override val tileSize = 9f
    override val body = 19f; override val small = 17f

    override fun titleFont(ctx: Context) = PixelFonts.title(ctx)
    override fun bodyFont(ctx: Context) = PixelFonts.body(ctx)

    override fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float) {
        c.drawColor(Pal.BG)
        // faint checker in the corner for flavour
        p.color = alpha(Pal.MOSS, 18)
        val q = 18 * density
        for (y in 0 until 6) for (x in 0 until 6) if ((x + y) % 2 == 0) c.drawRect(w - (x + 1) * q, h - (y + 1) * q, w - x * q, h - y * q, p)
    }

    override fun panel(ctx: Context, overlay: Boolean) = PixelBox(Pal.PANEL, Pal.INK, ctx.dpf(3f), shadowSteps = 2)
    override fun button(ctx: Context, color: Int) = PixelBox(color, Pal.INK, ctx.dpf(3f), shadowSteps = 2)
    override fun tile(ctx: Context, selected: Boolean) =
        PixelBox(if (selected) Pal.MOSS else Pal.PANEL_HI, if (selected) Pal.GOLD else Pal.INK, ctx.dpf(2.5f), shadowSteps = 2)
    override fun field(ctx: Context) = PixelBox(Pal.BG, Pal.SLATE, ctx.dpf(2.5f), shadowSteps = 0, shadow = 0)

    override fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float) {
        val u = 2.5f * d
        p.color = Pal.INK
        c.steppedRect(u, u, w, h, u, p); c.steppedRect(0f, 0f, w - u, h - u, u, p)
        p.color = if (on) Pal.MOSS else Pal.SLATE
        c.steppedRect(u, u, w - 2 * u, h - 2 * u, u, p)
        val ks = h - 5 * u
        val kx = if (on) w - 3 * u - ks else 2 * u
        p.color = Pal.INK; c.drawRect(kx, 2 * u, kx + ks, 2 * u + ks, p)
        p.color = if (on) Pal.CREAM else Pal.MUTED; c.drawRect(kx + u, 3 * u, kx + ks - u, u + ks, p)
    }

    override fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float) {
        val u = 2.5f * d
        p.color = Pal.INK
        c.steppedRect(u, u, w, h, u, p); c.steppedRect(0f, 0f, w - u, h - u, u, p)
        p.color = Pal.PANEL_HI; c.steppedRect(u, u, w - 2 * u, h - 2 * u, u, p)
        val n = 24; val sw = (w - 5 * u) / n
        val filled = (f * n).toInt().coerceIn(1, n)
        for (i in 0 until n) {
            p.color = when { i >= filled -> Pal.SLATE; i == filled - 1 -> Pal.CREAM; else -> Pal.GOLD }
            val x = 2 * u + i * sw
            c.drawRect(x + u * 0.4f, 2.5f * u, x + sw - u * 0.4f, h - 3.5f * u, p)
        }
    }

    private val knight = Sprites.knight()

    override fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float) {
        val u = 2.5f * d
        p.color = 0x66000000; c.steppedRect(u, u, w, h, 2 * u, p)
        p.color = Pal.INK; c.steppedRect(0f, 0f, w - u, h - u, 2 * u, p)
        p.color = when (state) { FabState.SCANNING -> Pal.MOSS_DARK; FabState.BUSY -> Pal.SLATE; FabState.MANUAL -> Pal.PANEL_HI }
        c.steppedRect(u, u, w - 2 * u, h - 2 * u, 2 * u, p)
        knight.setBounds((3 * u).toInt(), (3 * u).toInt(), (w - 4 * u).toInt(), (h - 4 * u).toInt())
        knight.draw(c)
        if (state == FabState.SCANNING) { p.color = Pal.GOLD; c.drawRect(w - 5 * u, 2 * u, w - 3 * u, 4 * u, p) }
        if (state == FabState.BUSY) for (i in 0 until 3) {
            p.color = if (i == frame % 3) Pal.GOLD else Pal.CREAM
            val x = w / 2 - 4 * u + i * 3 * u
            c.drawRect(x, h - 5 * u, x + 2 * u, h - 3 * u, p)
        }
    }

    override fun drawLogo(c: Canvas, w: Float, h: Float, d: Float) {
        knight.setBounds(0, 0, w.toInt(), h.toInt()); knight.draw(c)
    }

    private val ring = listOf(
        "....kkkk....", "..kkggggkk..", ".kggkkkkggk.", ".kgk....kgk.", "kgk..kk..kgk", "kgk.kggk.kgk",
        "kgk.kggk.kgk", "kgk..kk..kgk", ".kgk....kgk.", ".kggkkkkggk.", "..kkggggkk..", "....kkkk....",
    )

    override fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float) {
        val q = size / 8f; val u = q / 16f
        val (ax, ay) = center(from, q).let { it[0] to it[1] }
        val (bx, by) = center(to, q).let { it[0] to it[1] }
        val dist = hypot(bx - ax, by - ay); val n = (dist / (q * 0.3f)).toInt()
        for (i in 1 until n) {
            val f = i.toFloat() / n; if (f > t) break
            val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
            if (hypot(x - ax, y - ay) < q * 0.62f || hypot(x - bx, y - by) < q * 0.5f) continue
            val sx = (x / u).toInt() * u; val sy = (y / u).toInt() * u
            p.color = Pal.INK; c.drawRect(sx - u, sy - u, sx + 2 * u, sy + 2 * u, p)
            p.color = Pal.GOLD; c.drawRect(sx, sy, sx + u, sy + u, p)
        }
        val fx = (from % 8) * q; val fy = (from / 8) * q
        val inset = (1.5f + (1f - t) * -3f) * u
        val l = fx + inset; val tp = fy + inset; val r = fx + q - inset; val b = fy + q - inset
        val len = 4 * u; val th = 1.5f * u
        for ((color, g) in listOf(Pal.INK to u * 0.75f, Pal.CYAN to 0f)) {
            p.color = color
            c.drawRect(l - g, tp - g, l + len + g, tp + th + g, p); c.drawRect(l - g, tp - g, l + th + g, tp + len + g, p)
            c.drawRect(r - len - g, tp - g, r + g, tp + th + g, p); c.drawRect(r - th - g, tp - g, r + g, tp + len + g, p)
            c.drawRect(l - g, b - th - g, l + len + g, b + g, p); c.drawRect(l - g, b - len - g, l + th + g, b + g, p)
            c.drawRect(r - len - g, b - th - g, r + g, b + g, p); c.drawRect(r - th - g, b - len - g, r + g, b + g, p)
        }
        p.color = 0x1A52E3FF; c.drawRect(l + th, tp + th, r - th, b - th, p)
        val uu = u * (0.7f + 0.45f * t); val nn = ring.size
        val ox = bx - nn * uu / 2; val oy = by - nn * uu / 2
        for ((y, row) in ring.withIndex()) for ((x, ch) in row.withIndex()) {
            p.color = when (ch) { 'k' -> Pal.INK; 'g' -> Pal.GOLD; else -> continue }
            c.drawRect(ox + x * uu, oy + y * uu, ox + (x + 1) * uu, oy + (y + 1) * uu, p)
        }
    }
}

// ---------------------------------------------------------------------------- VIBE (3 levels)

class VibeTheme(private val level: Int) : UiTheme(
    when (level) { 1 -> ThemeId.VIBE_LOW; 2 -> ThemeId.VIBE_MID; else -> ThemeId.VIBE_HIGH },
) {
    private val violet = 0xFF8B7CFF.toInt()
    private val cyan = 0xFF22D3EE.toInt()
    private val pink = 0xFFF472B6.toInt()
    private val lime = 0xFFA3E635.toInt()

    override val bg = when (level) { 1 -> 0xFF101020.toInt(); 2 -> 0xFF0A0A1C.toInt(); else -> 0xFF05050F.toInt() }
    override val surface = 0x1FFFFFFF
    override val text = 0xFFF5F3FF.toInt()
    override val muted = 0xFFA7A3C2.toInt()
    override val accent = if (level == 1) violet else 0xFF9F7BFF.toInt()
    override val onAccent = Color.WHITE
    override val fromColor = cyan
    override val toColor = if (level == 1) violet else pink
    override val danger = 0xFFFF5C8A.toInt()
    override val success = 0xFF34D399.toInt()
    override val outline = 0x33FFFFFF
    override val animatedBackground = level == 3

    override fun titleFont(ctx: Context): Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    override fun bodyFont(ctx: Context): Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private fun gradColors() = when (level) {
        1 -> intArrayOf(violet, 0xFF6D5DF6.toInt())
        2 -> intArrayOf(violet, cyan)
        else -> intArrayOf(pink, violet, cyan)
    }

    override fun styleTitle(tv: TextView, size: Float, color: Int) {
        super.styleTitle(tv, size, color)
        tv.letterSpacing = -0.01f
        if (level == 3 && size >= h2 && color == text) {
            tv.post {
                val w = tv.paint.measureText(tv.text.toString())
                tv.paint.shader = LinearGradient(0f, 0f, max(1f, w), 0f, intArrayOf(0xFFFFE4F5.toInt(), 0xFFC4B5FD.toInt(), 0xFF99F6E4.toInt()), null, Shader.TileMode.CLAMP)
                tv.invalidate()
            }
        }
    }

    override fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float) {
        c.drawColor(bg)
        val blobs: List<Triple<Float, Float, Int>> = when (level) {
            1 -> listOf(Triple(0.1f, 0.05f, alpha(violet, 70)), Triple(0.95f, 0.9f, alpha(0xFF4F46E5.toInt(), 50)))
            2 -> listOf(Triple(0.05f, 0.08f, alpha(violet, 120)), Triple(1.0f, 0.45f, alpha(cyan, 80)), Triple(0.2f, 1.0f, alpha(pink, 80)))
            else -> listOf(Triple(0.1f, 0.1f, alpha(pink, 120)), Triple(0.9f, 0.3f, alpha(cyan, 110)),
                Triple(0.3f, 0.75f, alpha(violet, 140)), Triple(0.9f, 1.0f, alpha(lime, 50)))
        }
        for ((i, b) in blobs.withIndex()) {
            val drift = if (level == 3) 0.06f else 0f
            val ang = (phase * 2 * Math.PI + i * 1.7).toFloat()
            val x = (b.first + drift * cos(ang)) * w
            val y = (b.second + drift * sin(ang)) * h
            val r = max(w, h) * (if (level == 1) 0.55f else 0.6f)
            p.shader = RadialGradient(x, y, r, b.third, alpha(b.third, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
        p.shader = null
        if (level == 3) {
            // faint perspective grid at the bottom – synthwave floor
            p.color = 0x14FFFFFF; p.strokeWidth = density
            val horizon = h * 0.72f
            for (i in 0..10) { val y = horizon + (h - horizon) * (i / 10f) * (i / 10f); c.drawLine(0f, y, w, y, p) }
            for (i in -8..8) c.drawLine(w / 2 + i * w * 0.04f, horizon, w / 2 + i * w * 0.25f, h, p)
        }
    }

    private fun glass(ctx: Context, radius: Float, overlay: Boolean, pressed: Boolean = false): Drawable {
        val d = ctx.dpf(1f)
        return LambdaDrawable { c, r, _ ->
            val rr = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, rr.top, 0f, rr.bottom,
                if (overlay) intArrayOf(alpha(0xFF1E1B3A.toInt(), 235), alpha(0xFF12122A.toInt(), 235))
                else intArrayOf(if (pressed) 0x30FFFFFF else 0x24FFFFFF, 0x0DFFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rr, radius, radius, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = if (level == 3) 1.6f * d else d
            p.shader = if (level == 1) null else LinearGradient(rr.left, rr.top, rr.right, rr.bottom,
                if (level == 2) intArrayOf(0x66FFFFFF, 0x1AFFFFFF, 0x4422D3EE) else intArrayOf(alpha(pink, 170), 0x22FFFFFF, alpha(cyan, 170)),
                null, Shader.TileMode.CLAMP)
            if (level == 1) p.color = 0x2EFFFFFF
            c.drawRoundRect(rr, radius, radius, p)
            p.shader = null; p.style = Paint.Style.FILL
            // top sheen
            p.shader = LinearGradient(0f, rr.top, 0f, rr.top + rr.height() * 0.4f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(rr.left + d, rr.top + d, rr.right - d, rr.top + rr.height() * 0.4f), radius, radius, p)
            p.shader = null
        }
    }

    override fun panel(ctx: Context, overlay: Boolean) = glass(ctx, ctx.dpf(18f + level * 3), overlay)

    override fun button(ctx: Context, color: Int): Drawable {
        val d = ctx.dpf(1f)
        return LambdaDrawable(stateful = true) { c, r, pressed ->
            val rr = RectF(r.left + 3 * d, r.top + 3 * d, r.right - 3 * d, r.bottom - 3 * d)
            val radius = ctx.dpf(16f + level * 4)
            val cols = if (color == accent) gradColors() else intArrayOf(color, color)
            if (level >= 2) {
                p.shader = null; p.color = alpha(cols[0], if (pressed) 40 else 90)
                p.setShadowLayer(12 * d, 0f, 4 * d, alpha(cols.last(), 150))
                c.drawRoundRect(rr, radius, radius, p)
                p.clearShadowLayer()
            }
            p.shader = LinearGradient(rr.left, rr.top, rr.right, rr.bottom, cols, null, Shader.TileMode.CLAMP)
            p.alpha = if (pressed) 190 else 255
            c.drawRoundRect(rr, radius, radius, p)
            p.shader = LinearGradient(0f, rr.top, 0f, rr.centerY(), 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(rr.left, rr.top, rr.right, rr.centerY()), radius, radius, p)
            p.shader = null; p.alpha = 255
        }
    }

    override fun tile(ctx: Context, selected: Boolean): Drawable =
        if (selected) button(ctx) else glass(ctx, ctx.dpf(14f + level * 2), false)

    override fun tileTextColor(selected: Boolean) = if (selected) Color.WHITE else muted
    override fun field(ctx: Context) = rounded(0x14FFFFFF, ctx.dpf(14f), 0x33FFFFFF, ctx.dpi(1))

    override fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float) {
        val r = RectF(2 * d, 2 * d, w - 2 * d, h - 2 * d)
        if (on) {
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, gradColors(), null, Shader.TileMode.CLAMP)
            if (level >= 2) p.setShadowLayer(8 * d, 0f, 0f, alpha(gradColors().last(), 160))
        } else p.color = 0x26FFFFFF
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.clearShadowLayer(); p.shader = null
        val kr = r.height() / 2 - 3 * d
        val kx = if (on) r.right - kr - 3 * d else r.left + kr + 3 * d
        p.color = Color.WHITE; p.setShadowLayer(4 * d, 0f, 1 * d, 0x55000000)
        c.drawCircle(kx, r.centerY(), kr, p); p.clearShadowLayer()
    }

    override fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float) {
        val cy = h / 2; val th = 6 * d
        p.color = 0x22FFFFFF
        c.drawRoundRect(RectF(8 * d, cy - th / 2, w - 8 * d, cy + th / 2), th, th, p)
        val x = 8 * d + (w - 16 * d) * f
        p.shader = LinearGradient(8 * d, 0f, w - 8 * d, 0f, gradColors(), null, Shader.TileMode.CLAMP)
        if (level >= 2) p.setShadowLayer(8 * d, 0f, 0f, alpha(gradColors().last(), 170))
        c.drawRoundRect(RectF(8 * d, cy - th / 2, x, cy + th / 2), th, th, p)
        p.clearShadowLayer(); p.shader = null
        p.color = Color.WHITE; p.setShadowLayer(6 * d, 0f, 1 * d, 0x66000000)
        c.drawCircle(x, cy, 9 * d, p); p.clearShadowLayer()
    }

    override fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) / 2 - 4 * d
        p.shader = null; p.color = alpha(0xFF14142E.toInt(), 225)
        p.setShadowLayer(10 * d, 0f, 3 * d, alpha(accent, 140))
        c.drawCircle(cx, cy, r, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d
        p.shader = SweepGradient(cx, cy, intArrayOf(pink, violet, cyan, pink), null)
        if (state == FabState.MANUAL && level == 1) { p.shader = null; p.color = 0x55FFFFFF }
        c.drawCircle(cx, cy, r, p)
        if (state == FabState.BUSY) {
            p.shader = null; p.color = Color.WHITE; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(cx - r + 4 * d, cy - r + 4 * d, cx + r - 4 * d, cy + r - 4 * d), (frame * 40f) % 360, 90f, false, p)
        }
        p.shader = null; p.style = Paint.Style.FILL
        glyph(c, p, "♞", cx, cy, r * 1.15f, Color.WHITE, Typeface.DEFAULT)
        if (state == FabState.SCANNING) { p.color = success; c.drawCircle(cx + r * 0.62f, cy - r * 0.62f, 4 * d, p) }
    }

    override fun drawLogo(c: Canvas, w: Float, h: Float, d: Float) = drawFab(c, w, h, FabState.SCANNING, 0, d)

    override fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float) {
        val q = size / 8f
        val a = center(from, q); val b = center(to, q)
        val s = 0.75f + 0.25f * t
        // trail
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = q * (if (level == 3) 0.07f else 0.055f)
        p.shader = LinearGradient(a[0], a[1], b[0], b[1], fromColor, toColor, Shader.TileMode.CLAMP)
        val ex = a[0] + (b[0] - a[0]) * t; val ey = a[1] + (b[1] - a[1]) * t
        if (level == 3) {
            p.pathEffect = DashPathEffect(floatArrayOf(q * 0.04f, q * 0.14f), 0f)
            p.strokeWidth = q * 0.09f
        }
        p.alpha = 210
        if (level >= 2) p.setShadowLayer(q * 0.12f, 0f, 0f, alpha(toColor, 200))
        c.drawLine(a[0], a[1], ex, ey, p)
        p.clearShadowLayer(); p.pathEffect = null; p.shader = null; p.alpha = 255

        // from: glowing rounded square ring
        val inset = q * 0.08f
        val fx = (from % 8) * q; val fy = (from / 8) * q
        val rr = RectF(fx + inset, fy + inset, fx + q - inset, fy + q - inset)
        val rad = q * 0.22f
        p.style = Paint.Style.FILL
        p.color = alpha(fromColor, if (level == 1) 40 else 55)
        c.drawRoundRect(rr, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = q * (0.035f + level * 0.01f)
        p.shader = if (level == 3) SweepGradient(rr.centerX(), rr.centerY(), intArrayOf(cyan, violet, pink, lime, cyan), null)
        else if (level == 2) LinearGradient(rr.left, rr.top, rr.right, rr.bottom, cyan, violet, Shader.TileMode.CLAMP) else null
        if (level == 1) p.color = fromColor
        p.setShadowLayer(q * 0.12f * level, 0f, 0f, alpha(fromColor, 220))
        c.drawRoundRect(rr, rad, rad, p)
        p.clearShadowLayer(); p.shader = null

        // to: orb + rings
        p.style = Paint.Style.FILL
        val or = q * 0.2f * s
        p.shader = RadialGradient(b[0], b[1], q * 0.5f, intArrayOf(alpha(toColor, 170), alpha(toColor, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(b[0], b[1], q * 0.5f, p)
        p.shader = RadialGradient(b[0] - or * 0.3f, b[1] - or * 0.3f, or, intArrayOf(Color.WHITE, toColor), null, Shader.TileMode.CLAMP)
        c.drawCircle(b[0], b[1], or, p); p.shader = null
        if (level >= 2) {
            p.style = Paint.Style.STROKE; p.strokeWidth = q * 0.025f; p.color = alpha(toColor, 200)
            c.drawCircle(b[0], b[1], q * 0.33f * s, p)
        }
        if (level == 3) {
            p.color = alpha(cyan, 160); p.strokeWidth = q * 0.015f
            c.drawCircle(b[0], b[1], q * 0.43f * s, p)
            // sparkles around the piece
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            for ((dx, dy) in listOf(-0.42f to -0.42f, 0.44f to -0.3f, -0.3f to 0.45f)) {
                val sx = a[0] + dx * q; val sy = a[1] + dy * q; val k = q * 0.05f * t
                val path = Path().apply { moveTo(sx, sy - k * 2); lineTo(sx + k * 0.5f, sy - k * 0.5f); lineTo(sx + k * 2, sy)
                    lineTo(sx + k * 0.5f, sy + k * 0.5f); lineTo(sx, sy + k * 2); lineTo(sx - k * 0.5f, sy + k * 0.5f)
                    lineTo(sx - k * 2, sy); lineTo(sx - k * 0.5f, sy - k * 0.5f); close() }
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
    }
}

// ---------------------------------------------------------------------------- TURNIER (wood & brass)

object TurnierTheme : UiTheme(ThemeId.TURNIER) {
    private val walnut = 0xFF2A1A10.toInt()
    private val wood = 0xFF3B2617.toInt()
    private val brass = 0xFFCDA349.toInt()
    override val bg = walnut
    override val surface = wood
    override val text = 0xFFF4E8D2.toInt()
    override val muted = 0xFFC2A887.toInt()
    override val accent = brass
    override val onAccent = 0xFF24160C.toInt()
    override val fromColor = brass
    override val toColor = 0xFF3FA34D.toInt()
    override val danger = 0xFFC8553D.toInt()
    override val success = 0xFF6BAF5B.toInt()
    override val outline = 0xFF6E4E33.toInt()
    override val h1 = 30f; override val h2 = 20f; override val body = 16f; override val small = 14f

    override fun titleFont(ctx: Context): Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    override fun bodyFont(ctx: Context): Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    override fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float) {
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF33200F.toInt(), 0xFF1A0F07.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // wood grain
        val rnd = Random(7)
        p.strokeWidth = density
        var y = 0f
        while (y < h) {
            p.color = alpha(0xFF000000.toInt(), 18 + rnd.nextInt(18))
            val path = Path(); path.moveTo(0f, y)
            var x = 0f
            while (x < w) { x += 40 * density; path.lineTo(x, y + (rnd.nextFloat() - 0.5f) * 6 * density) }
            p.style = Paint.Style.STROKE; c.drawPath(path, p)
            y += (6 + rnd.nextInt(10)) * density
        }
        p.style = Paint.Style.FILL
    }

    override fun panel(ctx: Context, overlay: Boolean): Drawable {
        val base = rounded(if (overlay) alpha(wood, 245) else wood, ctx.dpf(12f), brass, ctx.dpi(1.5f))
        val inner = rounded(0x00000000, ctx.dpf(9f), alpha(brass, 70), ctx.dpi(1))
        return LayerDrawable(arrayOf(base, inner)).apply { val i = ctx.dpi(4); setLayerInset(1, i, i, i, i) }
    }

    override fun button(ctx: Context, color: Int): Drawable {
        val n = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(color), color)).apply {
            cornerRadius = ctx.dpf(10f); setStroke(ctx.dpi(1.5f), 0xFF6B4E1F.toInt())
        }
        val pr = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, darken(color))).apply {
            cornerRadius = ctx.dpf(10f); setStroke(ctx.dpi(1.5f), 0xFF6B4E1F.toInt())
        }
        return pressable(n, pr)
    }

    private fun lighten(c: Int) = Color.rgb(min(255, Color.red(c) + 30), min(255, Color.green(c) + 30), min(255, Color.blue(c) + 30))
    private fun darken(c: Int) = Color.rgb(max(0, Color.red(c) - 30), max(0, Color.green(c) - 30), max(0, Color.blue(c) - 30))

    override fun tile(ctx: Context, selected: Boolean): Drawable =
        if (selected) button(ctx) else rounded(0xFF2F1E12.toInt(), ctx.dpf(10f), outline, ctx.dpi(1))
    override fun field(ctx: Context) = rounded(0xFF22150B.toInt(), ctx.dpf(8f), outline, ctx.dpi(1))

    override fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float) {
        val r = RectF(2 * d, 4 * d, w - 2 * d, h - 4 * d)
        p.color = if (on) 0xFF5C7A3A.toInt() else 0xFF22150B.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = brass
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); p.style = Paint.Style.FILL
        val kr = h / 2 - 3 * d
        val kx = if (on) w - kr - 3 * d else kr + 3 * d
        p.shader = RadialGradient(kx - kr * 0.3f, h / 2 - kr * 0.3f, kr * 1.3f, 0xFFF3D98A.toInt(), brass, Shader.TileMode.CLAMP)
        c.drawCircle(kx, h / 2, kr, p); p.shader = null
    }

    override fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float) {
        val cy = h / 2
        p.color = 0xFF22150B.toInt(); c.drawRoundRect(RectF(6 * d, cy - 3 * d, w - 6 * d, cy + 3 * d), 3 * d, 3 * d, p)
        val x = 6 * d + (w - 12 * d) * f
        p.color = brass; c.drawRoundRect(RectF(6 * d, cy - 3 * d, x, cy + 3 * d), 3 * d, 3 * d, p)
        p.shader = RadialGradient(x - 3 * d, cy - 3 * d, 12 * d, 0xFFF3D98A.toInt(), brass, Shader.TileMode.CLAMP)
        c.drawCircle(x, cy, 9 * d, p); p.shader = null
    }

    override fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) / 2 - 3 * d
        p.setShadowLayer(6 * d, 0f, 3 * d, 0x88000000.toInt())
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
            if (state == FabState.BUSY) 0xFFB9A98A.toInt() else 0xFFF6DE94.toInt(), 0xFF9C7A2B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.clearShadowLayer(); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0xFF6B4E1F.toInt()
        c.drawCircle(cx, cy, r - 4 * d, p); p.style = Paint.Style.FILL
        glyph(c, p, "♞", cx, cy, r * 1.15f, 0xFF2A1A10.toInt(), Typeface.SERIF)
        if (state == FabState.SCANNING) { p.color = success; c.drawCircle(cx + r * 0.65f, cy - r * 0.65f, 4 * d, p) }
        if (state == FabState.BUSY) { p.color = 0xFF2A1A10.toInt(); c.drawCircle(cx + r * 0.65f * cos(frame * 0.6f), cy + r * 0.65f * sin(frame * 0.6f), 3 * d, p) }
    }

    override fun drawLogo(c: Canvas, w: Float, h: Float, d: Float) = drawFab(c, w, h, FabState.MANUAL, 0, d)

    override fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float) {
        val q = size / 8f
        val a = center(from, q); val b = center(to, q)
        // classic analysis arrow (green, translucent)
        val ex = a[0] + (b[0] - a[0]) * t; val ey = a[1] + (b[1] - a[1]) * t
        val dist = hypot(b[0] - a[0], b[1] - a[1])
        val head = q * 0.36f
        val shorten = if (dist > 0) head / dist else 0f
        p.color = alpha(toColor, 200); p.style = Paint.Style.STROKE; p.strokeWidth = q * 0.16f; p.strokeCap = Paint.Cap.BUTT
        c.drawLine(a[0], a[1], ex - (b[0] - a[0]) * shorten * t, ey - (b[1] - a[1]) * shorten * t, p)
        p.style = Paint.Style.FILL
        if (t > 0.6f) { val path = Path(); arrowHead(path, a[0], a[1], ex, ey, head, q * 0.24f); c.drawPath(path, p) }
        // brass frame on the piece
        val fx = (from % 8) * q; val fy = (from / 8) * q
        p.style = Paint.Style.STROKE; p.color = brass; p.strokeWidth = q * 0.05f
        c.drawRect(fx + q * 0.05f, fy + q * 0.05f, fx + q * 0.95f, fy + q * 0.95f, p)
        p.strokeWidth = q * 0.015f; p.color = 0xFF6B4E1F.toInt()
        c.drawRect(fx + q * 0.11f, fy + q * 0.11f, fx + q * 0.89f, fy + q * 0.89f, p)
        p.style = Paint.Style.FILL
    }
}

// ---------------------------------------------------------------------------- TERMINAL (green CRT)

object TerminalTheme : UiTheme(ThemeId.TERMINAL) {
    private val green = 0xFF39FF7A.toInt()
    private val dim = 0xFF1C9E4A.toInt()
    override val bg = 0xFF030603.toInt()
    override val surface = 0xFF071007.toInt()
    override val text = green
    override val muted = dim
    override val accent = green
    override val onAccent = 0xFF021002.toInt()
    override val fromColor = green
    override val toColor = 0xFFB7FFCB.toInt()
    override val danger = 0xFFFF5252.toInt()
    override val success = green
    override val outline = dim
    override val upperCase = true
    override val h1 = 34f; override val h2 = 24f; override val label = 18f; override val buttonSize = 24f
    override val tileSize = 19f; override val body = 21f; override val small = 18f

    override fun titleFont(ctx: Context) = PixelFonts.body(ctx)
    override fun styleTitle(tv: TextView, size: Float, color: Int) {
        super.styleTitle(tv, size, color)
        tv.setShadowLayer(8f, 0f, 0f, alpha(green, 170))
    }
    override fun styleBody(tv: TextView, size: Float, color: Int) {
        super.styleBody(tv, size, color)
        tv.setShadowLayer(6f, 0f, 0f, alpha(green, 90))
    }

    override fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float) {
        c.drawColor(bg)
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.7f, 0x1A39FF7A, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        p.color = 0x22000000
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w, y + density, p); y += 3 * density }
    }

    override fun panel(ctx: Context, overlay: Boolean): Drawable {
        val d = ctx.dpf(1f)
        return LambdaDrawable { c, r, _ ->
            p.style = Paint.Style.FILL; p.color = if (overlay) 0xF2030803.toInt() else surface
            c.drawRect(r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = dim
            p.pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
            c.drawRect(r.left + d, r.top + d, r.right - d, r.bottom - d, p)
            p.pathEffect = null; p.style = Paint.Style.FILL
            // corner marks
            p.color = green
            val k = 8 * d
            for ((x, y) in listOf(r.left to r.top, r.right - k to r.top, r.left to r.bottom - k, r.right - k to r.bottom - k)) c.drawRect(x, y, x + k, y + k, p)
        }
    }

    override fun button(ctx: Context, color: Int): Drawable {
        val n = rounded(color, 0f)
        val pr = rounded(0xFF0B2A12.toInt(), 0f, color, ctx.dpi(2))
        return pressable(n, pr)
    }
    override fun tile(ctx: Context, selected: Boolean) = if (selected) rounded(green, 0f) else rounded(0x00000000, 0f, dim, ctx.dpi(1.5f))
    override fun field(ctx: Context) = rounded(0xFF000000.toInt(), 0f, dim, ctx.dpi(1))

    private fun mono(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        text2.typeface = Typeface.MONOSPACE; text2.textSize = size; text2.color = color; text2.textAlign = align
        val fm = text2.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text2)
    }

    override fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float) =
        mono(c, if (on) "[ON ]" else "[OFF]", w / 2, h / 2, h * 0.6f, if (on) green else dim, Paint.Align.CENTER)

    override fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float) {
        val n = 20; val filled = (f * n).toInt().coerceIn(1, n)
        mono(c, "[" + "#".repeat(filled) + "-".repeat(n - filled) + "]", w / 2, h / 2, h * 0.55f, green, Paint.Align.CENTER)
    }

    override fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float) {
        p.color = 0xF2020802.toInt(); c.drawRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = if (state == FabState.MANUAL) dim else green
        c.drawRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, p); p.style = Paint.Style.FILL
        val s = when (state) { FabState.BUSY -> listOf("|", "/", "-", "\\")[frame % 4]; else -> "♞" }
        glyph(c, p, s, w / 2, h / 2, h * 0.5f, green, Typeface.MONOSPACE)
        if (state == FabState.SCANNING && frame % 2 == 0) { p.color = green; c.drawRect(w - 12 * d, h - 10 * d, w - 6 * d, h - 7 * d, p) }
    }

    override fun drawLogo(c: Canvas, w: Float, h: Float, d: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = green
        c.drawRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, p); p.style = Paint.Style.FILL
        mono(c, ">_", w / 2, h / 2, h * 0.42f, green, Paint.Align.CENTER)
    }

    override fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float) {
        val q = size / 8f
        val a = center(from, q); val b = center(to, q)
        p.style = Paint.Style.STROKE; p.strokeWidth = q * 0.03f; p.color = green
        p.pathEffect = DashPathEffect(floatArrayOf(q * 0.08f, q * 0.06f), 0f)
        c.drawLine(a[0], a[1], a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, p)
        p.pathEffect = null
        // [ ] brackets around the piece
        val fx = (from % 8) * q; val fy = (from / 8) * q
        val m = q * 0.08f; val k = q * 0.22f
        p.strokeWidth = q * 0.045f
        for ((x, sx) in listOf(fx + m to 1f, fx + q - m to -1f)) {
            c.drawLine(x, fy + m, x, fy + q - m, p)
            c.drawLine(x, fy + m, x + sx * k, fy + m, p)
            c.drawLine(x, fy + q - m, x + sx * k, fy + q - m, p)
        }
        // crosshair on target
        val r = q * 0.28f * (0.6f + 0.4f * t)
        p.color = toColor; p.strokeWidth = q * 0.035f
        c.drawCircle(b[0], b[1], r, p)
        c.drawLine(b[0] - r * 1.4f, b[1], b[0] - r * 0.5f, b[1], p); c.drawLine(b[0] + r * 0.5f, b[1], b[0] + r * 1.4f, b[1], p)
        c.drawLine(b[0], b[1] - r * 1.4f, b[0], b[1] - r * 0.5f, p); c.drawLine(b[0], b[1] + r * 0.5f, b[0], b[1] + r * 1.4f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(b[0], b[1], q * 0.04f, p)
    }
}

// ---------------------------------------------------------------------------- SKIZZE (notebook)

object SkizzeTheme : UiTheme(ThemeId.SKIZZE) {
    private val paper = 0xFFFBF6E9.toInt()
    private val ink = 0xFF1F2A44.toInt()
    private val red = 0xFFE4572E.toInt()
    private val blue = 0xFF2F5DD0.toInt()
    private val marker = 0xFFFFDB4D.toInt()
    override val bg = paper
    override val surface = 0xFFFFFDF7.toInt()
    override val text = ink
    override val muted = 0xFF6B7280.toInt()
    override val accent = marker
    override val onAccent = ink
    override val fromColor = red
    override val toColor = blue
    override val danger = red
    override val success = 0xFF2E9E5B.toInt()
    override val outline = ink
    override val isLight = true
    override val h1 = 32f; override val h2 = 22f; override val label = 14f; override val buttonSize = 19f
    override val tileSize = 15f; override val body = 17f; override val small = 15f

    override fun titleFont(ctx: Context): Typeface = Typeface.create("casual", Typeface.BOLD)
    override fun bodyFont(ctx: Context): Typeface = Typeface.create("casual", Typeface.NORMAL)

    /** Slightly wobbly rectangle, like drawn with a pen (deterministic per size). */
    private fun wobblyRect(r: RectF, seed: Int, j: Float): Path {
        val rnd = Random(seed)
        val path = Path()
        fun jit() = (rnd.nextFloat() - 0.5f) * j
        val pts = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)
        path.moveTo(pts[0].first + jit(), pts[0].second + jit())
        for (i in 1..4) {
            val (x, y) = pts[i % 4]
            val (px, py) = pts[i - 1]
            path.quadTo((px + x) / 2 + jit() * 2, (py + y) / 2 + jit() * 2, x + jit(), y + jit())
        }
        return path
    }

    private fun wobblyCircle(cx: Float, cy: Float, r: Float, seed: Int, turns: Float = 1.15f): Path {
        val rnd = Random(seed)
        val path = Path()
        val steps = 40
        val start = rnd.nextFloat() * 6.28f
        for (i in 0..(steps * turns).toInt()) {
            val a = start + i * (2 * Math.PI / steps).toFloat()
            val rr = r * (1f + (rnd.nextFloat() - 0.5f) * 0.08f + i * 0.002f)
            val x = cx + rr * cos(a); val y = cy + rr * sin(a) * 0.95f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    override fun drawBackground(c: Canvas, w: Float, h: Float, phase: Float, density: Float) {
        c.drawColor(paper)
        p.color = 0x332F5DD0; p.strokeWidth = density
        var y = 32 * density
        while (y < h) { c.drawLine(0f, y, w, y, p); y += 30 * density }
        p.color = 0x55E4572E; c.drawLine(28 * density, 0f, 28 * density, h, p)
    }

    private fun sketch(ctx: Context, fill: Int, seed: Int, stroke: Float = 2f): Drawable {
        val d = ctx.dpf(1f)
        return LambdaDrawable(stateful = true) { c, r, pressed ->
            val rr = RectF(r.left + 4 * d, r.top + 4 * d, r.right - 4 * d, r.bottom - 4 * d)
            if (pressed) rr.offset(1.5f * d, 1.5f * d)
            p.style = Paint.Style.FILL; p.color = fill
            c.drawPath(wobblyRect(rr, seed, 3 * d), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = stroke * d; p.color = ink; p.strokeJoin = Paint.Join.ROUND
            c.drawPath(wobblyRect(rr, seed + 1, 3 * d), p)
            p.style = Paint.Style.FILL
        }
    }

    override fun panel(ctx: Context, overlay: Boolean) = sketch(ctx, if (overlay) 0xF7FFFDF7.toInt() else surface, 11, 1.6f)
    override fun button(ctx: Context, color: Int) = sketch(ctx, color, 23, 2.2f)
    override fun tile(ctx: Context, selected: Boolean) = sketch(ctx, if (selected) marker else surface, if (selected) 31 else 37, if (selected) 2.2f else 1.4f)
    override fun tileTextColor(selected: Boolean) = if (selected) ink else muted
    override fun field(ctx: Context) = sketch(ctx, 0xFFFFFFFF.toInt(), 41, 1.2f)

    override fun drawToggle(c: Canvas, w: Float, h: Float, on: Boolean, d: Float) {
        val r = RectF(3 * d, 5 * d, w - 3 * d, h - 5 * d)
        if (on) { p.color = alpha(marker, 220); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = ink
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.style = Paint.Style.FILL
        val kr = r.height() / 2 - 3 * d
        val kx = if (on) r.right - kr - 3 * d else r.left + kr + 3 * d
        c.drawPath(wobblyCircle(kx, h / 2, kr, 5, 1f), p)
    }

    override fun drawSlider(c: Canvas, w: Float, h: Float, f: Float, d: Float) {
        val cy = h / 2; val x = 8 * d + (w - 16 * d) * f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 10 * d; p.color = alpha(marker, 200); c.drawLine(8 * d, cy, x, cy, p)
        p.strokeWidth = 2 * d; p.color = ink; c.drawLine(8 * d, cy, w - 8 * d, cy + d, p)
        p.style = Paint.Style.FILL; c.drawCircle(x, cy, 7 * d, p)
    }

    override fun drawFab(c: Canvas, w: Float, h: Float, state: FabState, frame: Int, d: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) / 2 - 4 * d
        p.color = if (state == FabState.SCANNING) marker else surface
        p.setShadowLayer(4 * d, 1 * d, 2 * d, 0x40000000)
        c.drawCircle(cx, cy, r, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = ink
        c.drawPath(wobblyCircle(cx, cy, r, 3 + (if (state == FabState.BUSY) frame else 0)), p)
        p.style = Paint.Style.FILL
        glyph(c, p, "♞", cx, cy, r * 1.15f, ink, Typeface.DEFAULT)
    }

    override fun drawLogo(c: Canvas, w: Float, h: Float, d: Float) = drawFab(c, w, h, FabState.SCANNING, 0, d)

    override fun drawMarker(c: Canvas, size: Float, from: Int, to: Int, t: Float) {
        val q = size / 8f
        val a = center(from, q); val b = center(to, q)
        // red pencil circle around the piece
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = red; p.strokeWidth = q * 0.06f
        c.drawPath(wobblyCircle(a[0], a[1], q * 0.46f, from * 7 + 1, 0.2f + 1.0f * t), p)
        // blue ink arrow
        val ex = a[0] + (b[0] - a[0]) * t; val ey = a[1] + (b[1] - a[1]) * t
        val dist = hypot(b[0] - a[0], b[1] - a[1])
        val sx = a[0] + (b[0] - a[0]) * (q * 0.48f / dist); val sy = a[1] + (b[1] - a[1]) * (q * 0.48f / dist)
        val mx = (sx + ex) / 2 + (ey - sy) * 0.12f; val my = (sy + ey) / 2 - (ex - sx) * 0.12f
        p.color = blue; p.strokeWidth = q * 0.055f
        c.drawPath(Path().apply { moveTo(sx, sy); quadTo(mx, my, ex, ey) }, p)
        if (t > 0.7f) {
            val ang = kotlin.math.atan2(ey - my, ex - mx); val k = q * 0.22f
            c.drawLine(ex, ey, ex - k * cos(ang - 0.5f), ey - k * sin(ang - 0.5f), p)
            c.drawLine(ex, ey, ex - k * cos(ang + 0.5f), ey - k * sin(ang + 0.5f), p)
        }
        // highlighter dot under the target
        p.style = Paint.Style.FILL; p.color = alpha(marker, 120)
        c.drawCircle(b[0], b[1], q * 0.3f * t, p)
    }
}
