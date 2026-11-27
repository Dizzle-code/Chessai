package com.dizzle.chessai.core

import com.dizzle.chessai.core.ai.ClaudeVision
import com.dizzle.chessai.core.ai.OpenAiVision
import com.dizzle.chessai.core.chess.Piece
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises both AI clients against a local fake server (no real API calls). */
class AiClientTest {
    private val board = "rnbqkbnr\npppppppp\n........\n........\n....P...\n........\nPPPP.PPP\nRNBQKBNR\nw"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastBody = ""
    private var lastHeaders = mapOf<String, String>()

    init {
        server.createContext("/") { ex ->
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            lastHeaders = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val m = ObjectMapper()
            val resp = if (ex.requestURI.path.contains("chat/completions")) {
                m.createObjectNode().apply {
                    putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", board)
                    putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 40)
                }
            } else {
                m.createObjectNode().apply {
                    put("id", "msg_1"); put("type", "message"); put("role", "assistant"); put("model", "claude-opus-5-5")
                    putArray("content").apply {
                        addObject().put("type", "thinking").put("thinking", "").put("signature", "sig")
                        addObject().put("type", "text").put("text", board)
                    }
                    put("stop_reason", "end_turn"); putNull("stop_sequence")
                    putObject("usage").put("input_tokens", 340).put("output_tokens", 95)
                }
            }
            val bytes = m.writeValueAsBytes(resp)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Test fun claude() {
        val v = ClaudeVision("test-key", "claude-opus-5-5", baseUrl = url)
        val r = v.readBoard(byteArrayOf(1, 2, 3))
        assertEquals(true, r.whiteAtBottom)
        assertEquals(Piece.PAWN, r.visual[4 * 8 + 4])
        assertEquals(340, r.inputTokens)
        val body = ObjectMapper().readTree(lastBody)
        assertEquals("claude-opus-5-5", body.path("model").asText())
        assertEquals("low", body.path("output_config").path("effort").asText())
        assertEquals("default", body.path("fallbacks").asText())
        assertEquals("image/jpeg", body.path("messages").path(0).path("content").path(0).path("source").path("media_type").asText())
        assertTrue(lastHeaders["anthropic-beta"]!!.contains("server-side-fallback-2026-07-01"))
        assertTrue(lastHeaders["x-api-key"] == "test-key" || lastHeaders["authorization"] == "Bearer test-key")
    }

    @Test fun claudeHaikuHasNoFallback() {
        ClaudeVision("k", "claude-haiku-5-5", baseUrl = url).readBoard(byteArrayOf(1))
        assertTrue(ObjectMapper().readTree(lastBody).path("fallbacks").isMissingNode)
    }

    @Test fun openAi() {
        val v = OpenAiVision("sk-test", "gpt-5-mini", "$url/v1/chat/completions")
        val r = v.readBoard(byteArrayOf(1, 2, 3))
        assertEquals(-Piece.KING, r.visual[4])
        assertEquals(160, r.inputTokens + r.outputTokens)
        val body = ObjectMapper().readTree(lastBody)
        assertEquals("low", body.path("messages").path(0).path("content").path(1).path("image_url").path("detail").asText())
        assertEquals("Bearer sk-test", lastHeaders["authorization"])
    }
}
