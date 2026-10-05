package com.agentdeck

import android.app.Activity
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.agentdeck.audio.Chiptune
import com.agentdeck.audio.MicStreamer
import com.agentdeck.audio.LiveSpeaker
import com.agentdeck.audio.PortableUiSounds
import com.agentdeck.cam.CamCapture
import com.agentdeck.cam.FaceTracker
import com.agentdeck.net.BridgeClient
import com.agentdeck.pet.SuhairBehavior
import com.agentdeck.pet.SuhairIntent
import com.agentdeck.pet.SuhairRegion
import com.agentdeck.pet.SuhairScene
import com.agentdeck.ui.DeckView
import com.agentdeck.ui.LiveCompanionView
import com.agentdeck.ui.FeatureBootScene
import com.agentdeck.ui.Themes
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/**
 * MANZANILLA OS v3 — remappable actions, home tiles, screensaver, themes.
 */
class MainActivity : Activity(), BridgeClient.Listener {

    private lateinit var st: DeckState
    private lateinit var view: DeckView
    private lateinit var keymap: KeyMap
    private lateinit var client: BridgeClient
    private lateinit var mic: MicStreamer
    // MVP hardware fallback: Wispr listens to the laptop's microphone. Keep the
    // phone MicStreamer available so a future unit with a working mic can use it.
    private val useDeviceMicrophone = false
    private lateinit var cam: CamCapture
    private lateinit var faceTracker: FaceTracker
    private lateinit var audioManager: AudioManager
    private lateinit var videoTexture: TextureView
    private var saverPlayer: MediaPlayer? = null
    private var bootPlayer: MediaPlayer? = null
    private var bootGeneration = 0
    private var featureBootStarted = false
    private var bootExperienceStartedAt = 0L
    private var faceTrackerRunning = false
    private var petReactionUntil = 0L
    private var lastHandGreetingAt = 0L
    private var handWasRaised = false
    private var observedGesture = 0
    private var gestureStableSince = 0L
    private var lastGestureActionAt = 0L
    private var lastPetVoiceAt = 0L
    private var lastFaceGreetingAt = 0L
    private var petAwaitingHandReset = false
    private var petHandAbsentSince = 0L
    private var latestGestureFeatures = FloatArray(0)
    private var latestGazeFeatures = FloatArray(0)
    private var gazeLockedSince = 0L
    private var gazeLostSince = 0L
    private var openPalmWasRaised = false
    private var lastWaveTrickAt = 0L
    private var gazeAttentionScore = 0f
    private val recentGazeSamples = ArrayDeque<Pair<Long, FloatArray>>()
    private var pendingPetCommand = ""
    private var petTts: TextToSpeech? = null
    private var petTtsReady = false
    private val ui = Handler(Looper.getMainLooper())

    private var pttMode: String? = null
    private var globalDictationOn = false
    private var composerAwaitingFinal = false
    private var handoffArmed = false
    private var lastEndPress = 0L
    private var screenBeforeVoice = Screen.HOME
    private var callHeld = false
    private val liveSpeaker = LiveSpeaker()
    private var liveAvailable = false
    private var liveCallActive = false
    private var liveStartPending = false
    private var liveStopRequested = false
    private var liveEndKeyHandled = false
    private var liveCompanion: LiveCompanionView? = null
    private var companionResumed = false
    private val companionTick = object : Runnable {
        override fun run() {
            if (!companionResumed || !::view.isInitialized) return
            val show = st.screen == Screen.VOICE && view.voiceMode == "live" && !view.quickControlsOpen
            liveCompanion?.updateStage(show, view.liveStatus, liveSpeaker.currentEnergy(),
                !android.animation.ValueAnimator.areAnimatorsEnabled(), st.pttStart, view.livePreview)
            ui.postDelayed(this, if (show) 50L else 150L)
        }
    }
    private var wisprHeld = false
    /** Volume-shaped navigation keys are handled on DOWN. Some ADOC builds
     * route their UP event through AudioService when Home is foreground. */
    private val physicalNavDown = HashSet<Int>()
    private var pendingFileName = "file.bin"
    private var lastInteract = System.currentTimeMillis()
    private val saverAfterMs = 150_000L
    private val saverVideoTick = object : Runnable {
        override fun run() {
            if (::st.isInitialized && ::videoTexture.isInitialized &&
                (st.screen == Screen.SAVER && st.saverMode == "highway" ||
                    st.screen == Screen.SAVER_PICK)) {
                ensureSaverPlayer()
                saverPlayer?.let { if (!it.isPlaying) it.start() }
                if (videoTexture.isAvailable) {
                    videoTexture.getBitmap(426, 240)?.let { next ->
                        view.saverVideoFrame?.takeIf { it !== next && !it.isRecycled }?.recycle()
                        view.saverVideoFrame = next
                        view.invalidate()
                    }
                }
            } else {
                saverPlayer?.let { if (it.isPlaying) it.pause() }
            }
            ui.postDelayed(this, 100L)
        }
    }

    private val pttStarter = Runnable {
        if (callHeld && pttMode == null) {
            // The handset CALL key is the dependable physical Wispr/PC input.
            // Holding behaves like push-to-talk; a quick tap toggles dictation.
            val target = composerTargetForCurrentScreen()
            if (target != null) startAiComposer(target) else {
                globalDictationOn = true
                startPtt("global")
            }
        }
    }
    private val wisprStarter = Runnable {
        if (wisprHeld && pttMode == null) {
            val target = composerTargetForCurrentScreen()
            if (target != null) startAiComposer(target) else {
                globalDictationOn = true
                startPtt("global")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        st = DeckState()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        PortableUiSounds.init(this)
        syncDeviceVolume()
        keymap = KeyMap(this)
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        if(prefs.contains("display_brightness")) setDeviceBrightness(prefs.getFloat("display_brightness",.72f))
        // Dockstation v2 is selected once for this MVP, with Suhair restored
        // as its ambient companion. Later user choices remain respected.
        val dockV2Applied = prefs.getBoolean("dockstation_v2_applied", false)
        Themes.idx = if (dockV2Applied) prefs.getInt("theme", Themes.dockstationIndex)
            else Themes.dockstationIndex
        if (!dockV2Applied) prefs.edit()
            .putInt("theme", Themes.dockstationIndex)
            .putString("selected_pet", "suhair")
            .putString("saver_mode", "pet")
            .putBoolean("dockstation_v2_applied", true)
            .apply()
        st.themePreview = Themes.idx
        st.saverMode = prefs.getString("saver_mode", "mountain") ?: "mountain"
        st.selectedPet = prefs.getString("selected_pet", "manni") ?: "manni"
        st.orbitSmoothMotion = prefs.getBoolean("orbit_smooth_motion", true)
        view = DeckView(this, st)
        client = BridgeClient(this, this)
        mic = MicStreamer(this, client)
        cam = CamCapture(this)
        faceTracker = FaceTracker(this)
        for (label in 1..3) st.petCalibrated[label - 1] = faceTracker.isCalibrated(label)
        st.petGazeDeviceCalibrated = faceTracker.isGazeCalibrated("device")
        st.petGazeLaptopCalibrated = faceTracker.isGazeCalibrated("laptop")
        petTts = TextToSpeech(this) { result ->
            petTtsReady = result == TextToSpeech.SUCCESS
            if (petTtsReady) {
                petTts?.language = Locale.US
                petTts?.setSpeechRate(.93f)
                petTts?.setPitch(1.08f)
            }
        }

        videoTexture = TextureView(this).apply {
            alpha = 1f
            isClickable = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (::st.isInitialized && st.screen == Screen.BOOT && !featureBootStarted)
                        beginFeatureBoot(bootGeneration, null)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    bootPlayer?.release(); bootPlayer = null
                    saverPlayer?.release(); saverPlayer = null
                    return true
                }
            }
        }
        val root = FrameLayout(this).apply {
            addView(videoTexture, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        // Packaged model only: no microphone, network, native JS bridge or touch actions.
        try {
            liveCompanion = LiveCompanionView(this) { ready, failed ->
                view.liveCompanionReady = ready
                view.liveCompanionFailed = failed
                view.invalidate()
            }.also { companion ->
                root.addView(companion, FrameLayout.LayoutParams(1, 1))
                root.addOnLayoutChangeListener layout@{ _, l, t, r, b, _, _, _, _ ->
                    if (companion.parent !== root) return@layout
                    val sceneScale = minOf((r - l) / 854f, (b - t) / 480f)
                    val offsetY = ((b - t) - 480f * sceneScale) / 2f
                    val stageHeight = 300f
                    val stageWidth = (450f * sceneScale).toInt()
                    val stagePixels = (stageHeight * sceneScale).toInt()
                    val stageTop = (offsetY + 88f * sceneScale).toInt()
                    val existing = companion.layoutParams as? FrameLayout.LayoutParams
                    // Setting identical LayoutParams here recursively schedules layout
                    // and clears WebGL's drawing surface on every Android frame.
                    if (existing?.width != stageWidth || existing.height != stagePixels || existing.topMargin != stageTop) {
                        companion.layoutParams = FrameLayout.LayoutParams(stageWidth, stagePixels).apply {
                            topMargin = stageTop
                        }
                    }
                }
            }
        } catch (_: Exception) {
            view.liveCompanionFailed = true
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions(
            arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA), 1)
        client.start()

        view.onTap = { id -> ui.post { touch(id) } }
        view.onLongPress = { id -> ui.post { longPress(id) } }
        view.onSlider = { id, value ->
            ui.post {
                when (id) {
                    "volume" -> setDeviceVolume(value)
                    "brightness" -> setDeviceBrightness(value)
                }
            }
        }
        view.onBootDone = { ui.post { finishBoot() } }

        if (!showVoicePreview(intent)) startBootExperience()
        ensureWallpaper()
        refreshFiles()
        tick()
        ui.post(saverVideoTick)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) showVoicePreview(intent)
    }

    private fun showVoicePreview(request: android.content.Intent?): Boolean {
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val phase = request?.getStringExtra("voice_preview") ?: return false
        if (!debug || liveCallActive || liveStartPending || liveStopRequested ||
            phase !in listOf("Connecting","Listening","Speaking","Working","Saved","Error")) return false
        view.livePreview = true
        view.voiceMode = "live"
        view.liveStatus = phase
        view.voiceCapturing = false
        view.voiceLine = "Animation preview. No microphone is recording and no API request is running."
        st.screen = Screen.VOICE
        st.pttStart = System.currentTimeMillis()
        poke()
        view.invalidate()
        return true
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        // Some vendor builds briefly reveal their navigation-bar colour even
        // while immersive mode is being restored. Keep that surface transparent
        // so the Dockstation wallpaper remains edge-to-edge.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        companionResumed = true
        ui.removeCallbacks(companionTick)
        ui.post(companionTick)
        // Some vendor panels restore the status/navigation bars after an
        // Android settings activity or privacy indicator closes. Re-assert
        // kiosk fullscreen after the window has fully regained focus.
        ui.postDelayed({ enterImmersiveMode() }, 120L)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // Vendor MediaPlayer builds occasionally keep the last decoded frame
        // without issuing completion/error. Never leave the device trapped on
        // that frame even if both media callbacks fail.
        if (st.screen == Screen.BOOT && bootExperienceStartedAt > 0L &&
            now - bootExperienceStartedAt > 26_000L) finishBoot()
        SuhairBehavior.advance(st.suhairPet, now)
        if (suhairPetActive()) SuhairBehavior.maybeRoam(st.suhairPet, now, st.petFacePresent)
        updatePetTracking()
        if (now > petReactionUntil &&
            st.petExpression in listOf("happy", "curious", "waving", "jumping"))
            st.petExpression = "idle"
        // screensaver on idle
        if (!view.quickControlsOpen && st.screen in listOf(Screen.HOME, Screen.DASHBOARD, Screen.AGENT) &&
            now - lastInteract > saverAfterMs
        ) enterSaver()
        view.invalidate()
        ui.postDelayed({ tick() }, 250)
    }

    override fun onStop() {
        companionResumed = false
        ui.removeCallbacks(companionTick)
        liveCompanion?.updateStage(false, "idle", 0f, true, 0L, false)
        // Some phone firmware consumes the red key as screen-off before Activity
        // sees it. Never leave a paid call capturing audio invisibly in that case.
        if (liveCallActive || liveStartPending) requestLiveStop()
        super.onStop()
    }

    /** Pending startup is already a stoppable call, even before the bridge replies. */
    private fun requestLiveStop(force: Boolean = false) {
        if (!force && !liveCallActive && !liveStartPending && !liveStopRequested) return
        if (!liveStopRequested) client.send("live_stop")
        liveStopRequested = true
        liveStartPending = false
        liveSpeaker.stop()
        view.voiceCapturing = false
        view.liveStatus = "Ending"
        view.voiceLine = "Closing the microphone and audio."
        view.invalidate()
    }

    override fun onDestroy() {
        companionResumed = false
        ui.removeCallbacks(companionTick)
        liveCompanion?.disposeStage()
        liveCompanion = null
        liveSpeaker.stop()
        ui.removeCallbacks(saverVideoTick)
        bootGeneration += 1
        bootPlayer?.release()
        bootPlayer = null
        saverPlayer?.release()
        saverPlayer = null
        Chiptune.stopBootMusic()
        PortableUiSounds.release()
        client.stop()
        if (::faceTracker.isInitialized) faceTracker.release()
        if (::view.isInitialized) view.cameraPreviewFrame?.takeIf { !it.isRecycled }?.recycle()
        petTts?.stop(); petTts?.shutdown(); petTts = null
        super.onDestroy()
    }

    private fun ensureSaverPlayer() {
        if (saverPlayer != null || !videoTexture.isAvailable) return
        try {
            val fd = resources.openRawResourceFd(R.raw.saver_highway)
            val surface = Surface(videoTexture.surfaceTexture)
            saverPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
            }
            surface.release()
            fd.close()
        } catch (_: Exception) {
            toast("video saver unavailable")
        }
    }

