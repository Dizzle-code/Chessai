package com.dizzle.chessai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every theme (real MainActivity screens + overlay pieces) to PNGs in
 * app/build/theme-previews. CI attaches them to the release so themes can be checked visually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeScreenshots {
    private val out = File("build/theme-previews").apply { mkdirs() }

    private fun snap(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun renderAllThemes() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        for (id in ThemeId.values()) {
            prefs.themeId = id; Themes.invalidate()
            // onboarding theme picker
            prefs.onboarded = false
            val onb = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            onb.javaClass.getDeclaredField("onboardingPage").apply { isAccessible = true }.setInt(onb, 1)
            onb.javaClass.getDeclaredMethod("render").apply { isAccessible = true }.invoke(onb)
            val pages = mutableListOf(snap(onb.window.decorView, 1080, 2340))
            // main tabs
            prefs.onboarded = true
            val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            for (tab in 0..2) {
                act.javaClass.getDeclaredField("currentTab").apply { isAccessible = true }.setInt(act, tab)
                act.javaClass.getDeclaredMethod("render").apply { isAccessible = true }.invoke(act)
                pages += snap(act.window.decorView, 1080, 2340)
            }
            // overlay pieces on a board
            val t = Themes.of(id)
            val ov = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val c = Canvas(ov)
            c.drawColor(Color.rgb(48, 46, 43))
            val bs = 1080f; val q = bs / 8; val by = 500f
            val sq = Paint()
            for (y in 0 until 8) for (x in 0 until 8) {
                sq.color = if ((x + y) % 2 == 0) Color.rgb(238, 238, 210) else Color.rgb(118, 150, 86)
                c.drawRect(x * q, by + y * q, (x + 1) * q, by + (y + 1) * q, sq)
            }
            val pc = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = q * 0.85f; textAlign = Paint.Align.CENTER; color = Color.WHITE; setShadowLayer(4f, 0f, 2f, Color.BLACK) }
            c.drawText("♘", 6.5f * q, by + 7.5f * q + q * 0.3f, pc)
            c.save(); c.translate(0f, by); t.drawMarker(c, bs, 7 * 8 + 6, 5 * 8 + 5, 1f); c.restore()
            for ((i, st) in FabState.values().withIndex()) { c.save(); c.translate(60f + i * 260f, 200f); t.drawFab(c, 180f, 180f, st, 3, 3f); c.restore() }
            val panel = t.panel(ctx, overlay = true); panel.setBounds(60, 1700, 1020, 2200); panel.draw(c)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.text; textSize = 54f; typeface = t.bodyFont(ctx) }
            c.drawText("e2e4 +0.3 (lokal, 0 Tokens)", 120f, 1830f, tp)
            pages += ov
            // contact sheet: 5 screens side by side, scaled to 40%
            val sw = 432; val sh = 936
            val sheet = Bitmap.createBitmap(sw * pages.size + 20 * (pages.size + 1), sh + 40, Bitmap.Config.ARGB_8888)
            val sc = Canvas(sheet); sc.drawColor(Color.rgb(20, 20, 24))
            for ((i, pg) in pages.withIndex()) sc.drawBitmap(Bitmap.createScaledBitmap(pg, sw, sh, true), 20f + i * (sw + 20), 20f, null)
            save(sheet, "theme_${id.name.lowercase()}")
            println("rendered ${id.name}")
        }
    }
}
