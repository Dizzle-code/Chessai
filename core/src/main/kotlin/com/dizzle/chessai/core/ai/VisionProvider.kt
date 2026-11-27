package com.dizzle.chessai.core.ai

/** Result of an AI board read: 8 visual rows (top to bottom) + which color sits at the bottom. */
data class AiBoard(
    /** 64 pieces in visual order (row 0 = top, col 0 = left). Piece codes as in [com.dizzle.chessai.core.chess.Piece]. */
    val visual: IntArray,
    /** true if white pieces start at the bottom of the image (board not flipped). null = unknown. */
    val whiteAtBottom: Boolean?,
    val inputTokens: Long,
    val outputTokens: Long,
)

interface VisionProvider {
    val label: String
    /** Reads the board from a JPEG that shows exactly the 8x8 board. */
    fun readBoard(jpeg: ByteArray): AiBoard
}

object BoardPrompt {
    /** Deliberately tiny prompt: ~70 tokens in, ~80 tokens out. */
    const val TEXT =
        "Chessboard image (exactly 8x8 squares). Reply with exactly 9 lines, nothing else. " +
            "Lines 1-8: the rows from top to bottom as seen, each 8 chars left to right: " +
            "PNBRQK=white, pnbrqk=black, .=empty. Line 9: w if white pieces started at the bottom, else b."

    /** Parses the model output. Tolerates code fences, spaces, FEN-style digits and extra lines. */
    fun parse(text: String, inTok: Long, outTok: Long): AiBoard {
        val lines = text.lines().map { it.trim().trim('`').replace(" ", "") }.filter { it.isNotEmpty() }
        val rows = ArrayList<String>()
        var bottom: Boolean? = null
        for (l in lines) {
            val expanded = buildString {
                for (c in l) if (c.isDigit() && c in '1'..'8') repeat(c - '0') { append('.') } else append(c)
            }
            if (rows.size < 8 && expanded.length == 8 && expanded.all { it == '.' || it in "PNBRQKpnbrqk" || it == '-' }) {
                rows.add(expanded.replace('-', '.'))
            } else if (rows.size == 8 && bottom == null) {
                val lc = l.lowercase()
                if (lc.startsWith("w")) bottom = true else if (lc.startsWith("b")) bottom = false
            }
        }
        // Single-line FEN fallback
        if (rows.size < 8) {
            val fen = lines.firstOrNull { it.count { c -> c == '/' } == 7 }
            if (fen != null) {
                rows.clear()
                for (r in fen.substringBefore(' ').split('/')) {
                    rows.add(buildString { for (c in r) if (c.isDigit()) repeat(c - '0') { append('.') } else append(c) })
                }
                val after = lines.drop(lines.indexOf(fen) + 1).firstOrNull()?.lowercase()
                bottom = when {
                    after == null -> null
                    after.startsWith("w") -> true
                    after.startsWith("b") -> false
                    else -> null
                }
            }
        }
        require(rows.size == 8 && rows.all { it.length == 8 }) { "AI-Antwort nicht lesbar: ${text.take(120)}" }
        val visual = IntArray(64)
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = rows[r][c]
            visual[r * 8 + c] = if (ch == '.') 0 else com.dizzle.chessai.core.chess.Piece.fromChar(ch) ?: 0
        }
        return AiBoard(visual, bottom, inTok, outTok)
    }
}
