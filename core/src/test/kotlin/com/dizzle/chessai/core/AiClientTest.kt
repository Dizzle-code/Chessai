package com.dizzle.chessai.core

import com.dizzle.chessai.core.ai.ChatGptOAuth
import com.dizzle.chessai.core.ai.ChatGptPlanVision
import com.dizzle.chessai.core.ai.ClaudeVision
import com.dizzle.chessai.core.ai.OAuthTokens
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

/** "Sign in with ChatGPT" flow against a fake authorization server. */
class ChatGptOAuthTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private var tokenForm = ""
    private var calls = 0

    init {
        val m = ObjectMapper()
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: Any) {
            val bytes = m.writeValueAsBytes(body)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/.well-known/openid-configuration") { ex ->
            reply(ex, 200, mapOf("authorization_endpoint" to "$base/authorize", "token_endpoint" to "$base/token"))
        }
        server.createContext("/token") { ex ->
            tokenForm = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"me@example.com"}""".toByteArray())
            reply(ex, 200, mapOf("access_token" to "AT${++calls}", "refresh_token" to "RT", "expires_in" to 3600,
                "id_token" to "h.$payload.s", "scope" to "openid offline_access resource.invoke chatgpt.tokens.use.direct"))
        }
        server.createContext("/v1/models") { ex ->
            reply(ex, 200, mapOf("models" to listOf(
                mapOf("slug" to "gpt-5.5", "display_name" to "GPT-5.5", "visibility" to "list"),
                mapOf("slug" to "hidden", "visibility" to "hide"),
                mapOf("slug" to "gpt-5.5-mini", "display_name" to "GPT-5.5 mini", "visibility" to "list"))))
        }
        var first = true
        server.createContext("/v1/responses") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (first) { first = false; reply(ex, 401, mapOf("error" to mapOf("message" to "expired"))); return@createContext }
            assertEquals("Bearer fresh", auth)
            reply(ex, 200, mapOf(
                "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text",
                    "text" to "rnbqkbnr\npppppppp\n8\n8\n8\n8\nPPPPPPPP\nRNBQKBNR\nw")))),
                "usage" to mapOf("input_tokens" to 100, "output_tokens" to 30)))
        }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    @Test fun pkceFlowWithDynamicClient() {
        // first sign-in: placeholder client, OpenAI issues the real client id on the callback
        val o = ChatGptOAuth(savedClientId = null, hostId = "urn:uuid:test-host", issuer = base)
        o.discover()
        val p = o.begin("http://127.0.0.1:1455/auth/callback")
        assertTrue(p.url.startsWith("$base/authorize?"))
        for (part in listOf("client_id=dynamic_agent_client", "agent_name_hint=Chess+AI", "ext_agent_host_id=urn%3Auuid%3Atest-host",
            "code_challenge_method=S256", "response_type=code", "chatgpt.tokens.use.direct",
            "resource=https%3A%2F%2Fapi.openai.com%2Fv1", "state=${p.state}", "nonce=${p.nonce}")) {
            assertTrue(part in p.url, "missing $part in ${p.url}")
        }
        val t = o.exchange(p, "the-code", returnedClientId = "app_issued_42")
        assertEquals("AT1", t.accessToken)
        assertEquals("app_issued_42", t.clientId)
        assertEquals("me@example.com", t.email)
        assertTrue("code_verifier=${p.verifier}" in tokenForm && "grant_type=authorization_code" in tokenForm &&
            "client_id=app_issued_42" in tokenForm && "resource=https%3A%2F%2Fapi.openai.com%2Fv1" in tokenForm &&
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" in tokenForm)
        val r = o.refresh(t)
        assertEquals("AT2", r.accessToken)
        assertEquals("app_issued_42", r.clientId)
        assertTrue("grant_type=refresh_token" in tokenForm && "client_id=app_issued_42" in tokenForm && "resource=" in tokenForm)
        assertEquals(t, OAuthTokens.decode(t.encode()))

        // later sign-in reuses the issued client and sends no name hint
        val again = ChatGptOAuth(savedClientId = "app_issued_42", hostId = "urn:uuid:test-host", issuer = base).apply { discover() }
            .begin("http://127.0.0.1:1455/auth/callback")
        assertTrue("client_id=app_issued_42" in again.url && "agent_name_hint" !in again.url)

        // the placeholder must never be accepted as the issued id
        assertTrue(runCatching { o.exchange(p, "c", returnedClientId = "dynamic_agent_client") }.isFailure)
    }

    @Test fun listsPlanModels() {
        val models = ChatGptOAuth(issuer = base).listModels("AT", "$base/v1/models")
        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), models)
    }

    @Test fun planVisionRefreshesOn401() {
        val v = ChatGptPlanVision(tokens = { "stale" }, onUnauthorized = { "fresh" }, endpoint = "$base/v1/responses")
        val b = v.readBoard(byteArrayOf(1))
        assertEquals(true, b.whiteAtBottom)
        assertEquals(130, b.inputTokens + b.outputTokens)
    }
}
