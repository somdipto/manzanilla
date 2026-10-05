package com.agentdeck

import android.graphics.Bitmap
import com.agentdeck.pet.SuhairPetRuntime
import org.json.JSONArray
import org.json.JSONObject

/** State of one agent slot (1..9), mirrored from the Bridge. */
data class Slot(
    val slot: Int,
    var kind: String = "",      // "claude" | "codex" | ""
    var state: String = "empty",// empty|working|done|needs_input|error|paused
    var title: String = "",
    var tail: String = ""       // last N chars of terminal output
)

data class AiProject(
    var task: String = "",
    var project: String = "",
    var state: String = "idle",
    var detail: String = "",
    var model: String = ""
)

data class PcAppWindow(
    var id: String = "",
    var name: String = "",
    var title: String = "",
    var exe: String = "",
    var active: Boolean = false
)

class DeckState {
    val slots = Array(9) { Slot(it + 1) }
    var selected = 1
    var connected = false
    var bridgeName = ""
    var pairingCode = ""        // non-empty while pairing
    var broadcast = false
    var keyboardMode = false
    var soundAlerts = true
    var screen = Screen.BOOT
    var bootStart = System.currentTimeMillis()
    var page = 0                        // dashboard page: 0 = agents, 1 = files
    val files = ArrayList<Pair<String, Long>>()   // received downloads (name, bytes)
    var dialBuf = ""                    // dial pad screen buffer
    var homeSel = 0                     // selected tile on HOME
    var homePage = 0                    // HOME tile page (0/1)
    var xmbCategory = 0                 // ORBIT horizontal category axis
    var xmbItem = 0                     // ORBIT vertical item axis
    var settingsCategory = 0            // ORBIT cross-axis category (0..3)
    var settingsItem = 0                // item inside the selected category
    var orbitSmoothMotion = true        // 30 FPS; false is the 20 FPS low-power cadence
    var saverMode = "mountain"          // mountain | highway | pet | spider
    var deviceVolume = 50                // Android media stream, 0..100
    var deviceBrightness = 72            // current window brightness, 1..100
    var themePreview = 0                 // highlighted card in the theme carousel
    var gameSelected = 0                 // highlighted game in the local PSP-style library
    var petTargetX = 0f                  // normalized camera gaze target (-1..1)
    var petTargetY = 0f
    var petFacePresent = false
    var petTrackingAvailable = false
    var petTrackingEnabled = true
    var petExpression = "idle"           // idle|curious|happy|waving|thinking|sleepy|error
    var selectedPet = "manni"
    var petSpeech = ""
    var petSpeechUntil = 0L
    var petGestureCount = 0
    var petGestureRaw = 0
    var petGestureConfidence = 0f
    val petCalibrated = BooleanArray(3)
    var petLookingAtDevice = false
    var petGazeConfidence = 0f
    var petGazeDeviceCalibrated = false
    var petGazeLaptopCalibrated = false
    var petWebBurstUntil = 0L
    var petInteractionStage = "idle"    // idle | greeted | choosing
    var petJumpStartedAt = 0L
    var petJumpUntil = 0L
    var petJumpIndex = 0
    /** Typed v2-atlas runtime used only by the Suhair companion. */
    val suhairPet = SuhairPetRuntime()

    // ---- v4: Now-context + activity + stream deck ----
    var homeIntroStart = 0L             // boot→home morph timer
    var nowName = ""                    // current context label ("CLAUDE", "COWORK"…)
    var nowKind = ""                    // claude|codex|cowork|external|""
    var nowTask = ""
    var nowProject = ""
    var nowState = "idle"
    var nowDetail = ""
    var nowModel = ""
    val aiRunning = HashMap<String, Boolean>()
    val aiProjects = ArrayList<AiProject>()
    var aiWorkspacePage = 0
    var aiWorkspaceKind = "chatgpt"
    var nowPinned = false               // user manually pinned the context
    val events = ArrayList<Triple<String, String, String>>() // (time, who, text)
    var sdPage = 0
    var sdPagesJson: String = StreamDeckDefaults.json // bridge may replace the built-in pages
    var appControllerId = ""
    var appControllerTitle = ""
    var appControllerPage = 0
    val pcAppWindows = ArrayList<PcAppWindow>()
    var appSwitcherPage = 0
    var pttStart = 0L                   // listening timer

    fun addEvent(time: String, who: String, text: String) {
        events.add(0, Triple(time, who, text))
        while (events.size > 16) events.removeAt(events.size - 1)
    }
    var toast = ""              // transient status line
    var toastUntil = 0L
    var pcNotificationApp = ""
    var pcNotificationTitle = ""
    var pcNotificationBody = ""       // allow-listed AI preview; display-only, never a command
    var composerTarget = "chatgpt"     // single trusted AI reply destination
    var pcNotificationUntil = 0L
    var pcNotificationCount = 0
    // Genuine Codex account windows supplied by the PC bridge. -1 means the
    // service did not publish that window; the renderer must show a dash.
    var usageAvailable = false
    var shortLimitRemaining = -1
    var shortLimitWindowMins = 0
    var weeklyLimitRemaining = -1
    var monthlyLimitRemaining = -1
    // Windows Global System Media Transport Controls snapshot.  The Bridge
    // keeps this current even when Spotify/Chrome is not the foreground app.
    var mediaActive = false
    var mediaSource = ""
    var mediaTitle = ""
    var mediaArtist = ""
    var mediaAlbum = ""
    var mediaStatus = "closed"
    var mediaArtwork: Bitmap? = null
    // Dockstation's info pane is a swipeable three-card surface: media,
    // activity, and device/bridge. Direction is retained
    // briefly so the renderer can give the cards physical horizontal motion.
    var statusCardIndex = 0
    var statusCardDirection = 1
    var statusCardLastManualAt = 0L

    fun sel(): Slot = slots[selected - 1]

    fun applySlots(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.getInt("slot")
            if (n in 1..9) {
                val s = slots[n - 1]
                s.kind = o.optString("kind", s.kind)
                s.state = o.optString("state", s.state)
                s.title = o.optString("title", s.title)
                if (o.has("tail")) s.tail = o.getString("tail")
            }
        }
    }

    fun applyTail(o: JSONObject) {
        val n = o.getInt("slot")
        if (n in 1..9) {
            val s = slots[n - 1]
            s.tail = (s.tail + o.getString("data")).takeLast(MAX_TAIL)
        }
    }

    companion object { const val MAX_TAIL = 6000 }
}

enum class Screen {
    BOOT, HOME, DASHBOARD, AGENT, VOICE, SETTINGS, KEYLEARN, SPAWN_PICK,
    VIEWFINDER, SAVER, DIAL, STORE, THEMES, STREAM, APP_CONTROLLER, AI_WORKSPACE,
    TASKS, VOLUME, SAVER_PICK, GAMES, PET, PET_PICK, PET_CALIBRATE, PET_GAZE_CALIBRATE,
    APP_SWITCHER, DYNAMIC_STATUS
}
