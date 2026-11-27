package com.dizzle.chessai.core.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth tokens from "Sign in with ChatGPT". */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** epoch millis */
    val expiresAt: Long,
    val email: String?,
    val scope: String?,
    /** Client id that OpenAI issued to this app installation at the first sign-in. */
    val clientId: String = "",
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now > expiresAt - 60_000

    fun encode(): String = ObjectMapper().writeValueAsString(
        mapOf("a" to accessToken, "r" to refreshToken, "e" to expiresAt, "m" to email, "s" to scope, "c" to clientId)
    )

    companion object {
        fun decode(s: String?): OAuthTokens? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val n = ObjectMapper().readTree(s)
                OAuthTokens(n["a"].asText(), n["r"]?.takeIf { !it.isNull }?.asText(), n["e"].asLong(),
                    n["m"]?.takeIf { !it.isNull }?.asText(), n["s"]?.takeIf { !it.isNull }?.asText(),
                    n["c"]?.takeIf { !it.isNull }?.asText() ?: "")
            }.getOrNull()
        }
    }
}

/**
 * "Sign in with ChatGPT" for open-source apps (OAuth 2.0 + PKCE, public client, loopback redirect),
 * following OpenAI's sign-in-with-chatgpt-devkit: no pre-registered client is needed. The first
 * sign-in uses the placeholder client "dynamic_agent_client" and OpenAI returns the client id it
 * issued for this installation on the callback; later sign-ins and refreshes use that id.
 * With the plan-usage scopes the access token runs requests against the user's ChatGPT plan.
 */
