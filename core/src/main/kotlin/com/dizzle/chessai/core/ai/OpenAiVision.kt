package com.dizzle.chessai.core.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Board recognition via the OpenAI Chat Completions API (plain HTTPS).
 * Token saving: image sent with detail=low (fixed small token cost), tiny prompt.
 */
class OpenAiVision(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
) : VisionProvider {
    override val label = "OpenAI ($model)"
    private val mapper = ObjectMapper()

    override fun readBoard(jpeg: ByteArray): AiBoard {
        val f = JsonNodeFactory.instance
        val body = f.objectNode().apply {
            put("model", model)
            if (isReasoningModel(model)) {
                put("max_completion_tokens", 4000)
                put("reasoning_effort", "low")
            } else {
                put("max_tokens", 300)
            }
            putArray("messages").addObject().apply {
                put("role", "user")
                putArray("content").apply {
                    addObject().put("type", "text").put("text", BoardPrompt.TEXT)
                    addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
                        .put("detail", "low")
                }
            }
        }
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(mapper.writeValueAsBytes(body)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { mapper.readTree(resp).path("error").path("message").asText() }.getOrNull()
            throw IllegalStateException("OpenAI Fehler $code: ${msg?.takeIf { it.isNotBlank() } ?: resp.take(200)}")
        }
        val json = mapper.readTree(resp)
        val text = json.path("choices").path(0).path("message").path("content").asText("")
        val usage = json.path("usage")
        return BoardPrompt.parse(text, usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0))
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"
        fun isReasoningModel(m: String) = m.startsWith("gpt-5") || m.startsWith("o")
    }
}
