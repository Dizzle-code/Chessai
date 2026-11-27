package com.dizzle.chessai.core.vision

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Feature of one board square: a GRID x GRID map where 0 = background (square color) and
 * 1..255 = luminance of foreground (piece) pixels. Independent of the square's own color.
 */
class SquareFeature(val cells: ByteArray, val fgFraction: Float, val parity: Int) {
    fun v(i: Int) = cells[i].toInt() and 255
}

object SquareFeatures {
    const val GRID = 24

    /** Extracts the 64 square features in visual order (index = row*8 + col, row 0 at the top). */
    fun extract(img: RgbImage, rect: BoardRect): Array<SquareFeature> {
        val q = rect.square
        return Array(64) { i ->
            val row = i / 8
            val col = i % 8
            extractSquare(img, rect.x + col * q, rect.y + row * q, q, (row + col) and 1)
        }
    }

    private fun extractSquare(img: RgbImage, sx: Double, sy: Double, q: Double, parity: Int): SquareFeature {
        val n = GRID
        val inset = q * 0.05
        val cs = (q - 2 * inset) / n
        val rgb = IntArray(n * n)
        for (cy in 0 until n) for (cx in 0 until n) {
            var r = 0; var g = 0; var b = 0; var k = 0
            for (yy in 0 until 2) for (xx in 0 until 2) {
                val px = (sx + inset + (cx + 0.25 + xx * 0.5) * cs).toInt().coerceIn(0, img.width - 1)
                val py = (sy + inset + (cy + 0.25 + yy * 0.5) * cs).toInt().coerceIn(0, img.height - 1)
                val c = img.px[py * img.width + px]
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; k++
            }
            rgb[cy * n + cx] = ((r / k) shl 16) or ((g / k) shl 8) or (b / k)
        }
        // background = per-channel median of the four 2x2 corner blocks
        val corners = intArrayOf(0, n - 2, (n - 2) * n, (n - 2) * n + n - 2)
        val blockAvg = corners.map { base ->
            var r = 0; var g = 0; var b = 0
            for (d in intArrayOf(0, 1, n, n + 1)) {
                val c = rgb[base + d]
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            intArrayOf(r / 4, g / 4, b / 4)
        }
        val bg = IntArray(3) { ch ->
            val v = blockAvg.map { it[ch] }.sorted()
            (v[1] + v[2]) / 2
        }
        // noise level of the square's own texture (flat digital boards ~0, wood themes higher)
        val noise = corners.flatMap { base -> listOf(0, 1, n, n + 1).map { base + it } }.map { i ->
            val c = rgb[i]
            abs(((c shr 16) and 255) - bg[0]) + abs(((c shr 8) and 255) - bg[1]) + abs((c and 255) - bg[2])
        }.sorted()[8]
        val threshold = max(FG_THRESHOLD, noise * 3 + 12)
        val cells = ByteArray(n * n)
        var fg = 0
        for (i in 0 until n * n) {
            val c = rgb[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val d = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
            if (d > threshold) {
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                cells[i] = (1 + luma * 254 / 255).toByte()
                fg++
            }
        }
        return SquareFeature(cells, fg.toFloat() / (n * n), parity)
    }

    const val FG_THRESHOLD = 28

    /** Distance 0..~100 between two features, tolerant to a 1-cell misalignment. */
    fun distance(a: SquareFeature, b: SquareFeature): Float {
        var best = Float.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            val d = shiftedDistance(a, b, dx, dy)
            if (d < best) best = d
        }
        return best
    }

    private fun shiftedDistance(a: SquareFeature, b: SquareFeature, dx: Int, dy: Int): Float {
        val n = GRID
        var sum = 0
        var cnt = 0
        for (y in max(0, -dy) until min(n, n - dy)) {
            val ya = y * n
            val yb = (y + dy) * n
            for (x in max(0, -dx) until min(n, n - dx)) {
                val va = a.v(ya + x)
                val vb = b.v(yb + x + dx)
                cnt++
                if (va == 0 && vb == 0) continue
                sum += if (va == 0 || vb == 0) MISMATCH else abs(va - vb)
            }
        }
        return sum.toFloat() / cnt
    }

    const val MISMATCH = 100

    /** Distance to an empty square. */
    fun emptyDistance(a: SquareFeature): Float = a.fgFraction * MISMATCH
}

/**
 * Learned piece appearance templates (for one board/piece theme). Lets the app recognise
 * positions locally without spending any AI tokens once it has seen each piece type once.
 */
class PieceLibrary {
    private class Template(val label: Int, val feature: SquareFeature)

    private val templates = ArrayList<Template>()

    @get:Synchronized
    val size: Int get() = templates.size

    @Synchronized
    fun clear() = templates.clear()

    @Synchronized
    fun hasLabel(label: Int) = templates.any { it.label == label }

    @Synchronized
    fun knownLabels(): Set<Int> = templates.map { it.label }.toSet()

    /** Adds templates for every non-empty labelled square (labels indexed like [features]). */
    @Synchronized
    fun learn(features: Array<SquareFeature>, labels: IntArray) {
        for (i in features.indices) {
            val label = labels[i]
            if (label == 0) continue
            val f = features[i]
            if (f.fgFraction < 0.02f) continue // looks empty, don't learn garbage
            val same = templates.filter { it.label == label && it.feature.parity == f.parity }
            if (same.any { SquareFeatures.distance(it.feature, f) < 6f }) continue
            if (same.size >= MAX_PER_BUCKET) templates.remove(same.first())
            templates.add(Template(label, f))
        }
    }

    /**
     * For each square returns 13 distances, index = piece + 6 (index 6 = empty).
     * Labels without templates get [UNKNOWN_DIST].
     */
    @Synchronized
    fun distances(features: Array<SquareFeature>): Array<FloatArray> = Array(features.size) { i ->
        val f = features[i]
        val d = FloatArray(13) { UNKNOWN_DIST }
        d[6] = SquareFeatures.emptyDistance(f)
        for (t in templates) {
            // quick reject on very different amount of foreground
            if (abs(t.feature.fgFraction - f.fgFraction) > 0.35f) {
                d[t.label + 6] = min(d[t.label + 6], 60f)
                continue
            }
            val dist = SquareFeatures.distance(t.feature, f)
            if (dist < d[t.label + 6]) d[t.label + 6] = dist
        }
        d
    }

    @Synchronized
    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(templates.size)
        for (t in templates) {
            d.writeByte(t.label)
            d.writeByte(t.feature.parity)
            d.writeFloat(t.feature.fgFraction)
            d.writeShort(t.feature.cells.size)
            d.write(t.feature.cells)
        }
        d.flush()
    }

    @Synchronized
    fun read(input: InputStream) {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) return
        val n = d.readInt()
        templates.clear()
        repeat(n) {
            val label = d.readByte().toInt()
            val parity = d.readByte().toInt()
            val fg = d.readFloat()
            val len = d.readShort().toInt()
            val cells = ByteArray(len)
            d.readFully(cells)
            if (len == SquareFeatures.GRID * SquareFeatures.GRID) templates.add(Template(label, SquareFeature(cells, fg, parity)))
        }
    }

    companion object {
        const val MAX_PER_BUCKET = 3
        const val UNKNOWN_DIST = 55f
        private const val MAGIC = 0x43414931 // "CAI1"
    }
}
