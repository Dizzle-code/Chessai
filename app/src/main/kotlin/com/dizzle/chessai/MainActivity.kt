package com.dizzle.chessai

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
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
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * App screens: onboarding (first start) and a tabbed main UI (START · KI · SPIEL · DESIGN).
 * Everything is drawn by the selected [UiTheme]; built in code, no UI libraries.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var theme: UiTheme
    private lateinit var frame: FrameLayout
    private var insetTop = 0
    private var insetBottom = 0
    private var currentTab = 0
    private var onboardingPage = 0

    // START tab views (refreshed on resume)
    private var startButton: TextView? = null
    private var statusLabel: TextView? = null
    private var statusDot: View? = null
    private var statEngine: TextView? = null
    private var statTokens: TextView? = null
    private var statLocal: TextView? = null
    // KI tab views
    private var loginStatus: TextView? = null
    private var loginButton: TextView? = null

    companion object {
        private const val REQ_CAPTURE = 42
        private val TABS = listOf("START" to "▶", "KI" to "◎", "SPIEL" to "♟", "DESIGN" to "✦")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Draw edge-to-edge on every Android version and pad content by the real system insets,
        // so the title never collides with the status bar or the camera cut-out.
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = 0x00000000
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0x00000000
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        frame = FrameLayout(this)
        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { _, ins ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                ins.systemWindowInsetTop to ins.systemWindowInsetBottom
            }
            if (t != insetTop || b != insetBottom) { insetTop = t; insetBottom = b; render() }
            ins
        }
        render()
    }

    private val resumeLock = Object()
    @Volatile private var resumed = false

    override fun onResume() {
        super.onResume()
        synchronized(resumeLock) { resumed = true; resumeLock.notifyAll() }
        refreshStatus()
        refreshLogin()
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

    // ------------------------------------------------------------------ rendering

    private fun render() {
        theme = Themes.current(this)
        applySystemBarIcons()
        frame.removeAllViews()
        startButton = null; statusLabel = null; statusDot = null; statEngine = null; statTokens = null; statLocal = null
        loginStatus = null; loginButton = null
        frame.addView(ThemeBackgroundView(this, theme), FrameLayout.LayoutParams(-1, -1))
        if (!prefs.onboarded) renderOnboarding() else renderMain()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (theme.isLight) flags else 0, flags)
        } else {
            window.decorView.systemUiVisibility = if (theme.isLight)
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            else View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    // ------------------------------------------------------------------ onboarding

    private fun renderOnboarding() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), insetTop + dp(24), dp(20), dp(16))
        }
        scroll.addView(content)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (onboardingPage) {
            0 -> {
                content.gravity = Gravity.CENTER_HORIZONTAL
                content.addView(ThemeLogo(this, theme), LinearLayout.LayoutParams(dp(110), dp(110)).apply { topMargin = dp(40) })
                content.addView(title(theme.t("Chess AI"), theme.h1 * 1.3f).apply { gravity = Gravity.CENTER }, lp(top = 24))
                content.addView(body("Dein Zugberater fürs Schachbrett – direkt über deiner Schach-App.", theme.body + 1).apply { gravity = Gravity.CENTER }, lp(top = 12))
                for ((i, t) in listOf(
                    "Erkennt das Brett auf dem Bildschirm – lokal, meist ohne Tokens",
                    "Stockfish rechnet den besten Zug direkt auf dem Handy",
                    "Leuchtende Marker zeigen Figur und Zielfeld",
                    "Dauer-Scan: der nächste Vorschlag kommt von selbst",
                ).withIndex()) content.addView(step(i + 1, t), lp(top = if (i == 0) 32 else 12))
            }
            1 -> {
                content.addView(title(theme.t("Wähle dein Design"), theme.h1), lp())
                content.addView(body("Ändert alles: App, Overlay-Button, Zug-Marker, Menü. Später jederzeit unter DESIGN umstellbar.", theme.body, theme.muted), lp(top = 8))
                addThemeGrid(content)
            }
            else -> {
                content.addView(title(theme.t("Fast fertig"), theme.h1), lp(top = 8))
                content.addView(body("Beim Start fragt Android zwei Dinge ab:", theme.body, theme.muted), lp(top = 8))
                content.addView(step(1, "„Über anderen Apps einblenden“ – für den Button und die Marker über deiner Schach-App."), lp(top = 16))
                content.addView(step(2, "Bildschirmaufnahme – „Gesamter Bildschirm“ wählen, damit die App das Brett sieht."), lp(top = 12))
                content.addView(step(3, "Tipp: Partie aus der Grundstellung starten – dann lernt die App die Figuren ganz ohne AI."), lp(top = 12))
                content.addView(body("Fairplay: Engine-Hilfe gegen Menschen ist auf Schach-Plattformen verboten. Nutze die App zum Lernen und gegen Computer-Gegner.", theme.small, theme.muted), lp(top = 24))
            }
        }

        // bottom bar: dots + navigation
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), insetBottom + dp(16))
        }
        if (onboardingPage > 0) bar.addView(button(theme.t("Zurück"), theme.surface, theme.text) { onboardingPage--; render() },
            LinearLayout.LayoutParams(dp(110), dp(54)))
        bar.addView(PageDots(3, onboardingPage), LinearLayout.LayoutParams(0, dp(20), 1f))
        val last = onboardingPage == 2
        bar.addView(button(theme.t(if (last) "Los geht's" else "Weiter"), theme.accent, theme.onAccent) {
            if (last) { prefs.onboarded = true; currentTab = 0 } else onboardingPage++
            render()
        }, LinearLayout.LayoutParams(dp(if (last) 150 else 130), dp(54)))
        col.addView(bar)
    }

    private inner class PageDots(private val n: Int, private val sel: Int) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = dp(4).toFloat(); val gap = dp(14).toFloat()
            val x0 = width / 2f - (n - 1) * gap / 2
            for (i in 0 until n) {
                p.color = if (i == sel) theme.accent else alpha(theme.muted, 120)
                c.drawCircle(x0 + i * gap, height / 2f, if (i == sel) r * 1.4f else r, p)
            }
        }
    }

    private fun addThemeGrid(parent: LinearLayout) {
        val ids = ThemeId.values().toList()
        var row: LinearLayout? = null
        for ((i, id) in ids.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(row, lp(top = 14))
            }
            val t = Themes.of(id)
            val selected = id == prefs.themeId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = theme.tile(this@MainActivity, selected)
                setPadding(dp(10), dp(10), dp(12), dp(14))
                isClickable = true
                setOnClickListener {
                    prefs.themeId = id; Themes.invalidate()
                    OverlayService.onThemeChanged()
                    render()
                }
            }
            card.addView(ThemePreview(this, t), LinearLayout.LayoutParams(-1, -2))
            card.addView(TextView(this).apply {
                text = id.title; theme.styleTitle(this, theme.tileSize + 1, theme.tileTextColor(selected))
                setPadding(dp(2), dp(8), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = id.tagline; theme.styleBody(this, theme.small - 1, if (selected) theme.tileTextColor(true) else theme.muted)
                setPadding(dp(2), dp(4), 0, 0)
            })
            row!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 2 == 1) leftMargin = dp(10) })
        }
        if (ids.size % 2 == 1) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
    }

    // ------------------------------------------------------------------ main (tabs)

    private fun renderMain() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), insetTop + dp(12), dp(18), dp(10))
        }
        header.addView(ThemeLogo(this, theme), LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(title(theme.t("Chess AI"), theme.h2 + 4))
        titles.addView(body(TABS[currentTab].first.lowercase().replaceFirstChar { it.uppercase() }.let {
            when (currentTab) { 0 -> "Overlay & Status"; 1 -> "Bilderkennung"; 2 -> "Spiel-Einstellungen"; else -> "Aussehen" }
        }, theme.small, theme.muted).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(titles)
        col.addView(header)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(content)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (currentTab) {
            0 -> buildStartTab(content)
            1 -> buildAiTab(content)
            2 -> buildGameTab(content)
            else -> buildDesignTab(content)
        }
        col.addView(tabBar())
        refreshStatus()
        refreshLogin()
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = theme.panel(this@MainActivity, overlay = true)
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        for ((i, tab) in TABS.withIndex()) {
            val selected = i == currentTab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                if (selected) background = theme.tile(this@MainActivity, true)
                setPadding(0, dp(6), 0, dp(8))
                isClickable = true
                setOnClickListener { if (currentTab != i) { currentTab = i; render() } }
            }
            val color = if (selected) theme.tileTextColor(true) else theme.muted
            item.addView(TextView(this).apply {
                text = tab.second; textSize = 18f; setTextColor(color); gravity = Gravity.CENTER
            })
            item.addView(TextView(this).apply {
                text = theme.t(tab.first); theme.styleTitle(this, (theme.label).coerceAtMost(14f), color); gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0); maxLines = 1
            })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(10), dp(4), dp(10), insetBottom + dp(8)) }
        wrap.addView(bar)
        return wrap
    }

    // ---- START
    private fun buildStartTab(root: LinearLayout) {
        val status = panel(root, top = 6)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusLabel = title("", theme.label + 2).apply { setPadding(dp(10), 0, 0, 0) }
        statusRow.addView(statusLabel)
        status.addView(statusRow)
        startButton = button("", theme.accent, theme.onAccent, theme.buttonSize + 2) {
            if (OverlayService.running) stopOverlay() else startOverlay()
        }
        status.addView(startButton, lp(top = 14, height = 64))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statEngine = statChip(stats, "Engine")
        statTokens = statChip(stats, "Tokens")
        statLocal = statChip(stats, "Lokal")
        status.addView(stats, lp(top = 14))

        val quick = section(root, "Schnell")
        quick.addView(toggleRow("Dauer-Scan", "Nächster Vorschlag kommt von selbst", prefs.autoMode) { prefs.autoMode = it })
        quick.addView(toggleRow("Auto-Zug", "Die App tippt den Zug selbst (nur gegen Computer!)", prefs.autoPlay) { on ->
            prefs.autoPlay = on
            if (on) { prefs.autoMode = true; if (!ChessTapService.isEnabled(this)) showAutoPlaySetup() }
        }, lp(top = 12))

        val how = section(root, "So geht's")
        listOf(
            "START antippen und den ganzen Bildschirm freigeben.",
            "Schach-App öffnen, den Button neben das Brett ziehen.",
            "Partie aus der Grundstellung starten – die App lernt die Figuren ohne AI.",
            "Antippen = neu scannen, lange drücken = Menü.",
        ).forEachIndexed { i, t -> how.addView(step(i + 1, t), lp(top = if (i == 0) 4 else 10)) }
        root.addView(body("Fairplay: In Online-Partien gegen Menschen ist Engine-Hilfe verboten. Nutze die App zum Lernen, für Analysen und gegen Computer-Gegner.", theme.small, theme.muted), lp(top = 16))
    }

    // ---- KI
    private fun buildAiTab(root: LinearLayout) {
        val rec = section(root, "Erkennung", top = 6)
        rec.addView(body("Figuren werden lokal erkannt und die Partie wird mitverfolgt – 0 Tokens. AI nur als Joker, wenn ein Figuren-Design neu ist.", theme.small, theme.muted))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val idx = when (prefs.provider) { AiProvider.NONE -> 0; AiProvider.CHATGPT_LOGIN -> 1; else -> 2 }
        listOf("Lokal", "ChatGPT", "API-Key").forEachIndexed { i, t ->
            tile(tiles, t, i == idx) {
                prefs.provider = when (i) {
                    0 -> AiProvider.NONE
                    1 -> AiProvider.CHATGPT_LOGIN
                    else -> if (prefs.provider == AiProvider.OPENAI) AiProvider.OPENAI else AiProvider.CLAUDE
                }
                render()
            }
        }
        rec.addView(tiles, lp(top = 12))
        rec.addView(body(when (idx) {
            0 -> "Komplett offline. Starte eine Partie aus der Grundstellung oder lass die App in den ersten Zügen scannen – dann lernt sie das Figuren-Design selbst."
            1 -> "Nutzt dein ChatGPT-Abo (Go, Plus, Pro oder Business). Ohne Abo bitte LOKAL wählen."
            else -> "Eigener API-Key. Kostet meist nur Bruchteile eines Cents pro Figuren-Design."
        }, theme.body), lp(top = 12))

        if (idx == 1) {
            val login = section(root, "ChatGPT-Login")
            loginStatus = body("", theme.body)
            login.addView(loginStatus)
            loginButton = button("", theme.accent, theme.onAccent) {
                if (prefs.chatGptTokens != null) logoutChatGpt() else loginChatGpt()
            }
            login.addView(loginButton, lp(top = 10, height = 54))
        }
        if (idx == 2) {
            val api = section(root, "API-Key")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Claude", "OpenAI").forEachIndexed { i, t ->
                tile(row, t, (i == 0) == (prefs.provider == AiProvider.CLAUDE)) {
                    prefs.provider = if (i == 0) AiProvider.CLAUDE else AiProvider.OPENAI; render()
                }
            }
            api.addView(row)
            if (prefs.provider == AiProvider.CLAUDE) {
                api.addView(fieldLabel("Anthropic API-Key"))
                api.addView(field("sk-ant-…", prefs.claudeKey, true) { prefs.claudeKey = it })
                api.addView(link("Key bei Anthropic erstellen", "https://console.anthropic.com/settings/keys"))
                api.addView(fieldLabel("Modell"))
                api.addView(field("claude-opus-5-5", prefs.claudeModel, false) { prefs.claudeModel = it })
                api.addView(body("Tipp: claude-haiku-5-5 ist günstiger und reicht meist.", theme.small, theme.muted), lp(top = 6))
            } else {
                api.addView(fieldLabel("OpenAI API-Key"))
                api.addView(field("sk-…", prefs.openAiKey, true) { prefs.openAiKey = it })
                api.addView(link("Key bei OpenAI erstellen", "https://platform.openai.com/api-keys"))
                api.addView(fieldLabel("Modell"))
                api.addView(field("gpt-5-mini", prefs.openAiModel, false) { prefs.openAiModel = it })
            }
            api.addView(body("Claude-Abo-Login ist für fremde Apps nicht erlaubt – für Claude geht nur ein API-Key.", theme.small, theme.muted), lp(top = 10))
        }
        val stats = section(root, "Verbrauch")
        stats.addView(body("AI-Aufrufe: ${prefs.aiCalls} · Tokens: ${prefs.totalTokens} · lokal erkannt: ${prefs.localHits}×", theme.body))
    }

    // ---- SPIEL
    private fun buildGameTab(root: LinearLayout) {
        val color = section(root, "Ich spiele", top = 6)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val values = listOf(0, 1, -1)
        listOf("Auto", "Weiß", "Schwarz").forEachIndexed { i, t ->
            tile(tiles, t, values[i] == prefs.myColor) { prefs.myColor = values[i]; render() }
        }
        color.addView(tiles)
        color.addView(body("Auto = die Farbe, die unten auf dem Brett steht.", theme.small, theme.muted), lp(top = 8))

        val engine = section(root, "Engine")
        val thinkLabel = fieldLabel("")
        fun setThink(ms: Long) { thinkLabel.text = theme.t("Bedenkzeit  ${"%.1f".format(ms / 1000.0)} s") }
        setThink(prefs.thinkMs)
        engine.addView(thinkLabel)
        engine.addView(ThemedSlider(this, theme, 48, ((prefs.thinkMs - 200) / 100).toInt()) { p ->
            val ms = 200L + p * 100L; prefs.thinkMs = ms; setThink(ms)
        }, lp(top = 6))
        engine.addView(toggleRow("Stockfish", "Starke Engine nutzen (sonst eingebaute Engine)", prefs.useStockfish) { prefs.useStockfish = it }, lp(top = 14))

        val play = section(root, "Automatik")
        play.addView(toggleRow("Dauer-Scan", "Bester Zug erscheint automatisch, sobald du dran bist", prefs.autoMode) { prefs.autoMode = it })
        play.addView(toggleRow("Auto-Zug", "Die App tippt den Zug selbst (nur gegen Computer!)", prefs.autoPlay) { on ->
            prefs.autoPlay = on
            if (on) { prefs.autoMode = true; if (!ChessTapService.isEnabled(this)) showAutoPlaySetup() }
        }, lp(top = 12))
    }

    // ---- DESIGN
    private fun buildDesignTab(root: LinearLayout) {
        root.addView(body("Das Design ändert App, Overlay-Button, Zug-Marker, Menü und Sprechblase.", theme.body, theme.muted), lp(top = 4))
        addThemeGrid(root)
        root.addView(button(theme.t("Onboarding nochmal ansehen"), theme.surface, theme.text) {
            prefs.onboarded = false; onboardingPage = 0; render()
        }, lp(top = 20, height = 54))
        root.addView(body("Schriften: Press Start 2P & VT323 (SIL OFL). Engine: Stockfish (GPLv3).", theme.small, theme.muted), lp(top = 14))
    }

    // ------------------------------------------------------------------ ChatGPT login

    private fun refreshLogin() {
        val st = loginStatus ?: return
        val b = loginButton ?: return
        val t = prefs.chatGptTokens
        when {
            loginRunning -> { st.text = "Anmeldung läuft …"; b.text = theme.t("Im Browser bestätigen") }
            t != null -> { st.text = "Angemeldet" + (t.email?.let { " als $it" } ?: "") + " · Modell ${prefs.openAiModel}"; b.text = theme.t("Abmelden") }
            else -> { st.text = "Nicht angemeldet"; b.text = theme.t("Mit ChatGPT anmelden") }
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
            .setTitle("Auto-Zug einrichten")
            .setView(text)
            .setPositiveButton("Bedienungshilfen öffnen") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
        startButton?.let { b ->
            b.text = theme.t(if (on) "Stop" else "Start")
            b.background = theme.button(this, if (on) theme.danger else theme.accent)
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else theme.onAccent)
        }
        statusLabel?.text = theme.t(if (on) "Overlay aktiv" else "Overlay aus")
        statusDot?.background = rounded(if (on) theme.success else theme.danger, dpf(6f))
        statEngine?.text = if (Engines.stockfishBinary(this) != null) "Stockfish" else "Eingebaut"
        statTokens?.text = "${prefs.totalTokens}"
        statLocal?.text = "${prefs.localHits}×"
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
        frame.postDelayed({ refreshStatus() }, 400)
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
        frame.postDelayed({ refreshStatus(); moveTaskToBack(true) }, 600)
    }

    // ------------------------------------------------------------------ themed widgets

    private fun lp(top: Int = 0, height: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (height > 0) dp(height) else ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun title(t: String, size: Float, color: Int = theme.text) = TextView(this).apply {
        text = t; theme.styleTitle(this, size, color)
    }

    private fun body(t: String, size: Float = theme.body, color: Int = theme.text) = TextView(this).apply {
        text = t; theme.styleBody(this, size, color); setLineSpacing(0f, 1.05f)
    }

    private fun fieldLabel(t: String) = title(theme.t(t), theme.label, theme.accent.takeIf { !theme.isLight } ?: theme.text).apply {
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun panel(root: LinearLayout, top: Int = 16): LinearLayout {
        val pad = theme.panelPadding(this)
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.panel(this@MainActivity)
            setPadding(pad, pad, pad + dp(4), pad + dp(4))
        }
        root.addView(p, lp(top = top))
        return p
    }

    private fun section(root: LinearLayout, name: String, top: Int = 16): LinearLayout {
        val p = panel(root, top)
        p.addView(title(theme.t(name), theme.h2), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return p
    }

    private fun button(t: String, fill: Int, textColor: Int, size: Float = theme.buttonSize, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        theme.styleTitle(this, size, textColor)
        background = theme.button(this@MainActivity, fill)
        setPadding(dp(10), 0, dp(14), dp(4))
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(8, size.toInt().coerceAtLeast(9), 1, TypedValue.COMPLEX_UNIT_SP)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tile(parent: LinearLayout, t: String, selected: Boolean, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = theme.t(t)
            gravity = Gravity.CENTER
            theme.styleTitle(this, theme.tileSize, theme.tileTextColor(selected))
            background = theme.tile(this@MainActivity, selected)
            setPadding(dp(6), dp(14), dp(10), dp(16))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(7, theme.tileSize.toInt().coerceAtLeast(8), 1, TypedValue.COMPLEX_UNIT_SP)
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(v, LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (parent.childCount > 0) leftMargin = dp(8) })
        return v
    }

    private fun statChip(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = theme.tile(this@MainActivity, false)
            setPadding(dp(4), dp(10), dp(6), dp(12))
        }
        box.addView(title(theme.t(label), (theme.label - 1).coerceAtLeast(7f), theme.muted))
        val value = body("", theme.body + 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0); maxLines = 1 }
        box.addView(value)
        parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount > 0) leftMargin = dp(8)
        })
        return value
    }

    private fun toggleRow(name: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(theme.t(name), theme.label + 2))
        texts.addView(body(desc, theme.small, theme.muted).apply { setPadding(0, dp(4), dp(8), 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ThemedToggle(this, theme, value, onChange))
        return row
    }

    private fun step(n: Int, t: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = FrameLayout(this).apply { background = theme.tile(this@MainActivity, n % 2 == 1) }
        badge.addView(title("$n", theme.tileSize, theme.tileTextColor(n % 2 == 1)).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1))
        row.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(body(t, theme.body).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun field(hint: String, value: String, secret: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        theme.styleBody(this, theme.body, theme.text)
        setHintTextColor(theme.muted)
        isSingleLine = true
        background = theme.field(this@MainActivity)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun link(t: String, url: String) = body("→ $t", theme.body, theme.fromColor).apply {
        setPadding(0, dp(8), 0, dp(2))
        isClickable = true
        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    @Suppress("unused")
    private fun Drawable.sized(w: Int, h: Int) = apply { setBounds(0, 0, w, h) }
}
