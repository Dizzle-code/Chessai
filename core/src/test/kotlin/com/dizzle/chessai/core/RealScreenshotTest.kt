package com.dizzle.chessai.core

import com.dizzle.chessai.core.engine.SimpleEngine
import com.dizzle.chessai.core.vision.BoardLocator
import com.dizzle.chessai.core.vision.BoardRect
import com.dizzle.chessai.core.vision.PieceLibrary
import com.dizzle.chessai.core.vision.RgbImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recognition on a real chess.com screenshot (green board, coordinates in the corners). */
class RealScreenshotTest {
    private fun load(name: String): RgbImage {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/$name"))
        val px = IntArray(bi.width * bi.height)
        bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return RgbImage(bi.width, bi.height, px)
    }

    /** Copies the pixels of square [src] onto square [dst] (algebraic names, white at bottom). */
    private fun copySquare(img: RgbImage, r: BoardRect, from: RgbImage, src: String, dst: String) {
        val q = r.size / 8.0
        fun origin(sq: String) = Pair(r.x + (sq[0] - 'a') * q, r.y + ('8' - sq[1]) * q)
        val (sx, sy) = origin(src); val (dx, dy) = origin(dst)
        for (y in 2 until q.toInt() - 2) for (x in 2 until q.toInt() - 2) {
            img.px[(dy.toInt() + y) * img.width + dx.toInt() + x] = from.px[(sy.toInt() + y) * from.width + sx.toInt() + x]
        }
    }

    @Test fun chessComStartThenTracking() {
        val start = load("chesscom_start.png")
        val rect = BoardLocator.locate(start)!!
        assertTrue(rect.x <= 4 && kotlin.math.abs(rect.size - start.width) <= 8, "rect $rect")

        val a = Analyzer(PieceLibrary()) { _, _, _ -> ByteArray(0) }
        val eng = SimpleEngine(16)
        val r1 = a.analyze(start, Analyzer.Options(engine = eng, thinkMs = 200))
        assertTrue(r1.ok, r1.message)
        assertEquals(Analyzer.Source.START, r1.source)

        // 1. e4 e5: move the pawns by copying square pixels (same square colors)
        val img = RgbImage(start.width, start.height, start.px.copyOf())
        copySquare(img, rect, start, "e2", "e4")   // light -> light
        copySquare(img, rect, start, "e6", "e2")   // empty light square
        copySquare(img, rect, start, "e7", "e5")   // dark -> dark
        copySquare(img, rect, start, "e3", "e7")   // empty dark square
        val r2 = a.analyze(img, Analyzer.Options(engine = eng, thinkMs = 200))
        assertTrue(r2.ok, r2.message)
        assertEquals(Analyzer.Source.TRACKED, r2.source)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR", r2.fen!!.substringBefore(' '))

        // a fresh game later in the same theme is classified locally from the learned pieces
        a.resetGame()
        val r3 = a.analyze(img, Analyzer.Options(myColor = 1, engine = eng, thinkMs = 200))
        assertTrue(r3.ok, r3.message)
        assertEquals(Analyzer.Source.LOCAL, r3.source)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR", r3.fen!!.substringBefore(' '))
    }
}

/** Dark theme, board flipped (user plays black), white already played 1. e4 – learned without AI. */
class DarkThemeScreenshotTest {
    @Test fun flippedDarkBoardAfterFirstMove() {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/dark_e4_flipped.png"))
        val px = IntArray(bi.width * bi.height)
        bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        val img = RgbImage(bi.width, bi.height, px)
        val a = Analyzer(PieceLibrary()) { _, _, _ -> ByteArray(0) }
        val r = a.analyze(img, Analyzer.Options(engine = SimpleEngine(16), thinkMs = 200))
        assertTrue(r.ok, r.message)
        assertEquals(Analyzer.Source.START, r.source)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", r.fen!!.substringBefore(' '))
        assertEquals("b", r.fen!!.split(' ')[1])
        assertEquals(false, a.whiteBottom)
        assertTrue(r.myTurn, "black (bottom) is to move")
        assertTrue(r.fromVisual >= 48, "suggested move starts from black's side at the bottom: ${r.message}")
        // pieces of this design are learned now: the position is also recognised by classification
        a.resetGame()
        val r2 = a.analyze(img, Analyzer.Options(myColor = -1, engine = SimpleEngine(16), thinkMs = 200))
        assertEquals(Analyzer.Source.LOCAL, r2.source, r2.message)
    }
}
