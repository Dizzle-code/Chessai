package com.dizzle.chessai.core

import com.dizzle.chessai.core.ai.VisionProvider
import com.dizzle.chessai.core.chess.Move
import com.dizzle.chessai.core.chess.Piece
import com.dizzle.chessai.core.chess.Position
import com.dizzle.chessai.core.engine.Engine
import com.dizzle.chessai.core.engine.EngineResult
import com.dizzle.chessai.core.vision.BoardLocator
import com.dizzle.chessai.core.vision.BoardRect
import com.dizzle.chessai.core.vision.PieceLibrary
import com.dizzle.chessai.core.vision.RgbImage
import com.dizzle.chessai.core.vision.SquareFeature
import com.dizzle.chessai.core.vision.SquareFeatures

/**
 * Screenshot -> position -> best move.
 *
 * Token strategy (cheapest first):
 *  1. Track the game locally: find the legal 0/1/2-ply continuation of the last known position that
 *     best matches the learned piece templates (0 tokens).
 *  2. Classify every square with the learned templates (0 tokens).
 *  3. Detect the start position without any templates (0 tokens) and learn the pieces from it.
 *  4. Only if all of that fails: ask the AI once, then learn the pieces from its answer.
 */
class Analyzer(
    val library: PieceLibrary,
    /** Crops [BoardRect] out of the image, scales it to size x size and encodes it as JPEG. */
    private val jpegEncoder: (RgbImage, BoardRect, Int) -> ByteArray,
) {
    enum class Source { TRACKED, LOCAL, START, AI }

    data class Options(
        /** 1 = I play white, -1 = black, 0 = auto (color at the bottom of the board). */
        val myColor: Int = 0,
        val thinkMs: Long = 1000,
        val manualRect: BoardRect? = null,
        val vision: VisionProvider? = null,
        val engine: Engine,
        val forceAi: Boolean = false,
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val rect: BoardRect? = null,
        /** Visual square indices (row*8+col, row 0 = top) of the suggested move, -1 if none. */
        val fromVisual: Int = -1,
        val toVisual: Int = -1,
        val fen: String? = null,
        val source: Source? = null,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val engine: EngineResult? = null,
        val myTurn: Boolean = false,
    )

    @Volatile var lastRect: BoardRect? = null
        private set
    @Volatile var whiteBottom: Boolean? = null
        private set
    private var lastPos: Position? = null
    @Volatile var totalTokens = 0L

    @Synchronized
    fun resetGame() {
        lastPos = null
        whiteBottom = null
    }

    @Synchronized
    fun forgetRect() { lastRect = null }

    // ------------------------------------------------------------------ board mapping

    private fun boardSq(visual: Int, wb: Boolean): Int {
        val r = visual / 8; val c = visual % 8
        return if (wb) (7 - r) * 8 + c else r * 8 + (7 - c)
    }

    fun visualIndex(sq: Int, wb: Boolean): Int {
        val rank = sq shr 3; val file = sq and 7
        return if (wb) (7 - rank) * 8 + file else rank * 8 + (7 - file)
    }

    private fun toBoard(visual: IntArray, wb: Boolean) = IntArray(64).also { b ->
        for (v in 0 until 64) b[boardSq(v, wb)] = visual[v]
    }

    private fun toVisual(pos: Position, wb: Boolean) = IntArray(64) { v -> pos.board[boardSq(v, wb)] }

    // ------------------------------------------------------------------ public API

    /** Finds (or re-validates) the board rectangle. */
    @Synchronized
    fun findRect(img: RgbImage, manual: BoardRect?): BoardRect? {
        if (manual != null) return manual.also { lastRect = it }
        lastRect?.let { if (BoardLocator.verify(img, it) > 30) return it }
        return BoardLocator.locate(img)?.also { lastRect = it }
    }

    /** Cheap per-square features for change detection (auto mode). */
    fun features(img: RgbImage, manual: BoardRect?): Pair<BoardRect, Array<SquareFeature>>? {
        val rect = findRect(img, manual) ?: return null
        return rect to SquareFeatures.extract(img, rect)
    }

    @Synchronized
    fun analyze(img: RgbImage, opt: Options): Result {
        val rect = findRect(img, opt.manualRect)
            ?: return Result(false, noBoardMessage(img))
        val feats = SquareFeatures.extract(img, rect)

        var pos: Position? = null
        var source: Source? = null
        var inTok = 0L
        var outTok = 0L

        // 1 + 2: local recognition with learned templates
        if (!opt.forceAi && library.size > 0) {
            val dist = library.distances(feats)
            val prev = lastPos
            val wb = whiteBottom
            if (prev != null && wb != null) {
                track(prev, dist, wb)?.let { p ->
                    pos = p; source = Source.TRACKED
                    learnConfident(feats, toVisual(p, wb), dist)
                }
            }
            if (pos == null) {
                classify(dist)?.let { labels ->
                    val wb2 = orientation(labels, opt.myColor)
                    val board = toBoard(labels, wb2)
                    val p = if (board.contentEquals(Position.start().board)) Position.start()
                    else Position.fromBoard(board, myColor(opt.myColor, wb2))
                    if (p.isPlausible()) { pos = p; whiteBottom = wb2; source = Source.LOCAL }
                }
            }
        }

        // 3: start position (needs no templates)
        if (pos == null && !opt.forceAi) {
            // initial position or one of the first moves (learns the piece design without AI)
            val orientation = if (opt.myColor != 0) opt.myColor > 0 else null
            val opening = detectStart(feats, opt.myColor)?.let { Openings.Match(Position.start(), it) }
                ?: Openings.signatureOf(feats)?.let { Openings.match(it, orientation) }
            opening?.let { m ->
                pos = m.position; whiteBottom = m.whiteBottom; source = Source.START
                library.learn(feats, toVisual(m.position, m.whiteBottom))
            }
        }

        // 4: AI
        if (pos == null) {
            val vision = opt.vision
                ?: return Result(false, "Stellung lokal nicht erkannt und keine AI eingerichtet", rect)
            val jpeg = jpegEncoder(img, rect, AI_IMAGE_SIZE)
            val ai = vision.readBoard(jpeg)
            inTok = ai.inputTokens; outTok = ai.outputTokens
            totalTokens += inTok + outTok
            val wb = when {
                opt.myColor > 0 -> true
                opt.myColor < 0 -> false
                ai.whiteAtBottom != null -> ai.whiteAtBottom
                else -> orientation(ai.visual, 0)
            }
            val board = toBoard(ai.visual, wb)
            var p = if (board.contentEquals(Position.start().board)) Position.start()
            else Position.fromBoard(board, myColor(opt.myColor, wb))
            if (!p.isPlausible()) {
                // maybe it is actually the other side's turn
                val alt = Position.fromBoard(board, -p.stm)
                if (alt.isPlausible()) p = alt
                else return Result(false, "AI-Stellung ungültig (${p.placement()})", rect, inputTokens = inTok, outputTokens = outTok)
            }
            // Theme changed? Drop templates that disagree with the AI.
            if (library.size > 0) {
                val dist = library.distances(feats)
                var conflicts = 0
                for (v in 0 until 64) {
                    val best = (0 until 13).minByOrNull { dist[v][it] }!! - 6
                    if (dist[v][best + 6] < 15f && best != ai.visual[v]) conflicts++
                }
                if (conflicts > 6) library.clear()
            }
            library.learn(feats, ai.visual)
            pos = p; whiteBottom = wb; source = Source.AI
        }

        val p = pos!!
        val wb = whiteBottom!!
        lastPos = p.copy()
        val me = myColor(opt.myColor, wb)
        val base = Result(true, "", rect, fen = p.toFen(), source = source, inputTokens = inTok, outputTokens = outTok)
        if (p.stm != me) return base.copy(message = "Gegner ist am Zug", myTurn = false)
        if (p.legalMoves().isEmpty()) {
            return base.copy(message = if (p.inCheck()) "Schachmatt" else "Patt", myTurn = true)
        }
        val er = opt.engine.bestMove(p.toFen(), opt.thinkMs)
            ?: return base.copy(message = "Kein Zug gefunden", myTurn = true)
        val mv = p.parseUci(er.bestMove)
            ?: return base.copy(ok = false, message = "Engine-Zug ungültig: ${er.bestMove}", myTurn = true)
        val eval = when {
            er.mateIn != null -> "M${er.mateIn}"
            er.scoreCp != null -> String.format(java.util.Locale.US, "%+.1f", er.scoreCp / 100.0)
            else -> ""
        }
        val src = when (source) {
            Source.TRACKED, Source.LOCAL, Source.START -> "lokal, 0 Tokens"
            Source.AI -> "${opt.vision?.label}: ${inTok + outTok} Tokens"
            null -> ""
        }
        return base.copy(
            message = "${er.bestMove} $eval ($src)".replace("  ", " "),
            fromVisual = visualIndex(Move.from(mv), wb),
            toVisual = visualIndex(Move.to(mv), wb),
            engine = er,
            myTurn = true,
        )
    }

    /** Explains why no board was found: blank/black capture vs. board just not detected. */
    private fun noBoardMessage(img: RgbImage): String {
        var min = 255; var max = 0; var sum = 0L; var n = 0
        val step = maxOf(1, img.px.size / 20_000)
        var i = 0
        while (i < img.px.size) {
            val c = img.px[i]
            val l = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
            if (l < min) min = l; if (l > max) max = l; sum += l; n++
            i += step
        }
        return if (max - min < 12 || sum / n < 8) {
            "Aufnahme ist leer/schwarz – beim Start „Gesamten Bildschirm“ freigeben (nicht nur eine App). " +
                "Manche Apps blockieren Bildschirmaufnahmen."
        } else {
            "Kein Schachbrett gefunden – Brett ganz sichtbar? Sonst lange auf ♞ drücken → „Brett manuell festlegen“"
        }
    }

    private fun myColor(pref: Int, wb: Boolean) = if (pref != 0) pref else if (wb) 1 else -1

    // ------------------------------------------------------------------ local recognition

    /**
     * Finds the legal continuation (0, 1 or 2 plies) of [prev] that best explains the observed
     * square distances. Returns null if nothing matches well or the match is ambiguous.
     */
    private fun track(prev: Position, distVisual: Array<FloatArray>, wb: Boolean): Position? {
        val d = Array(64) { sq -> distVisual[visualIndex(sq, wb)] }
        val known = library.knownLabels()
        fun cost(p: Position): Float {
            var s = 0f
            for (sq in 0 until 64) s += d[sq][p.board[sq] + 6]
            return s
        }

        val root = prev.copy()
        var best = Float.MAX_VALUE
        var bestBoard: IntArray? = null
        var bestPath = IntArray(0)
        var second = Float.MAX_VALUE
        fun consider(c: Float, path: IntArray) {
            if (c < best) {
                if (bestBoard != null && !root.board.contentEquals(bestBoard!!)) second = minOf(second, best)
                best = c; bestBoard = root.board.copyOf(); bestPath = path
            } else if (c < second && !root.board.contentEquals(bestBoard!!)) {
                second = c
            }
        }
        consider(cost(root), IntArray(0))
        for (m1 in root.legalMoves()) {
            root.make(m1)
            consider(cost(root), intArrayOf(m1))
            for (m2 in root.legalMoves()) {
                root.make(m2)
                consider(cost(root), intArrayOf(m1, m2))
                root.unmake()
            }
            root.unmake()
        }
        val bb = bestBoard ?: return null
        val candidate = prev.copy().also { p -> for (m in bestPath) p.make(m) }
        // Apps highlight a king in check (red glow etc.) – be lenient on that square.
        val checkedKing = if (candidate.inCheck()) candidate.kingSquare(candidate.stm) else -1
        // quality checks
        var worst = 0f
        for (sq in 0 until 64) {
            val label = bb[sq]
            if (label != 0 && label !in known) continue
            val dist = d[sq][label + 6]
            worst = maxOf(worst, if (sq == checkedKing) dist * 0.5f else dist)
        }
        if (worst > TRACK_SQUARE_MAX) return null
        if (best / 64 > TRACK_AVG_MAX) return null
        if (second - best < TRACK_MARGIN) return null
        val result = prev.copy()
        for (m in bestPath) result.make(m)
        result.clearUndo()
        return Position.fromFen(result.toFen())
    }

    /** Independent per-square classification; null unless every square is confident. */
    private fun classify(dist: Array<FloatArray>): IntArray? {
        val labels = IntArray(64)
        for (v in 0 until 64) {
            var b = 0; var bd = Float.MAX_VALUE; var sd = Float.MAX_VALUE
            for (i in 0 until 13) {
                val x = dist[v][i]
                if (x < bd) { sd = bd; bd = x; b = i } else if (x < sd) sd = x
            }
            if (bd > CLASSIFY_MAX || sd - bd < CLASSIFY_MARGIN) return null
            labels[v] = b - 6
        }
        return labels
    }

    private fun learnConfident(feats: Array<SquareFeature>, labels: IntArray, dist: Array<FloatArray>) {
        val l = IntArray(64) { v -> if (labels[v] != 0 && dist[v][labels[v] + 6] < 25f) labels[v] else 0 }
        library.learn(feats, l)
    }

    /** true = white at bottom. Uses preference, else where the white pieces are. */
    private fun orientation(visual: IntArray, pref: Int): Boolean {
        if (pref != 0) return pref > 0
        var wSum = 0.0; var wN = 0; var bSum = 0.0; var bN = 0
        var wKingRow = -1; var bKingRow = -1
        for (v in 0 until 64) {
            val p = visual[v]
            if (p > 0) { wSum += v / 8; wN++ }
            if (p < 0) { bSum += v / 8; bN++ }
            if (p == Piece.KING) wKingRow = v / 8
            if (p == -Piece.KING) bKingRow = v / 8
        }
        if (wKingRow >= 0 && bKingRow >= 0 && wKingRow != bKingRow &&
            (wKingRow >= 6 || bKingRow <= 1 || wKingRow <= 1 || bKingRow >= 6)
        ) return wKingRow > bKingRow
        if (wN == 0 || bN == 0) return whiteBottom ?: true
        return wSum / wN >= bSum / bN
    }

    /** Detects the initial position purely from occupancy; returns whiteBottom or null. */
    private fun detectStart(f: Array<SquareFeature>, pref: Int): Boolean? {
        for (v in 0 until 64) {
            val row = v / 8
            val occupied = f[v].fgFraction > 0.05f
            val shouldBe = row <= 1 || row >= 6
            if (occupied != shouldBe) return null
            if (!shouldBe && f[v].fgFraction > 0.02f) return null
        }
        // pawns look alike, rooks look alike
        for (row in intArrayOf(1, 6)) for (c in 1 until 8) {
            if (SquareFeatures.distance(f[row * 8], f[row * 8 + c]) > 15f) return null
        }
        for (row in intArrayOf(0, 7)) {
            if (SquareFeatures.distance(f[row * 8], f[row * 8 + 7]) > 15f) return null
            if (SquareFeatures.distance(f[row * 8], f[row * 8 + 3]) < 4f) return null // a rook is not a queen/king
        }
        if (pref != 0) return pref > 0
        fun meanLuma(rows: IntArray): Double {
            var s = 0.0; var n = 0
            for (r in rows) for (c in 0 until 8) {
                val fe = f[r * 8 + c]
                for (i in fe.cells.indices) { val x = fe.v(i); if (x != 0) { s += x; n++ } }
            }
            return if (n == 0) 0.0 else s / n
        }
        return meanLuma(intArrayOf(6, 7)) > meanLuma(intArrayOf(0, 1))
    }

    companion object {
        const val AI_IMAGE_SIZE = 448
        const val TRACK_SQUARE_MAX = 32f
        const val TRACK_AVG_MAX = 8f
        const val TRACK_MARGIN = 20f
        const val CLASSIFY_MAX = 20f
        const val CLASSIFY_MARGIN = 6f

        /** Number of squares that look different between two feature sets (ignoring [ignore]). */
        fun changedSquares(a: Array<SquareFeature>, b: Array<SquareFeature>, ignore: Set<Int> = emptySet()): Int {
            var n = 0
            for (i in 0 until 64) {
                if (i in ignore) continue
                if (SquareFeatures.distance(a[i], b[i]) > 10f) n++
            }
            return n
        }
    }
}
