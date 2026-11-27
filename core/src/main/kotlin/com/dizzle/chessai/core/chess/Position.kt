package com.dizzle.chessai.core.chess

/**
 * Pieces are plain ints: 0 = empty, +1..+6 = white P N B R Q K, -1..-6 = black.
 * Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 */
object Piece {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    private const val CHARS = "PNBRQK"

    fun toChar(p: Int): Char = when {
        p == 0 -> '.'
        p > 0 -> CHARS[p - 1]
        else -> CHARS[-p - 1].lowercaseChar()
    }

    /** Returns piece code for a FEN char or null if the char is not a piece. */
    fun fromChar(c: Char): Int? {
        val idx = CHARS.indexOf(c.uppercaseChar())
        if (idx < 0) return null
        return if (c.isUpperCase()) idx + 1 else -(idx + 1)
    }
}

object Sq {
    fun file(s: Int) = s and 7
    fun rank(s: Int) = s shr 3
    fun of(file: Int, rank: Int) = rank * 8 + file
    fun name(s: Int): String = "${'a' + file(s)}${'1' + rank(s)}"
    fun parse(n: String): Int {
        require(n.length == 2) { "bad square $n" }
        val f = n[0] - 'a'
        val r = n[1] - '1'
        require(f in 0..7 && r in 0..7) { "bad square $n" }
        return of(f, r)
    }
}

/** Moves are packed ints: from | to<<6 | promoPieceType<<12 | flags<<16. */
object Move {
    const val FLAG_EP = 1
    const val FLAG_CASTLE = 2
    const val FLAG_DOUBLE = 4

    fun of(from: Int, to: Int, promo: Int = 0, flags: Int = 0) =
        from or (to shl 6) or (promo shl 12) or (flags shl 16)

    fun from(m: Int) = m and 63
    fun to(m: Int) = (m shr 6) and 63
    fun promo(m: Int) = (m shr 12) and 7
    fun flags(m: Int) = m shr 16

    fun uci(m: Int): String {
        val p = promo(m)
        val suffix = if (p != 0) Piece.toChar(-p).toString() else ""
        return Sq.name(from(m)) + Sq.name(to(m)) + suffix
    }
}

internal object Tables {
    // (df, dr) — first four orthogonal, last four diagonal
    val DIRS = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )
    val knight: Array<IntArray>
    val king: Array<IntArray>
    val rays: Array<Array<IntArray>>

    init {
        val kn = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        knight = Array(64) { s -> jumps(s, kn) }
        king = Array(64) { s -> jumps(s, DIRS) }
        rays = Array(64) { s ->
            Array(8) { d ->
                val list = ArrayList<Int>(7)
                var f = Sq.file(s) + DIRS[d][0]
                var r = Sq.rank(s) + DIRS[d][1]
                while (f in 0..7 && r in 0..7) {
                    list.add(Sq.of(f, r)); f += DIRS[d][0]; r += DIRS[d][1]
                }
                list.toIntArray()
            }
        }
    }

    private fun jumps(s: Int, deltas: Array<IntArray>): IntArray {
        val out = ArrayList<Int>(8)
        for (d in deltas) {
            val f = Sq.file(s) + d[0]
            val r = Sq.rank(s) + d[1]
            if (f in 0..7 && r in 0..7) out.add(Sq.of(f, r))
        }
        return out.toIntArray()
    }

    // Zobrist keys
    val zPiece = Array(13) { LongArray(64) }
    val zCastle = LongArray(16)
    val zEp = LongArray(8)
    val zSide: Long

    init {
        var seed = 0x9E3779B97F4A7C15uL.toLong()
        fun next(): Long {
            // splitmix64
            seed += -0x61c8864680b583ebL
            var z = seed
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        for (p in 0 until 13) for (s in 0 until 64) zPiece[p][s] = if (p == 6) 0L else next()
        for (i in 0 until 16) zCastle[i] = next()
        for (i in 0 until 8) zEp[i] = next()
        zSide = next()
    }

    val castleMask = IntArray(64) { 15 }.also {
        it[0] = 15 and 2.inv()  // a1 -> white queen side
        it[7] = 15 and 1.inv()  // h1 -> white king side
        it[4] = 15 and 3.inv()  // e1
        it[56] = 15 and 8.inv() // a8
        it[63] = 15 and 4.inv() // h8
        it[60] = 15 and 12.inv() // e8
    }
}

