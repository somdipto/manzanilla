package com.agentdeck.ui

import android.graphics.*
import kotlin.math.*

/** Native call chrome. The centre is the packaged, articulated 3D companion. */
class LiveVoiceScene {
    private val ink = Color.rgb(34, 36, 41)
    private val muted = Color.rgb(100, 104, 112)
    private val stage = Color.rgb(250, 251, 252)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val heading = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }

    fun reset() = Unit

    fun draw(c: Canvas, width: Int, height: Int, now: Long, state: String,
             transcript: String, started: Long, preview: Boolean, reduceMotion: Boolean,
             companionReady: Boolean = false, companionFailed: Boolean = false,
             density: Float = 1f, fontScale: Float = 1f): RectF {
        val phase = state.lowercase()
        val active = phase in listOf("connecting", "listening", "speaking", "working", "saved", "ending")
        val scale = min(width / 854f, height / 480f)
        val ox = (width - 854f * scale) / 2f
        val oy = (height - 480f * scale) / 2f
        val typeScale = fontScale.coerceAtLeast(1f)
        c.drawColor(stage)
        c.save(); c.translate(ox, oy); c.scale(scale, scale)
        label(c, "Orange Reception", 32f, 38f, 17f, ink, true)
        label(c, if (preview) "Preview · microphone off" else "Hotel night reception", 32f, 61f, 11f * typeScale, muted)
        if (!preview && active) {
            val elapsed = ((now - started) / 1000).coerceAtLeast(0)
            text.textSize = 14f; text.typeface = regular
            val clock = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            label(c, clock, 822f - text.measureText(clock), 39f, 14f, muted)
        }
        if (!companionReady) {
            val message = if (companionFailed) "3D companion unavailable. Voice still works." else "Getting your companion ready…"
            text.textSize = 12f * typeScale
            centred(c, fit(message, 390f), 225f, 244f, 12f * typeScale, muted)
        }
        val title = when (phase) {
            "connecting" -> "Connecting…"
            "listening" -> "Ready to listen"
            "speaking" -> "Speaking"
            "working" -> "Working on it"
            "saved" -> "Draft saved"
            "ending" -> "Ending call"
            "error", "disconnected" -> "Let's reconnect"
            else -> "Ready when you are"
        }
        // The supplied desk-phone reference: character left, quiet status right.
        // This is a real call-state label, never a fabricated microphone meter.
        val status = RectF(470f, 174f, 822f, 250f)
        paint.color = if (phase in listOf("error", "disconnected")) Color.rgb(250, 232, 233) else Color.rgb(233, 249, 242)
        c.drawRoundRect(status, 38f, 38f, paint)
        paint.color = if (phase in listOf("error", "disconnected")) Color.rgb(176, 42, 58) else Color.rgb(16, 133, 83)
        for (bar in 0..2) {
            val x = 494f + bar * 8f
            val half = if (bar == 1) 12f else 7f
            c.drawRoundRect(RectF(x, 211f - half, x + 4f, 211f + half), 2f, 2f, paint)
        }
        text.textSize = 17f * typeScale
        centred(c, fit(title, 270f), 670f, 212f - (text.ascent() + text.descent()) / 2f, 17f * typeScale, ink, true)
        val help = when {
            preview -> "Preview only. No microphone or API."
            phase in listOf("error", "disconnected") -> transcript.trim().ifEmpty { "Check Orange's connection, then press green to try again." }
            phase == "connecting" -> "Opening your voice session."
            phase == "working" -> "Checking your hotel request."
            phase == "speaking" -> "You can interrupt me at any time."
            phase == "ending" -> "Closing the microphone and audio."
            active -> "Speak naturally. I'm here."
            else -> "Press the green button to talk."
        }
        text.textSize = 12f * typeScale; text.typeface = regular
        val lineCount = text.breakText(help, true, 338f, null)
        val firstLine = if (lineCount < help.length) help.take(lineCount).substringBeforeLast(' ', help.take(lineCount)) else help
        val remainder = help.removePrefix(firstLine).trim()
        centred(c, firstLine, 646f, 286f, 12f * typeScale, muted)
        if (remainder.isNotEmpty()) centred(c, fit(remainder, 338f), 646f, 286f + 18f * typeScale, 12f * typeScale, muted)
        val buttonHeight = max(52f, 48f * density / scale).coerceAtMost(72f)
        val button = RectF(538f, 400f - buttonHeight, 754f, 400f)
        paint.color = if (active && !preview) Color.rgb(176, 42, 58) else Color.rgb(231, 233, 236)
        c.drawRoundRect(button, 26f, 26f, paint)
        centred(c, if (preview) "Close preview" else if (active) "End call" else "Start call",
            button.centerX(), button.centerY() + 7f, 15f * typeScale,
            if (active && !preview) Color.WHITE else ink, true)
        c.restore()
        return RectF(ox + button.left * scale, oy + button.top * scale, ox + button.right * scale, oy + button.bottom * scale)
    }

    private fun fit(value: String, maxWidth: Float): String {
        if (text.measureText(value) <= maxWidth) return value
        val count = text.breakText(value, true, maxWidth - text.measureText("…"), null)
        return value.take(count) + "…"
    }

    private fun centred(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, medium: Boolean = false) {
        text.textSize = size; text.typeface = if (medium) heading else regular
        label(c, value, x - text.measureText(value) / 2f, y, size, color, medium)
    }

    private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, medium: Boolean = false) {
        text.textSize = size; text.color = color; text.typeface = if (medium) heading else regular
        c.drawText(value, x, y, text)
    }
}
