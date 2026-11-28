package com.dizzle.chessai.core

import com.dizzle.chessai.core.chess.Position
import com.dizzle.chessai.core.vision.SquareFeature

/**
 * Recognises the first moves of a game without any learned pieces or AI: it compares the
 * observed pattern "square empty / light piece / dark piece" with every position reachable from
 * the initial position within [MAX_PLIES] half-moves, in both board orientations. A unique match
 * yields the full position (and who is to move), from which the piece templates are learned.
 */
object Openings {
    const val MAX_PLIES = 4

    private class Table(val sigs: ByteArray, val parent: IntArray, val move: IntArray, val count: Int)

    @Volatile private var table: Table? = null

    /** Builds the opening table in advance (call from a background thread). */
    fun warmUp() { table() }

    private fun table(): Table = table ?: synchronized(this) { table ?: build().also { table = it } }

    /** Breadth-first enumeration of all distinct positions up to MAX_PLIES (signature per square). */
    private fun build(): Table {
        val sigs = ArrayList<ByteArray>()
        val parents = ArrayList<Int>()
        val moves = ArrayList<Int>()
        val seen = HashSet<Long>()
        val start = Position.start()
        seen += start.hash
        sigs += signature(start); parents += -1; moves += 0
        var levelStart = 0
        for (ply in 1..MAX_PLIES) {
            val levelEnd = sigs.size
            for (i in levelStart until levelEnd) {
                val p = replay(i, parents, moves)
                for (m in p.legalMoves()) {
                    p.make(m)
                    if (seen.add(p.hash)) {
                        sigs += signature(p); parents += i; moves += m
                    }
                    p.unmake()
                }
            }
            levelStart = levelEnd
        }
        val flat = ByteArray(sigs.size * 64)
        for ((i, s) in sigs.withIndex()) s.copyInto(flat, i * 64)
        return Table(flat, parents.toIntArray(), moves.toIntArray(), sigs.size)
    }

    private fun replay(i: Int, parents: List<Int>, moves: List<Int>): Position {
        val path = ArrayList<Int>()
        var k = i
        while (parents[k] >= 0) { path += moves[k]; k = parents[k] }
        val p = Position.start()
        for (m in path.asReversed()) p.make(m)
        p.clearUndo()
        return p
    }

    private fun signature(p: Position) = ByteArray(64) { sq ->
        val v = p.board[sq]
        (if (v > 0) 1 else if (v < 0) 2 else 0).toByte()
    }

    data class Match(val position: Position, val whiteBottom: Boolean)

    /**
     * @param visualSig per visual square (row*8+col, row 0 = top): 0 empty, 1 light piece, 2 dark piece
     * @param orientation true/false to force white-at-bottom, null to try both
     */
    fun match(visualSig: ByteArray, orientation: Boolean?): Match? {
        val t = table()
        var found: Match? = null
        var foundPlacement: String? = null
        for (wb in listOfNotNull(if (orientation != false) true else null, if (orientation != true) false else null)) {
            // observed signature in board-square order
            val obs = ByteArray(64)
            for (v in 0 until 64) {
                val r = v / 8; val c = v % 8
                val sq = if (wb) (7 - r) * 8 + c else r * 8 + (7 - c)
                obs[sq] = visualSig[v]
            }
            for (i in 0 until t.count) {
                val o = i * 64
                var ok = true
                for (sq in 0 until 64) if (t.sigs[o + sq] != obs[sq]) { ok = false; break }
                if (!ok) continue
                val pos = replayFlat(i, t)
                val placement = pos.placement()
                if (foundPlacement == null) {
                    found = Match(Position.fromFen(pos.toFen()), wb); foundPlacement = placement
                } else if (placement != foundPlacement || found!!.whiteBottom != wb) {
                    return null // ambiguous
                }
            }
        }
        return found
    }

    private fun replayFlat(i: Int, t: Table): Position {
        val path = ArrayList<Int>()
        var k = i
        while (t.parent[k] >= 0) { path += t.move[k]; k = t.parent[k] }
        val p = Position.start()
        for (m in path.asReversed()) p.make(m)
        p.clearUndo()
        return p
    }

    /**
     * Turns square features into the empty/light/dark signature. Returns null if some square is
     * ambiguous or the two piece colours cannot be told apart clearly.
     */
    fun signatureOf(f: Array<SquareFeature>): ByteArray? {
        val lum = DoubleArray(64)
        val occupied = BooleanArray(64)
        for (v in 0 until 64) {
            val fe = f[v]
            when {
                fe.fgFraction > 0.06f -> occupied[v] = true
                fe.fgFraction < 0.025f -> {}
                else -> return null
            }
            if (occupied[v]) {
                var s = 0L; var n = 0
                for (i in fe.cells.indices) { val x = fe.v(i); if (x != 0) { s += x; n++ } }
                lum[v] = if (n == 0) 0.0 else s.toDouble() / n
            }
        }
        val values = (0 until 64).filter { occupied[it] }.map { lum[it] }.sorted()
        if (values.size < 4) return null
        // split at the largest gap between sorted brightness values
        var gap = 0.0; var cut = 0.0
        for (i in 1 until values.size) {
            val g = values[i] - values[i - 1]
            if (g > gap) { gap = g; cut = (values[i] + values[i - 1]) / 2 }
        }
        if (gap < 25) return null
        return ByteArray(64) { v -> if (!occupied[v]) 0 else if (lum[v] > cut) 1 else 2 }
    }
}