    private fun goHome() { st.screen = Screen.HOME; st.nowPinned = false }

    /** Native Dockstation startup; never waits on a video decoder or the bridge. */
    private fun startBootExperience() {
        val generation = ++bootGeneration
        bootExperienceStartedAt = System.currentTimeMillis()
        featureBootStarted = false
        Chiptune.stopBootMusic()
        bootPlayer?.release(); bootPlayer = null
        saverPlayer?.release(); saverPlayer = null
        st.screen = Screen.BOOT
        st.homeIntroStart = 0L
        view.prepareVideoBoot()
        view.alpha = 1f
        videoTexture.alpha = 0.01f
        beginFeatureBoot(generation, null)
        if (st.soundAlerts) Chiptune.dockstationBoot(this)

        // Media failure must never trap the launcher. The bundled final frame
        // provides a visually correct continuation if decoding stalls.
        ui.postDelayed({
            if (generation == bootGeneration && st.screen == Screen.BOOT) finishBoot()
        }, 12_500L)
    }

    private fun playIntroVideo(generation: Int) {
        if (generation != bootGeneration || featureBootStarted || bootPlayer != null ||
            !videoTexture.isAvailable) return
        try {
            val fd = resources.openRawResourceFd(R.raw.manzanilla_intro)
            val surface = Surface(videoTexture.surfaceTexture)
            bootPlayer = MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setSurface(surface)
                isLooping = false
                // The cinematic file is deliberately silent in-app. Boot
                // music and feature cues are controlled independently.
                setVolume(0f, 0f)
                setOnPreparedListener { player ->
                    if (generation == bootGeneration && st.screen == Screen.BOOT) player.start()
                }
                setOnCompletionListener {
                    if (generation == bootGeneration && st.screen == Screen.BOOT) {
                        val captured = try {
                            videoTexture.getBitmap(
                                view.width.coerceAtLeast(854), view.height.coerceAtLeast(480))
                        } catch (_: Exception) { null }
                        beginFeatureBoot(generation, captured)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    if (generation == bootGeneration && st.screen == Screen.BOOT)
                        beginFeatureBoot(generation, null)
                    true
                }
                prepareAsync()
            }
            surface.release()
            fd.close()
        } catch (_: Exception) {
            beginFeatureBoot(generation, null)
        }
    }

    private fun beginFeatureBoot(generation: Int, finalVideoFrame: Bitmap?) {
        if (generation != bootGeneration || featureBootStarted || st.screen != Screen.BOOT) return
        featureBootStarted = true
        view.startFeatureBoot(finalVideoFrame)
        // The view now paints the same final frame above the TextureView. Only
        // after that draw is queued do we hide/release the video surface.
        view.alpha = 1f
        view.invalidate()
        view.postOnAnimation {
            if (generation == bootGeneration) {
                videoTexture.alpha = 0.01f
                bootPlayer?.release()
                bootPlayer = null
            }
        }
        // The original Dockstation score supplies the synchronized intro cues.
    }

    /** the coded feature film's last frame has already become the live home screen */
    private fun finishBoot() {
        if (st.screen != Screen.BOOT) return
        Chiptune.stopBootMusic()
        bootGeneration += 1
        featureBootStarted = false
        bootPlayer?.release(); bootPlayer = null
        videoTexture.alpha = 0.01f
        view.alpha = 1f
        view.cancelBootVisuals()
        st.homeIntroStart = 0L
        st.screen = Screen.HOME
        st.nowPinned = false
        if (Themes.current.name == "WEB SLINGER" && st.soundAlerts) Chiptune.webStart()
    }
    private fun replayBoot() {
        startBootExperience()
    }
    private fun poke() { lastInteract = System.currentTimeMillis() }
    private fun isOrbitAudioTheme() =
        Themes.current.name == "ORBIT" || Themes.current.name == "DOCKSTATION"
    private fun blip() {
        if (!st.soundAlerts) return
        if (isOrbitAudioTheme()) {
            if (!PortableUiSounds.play(PortableUiSounds.Cue.MOVE)) Chiptune.orbitMove()
        } else Chiptune.blip()
    }
    private fun orbitCategorySound() {
        if (!st.soundAlerts) return
        if (!PortableUiSounds.play(PortableUiSounds.Cue.CATEGORY)) Chiptune.orbitMove()
        // Deliberately layered: the authentic movement cue supplies the PSP
        // character while the synthesized strike gives the physical keypad a
        // clear, hard metallic response on this device's small speaker.
        Chiptune.orbitMetal()
    }
    private fun confirmSound() {
        if (!st.soundAlerts) return
        if (isOrbitAudioTheme()) {
            if (!PortableUiSounds.play(PortableUiSounds.Cue.CONFIRM)) Chiptune.orbitConfirm()
        } else Chiptune.select()
    }
    private fun toast(msg: String) { st.toast = msg; st.toastUntil = System.currentTimeMillis() + 2200 }

    // ---- Bridge events ------------------------------------------------------

    override fun onConnected(name: String) = ui.post {
        st.connected = true; st.bridgeName = name; st.pairingCode = ""
        client.send("live_ready", "audio_version" to 1)
        toast("bridge linked: $name")
        if (st.soundAlerts) {
            if (isOrbitAudioTheme()) {
                if (!PortableUiSounds.play(PortableUiSounds.Cue.READY)) Chiptune.orbitReady()
            } else Chiptune.select()
        }
    }.let {}

    override fun onDisconnected() = ui.post {
        st.connected = false
        liveAvailable = false
        liveCallActive = false
        liveStartPending = false
        liveStopRequested = false
        liveSpeaker.stop()
        if (view.voiceMode == "live") {
            view.liveStatus = "Disconnected"
            view.voiceCapturing = false
            view.voiceLine = "PC connection lost. Reconnect before starting another call."
        }
    }.let {}

