package com.dizzle.chessai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dizzle.chessai.core.Analyzer
import com.dizzle.chessai.core.engine.Engine
import com.dizzle.chessai.core.vision.BoardRect
import com.dizzle.chessai.core.vision.RgbImage
import com.dizzle.chessai.core.vision.SquareFeature
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns the screen capture, the floating button and the highlight overlay.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.dizzle.chessai.STOP"
        const val ACTION_TOGGLE_SCAN = "com.dizzle.chessai.TOGGLE_SCAN"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        private const val AUTO_INTERVAL_MS = 800L
        private const val AUTO_AI_MIN_GAP_MS = 20_000L

        @Volatile var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var analyzer: Analyzer
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureThread = HandlerThread("capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latestImage: Image? = null // only touched on captureHandler
    private var screenW = 0
    private var screenH = 0

    private var engine: Engine? = null

    private var button: PixelFab? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    @Volatile private var bubbleScreenRect = Rect()
    private var buttonParams: WindowManager.LayoutParams? = null
    private var highlight: HighlightView? = null
    private var highlightParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var calibration: View? = null

    @Volatile private var busy = false
    private var autoMode = false
    private var lastAutoAi = 0L
    // auto mode change detection (worker thread only)
    private var stableFeatures: Array<SquareFeature>? = null
    private var pendingFeatures: Array<SquareFeature>? = null
    @Volatile private var dotSquares: Set<Int> = emptySet()
    /** After the dots appear, the next scan becomes the reference (so the user's own move is seen too). */
    @Volatile private var refreshReferenceAt = 0L
    @Volatile private var buttonScreenRect = Rect()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        analyzer = Analyzer(PieceStore.load(this), ::encodeBoardJpeg)
        worker.execute { runCatching { com.dizzle.chessai.core.Openings.warmUp() } }
        autoMode = prefs.autoMode
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_SCAN) {
            if (running) toggleAuto()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (projection == null) {
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            try {
                setupProjection(code, data)
            } catch (e: Exception) {
                say("Bildschirmaufnahme nicht möglich: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (button == null) addButton()
        running = true
        if (autoMode) startAuto()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        autoMode = false
        main.removeCallbacksAndMessages(null)
        listOf(button, highlight, menu, calibration, bubble).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        button = null; highlight = null; menu = null; calibration = null; bubble = null
        captureHandler.post {
            latestImage?.close(); latestImage = null
            virtualDisplay?.release()
            reader?.close()
            runCatching { projection?.stop() }
            captureThread.quitSafely()
        }
        worker.execute { engine?.close(); PieceStore.save(this, analyzer.library) }
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_SCAN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_knight)
            .setContentTitle(if (autoMode) "Chess AI · Dauer-Scan aktiv" else "Chess AI aktiv")
            .setContentText(if (autoMode) "Der beste Zug erscheint automatisch, sobald du dran bist"
                else "Tippe auf den ♞-Button über dem Brett")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (autoMode) "Scan pausieren" else "Dauer-Scan", toggle).build())
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, buildNotification()) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chess AI Overlay", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    // ------------------------------------------------------------------ screen capture

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("keine Freigabe")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, captureHandler)
        val dm = realMetrics()
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: IllegalStateException) { null }
            if (img != null) {
                latestImage?.close()
                latestImage = img
            }
        }, captureHandler)
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "chessai", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler,
        )
    }

    /** Delivers the most recent frame as ARGB pixels (on the capture thread). */
    private fun captureFrame(cb: (RgbImage?) -> Unit) {
        captureHandler.post {
            val img = latestImage
            if (img == null) { cb(null); return@post }
            val rgb = try { toRgb(img) } catch (e: Exception) { null }
            cb(rgb)
        }
    }

    private fun toRgb(img: Image): RgbImage {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val px = IntArray(w * h)
        val row = ByteArray(rs)
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, min(rs, buf.remaining()))
            var i = 0
            val o = y * w
            for (x in 0 until w) {
                px[o + x] = (0xFF shl 24) or ((row[i].toInt() and 255) shl 16) or
                    ((row[i + 1].toInt() and 255) shl 8) or (row[i + 2].toInt() and 255)
                i += ps
            }
        }
        return RgbImage(w, h, px)
    }

    // ------------------------------------------------------------------ overlay windows

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** Makes sure a window really sits at the requested absolute screen position. */
    private fun fixPosition(v: View, p: WindowManager.LayoutParams, x: Int, y: Int) {
        v.post {
            if (v.parent == null) return@post
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dx = x - loc[0]; val dy = y - loc[1]
            if (dx != 0 || dy != 0) {
                p.x += dx; p.y += dy
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    private fun updateButtonLook() {
        button?.state = when {
            busy -> PixelFab.State.BUSY
            autoMode -> PixelFab.State.SCANNING
            else -> PixelFab.State.MANUAL
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(58)
        val b = PixelFab(this).apply { contentDescription = "Chess AI" }
        button = b
        updateButtonLook()
        val p = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        p.x = if (prefs.buttonX >= 0) prefs.buttonX else screenW - size - dp(8)
        p.y = if (prefs.buttonY >= 0) prefs.buttonY else (screenH * 0.12).toInt()
        buttonParams = p
        wm.addView(b, p)
        updateButtonRect()

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; showMenu() }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        wm.updateViewLayout(b, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        prefs.buttonX = p.x; prefs.buttonY = p.y
                        updateButtonRect()
                    } else if (!longPressed) {
                        analyzeNow(auto = false, forceAi = false)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
    }

    private fun updateButtonRect() {
        val p = buttonParams ?: return
        buttonScreenRect = Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    private fun showHighlight(rect: BoardRect, from: Int, to: Int) {
        var v = highlight
        var p = highlightParams
        if (v == null || p == null) {
            v = HighlightView(this)
            p = overlayParams(rect.size, rect.size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            // Android 12+ only passes touches through untrusted overlays with opacity <= 0.8
            p.alpha = 0.8f
            p.x = rect.x; p.y = rect.y
            highlight = v; highlightParams = p
            wm.addView(v, p)
        } else {
            p.x = rect.x; p.y = rect.y; p.width = rect.size; p.height = rect.size
            wm.updateViewLayout(v, p)
        }
        v.visibility = View.VISIBLE
        v.show(from, to)
        fixPosition(v, p, rect.x, rect.y)
        dotSquares = setOf(from, to)
        refreshReferenceAt = System.currentTimeMillis() + 700 // after the pop-in animation
    }

    private fun clearHighlight() {
        highlight?.let { runCatching { wm.removeView(it) } }
        highlight = null; highlightParams = null
        dotSquares = emptySet()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        if (menu != null) { closeMenu(); return }
        hideBubble()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(16))
            background = PixelBox(Pal.PANEL, Pal.INK, dpf(3f), shadowSteps = 2)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply { background = Sprites.knight() }, LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(TextView(this).apply {
            text = "CHESS AI"; textSize = 10f; setTextColor(Pal.GOLD); typeface = PixelFonts.title(this@OverlayService)
            setPadding(dp(8), 0, 0, 0)
        })
        box.addView(head)
        box.addView(CheckerStrip(this, 16), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        })
        fun item(text: String, color: Int = Pal.CREAM, action: () -> Unit) {
            box.addView(TextView(this).apply {
                val label = android.text.SpannableString("> $text")
                label.setSpan(android.text.style.ForegroundColorSpan(Pal.GOLD), 0, 1, 0)
                this.text = label
                textSize = 8.5f
                setTextColor(color)
                typeface = PixelFonts.title(this@OverlayService)
                setPadding(dp(6), dp(11), dp(6), dp(11))
                isClickable = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), PixelBox(Pal.PANEL_HI, Pal.PANEL_HI, dpf(2f), shadowSteps = 0, shadow = 0))
                }
                setOnClickListener { closeMenu(); action() }
            })
        }
        item(if (autoMode) "DAUER-SCAN AUS" else "DAUER-SCAN AN", Pal.GOLD) { toggleAuto() }
        item("JETZT SCANNEN") { analyzeNow(auto = false, forceAi = false) }
        item("MIT AI EINLESEN") { analyzeNow(auto = false, forceAi = true) }
        item("NEUE PARTIE") {
            worker.execute { analyzer.resetGame(); stableFeatures = null }
            clearHighlight(); say("Neue Partie – Stellung wird neu erkannt")
        }
        item("BRETT FESTLEGEN") { startCalibration() }
        if (prefs.manualRect != null) item("BRETT AUTO-SUCHE") {
            prefs.manualRect = null; worker.execute { analyzer.forgetRect() }; say("Brett wird wieder automatisch gesucht")
        }
        item("FIGUREN NEU LERNEN") {
            worker.execute { analyzer.library.clear(); analyzer.resetGame(); PieceStore.clear(this) }
            say("Figuren werden neu gelernt")
        }
        item("OVERLAY BEENDEN", Pal.CORAL) { stopSelf() }
        item("SCHLIESSEN", Pal.MUTED) {}

        val w = dp(250)
        val p = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val bp = buttonParams!!
        p.x = max(0, min(bp.x - w + bp.width, screenW - w))
        p.y = if (bp.y + bp.height + dp(440) < screenH) bp.y + bp.height + dp(6) else max(0, bp.y - dp(446))
        box.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu(); false }
        menu = box
        wm.addView(box, p)
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun startCalibration() {
        if (calibration != null) return
        clearHighlight()
        val init = prefs.manualRect ?: analyzer.lastRect
            ?: BoardRect((screenW * 0.05).toInt(), (screenH - screenW * 0.9).toInt() / 2, (screenW * 0.9).toInt())
        val root = FrameLayout(this)
        val cv = CalibrationView(this, init)
        root.addView(cv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(16))
            background = PixelBox(Pal.PANEL, Pal.INK, dpf(3f), shadowSteps = 2)
        }
        val hint = TextView(this).apply {
            text = "Raster aufs Brett ziehen.\nGoldene Ecke = Größe."
            setTextColor(Pal.CREAM); textSize = 18f; typeface = PixelFonts.body(this@OverlayService)
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun pixelBtn(label: String, fill: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 9f; setTextColor(Pal.INK); typeface = PixelFonts.title(this@OverlayService)
            gravity = Gravity.CENTER
            background = PixelBox(fill, Pal.INK, dpf(2.5f), shadowSteps = 2)
            setPadding(dp(12), dp(12), dp(16), dp(16))
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(pixelBtn("ZURÜCK", Pal.SLATE) { endCalibration() })
        bar.addView(pixelBtn("OK", Pal.GOLD) {
                val loc = IntArray(2)
                cv.getLocationOnScreen(loc)
                val r = cv.result()
                val abs = BoardRect(r.x + loc[0], r.y + loc[1], r.size)
                prefs.manualRect = abs
                worker.execute { analyzer.forgetRect(); analyzer.resetGame(); stableFeatures = null }
                endCalibration()
                say("Brett gespeichert")
            }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), dp(12), dp(12), dp(48))
        })
        val p = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        calibration = root
        button?.visibility = View.GONE
        wm.addView(root, p)
    }

    private fun endCalibration() {
        calibration?.let { runCatching { wm.removeView(it) } }
        calibration = null
        button?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ analysis

    private fun engine(): Engine = engine ?: Engines.create(this, prefs).also { engine = it }

    private fun analyzeNow(auto: Boolean, forceAi: Boolean) {
        if (busy || calibration != null) return
        busy = true
        updateButtonLook()
        // hide our own overlays so they are not part of the screenshot
        highlight?.visibility = View.INVISIBLE
        button?.visibility = View.INVISIBLE
        bubble?.visibility = View.GONE
        main.postDelayed({
            captureFrame { img ->
                main.post { button?.visibility = View.VISIBLE }
                if (img == null) {
                    main.post { finish(null, "Noch kein Bild – bitte erneut tippen", auto) }
                    return@captureFrame
                }
                worker.execute { runAnalysis(img, auto, forceAi) }
            }
        }, 280)
    }

    private fun runAnalysis(img: RgbImage, auto: Boolean, forceAi: Boolean) {
        val allowAi = !auto || System.currentTimeMillis() - lastAutoAi > AUTO_AI_MIN_GAP_MS
        val vision = if (allowAi) Vision.provider(prefs) else null
        val res = try {
            analyzer.analyze(img, Analyzer.Options(
                myColor = prefs.myColor,
                thinkMs = prefs.thinkMs,
                manualRect = prefs.manualRect,
                vision = vision,
                engine = engine(),
                forceAi = forceAi,
            ))
        } catch (e: Exception) {
            Analyzer.Result(false, "Fehler: ${e.message ?: e.javaClass.simpleName}")
        }
        // No board found on a manual tap: keep the capture so the user can send it for diagnosis.
        val res2 = if (!res.ok && res.rect == null && !auto && saveDiagnosticCapture(this, img)) {
            res.copy(message = res.message + " (Aufnahme in Galerie › ChessAI gespeichert)")
        } else res
        reportResult(res2, img, auto)
    }

    private fun reportResult(res: Analyzer.Result, img: RgbImage, auto: Boolean) {
        if (res.source == Analyzer.Source.AI) {
            if (auto) lastAutoAi = System.currentTimeMillis()
            prefs.totalTokens = prefs.totalTokens + res.inputTokens + res.outputTokens
            prefs.aiCalls = prefs.aiCalls + 1
        } else if (res.ok) {
            prefs.localHits = prefs.localHits + 1
        }
        if (res.ok) PieceStore.save(this, analyzer.library)
        // Reference frame for the continuous scan (taken without our overlays). Also after a failed
        // recognition, so the scan only retries once the board changes again.
        if (res.rect != null) {
            stableFeatures = analyzer.features(img, prefs.manualRect)?.second
            pendingFeatures = null
        }
        main.post { finish(res, null, auto) }
    }

    private fun finish(res: Analyzer.Result?, error: String?, auto: Boolean) {
        busy = false
        updateButtonLook()
        if (res == null) {
            highlight?.visibility = View.VISIBLE
            if (!auto && error != null) say(error)
            return
        }
        val rect = res.rect
        if (res.ok && res.myTurn && res.fromVisual >= 0 && rect != null) {
            showHighlight(rect, res.fromVisual, res.toVisual)
            if (!auto || res.source == Analyzer.Source.AI) say(res.message)
        } else {
            clearHighlight()
            if (!auto || !res.ok && res.source == Analyzer.Source.AI) say(res.message)
        }
    }

    // ------------------------------------------------------------------ auto mode

    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoMode || !running) return
            autoStep()
            main.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    private fun toggleAuto() {
        autoMode = !autoMode
        prefs.autoMode = autoMode
        updateButtonLook()
        if (autoMode) { startAuto(); say("Dauer-Scan an: der beste Zug erscheint automatisch, wenn du dran bist") }
        else { main.removeCallbacks(autoTick); say("Dauer-Scan aus – zum Scannen auf den Springer tippen") }
        updateNotification()
    }

    private fun startAuto() {
        main.removeCallbacks(autoTick)
        main.postDelayed(autoTick, 500)
    }

    private fun autoStep() {
        if (busy || calibration != null || menu != null) return
        captureFrame { img ->
            if (img == null) return@captureFrame
            worker.execute {
                if (busy) return@execute
                val fr = analyzer.features(img, prefs.manualRect) ?: return@execute
                val (rect, feats) = fr
                val ref = stableFeatures
                if (ref == null) {
                    main.post { analyzeNow(auto = true, forceAi = false) }
                    return@execute
                }
                val refreshAt = refreshReferenceAt
                if (refreshAt != 0L) {
                    if (System.currentTimeMillis() < refreshAt) return@execute
                    // dots are on screen now: take them into the reference, then watch all 64 squares
                    stableFeatures = feats
                    pendingFeatures = null
                    refreshReferenceAt = 0L
                    return@execute
                }
                val ignore = HashSet(squaresUnder(buttonScreenRect, rect))
                if (!bubbleScreenRect.isEmpty) ignore += squaresUnder(bubbleScreenRect, rect)
                if (Analyzer.changedSquares(ref, feats, ignore) == 0) { pendingFeatures = null; return@execute }
                val pend = pendingFeatures
                if (pend != null && Analyzer.changedSquares(pend, feats, ignore) == 0) {
                    // board changed and is stable now (no animation in progress)
                    pendingFeatures = null
                    main.post { analyzeNow(auto = true, forceAi = false) }
                } else {
                    pendingFeatures = feats
                }
            }
        }
    }

    private fun squaresUnder(r: Rect, board: BoardRect): Set<Int> {
        val out = HashSet<Int>()
        val q = board.square
        for (v in 0 until 64) {
            val x0 = board.x + (v % 8) * q; val y0 = board.y + (v / 8) * q
            if (r.left < x0 + q && r.right > x0 && r.top < y0 + q && r.bottom > y0) out += v
        }
        return out
    }

    private val hideBubbleTask = Runnable { hideBubble() }

    private fun hideBubble() {
        val b = bubble ?: return
        if (b.visibility == View.VISIBLE) {
            b.visibility = View.GONE
            bubbleScreenRect = Rect()
            // squares under the bubble change when it disappears – re-baseline the scan
            if (autoMode) refreshReferenceAt = System.currentTimeMillis() + 250
        }
    }

    /** Pixel speech bubble next to the knight button (replaces Android toasts). */
    private fun say(msg: String) {
        main.post {
            val bp = buttonParams ?: run { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show(); return@post }
            var v = bubble
            var p = bubbleParams
            if (v == null || p == null) {
                v = TextView(this).apply {
                    setTextColor(Pal.CREAM)
                    textSize = 18f
                    typeface = PixelFonts.body(this@OverlayService)
                    background = PixelBox(Pal.PANEL, Pal.GOLD, dpf(2.5f), shadowSteps = 2)
                    setPadding(dp(12), dp(8), dp(16), dp(12))
                    maxWidth = (screenW * 0.72).toInt()
                }
                p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                p.alpha = 0.95f
                bubble = v; bubbleParams = p
                wm.addView(v, p)
            }
            v.text = msg
            val rightSide = bp.x + bp.width / 2 > screenW / 2
            p.gravity = Gravity.TOP or (if (rightSide) Gravity.END else Gravity.START)
            p.x = if (rightSide) max(0, screenW - (bp.x + bp.width)) else max(0, bp.x)
            p.y = if (bp.y + bp.height + dp(90) < screenH) bp.y + bp.height + dp(8) else max(0, bp.y - dp(70))
            v.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(v, p) }
            val view = v
            view.post {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                bubbleScreenRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            }
            main.removeCallbacks(hideBubbleTask)
            main.postDelayed(hideBubbleTask, if (msg.length > 60) 4500 else 2800)
        }
    }
}
