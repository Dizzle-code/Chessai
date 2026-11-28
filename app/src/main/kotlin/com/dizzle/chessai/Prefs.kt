package com.dizzle.chessai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dizzle.chessai.core.vision.BoardRect
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider { CHATGPT_LOGIN, CLAUDE, OPENAI, NONE }

/** App settings. API keys are encrypted with a key held in the Android Keystore. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("chessai", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() = runCatching { AiProvider.valueOf(sp.getString("provider", AiProvider.CHATGPT_LOGIN.name)!!) }.getOrDefault(AiProvider.CHATGPT_LOGIN)
        set(v) = sp.edit().putString("provider", v.name).apply()

    var claudeKey: String
        get() = SecureStore.decrypt(sp.getString("claude_key", null)) ?: ""
        set(v) = sp.edit().putString("claude_key", SecureStore.encrypt(v.trim())).apply()

    var openAiKey: String
        get() = SecureStore.decrypt(sp.getString("openai_key", null)) ?: ""
        set(v) = sp.edit().putString("openai_key", SecureStore.encrypt(v.trim())).apply()

    /** "Sign in with ChatGPT" tokens (encrypted). */
    var chatGptTokens: com.dizzle.chessai.core.ai.OAuthTokens?
        get() = com.dizzle.chessai.core.ai.OAuthTokens.decode(SecureStore.decrypt(sp.getString("chatgpt_tokens", null)))
        set(v) = sp.edit().putString("chatgpt_tokens", v?.let { SecureStore.encrypt(it.encode()) }).apply()

    /** Client id that ChatGPT issued to this installation at the first sign-in (kept after logout). */
    var chatGptIssuedClientId: String
        get() = sp.getString("chatgpt_issued_client_id", "") ?: ""
        set(v) = sp.edit().putString("chatgpt_issued_client_id", v).apply()

    /** Stable per-installation id sent as ext_agent_host_id. */
    val hostId: String
        get() = sp.getString("host_id", null) ?: ("urn:uuid:" + java.util.UUID.randomUUID()).also {
            sp.edit().putString("host_id", it).apply()
        }

    /** Models offered by the signed-in ChatGPT plan (comma separated). */
    var chatGptModels: List<String>
        get() = sp.getString("chatgpt_models", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("chatgpt_models", v.joinToString(",")).apply()

    var showAdvanced: Boolean
        get() = sp.getBoolean("show_advanced", false)
        set(v) = sp.edit().putBoolean("show_advanced", v).apply()

    var claudeModel: String
        get() = sp.getString("claude_model", null)?.takeIf { it.isNotBlank() } ?: com.dizzle.chessai.core.ai.ClaudeVision.DEFAULT_MODEL
        set(v) = sp.edit().putString("claude_model", v.trim()).apply()

    var openAiModel: String
        get() = sp.getString("openai_model", null)?.takeIf { it.isNotBlank() } ?: com.dizzle.chessai.core.ai.OpenAiVision.DEFAULT_MODEL
        set(v) = sp.edit().putString("openai_model", v.trim()).apply()

    /** 0 = auto (bottom color), 1 = white, -1 = black */
    var myColor: Int
        get() = sp.getInt("my_color", 0)
        set(v) = sp.edit().putInt("my_color", v).apply()

    var thinkMs: Long
        get() = sp.getLong("think_ms", 1500)
        set(v) = sp.edit().putLong("think_ms", v).apply()

    var useStockfish: Boolean
        get() = sp.getBoolean("use_stockfish", true)
        set(v) = sp.edit().putBoolean("use_stockfish", v).apply()

    var themeId: ThemeId
        get() = runCatching { ThemeId.valueOf(sp.getString("theme", ThemeId.PIXEL.name)!!) }.getOrDefault(ThemeId.PIXEL)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Perform the suggested move as taps via the accessibility service. */
    var autoPlay: Boolean
        get() = sp.getBoolean("auto_play", false)
        set(v) = sp.edit().putBoolean("auto_play", v).apply()

    var autoMode: Boolean
        get() = sp.getBoolean("auto_mode", true)
        set(v) = sp.edit().putBoolean("auto_mode", v).apply()

    var manualRect: BoardRect?
        get() = BoardRect.decode(sp.getString("manual_rect", null))
        set(v) = sp.edit().putString("manual_rect", v?.encode()).apply()

    var totalTokens: Long
        get() = sp.getLong("total_tokens", 0)
        set(v) = sp.edit().putLong("total_tokens", v).apply()

    var aiCalls: Int
        get() = sp.getInt("ai_calls", 0)
        set(v) = sp.edit().putInt("ai_calls", v).apply()

    var localHits: Int
        get() = sp.getInt("local_hits", 0)
        set(v) = sp.edit().putInt("local_hits", v).apply()

    var buttonX: Int
        get() = sp.getInt("btn_x", -1)
        set(v) = sp.edit().putInt("btn_x", v).apply()

    var buttonY: Int
        get() = sp.getInt("btn_y", -1)
        set(v) = sp.edit().putInt("btn_y", v).apply()
}

internal object SecureStore {
    private const val ALIAS = "chessai_api_keys"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return runCatching {
            val (iv, data) = stored.split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
}
