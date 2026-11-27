package com.dizzle.chessai.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration
import java.util.Base64

/**
 * Board recognition via the Claude API (official Anthropic Java SDK).
 * Token saving: one small JPEG (~450x450 => ~270 image tokens), a ~70 token prompt, low effort.
 */
class ClaudeVision(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    baseUrl: String? = null,
) : VisionProvider {
    override val label = "Claude ($model)"

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(90))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override fun readBoard(jpeg: ByteArray): AiBoard {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addUserMessageOfContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofImage(
                        BetaImageBlockParam.builder()
                            .source(
                                BetaBase64ImageSource.builder()
                                    .data(b64)
                                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                    .build()
                            )
                            .build()
                    ),
                    BetaContentBlockParam.ofText(BoardPrompt.TEXT),
                )
            )
        if (supportsServerFallback(model)) {
            // Server-side refusal fallback (routes by refusal category, no model list to maintain).
            builder.addBeta(FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val msg = client.beta().messages().create(builder.build())
        if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw IllegalStateException("Claude hat die Anfrage abgelehnt")
        }
        val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n")
        val usage = msg.usage()
        return BoardPrompt.parse(text, usage.inputTokens(), usage.outputTokens())
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        fun supportsServerFallback(model: String) = model.startsWith("claude-opus-5") ||
            model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-fable-5")
    }
}
