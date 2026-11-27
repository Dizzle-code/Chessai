package com.dizzle.chessai

import android.content.Context
import android.graphics.Bitmap
import com.dizzle.chessai.core.ai.ClaudeVision
import com.dizzle.chessai.core.ai.OpenAiVision
import com.dizzle.chessai.core.ai.VisionProvider
import com.dizzle.chessai.core.engine.Engine
import com.dizzle.chessai.core.engine.EngineResult
import com.dizzle.chessai.core.engine.SimpleEngine
import com.dizzle.chessai.core.engine.UciEngine
import com.dizzle.chessai.core.vision.BoardRect
import com.dizzle.chessai.core.vision.PieceLibrary
import com.dizzle.chessai.core.vision.RgbImage
import java.io.ByteArrayOutputStream
import java.io.File

object Engines {
    fun stockfishBinary(ctx: Context): File? =
        File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so").takeIf { it.exists() }

    fun create(ctx: Context, prefs: Prefs): Engine {
        val sf = stockfishBinary(ctx)
        return if (sf != null && prefs.useStockfish) FallbackEngine(UciEngine(sf), SimpleEngine()) else SimpleEngine()
    }
}

/** Uses [primary] (Stockfish) and permanently switches to [fallback] if it ever fails. */
class FallbackEngine(private val primary: Engine, private val fallback: Engine) : Engine {
    private var failed = false
    override val name: String get() = if (failed) fallback.name else primary.name

    override fun bestMove(fen: String, moveTimeMs: Long): EngineResult? {
        if (!failed) {
            try {
                return primary.bestMove(fen, moveTimeMs)
            } catch (e: Exception) {
                failed = true
                runCatching { primary.close() }
            }
        }
        return fallback.bestMove(fen, moveTimeMs)
    }

    override fun close() {
        runCatching { primary.close() }
        fallback.close()
    }
}

object Vision {
    private var cacheKey: String? = null
    private var cached: VisionProvider? = null

    @Synchronized
    fun provider(prefs: Prefs): VisionProvider? {
        val (key, factory) = when (prefs.provider) {
            AiProvider.CLAUDE -> {
                val k = prefs.claudeKey
                if (k.isBlank()) return null
                val m = prefs.claudeModel
                "c|$k|$m" to { ClaudeVision(k, m) as VisionProvider }
            }
            AiProvider.OPENAI -> {
                val k = prefs.openAiKey
                if (k.isBlank()) return null
                val m = prefs.openAiModel
                "o|$k|$m" to { OpenAiVision(k, m) as VisionProvider }
            }
            AiProvider.NONE -> return null
        }
        if (key != cacheKey) {
            cached = factory()
            cacheKey = key
        }
        return cached
    }
}

object PieceStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "pieces.bin")

    fun load(ctx: Context): PieceLibrary {
        val lib = PieceLibrary()
        runCatching { file(ctx).takeIf { it.exists() }?.inputStream()?.use { lib.read(it) } }
        return lib
    }

    fun save(ctx: Context, lib: PieceLibrary) {
        runCatching {
            val tmp = File(ctx.filesDir, "pieces.tmp")
            tmp.outputStream().use { lib.write(it) }
            tmp.renameTo(file(ctx))
        }
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}

/** Crops the board, scales it to size x size and encodes a JPEG for the AI request. */
fun encodeBoardJpeg(img: RgbImage, r: BoardRect, size: Int): ByteArray {
    val x0 = r.x.coerceIn(0, img.width - 1)
    val y0 = r.y.coerceIn(0, img.height - 1)
    val s = minOf(r.size, img.width - x0, img.height - y0)
    val crop = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    crop.setPixels(img.px, y0 * img.width + x0, img.width, 0, 0, s, s)
    val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (scaled !== crop) scaled.recycle()
    crop.recycle()
    return out.toByteArray()
}
