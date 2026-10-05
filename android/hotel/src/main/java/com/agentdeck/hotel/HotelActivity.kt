package com.agentdeck.hotel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.agentdeck.audio.LiveSpeaker
import com.agentdeck.ui.LiveVoiceScene
import com.agentdeck.ui.LiveCompanionView
import org.json.JSONObject

class HotelActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("orange-hotel", MODE_PRIVATE) }
    private var cloud: CloudClient? = null
    private lateinit var mic: HotelMic
    private val speaker = LiveSpeaker()
    private lateinit var companion: LiveCompanionView
    private lateinit var scene: CallView
    private var phase = "idle"
    private var message = ""
    private var active = false
    private var generation = 0
    private var started = 0L
    private var item = ""
    private var itemBase = 0L
    private var itemReceived = 0L
    private var configVersion = 0
    private var primary = ""
    private var backup = ""
    private var companionReady = false
    private var companionFailed = false
    private var dialog: AlertDialog? = null
    private val pulse = object : Runnable {
        override fun run() {
            scene.invalidate()
            companion.updateStage(true, phase, speaker.currentEnergy(), !android.animation.ValueAnimator.areAnimatorsEnabled(), started, false)
            handler.postDelayed(this, 50)
        }
    }
    private val beat = object : Runnable {
        override fun run() { cloud?.heartbeat(configVersion); handler.postDelayed(this, 25000) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        mic = HotelMic(this, { cloud?.sendAudio(it) == true }, { handler.post { failure("Phone microphone unavailable. Use the operator button.") } })
        primary = prefs.getString("primary", "") ?: ""
        backup = prefs.getString("backup", "") ?: ""
        val root = FrameLayout(this)
        scene = CallView()
        root.addView(scene)
        companion = LiveCompanionView(this) { ready, failed -> companionReady = ready; companionFailed = failed }
        root.addView(companion, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * .49f).toInt(), (resources.displayMetrics.heightPixels * .65f).toInt()).apply { leftMargin = 12; topMargin = 65 })
        val operator = Button(this).apply { setText(R.string.operator); setOnClickListener { stop(); dial(primary, backup, null) } }
        root.addView(operator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        val settings = Button(this).apply { setText(R.string.setup); setOnClickListener { setup() } }
        root.addView(settings, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
        if (prefs.getString("base", "").isNullOrEmpty()) setup() else configure()
    }
    private fun configure() {
        cloud?.dispose()
        val gen = ++generation
        cloud = CloudClient(prefs.getString("base", "")!!, prefs.getString("token", "")!!,
            { event -> handler.post { if (gen == generation) onEvent(event) } },
            { bytes -> handler.post { if (gen == generation && active) { speaker.append(bytes); itemReceived += bytes.size / 2; phase = "speaking" } } })
        handler.removeCallbacks(beat); handler.post(beat)
    }
    private fun setup() {
        stop()
        if (cloud != null) {
            val staff = EditText(this).apply { hint = "Hotel staff authorization token"; inputType = 129 }
            AlertDialog.Builder(this).setTitle("Staff authorization").setView(staff)
                .setNegativeButton("Cancel", null).setPositiveButton("Authorize") { _, _ ->
                    cloud?.authorizeStaff(staff.text.toString().trim()) { handler.post { enrollment() } }
                }.show()
        } else enrollment()
    }
    private fun enrollment() {
        // Enrollment belongs to hotel staff. Device token must never be an OpenAI API key.
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val base = EditText(this).apply { hint = "https://orange.example.com"; setText(prefs.getString("base", "")) }
        val token = EditText(this).apply { hint = "Device enrollment token"; inputType = 129 }
        box.addView(base); box.addView(token)
        AlertDialog.Builder(this).setTitle("Staff device enrollment").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val value = base.text.toString().trim().trimEnd('/')
            val secret = token.text.toString().trim()
            if (!value.startsWith("https://") || secret.isBlank()) { failure("HTTPS URL and device token required."); return@setPositiveButton }
            prefs.edit().putString("base", value).putString("token", secret).apply()
            configure()
        }.show()
    }
    private fun start() {
        if (cloud == null) { setup(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        active = true; phase = "connecting"; message = ""; started = System.currentTimeMillis()
        cloud?.start()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 7 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else failure("Microphone permission required for voice. The operator button remains available.")
    }
    private fun stop() {
        active = false; mic.stop(); speaker.stop(); cloud?.stop()
        dialog?.dismiss(); dialog = null
        phase = "idle"; item = ""; message = ""
    }
    private fun failure(text: String) { stop(); phase = "error"; message = text }
    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "config" -> {
                val hotel = event.getJSONObject("hotel")
                configVersion = hotel.getInt("config_version")
                primary = hotel.optString("operator_primary"); backup = hotel.optString("operator_secondary")
                prefs.edit().putString("primary", primary).putString("backup", backup).putString("device_id", event.optString("device_id")).apply()
            }
            "ready" -> if (active) { speaker.start(); mic.start(); phase = "listening" }
            "error" -> failure(event.optString("message"))
            "ended" -> stop()
            "state" -> if (active) phase = event.optString("state", "listening")
            "audio_item" -> if (item != event.optString("item_id")) {
                item = event.optString("item_id"); itemBase = speaker.playedSamples(); itemReceived = 0L
            }
            "interrupt" -> if (active) {
                val heard = (speaker.playedSamples() - itemBase).coerceIn(0L, itemReceived) / 24
                speaker.stop(); speaker.start(); phase = "listening"
                if (item.isNotBlank()) cloud?.send(JSONObject().put("type", "played").put("item_id", item).put("ms", heard))
                item = ""
            }
            "confirm" -> if (active) {
                val id = event.getString("id")
                val args = event.getJSONObject("arguments")
                val text = if (event.optString("tool") == "call_taxi") "Send taxi request to ${args.optString("destination")} at ${args.optString("pickup_time")}?" else args.optString("summary", args.optString("original_request"))
                dialog?.dismiss()
                dialog = AlertDialog.Builder(this).setTitle("Confirm request").setMessage(text)
                    .setPositiveButton("Confirm") { _, _ -> cloud?.send(JSONObject().put("type", "confirmation").put("id", id).put("accepted", true)) }
                    .setNegativeButton("Cancel") { _, _ -> cloud?.send(JSONObject().put("type", "confirmation").put("id", id).put("accepted", false)) }
                    .setOnCancelListener { cloud?.send(JSONObject().put("type", "confirmation").put("id", id).put("accepted", false)) }.show()
            }
            "handoff" -> if (active) dial(event.optString("number"), event.optString("backup"), event.getString("id"))
        }
    }
    private fun dial(number: String, fallback: String, requestId: String?) {
        mic.stop(); speaker.stop()
        val result = JSONObject().put("type", "handoff_result").put("id", requestId)
        try {
            require(number.matches(Regex("\\+[1-9][0-9]{6,14}")))
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            result.put("status", "dialer_opened")
        } catch (_: Exception) {
            result.put("status", "failed")
            AlertDialog.Builder(this).setTitle("Operator unavailable").setMessage("Primary: $number\nBackup: $fallback\nPlease use another telephone or the hotel's emergency contact.").setPositiveButton("OK", null).show()
        }
        if (requestId != null) cloud?.send(result)
        // ACTION_DIAL is intentionally not reported as an answered call.
        phase = "ending"
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_CALL && event.repeatCount == 0) { if (!active) start(); return true }
        if (code == KeyEvent.KEYCODE_ENDCALL && event.repeatCount == 0) { stop(); return true }
        return super.onKeyDown(code, event)
    }
    override fun onResume() { super.onResume(); handler.post(pulse); handler.removeCallbacks(beat); handler.post(beat) }
    override fun onPause() { super.onPause(); stop(); handler.removeCallbacks(pulse); companion.updateStage(false, "idle", 0f, true, 0, false) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); stop(); cloud?.dispose(); companion.disposeStage(); super.onDestroy() }
    inner class CallView : View(this@HotelActivity) {
        private val painter = LiveVoiceScene()
        private var button = RectF()
        override fun onDraw(canvas: Canvas) { button = painter.draw(canvas, width, height, System.currentTimeMillis(), phase, message, started, false, !android.animation.ValueAnimator.areAnimatorsEnabled(), companionReady, companionFailed, resources.displayMetrics.density, resources.configuration.fontScale) }
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP && button.contains(event.x, event.y)) { if (active) stop() else start(); performClick(); return true }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
