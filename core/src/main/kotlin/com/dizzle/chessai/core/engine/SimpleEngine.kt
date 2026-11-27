package com.dizzle.chessai.core.engine

import com.dizzle.chessai.core.chess.Move
import com.dizzle.chessai.core.chess.Piece
import com.dizzle.chessai.core.chess.Position
import com.dizzle.chessai.core.chess.Sq

/**
 * Small built-in alpha-beta engine (PVS, transposition table, null move, LMR, quiescence,
 * tapered piece-square evaluation). Used as fallback when no Stockfish binary is available.
 */
class SimpleEngine(ttBits: Int = 19) : Engine {
    override val name = "ChessAI built-in"

    private val ttSize = 1 shl ttBits
    private val ttMask = (ttSize - 1).toLong()
    private val ttKey = LongArray(ttSize)
    private val ttMove = IntArray(ttSize)
    private val ttScore = IntArray(ttSize)
    private val ttDepth = ByteArray(ttSize)
    private val ttFlag = ByteArray(ttSize)

    private val killers = Array(MAX_DEPTH + 8) { IntArray(2) }
    private val historyTable = Array(13) { IntArray(64) }
    private val moveBufs = Array(MAX_DEPTH + 8) { IntArray(256) }
    private val scoreBufs = Array(MAX_DEPTH + 8) { IntArray(256) }

    private lateinit var pos: Position
    private var nodes = 0L
    private var deadline = 0L
    private var stopped = false

    @Synchronized
    override fun bestMove(fen: String, moveTimeMs: Long): EngineResult? {
        pos = Position.fromFen(fen)
        val legal = pos.legalMoves()
        if (legal.isEmpty()) return null
        if (legal.size == 1) return EngineResult(Move.uci(legal[0]), null, null, 0, name)

        for (k in killers) k.fill(0)
        for (h in historyTable) h.fill(0)
        nodes = 0
        stopped = false
        deadline = System.currentTimeMillis() + moveTimeMs.coerceAtLeast(50)

        var best = legal[0]
        var bestScore = 0
        var depthDone = 0
        for (depth in 1..MAX_DEPTH) {
            val score = search(depth, -INF, INF, 0, true)
            if (stopped) break
            val m = probeMove()
            if (m != 0) best = m
            bestScore = score
            depthDone = depth
            if (kotlin.math.abs(score) > MATE - 200) break
            // Not enough time left for another iteration (rough estimate: x3 branching)
            if (System.currentTimeMillis() + (System.currentTimeMillis() - (deadline - moveTimeMs)) * 2 > deadline) break
        }
        val mate = if (kotlin.math.abs(bestScore) > MATE - 200) {
            val plies = MATE - kotlin.math.abs(bestScore)
            val n = (plies + 1) / 2
            if (bestScore > 0) n else -n
        } else null
        return EngineResult(Move.uci(best), if (mate == null) bestScore else null, mate, depthDone, name)
    }

    private fun probeMove(): Int {
        val idx = (pos.hash and ttMask).toInt()
        return if (ttKey[idx] == pos.hash) ttMove[idx] else 0
    }

    private fun checkTime() {
        if (nodes and 2047L == 0L && System.currentTimeMillis() > deadline) stopped = true
    }

