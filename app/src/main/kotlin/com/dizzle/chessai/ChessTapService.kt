package com.dizzle.chessai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used only for AUTO-ZUG: it taps the piece and then the target square.
 * It does not read window content.
 */
class ChessTapService : AccessibilityService() {

    companion object {
        @Volatile var instance: ChessTapService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun tap(x: Float, y: Float, then: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { then(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { then(false) }
        }, main)
        if (!ok) then(false)
    }

    /**
     * Plays a move by tapping the from-square, then the to-square. For promotions the target
     * is tapped once more, which picks the queen in the usual promotion pickers.
     */
    fun playMove(fx: Float, fy: Float, tx: Float, ty: Float, promotion: Boolean, done: (Boolean) -> Unit) {
        tap(fx, fy) { ok1 ->
            if (!ok1) { done(false); return@tap }
            main.postDelayed({
                tap(tx, ty) { ok2 ->
                    if (!ok2 || !promotion) { done(ok2); return@tap }
                    main.postDelayed({ tap(tx, ty) { done(it) } }, 450)
                }
            }, 180)
        }
    }
}
