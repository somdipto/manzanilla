package com.agentdeck

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * Translucent, permission-free controller coach shown before local games.
 * OK slides the coach away and launches the selected game directly.
 */
class ControllerCoachActivity : Activity() {
    private lateinit var coach: ControllerCoachView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        coach = ControllerCoachView(this, intent.getStringExtra("profile") ?: "mario") {
            launchGame()
        }
        setContentView(coach)
    }

    private fun launchGame() {
        try {
            startActivity(android.content.Intent().apply {
                setClassName("com.retroarch.ra32",
                    "com.retroarch.browser.retroactivity.RetroActivityFuture")
                putExtra("ROM", intent.getStringExtra("ROM"))
                putExtra("LIBRETRO", intent.getStringExtra("LIBRETRO"))
                putExtra("CONFIGFILE", intent.getStringExtra("CONFIGFILE"))
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        } finally {
            finish()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in listOf(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_CALL)) {
            coach.dismiss()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return true
    }
}

private class ControllerCoachView(
    context: android.content.Context,
    private val profile: String,
    private val onDismissed: () -> Unit
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(220, 255, 255, 255)
    }
    private val bold = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    private var progress = 0f
    private var closing = false
    private var ok = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun text(color: Int, size: Float, weight: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size
        typeface = if (weight) bold else android.graphics.Typeface.create("sans-serif-medium", 0)
    }

    override fun onDraw(c: Canvas) {
        val ease = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        fill.color = Color.argb((55 * ease).toInt(), 32, 20, 10)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val right = width - 20f
        val panelW = 405f
        val left = right - panelW + (1f - ease) * (panelW + 35f)
        val panel = RectF(left, 18f, right, height - 18f)
        fill.color = Color.argb(236, 255, 249, 235)
        fill.setShadowLayer(18f, -4f, 8f, Color.argb(80, 36, 23, 13))
        c.drawRoundRect(panel, 34f, 34f, fill)
        fill.clearShadowLayer()
        c.drawRoundRect(panel, 34f, 34f, stroke)
        c.drawRoundRect(RectF(panel.left + 4f, panel.top + 4f, panel.right - 4f, panel.bottom - 4f),
            30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(95, 250, 190, 35); style = Paint.Style.STROKE; strokeWidth = 1.4f
            })

        val title = if (profile == "mario") "MARIO CONTROLS" else "PLAYSTATION CONTROLS"
        c.drawText(title, panel.left + 26f, panel.top + 42f, text(Color.rgb(47, 47, 47), 21f, true))
        c.drawText("PHYSICAL KEYS ARE READY", panel.left + 26f, panel.top + 65f,
            text(Color.rgb(0, 158, 78), 10f, true))

        val rows = if (profile == "mario") listOf(
            "1" to "UP", "4" to "DOWN", "5" to "LEFT", "6" to "RIGHT",
            "8" to "SELECT", "9" to "START", "TOP LEFT" to "B", "TOP RIGHT" to "A"
        ) else listOf(
            "1" to "UP", "4" to "DOWN", "5" to "LEFT", "6" to "RIGHT",
            "8" to "SELECT", "9" to "START", "RIGHT KEYS" to "△  ○  □  ×"
        )
        rows.forEachIndexed { i, row ->
            val cols = 2
            val col = i % cols; val line = i / cols
            val x = panel.left + 25f + col * 177f
            val y = panel.top + 86f + line * 58f
            val card = RectF(x, y, x + 166f, y + 48f)
            fill.color = Color.argb(176, 255, 254, 248)
            c.drawRoundRect(card, 17f, 17f, fill)
            c.drawRoundRect(card, 17f, 17f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(155, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f
            })
            fill.color = when (i % 3) { 0 -> Color.rgb(170, 21, 27); 1 -> Color.rgb(241, 191, 0); else -> Color.rgb(0, 168, 89) }
            c.drawCircle(card.left + 27f, card.centerY(), 16f, fill)
            val keyPaint = text(Color.WHITE, if (row.first.length > 3) 7f else 10f, true)
            c.drawText(row.first, card.left + 27f - keyPaint.measureText(row.first) / 2f,
                card.centerY() + 3.5f, keyPaint)
            c.drawText(row.second, card.left + 53f, card.centerY() + 4f,
                text(Color.rgb(48, 48, 48), 11f, true))
        }

        val footer = if (profile == "mario") "2 LOAD  •  3 SAVE  •  0 EXIT" else "0 EXIT  •  AUTO-SAVE ENABLED"
        c.drawText(footer, panel.left + 27f, panel.bottom - 82f,
            text(Color.rgb(170, 21, 27), 10f, true))
        ok = RectF(panel.left + 24f, panel.bottom - 68f, panel.right - 24f, panel.bottom - 18f)
        fill.color = Color.rgb(0, 168, 89)
        c.drawRoundRect(ok, 25f, 25f, fill)
        val okText = "OK  —  START PLAYING"
        val okPaint = text(Color.WHITE, 12f, true)
        c.drawText(okText, ok.centerX() - okPaint.measureText(okText) / 2f, ok.centerY() + 4f, okPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && ok.contains(event.x, event.y)) dismiss()
        return true
    }

    fun dismiss() {
        if (closing) return
        closing = true
        ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onDismissed()
            })
            start()
        }
    }
}
