package com.dizzle.chessai.core

import com.dizzle.chessai.core.vision.BoardLocator
import com.dizzle.chessai.core.vision.RgbImage
import com.dizzle.chessai.core.vision.SquareFeatures
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** lichess draws a red glow behind a king in check; it must not count as part of the piece. */
class CheckGlowTest {
    @Test fun redCheckGlowIsIgnored() {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/lichess_check_full.png"))
        val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        val img = RgbImage(bi.width, bi.height, px)
        val rect = BoardLocator.locate(img)!!
        assertTrue(kotlin.math.abs(rect.x - 37) <= 6 && kotlin.math.abs(rect.size - 1111) <= 10, "rect $rect")
        val f = SquareFeatures.extract(img, rect)
        val glowingKing = f[3 * 8 + 7]   // h5, white king in check
        val blackKing = f[0 * 8 + 6]     // g8, no glow
        println("fg h5=${glowingKing.fgFraction} g8=${blackKing.fgFraction}")
        assertTrue(glowingKing.fgFraction < blackKing.fgFraction + 0.12f, "glow counted as piece: ${glowingKing.fgFraction}")

        // Same piece type, other colour: the silhouettes (foreground masks) must overlap well.
        var inter = 0; var union = 0
        for (i in glowingKing.cells.indices) {
            val a = glowingKing.v(i) != 0; val b = blackKing.v(i) != 0
            if (a && b) inter++
            if (a || b) union++
        }
        val iou = inter.toDouble() / union
        println("king silhouette overlap (IoU) = $iou")
        assertTrue(iou > 0.6, "IoU $iou")
    }
}
