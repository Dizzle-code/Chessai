package com.dizzle.chessai

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings screen + start button in a pixel-art chess style. Built in code, no UI libraries. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var statusLabel: TextView
    private lateinit var statusLight: View
    private lateinit var statEngine: TextView
    private lateinit var statTokens: TextView
    private lateinit var statLocal: TextView
    private lateinit var providerHint: TextView
    private lateinit var loginBox: LinearLayout
    private lateinit var loginStatus: TextView
    private lateinit var loginButton: TextView
    private lateinit var apiBox: LinearLayout
    private lateinit var claudeBox: LinearLayout
    private lateinit var openAiBox: LinearLayout
    private var providerTiles: List<TextView> = emptyList()
    private var apiTiles: List<TextView> = emptyList()

    companion object {
        private const val REQ_CAPTURE = 42
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

    private fun dp(v: Number) = dpi(v)

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        // ---- header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(this).apply { background = Sprites.knight() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(title("CHESS AI", 20f, Pal.CREAM))
        titles.addView(body("Dein Pixel-Zugberater fürs Schachbrett", Pal.MUTED, 19f).apply { setPadding(0, dp(6), 0, 0) })
        header.addView(titles)
        root.addView(header)
        root.addView(CheckerStrip(this), lp(top = 16))

        // ---- status / start
        val status = panel(Pal.PANEL)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusLight = View(this)
        statusRow.addView(statusLight, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusLabel = title("", 11f, Pal.CREAM).apply { setPadding(dp(10), 0, 0, 0) }
        statusRow.addView(statusLabel)
        status.addView(statusRow)
        startButton = pixelButton("", Pal.MOSS, Pal.INK, 14f) {
            if (OverlayService.running) stopOverlay() else startOverlay()
        }
        status.addView(startButton, lp(top = 14, height = 64))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statEngine = statChip(stats, "ENGINE")
        statTokens = statChip(stats, "TOKENS")
        statLocal = statChip(stats, "LOKAL")
        status.addView(stats, lp(top = 14))

        // ---- recognition
        val rec = section("ERKENNUNG")
        rec.addView(body("Figuren werden lokal erkannt und die Partie wird mitverfolgt – 0 Tokens. AI nur als Joker, wenn ein Figuren-Design neu ist.", Pal.MUTED))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        providerTiles = listOf("LOKAL", "CHATGPT", "API-KEY").mapIndexed { i, t ->
            tile(tiles, t) { selectProvider(i) }
        }
        rec.addView(tiles, lp(top = 12))
        providerHint = body("", Pal.CREAM).apply { setPadding(0, dp(10), 0, 0) }
        rec.addView(providerHint)

        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loginStatus = body("", Pal.CREAM)
        loginBox.addView(loginStatus)
        loginButton = pixelButton("", Pal.GOLD, Pal.INK, 11f) {
            if (prefs.chatGptTokens != null) logoutChatGpt() else loginChatGpt()
        }
        loginBox.addView(loginButton, lp(top = 8, height = 52))
        rec.addView(loginBox, lp(top = 6))

        apiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        apiTiles = listOf("CLAUDE", "OPENAI").mapIndexed { i, t ->
            tile(apiRow, t, small = true) {
                prefs.provider = if (i == 0) AiProvider.CLAUDE else AiProvider.OPENAI
                updateProviderUi()
            }
        }
        apiBox.addView(apiRow, lp(top = 8))
        claudeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        claudeBox.addView(fieldLabel("ANTHROPIC API-KEY"))
        claudeBox.addView(field("sk-ant-…", prefs.claudeKey, secret = true) { prefs.claudeKey = it })
        claudeBox.addView(link("> Key bei Anthropic erstellen", "https://console.anthropic.com/settings/keys"))
        claudeBox.addView(fieldLabel("MODELL"))
        claudeBox.addView(field("claude-opus-5-5", prefs.claudeModel, secret = false) { prefs.claudeModel = it })
        claudeBox.addView(body("Tipp: claude-haiku-5-5 ist günstiger und reicht meist.", Pal.MUTED, 17f))
        apiBox.addView(claudeBox)
        openAiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        openAiBox.addView(fieldLabel("OPENAI API-KEY"))
        openAiBox.addView(field("sk-…", prefs.openAiKey, secret = true) { prefs.openAiKey = it })
        openAiBox.addView(link("> Key bei OpenAI erstellen", "https://platform.openai.com/api-keys"))
        openAiBox.addView(fieldLabel("MODELL"))
        openAiBox.addView(field("gpt-5-mini", prefs.openAiModel, secret = false) { prefs.openAiModel = it })
        apiBox.addView(openAiBox)
        apiBox.addView(body("Claude-Abo-Login ist für fremde Apps nicht erlaubt – für Claude geht nur ein API-Key.", Pal.MUTED, 17f).apply {
            setPadding(0, dp(8), 0, 0)
        })
        rec.addView(apiBox)
        updateProviderUi()
        refreshLogin()

        // ---- game
        val game = section("SPIEL")
        game.addView(fieldLabel("ICH SPIELE"))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colorValues = listOf(0, 1, -1)
        val colorTiles = ArrayList<TextView>()
        listOf("AUTO" to Sprites.autoPawn(), "WEISS" to Sprites.whitePawn(), "SCHWARZ" to Sprites.blackPawn())
            .forEachIndexed { i, (t, icon) ->
                colorTiles += tile(colors, t, small = true, icon = icon) {
                    prefs.myColor = colorValues[i]
                    colorTiles.forEachIndexed { j, v -> styleTile(v, j == i) }
                }
            }
        colorTiles.forEachIndexed { j, v -> styleTile(v, colorValues[j] == prefs.myColor) }
        game.addView(colors, lp(top = 6))
        game.addView(body("Auto = die Farbe, die unten auf dem Brett steht.", Pal.MUTED, 17f).apply { setPadding(0, dp(6), 0, 0) })

        val thinkLabel = fieldLabel("")
        fun setThink(ms: Long) { thinkLabel.text = "BEDENKZEIT  ${"%.1f".format(ms / 1000.0)} S" }
        setThink(prefs.thinkMs)
        game.addView(thinkLabel, lp(top = 10))
        game.addView(PixelSlider(this, 48, ((prefs.thinkMs - 200) / 100).toInt()) { p ->
            val ms = 200L + p * 100L
            prefs.thinkMs = ms; setThink(ms)
        }, lp(top = 6))
        game.addView(toggleRow("DAUER-SCAN", "Bester Zug erscheint automatisch, sobald du dran bist", prefs.autoMode) { prefs.autoMode = it }, lp(top = 14))
        game.addView(toggleRow("AUTO-ZUG", "Die App tippt den besten Zug selbst (nur gegen Computer-Gegner verwenden!)", prefs.autoPlay) { on ->
            prefs.autoPlay = on
            if (on) {
                prefs.autoMode = true
                if (!ChessTapService.isEnabled(this)) showAutoPlaySetup()
            }
        }, lp(top = 10))
        game.addView(toggleRow("STOCKFISH", "Starke Engine nutzen (sonst eingebaute Engine)", prefs.useStockfish) { prefs.useStockfish = it }, lp(top = 10))

        // ---- how to
        val how = section("SO GEHT'S")
        listOf(
            "START antippen und den ganzen Bildschirm freigeben.",
            "Schach-App öffnen, den Springer-Button neben das Brett ziehen.",
            "Partie aus der Grundstellung starten – die App lernt die Figuren ohne AI.",
            "Cyan = Figur, Gold = Zielfeld. Antippen = neu scannen, lange drücken = Menü.",
        ).forEachIndexed { i, t -> how.addView(step(i + 1, t), lp(top = if (i == 0) 4 else 10)) }

        // ---- footer
        root.addView(CheckerStrip(this), lp(top = 22))
        root.addView(body("Fairplay: In Online-Partien gegen Menschen ist Engine-Hilfe verboten. Nutze die App zum Lernen, für Analysen und gegen Computer-Gegner.", Pal.MUTED, 17f), lp(top = 12))
        root.addView(body("Schriften: Press Start 2P & VT323 (SIL Open Font License). Engine: Stockfish (GPLv3).", Pal.SLATE, 15f), lp(top = 8))
    }

    // ------------------------------------------------------------------ provider selection

    private fun providerIndex() = when (prefs.provider) {
        AiProvider.NONE -> 0
        AiProvider.CHATGPT_LOGIN -> 1
        AiProvider.CLAUDE, AiProvider.OPENAI -> 2
    }

    private fun selectProvider(i: Int) {
        prefs.provider = when (i) {
            0 -> AiProvider.NONE
            1 -> AiProvider.CHATGPT_LOGIN
            else -> if (prefs.provider == AiProvider.OPENAI) AiProvider.OPENAI else AiProvider.CLAUDE
        }
        updateProviderUi()
    }

    private fun updateProviderUi() {
        val idx = providerIndex()
        providerTiles.forEachIndexed { i, v -> styleTile(v, i == idx) }
        providerHint.text = when (idx) {
            0 -> "Komplett offline. Starte eine Partie aus der Grundstellung – danach erkennt die App auch spätere Stellungen im gleichen Design."
            1 -> "Nutzt dein ChatGPT-Abo (Go, Plus, Pro oder Business). Ohne Abo bitte LOKAL wählen."
            else -> "Eigener API-Key. Kostet meist nur Bruchteile eines Cents pro Figuren-Design."
        }
        loginBox.visibility = if (idx == 1) View.VISIBLE else View.GONE
        apiBox.visibility = if (idx == 2) View.VISIBLE else View.GONE
        apiTiles.forEachIndexed { i, v -> styleTile(v, (i == 0) == (prefs.provider == AiProvider.CLAUDE)) }
        claudeBox.visibility = if (prefs.provider == AiProvider.CLAUDE) View.VISIBLE else View.GONE
        openAiBox.visibility = if (prefs.provider == AiProvider.OPENAI) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ ChatGPT login

    private fun refreshLogin() {
        val t = prefs.chatGptTokens
        when {
            loginRunning -> { loginStatus.text = "Anmeldung läuft …"; loginButton.text = "IM BROWSER BESTÄTIGEN" }
            t != null -> { loginStatus.text = "Angemeldet" + (t.email?.let { " als $it" } ?: "") + " · Modell ${prefs.openAiModel}"; loginButton.text = "ABMELDEN" }
            else -> { loginStatus.text = "Nicht angemeldet"; loginButton.text = "MIT CHATGPT ANMELDEN" }
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
                result.exceptionOrNull()?.let { showError("ChatGPT-Login fehlgeschlagen", it.message ?: it.javaClass.simpleName) }
                    ?: Toast.makeText(this, "Mit ChatGPT angemeldet · Modell: ${prefs.openAiModel}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showAutoPlaySetup() {
        val text = TextView(this).apply {
            text = "Damit die App selbst tippen darf, braucht sie die Bedienungshilfe „Chess AI Auto-Zug“.\n\n" +
                "1. Gleich öffnen sich die Bedienungshilfen.\n" +
                "2. „Chess AI Auto-Zug“ (evtl. unter „Installierte Apps“) antippen und einschalten.\n\n" +
                "Ist der Schalter ausgegraut (Android 13+, „Eingeschränkte Einstellung“): App-Info von Chess AI öffnen → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“, dann nochmal versuchen.\n\n" +
                "Fairplay: Automatisches Ziehen gegen Menschen wird von Schach-Plattformen erkannt und gesperrt."
            setPadding(dp(20), dp(12), dp(20), dp(4))
            textSize = 15f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("AUTO-ZUG einrichten")
            .setView(text)
            .setPositiveButton("Bedienungshilfen öffnen") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App-Info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun showError(title: String, message: String) {
        val text = TextView(this).apply {
            this.text = message
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(4))
            textSize = 14f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(text)
            .setPositiveButton("Kopieren") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Fehler", message))
                Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK", null)
            .show()
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
        val on = OverlayService.running
        startButton.text = if (on) "STOP" else "START"
        (startButton.background as? PixelBox)?.let { it.fill = if (on) Pal.CORAL else Pal.MOSS; it.invalidateSelf() }
        statusLabel.text = if (on) "OVERLAY AKTIV" else "OVERLAY AUS"
        statusLight.background = PixelBox(if (on) Pal.MOSS else Pal.CORAL, Pal.INK, dpf(2f), shadow = 0, shadowSteps = 0)
        statEngine.text = if (Engines.stockfishBinary(this) != null) "Stockfish" else "Eingebaut"
        statTokens.text = "${prefs.totalTokens}"
        statLocal.text = "${prefs.localHits}x"
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
            Toast.makeText(this, "Keine AI verbunden – es wird nur lokal erkannt", Toast.LENGTH_LONG).show()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        // Android 14+: only offer "entire screen" – sharing a single app would hide the chess app.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
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

    // ------------------------------------------------------------------ pixel widgets

    private fun lp(top: Int = 0, height: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (height > 0) dp(height) else ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun title(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = PixelFonts.title(this@MainActivity)
        includeFontPadding = false
    }

    private fun body(t: String, color: Int, size: Float = 19f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = PixelFonts.body(this@MainActivity)
        setLineSpacing(0f, 0.95f)
    }

    private fun fieldLabel(t: String) = title(t, 9f, Pal.GOLD).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun panel(fill: Int): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = PixelBox(fill, Pal.INK, dpf(3f), shadowSteps = 2)
            setPadding(dp(16), dp(16), dp(22), dp(22))
        }
        root.addView(p, lp(top = 18))
        return p
    }

    private fun section(name: String): LinearLayout {
        val p = panel(Pal.PANEL)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply { background = PixelBox(Pal.GOLD, Pal.INK, dpf(1.5f), shadow = 0, shadowSteps = 0) },
            LinearLayout.LayoutParams(dp(10), dp(10)))
        head.addView(title(name, 12f, Pal.CREAM).apply { setPadding(dp(10), 0, 0, 0) })
        p.addView(head)
        p.addView(View(this).apply { setBackgroundColor(Pal.SLATE) }, lp(top = 10, height = 2).apply { height = dpi(2) })
        return p
    }

    private fun pixelButton(t: String, fill: Int, textColor: Int, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        textSize = size
        setTextColor(textColor)
        typeface = PixelFonts.title(this@MainActivity)
        background = PixelBox(fill, Pal.INK, dpf(3f), shadowSteps = 2)
        setPadding(dp(8), 0, dp(14), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tile(parent: LinearLayout, t: String, small: Boolean = false, icon: Drawable? = null, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = t
            gravity = Gravity.CENTER
            textSize = if (small) 8.5f else 9.5f
            typeface = PixelFonts.title(this@MainActivity)
            setPadding(dp(4), dp(10), dp(8), dp(14))
            isClickable = true
            if (icon != null) {
                icon.setBounds(0, 0, dp(22), dp(22))
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(6)
            }
            setOnClickListener { onClick() }
        }
        parent.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount > 0) leftMargin = dp(8)
        })
        return v
    }

    private fun styleTile(v: TextView, selected: Boolean) {
        v.background = PixelBox(if (selected) Pal.MOSS else Pal.PANEL_HI, if (selected) Pal.GOLD else Pal.INK, dpf(2.5f), shadowSteps = 2)
        v.setTextColor(if (selected) Pal.INK else Pal.MUTED)
    }

    private fun statChip(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = PixelBox(Pal.PANEL_HI, Pal.INK, dpf(2.5f), shadowSteps = 1)
            setPadding(dp(4), dp(8), dp(6), dp(10))
        }
        box.addView(title(label, 7f, Pal.MUTED))
        val value = body("", Pal.CREAM, 20f).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        box.addView(value)
        parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount > 0) leftMargin = dp(8)
        })
        return value
    }

    private fun toggleRow(name: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(name, 10f, Pal.CREAM))
        texts.addView(body(desc, Pal.MUTED, 17f).apply { setPadding(0, dp(4), dp(8), 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(PixelToggle(this, value, onChange))
        return row
    }

    private fun step(n: Int, t: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = FrameLayout(this).apply { background = PixelBox(if (n % 2 == 1) Pal.MOSS else Pal.CREAM, Pal.INK, dpf(2f), shadowSteps = 1) }
        badge.addView(title("$n", 10f, Pal.INK).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(badge, LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(body(t, Pal.CREAM, 18f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun field(hint: String, value: String, secret: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Pal.CREAM)
        setHintTextColor(Pal.SLATE)
        textSize = 19f
        typeface = PixelFonts.body(this@MainActivity)
        isSingleLine = true
        background = PixelBox(Pal.BG, Pal.SLATE, dpf(2.5f), shadowSteps = 0, shadow = 0)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun link(t: String, url: String) = body(t, Pal.CYAN, 18f).apply {
        setPadding(0, dp(8), 0, dp(2))
        isClickable = true
        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
