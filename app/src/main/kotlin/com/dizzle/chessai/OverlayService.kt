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
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        private const val AUTO_INTERVAL_MS = 1500L
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

    private var button: TextView? = null
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
    @Volatile private var buttonScreenRect = Rect()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        analyzer = Analyzer(PieceStore.load(this), ::encodeBoardJpeg)
        autoMode = prefs.autoMode
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
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
                toast("Bildschirmaufnahme nicht möglich: ${e.message}")
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
        listOf(button, highlight, menu, calibration).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        button = null; highlight = null; menu = null; calibration = null
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

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chess AI Overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_knight)
            .setContentTitle("Chess AI aktiv")
            .setContentText("Tippe auf den ♞-Button über dem Brett")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .setOngoing(true)
            .build()
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
        val b = button ?: return
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (busy) Color.argb(220, 120, 120, 120) else if (autoMode) Color.argb(220, 0, 150, 90) else Color.argb(220, 30, 136, 229))
            setStroke(dp(2), Color.argb(230, 255, 214, 0))
        }
        b.background = bg
        b.text = if (busy) "…" else "♞"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(56)
        val b = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 26f
            elevation = dp(6).toFloat()
        }
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
    }

    private fun clearHighlight() {
        highlight?.let { runCatching { wm.removeView(it) } }
        highlight = null; highlightParams = null
        dotSquares = emptySet()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        if (menu != null) { closeMenu(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.argb(240, 28, 30, 36)); cornerRadius = dp(14).toFloat() }
        }
        fun item(text: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = null
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { closeMenu(); action() }
            })
        }
        item(if (autoMode) "⏸  Auto-Modus ausschalten" else "▶  Auto-Modus einschalten") { toggleAuto() }
        item("🔍  Jetzt analysieren") { analyzeNow(auto = false, forceAi = false) }
        item("🤖  Mit AI neu einlesen") { analyzeNow(auto = false, forceAi = true) }
        item("♻  Neue Partie") {
            worker.execute { analyzer.resetGame(); stableFeatures = null }
            clearHighlight(); toast("Neue Partie – Stellung wird neu erkannt")
        }
        item("▦  Brett manuell festlegen") { startCalibration() }
        if (prefs.manualRect != null) item("✦  Brett automatisch finden") {
            prefs.manualRect = null; worker.execute { analyzer.forgetRect() }; toast("Automatische Brett-Erkennung aktiv")
        }
        item("🧠  Gelernte Figuren löschen") {
            worker.execute { analyzer.library.clear(); analyzer.resetGame(); PieceStore.clear(this) }
            toast("Figuren werden neu gelernt")
        }
        item("⏹  Overlay beenden") { stopSelf() }
        item("✕  Schließen") {}

        val p = overlayParams(dp(270), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val bp = buttonParams!!
        p.x = max(0, min(bp.x - dp(270) + bp.width, screenW - dp(270)))
        p.y = if (bp.y + bp.height + dp(420) < screenH) bp.y + bp.height + dp(4) else max(0, bp.y - dp(424))
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(Color.argb(235, 28, 30, 36)); cornerRadius = dp(14).toFloat() }
        }
        val hint = TextView(this).apply {
            text = "Raster auf das Brett ziehen,\nEcke unten rechts = Größe"
            setTextColor(Color.WHITE); textSize = 13f
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Abbrechen"; isAllCaps = false
            setOnClickListener { endCalibration() }
        })
        bar.addView(Button(this).apply {
            text = "OK"; isAllCaps = false
            setOnClickListener {
                val loc = IntArray(2)
                cv.getLocationOnScreen(loc)
                val r = cv.result()
                val abs = BoardRect(r.x + loc[0], r.y + loc[1], r.size)
                prefs.manualRect = abs
                worker.execute { analyzer.forgetRect(); analyzer.resetGame(); stableFeatures = null }
                endCalibration()
                toast("Brett gespeichert")
            }
        })
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
        if (res.source == Analyzer.Source.AI) {
            if (auto) lastAutoAi = System.currentTimeMillis()
            prefs.totalTokens = prefs.totalTokens + res.inputTokens + res.outputTokens
            prefs.aiCalls = prefs.aiCalls + 1
        } else if (res.ok) {
            prefs.localHits = prefs.localHits + 1
        }
        if (res.ok) {
            PieceStore.save(this, analyzer.library)
            // reference frame for auto mode (taken without our overlays)
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
            if (!auto && error != null) toast(error)
            return
        }
        val rect = res.rect
        if (res.ok && res.myTurn && res.fromVisual >= 0 && rect != null) {
            showHighlight(rect, res.fromVisual, res.toVisual)
            if (!auto || res.source == Analyzer.Source.AI) toast(res.message)
        } else {
            clearHighlight()
            if (!auto || !res.ok && res.source == Analyzer.Source.AI) toast(res.message)
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
        if (autoMode) { startAuto(); toast("Auto-Modus: Züge werden automatisch angezeigt") }
        else { main.removeCallbacks(autoTick); toast("Auto-Modus aus") }
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
                val ignore = HashSet<Int>(dotSquares)
                ignore += squaresUnder(buttonScreenRect, rect)
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

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
