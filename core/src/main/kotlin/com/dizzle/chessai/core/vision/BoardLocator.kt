package com.dizzle.chessai.core.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ARGB image (same layout as android.graphics.Bitmap#getPixels). */
class RgbImage(val width: Int, val height: Int, val px: IntArray) {
    init { require(px.size >= width * height) }
}

/** Square board region in image pixels. */
data class BoardRect(val x: Int, val y: Int, val size: Int) {
    val square: Double get() = size / 8.0
    fun encode() = "$x,$y,$size"
    companion object {
        fun decode(s: String?): BoardRect? {
            val p = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            return if (p.size == 3 && p[2] > 16) BoardRect(p[0], p[1], p[2]) else null
        }
    }
}

/** Small RGB raster with integral images for O(1) box averages. */
internal class Raster(val w: Int, val h: Int, val r: IntArray, val g: IntArray, val b: IntArray) {
    private val ir = integral(r)
    private val ig = integral(g)
    private val ib = integral(b)

    private fun integral(c: IntArray): IntArray {
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += c[y * w + x]
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        return ii
    }

    private fun box(ii: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val W = w + 1
        return ii[y1 * W + x1] - ii[y0 * W + x1] - ii[y1 * W + x0] + ii[y0 * W + x0]
    }

    /** Average color of box [x0,x1) x [y0,y1) into out[0..2]. Coordinates are clamped. */
    fun avg(x0: Int, y0: Int, x1: Int, y1: Int, out: IntArray, o: Int = 0) {
        val ax = x0.coerceIn(0, w - 1)
        val ay = y0.coerceIn(0, h - 1)
        val bx = x1.coerceIn(ax + 1, w)
        val by = y1.coerceIn(ay + 1, h)
        val n = (bx - ax) * (by - ay)
        out[o] = box(ir, ax, ay, bx, by) / n
        out[o + 1] = box(ig, ax, ay, bx, by) / n
        out[o + 2] = box(ib, ax, ay, bx, by) / n
    }

    companion object {
        fun downscale(img: RgbImage, targetW: Int): Raster {
            val f = max(1.0, img.width.toDouble() / targetW)
            val w = max(1, (img.width / f).toInt())
            val h = max(1, (img.height / f).toInt())
            val r = IntArray(w * h)
            val g = IntArray(w * h)
            val b = IntArray(w * h)
            for (y in 0 until h) {
                val sy0 = (y * f).toInt()
                val sy1 = min(img.height, max(sy0 + 1, ((y + 1) * f).toInt()))
                for (x in 0 until w) {
                    val sx0 = (x * f).toInt()
                    val sx1 = min(img.width, max(sx0 + 1, ((x + 1) * f).toInt()))
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    // sample a 3x3 grid inside the source box (fast, good enough)
                    for (yy in 0 until 3) {
                        val py = sy0 + (sy1 - sy0) * yy / 3
                        for (xx in 0 until 3) {
                            val pxx = sx0 + (sx1 - sx0) * xx / 3
                            val c = img.px[py * img.width + pxx]
                            sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
                        }
                    }
                    r[y * w + x] = sr / n; g[y * w + x] = sg / n; b[y * w + x] = sb / n
                }
            }
            return Raster(w, h, r, g, b)
        }
    }
}

/**
 * Finds a chess board on a screenshot by searching for the square region whose 8x8 grid shows the
 * strongest two-color checker pattern (sampled at square corners, where pieces rarely are).
 */
object BoardLocator {
    private const val MEDIUM_W = 256
    private const val COARSE_W = 64

    data class Located(val rect: BoardRect, val score: Double)

    fun locate(img: RgbImage): BoardRect? = locateScored(img)?.rect

    fun locateScored(img: RgbImage): Located? {
        val medium = Raster.downscale(img, MEDIUM_W)
        val fm = img.width.toDouble() / medium.w
        val coarse = Raster.downscale(img, COARSE_W)
        val fc = medium.w.toDouble() / coarse.w

        // --- coarse exhaustive search
        val tmp = IntArray(64 * 3 * 2)
        var bestS = -1e9; var bx = 0; var by = 0; var bs = 0
        val minS = (min(coarse.w, coarse.h) * 0.3).toInt().coerceAtLeast(12)
        val maxS = min(coarse.w, coarse.h)
        for (s in minS..maxS) {
            for (y in 0..(coarse.h - s)) {
                for (x in 0..(coarse.w - s)) {
                    if (quickScore(coarse, x.toDouble(), y.toDouble(), s.toDouble(), tmp) < 18) continue
                    val sc = score(coarse, x.toDouble(), y.toDouble(), s.toDouble(), tmp, fourCorners = false)
                    if (sc > bestS) { bestS = sc; bx = x; by = y; bs = s }
                }
            }
        }
        if (bestS < 10) return null

        // --- refine on medium raster
        var mx = bx * fc; var my = by * fc; var ms = bs * fc
        var best = score(medium, mx, my, ms, tmp, true)
        val rad = (fc + 1).roundToInt()
        val cx = mx; val cy = my; val cs = ms
        for (ds in -rad..rad) for (dy in -rad..rad) for (dx in -rad..rad) {
            val sx = cx + dx; val sy = cy + dy; val ss = cs + ds * 1.0
            if (sx < 0 || sy < 0 || sx + ss > medium.w + 0.5 || sy + ss > medium.h + 0.5) continue
            val sc = score(medium, sx, sy, ss, tmp, true)
            if (sc > best) { best = sc; mx = sx; my = sy; ms = ss }
        }
        // sub-pixel-ish final pass with half steps
        val px = mx; val py = my; val ps = ms
        for (ds in -2..2) for (dy in -2..2) for (dx in -2..2) {
            val sx = px + dx * 0.5; val sy = py + dy * 0.5; val ss = ps + ds * 0.5
            if (sx < 0 || sy < 0 || sx + ss > medium.w + 0.5 || sy + ss > medium.h + 0.5) continue
            val sc = score(medium, sx, sy, ss, tmp, true)
            if (sc > best) { best = sc; mx = sx; my = sy; ms = ss }
        }
        if (best < 15) return null
        val rough = BoardRect((mx * fm).roundToInt(), (my * fm).roundToInt(), (ms * fm).roundToInt())
        return Located(refineEdges(img, rough, (fm * 3).roundToInt() + 3), best)
    }

    /**
     * Pixel-exact refinement: the 7 inner grid lines in each direction must sit exactly on the
     * color boundaries between neighbouring squares.
     */
    fun refineEdges(img: RgbImage, rough: BoardRect, radius: Int): BoardRect {
        var best = rough
        // x/size from the vertical lines, then y from the horizontal lines; two passes
        for (pass in 0 until 2) {
            val c = best
            var bv = edgeScore(img, c, vertical = true)
            for (ds in -radius..radius) for (dx in -radius..radius) {
                val r = BoardRect(c.x + dx, c.y, c.size + ds)
                val s = edgeScore(img, r, vertical = true)
                if (s > bv) { bv = s; best = r }
            }
            val c2 = best
            var bh = edgeScore(img, c2, vertical = false)
            for (dy in -radius..radius) {
                val r = BoardRect(c2.x, c2.y + dy, c2.size)
                val s = edgeScore(img, r, vertical = false)
                if (s > bh) { bh = s; best = r }
            }
        }
        return if (edgeScore(img, best) > 5) best else rough
    }

    /** Mean color jump across the inner grid lines (vertical lines and/or horizontal lines). */
    private fun edgeScore(img: RgbImage, r: BoardRect, vertical: Boolean? = null): Double {
        val q = r.size / 8.0
        var sum = 0.0
        var n = 0
        val samples = 6
        fun px(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, img.width - 1); val cy = y.coerceIn(0, img.height - 1)
            return img.px[cy * img.width + cx]
        }
        fun diff(a: Int, b: Int) = abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
        for (i in 1 until 8) for (k in 0 until 8) for (s in 0 until samples) {
            val along = (k + 0.15 + 0.7 * s / (samples - 1)) * q
            if (vertical != false) {
                val lx = (r.x + i * q).roundToInt(); val y = (r.y + along).toInt()
                sum += diff(px(lx - 2, y), px(lx + 1, y)) - 0.5 * (diff(px(lx - 4, y), px(lx - 2, y)) + diff(px(lx + 1, y), px(lx + 3, y)))
                n++
            }
            if (vertical != true) {
                val ly = (r.y + i * q).roundToInt(); val x = (r.x + along).toInt()
                sum += diff(px(x, ly - 2), px(x, ly + 1)) - 0.5 * (diff(px(x, ly - 4), px(x, ly - 2)) + diff(px(x, ly + 1), px(x, ly + 3)))
                n++
            }
        }
        return sum / n
    }

    /** Checker-pattern score of an existing rect on a full image (used to validate a cached rect). */
    fun verify(img: RgbImage, rect: BoardRect): Double {
        val medium = Raster.downscale(img, MEDIUM_W)
        val f = img.width.toDouble() / medium.w
        return score(medium, rect.x / f, rect.y / f, rect.size / f, IntArray(64 * 3 * 2), true)
    }

    /** Cheap pre-filter: 4 squares per row on two rows. */
    private fun quickScore(r: Raster, x: Double, y: Double, s: Double, tmp: IntArray): Double {
        val q = s / 8
        var e0 = 0; var e1 = 0; var e2 = 0; var o0 = 0; var o1 = 0; var o2 = 0
        var k = 0
        for (row in intArrayOf(0, 7)) for (col in 0 until 8) {
            patch(r, x, y, q, col, row, 0, tmp, 0)
            if ((col + row) and 1 == 0) { e0 += tmp[0]; e1 += tmp[1]; e2 += tmp[2] }
            else { o0 += tmp[0]; o1 += tmp[1]; o2 += tmp[2] }
            k++
        }
        return (abs(e0 - o0) + abs(e1 - o1) + abs(e2 - o2)) / 8.0
    }

    private fun patch(r: Raster, x: Double, y: Double, q: Double, col: Int, row: Int, corner: Int, out: IntArray, o: Int) {
        val inset = q * 0.10
        val p = max(1.0, q * 0.16)
        val sx = x + col * q
        val sy = y + row * q
        val px = if (corner and 1 == 0) sx + inset else sx + q - inset - p
        val py = if (corner and 2 == 0) sy + inset else sy + q - inset - p
        r.avg(px.toInt(), py.toInt(), (px + p).roundToInt(), (py + p).roundToInt(), out, o)
    }

    private fun score(r: Raster, x: Double, y: Double, s: Double, tmp: IntArray, fourCorners: Boolean): Double {
        val q = s / 8
        val col = IntArray(64 * 3)
        val c4 = IntArray(12)
        for (row in 0 until 8) for (c in 0 until 8) {
            val i = (row * 8 + c) * 3
            if (fourCorners) {
                for (k in 0 until 4) patch(r, x, y, q, c, row, k, c4, k * 3)
                for (ch in 0 until 3) {
                    // median of 4 = mean of middle two
                    val v = intArrayOf(c4[ch], c4[3 + ch], c4[6 + ch], c4[9 + ch]).sorted()
                    col[i + ch] = (v[1] + v[2]) / 2
                }
            } else {
                patch(r, x, y, q, c, row, 0, c4, 0)
                patch(r, x, y, q, c, row, 3, c4, 3)
                for (ch in 0 until 3) col[i + ch] = (c4[ch] + c4[3 + ch]) / 2
            }
        }
        val me = DoubleArray(3); val mo = DoubleArray(3)
        for (k in 0 until 64) {
            val even = ((k / 8) + (k % 8)) and 1 == 0
            for (ch in 0 until 3) { if (even) me[ch] += col[k * 3 + ch].toDouble() else mo[ch] += col[k * 3 + ch].toDouble() }
        }
        for (ch in 0 until 3) { me[ch] /= 32; mo[ch] /= 32 }
        var dev = 0.0
        for (k in 0 until 64) {
            val even = ((k / 8) + (k % 8)) and 1 == 0
            val m = if (even) me else mo
            for (ch in 0 until 3) dev += abs(col[k * 3 + ch] - m[ch])
        }
        dev /= 64
        val contrast = abs(me[0] - mo[0]) + abs(me[1] - mo[1]) + abs(me[2] - mo[2])
        if (contrast < 20) return -1e9
        // slight preference for bigger boards so that we lock onto the full board
        return contrast - 2.0 * dev + s / r.w * 4
    }
}