    override fun onJson(o: JSONObject) = ui.post {
        when (o.optString("t")) {
            "live_audio_stop" -> {
                liveSpeaker.stop()
                liveCallActive = false
                liveStartPending = false
                liveStopRequested = false
                view.voiceCapturing = false
            }
            "live_state" -> {
                liveAvailable = o.optBoolean("enabled", false)
                if (view.livePreview) return@post
                val phase = o.optString("state", "idle")
                val active = phase in listOf("connecting", "listening", "speaking", "working", "saved", "ending")
                if (active && phase != "ending" && (!companionResumed || liveStopRequested)) {
                    // A late startup/status reply cannot reopen a cancelled or hidden call.
                    if (!liveStopRequested) requestLiveStop(force = true) else liveSpeaker.stop()
                    return@post
                }
                if (!active) {
                    liveStartPending = false
                    liveStopRequested = false
                } else if (phase == "ending") {
                    liveStartPending = false
                    liveStopRequested = true
                    liveSpeaker.stop()
                }
                if (active && phase != "ending" && !liveCallActive) {
                    screenBeforeVoice = st.screen
                    // Green already began the entrance locally. Connection updates
                    // must not make the character jump back and enter a second time.
                    if (st.screen != Screen.VOICE || view.voiceMode != "live" || view.liveStatus != "Connecting") {
                        st.pttStart = System.currentTimeMillis()
                    }
                    petTts?.stop()
                    liveSpeaker.start()
                }
                if (active) liveStartPending = false
                liveCallActive = active
                if (active || view.voiceMode == "live" || phase == "error") {
                    view.voiceMode = "live"
                    view.voiceReady = false
                    view.voiceCapturing = active && phase != "ending"
                    view.liveStatus = phase.replaceFirstChar { it.uppercase() }
                    val message = o.optString("message")
                    if (o.optBoolean("transcript", false)) {
                        view.voiceLine = (if (view.liveTranscriptPhase == phase) view.voiceLine + message else message).takeLast(420)
                        view.liveTranscriptPhase = phase
                    } else if (message.isNotBlank()) {
                        view.voiceLine = message
                        view.liveTranscriptPhase = ""
                    }
                    st.screen = Screen.VOICE
                    if (!active) liveSpeaker.stop()
                    view.invalidate()
                }
            }
            "pair" -> st.pairingCode = o.optString("code")
            "slots" -> st.applySlots(o.getJSONArray("slots"))
            "tail" -> st.applyTail(o)
            "voice" -> view.voiceLine = o.optString("text")
            "voice_final" -> {
                val transcript = o.optString("text").take(2000).trim()
                view.voiceLine = transcript
                composerAwaitingFinal = false
                view.voiceReady = transcript.isNotEmpty()
                view.voiceCapturing = false
                st.screen = Screen.VOICE
                if (view.voiceReady) toast("5 SEND  ·  1 AGAIN  ·  0 CANCEL")
                else toast("NO TRANSCRIPT RECEIVED")
            }
            "usage" -> {
                st.usageAvailable = o.optBoolean("available", false)
                st.shortLimitRemaining = if (o.isNull("short_remaining")) -1
                    else o.optInt("short_remaining", -1).coerceIn(0, 100)
                st.shortLimitWindowMins = o.optInt("short_window_mins", 0)
                st.weeklyLimitRemaining = if (o.isNull("weekly_remaining")) -1
                    else o.optInt("weekly_remaining", -1).coerceIn(0, 100)
                st.monthlyLimitRemaining = if (o.isNull("monthly_remaining")) -1
                    else o.optInt("monthly_remaining", -1).coerceIn(0, 100)
            }
            "media" -> {
                st.mediaActive = o.optBoolean("active", false)
                st.mediaSource = o.optString("source")
                st.mediaTitle = o.optString("title")
                st.mediaArtist = o.optString("artist")
                st.mediaAlbum = o.optString("album")
                st.mediaStatus = o.optString("status", "closed")
                val encodedArt = o.optString("art")
                if (encodedArt.isNotBlank()) {
                    try {
                        val bytes = Base64.decode(encodedArt, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { next ->
                            st.mediaArtwork?.takeIf { it !== next && !it.isRecycled }?.recycle()
                            st.mediaArtwork = next
                        }
                    } catch (_: Exception) {}
                } else {
                    st.mediaArtwork?.takeIf { !it.isRecycled }?.recycle()
                    st.mediaArtwork = null
                }
                // On first connection, a live player is the most immediately
                // useful card.  Once the user swipes, preserve their choice.
                if (st.mediaActive && st.statusCardLastManualAt == 0L) {
                    st.statusCardDirection = 1
                    st.statusCardIndex = 0
                }
            }
            "action_result" -> {
                if (pendingPetCommand.isNotEmpty()) {
                    val result = o.optString("result", "command received")
                    sayPet("PC CONFIRMED: $pendingPetCommand", audible = false)
                    toast(result.take(54))
                    pendingPetCommand = ""
                } else toast(o.optString("result", "command received").take(54))
            }
            "window_list" -> {
                st.pcAppWindows.clear()
                o.optJSONArray("apps")?.let { apps ->
                    for (i in 0 until minOf(128, apps.length())) apps.optJSONObject(i)?.let { app ->
                        st.pcAppWindows.add(PcAppWindow(
                            app.optString("id"), app.optString("name", "APP"),
                            app.optString("title"), app.optString("exe"),
                            app.optBoolean("active", false)))
                    }
                }
                st.screen = Screen.APP_SWITCHER
                toast(if (st.pcAppWindows.isEmpty()) "NO OPEN PC APPS FOUND"
                    else "${st.pcAppWindows.size} OPEN PC APPS")
            }
            "pc_notification" -> {
                val rawApp = o.optString("app", "WINDOWS").take(24)
                val aiSource = rawApp.uppercase() in listOf("CHATGPT", "CODEX", "CLAUDE", "COWORK")
                val app = if (aiSource) "CHATGPT" else rawApp
                val classified = o.optString("title").take(64)
                val preview = o.optString("body")
                    .replace(Regex("\\s+"), " ").trim().take(6000)
                val message = if (aiSource && preview.isNotBlank())
                    "CHATGPT: ${preview.take(52)}"
                    else if (aiSource && classified.startsWith("HEY!")) classified
                    else if (aiSource) "CHATGPT HAS AN UPDATE"
                    else "HEY! ${app.uppercase()} HAS A NOTIFICATION"
                st.pcNotificationBody = preview
                if (aiSource) st.composerTarget = "chatgpt"
                showPetAlert(app, message, important = aiSource)
                // Keep the actual title and body for the reader. The pet's
                // short announcement is not the notification's full content.
                st.pcNotificationTitle = classified.ifBlank { "$app update" }
                st.statusCardDirection = 1
                st.statusCardIndex = 1
            }
            "file" -> pendingFileName = o.optString("name", "file.bin")
            "keycfg" -> o.optJSONObject("map")?.let { keymap.applyActions(it); toast("keys updated") }
            "context" -> {
                if (!st.nowPinned) {
                    st.nowName = o.optString("name").uppercase().take(10)
                    st.nowKind = o.optString("kind")
                }
                st.nowTask = o.optString("task")
                st.nowProject = o.optString("project")
                st.nowState = o.optString("state", "idle")
                st.nowDetail = o.optString("detail")
                st.nowModel = o.optString("model")
                o.optJSONObject("apps")?.let { apps ->
                    for (kind in listOf("chatgpt", "codex", "claude", "cowork"))
                        st.aiRunning[kind] = apps.optJSONObject(kind)?.optBoolean("running") ?: false
                }
                o.optJSONArray("projects")?.let { projects ->
                    st.aiProjects.clear()
                    for (i in 0 until projects.length()) projects.optJSONObject(i)?.let { p ->
                        st.aiProjects.add(AiProject(p.optString("task"), p.optString("project"),
                            p.optString("state", "idle"), p.optString("detail"), p.optString("model")))
                    }
                }
                updateSuhairFromContext()
            }
            "pet_intent" -> {
                // The bridge/AI may request only this finite semantic vocabulary. Message text is
                // rendered as untrusted content and cannot address atlas cells or device actions.
                val intent = SuhairIntent.fromWire(o.optString("intent"))
                if (intent != null) {
                    val region = when (o.optString("region").lowercase()) {
                        "left" -> SuhairRegion.LEFT
                        "right" -> SuhairRegion.RIGHT
                        "center" -> SuhairRegion.CENTER
                        else -> null
                    }
                    val scene = SuhairScene.fromWire(o.optString("scene"))
                        ?: SuhairBehavior.defaultSceneFor(intent, st.suhairPet.scene)
                    SuhairBehavior.requestInScene(st.suhairPet, intent, scene,
                        message = o.optString("message"), source = o.optString("source"),
                        progress = if (o.has("progress")) o.optDouble("progress").toFloat() else null,
                        region = region)
                }
            }
            "sd_pages" -> o.optJSONArray("pages")?.takeIf { it.length() > 0 }?.let {
                st.sdPagesJson = it.toString()
                st.sdPage = st.sdPage.coerceIn(0, it.length() - 1)
            }
            "clip" -> { toast("PC clip: ${o.optString("text").take(24)}") }
            "event" -> {
                val kind = o.optString("kind")
                val slot = o.optInt("slot")
                if (st.soundAlerts) when (kind) {
                    "done" -> Chiptune.done(); "needs_input" -> Chiptune.needsYou(); "error" -> Chiptune.error()
                }
                val cal = java.util.Calendar.getInstance()
                val who = if (slot in 1..9) agentNameOf(st.slots[slot - 1].kind) else "SYS"
                st.addEvent("%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE)), who,
                    when (kind) { "done" -> "finished a task"; "needs_input" -> "needs your input"
                        "error" -> "hit an error"; else -> kind })
                toast("$who: $kind")
                val message = when (kind) {
                    "done" -> "HEY! ${who.uppercase()} FINISHED YOUR TASK"
                    "needs_input" -> "HEY! ${who.uppercase()} NEEDS YOUR ATTENTION"
                    "error" -> "HEY! ${who.uppercase()} HIT AN ERROR"
                    else -> "HEY! ${who.uppercase()} HAS AN UPDATE"
                }
                showPetAlert(who, message, important = true, playSound = false)
            }
        }
    }.let {}

    override fun onBinary(tag: Byte, data: ByteArray) {
        if (tag == 0x05.toByte()) { liveSpeaker.append(data); return }
        if (tag != 0x03.toByte()) return
        ui.post { saveIncomingFile(pendingFileName, data) }
    }

    private fun saveIncomingFile(name: String, data: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                toast("got file: $name")
                if (st.soundAlerts) Chiptune.select()
                refreshFiles()
            }
        } catch (e: Exception) { toast("file save failed") }
    }

    private fun refreshFiles() {
        Thread {
            try {
                val out = ArrayList<Pair<String, Long>>()
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                    null, null, MediaStore.Downloads.DATE_ADDED + " DESC"
                )?.use { cur ->
                    while (cur.moveToNext() && out.size < 12) out.add(cur.getString(0) to cur.getLong(1))
                }
                ui.post { st.files.clear(); st.files.addAll(out) }
            } catch (_: Exception) {}
        }.start()
    }

    // ---- touch --------------------------------------------------------------

    private fun touch(id: String) {
        poke()
        when {
            id == "arc_open" -> {
                if (st.connected) { client.send("manzanilla_open"); toast("Opening Manzanilla on PC…"); blip() }
                else toast("Connect the PC bridge first")
            }
            id == "quick_open" -> {
                if (st.screen == Screen.SAVER) goHome()
                syncDeviceVolume(); syncDeviceBrightness()
                view.quickControlsOpen = true; view.invalidate()
            }
            id == "quick_close" -> { view.quickControlsOpen=false; view.invalidate() }
            id == "quick_pet" -> {
                view.quickControlsOpen=false; st.selectedPet="suhair"; st.screen=Screen.PET; blip()
            }
            id == "quick_settings" -> { view.quickControlsOpen=false; st.screen=Screen.SETTINGS; blip() }
            id == "quick_keys" -> { view.quickControlsOpen=false; view.learnIndex=0; st.screen=Screen.KEYLEARN; blip() }
            id == "brightness_down" -> adjustDeviceBrightness(-1)
            id == "brightness_up" -> adjustDeviceBrightness(1)
            st.screen == Screen.SAVER && id == "notification_open" -> {
                st.statusCardIndex = 1; st.screen = Screen.DYNAMIC_STATUS; blip()
            }
            st.screen == Screen.SAVER || id == "wake" -> { st.screen = Screen.HOME; blip() }
            st.screen == Screen.BOOT -> finishBoot()
            id.startsWith("tile:") -> openTile(id.removePrefix("tile:"))
            id.startsWith("xmb_category:") -> {
                st.xmbCategory = id.removePrefix("xmb_category:").toIntOrNull()
                    ?.coerceIn(XmbMenu.categories.indices) ?: st.xmbCategory
                st.xmbItem = 0
                orbitCategorySound()
            }
            id.startsWith("xmb_item:") -> {
                val items = XmbMenu.category(st.xmbCategory).items
                st.xmbItem = id.removePrefix("xmb_item:").toIntOrNull()
                    ?.coerceIn(items.indices) ?: st.xmbItem
                openXmbItem()
            }
            id.startsWith("slot:") -> {
                val n = id.removePrefix("slot:").toInt()
                if (st.slots[n - 1].state == "empty") { st.selected = n; st.screen = Screen.SPAWN_PICK }
                else { st.selected = n; st.screen = Screen.AGENT; client.send("select", "slot" to n) }
                blip()
            }
            id.startsWith("pick:") -> { spawn(id.removePrefix("pick:")); st.screen = Screen.DASHBOARD }
            id.startsWith("theme:") -> pickTheme(id.removePrefix("theme:").toInt())
            id == "theme_prev" -> {
                st.themePreview = (st.themePreview - 1 + Themes.all.size) % Themes.all.size
                blip()
            }
            id == "theme_next" -> {
                st.themePreview = (st.themePreview + 1) % Themes.all.size
                blip()
            }
            id == "theme_apply" -> pickTheme(st.themePreview)
            id.startsWith("set:") -> settingsChoice(id.removePrefix("set:").toInt())
            id.startsWith("settings_cat:") -> {
                st.settingsCategory = id.removePrefix("settings_cat:").toInt().coerceIn(0, 3)
                st.settingsItem = 0
                if (st.soundAlerts && isOrbitAudioTheme()) {
                    orbitCategorySound()
                } else blip()
            }
            id == "settings_open" -> openOrbitSetting()
            id.startsWith("sd:") -> sdPress(id.removePrefix("sd:").toInt())
            id.startsWith("appctl:") -> appControllerPress(id.removePrefix("appctl:").toInt())
            id.startsWith("aiws:") -> aiWorkspacePress(id.removePrefix("aiws:").toInt())
            id == "aiws_toggle" -> {
                val pages = maxOf(1, ((AppProfiles.get(st.aiWorkspaceKind)?.controls?.size ?: 1) + 8) / 9)
                st.aiWorkspacePage = (st.aiWorkspacePage + 1) % (pages + 1); blip()
            }
            id == "aiws_focus" -> focusPcApp(st.aiWorkspaceKind)
            id == "appctl_prev" -> { st.appControllerPage = maxOf(0, st.appControllerPage - 1); blip() }
            id == "appctl_next" -> {
                val count = AppProfiles.get(st.appControllerId)?.controls?.size ?: 0
                val pageSize = if (Themes.current.name == "LIQUID GLASS") 6 else 9
                st.appControllerPage = minOf(maxOf(0, (count - 1) / pageSize), st.appControllerPage + 1); blip()
            }
            id == "voice_preview_close" -> navigateBack()
            id == "voice_stop" -> {
                if (view.voiceMode == "live") {
                    requestLiveStop()
                    return
                }
                globalDictationOn = false
                if (pttMode != null) stopPtt()
            }
            id == "voice_send" -> submitAiComposer()
            id == "voice_again" -> startAiComposer("chatgpt")
            id == "voice_cancel" -> cancelAiComposer()
            id == "notification_open" -> {
                st.statusCardIndex = 1; st.screen = Screen.DYNAMIC_STATUS; blip()
            }
            id == "notification_reply" -> startAiComposer("chatgpt")
            id == "volume_down" -> adjustDeviceVolume(-1)
            id == "volume_up" -> adjustDeviceVolume(1)
            id == "volume_mute" -> toggleDeviceMute()
            id.startsWith("saver_pick:") ->
                selectSaver(id.removePrefix("saver_pick:"))
            id == "add" -> {
                st.selected = st.slots.firstOrNull { it.state == "empty" }?.slot ?: 1
                st.screen = Screen.SPAWN_PICK; blip()
            }
            id == "open_store" -> launchStore()
            id == "replay_boot" -> replayBoot()
            id == "home_page_toggle" -> { st.homePage = 1 - st.homePage; blip() }
            id == "go_home" -> { goHome(); blip() }
            id == "go_back" -> navigateBack()
            id == "game_prev" -> {
                st.gameSelected = (st.gameSelected - 1 + 2) % 2; blip()
            }
            id == "game_next" -> {
                st.gameSelected = (st.gameSelected + 1) % 2; blip()
            }
            id == "game_launch" -> launchLocalGame(st.gameSelected)
            id == "appctl_ok" -> navigateBack()
            id == "stream_apps" -> openPcAppSwitcher()
            id == "stream_theme" -> togglePcTheme()
            id == "media_previous" -> client.send("media_action", "action" to "prev")
            id == "media_toggle" -> client.send("media_action", "action" to "play")
            id == "media_next" -> client.send("media_action", "action" to "next")
            id.startsWith("status_tab:") -> {
                id.substringAfter(':').toIntOrNull()?.takeIf { it in 0..2 }?.let {
                    st.statusCardDirection = if (it >= st.statusCardIndex) 1 else -1
                    st.statusCardIndex = it
                    st.statusCardLastManualAt = System.currentTimeMillis()
                    blip()
                }
            }
            id == "status_expand" -> { st.screen = Screen.DYNAMIC_STATUS; blip() }
            id == "status_close" -> { goHome(); blip() }
            id == "reader_next" -> { view.notificationPage++; blip() }
            id == "reader_prev" -> { view.notificationPage = maxOf(0,view.notificationPage-1); blip() }
            id == "chat_preview_open_pc" -> {
                val app = if (st.pcNotificationApp.equals("codex", true)) "codex" else "chatgpt"
                client.send("app_control", "app" to app, "action" to "focus")
                toast("OPENING ${app.uppercase()} ON PC")
                confirmSound()
            }
            id == "status_swipe_next" -> {
                st.statusCardDirection = 1
                st.statusCardIndex = (st.statusCardIndex + 1) % 3
                st.statusCardLastManualAt = System.currentTimeMillis()
                blip()
            }
            id == "status_swipe_prev" -> {
                st.statusCardDirection = -1
                st.statusCardIndex = (st.statusCardIndex + 2) % 3
                st.statusCardLastManualAt = System.currentTimeMillis()
                blip()
            }
            id == "appswitch_close" -> { st.screen = Screen.STREAM; blip() }
            id == "appswitch_prev" -> moveAppSwitcherPage(-1)
            id == "appswitch_next" -> moveAppSwitcherPage(1)
            id.startsWith("appswitch:") ->
                focusPcWindow(id.removePrefix("appswitch:").toIntOrNull() ?: 0)
            id == "pet_back" -> goHome()
            id == "pet_calibrate" -> {
                st.screen = Screen.PET_GAZE_CALIBRATE
                st.petInteractionStage = "idle"
                toast("FIRST SAVE LOOKING AT MANZANILLA, THEN LOOKING AT LAPTOP")
                blip()
            }
            id == "pet_cal_done" -> { st.screen = Screen.PET; blip() }
            id == "pet_gaze_done" -> { st.screen = Screen.PET; blip() }
            id == "pet_gaze_fingers" -> { st.screen = Screen.PET_CALIBRATE; blip() }
            id.startsWith("pet_gaze:") ->
                calibratePetGaze(id.removePrefix("pet_gaze:"))
            id.startsWith("pet_cal:") ->
                calibratePetGesture(id.removePrefix("pet_cal:").toIntOrNull() ?: 0)
            id == "pet_tracking" -> {
                st.petTrackingEnabled = !st.petTrackingEnabled
                if (!st.petTrackingEnabled) {
                    st.petFacePresent = false
                    faceTracker.stop(); faceTrackerRunning = false
                }
                blip()
            }
            id == "pet_expression" -> if (st.selectedPet == "suhair") cycleSuhairMood() else cyclePetMood()
            id == "pet_pick:manni" -> {
                st.selectedPet = "manni"
                getSharedPreferences("deck", MODE_PRIVATE).edit().putString("selected_pet", "manni").apply()
                st.screen = Screen.PET
                toast("Manni selected")
                blip()
            }
            id == "pet_pick:webby" -> {
                st.selectedPet = "webby"
                st.saverMode = "spider"
                getSharedPreferences("deck", MODE_PRIVATE).edit()
                    .putString("selected_pet", "webby")
                    .putString("saver_mode", "spider").apply()
                st.screen = Screen.PET
                if (st.soundAlerts) Chiptune.webSaverTheme()
                sayPet("Ready to patrol")
                toast("Webby selected")
                blip()
            }
            id == "pet_pick:suhair" -> {
                st.selectedPet = "suhair"
                st.saverMode = "pet"
                SuhairBehavior.request(st.suhairPet, SuhairIntent.GREET,
                    message = "Ready to help with your work", source = "MANZANILLA")
                getSharedPreferences("deck", MODE_PRIVATE).edit()
                    .putString("selected_pet", "suhair")
                    .putString("saver_mode", "pet").apply()
                st.screen = Screen.PET
                toast("Suhair selected")
                blip()
            }
            id.startsWith("game:") -> {
                val selected = id.removePrefix("game:").toIntOrNull() ?: 0
                if (st.gameSelected == selected) launchLocalGame(selected)
                else { st.gameSelected = selected.coerceIn(0, 1); blip() }
            }
        }
    }

    private fun longPress(id: String) {
        poke()
        if (!id.startsWith("tile:") || st.screen != Screen.HOME) return
        val tile = id.removePrefix("tile:")
        when (tile) {
            "chatgpt", "codex", "clor" -> {
                st.aiWorkspaceKind = if (tile == "clor") "claude" else tile
                st.aiWorkspacePage = 0
                st.screen = Screen.AI_WORKSPACE
                toast("${st.aiWorkspaceKind} controls")
            }
            "more" -> {
                st.homePage = 1
                blip()
            }
            else -> {
                val profileId = when (tile) {
                    "stream" -> "stream"
                    "wispr" -> "wispr"
                    else -> tile
                }
                val profile = AppProfiles.get(profileId)
                if (profile != null) {
                    st.appControllerId = profile.id
                    st.appControllerTitle = profile.title
                    st.appControllerPage = 0
                    st.screen = Screen.APP_CONTROLLER
                    toast("${profile.title.lowercase()} controls")
                } else {
                    openTile(tile)
                }
            }
        }
        if (st.soundAlerts) Chiptune.select()
    }

    private fun agentNameOf(kind: String) = when (kind) {
        "claude" -> "CLAUDE"; "codex" -> "CODEX"; "cowork" -> "COWORK"; else -> "SYS" }

    private fun openTile(tile: String) {
        confirmSound()
        when (tile) {
            "clor" -> connectKind("claude")
            "codex" -> { st.aiWorkspaceKind = "codex"; st.aiWorkspacePage = 0; st.screen = Screen.AI_WORKSPACE }
            "chatgpt" -> {
                st.aiWorkspaceKind = "chatgpt"
                st.aiWorkspacePage = 0
                st.screen = Screen.AI_WORKSPACE
                client.send("app_control", "app" to "chatgpt", "action" to "focus")
                toast("opening ChatGPT app on PC")
            }
            "chrome" -> openPcAppWithCoach("chrome")
            "comet" -> openPcAppWithCoach("comet")
            "spotify" -> openPcAppWithCoach("spotify")
            "wispr" -> toggleGlobalDictation()
            "more" -> { st.homePage = 1; blip() }
            "agents" -> { st.page = 0; st.screen = Screen.DASHBOARD }
            "files" -> { refreshFiles(); st.page = 1; st.screen = Screen.DASHBOARD }
            "dial" -> { st.dialBuf = ""; st.screen = Screen.DIAL }
            "store" -> st.screen = Screen.STORE
            "games" -> { st.gameSelected = 0; st.screen = Screen.GAMES }
            "themes" -> st.screen = Screen.THEMES
            "stream" -> st.screen = Screen.STREAM
            "tasks" -> st.screen = Screen.TASKS
            "settings" -> st.screen = Screen.SETTINGS
            "camera" -> st.screen = Screen.VIEWFINDER
            "saver" -> enterSaver()
            "pet" -> {
                st.petExpression = "idle"
                st.screen = Screen.PET
                if (st.selectedPet == "webby" && st.soundAlerts) Chiptune.webSaverTheme()
                if (st.selectedPet == "suhair" && st.suhairPet.intent == SuhairIntent.IDLE)
                    SuhairBehavior.request(st.suhairPet, SuhairIntent.GREET,
                        message = "What are we working on?", source = "SUHAIR")
            }
            "kbd" -> { st.keyboardMode = true; toast("keys mode on") }
            "clip" -> client.send("clip_get")
        }
    }

    /** CLAUDE/CODEX tile: jump to a live agent of that kind, or spawn one */
    private fun connectKind(kind: String) {
        val live = st.slots.firstOrNull { it.kind == kind && it.state != "empty" }
        if (live != null) {
            st.selected = live.slot; st.screen = Screen.AGENT
            client.send("select", "slot" to live.slot)
        } else {
            val empty = st.slots.firstOrNull { it.state == "empty" }?.slot ?: 1
            st.selected = empty
            spawn(kind)
            st.screen = Screen.AGENT
        }
    }

    private fun pickTheme(i: Int) {
        val leavingWeb = Themes.current.name == "WEB SLINGER" &&
            Themes.all.getOrNull(i)?.name != "WEB SLINGER"
        Themes.idx = i
        st.themePreview = i
        val web = Themes.current.name == "WEB SLINGER"
        if (web) {
            st.saverMode = "spider"
            st.selectedPet = "webby"
        }
        getSharedPreferences("deck", MODE_PRIVATE).edit().putInt("theme", i)
            .putString("saver_mode", st.saverMode).apply()
        if (web) getSharedPreferences("deck", MODE_PRIVATE).edit()
            .putString("selected_pet", "webby").apply()
        toast(Themes.current.name)
        if (st.soundAlerts) when {
            web -> Chiptune.webStart()
            leavingWeb -> Chiptune.webOff()
            isOrbitAudioTheme() ->
                if (!PortableUiSounds.play(PortableUiSounds.Cue.READY)) Chiptune.orbitReady()
            else -> Chiptune.select()
        }
    }

    private fun settingsChoice(n: Int) {
        when (n) {
            1 -> { view.learnIndex = 0; st.screen = Screen.KEYLEARN; blip() }
            2 -> { client.token = null; toast("re-pairing…") }
            3 -> {
                st.soundAlerts = !st.soundAlerts
                if (st.soundAlerts) confirmSound()
            }
            4 -> { st.themePreview = Themes.idx; st.screen = Screen.THEMES; blip() }
            5 -> { st.screen = Screen.SAVER_PICK; blip() }
            6 -> try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (e: Exception) { toast("can't open wifi panel") }
            7 -> { syncDeviceVolume(); syncDeviceBrightness(); st.screen = Screen.VOLUME; blip() }
            8 -> { st.screen = Screen.PET_PICK; blip() }
            9 -> {
                st.orbitSmoothMotion = !st.orbitSmoothMotion
                getSharedPreferences("deck", MODE_PRIVATE).edit()
                    .putBoolean("orbit_smooth_motion", st.orbitSmoothMotion).apply()
                toast(if (st.orbitSmoothMotion) "smooth motion · 30 fps" else "eco motion · 20 fps")
                confirmSound()
            }
            0 -> { goHome(); blip() }
        }
    }

    private val orbitSettingGroups = arrayOf(
        intArrayOf(1, 2),
        intArrayOf(3, 4, 5),
        intArrayOf(6, 7, 9),
        intArrayOf(8)
    )

    private fun moveOrbitSettingsCategory(direction: Int) {
        st.settingsCategory = (st.settingsCategory + direction + orbitSettingGroups.size) %
            orbitSettingGroups.size
        st.settingsItem = st.settingsItem.coerceIn(
            0, orbitSettingGroups[st.settingsCategory].lastIndex
        )
        orbitCategorySound()
    }

    private fun moveOrbitSettingsItem(direction: Int) {
        val group = orbitSettingGroups[st.settingsCategory]
        st.settingsItem = (st.settingsItem + direction + group.size) % group.size
        blip()
    }

    private fun openOrbitSetting() {
        val group = orbitSettingGroups[st.settingsCategory]
        settingsChoice(group[st.settingsItem.coerceIn(0, group.lastIndex)])
        confirmSound()
    }

    private fun moveXmbCategory(direction: Int) {
        st.xmbCategory = (st.xmbCategory + direction + XmbMenu.categories.size) %
            XmbMenu.categories.size
        st.xmbItem = 0
        orbitCategorySound()
    }

    private fun moveXmbItem(direction: Int) {
        val items = XmbMenu.category(st.xmbCategory).items
        st.xmbItem = (st.xmbItem + direction + items.size) % items.size
        if (st.soundAlerts &&
            !PortableUiSounds.play(PortableUiSounds.Cue.MOVE)) Chiptune.orbitMove()
    }

    private fun openXmbItem() {
        val items = XmbMenu.category(st.xmbCategory).items
        openTile(items[st.xmbItem.coerceIn(items.indices)].id)
    }

    private fun syncDeviceVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        st.deviceVolume = (current * 100f / max).toInt().coerceIn(0, 100)
    }

    private fun setDeviceVolume(value: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
            (value.coerceIn(0f, 1f) * max).toInt(), 0)
        syncDeviceVolume()
    }

    private fun syncDeviceBrightness() {
        val windowValue = window.attributes.screenBrightness
        st.deviceBrightness = if (windowValue >= 0f) {
            (windowValue * 100f).toInt().coerceIn(1, 100)
        } else {
            runCatching {
                android.provider.Settings.System.getInt(contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
            }.getOrDefault(72).coerceIn(1, 100)
        }
    }

    private fun setDeviceBrightness(value: Float) {
        val level = value.coerceIn(0.02f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = level }
        st.deviceBrightness = (level * 100f).toInt().coerceIn(1, 100)
        getSharedPreferences("deck", MODE_PRIVATE).edit().putFloat("display_brightness",level).apply()
    }

    private fun adjustDeviceVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        syncDeviceVolume()
        toast("volume ${st.deviceVolume}%")
        blip()
    }

    private fun adjustDeviceBrightness(direction: Int) {
        setDeviceBrightness((st.deviceBrightness + direction * 5) / 100f)
        toast("brightness ${st.deviceBrightness}%")
        blip()
    }

    private fun toggleDeviceMute() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_TOGGLE_MUTE, 0)
        syncDeviceVolume()
        toast(if (st.deviceVolume == 0) "muted" else "volume ${st.deviceVolume}%")
    }

    private fun selectSaver(mode: String) {
        st.saverMode = when (mode) {
            "highway" -> "highway"; "pet" -> "pet"; "spider" -> "spider"; else -> "mountain"
        }
        if (st.saverMode == "spider") st.selectedPet = "webby"
        getSharedPreferences("deck", MODE_PRIVATE).edit()
            .putString("saver_mode", st.saverMode)
            .putString("selected_pet", st.selectedPet).apply()
        // Manual selection is itself a fresh screensaver entrance. Play Webby's
        // cue exactly once here; automatic idle entry remains handled by enterSaver().
        enterSaver(playTheme = st.saverMode != "spider")
        if (st.saverMode == "spider" && st.soundAlerts) Chiptune.webSaverTheme()
        toast(when (st.saverMode) {
            "highway" -> "pixel highway default"
            "pet" -> "pets default"
            "spider" -> "web skyline default"
            else -> "mountain default"
        })
        blip()
    }

    private fun enterSaver(playTheme: Boolean = true) {
        val entering = st.screen != Screen.SAVER
        st.screen = Screen.SAVER
        if (entering && playTheme && st.saverMode == "spider" && st.soundAlerts)
            Chiptune.webSaverTheme()
    }

    /** Show a local companion alert without focusing or changing any PC window. */
    private fun showPetAlert(app: String, message: String, important: Boolean,
                             playSound: Boolean = true) {
        val now = System.currentTimeMillis()
        st.pcNotificationApp = app.uppercase().take(24)
        st.pcNotificationTitle = message.uppercase().take(64)
        st.pcNotificationUntil = now + if (important) 11_000L else 7_500L
        st.pcNotificationCount += 1
        st.petSpeech = st.pcNotificationTitle
        st.petSpeechUntil = st.pcNotificationUntil
        if (st.selectedPet == "suhair") {
            SuhairBehavior.requestInScene(st.suhairPet, SuhairIntent.SHOW_NOTIFICATION,
                SuhairScene.COMMAND, now, message = message, source = app,
                region = SuhairRegion.RIGHT)
            if (important && !view.quickControlsOpen && st.screen in listOf(
                    Screen.HOME, Screen.SAVER, Screen.DASHBOARD, Screen.AGENT, Screen.TASKS)) {
                st.saverMode = "pet"
                enterSaver(playTheme = false)
            }
        } else {
            st.petJumpStartedAt = now
            st.petJumpIndex = (st.petJumpIndex + 1) % 3
            st.petWebBurstUntil = now + 2_400L
            reactPet("waving", if (important) 6_500L else 3_200L)
            if (important && !view.quickControlsOpen && st.screen in listOf(
                    Screen.HOME, Screen.SAVER, Screen.DASHBOARD, Screen.AGENT, Screen.TASKS)) {
                st.selectedPet = "webby"
                st.saverMode = "spider"
                enterSaver(playTheme = false)
            }
        }
        toast("${app.uppercase()} notification")
        if (playSound && st.soundAlerts) Chiptune.needsYou()
        view.invalidate()
    }

    private fun updateSuhairFromContext() {
        if (st.selectedPet != "suhair") return
        val state = st.nowState.lowercase()
        val activeProject = st.aiProjects.firstOrNull { it.state.lowercase() in setOf(
            "working", "running", "thinking", "active", "waiting", "paused",
            "needs_input", "needs-input", "review"
        ) }
        val foregroundAi = st.nowKind.lowercase() in setOf("chatgpt", "codex", "claude", "cowork")
        val intent = SuhairBehavior.intentForContext(st.nowKind, state, activeProject?.state)
        val useProject = !foregroundAi && intent != SuhairIntent.IDLE && activeProject != null
        val message = if (useProject) activeProject?.let { project ->
            project.detail.ifBlank { project.task }.ifBlank { project.project }
        }.orEmpty() else st.nowDetail.ifBlank { st.nowTask }.ifBlank { st.nowProject }
        val source = if (useProject) "CODEX" else st.nowName.ifBlank { st.nowKind }
        val desiredScene = SuhairBehavior.sceneForContext(
            st.nowKind,
            intent,
            st.suhairPet.scene
        )
        // The studio laptop is a real destination, not decoration. A work intent first walks
        // Suhair to the right-side workstation and only then starts the typing animation.
        val desiredRegion = if (intent == SuhairIntent.WORK && desiredScene in setOf(
                SuhairScene.STUDIO, SuhairScene.COMMAND))
            SuhairRegion.RIGHT else null
        if (st.suhairPet.regionTransitionActive && desiredRegion != null &&
                st.suhairPet.targetRegion == desiredRegion && st.suhairPet.settleIntent == intent) {
            st.suhairPet.pendingMessage = SuhairBehavior.safeExternalText(message)
            st.suhairPet.pendingSource = SuhairBehavior.safeExternalText(source, 24)
            return
        }
        if (intent == st.suhairPet.intent && intent in listOf(
                SuhairIntent.IDLE, SuhairIntent.WAIT, SuhairIntent.WORK, SuhairIntent.REVIEW) &&
                st.suhairPet.scene == desiredScene && !st.suhairPet.sceneTransitionActive &&
                (desiredRegion == null || st.suhairPet.region == desiredRegion)) {
            st.suhairPet.message = SuhairBehavior.safeExternalText(message)
            st.suhairPet.source = SuhairBehavior.safeExternalText(source, 24)
        } else {
            SuhairBehavior.requestInScene(st.suhairPet, intent,
                desiredScene,
                message = message, source = source, region = desiredRegion)
        }
    }

    private fun sdPress(key: Int) {
        client.send("sd", "key" to key, "page" to st.sdPage)
        toast("deck $key!")
        blip()
    }

    private fun sdEntry(key: Int): JSONObject? = try {
        val pages = StreamDeckDefaults.pages(st.sdPagesJson)
        if (st.sdPage !in 0 until pages.length()) null
        else pages.getJSONObject(st.sdPage).optJSONObject("keys")?.optJSONObject("$key")
    } catch (_: Exception) { null }

    private fun sdHold(key: Int) {
        val profile = sdEntry(key)?.let {
            AppProfiles.infer(it.optString("label"), it.optString("value"), it.optString("match"))
        }
        if (profile == null) {
            toast("no mini controller for key $key")
            if (st.soundAlerts) Chiptune.error()
            return
        }
        client.send("sd", "key" to key, "page" to st.sdPage)
        st.appControllerId = profile.id
        st.appControllerTitle = profile.title
        st.appControllerPage = 0
        st.screen = Screen.APP_CONTROLLER
        toast("${profile.title.lowercase()} ready")
        blip()
    }

    private fun appControllerPress(key: Int) {
        val profile = AppProfiles.get(st.appControllerId) ?: return
        val pageSize = if (Themes.current.name == "LIQUID GLASS") 6 else 9
        val control = profile.controls.getOrNull(st.appControllerPage * pageSize + key - 1) ?: return
        if (control.id == "talk") {
            startAiComposer("chatgpt")
        } else {
            client.send("app_control", "app" to profile.id, "action" to control.id)
            toast(control.label.lowercase())
            blip()
        }
    }

    private fun aiWorkspacePress(key: Int) {
        val control = if (st.aiWorkspacePage == 0) {
            val actions = listOf(
                AppControl("talk", "REPLY VOICE"), AppControl("focus", "OPEN ON PC"),
                AppControl("send", "SEND"), AppControl("stop", "STOP")
            )
            actions.getOrNull(key - 1)
        } else AppProfiles.get(st.aiWorkspaceKind)?.controls?.getOrNull((st.aiWorkspacePage - 1) * 9 + key - 1)
        control ?: return
        if (control.id == "talk") {
            startAiComposer("chatgpt")
        } else if (control.id == "focus") {
            focusPcApp("chatgpt")
        } else {
            client.send("app_control", "app" to st.aiWorkspaceKind, "action" to control.id)
            toast(control.label.lowercase())
            blip()
        }
    }

    private fun focusPcApp(id: String) {
        client.send("app_control", "app" to id, "action" to "focus")
        st.nowName = id.uppercase()
        st.nowKind = "external"
        toast("focus $id on PC")
    }

    private fun openPcAppSwitcher() {
        if (!st.connected) {
            toast("BRIDGE OFFLINE - START THE PC BRIDGE")
            if (st.soundAlerts) Chiptune.error()
            return
        }
        st.pcAppWindows.clear()
        st.appSwitcherPage = 0
        st.screen = Screen.APP_SWITCHER
        client.send("window_list")
        toast("READING OPEN WINDOWS...")
        blip()
    }

    private fun focusPcWindow(number: Int) {
        val index = st.appSwitcherPage * 8 + number - 1
        val app = st.pcAppWindows.getOrNull(index) ?: run {
            toast("NO APP IN SLOT $number")
            return
        }
        client.send("window_focus", "id" to app.id)
        toast("FOCUSING ${app.name.uppercase()}")
        blip()
    }

    private fun moveAppSwitcherPage(delta: Int) {
        val pages = maxOf(1, (st.pcAppWindows.size + 7) / 8)
        st.appSwitcherPage = (st.appSwitcherPage + delta + pages) % pages
        blip()
    }

    private fun togglePcTheme() {
        if (!st.connected) {
            toast("BRIDGE OFFLINE - CANNOT CHANGE WINDOWS THEME")
            if (st.soundAlerts) Chiptune.error()
            return
        }
        client.send("theme_toggle")
        toast("CHANGING WINDOWS THEME...")
        blip()
    }

    private fun launchStore() {
        for (pkg in listOf("com.android.vending", "org.fdroid.fdroid", "com.aurora.store")) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { startActivity(i); toast("opening store…"); return }
        }
        toast("no store installed yet - ask Claude!")
        if (st.soundAlerts) Chiptune.error()
    }

    /** Launches local, user-supplied games through the verified 32-bit RetroArch build. */
    private fun launchLocalGame(index: Int) {
        val mario = index == 0
        val rom = if (mario)
            "/storage/emulated/0/Manzanilla/Games/NES/SuperMarioBros3/SuperMarioBros3.nes"
        else
            "/storage/emulated/0/Manzanilla/Games/PlayStation/NASCAR Rumble/NASCAR Rumble.cue"
        val core = if (mario)
            "/data/user/0/com.retroarch.ra32/cores/fceumm_libretro_android.so"
        else
            "/data/user/0/com.retroarch.ra32/cores/pcsx_rearmed_libretro_android.so"
        val config = if (mario)
            "/storage/emulated/0/Manzanilla/Emulator/manzanilla-nes.cfg"
        else
            "/storage/emulated/0/Manzanilla/Emulator/manzanilla-ps1.cfg"

        try {
            // Show the coach first.  It launches RetroArch from its OK action,
            // guaranteeing the game is the activity revealed after dismissal.
            startActivity(android.content.Intent(this, ControllerCoachActivity::class.java).apply {
                putExtra("profile", if (mario) "mario" else "playstation")
                putExtra("ROM", rom)
                putExtra("LIBRETRO", core)
                putExtra("CONFIGFILE", config)
            })
            toast(if (mario) "Mario controls ready" else "NASCAR controls ready")
        } catch (_: Exception) {
            toast("game player is not installed")
            if (st.soundAlerts) Chiptune.error()
        }
    }

    private fun openPcAppWithCoach(id: String) {
        focusPcApp(id)
        val profile = AppProfiles.get(id) ?: return
        st.appControllerId = profile.id
        st.appControllerTitle = profile.title
        st.appControllerPage = 0
        st.screen = Screen.APP_CONTROLLER
    }

    private fun navigateBack() {
        if (!view.livePreview && view.voiceMode == "live" && (liveCallActive || liveStartPending)) requestLiveStop()
        view.livePreview = false
        st.screen = when (st.screen) {
            Screen.HOME, Screen.BOOT -> Screen.HOME
            Screen.APP_CONTROLLER, Screen.APP_SWITCHER -> Screen.STREAM
            Screen.THEMES, Screen.SAVER_PICK, Screen.PET_PICK, Screen.VOLUME, Screen.KEYLEARN -> Screen.SETTINGS
            Screen.AGENT, Screen.SPAWN_PICK -> Screen.DASHBOARD
            Screen.PET_CALIBRATE, Screen.PET_GAZE_CALIBRATE -> Screen.PET
            else -> Screen.HOME
        }
        if (st.soundAlerts) {
            if (isOrbitAudioTheme()) {
                if (!PortableUiSounds.play(PortableUiSounds.Cue.BACK)) Chiptune.orbitBack()
            } else Chiptune.blip()
        }
    }

    private fun updatePetTracking() {
        val shouldRun = (st.screen == Screen.PET || st.screen == Screen.PET_CALIBRATE ||
            st.screen == Screen.PET_GAZE_CALIBRATE ||
            st.screen == Screen.SAVER &&
            (st.saverMode == "pet" || st.saverMode == "spider")) &&
            st.petTrackingEnabled &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (shouldRun && !faceTrackerRunning) {
            faceTrackerRunning = true
            faceTracker.start({ target -> ui.post {
                val hadFace = st.petFacePresent
                if (target == null) {
                    st.petFacePresent = false
                    st.petLookingAtDevice = false
                    st.petGazeConfidence = 0f
                    gazeAttentionScore = 0f
                    gazeLockedSince = 0L
                } else {
                    val movement = kotlin.math.abs(target.x - st.petTargetX) +
                        kotlin.math.abs(target.y - st.petTargetY)
                    st.petTargetX = target.x
                    st.petTargetY = target.y
                    st.petFacePresent = true
                    val now = System.currentTimeMillis()
                    latestGazeFeatures = target.gazeFeatures.copyOf()
                    if (target.gazeFeatures.size == 5) {
                        recentGazeSamples.addLast(now to target.gazeFeatures.copyOf())
                        while (recentGazeSamples.isNotEmpty() && now - recentGazeSamples.first.first > 3500L)
                            recentGazeSamples.removeFirst()
                    }
                    gazeAttentionScore = (gazeAttentionScore +
                        if (target.lookingAtDevice) .11f else -.075f).coerceIn(0f, 1f)
                    // Two thresholds prevent rapid on/off switching near the boundary.
                    st.petLookingAtDevice = if (st.petLookingAtDevice)
                        gazeAttentionScore > .32f else gazeAttentionScore >= .72f
                    st.petGazeConfidence = target.gazeConfidence
                    if (st.petLookingAtDevice) {
                        gazeLostSince = 0L
                        if (gazeLockedSince == 0L) gazeLockedSince = now
                        if (st.screen !in listOf(Screen.PET_CALIBRATE, Screen.PET_GAZE_CALIBRATE) &&
                            webPetActive() && st.petInteractionStage != "choosing" &&
                            now - gazeLockedSince >= 800L) {
                            st.petInteractionStage = "choosing"
                            petAwaitingHandReset = handWasRaised
                            petHandAbsentSince = if (handWasRaised) 0L else now - 700L
                            reactPet("curious", 1800L)
                            sayPet("I SEE YOU! SHOW 1 FOR CHROME, 2 FOR SPAIN WORK, OR 3 FOR STREAM DECK")
                            toast("GAZE LOCKED - SHOW 1, 2, OR 3")
                            if (st.soundAlerts) Chiptune.select()
                        }
                    } else {
                        gazeLockedSince = 0L
                        if (gazeLostSince == 0L) gazeLostSince = now
                        if (now - gazeLostSince >= 600L) {
                            st.petInteractionStage = "idle"
                            observedGesture = 0
                            gestureStableSince = 0L
                        }
                    }
                    if (suhairPetActive()) {
                        when {
                            !hadFace && now - lastFaceGreetingAt > 15_000L -> {
                                lastFaceGreetingAt = now
                                SuhairBehavior.request(st.suhairPet, SuhairIntent.GREET, now,
                                    message = "Hello! I am here when you need me.", source = "SUHAIR")
                            }
                            target.smiling && st.suhairPet.intent == SuhairIntent.IDLE &&
                                now - lastFaceGreetingAt > 9_000L -> {
                                lastFaceGreetingAt = now
                                SuhairBehavior.request(st.suhairPet, SuhairIntent.CELEBRATE, now,
                                    message = "Great to see you!", source = "SUHAIR")
                            }
                        }
                    } else {
                        val greetingActive = st.petExpression in listOf("waving", "jumping") &&
                            System.currentTimeMillis() < petReactionUntil
                        when {
                            greetingActive -> Unit
                            target.smiling -> reactPet("happy", 900L)
                            !hadFace && webPetActive() && now - lastFaceGreetingAt > 15_000L -> {
                                lastFaceGreetingAt = now
                                st.petInteractionStage = "greeted"
                                reactPet("happy", 2800L)
                                sayPet("HELLO! LOOK AT ME WHEN YOU WANT THE APP MENU", audible = false)
                            }
                            !hadFace -> reactPet("happy", 1800L)
                            movement > .28f -> reactPet("curious", 650L)
                        }
                    }
                }
                view.invalidate()
            } }, { gesture -> ui.post {
                val now = System.currentTimeMillis()
                st.petGestureCount = gesture.fingers
                st.petGestureRaw = gesture.rawFingers
                st.petGestureConfidence = gesture.confidence
                latestGestureFeatures = gesture.features.copyOf()
                val openPalm = gesture.handRaised && gesture.rawFingers == 4
                if (st.screen !in listOf(Screen.PET_CALIBRATE, Screen.PET_GAZE_CALIBRATE) &&
                    (webPetActive() || suhairPetActive()) && st.petLookingAtDevice && openPalm && !openPalmWasRaised &&
                    now - lastWaveTrickAt > 1800L) {
                    lastWaveTrickAt = now
                    if (suhairPetActive()) {
                        SuhairBehavior.request(st.suhairPet, SuhairIntent.GREET, now,
                            message = "Hello! Nice wave.", source = "SUHAIR")
                    } else {
                        st.petJumpStartedAt = now
                        st.petJumpUntil = now + 1500L
                        st.petJumpIndex = (st.petJumpIndex + 1) % 3
                        st.petWebBurstUntil = now + 1300L
                        reactPet("jumping", 1600L)
                        sayPet("HELLO! NICE WAVE!", audible = false)
                    }
                    toast("WAVE SEEN - TRICK ONLY, NO COMMAND")
                    if (st.soundAlerts) Chiptune.select()
                }
                if (!gesture.handRaised || gesture.rawFingers == 0) {
                    observedGesture = 0; gestureStableSince = 0L
                    if (!gesture.handRaised) {
                        if (petHandAbsentSince == 0L) petHandAbsentSince = now
                        // A brief tracking miss during a wave is not a deliberate reset.
                        if (now - petHandAbsentSince >= 650L) petAwaitingHandReset = false
                    }
                } else if (gesture.rawFingers !in 1..3) {
                    // Four extended fingers is the open-palm wave state. Never select an app.
                    petHandAbsentSince = 0L
                    observedGesture = 0; gestureStableSince = 0L
                } else if (gesture.fingers != observedGesture) {
                    petHandAbsentSince = 0L
                    observedGesture = gesture.fingers; gestureStableSince = now
                } else {
                    petHandAbsentSince = 0L
                    val needed = if (gesture.fingers == 3) 1200L else 850L
                    if (st.screen !in listOf(Screen.PET_CALIBRATE, Screen.PET_GAZE_CALIBRATE) &&
                        st.petLookingAtDevice &&
                        st.petInteractionStage == "choosing" && !petAwaitingHandReset &&
                        now - gestureStableSince > needed && now - lastGestureActionAt > 1800L) {
                        lastGestureActionAt = now
                        performPetGesture(gesture.fingers)
                    }
                }
                openPalmWasRaised = openPalm
                handWasRaised = gesture.handRaised
            } }, { available -> ui.post {
                st.petTrackingAvailable = available
                if (!available) faceTrackerRunning = false
            } }, { st.screen == Screen.PET_CALIBRATE || st.screen == Screen.PET_GAZE_CALIBRATE }, { bitmap -> ui.post {
                val old = view.cameraPreviewFrame
                view.cameraPreviewFrame = bitmap
                if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                view.invalidate()
            } })
        } else if (!shouldRun && faceTrackerRunning) {
            faceTracker.stop(); faceTrackerRunning = false
            st.petFacePresent = false
            handWasRaised = false
            petAwaitingHandReset = false
            petHandAbsentSince = 0L
            st.petGestureCount = 0
            st.petGestureRaw = 0
            st.petLookingAtDevice = false
            gazeLockedSince = 0L
            gazeLostSince = 0L
            openPalmWasRaised = false
        }
    }

    private fun webPetActive(): Boolean = st.selectedPet == "webby" &&
        (st.screen == Screen.PET || st.screen == Screen.PET_CALIBRATE ||
            st.screen == Screen.PET_GAZE_CALIBRATE ||
            st.screen == Screen.SAVER && st.saverMode == "spider" ||
            st.screen == Screen.VOICE &&
            (screenBeforeVoice == Screen.PET || screenBeforeVoice == Screen.SAVER && st.saverMode == "spider"))

    private fun suhairPetActive(): Boolean = st.selectedPet == "suhair" &&
        (st.screen == Screen.PET || st.screen == Screen.PET_CALIBRATE ||
            st.screen == Screen.PET_GAZE_CALIBRATE ||
            st.screen == Screen.SAVER && st.saverMode == "pet" ||
            st.screen == Screen.VOICE &&
            (screenBeforeVoice == Screen.PET || screenBeforeVoice == Screen.SAVER && st.saverMode == "pet"))

    private fun sayPet(message: String, audible: Boolean = false) {
        st.petSpeech = message.uppercase().take(64)
        st.petSpeechUntil = System.currentTimeMillis() + 4200L
        if (audible && st.soundAlerts && petTtsReady)
            petTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "manzanilla-pet")
        view.invalidate()
    }

    private fun performPetGesture(fingers: Int) {
        when (fingers) {
            1 -> {
                st.petInteractionStage = "idle"
                reactPet("happy", 2200L)
                pendingPetCommand = "CHROME OPENED"
                sayPet("1 SEEN - CHROME COMMAND SENT TO PC", audible = false)
                focusPcApp("chrome")
            }
            2 -> {
                st.petInteractionStage = "idle"
                reactPet("happy", 2200L)
                pendingPetCommand = "SPAIN ACADEMY WORKSPACE OPENED"
                sayPet("2 SEEN - OPENING 4 COMET TABS + SPAIN ACADEMY FILES", audible = false)
                client.send("app_control", "app" to "comet", "action" to "spain_academy")
                toast("Spain Academy workspace command sent")
            }
            3 -> {
                st.petInteractionStage = "idle"
                sayPet("3 SEEN - STREAM DECK READY", audible = false)
                st.screen = Screen.STREAM
                toast("three fingers: stream deck")
            }
        }
    }

    private fun calibratePetGesture(label: Int) {
        if (label !in 1..3) return
        if (st.petGestureRaw == 0 || latestGestureFeatures.size != 63) {
            toast("HOLD UP $label FINGER${if (label == 1) "" else "S"}, THEN TAP $label")
            if (st.soundAlerts) Chiptune.error()
            return
        }
        if (faceTracker.saveCalibration(label, latestGestureFeatures)) {
            st.petCalibrated[label - 1] = true
            toast("$label FINGER POSE SAVED OFFLINE")
            if (st.soundAlerts) Chiptune.done()
        }
    }

    private fun calibratePetGaze(kind: String) {
        if (kind !in listOf("device", "laptop")) return
        val now = System.currentTimeMillis()
        val samples = recentGazeSamples.filter { now - it.first <= 2500L && it.second.size == 5 }
        val span = if (samples.isEmpty()) 0L else samples.last().first - samples.first().first
        // The Orange device's low-power camera yields only a few full 478-point
        // iris measurements per second. Four readings across a second is a real
        // multi-frame lesson without making calibration appear broken.
        if (!st.petFacePresent || samples.size < 4 || span < 1000L) {
            toast("HOLD THIS LOOK STEADY FOR 2 SECONDS, THEN TAP AGAIN")
            if (st.soundAlerts) Chiptune.error()
            return
        }
        val averaged = FloatArray(5)
        samples.forEach { sample ->
            for (i in averaged.indices) averaged[i] += sample.second[i]
        }
        for (i in averaged.indices) averaged[i] /= samples.size.toFloat()
        if (faceTracker.saveGazeCalibration(kind, averaged)) {
            st.petGazeDeviceCalibrated = faceTracker.isGazeCalibrated("device")
            st.petGazeLaptopCalibrated = faceTracker.isGazeCalibrated("laptop")
            gazeAttentionScore = 0f
            st.petLookingAtDevice = false
            toast(if (kind == "device") "MANZANILLA LOOK LEARNED FROM ${samples.size} FRAMES"
                else "LAPTOP LOOK LEARNED FROM ${samples.size} FRAMES")
            if (st.soundAlerts) Chiptune.done()
        }
    }

    private fun reactPet(expression: String, duration: Long) {
        st.petExpression = expression
        petReactionUntil = System.currentTimeMillis() + duration
    }

    private fun cyclePetMood() {
        val moods = listOf("idle", "curious", "happy", "waving", "thinking", "sleepy", "error")
        val next = moods[(moods.indexOf(st.petExpression).coerceAtLeast(0) + 1) % moods.size]
        reactPet(next, if (next == "idle") 0L else 3500L)
        blip()
    }

    private fun cycleSuhairMood() {
        val moods = listOf(
            SuhairIntent.IDLE, SuhairIntent.GREET, SuhairIntent.CELEBRATE,
            SuhairIntent.EXPLAIN, SuhairIntent.WAIT, SuhairIntent.WORK,
            SuhairIntent.REVIEW, SuhairIntent.FAIL
        )
        val next = moods[(moods.indexOf(st.suhairPet.intent).coerceAtLeast(0) + 1) % moods.size]
        SuhairBehavior.requestInScene(st.suhairPet, next,
            SuhairBehavior.defaultSceneFor(next, st.suhairPet.scene),
            message = when (next) {
                SuhairIntent.GREET -> "Hello! I am ready."
                SuhairIntent.CELEBRATE -> "Task completed."
                SuhairIntent.EXPLAIN -> "Here is the latest update."
                SuhairIntent.WAIT -> "Waiting for your response."
                SuhairIntent.WORK -> "Working on the current task."
                SuhairIntent.REVIEW -> "Ready for your review."
                SuhairIntent.FAIL -> "The task needs attention."
                else -> ""
            }, source = "PREVIEW")
        blip()
    }

    // ---- key handling -------------------------------------------------------

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (view.livePreview && keymap.fn(code) in listOf(Fn.CALL,Fn.END)) { poke(); return true }
        if (view.quickControlsOpen) {
            poke()
            if(code==KeyEvent.KEYCODE_BACK) { view.quickControlsOpen=false; view.invalidate() }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            navigateBack()
            return true
        }
        ev.startTracking()
        poke()
        if (st.screen == Screen.SAVER) { st.screen = Screen.HOME; blip(); return true }
        if (st.screen == Screen.BOOT) { finishBoot(); return true }
        if (st.screen == Screen.KEYLEARN) return learnKey(code)
        val physicalRole = keymap.fn(code)
        if (liveAvailable && physicalRole == Fn.CALL) return true
        if ((liveCallActive || liveStartPending || liveStopRequested) && physicalRole == Fn.END) {
            liveEndKeyHandled = true
            if (ev.repeatCount == 0) requestLiveStop()
            return true
        }
        if (ev.repeatCount == 0 && physicalRole in listOf(Fn.MINUS, Fn.PLUS)) {
            val delta = if (physicalRole == Fn.MINUS) -1 else 1
            val handled = when (st.screen) {
                Screen.HOME -> if (Themes.current.name != "ORBIT") {
                    st.homePage = 1 - st.homePage
                    blip()
                    true
                } else false
                Screen.STREAM -> {
                    val count = StreamDeckDefaults.pageCount(st.sdPagesJson)
                    st.sdPage = (st.sdPage + delta + count) % count
                    blip()
                    true
                }
                Screen.APP_SWITCHER -> { moveAppSwitcherPage(delta); true }
                Screen.DYNAMIC_STATUS -> {
                    st.statusCardDirection = delta
                    st.statusCardIndex = (st.statusCardIndex + delta + 3) % 3
                    st.statusCardLastManualAt = System.currentTimeMillis()
                    blip()
                    true
                }
                else -> false
            }
            if (handled) {
                physicalNavDown.add(code)
                return true
            }
        }
        if (keymap.fn(code) == Fn.CALL && !callHeld) {
            callHeld = true
            ui.postDelayed(pttStarter, 300)
        }
        val role = keymap.role(code)
        if (role != null && keymap.action(role) == Act.PTT_GLOBAL && !wisprHeld) {
            wisprHeld = true
            ui.postDelayed(wisprStarter, 260)
        }
        return true
    }

    override fun onKeyLongPress(code: Int, ev: KeyEvent): Boolean {
        if (view.livePreview) return true
        if (liveAvailable && keymap.fn(code) == Fn.CALL) return true
        if (liveCallActive && keymap.fn(code) == Fn.END) return true
        if (view.quickControlsOpen) return true
        if (st.screen == Screen.HOME && keymap.isStar(code)) return true
        if (st.screen == Screen.KEYLEARN) return true
        val d = keymap.digit(code)
        when {
            st.screen == Screen.STREAM && d != null && d in 1..9 -> sdHold(d)
            st.screen == Screen.STREAM && keymap.isPound(code) -> openPcAppSwitcher()
            st.screen == Screen.STREAM && keymap.isStar(code) -> togglePcTheme()
            d != null && d in 1..9 -> { st.selected = d; st.screen = Screen.SPAWN_PICK; blip() }
            d == 0 -> { st.screen = Screen.SETTINGS; blip() }
            keymap.isPound(code) -> st.screen = Screen.VIEWFINDER
            keymap.fn(code) == Fn.END -> { client.send("interrupt", "level" to "kill"); toast("kill sent"); Chiptune.error() }
            else -> return false
        }
        return true
    }

    override fun onKeyUp(code: Int, ev: KeyEvent): Boolean {
        poke()
        if (liveEndKeyHandled && keymap.fn(code) == Fn.END) {
            liveEndKeyHandled = false
            return true
        }
        if (view.livePreview) {
            if (keymap.fn(code) == Fn.CALL) {
                val states = listOf("Connecting","Listening","Speaking","Working","Saved")
                view.liveStatus = states[(states.indexOf(view.liveStatus)+1)%states.size]
                view.invalidate()
            } else if (keymap.fn(code) == Fn.END || keymap.digit(code) in listOf(0,5)) navigateBack()
            return true
        }
        if(view.quickControlsOpen) {
            CompanionInput.quickAction(keymap.digit(code),keymap.fn(code),keymap.isPound(code))?.let { touch(it) }
            return true
        }
        if(st.screen==Screen.HOME && keymap.isPound(code)) {
            touch("quick_open"); return true
        }
        if(st.screen==Screen.HOME && keymap.isStar(code)) {
            touch("arc_open"); return true
        }
        if (st.screen == Screen.KEYLEARN) return true
        val role = keymap.role(code) ?: return super.onKeyUp(code, ev)
        val d = keymap.digit(code)
        val canceledLongPress = (ev.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0
        if (physicalNavDown.remove(code)) return true

        // CALL is a dedicated Wispr Flow / PC dictation key. Hold for PTT;
        // tap once to leave dictation listening until CALL is pressed again.
        if (role == Fn.CALL) {
            if (liveAvailable) {
                callHeld = false
                ui.removeCallbacks(pttStarter)
                if (!canceledLongPress) {
                    if (liveCallActive || liveStartPending) {
                        requestLiveStop()
                    } else if (!liveStopRequested) {
                        view.voiceMode = "live"
                        view.liveStatus = if(st.connected) "Connecting" else "Disconnected"
                        view.voiceLine = if(st.connected) "Opening your conversation…" else "Connect Orange to your laptop first."
                        view.voiceCapturing = st.connected
                        st.pttStart = System.currentTimeMillis()
                        st.screen = Screen.VOICE
                        liveStartPending = st.connected
                        view.invalidate()
                        if(st.connected) client.send("live_toggle")
                    }
                }
                return true
            }
            callHeld = false
            ui.removeCallbacks(pttStarter)
            if (pttMode == "global" || pttMode == "agent") {
                globalDictationOn = false
                stopPtt()
            } else if (!canceledLongPress) toggleGlobalDictation()
            return true
        }
        if (keymap.action(role) == Act.PTT_GLOBAL) {
            wisprHeld = false
            ui.removeCallbacks(wisprStarter)
            if (pttMode == "global" || pttMode == "agent") {
                globalDictationOn = false
                stopPtt()
            } else if (!canceledLongPress) toggleGlobalDictation()
            return true
        }
        if (canceledLongPress) return true
        // END: double-tap = ctrl-c (fixed), tap = assigned action
        if (role == Fn.END) {
            if (view.voiceMode == "live" && (liveCallActive || liveStartPending || liveStopRequested)) {
                requestLiveStop()
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastEndPress < 450) { client.send("interrupt", "level" to "sigint"); toast("ctrl-c!") }
            else exec(keymap.action(role), null)
            lastEndPress = now
            return true
        }

        // Physical 0 is the universal in-app Back key. Keep it available as a
        // literal zero only on the dialler; every menu/controller returns to
        // its parent instead of throwing the user all the way to Home.
        if (d == 0 && st.screen !in listOf(Screen.HOME, Screen.DIAL, Screen.BOOT,
                Screen.SAVER, Screen.VOICE, Screen.KEYLEARN)) {
            navigateBack()
            return true
        }

        // contextual screens first
        when (st.screen) {
            Screen.VOICE -> {
                if (view.voiceMode == "live") {
                    if (d == 5 || d == 0) {
                        requestLiveStop()
                        if (d == 0) st.screen = Screen.HOME
                    }
                    return true
                }
                if (view.voiceReady) {
                    when (d) {
                        5 -> submitAiComposer()
                        1 -> startAiComposer("chatgpt")
                        0 -> cancelAiComposer()
                    }
                } else if ((d == 5 || d == 2) && pttMode != null) {
                    // 5 is the keypad OK key: stop capture now, then use the
                    // same key again to confirm once the transcript is ready.
                    // Keep 2 as a compatibility shortcut for existing units.
                    stopPtt()
                }
                return true
            }
            Screen.DYNAMIC_STATUS -> {
                when (d) {
                    1 -> if (st.statusCardIndex == 1 && st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex")) startAiComposer("chatgpt")
                    2 -> if (st.statusCardIndex == 1 && st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex")) focusPcApp(st.pcNotificationApp.lowercase())
                    7 -> if (st.statusCardIndex == 1) { view.notificationPage=maxOf(0,view.notificationPage-1); blip() }
                    9 -> if (st.statusCardIndex == 1) { view.notificationPage++; blip() }
                    4 -> {
                        st.statusCardDirection = -1
                        st.statusCardIndex = (st.statusCardIndex + 2) % 3
                        st.statusCardLastManualAt = System.currentTimeMillis()
                        blip()
                    }
                    6 -> {
                        st.statusCardDirection = 1
                        st.statusCardIndex = (st.statusCardIndex + 1) % 3
                        st.statusCardLastManualAt = System.currentTimeMillis()
                        blip()
                    }
                }
                return true
            }
            Screen.HOME -> {
                if (Themes.current.name == "ORBIT") {
                    when (d) {
                        4 -> moveXmbCategory(-1)
                        6 -> moveXmbCategory(1)
                        2 -> moveXmbItem(-1)
                        8 -> moveXmbItem(1)
                        5 -> openXmbItem()
                        0 -> { goHome(); blip() }
                    }
                    return true
                }
                var handled = true
                when {
                    d != null && d in 1..8 -> {
                        st.homeSel = d - 1
                        view.homeTileId(st.homePage, d - 1)?.let { openTile(it) }
                    }
                    d == 9 || role == Fn.MINUS || role == Fn.PLUS -> {
                        st.homePage = 1 - st.homePage; blip()
                    }
                    d == 0 -> goHome()
                    else -> handled = false
                }
                if (handled) return true
            }
            Screen.SPAWN_PICK -> {
                when (d) { 1 -> spawn("claude"); 2 -> spawn("codex") }
                if (d != null) st.screen = Screen.DASHBOARD
                return true
            }
            Screen.SETTINGS -> {
                if (Themes.current.name == "ORBIT") {
                    when {
                        d == 4 -> moveOrbitSettingsCategory(-1)
                        d == 6 -> moveOrbitSettingsCategory(1)
                        d == 2 -> moveOrbitSettingsItem(-1)
                        d == 8 -> moveOrbitSettingsItem(1)
                        d == 5 -> openOrbitSetting()
                        d == 0 -> { goHome(); blip() }
                    }
                } else d?.let { settingsChoice(it) }
                return true
            }
            Screen.VOLUME -> {
                when (d) {
                    2 -> adjustDeviceBrightness(-1)
                    4 -> adjustDeviceVolume(-1)
                    5 -> toggleDeviceMute()
                    6 -> adjustDeviceVolume(1)
                    8 -> adjustDeviceBrightness(1)
                }
                return true
            }
            Screen.SAVER_PICK -> {
                when (d) {
                    1 -> selectSaver("mountain")
                    2 -> selectSaver("highway")
                    3 -> selectSaver("pet")
                    4 -> selectSaver("spider")
                    0 -> goHome()
                }
                return true
            }
            Screen.PET_PICK -> {
                when (d) {
                    1 -> { st.selectedPet = "manni"; st.screen = Screen.PET; blip() }
                    2 -> {
                        st.selectedPet = "webby"; st.saverMode = "spider"; st.screen = Screen.PET
                        getSharedPreferences("deck", MODE_PRIVATE).edit()
                            .putString("selected_pet", "webby").putString("saver_mode", "spider").apply()
                        if (st.soundAlerts) Chiptune.webSaverTheme()
                        sayPet("Ready to patrol"); blip()
                    }
                    3 -> {
                        st.selectedPet = "suhair"; st.saverMode = "pet"; st.screen = Screen.PET
                        getSharedPreferences("deck", MODE_PRIVATE).edit()
                            .putString("selected_pet", "suhair").putString("saver_mode", "pet").apply()
                        SuhairBehavior.request(st.suhairPet, SuhairIntent.GREET,
                            message = "Ready to help with your work", source = "MANZANILLA")
                        blip()
                    }
                    5 -> { st.screen = Screen.PET; blip() }
                    0 -> { st.screen = Screen.SETTINGS; blip() }
                }
                return true
            }
            Screen.PET -> {
                when (d) {
                    0 -> goHome()
                    1 -> {
                        st.petTrackingEnabled = !st.petTrackingEnabled
                        if (!st.petTrackingEnabled) {
                            faceTracker.stop(); faceTrackerRunning = false
                            st.petFacePresent = false
                        }
                        blip()
                    }
                    5 -> if (st.selectedPet == "suhair") cycleSuhairMood() else cyclePetMood()
                }
                return true
            }
            Screen.PET_CALIBRATE -> {
                when (d) {
                    1, 2, 3 -> calibratePetGesture(d)
                    0 -> { st.screen = Screen.PET; blip() }
                }
                return true
            }
            Screen.PET_GAZE_CALIBRATE -> {
                when (d) {
                    1 -> calibratePetGaze("device")
                    2 -> calibratePetGaze("laptop")
                    0 -> { st.screen = Screen.PET; blip() }
                }
                return true
            }
            Screen.THEMES -> {
                when {
                    role == Fn.MINUS -> {
                        st.themePreview = (st.themePreview - 1 + Themes.all.size) % Themes.all.size
                        blip()
                    }
                    role == Fn.PLUS -> {
                        st.themePreview = (st.themePreview + 1) % Themes.all.size
                        blip()
                    }
                    d == 5 -> pickTheme(st.themePreview)
                    d == 0 -> goHome()
                    d != null && d in 1..9 -> {
                        st.themePreview = (d - 1).coerceIn(0, Themes.all.lastIndex)
                        blip()
                    }
                }
                return true
            }
            Screen.DIAL -> {
                when {
                    d != null -> { st.dialBuf += d; blip() }
                    keymap.isStar(code) -> { st.dialBuf += "*"; blip() }
                    keymap.isPound(code) -> { st.dialBuf = st.dialBuf.dropLast(1); blip() }
                }
                return true
            }
            Screen.VIEWFINDER -> {
                if (keymap.isPound(code)) snapPhoto()
                if (d == 0) goHome()
                return true
            }
            Screen.STORE -> { if (d == 0) goHome() else if (d == 5) launchStore(); return true }
            Screen.STREAM -> {
                when {
                    d != null && d in 1..9 -> sdPress(d)
                    d == 0 -> goHome()
                    keymap.isStar(code) -> togglePcTheme()
                    keymap.isPound(code) -> openPcAppSwitcher()
                    role == Fn.MINUS -> {
                        val count = StreamDeckDefaults.pageCount(st.sdPagesJson)
                        st.sdPage = (st.sdPage - 1 + count) % count
                        blip()
                    }
                    role == Fn.PLUS -> {
                        val count = StreamDeckDefaults.pageCount(st.sdPagesJson)
                        st.sdPage = (st.sdPage + 1) % count
                        blip()
                    }
                }
                return true
            }
            Screen.APP_SWITCHER -> {
                when {
                    d != null && d in 1..8 -> focusPcWindow(d)
                    d == 0 || keymap.isPound(code) -> { st.screen = Screen.STREAM; blip() }
                    keymap.isStar(code) -> togglePcTheme()
                    role == Fn.MINUS -> moveAppSwitcherPage(-1)
                    role == Fn.PLUS -> moveAppSwitcherPage(1)
                }
                return true
            }
            Screen.APP_CONTROLLER -> {
                when {
                    d != null && d in 1..9 -> appControllerPress(d)
                    d == 0 -> goHome()
                    keymap.isStar(code) -> { st.screen = Screen.STREAM; blip() }
                    role == Fn.MINUS -> { st.appControllerPage = maxOf(0, st.appControllerPage - 1); blip() }
                    role == Fn.PLUS -> {
                        val count = AppProfiles.get(st.appControllerId)?.controls?.size ?: 0
                        val pageSize = if (Themes.current.name == "LIQUID GLASS") 6 else 9
                        st.appControllerPage = minOf(maxOf(0, (count - 1) / pageSize), st.appControllerPage + 1); blip()
                    }
                }
                return true
            }
            Screen.AI_WORKSPACE -> {
                when {
                    d == 1 -> startAiComposer("chatgpt")
                    d == 2 -> focusPcApp("chatgpt")
                    d != null && d in 3..9 -> aiWorkspacePress(d)
                    d == 0 -> goHome()
                    keymap.isStar(code) -> focusPcApp(st.aiWorkspaceKind)
                    role == Fn.MINUS -> { st.aiWorkspacePage = maxOf(0, st.aiWorkspacePage - 1); blip() }
                    role == Fn.PLUS -> {
                        val pages = maxOf(1, ((AppProfiles.get(st.aiWorkspaceKind)?.controls?.size ?: 1) + 8) / 9)
                        st.aiWorkspacePage = minOf(pages, st.aiWorkspacePage + 1); blip()
                    }
                }
                return true
            }
            Screen.TASKS -> { if (d == 0) goHome(); return true }
            Screen.GAMES -> {
                when {
                    d == 1 -> { st.gameSelected = 0; blip() }
                    d == 2 -> { st.gameSelected = 1; blip() }
                    d == 5 -> launchLocalGame(st.gameSelected)
                    d == 0 || keymap.isStar(code) -> navigateBack()
                    role == Fn.MINUS -> { st.gameSelected = (st.gameSelected - 1 + 2) % 2; blip() }
                    role == Fn.PLUS -> { st.gameSelected = (st.gameSelected + 1) % 2; blip() }
                }
                return true
            }
            else -> {}
        }

        // keyboard mode
        if (st.keyboardMode && role.startsWith("d") || st.keyboardMode && role in listOf("star", "pound")) {
            if (keymap.isStar(code)) { st.keyboardMode = false; toast("keys mode off"); blip(); return true }
            val key = when {
                d == 2 -> "up"; d == 4 -> "left"; d == 6 -> "right"; d == 8 -> "down"
                d == 5 -> "enter"; d == 1 -> "tab"; d == 3 -> "esc"
                d == 7 -> "backspace"; d == 9 -> "space"; d == 0 -> "0"
                keymap.isPound(code) -> "hash"
                else -> null
            }
            key?.let { client.send("kbd", "key" to it); blip() }
            return true
        }

        // handoff arm
        if (handoffArmed && d != null && d in 1..9) {
            client.send("handoff", "from" to st.selected, "to" to d)
            toast("handoff ${st.selected} > $d")
            handoffArmed = false
            return true
        }
        handoffArmed = false

        exec(keymap.action(role), d)
        return true
    }

    /** the reassignable action executor — any key can trigger any of these */
    private fun exec(action: String, digit: Int?) {
        when (action) {
            Act.SLOT -> if (digit != null && digit in 1..9) {
                st.selected = digit; st.screen = Screen.AGENT
                st.nowPinned = true
                st.nowName = agentNameOf(st.slots[digit - 1].kind)
                st.nowKind = st.slots[digit - 1].kind
                client.send("select", "slot" to digit); blip()
            }
            Act.HOME -> { goHome(); blip() }
            Act.AGENTS -> { st.page = 0; st.screen = Screen.DASHBOARD; blip() }
            Act.FILES -> { refreshFiles(); st.page = 1; st.screen = Screen.DASHBOARD; blip() }
            Act.SETTINGS -> { st.screen = Screen.SETTINGS; blip() }
            Act.KBD -> { st.keyboardMode = true; toast("keys mode on"); blip() }
            Act.CAMERA -> snapPhoto()
            Act.ACCEPT -> {
                if (st.screen == Screen.DIAL) {
                    client.send("dial", "number" to st.dialBuf); toast("sent to PC clipboard")
                } else { client.send("accept"); toast("yes!"); if (st.soundAlerts) Chiptune.select() }
            }
            Act.REJECT -> { client.send("interrupt", "level" to "esc"); toast("no / stop"); blip() }
            Act.KILL -> { client.send("interrupt", "level" to "kill"); toast("kill sent") }
            Act.PTT_GLOBAL -> toggleGlobalDictation()
            Act.ALERTS -> { st.soundAlerts = !st.soundAlerts; toast(if (st.soundAlerts) "sound on" else "sound off"); if (st.soundAlerts) Chiptune.select() }
            Act.HANDOFF -> { handoffArmed = true; toast("handoff: press slot #") }
            Act.PAUSE -> client.send("pause", "slot" to st.selected)
            Act.BROADCAST -> { st.broadcast = !st.broadcast; client.send("broadcast", "on" to st.broadcast); toast(if (st.broadcast) "ALL AGENTS!" else "solo mode") }
            Act.REPEAT -> { client.send("repeat"); toast("again!"); blip() }
            Act.SCROLL_UP -> scrollOrFlip(-1)
            Act.SCROLL_DOWN -> scrollOrFlip(1)
            Act.DIAL -> { st.dialBuf = ""; st.screen = Screen.DIAL; blip() }
            Act.SAVER -> enterSaver()
            Act.STORE -> { st.screen = Screen.STORE; blip() }
            Act.THEMES -> { st.screen = Screen.THEMES; blip() }
            Act.SPAWN -> { st.screen = Screen.SPAWN_PICK; blip() }
            Act.STREAM -> { st.screen = Screen.STREAM; blip() }
            Act.TASKS -> { st.screen = Screen.TASKS; blip() }
        }
    }

    private fun scrollOrFlip(dir: Int) {
        when (st.screen) {
            Screen.DASHBOARD -> {
                st.page = 1 - st.page
                if (st.page == 1) refreshFiles()
                blip()
            }
            Screen.HOME -> { st.homePage = 1 - st.homePage; blip() }
            else -> client.send("scroll", "dir" to dir)
        }
    }

    private fun learnKey(code: Int): Boolean {
        if (keymap.digit(code) == 0) { st.screen = Screen.SETTINGS; return true }
        if (keymap.isPound(code)) { view.learnIndex++; blip(); return true }
        val fn = Fn.LEARNABLE.getOrNull(view.learnIndex) ?: run { st.screen = Screen.SETTINGS; return true }
        keymap.learn(code, fn)
        view.learnIndex++
        blip()
        if (view.learnIndex >= Fn.LEARNABLE.size) { toast("key map saved"); Chiptune.done() }
        return true
    }

    // ---- actions ------------------------------------------------------------

    private fun spawn(kind: String) {
        client.send("spawn", "slot" to st.selected, "kind" to kind)
        toast("starting ${if (kind == "claude") "CLAUDE" else "CODEX"}…")
        if (st.soundAlerts) Chiptune.select()
    }

    private fun startPtt(mode: String) {
        if (useDeviceMicrophone &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("mic permission missing"); return
        }
        pttMode = mode
        view.voiceMode = mode
        view.voiceLine = ""
        view.voiceReady = false
        view.voiceCapturing = true
        st.pttStart = System.currentTimeMillis()
        // Never make VOICE its own return destination after a repeated press.
        if (st.screen != Screen.VOICE) screenBeforeVoice = st.screen
        st.screen = Screen.VOICE
        client.send("ptt_start", "mode" to mode, "slot" to st.selected, "broadcast" to st.broadcast)
        if (useDeviceMicrophone) mic.start()
        // Android 14 briefly reveals system chrome with its microphone privacy
        // indicator on this vendor build. Re-hide only the bars; the privacy
        // indicator remains available through the OS privacy dashboard.
        ui.postDelayed({ enterImmersiveMode() }, 180L)
        ui.postDelayed({ enterImmersiveMode() }, 700L)
        if (st.soundAlerts) Chiptune.listen()
    }

    private fun stopPtt() {
        val stoppedMode = pttMode
        mic.stop()
        client.send("ptt_stop")
        pttMode = null
        view.voiceCapturing = false
        if (stoppedMode == "global" ||
            (view.voiceMode == "global" && st.screen == Screen.VOICE)) {
            globalDictationOn = false
            st.screen = if (screenBeforeVoice == Screen.VOICE) Screen.HOME else screenBeforeVoice
        } else if (stoppedMode == "agent") {
            // Keep the review screen visible while Wispr finalizes. Sending is a
            // separate, explicit action so a recognition error cannot be posted.
            composerAwaitingFinal = true
            val captureStartedAt = st.pttStart
            st.screen = Screen.VOICE
            toast("FINALIZING TRANSCRIPT…")
            ui.postDelayed({
                if (composerAwaitingFinal && st.pttStart == captureStartedAt) {
                    composerAwaitingFinal = false
                    view.voiceReady = false
                    view.voiceLine = ""
                    toast("NO TRANSCRIPT RECEIVED")
                }
            }, 10_000L)
        }
        if (st.broadcast) { st.broadcast = false; client.send("broadcast", "on" to false) }
        if (st.soundAlerts) Chiptune.select()
    }

    private fun startAiComposer(target: String) {
        @Suppress("UNUSED_VARIABLE") val compatibilitySource = target
        st.composerTarget = "chatgpt"
        composerAwaitingFinal = false
        startPtt("agent")
    }

    private fun composerTargetForCurrentScreen(): String? = when {
        st.screen == Screen.AI_WORKSPACE -> "chatgpt"
        st.screen == Screen.DYNAMIC_STATUS && st.statusCardIndex == 1 &&
            st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex") -> "chatgpt"
        else -> null
    }

    private fun submitAiComposer() {
        val text = view.voiceLine.trim()
        if (!view.voiceReady || text.isEmpty()) {
            toast("WAITING FOR TRANSCRIPT")
            return
        }
        client.send("agent_submit", "target" to st.composerTarget,
            "slot" to st.selected, "text" to text)
        view.voiceReady = false
        view.voiceCapturing = false
        view.voiceLine = ""
        st.screen = screenBeforeVoice
        toast("SENT TO ${st.composerTarget.uppercase()}")
        confirmSound()
    }

    private fun cancelAiComposer() {
        composerAwaitingFinal = false
        if (pttMode != null) {
            mic.stop()
            client.send("ptt_stop")
            pttMode = null
        }
        view.voiceReady = false
        view.voiceCapturing = false
        view.voiceLine = ""
        st.screen = screenBeforeVoice
        toast("REPLY CANCELLED")
        blip()
    }

    private fun toggleGlobalDictation() {
        globalDictationOn = !globalDictationOn
        if (globalDictationOn) startPtt("global") else { stopPtt(); toast("dictation off") }
    }

    private fun snapPhoto() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("camera permission missing"); return
        }
        toast("snap!")
        cam.snap({ jpeg ->
            client.send("photo", "bytes" to jpeg.size)
            client.sendBinary(CamCapture.TAG_JPEG, jpeg)
            ui.post { toast("photo > PC (${jpeg.size / 1024} KB)"); if (st.soundAlerts) Chiptune.select() }
        }, { err -> ui.post { toast("camera: $err"); if (st.soundAlerts) Chiptune.error() } })
    }

    private fun ensureWallpaper() {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        if (prefs.getBoolean("wall_set2", false)) return
        Thread {
            try {
                val w = 1024; val h = 1024
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.rgb(16, 16, 24))
                val p = Paint().apply { isAntiAlias = false }
                for (i in 0 until 90) {
                    val sx = ((i * 733) % w).toFloat(); val sy = ((i * 397) % h).toFloat()
                    p.color = if (i % 4 == 0) Color.rgb(60, 188, 252) else Color.rgb(248, 248, 248)
                    p.alpha = 70 + (i * 37) % 130
                    val s = if (i % 5 == 0) 6f else 3f
                    c.drawRect(sx, sy, sx + s, sy + s, p)
                }
                val sp = Paint().apply { isFilterBitmap = false }
                c.drawBitmap(com.agentdeck.ui.Sprites.apple, null,
                    RectF(w / 2f - 160, h / 2f - 190, w / 2f + 160, h / 2f + 130), sp)
                WallpaperManager.getInstance(this).setBitmap(bmp)
                prefs.edit().putBoolean("wall_set2", true).apply()
            } catch (_: Exception) {}
        }.start()
    }

    override fun onBackPressed() { navigateBack() }
}
