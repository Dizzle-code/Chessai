package com.dizzle.chessai

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings screen + start button. Built in code to keep the app small and dependency-free. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var startButton: Button
    private lateinit var status: TextView
    private lateinit var claudeBox: LinearLayout
    private lateinit var openAiBox: LinearLayout
    private lateinit var advancedBox: LinearLayout
    private lateinit var loginBox: LinearLayout
    private lateinit var loginStatus: TextView
    private lateinit var loginButton: Button
    private var advancedRadios: List<RadioButton> = emptyList()

    companion object {
        private const val REQ_CAPTURE = 42
        private const val TEXT = Color.WHITE
        private const val MUTED = 0xFFB0B4BC.toInt()
        private const val CARD = 0xFF23262E.toInt()
        private const val ACCENT = 0xFF1E88E5.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        buildUi()
    }

    private val resumeLock = Object()
    @Volatile private var resumed = false

    override fun onResume() {
        super.onResume()
        synchronized(resumeLock) { resumed = true; resumeLock.notifyAll() }
        refreshStatus()
        if (::loginStatus.isInitialized) refreshLogin()
    }

    override fun onPause() {
        synchronized(resumeLock) { resumed = false }
        super.onPause()
    }

    /** Blocks (background thread) until this activity is in the foreground again, or timeout. */
    private fun awaitResumed(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(resumeLock) {
            while (!resumed) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                resumeLock.wait(left)
            }
        }
        Thread.sleep(400) // let the network policy switch to foreground
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "♞ Chess AI"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
        })
        root.addView(muted("Erkennt das Schachbrett auf deinem Bildschirm und zeigt den besten Zug als leuchtende Punkte an – ohne ihn auszuführen."))

        // ---- start / stop
        startButton = Button(this).apply {
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(14).toFloat() }
            setOnClickListener { if (OverlayService.running) stopOverlay() else startOverlay() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(18) })
        status = muted("")
        root.addView(status)

        // ---- AI provider
        val ai = card("Bilderkennung (nur wenn nötig)")
        ai.addView(muted("Die App erkennt Figuren lokal und verfolgt die Partie selbst (0 Tokens). Die AI wird nur gefragt, wenn sie ein Figuren-Design zum ersten Mal sieht – meist 1× pro Design."))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        group.addView(radio("ChatGPT – mit deinem Abo anmelden", 4))
        group.addView(radio("Keine AI – nur lokal (ab Grundstellung)", 3))
        advancedRadios = listOf(radio("Claude (Anthropic API-Key)", 1), radio("OpenAI API-Key", 2))
        advancedRadios.forEach { group.addView(it) }
        group.check(when (prefs.provider) { AiProvider.CHATGPT_LOGIN -> 4; AiProvider.CLAUDE -> 1; AiProvider.OPENAI -> 2; AiProvider.NONE -> 3 })
        ai.addView(group)

        // ChatGPT login
        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loginStatus = label("")
        loginBox.addView(loginStatus)
        loginButton = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF10A37F.toInt()); cornerRadius = dp(12).toFloat() }
            setOnClickListener { if (prefs.chatGptTokens != null) logoutChatGpt() else loginChatGpt() }
        }
        loginBox.addView(loginButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })
        loginBox.addView(muted("Die Anfragen laufen über dein ChatGPT-Plus/Pro-Kontingent – kein API-Key nötig. Login-Daten werden verschlüsselt im Android-Keystore gespeichert."))
        ai.addView(loginBox)

        ai.addView(muted("Claude: Anthropic erlaubt den Login mit einem Claude-Pro/Max-Abo nur in den eigenen Apps (Claude, Claude Code) – fremde Apps dürfen ihn nicht nutzen. Für Claude bleibt daher nur ein API-Key (unter „Erweitert“)."))

        // advanced: API keys + client id
        advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        claudeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        claudeBox.addView(field("Anthropic API-Key (sk-ant-…)", prefs.claudeKey, secret = true) { prefs.claudeKey = it })
        claudeBox.addView(linkButton("API-Key bei Anthropic erstellen", "https://console.anthropic.com/settings/keys"))
        claudeBox.addView(field("Claude-Modell", prefs.claudeModel, secret = false) { prefs.claudeModel = it })
        claudeBox.addView(muted("Tipp: „claude-haiku-5-5“ ist deutlich günstiger und reicht für Brett-Erkennung meist aus."))
        advancedBox.addView(claudeBox)
        openAiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        openAiBox.addView(field("OpenAI API-Key (sk-…)", prefs.openAiKey, secret = true) { prefs.openAiKey = it })
        openAiBox.addView(linkButton("API-Key bei OpenAI erstellen", "https://platform.openai.com/api-keys"))
        advancedBox.addView(openAiBox)
        advancedBox.addView(field("OpenAI-Modell (Login & API-Key)", prefs.openAiModel, secret = false) { prefs.openAiModel = it })
        val advToggle = switch("Erweitert (API-Keys, Modell)", prefs.showAdvanced) {
            prefs.showAdvanced = it; updateProviderBoxes()
        }
        ai.addView(advToggle)
        ai.addView(advancedBox)

        group.setOnCheckedChangeListener { _, id ->
            prefs.provider = when (id) { 4 -> AiProvider.CHATGPT_LOGIN; 1 -> AiProvider.CLAUDE; 2 -> AiProvider.OPENAI; else -> AiProvider.NONE }
            updateProviderBoxes()
        }
        updateProviderBoxes()
        refreshLogin()

        // ---- game settings
        val game = card("Spiel")
        game.addView(label("Ich spiele"))
        val color = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        color.addView(radio("Auto (unten)", 10)); color.addView(radio("Weiß", 11)); color.addView(radio("Schwarz", 12))
        color.check(when (prefs.myColor) { 1 -> 11; -1 -> 12; else -> 10 })
        color.setOnCheckedChangeListener { _, id -> prefs.myColor = when (id) { 11 -> 1; 12 -> -1; else -> 0 } }
        game.addView(color)

        val thinkLabel = label("")
        fun setThinkLabel(ms: Long) { thinkLabel.text = "Bedenkzeit der Engine: ${"%.1f".format(ms / 1000.0)} s" }
        setThinkLabel(prefs.thinkMs)
        game.addView(thinkLabel)
        game.addView(SeekBar(this).apply {
            max = 48 // 0.2 s .. 5.0 s in 0.1 s steps
            progress = ((prefs.thinkMs - 200) / 100).toInt().coerceIn(0, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val ms = 200L + p * 100L
                    prefs.thinkMs = ms; setThinkLabel(ms)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        game.addView(switch("Stockfish verwenden (falls enthalten)", prefs.useStockfish) { prefs.useStockfish = it })
        game.addView(switch("Auto-Modus (Zug erscheint automatisch nach Gegnerzug)", prefs.autoMode) { prefs.autoMode = it })

        // ---- help
        val help = card("So geht's")
        help.addView(muted(
            "1. Overlay starten und Bildschirmaufnahme erlauben.\n" +
                "2. Schach-App öffnen. Den ♞-Button an eine freie Stelle ziehen.\n" +
                "3. Wenn du am Zug bist: ♞ antippen → Cyan = Figur, Gelb = Zielfeld.\n" +
                "4. Lange drücken = Menü (Auto-Modus, Brett manuell festlegen, AI neu einlesen, Neue Partie).\n\n" +
                "Fairplay: Auf Online-Plattformen gegen Menschen ist Engine-Hilfe verboten und führt zur Sperre. " +
                "Nutze die App zum Lernen, für Analysen und gegen Computer-Gegner."
        ))
    }

    private fun updateProviderBoxes() {
        val adv = prefs.showAdvanced || prefs.provider == AiProvider.CLAUDE || prefs.provider == AiProvider.OPENAI
        advancedBox.visibility = if (adv) View.VISIBLE else View.GONE
        advancedRadios.forEach { it.visibility = if (adv) View.VISIBLE else View.GONE }
        claudeBox.visibility = if (prefs.provider == AiProvider.CLAUDE) View.VISIBLE else View.GONE
        openAiBox.visibility = if (prefs.provider == AiProvider.OPENAI) View.VISIBLE else View.GONE
        loginBox.visibility = if (prefs.provider == AiProvider.CHATGPT_LOGIN) View.VISIBLE else View.GONE
    }

    private fun refreshLogin() {
        val t = prefs.chatGptTokens
        when {
            loginRunning -> { loginStatus.text = "Anmeldung läuft …"; loginButton.text = "Bitte im Browser bestätigen" }
            t != null -> { loginStatus.text = "✓ Angemeldet" + (t.email?.let { " als $it" } ?: ""); loginButton.text = "Abmelden" }
            else -> { loginStatus.text = "Nicht angemeldet"; loginButton.text = "Mit ChatGPT anmelden" }
        }
    }

    @Volatile private var loginRunning = false

    private fun loginChatGpt() {
        if (loginRunning) return
        loginRunning = true
        refreshLogin()
        Thread {
            val result = runCatching {
                ChatGptLogin.run(
                    prefs,
                    openBrowser = { url -> runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    awaitForeground = { awaitResumed(90_000) },
                )
            }
            runOnUiThread {
                loginRunning = false
                refreshLogin()
                result.exceptionOrNull()?.let { Toast.makeText(this, it.message ?: "Login fehlgeschlagen", Toast.LENGTH_LONG).show() }
                        ?: Toast.makeText(this, "Mit ChatGPT angemeldet · Modell: ${prefs.openAiModel}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun logoutChatGpt() {
        prefs.chatGptTokens = null
        refreshLogin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshLogin()
    }

    private fun refreshStatus() {
        startButton.text = if (OverlayService.running) "■  Overlay stoppen" else "▶  Overlay starten"
        val engine = if (Engines.stockfishBinary(this) != null) "Stockfish (eingebaut)" else "integrierte Engine"
        status.text = "Engine: $engine · AI-Aufrufe: ${prefs.aiCalls} · Tokens gesamt: ${prefs.totalTokens} · lokal erkannt: ${prefs.localHits}×"
    }

    // ------------------------------------------------------------------ start / stop

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bitte „Über anderen Apps einblenden“ erlauben und zurückkehren", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (prefs.provider == AiProvider.CHATGPT_LOGIN && prefs.chatGptTokens == null ||
            prefs.provider == AiProvider.CLAUDE && prefs.claudeKey.isBlank() ||
            prefs.provider == AiProvider.OPENAI && prefs.openAiKey.isBlank()
        ) {
            Toast.makeText(this, "Keine AI verbunden – es wird nur lokal erkannt (Startstellung / gelernte Figuren)", Toast.LENGTH_LONG).show()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        startButton.postDelayed({ refreshStatus() }, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Bildschirmaufnahme wurde nicht erlaubt", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "Overlay läuft – öffne jetzt deine Schach-App", Toast.LENGTH_SHORT).show()
        startButton.postDelayed({ refreshStatus(); moveTaskToBack(true) }, 600)
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
        }
        c.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return c
    }

    private fun muted(t: String) = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(MUTED); setPadding(0, dp(6), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14.5f; setTextColor(TEXT); setPadding(0, dp(10), 0, dp(2))
    }

    private fun radio(t: String, id: Int) = RadioButton(this).apply {
        text = t; this.id = id; setTextColor(TEXT); textSize = 14.5f
    }

    @Suppress("DEPRECATION")
    private fun switch(t: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = value; setTextColor(TEXT); textSize = 14.5f; setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun field(hint: String, value: String, secret: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 14.5f
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun linkButton(t: String, url: String) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(0xFF64B5F6.toInt()); background = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
