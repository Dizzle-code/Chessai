package com.dizzle.chessai.core

import com.dizzle.chessai.core.ai.BoardPrompt
import com.dizzle.chessai.core.chess.Piece
import com.dizzle.chessai.core.chess.Position
import com.dizzle.chessai.core.engine.SimpleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChessTest {
    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        val buf = IntArray(256)
        val n = p.generate(buf)
        var total = 0L
        for (i in 0 until n) {
            p.make(buf[i])
            if (!p.leftKingInCheck()) total += perft(p, depth - 1)
            p.unmake()
        }
        return total
    }

    @Test fun perftStart() {
        val p = Position.start()
        assertEquals(20, perft(p, 1))
        assertEquals(8902, perft(p, 3))
        assertEquals(197281, perft(p, 4))
        assertEquals(Position.START_FEN, p.toFen())
    }

    @Test fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(p, 1))
        assertEquals(2039, perft(p, 2))
        assertEquals(97862, perft(p, 3))
    }

    @Test fun perftOthers() {
        assertEquals(43238, perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4))
        assertEquals(9467, perft(Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 3))
        assertEquals(62379, perft(Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"), 3))
    }

    @Test fun hashIsIncrementallyConsistent() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        for (m in p.legalMoves()) {
            p.make(m)
            assertEquals(p.recomputeHash(), p.hash, "after ${com.dizzle.chessai.core.chess.Move.uci(m)}")
            p.unmake()
        }
    }

    @Test fun engineFindsMateInOne() {
        val e = SimpleEngine()
        val r = e.bestMove("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", 500)!!
        assertEquals("d1d8", r.bestMove)
        assertEquals(1, r.mateIn)
    }

    @Test fun engineWinsQueen() {
        val e = SimpleEngine()
        // knight fork: Nc7+ wins the rook... simpler: free queen capture
        val r = e.bestMove("4k3/8/8/3q4/8/8/3R4/4K3 w - - 0 1", 500)!!
        assertEquals("d2d5", r.bestMove)
    }

    @Test fun parsesAiOutput() {
        val txt = """
            ```
            rnbqkbnr
            pppppppp
            ........
            ........
            ....P...
            ........
            PPPP.PPP
            RNBQKBNR
            w
            ```
        """.trimIndent()
        val b = BoardPrompt.parse(txt, 10, 20)
        assertEquals(true, b.whiteAtBottom)
        assertEquals(-Piece.ROOK, b.visual[0])
        assertEquals(Piece.PAWN, b.visual[4 * 8 + 4])
        val fen = BoardPrompt.parse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR\nb", 0, 0)
        assertEquals(false, fen.whiteAtBottom)
        assertEquals(Piece.KING, fen.visual[7 * 8 + 4])
    }

    @Test fun plausibility() {
        assertTrue(Position.start().isPlausible())
        // black to move but white king in check by black -> impossible (white just moved into check)
        val bad = Position.fromFen("4k3/8/8/8/8/8/8/r3K3 b - - 0 1")
        assertTrue(!bad.isPlausible())
    }
}