class Position {
    val board = IntArray(64)
    /** 1 = white to move, -1 = black. */
    var stm = 1
        private set
    /** bit 1 = K, 2 = Q, 4 = k, 8 = q */
    var castling = 0
        private set
    var ep = -1
        private set
    var halfmove = 0
        private set
    var fullmove = 1
        private set
    var hash = 0L
        private set
    private val kingSq = IntArray(2) // [0] white, [1] black

    // Undo stack
    private var sp = 0
    private val uMove = IntArray(MAX_PLY)
    private val uCaptured = IntArray(MAX_PLY)
    private val uMoved = IntArray(MAX_PLY)
    private val uCastle = IntArray(MAX_PLY)
    private val uEp = IntArray(MAX_PLY)
    private val uHalf = IntArray(MAX_PLY)
    private val uHash = LongArray(MAX_PLY)
    /** Hash history for repetition detection (game history + search path). */
    private val history = LongArray(MAX_PLY + 512)
    private var histLen = 0

    fun copy(): Position = fromFen(toFen())

    fun kingSquare(color: Int) = kingSq[if (color > 0) 0 else 1]

    // ---------------------------------------------------------------- FEN

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[Sq.of(f, r)]
                if (p == 0) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Piece.toChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (stm > 0) " w " else " b ")
        if (castling == 0) sb.append('-') else {
            if (castling and 1 != 0) sb.append('K')
            if (castling and 2 != 0) sb.append('Q')
            if (castling and 4 != 0) sb.append('k')
            if (castling and 8 != 0) sb.append('q')
        }
        sb.append(' ').append(if (ep >= 0) Sq.name(ep) else "-")
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    /** Board part of FEN only (for comparisons). */
    fun placement(): String = toFen().substringBefore(' ')

    fun recomputeHash(): Long {
        var h = 0L
        for (s in 0 until 64) if (board[s] != 0) h = h xor Tables.zPiece[board[s] + 6][s]
        h = h xor Tables.zCastle[castling]
        if (ep >= 0) h = h xor Tables.zEp[Sq.file(ep)]
        if (stm < 0) h = h xor Tables.zSide
        return h
    }

    private fun setupDerived() {
        kingSq[0] = -1; kingSq[1] = -1
        for (s in 0 until 64) {
            if (board[s] == Piece.KING) kingSq[0] = s
            if (board[s] == -Piece.KING) kingSq[1] = s
        }
        hash = recomputeHash()
        sp = 0
        histLen = 0
    }

    // ---------------------------------------------------------------- attacks

    fun isAttacked(sq: Int, by: Int): Boolean {
        val b = board
        val f = Sq.file(sq)
        if (by > 0) {
            if (f > 0 && sq - 9 >= 0 && b[sq - 9] == Piece.PAWN) return true
            if (f < 7 && sq - 7 >= 0 && b[sq - 7] == Piece.PAWN) return true
        } else {
            if (f > 0 && sq + 7 < 64 && b[sq + 7] == -Piece.PAWN) return true
            if (f < 7 && sq + 9 < 64 && b[sq + 9] == -Piece.PAWN) return true
        }
        val kn = by * Piece.KNIGHT
        for (t in Tables.knight[sq]) if (b[t] == kn) return true
        val kg = by * Piece.KING
        for (t in Tables.king[sq]) if (b[t] == kg) return true
        val rook = by * Piece.ROOK
        val bishop = by * Piece.BISHOP
        val queen = by * Piece.QUEEN
        val rays = Tables.rays[sq]
        for (d in 0 until 8) {
            for (t in rays[d]) {
                val p = b[t]
                if (p == 0) continue
                if (p == queen || p == (if (d < 4) rook else bishop)) return true
                break
            }
        }
        return false
    }

    fun inCheck(): Boolean {
        val k = kingSquare(stm)
        return k >= 0 && isAttacked(k, -stm)
    }

    /** True if the side that just moved left its king in check (i.e. last move was illegal). */
    fun leftKingInCheck(): Boolean {
        val k = kingSquare(-stm)
        return k >= 0 && isAttacked(k, stm)
    }

    // ---------------------------------------------------------------- move generation

    /** Generates pseudo-legal moves into [out], returns count. */
    fun generate(out: IntArray, capturesOnly: Boolean = false): Int {
        var n = 0
        val us = stm
        val b = board
        for (s in 0 until 64) {
            val p = b[s]
            if (p == 0 || (p > 0) != (us > 0)) continue
            when (if (p > 0) p else -p) {
                Piece.PAWN -> n = genPawn(s, out, n, capturesOnly)
                Piece.KNIGHT -> for (t in Tables.knight[s]) {
                    val q = b[t]
                    if (q == 0) { if (!capturesOnly) out[n++] = Move.of(s, t) }
                    else if ((q > 0) != (us > 0)) out[n++] = Move.of(s, t)
                }
                Piece.BISHOP -> n = genSlide(s, 4, 8, out, n, capturesOnly)
                Piece.ROOK -> n = genSlide(s, 0, 4, out, n, capturesOnly)
                Piece.QUEEN -> n = genSlide(s, 0, 8, out, n, capturesOnly)
                Piece.KING -> {
                    for (t in Tables.king[s]) {
                        val q = b[t]
                        if (q == 0) { if (!capturesOnly) out[n++] = Move.of(s, t) }
                        else if ((q > 0) != (us > 0)) out[n++] = Move.of(s, t)
                    }
                    if (!capturesOnly) n = genCastles(s, out, n)
                }
            }
        }
        return n
    }

    private fun genSlide(s: Int, d0: Int, d1: Int, out: IntArray, start: Int, capturesOnly: Boolean): Int {
        var n = start
        val us = stm
        for (d in d0 until d1) {
            for (t in Tables.rays[s][d]) {
                val q = board[t]
                if (q == 0) {
                    if (!capturesOnly) out[n++] = Move.of(s, t)
                    continue
                }
                if ((q > 0) != (us > 0)) out[n++] = Move.of(s, t)
                break
            }
        }
        return n
    }

    private fun genPawn(s: Int, out: IntArray, start: Int, capturesOnly: Boolean): Int {
        var n = start
        val us = stm
        val dir = if (us > 0) 8 else -8
        val startRank = if (us > 0) 1 else 6
        val promoRank = if (us > 0) 7 else 0
        val f = Sq.file(s)
        val one = s + dir
        if (one in 0..63 && board[one] == 0) {
            if (Sq.rank(one) == promoRank) {
                n = addPromos(s, one, out, n, capturesOnly)
            } else if (!capturesOnly) {
                out[n++] = Move.of(s, one)
                val two = one + dir
                if (Sq.rank(s) == startRank && board[two] == 0) out[n++] = Move.of(s, two, 0, Move.FLAG_DOUBLE)
            }
        }
        for (df in intArrayOf(-1, 1)) {
            if (f + df !in 0..7) continue
            val t = one + df
            if (t !in 0..63) continue
            val q = board[t]
            if (q != 0 && (q > 0) != (us > 0)) {
                if (Sq.rank(t) == promoRank) n = addPromos(s, t, out, n, false)
                else out[n++] = Move.of(s, t)
            } else if (t == ep && q == 0) {
                out[n++] = Move.of(s, t, 0, Move.FLAG_EP)
            }
        }
        return n
    }

    private fun addPromos(s: Int, t: Int, out: IntArray, start: Int, queenOnly: Boolean): Int {
        var n = start
        out[n++] = Move.of(s, t, Piece.QUEEN)
        if (!queenOnly) {
            out[n++] = Move.of(s, t, Piece.ROOK)
            out[n++] = Move.of(s, t, Piece.BISHOP)
            out[n++] = Move.of(s, t, Piece.KNIGHT)
        }
        return n
    }

    private fun genCastles(s: Int, out: IntArray, start: Int): Int {
        var n = start
        val us = stm
        val base = if (us > 0) 0 else 56
        if (s != base + 4) return n
        val kBit = if (us > 0) 1 else 4
        val qBit = if (us > 0) 2 else 8
        val rook = us * Piece.ROOK
        if (castling and kBit != 0 && board[base + 5] == 0 && board[base + 6] == 0 && board[base + 7] == rook &&
            !isAttacked(base + 4, -us) && !isAttacked(base + 5, -us) && !isAttacked(base + 6, -us)
        ) out[n++] = Move.of(s, base + 6, 0, Move.FLAG_CASTLE)
        if (castling and qBit != 0 && board[base + 3] == 0 && board[base + 2] == 0 && board[base + 1] == 0 &&
            board[base] == rook &&
            !isAttacked(base + 4, -us) && !isAttacked(base + 3, -us) && !isAttacked(base + 2, -us)
        ) out[n++] = Move.of(s, base + 2, 0, Move.FLAG_CASTLE)
        return n
    }

    fun legalMoves(): IntArray {
        val buf = IntArray(256)
        val n = generate(buf)
        val res = IntArray(n)
        var k = 0
        for (i in 0 until n) {
            make(buf[i])
            if (!leftKingInCheck()) res[k++] = buf[i]
            unmake()
        }
        return res.copyOf(k)
    }

    /** Finds a legal move by UCI string (e.g. "e2e4", "e7e8q"). */
    fun parseUci(uci: String): Int? {
        val u = uci.trim().lowercase()
        if (u.length < 4) return null
        val from = runCatching { Sq.parse(u.substring(0, 2)) }.getOrNull() ?: return null
        val to = runCatching { Sq.parse(u.substring(2, 4)) }.getOrNull() ?: return null
        val promo = if (u.length >= 5) (Piece.fromChar(u[4])?.let { if (it < 0) -it else it } ?: 0) else 0
        return legalMoves().firstOrNull { Move.from(it) == from && Move.to(it) == to && Move.promo(it) == promo }
    }

    // ---------------------------------------------------------------- make / unmake

    fun make(m: Int) {
        val from = Move.from(m)
        val to = Move.to(m)
        val promo = Move.promo(m)
        val flags = Move.flags(m)
        val moved = board[from]
        val us = stm
        val z = Tables.zPiece

        uMove[sp] = m; uMoved[sp] = moved; uCastle[sp] = castling; uEp[sp] = ep
        uHalf[sp] = halfmove; uHash[sp] = hash
        history[histLen++] = hash

        var h = hash
        var captured: Int
        if (flags and Move.FLAG_EP != 0) {
            val capSq = to - 8 * us
            captured = board[capSq]
            board[capSq] = 0
            h = h xor z[captured + 6][capSq]
        } else {
            captured = board[to]
            if (captured != 0) h = h xor z[captured + 6][to]
        }
        uCaptured[sp] = captured
        sp++

        val placed = if (promo != 0) us * promo else moved
        board[from] = 0
        board[to] = placed
        h = h xor z[moved + 6][from] xor z[placed + 6][to]

        if (flags and Move.FLAG_CASTLE != 0) {
            val rook = us * Piece.ROOK
            val (rf, rt) = if (to > from) (from + 3) to (from + 1) else (from - 4) to (from - 1)
            board[rf] = 0; board[rt] = rook
            h = h xor z[rook + 6][rf] xor z[rook + 6][rt]
        }
        if (moved == Piece.KING) kingSq[0] = to
        else if (moved == -Piece.KING) kingSq[1] = to

        val newCastle = castling and Tables.castleMask[from] and Tables.castleMask[to]
        h = h xor Tables.zCastle[castling] xor Tables.zCastle[newCastle]
        castling = newCastle

        if (ep >= 0) h = h xor Tables.zEp[Sq.file(ep)]
        ep = if (flags and Move.FLAG_DOUBLE != 0) (from + to) / 2 else -1
        if (ep >= 0) h = h xor Tables.zEp[Sq.file(ep)]

        halfmove = if (moved == Piece.PAWN || moved == -Piece.PAWN || captured != 0) 0 else halfmove + 1
        if (us < 0) fullmove++
        stm = -us
        h = h xor Tables.zSide
        hash = h
    }

    fun unmake() {
        sp--
        histLen--
        val m = uMove[sp]
        val from = Move.from(m)
        val to = Move.to(m)
        val flags = Move.flags(m)
        val moved = uMoved[sp]
        val captured = uCaptured[sp]
        stm = -stm
        val us = stm
        if (us < 0) fullmove--

        board[from] = moved
        if (flags and Move.FLAG_EP != 0) {
            board[to] = 0
            board[to - 8 * us] = captured
        } else {
            board[to] = captured
        }
        if (flags and Move.FLAG_CASTLE != 0) {
            val rook = us * Piece.ROOK
            val (rf, rt) = if (to > from) (from + 3) to (from + 1) else (from - 4) to (from - 1)
            board[rt] = 0; board[rf] = rook
        }
        if (moved == Piece.KING) kingSq[0] = from
        else if (moved == -Piece.KING) kingSq[1] = from
        castling = uCastle[sp]; ep = uEp[sp]; halfmove = uHalf[sp]; hash = uHash[sp]
    }

    fun makeNull() {
        uMove[sp] = 0; uMoved[sp] = 0; uCaptured[sp] = 0
        uCastle[sp] = castling; uEp[sp] = ep; uHalf[sp] = halfmove; uHash[sp] = hash
        history[histLen++] = hash
        sp++
        var h = hash
        if (ep >= 0) h = h xor Tables.zEp[Sq.file(ep)]
        ep = -1
        halfmove++
        stm = -stm
        hash = h xor Tables.zSide
    }

    fun unmakeNull() {
        sp--
        histLen--
        stm = -stm
        castling = uCastle[sp]; ep = uEp[sp]; halfmove = uHalf[sp]; hash = uHash[sp]
    }

    /** True if the current position occurred before within the reversible-move window. */
    fun isRepetition(): Boolean {
        var i = histLen - 2
        val stop = maxOf(0, histLen - halfmove)
        while (i >= stop) {
            if (history[i] == hash) return true
            i -= 2
        }
        return false
    }

    /** Commits current position as the new root (clears undo stack, keeps repetition history). */
    fun clearUndo() { sp = 0 }

    fun hasNonPawnMaterial(color: Int): Boolean {
        for (s in 0 until 64) {
            val p = board[s] * color
            if (p in Piece.KNIGHT..Piece.QUEEN) return true
        }
        return false
    }

    companion object {
        const val MAX_PLY = 1024
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun start() = fromFen(START_FEN)

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty()) { "empty FEN" }
            val pos = Position()
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN needs 8 ranks" }
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) f += c - '0'
                    else {
                        val p = Piece.fromChar(c) ?: throw IllegalArgumentException("bad FEN char $c")
                        require(f < 8) { "rank too long" }
                        pos.board[Sq.of(f, r)] = p
                        f++
                    }
                }
                require(f == 8) { "rank $r has $f files" }
            }
            pos.stm = if (parts.getOrNull(1) == "b") -1 else 1
            var c = 0
            parts.getOrNull(2)?.forEach {
                when (it) { 'K' -> c = c or 1; 'Q' -> c = c or 2; 'k' -> c = c or 4; 'q' -> c = c or 8 }
            }
            pos.castling = c
            pos.ep = parts.getOrNull(3)?.takeIf { it != "-" }?.let { runCatching { Sq.parse(it) }.getOrNull() } ?: -1
            pos.halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
            pos.fullmove = parts.getOrNull(5)?.toIntOrNull() ?: 1
            pos.sanitizeCastling()
            pos.setupDerived()
            return pos
        }

        /**
         * Builds a position from a board array (index = square), guessing castling rights from
         * king/rook placement. En-passant is unknown and left empty.
         */
        fun fromBoard(board: IntArray, stm: Int): Position {
            val pos = Position()
            board.copyInto(pos.board)
            pos.stm = if (stm >= 0) 1 else -1
            pos.castling = 15
            pos.sanitizeCastling()
            pos.setupDerived()
            return pos
        }
    }

    private fun sanitizeCastling() {
        var c = castling
        if (board[4] != Piece.KING) c = c and 3.inv()
        if (board[7] != Piece.ROOK) c = c and 1.inv()
        if (board[0] != Piece.ROOK) c = c and 2.inv()
        if (board[60] != -Piece.KING) c = c and 12.inv()
        if (board[63] != -Piece.ROOK) c = c and 4.inv()
        if (board[56] != -Piece.ROOK) c = c and 8.inv()
        castling = c
    }

    /** Basic sanity: exactly one king per side, no pawns on back ranks, side not to move not in check. */
    fun isPlausible(): Boolean {
        var wk = 0; var bk = 0
        for (s in 0 until 64) {
            val p = board[s]
            if (p == Piece.KING) wk++
            if (p == -Piece.KING) bk++
            if ((p == Piece.PAWN || p == -Piece.PAWN) && (Sq.rank(s) == 0 || Sq.rank(s) == 7)) return false
        }
        if (wk != 1 || bk != 1) return false
        return !leftKingInCheck()
    }
}