    private fun search(depthIn: Int, alphaIn: Int, beta: Int, ply: Int, allowNull: Boolean): Int {
        var alpha = alphaIn
        var depth = depthIn
        nodes++
        checkTime()
        if (stopped) return 0
        if (ply > 0 && (pos.halfmove >= 100 || pos.isRepetition())) return 0
        if (ply >= MAX_DEPTH) return evaluate()

        val inCheck = pos.inCheck()
        if (inCheck) depth++
        if (depth <= 0) return quiesce(alpha, beta, ply)

        val pvNode = beta - alpha > 1
        val idx = (pos.hash and ttMask).toInt()
        var ttM = 0
        if (ttKey[idx] == pos.hash) {
            ttM = ttMove[idx]
            if (ply > 0 && !pvNode && ttDepth[idx] >= depth) {
                val s = fromTt(ttScore[idx], ply)
                when (ttFlag[idx].toInt()) {
                    EXACT -> return s
                    LOWER -> if (s >= beta) return s
                    UPPER -> if (s <= alpha) return s
                }
            }
        }

        if (allowNull && !inCheck && !pvNode && depth >= 3 && ply > 0 && pos.hasNonPawnMaterial(pos.stm) &&
            evaluate() >= beta
        ) {
            pos.makeNull()
            val s = -search(depth - 3, -beta, -beta + 1, ply + 1, false)
            pos.unmakeNull()
            if (stopped) return 0
            if (s >= beta) return beta
        }

        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = pos.generate(moves)
        scoreMoves(moves, scores, n, ttM, ply)

        var legal = 0
        var bestScore = -INF
        var bestMove = 0
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            val isCapture = pos.board[Move.to(m)] != 0 || Move.flags(m) and Move.FLAG_EP != 0
            val isQuiet = !isCapture && Move.promo(m) == 0
            pos.make(m)
            if (pos.leftKingInCheck()) { pos.unmake(); continue }
            legal++
            var score: Int
            if (legal == 1) {
                score = -search(depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                var r = 0
                if (depth >= 3 && legal > 3 && isQuiet && !inCheck && !pos.inCheck()) {
                    r = if (legal > 8) 2 else 1
                }
                score = -search(depth - 1 - r, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && (r > 0 || score < beta)) {
                    score = -search(depth - 1, -beta, -alpha, ply + 1, true)
                }
            }
            pos.unmake()
            if (stopped) return 0
            if (score > bestScore) {
                bestScore = score
                bestMove = m
                if (score > alpha) {
                    alpha = score
                    if (alpha >= beta) {
                        if (isQuiet) {
                            val k = killers[ply]
                            if (k[0] != m) { k[1] = k[0]; k[0] = m }
                            val pc = pos.board[Move.from(m)] + 6
                            historyTable[pc][Move.to(m)] += depth * depth
                        }
                        break
                    }
                }
            }
        }
        if (legal == 0) return if (inCheck) -MATE + ply else 0

        val flag = when {
            bestScore >= beta -> LOWER
            bestScore > alphaIn -> EXACT
            else -> UPPER
        }
        if (ttKey[idx] != pos.hash || depth >= ttDepth[idx] || flag == EXACT) {
            ttKey[idx] = pos.hash
            ttMove[idx] = bestMove
            ttScore[idx] = toTt(bestScore, ply)
            ttDepth[idx] = depth.coerceAtMost(127).toByte()
            ttFlag[idx] = flag.toByte()
        }
        return bestScore
    }

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        var alpha = alphaIn
        nodes++
        checkTime()
        if (stopped) return 0
        val stand = evaluate()
        if (stand >= beta) return stand
        if (stand > alpha) alpha = stand
        if (ply >= MAX_DEPTH) return stand

        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = pos.generate(moves, capturesOnly = true)
        scoreMoves(moves, scores, n, 0, ply)
        var best = stand
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            pos.make(m)
            if (pos.leftKingInCheck()) { pos.unmake(); continue }
            val s = -quiesce(-beta, -alpha, ply + 1)
            pos.unmake()
            if (stopped) return 0
            if (s > best) {
                best = s
                if (s > alpha) {
                    alpha = s
                    if (s >= beta) break
                }
            }
        }
        return best
    }

    private fun scoreMoves(moves: IntArray, scores: IntArray, n: Int, ttM: Int, ply: Int) {
        val b = pos.board
        val k = killers[ply]
        for (i in 0 until n) {
            val m = moves[i]
            val victim = b[Move.to(m)].let { if (it < 0) -it else it }
            val attacker = b[Move.from(m)].let { if (it < 0) -it else it }
            scores[i] = when {
                m == ttM -> 2_000_000
                victim != 0 -> 1_000_000 + VALUE[victim] * 10 - VALUE[attacker] / 10
                Move.flags(m) and Move.FLAG_EP != 0 -> 1_000_000 + 1000 - 10
                Move.promo(m) != 0 -> 900_000 + Move.promo(m)
                m == k[0] -> 800_000
                m == k[1] -> 790_000
                else -> historyTable[b[Move.from(m)] + 6][Move.to(m)].coerceAtMost(700_000)
            }
        }
    }

    private fun pickNext(moves: IntArray, scores: IntArray, i: Int, n: Int) {
        var best = i
        for (j in i + 1 until n) if (scores[j] > scores[best]) best = j
        if (best != i) {
            val tm = moves[i]; moves[i] = moves[best]; moves[best] = tm
            val ts = scores[i]; scores[i] = scores[best]; scores[best] = ts
        }
    }

    private fun toTt(s: Int, ply: Int) = when {
        s > MATE - 1000 -> s + ply
        s < -MATE + 1000 -> s - ply
        else -> s
    }

    private fun fromTt(s: Int, ply: Int) = when {
        s > MATE - 1000 -> s - ply
        s < -MATE + 1000 -> s + ply
        else -> s
    }

    /** Static evaluation from the side to move's perspective. */
    fun evaluate(): Int {
        val b = pos.board
        var mg = 0
        var eg = 0
        var phase = 0
        var wBishops = 0
        var bBishops = 0
        for (s in 0 until 64) {
            val p = b[s]
            if (p == 0) continue
            val t = if (p > 0) p else -p
            val idx = if (p > 0) (7 - Sq.rank(s)) * 8 + Sq.file(s) else Sq.rank(s) * 8 + Sq.file(s)
            val sign = if (p > 0) 1 else -1
            val base = VALUE[t]
            when (t) {
                Piece.KING -> {
                    mg += sign * KING_MG[idx]
                    eg += sign * KING_EG[idx]
                }
                else -> {
                    val pst = PST[t][idx]
                    mg += sign * (base + pst)
                    eg += sign * (base + pst)
                    phase += PHASE[t]
                    if (t == Piece.BISHOP) { if (p > 0) wBishops++ else bBishops++ }
                }
            }
        }
        if (wBishops >= 2) { mg += 30; eg += 40 }
        if (bBishops >= 2) { mg -= 30; eg -= 40 }
        val ph = phase.coerceAtMost(24)
        val score = (mg * ph + eg * (24 - ph)) / 24
        return score * pos.stm + 10 // small tempo bonus
    }

    companion object {
        const val MAX_DEPTH = 64
        const val INF = 50_000
        const val MATE = 30_000
        private const val EXACT = 0
        private const val LOWER = 1
        private const val UPPER = 2

        private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)
        private val PHASE = intArrayOf(0, 0, 1, 1, 2, 4, 0)

        // Tables from white's point of view, index 0 = a8 ... 63 = h1
        private val PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        )
        private val BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        )
        private val ROOK = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        )
        private val QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        )
        private val KING_MG = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        )
        private val KING_EG = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50,
        )
        private val PST = arrayOf(IntArray(64), PAWN, KNIGHT, BISHOP, ROOK, QUEEN, IntArray(64))
    }
}
