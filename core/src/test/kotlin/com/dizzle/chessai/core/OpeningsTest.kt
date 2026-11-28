package com.dizzle.chessai.core

import com.dizzle.chessai.core.chess.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpeningsTest {
    private fun sig(p: Position, wb: Boolean) = ByteArray(64) { v ->
        val r = v / 8; val c = v % 8
        val x = p.board[if (wb) (7 - r) * 8 + c else r * 8 + (7 - c)]
        (if (x > 0) 1 else if (x < 0) 2 else 0).toByte()
    }

    @Test fun recognisesEarlyPositions() {
        val t0 = System.currentTimeMillis()
        Openings.warmUp()
        println("opening table built in ${System.currentTimeMillis() - t0} ms")
        val p = Position.start()
        for (u in listOf("d2d4", "g8f6", "c2c4", "e7e6")) p.make(p.parseUci(u)!!)
        val m = assertNotNull(Openings.match(sig(p, false), null))
        assertEquals(false, m.whiteBottom)
        assertEquals(p.placement(), m.position.placement())
        assertEquals(1, m.position.stm)
    }
}
