package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Stores the Gemini key encrypted with an Android Keystore AES key. Never logged, never in the APK. */
class SecretStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("gem", Context.MODE_PRIVATE)
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("manz_gem", null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder("manz_gem", android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun has() = prefs.contains("k")
    fun save(v: String) {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("k", Base64.encodeToString(c.iv + c.doFinal(v.trim().toByteArray()), Base64.NO_WRAP)).apply()
    }
    fun load(): String? { return try {
        val raw = Base64.decode(prefs.getString("k", null) ?: return null, Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    } catch (e: Throwable) { Log.e("GV", "key unreadable"); null } }
    fun clear() = prefs.edit().clear().apply()
}

/**
 * Live interpreter on the Gemini Live translation model. Two sessions are kept open (guest to reception language,
 * reception to guest language). The mic feeds only the session of the current turn.
 * Model name and message shapes follow https://ai.google.dev/gemini-api/docs/live-api/live-translate
 */
class GeminiVoice(private val ctx: Context, private val push: (JSONObject) -> Unit, private val store: SecretStore) {
    companion object {
        // Model names live here only. Both are preview or current names from ai.google.dev/gemini-api/docs/models.
        const val MODEL = "gemini-3.5-live-translate-preview"
    }
    @Volatile private var lastSpeech = 0L
    private var startedAt = 0L
    @Volatile private var running = false
    @Volatile private var turn = "guest"
    @Volatile private var muteUntil = 0L
    private var guest = "en"; private var staff = "es"
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    private inner class Sess(val side: String, val from: String, val to: String) {
        var ws: WebSocket? = null
        @Volatile var ready = false
        val inBuf = StringBuilder(); val outBuf = StringBuilder()
        @Volatile var speaking = false
        var tries = 0
        var tIn = 0L; var tOut = 0L; var tAud = 0L
        fun handle(raw: String) {
            val j = try { JSONObject(raw) } catch (_: Throwable) { return }
            if (j.has("setupComplete")) { ready = true; tries = 0; Log.i("GV", "$side session ready"); if (sessions.all { it.ready }) push(ev("listening")); return }
            val sc = j.optJSONObject("serverContent") ?: return
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { if (tIn == 0L) tIn = System.currentTimeMillis(); inBuf.append(it); partial() }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { if (tOut == 0L) tOut = System.currentTimeMillis(); outBuf.append(it); partial() }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { if (tAud == 0L) tAud = System.currentTimeMillis(); playPcm(Base64.decode(it, Base64.DEFAULT)); if (!speaking) { speaking = true; push(ev("speaking")) } }
            }
            if (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")) finish()
        }
        fun partial() { val t = if (outBuf.isNotEmpty()) outBuf.toString() else inBuf.toString(); push(ev("listening").put("transcript", true).put("partial", true).put("source", "input").put("text", outBuf.toString()).put("original", inBuf.toString()).put("side", side)) }
        fun finish() {
            if (outBuf.isEmpty() && inBuf.isEmpty()) return
            Log.i("GV", "$side turn done, in=${inBuf.length} chars out=${outBuf.length} chars")
            push(ev("speaking").put("transcript", true).put("source", "output").put("text", outBuf.toString().trim()).put("original", inBuf.toString().trim()).put("side", side).put("from", from).put("to", to))
            if (tIn > 0) push(JSONObject().put("type", "metrics").put("text", "$from to $to: translated text +${if (tOut > 0) tOut - tIn else -1} ms, voice +${if (tAud > 0) tAud - tIn else -1} ms after first words heard"))
            tIn = 0; tOut = 0; tAud = 0
            inBuf.setLength(0); outBuf.setLength(0); speaking = false
            muteUntil = System.currentTimeMillis() + 600
        }
    }
    private val sessions = ArrayList<Sess>()
    private fun ev(phase: String) = JSONObject().put("type", "reception_event").put("phase", phase)
    private fun fail(msg: String) { Log.e("GV", msg); push(ev("error").put("text", msg)); stop() }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun retry(s: Sess, apiKey: String): Boolean {
        if (!running || s.tries >= 5) return false
        s.tries++; s.ready = false; push(ev("connecting").put("text", "Reconnecting"))
        Log.i("GV", "${s.side} reconnect ${s.tries}")
        main.postDelayed({ if (running) open(s, apiKey) }, 1500L * s.tries); return true
    }
    private fun open(s: Sess, apiKey: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        s.ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject().put("model", "models/$MODEL").put("generationConfig", JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO")).put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
                    .put("translationConfig", JSONObject().put("targetLanguageCode", s.to).put("echoTargetLanguage", false))))
                webSocket.send(setup.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = s.handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = s.handle(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running) return
                val code = response?.code
                if (code != 400 && code != 401 && code != 403 && retry(s, apiKey)) return
                fail(if (code == 400 || code == 401 || code == 403) "Gemini rejected the key ($code). Check the key in settings." else if (t is java.net.UnknownHostException || t is java.net.ConnectException || t is java.net.SocketTimeoutException) "No internet. Live translation needs a connection." else "Connection to Gemini failed: ${t.javaClass.simpleName}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (running && code != 1000 && !retry(s, apiKey)) fail("Gemini closed the session ($code).") }
        })
    }

    fun setTurn(side: String) {
        val old = sessions.firstOrNull { it.side == turn }; turn = side
        old?.ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        Log.i("GV", "turn=$side")
    }

    fun start(guestLang: String, staffLang: String, turnMode: String = "guest", stubWav: String? = null) {
        if (running) return
        val k = store.load()
        if (k.isNullOrBlank()) { push(ev("error").put("text", "Add your Gemini key to start live translation.").put("need_key", true)); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && stubWav == null) { push(ev("error").put("text", "Microphone permission is needed for live translation.")); return }
        running = true; guest = guestLang; staff = staffLang; turn = turnMode
        push(ev("connecting"))
        sessions.clear(); sessions.add(Sess("guest", guest, staff)); sessions.add(Sess("staff", staff, guest))
        if (k == "STUB" && stubWav != null) { runStub(stubWav); return }
        sessions.forEach { open(it, k) }
        Thread {
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
                rec = r; aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) try { android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true } } catch (_: Throwable) { null } else null; Log.i("GV", "aec=${aec != null}"); r.startRecording()
                val buf = ByteArray(3200); var lastLvl = 0L; var lastTick = 0L; startedAt = System.currentTimeMillis(); lastSpeech = startedAt
                while (running) {
                    val n = r.read(buf, 0, buf.size); if (n <= 0) continue
                    var sum = 0.0; var q = 0
                    while (q + 1 < n) { val v = ((buf[q + 1].toInt() shl 8) or (buf[q].toInt() and 0xff)).toShort().toDouble(); sum += v * v; q += 2 }
                    val muted = aec == null && (System.currentTimeMillis() < muteUntil || sessions.any { it.speaking })
                    val now = System.currentTimeMillis()
                    if (now - lastLvl > 90) { lastLvl = now; push(JSONObject().put("type", "mic_level").put("level", if (muted) 0.0 else Math.min(1.0, Math.sqrt(sum / maxOf(1, n / 2)) / 32768.0 * 6))) }
                    val rms = Math.sqrt(sum / maxOf(1, n / 2)); if (rms > 500) lastSpeech = now
                    if (now - lastSpeech > 120000) { push(ev("idle")); stop(); break }
                    if (!muted && now - lastSpeech < 800) { val msg = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString(); for (s in sessions) if (s.ready) s.ws?.send(msg) }
                }
            } catch (e: Throwable) { if (running) fail("Microphone error: ${e.message}") } finally { try { rec?.stop(); rec?.release() } catch (_: Throwable) {} }
        }.start()
    }

    /** Debug stub (key "STUB" + wav path): replays Gemini-shaped server messages so the UI and parser can be tested with no key. */
    private fun runStub(wav: String) {
        Thread {
            try {
                val g = sessions[0]; val st = sessions[1]
                g.handle("{\"setupComplete\":{}}"); st.handle("{\"setupComplete\":{}}")
                val s = sessions.first { it.side == turn }
                Thread.sleep(800)
                for (w in listOf("Hello", " where is", " the breakfast", " room?")) { s.handle(JSONObject().put("serverContent", JSONObject().put("inputTranscription", JSONObject().put("text", w))).toString()); push(JSONObject().put("type", "mic_level").put("level", 0.5)); Thread.sleep(500) }
                for (w in listOf("¿Dónde está", " el comedor", " del desayuno?")) { s.handle(JSONObject().put("serverContent", JSONObject().put("outputTranscription", JSONObject().put("text", w))).toString()); Thread.sleep(300) }
                s.handle("{\"serverContent\":{\"turnComplete\":true}}")
                Thread.sleep(6000)
            } finally { if (running) push(ev("idle")); running = false }
        }.start()
    }

    private fun playPcm(b: ByteArray) {
        try {
            if (track == null) {
                val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
            }
            muteUntil = System.currentTimeMillis() + 800 + (b.size / 48L)
            val n = track!!.write(b, 0, b.size); Log.i("GV", "audio chunk ${b.size}B written=$n")
        } catch (e: Throwable) { Log.e("GV", "playback", e) }
    }

    fun stop() {
        running = false
        sessions.forEach { try { it.ws?.close(1000, "bye") } catch (_: Throwable) {} }
        sessions.clear()
        try { track?.stop(); track?.release() } catch (_: Throwable) {}; track = null
        try { aec?.release() } catch (_: Throwable) {}; aec = null
    }
}
