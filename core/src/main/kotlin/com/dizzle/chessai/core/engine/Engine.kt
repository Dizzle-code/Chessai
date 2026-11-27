package com.dizzle.chessai.core.engine

data class EngineResult(
    /** Best move in UCI notation, e.g. "e2e4". */
    val bestMove: String,
    /** Score in centipawns from the side to move's point of view (null if mate score given). */
    val scoreCp: Int?,
    /** Mate in N (positive = side to move mates), null if not a mate score. */
    val mateIn: Int?,
    val depth: Int,
    val engineName: String,
)

interface Engine : AutoCloseable {
    val name: String
    /** Returns the best move for the given FEN or null if there is no legal move. */
    fun bestMove(fen: String, moveTimeMs: Long): EngineResult?
    override fun close() {}
}