class ChatGptOAuth(
    /** Client id issued earlier (null/blank = first sign-in, dynamic registration). */
    private val savedClientId: String? = null,
    /** Stable per-installation id ("urn:uuid:..."). */
    private val hostId: String? = null,
    private val appName: String = "Chess AI",
    private val issuer: String = ISSUER,
) {
    private val mapper = ObjectMapper()
    private var authEndpoint: String? = null
    private var tokenEndpoint: String? = null

    data class Pending(val url: String, val state: String, val verifier: String, val redirectUri: String, val nonce: String)

    private var discovered = false

    /** Loads authorization/token endpoints (falls back to known defaults if discovery fails; retried later). */
    fun discover() {
        if (discovered) return
        val doc = runCatching { getJson("$issuer/.well-known/openid-configuration") }.getOrNull()
        discovered = doc != null
        authEndpoint = doc?.path("authorization_endpoint")?.asText()?.takeIf { it.isNotBlank() } ?: "$issuer/api/accounts/authorize"
        tokenEndpoint = doc?.path("token_endpoint")?.asText()?.takeIf { it.isNotBlank() } ?: "$issuer/api/accounts/oauth/token"
    }

    /** Builds the URL to open in the browser. Call [discover] first (network). */
    fun begin(redirectUri: String): Pending {
        val verifier = randomUrlSafe(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(24)
        val nonce = randomUrlSafe(24)
        val saved = savedClientId?.takeIf { it.isNotBlank() }
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to (saved ?: DYNAMIC_CLIENT),
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "nonce" to nonce,
        )
        if (hostId != null) params["ext_agent_host_id"] = hostId
        if (saved == null) params["agent_name_hint"] = appName
        val url = (authEndpoint ?: "$issuer/api/accounts/authorize") + "?" + form(params)
        return Pending(url, state, verifier, redirectUri, nonce)
    }

    /**
     * Exchanges the authorization code for tokens. [returnedClientId] is the `client_id` query
     * parameter on the callback (the id OpenAI issued at the first sign-in).
     */
    fun exchange(p: Pending, code: String, returnedClientId: String? = null): OAuthTokens {
        discover()
        val clientId = returnedClientId?.takeIf { it.isNotBlank() } ?: savedClientId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ChatGPT hat keine Client-ID für diese App vergeben")
        require(clientId != DYNAMIC_CLIENT && CLIENT_ID_RE.matches(clientId)) { "Ungültige Client-ID von ChatGPT" }
        val json = postForm(tokenEndpoint!!, linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to p.redirectUri,
            "client_id" to clientId,
            "code_verifier" to p.verifier,
        ))
        val t = parseTokens(json, null, clientId)
        val granted = t.scope
        if (granted != null && PLAN_SCOPE !in granted.split(' ')) {
            throw IllegalStateException("Angemeldet, aber ChatGPT hat die Plan-Nutzung nicht freigegeben (Scope $PLAN_SCOPE fehlt).")
        }
        return t
    }

    fun refresh(t: OAuthTokens): OAuthTokens {
        val rt = t.refreshToken ?: throw IllegalStateException("Sitzung abgelaufen – bitte erneut mit ChatGPT anmelden")
        discover()
        val json = postForm(tokenEndpoint!!, linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to rt,
            "client_id" to t.clientId,
        ))
        return parseTokens(json, t, t.clientId)
    }

    /** Models the signed-in ChatGPT plan may use (slugs), from GET /v1/models. */
    fun listModels(accessToken: String, endpoint: String = "https://api.openai.com/v1/models"): List<String> {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        if (c.responseCode !in 200..299) return emptyList()
        val n = c.inputStream.use { mapper.readTree(it) }
        val out = ArrayList<String>()
        for (m in n.path("models")) {
            if (m.path("visibility").asText("list") == "list") m.path("slug").asText("").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        if (out.isEmpty()) for (m in n.path("data")) m.path("id").asText("").takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    private fun parseTokens(n: JsonNode, old: OAuthTokens?, clientId: String): OAuthTokens {
        val access = n.path("access_token").asText("")
        require(access.isNotEmpty()) { "Keine Tokens erhalten: ${n.toString().take(200)}" }
        val expiresIn = n.path("expires_in").asLong(3600)
        val email = n.path("id_token").asText("").takeIf { it.isNotEmpty() }?.let { emailFromIdToken(it) } ?: old?.email
        return OAuthTokens(
            accessToken = access,
            refreshToken = n.path("refresh_token").asText("").ifEmpty { old?.refreshToken },
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            email = email,
            scope = n.path("scope").asText("").ifEmpty { old?.scope },
            clientId = clientId,
        )
    }

    private fun emailFromIdToken(jwt: String): String? = runCatching {
        val payload = jwt.split('.')[1]
        mapper.readTree(Base64.getUrlDecoder().decode(payload)).path("email").asText("").ifEmpty { null }
    }.getOrNull()

    private fun getJson(url: String): JsonNode {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000 }
        return c.inputStream.use { mapper.readTree(it) }
    }

    private fun postForm(url: String, params: Map<String, String>): JsonNode {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        c.outputStream.use { it.write(form(params).toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { mapper.readTree(body).let { it.path("error_description").asText("").ifEmpty { it.path("error").asText("") } } }.getOrNull()
            throw IllegalStateException("ChatGPT-Login fehlgeschlagen ($code): ${msg?.ifEmpty { null } ?: body.take(200)}")
        }
        return mapper.readTree(body)
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        private val CLIENT_ID_RE = Regex("^[a-zA-Z0-9_-]{1,200}$")
        const val RESOURCE = "https://api.openai.com/v1"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"

        private val rnd = SecureRandom()
        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun randomUrlSafe(bytes: Int) = b64url(ByteArray(bytes).also { rnd.nextBytes(it) })
        fun form(p: Map<String, String>) = p.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }
}

/**
 * Board recognition billed to the user's ChatGPT plan (Responses API with the OAuth token).
 * [tokens] returns a valid access token (refreshing if needed); [onUnauthorized] forces a refresh.
 */
class ChatGptPlanVision(
    private val tokens: () -> String,
    private val onUnauthorized: () -> String,
    private val model: String = OpenAiVision.DEFAULT_MODEL,
    private val endpoint: String = "https://api.openai.com/v1/responses",
) : VisionProvider {
    override val label = "ChatGPT-Abo ($model)"
    private val mapper = ObjectMapper()

    override fun readBoard(jpeg: ByteArray): AiBoard {
        val body = mapper.createObjectNode().apply {
            put("model", model)
            put("max_output_tokens", 4000)
            putObject("reasoning").put("effort", "low")
            put("store", false)
            putArray("input").addObject().apply {
                put("role", "user")
                putArray("content").apply {
                    addObject().put("type", "input_text").put("text", BoardPrompt.TEXT)
                    addObject().put("type", "input_image")
                        .put("image_url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
                        .put("detail", "low")
                }
            }
        }
        val bytes = mapper.writeValueAsBytes(body)
        var (code, resp) = post(bytes, tokens())
        if (code == 401) {
            val r = post(bytes, onUnauthorized()); code = r.first; resp = r.second
        }
        if (code !in 200..299) {
            val msg = runCatching { mapper.readTree(resp).path("error").path("message").asText() }.getOrNull()
            throw IllegalStateException("ChatGPT Fehler $code: ${msg?.takeIf { it.isNotBlank() } ?: resp.take(200)}")
        }
        val json = mapper.readTree(resp)
        val text = StringBuilder()
        json.path("output_text").takeIf { it.isTextual }?.let { text.append(it.asText()) }
        if (text.isEmpty()) {
            for (item in json.path("output")) for (c in item.path("content")) {
                if (c.path("type").asText() == "output_text") text.append(c.path("text").asText()).append('\n')
            }
        }
        val u = json.path("usage")
        return BoardPrompt.parse(text.toString(), u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0))
    }

    private fun post(body: ByteArray, token: String): Pair<Int, String> {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to resp
    }
}
