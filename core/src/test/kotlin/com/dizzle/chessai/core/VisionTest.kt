package com.dizzle.chessai.core

import com.dizzle.chessai.core.ai.AiBoard
import com.dizzle.chessai.core.ai.VisionProvider
import com.dizzle.chessai.core.chess.Piece
import com.dizzle.chessai.core.chess.Position
import com.dizzle.chessai.core.engine.SimpleEngine
import com.dizzle.chessai.core.vision.BoardLocator
import com.dizzle.chessai.core.vision.PieceLibrary
import com.dizzle.chessai.core.vision.RgbImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** End-to-end test on synthetic "screenshots" of a chess app. */
class VisionTest {
    private val W = 1080
    private val H = 2340
    private val BX = 0
    private val BY = 620
    private val BS = 1080

    private fun render(pos: Position, whiteBottom: Boolean, highlight: Set<Int> = emptySet()): RgbImage {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x302E2B)
        g.fillRect(0, 0, W, H)
        // some UI noise
        g.color = Color(0x4A4744); g.fillRect(40, 300, 600, 120); g.fillRect(40, 1900, 700, 120)
        val q = BS / 8
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = if (whiteBottom) (7 - row) * 8 + col else row * 8 + (7 - col)
            val light = (row + col) % 2 == 0
            g.color = if (sq in highlight) Color(0xF5F682) else if (light) Color(0xEEEED2) else Color(0x769656)
            g.fillRect(BX + col * q, BY + row * q, q, q)
            val p = pos.board[sq]
            if (p != 0) drawPiece(g, BX + col * q, BY + row * q, q, p)
        }
        g.dispose()
        val px = IntArray(W * H)
        img.getRGB(0, 0, W, H, px, 0, W)
        return RgbImage(W, H, px)
    }

    private fun drawPiece(g: java.awt.Graphics2D, x: Int, y: Int, q: Int, p: Int) {
        val white = p > 0
        val t = abs(p)
        val m = q / 6
        g.color = if (white) Color(0xF8F8F8) else Color(0x222222)
        // different silhouettes per type
        when (t) {
            Piece.PAWN -> g.fillOval(x + q / 3, y + q / 3, q / 3, q / 2)
            Piece.KNIGHT -> g.fillPolygon(intArrayOf(x + m, x + q - m, x + q / 2), intArrayOf(y + q - m, y + q - m, y + m), 3)
            Piece.BISHOP -> g.fillOval(x + q / 4, y + m, q / 2, q - 2 * m)
            Piece.ROOK -> g.fillRect(x + m, y + m, q - 2 * m, q - 2 * m)
            Piece.QUEEN -> g.fillOval(x + m, y + m, q - 2 * m, q - 2 * m)
            Piece.KING -> g.fillRoundRect(x + m, y + m / 2, q - 2 * m, q - m, q / 3, q / 3)
        }
        g.color = if (white) Color.BLACK else Color(0xDDDDDD)
        g.stroke = BasicStroke(4f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, q / 3)
        g.drawString("PNBRQK"[t - 1].toString(), x + q / 2 - q / 10, y + q / 2 + q / 8)
    }

    private class FakeVision(val answer: () -> AiBoard) : VisionProvider {
        var calls = 0
        override val label = "fake"
        override fun readBoard(jpeg: ByteArray): AiBoard { calls++; return answer() }
    }

    private fun visualOf(pos: Position, wb: Boolean) = IntArray(64) { v ->
        val r = v / 8; val c = v % 8
        pos.board[if (wb) (7 - r) * 8 + c else r * 8 + (7 - c)]
    }

    private fun play(pos: Position, vararg uci: String): Position {
        val p = pos.copy()
        for (u in uci) p.make(p.parseUci(u) ?: fail("illegal $u in ${p.toFen()}"))
        return Position.fromFen(p.toFen())
    }

    @Test fun locatesBoard() {
        val r = BoardLocator.locate(render(Position.start(), true))
        assertNotNull(r)
        assertTrue(abs(r.x - BX) <= 10 && abs(r.y - BY) <= 10 && abs(r.size - BS) <= 14, "got $r")
    }

    @Test fun startPositionWithoutAiThenTracking() {
        val lib = PieceLibrary()
        val a = Analyzer(lib) { _, _, _ -> ByteArray(0) }
        val fake = FakeVision { fail("AI should not be needed") }
        val eng = SimpleEngine(16)
        var pos = Position.start()
        val r1 = a.analyze(render(pos, true), Analyzer.Options(engine = eng, thinkMs = 200, vision = fake))
        assertTrue(r1.ok, r1.message)
        assertEquals(Analyzer.Source.START, r1.source)
        assertTrue(r1.myTurn)
        assertTrue(r1.fromVisual in 48..63, "white move from bottom: ${r1.message}")

        pos = play(pos, "e2e4", "e7e5")
        val r2 = a.analyze(render(pos, true, setOf(52, 36)), Analyzer.Options(engine = eng, thinkMs = 200, vision = fake))
        assertTrue(r2.ok, r2.message)
        assertEquals(Analyzer.Source.TRACKED, r2.source)
        assertEquals(pos.placement(), r2.fen!!.substringBefore(' '))

        pos = play(pos, "g1f3", "b8c6", )
        val r3 = a.analyze(render(pos, true), Analyzer.Options(engine = eng, thinkMs = 200, vision = fake))
        assertEquals(Analyzer.Source.TRACKED, r3.source, r3.message)
        assertEquals(pos.toFen().substringBefore(" - "), r3.fen!!.substringBefore(" - "))
        assertEquals(0, fake.calls)
    }

    @Test fun midGameNeedsAiOnceThenLocal() {
        val lib = PieceLibrary()
        val a = Analyzer(lib) { _, _, _ -> ByteArray(0) }
        var pos = Position.fromFen("r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 5")
        val wb = false // I play black, board flipped
        val fake = FakeVision { AiBoard(visualOf(pos, wb), false, 300, 60) }
        val eng = SimpleEngine(16)
        val r1 = a.analyze(render(pos, wb), Analyzer.Options(myColor = 0, engine = eng, thinkMs = 200, vision = fake))
        assertTrue(r1.ok, r1.message)
        assertEquals(Analyzer.Source.AI, r1.source)
        assertEquals(1, fake.calls)
        assertEquals(pos.placement(), r1.fen!!.substringBefore(' '))
        assertTrue(r1.myTurn)

        pos = play(pos, "e8g8", "c1g5")
        val r2 = a.analyze(render(pos, wb), Analyzer.Options(engine = eng, thinkMs = 200, vision = fake))
        assertTrue(r2.ok, r2.message)
        assertEquals(Analyzer.Source.TRACKED, r2.source)
        assertEquals(pos.placement(), r2.fen!!.substringBefore(' '))
        assertEquals(1, fake.calls)

        // new game in same theme -> recognised locally by classification
        a.resetGame()
        val p3 = play(Position.start(), "d2d4", "d7d5", "c2c4")
        val r3 = a.analyze(render(p3, wb), Analyzer.Options(myColor = -1, engine = eng, thinkMs = 200, vision = fake))
        assertTrue(r3.ok, r3.message)
        assertEquals(Analyzer.Source.LOCAL, r3.source)
        assertEquals(p3.placement(), r3.fen!!.substringBefore(' '))
        assertEquals(1, fake.calls)
    }
}
