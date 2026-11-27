package com.dizzle.chessai.core.engine

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Talks UCI to an external engine binary (Stockfish). The process is kept alive between calls.
 */
class UciEngine(
    private val binary: File,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    private val hashMb: Int = 64,
) : Engine {
    override var name: String = "Stockfish"
        private set

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        process = p
        reader = BufferedReader(InputStreamReader(p.inputStream))
        writer = OutputStreamWriter(p.outputStream)
        send("uci")
        readUntil(5_000) { line ->
            if (line.startsWith("id name ")) name = line.removePrefix("id name ").trim()
            line == "uciok"
        }
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("isready")
        readUntil(10_000) { it == "readyok" }
    }

    private fun send(cmd: String) {
        val w = writer ?: error("engine not started")
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    private fun readUntil(timeoutMs: Long, onLine: (String) -> Boolean) {
        val r = reader ?: error("engine not started")
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val line = r.readLine() ?: throw IllegalStateException("engine closed the stream")
            if (onLine(line.trim())) return
            if (System.currentTimeMillis() > end) throw IllegalStateException("engine timeout")
        }
    }

    @Synchronized
    override fun bestMove(fen: String, moveTimeMs: Long): EngineResult? {
        ensureStarted()
        send("ucinewgame")
        send("position fen $fen")
        send("go movetime $moveTimeMs")
        var best: String? = null
        var cp: Int? = null
        var mate: Int? = null
        var depth = 0
        readUntil(moveTimeMs + 15_000) { line ->
            if (line.startsWith("info ") && " pv " in line) {
                val tok = line.split(' ')
                val di = tok.indexOf("depth")
                if (di >= 0) depth = tok.getOrNull(di + 1)?.toIntOrNull() ?: depth
                val si = tok.indexOf("score")
                if (si >= 0) {
                    when (tok.getOrNull(si + 1)) {
                        "cp" -> { cp = tok.getOrNull(si + 2)?.toIntOrNull(); mate = null }
                        "mate" -> { mate = tok.getOrNull(si + 2)?.toIntOrNull(); cp = null }
                    }
                }
            }
            if (line.startsWith("bestmove")) {
                best = line.split(' ').getOrNull(1)
                true
            } else false
        }
        val mv = best ?: return null
        if (mv == "(none)" || mv == "0000") return null
        return EngineResult(mv, cp, mate, depth, name)
    }

    @Synchronized
    override fun close() {
        runCatching { send("quit") }
        process?.let { p ->
            runCatching { p.waitFor(500, TimeUnit.MILLISECONDS) }
            if (p.isAlive) p.destroy()
        }
        process = null
    }
}
