package com.dizzle.chessai

import com.dizzle.chessai.core.ai.ChatGptOAuth
import com.dizzle.chessai.core.ai.OAuthTokens
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * "Sign in with ChatGPT" for the app: PKCE flow with a loopback redirect
 * (http://127.0.0.1:<port>/auth/callback) served by a tiny one-shot HTTP server.
 * After the browser hits the callback it is sent back into the app via chessai://login.
 */
object ChatGptLogin {
    private const val PREFERRED_PORT = 1455
    private const val TIMEOUT_MS = 5 * 60_000

    /**
     * Runs the whole flow on the calling (background) thread. [openBrowser] is called with the
     * authorization URL. Returns the tokens or throws with a user-readable message.
     */
    fun run(prefs: Prefs, openBrowser: (String) -> Unit, awaitForeground: () -> Unit = {}): OAuthTokens {
        val oauth = ChatGptOAuth(prefs.chatGptIssuedClientId.ifBlank { null }, prefs.hostId)
        oauth.discover()
        val server = try {
            ServerSocket(PREFERRED_PORT, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        server.use { ss ->
            ss.soTimeout = TIMEOUT_MS
            val pending = oauth.begin("http://127.0.0.1:${ss.localPort}/auth/callback")
            openBrowser(pending.url)
            while (true) {
                val sock = try { ss.accept() } catch (e: SocketTimeoutException) {
                    throw IllegalStateException("Anmeldung abgelaufen – bitte erneut versuchen")
                }
                sock.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                    val target = line.split(' ').getOrNull(1) ?: return@use
                    if (!target.startsWith("/auth/callback")) {
                        respond(s, 404, "<p>Not found</p>")
                        return@use
                    }
                    val q = parseQuery(target.substringAfter('?', ""))
                    val error = q["error"]
                    if (error != null) {
                        respond(s, 200, page("Anmeldung abgebrochen", q["error_description"] ?: error))
                        throw IllegalStateException("ChatGPT-Login abgelehnt: ${q["error_description"] ?: error}")
                    }
                    if (q["state"] != pending.state || q["code"].isNullOrEmpty()) {
                        respond(s, 400, page("Ungültige Antwort", "Bitte erneut anmelden."))
                        throw IllegalStateException("Ungültige Login-Antwort (state)")
                    }
                    respond(s, 200, page("Fast geschafft ✓", "Zurück zur Chess-AI-App – die Anmeldung wird dort abgeschlossen.", redirect = true))
                    // Many phones block network access for apps in the background (data saver,
                    // battery optimisation). Finish the token exchange once the app is visible again.
                    awaitForeground()
                    val tokens = withNetworkRetry { oauth.exchange(pending, q["code"]!!, q["client_id"]) }
                    prefs.chatGptTokens = tokens
                    prefs.chatGptIssuedClientId = tokens.clientId
                    // pick a model this plan offers (prefer a small, cheap one)
                    val models = runCatching { withNetworkRetry { oauth.listModels(tokens.accessToken) } }.getOrDefault(emptyList())
                    if (models.isNotEmpty()) {
                        prefs.chatGptModels = models
                        if (prefs.openAiModel !in models) {
                            prefs.openAiModel = models.firstOrNull { "mini" in it } ?: models.first()
                        }
                    }
                    return tokens
                }
            }
        }
    }

    /** Returns a valid access token, refreshing it if needed. */
    @Synchronized
    fun accessToken(prefs: Prefs, force: Boolean = false): String {
        val t = prefs.chatGptTokens ?: throw IllegalStateException("Nicht mit ChatGPT angemeldet")
        if (!force && !t.isExpired()) return t.accessToken
        val fresh = withNetworkRetry { ChatGptOAuth(t.clientId, prefs.hostId).refresh(t) }
        prefs.chatGptTokens = fresh
        return fresh.accessToken
    }

    /** Retries transient network failures (DNS not yet available, connection resets). */
    fun <T> withNetworkRetry(attempts: Int = 5, block: () -> T): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(1500L * (i + 1))
            }
        }
        val e = last!!
        throw IllegalStateException(
            if (e is java.net.UnknownHostException)
                "Keine Verbindung zu OpenAI (${e.message}). Prüfe Internet, Datensparmodus bzw. Hintergrunddaten für Chess AI und ob ein Werbeblocker/Private DNS openai.com blockiert."
            else "Netzwerkfehler: ${e.message}", e,
        )
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun page(title: String, text: String, redirect: Boolean = false): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val back = if (redirect) "<p><a href=\"chessai://login\" style=\"color:#FFD600\">Zurück zur App</a></p>" +
            "<script>setTimeout(function(){location.href='chessai://login'},600)</script>" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Chess AI</title></head><body style=\"background:#16181D;color:#fff;font-family:sans-serif;padding:32px\">" +
            "<h2>♞ ${esc(title)}</h2><p>${esc(text)}</p>$back</body></html>"
    }

    private fun respond(s: java.net.Socket, code: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val out = s.getOutputStream()
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}
