package com.agentdeck.pet

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Semantic requests accepted by the Suhair companion. No request contains sprite coordinates. */
enum class SuhairIntent {
    IDLE,
    GREET,
    CELEBRATE,
    EXPLAIN,
    WARN,
    WAIT,
    WORK,
    REVIEW,
    FAIL,
    MOVE_LEFT,
    MOVE_RIGHT,
    SHOW_NOTIFICATION;

    companion object {
        /** Strict bridge allow-list. Unknown or instruction-like strings are rejected. */
        fun fromWire(value: String): SuhairIntent? = when (value.trim().lowercase()) {
            "idle" -> IDLE
            "greet", "wave" -> GREET
            "celebrate", "success" -> CELEBRATE
            "explain" -> EXPLAIN
            "warn", "warning" -> WARN
            "wait", "waiting" -> WAIT
            "work", "working", "progress" -> WORK
            "review", "needs-input", "needs_input" -> REVIEW
            "fail", "failed", "error" -> FAIL
            "move-left", "move_left" -> MOVE_LEFT
            "move-right", "move_right" -> MOVE_RIGHT
            "show-notification", "show_notification", "notification" -> SHOW_NOTIFICATION
            else -> null
        }
    }
}

enum class SuhairRegion { LEFT, CENTER, RIGHT }

/** Fixed, locally bundled rooms. Values from the bridge are parsed through this allow-list. */
enum class SuhairScene {
    PARK,
    OFFICE,
    STUDIO,
    LAB,
    ARCHIVE,
    COMMAND;

    companion object {
        fun fromWire(value: String): SuhairScene? = when (value.trim().lowercase()) {
            "park", "outside" -> PARK
            "office" -> OFFICE
            "studio", "work-studio", "work_studio" -> STUDIO
            "lab", "laboratory" -> LAB
            "archive", "files", "file-room", "file_room", "library" -> ARCHIVE
            "command", "command-room", "command_room", "screen" -> COMMAND
            else -> null
        }
    }
}

enum class SuhairLoopMode { LOOP, ONE_SHOT, ONE_SHOT_HOLD, STATIC }

data class SuhairAnimation(
    val row: Int,
    val frames: Int,
    val frameMs: Long,
    val mode: SuhairLoopMode
)

/** The approved Codex-compatible version-2 atlas contract. */
object SuhairAtlasContract {
    const val COLUMNS = 8
    const val ROWS = 11
    const val CELL_WIDTH = 192
    const val CELL_HEIGHT = 208
    const val WIDTH = COLUMNS * CELL_WIDTH
    const val HEIGHT = ROWS * CELL_HEIGHT

    val IDLE = SuhairAnimation(0, 6, 190L, SuhairLoopMode.LOOP)
    val RUN_RIGHT = SuhairAnimation(1, 8, 85L, SuhairLoopMode.LOOP)
    val RUN_LEFT = SuhairAnimation(2, 8, 85L, SuhairLoopMode.LOOP)
    val WAVE = SuhairAnimation(3, 4, 155L, SuhairLoopMode.ONE_SHOT_HOLD)
    val JUMP = SuhairAnimation(4, 5, 110L, SuhairLoopMode.ONE_SHOT_HOLD)
    val FAILED = SuhairAnimation(5, 8, 170L, SuhairLoopMode.ONE_SHOT_HOLD)
    val WAITING = SuhairAnimation(6, 6, 220L, SuhairLoopMode.LOOP)
    val WORKING = SuhairAnimation(7, 6, 145L, SuhairLoopMode.LOOP)
    val REVIEW = SuhairAnimation(8, 6, 205L, SuhairLoopMode.LOOP)

    /** One calm decision beat before studio work begins; it never oscillates back automatically. */
    const val STUDIO_THINKING_MS = 5_000L
    const val THINKING_HOLD_FRAME = 1

    fun animationFor(intent: SuhairIntent): SuhairAnimation = when (intent) {
        SuhairIntent.MOVE_LEFT -> RUN_LEFT
        SuhairIntent.MOVE_RIGHT -> RUN_RIGHT
        SuhairIntent.GREET, SuhairIntent.SHOW_NOTIFICATION -> WAVE
        SuhairIntent.CELEBRATE -> JUMP
        SuhairIntent.FAIL -> FAILED
        SuhairIntent.WAIT -> WAITING
        SuhairIntent.WORK -> WORKING
        SuhairIntent.REVIEW, SuhairIntent.EXPLAIN, SuhairIntent.WARN -> REVIEW
        SuhairIntent.IDLE -> IDLE
    }

    fun gazeCell(direction: Int): Pair<Int, Int> {
        val normalized = ((direction % 16) + 16) % 16
        return (9 + normalized / 8) to (normalized % 8)
    }

    fun frameFor(animation: SuhairAnimation, elapsedMs: Long): Int {
        val raw = (elapsedMs.coerceAtLeast(0L) / animation.frameMs).toInt()
        return when (animation.mode) {
            SuhairLoopMode.LOOP -> raw % animation.frames
            SuhairLoopMode.ONE_SHOT, SuhairLoopMode.ONE_SHOT_HOLD -> raw.coerceAtMost(animation.frames - 1)
            SuhairLoopMode.STATIC -> 0
        }
    }

    /**
     * Studio work is a one-way sequence: hold the hand-on-chin review pose, then type forever.
     * Other rooms keep their ordinary semantic animation so file/archive activity is immediate.
     */
    fun cellForState(
        intent: SuhairIntent,
        scene: SuhairScene,
        elapsedMs: Long
    ): Pair<Int, Int> {
        if (intent == SuhairIntent.WORK && scene == SuhairScene.STUDIO) {
            if (elapsedMs < STUDIO_THINKING_MS) return REVIEW.row to THINKING_HOLD_FRAME
            val typingElapsed = elapsedMs - STUDIO_THINKING_MS
            return WORKING.row to frameFor(WORKING, typingElapsed)
        }
        val animation = animationFor(intent)
        return animation.row to frameFor(animation, elapsedMs)
    }

    /** 000° is up, then directions proceed clockwise in 22.5° steps. */
    fun directionForTarget(x: Float, y: Float, deadZone: Float = 0.16f): Int? {
        if (hypot(x.toDouble(), y.toDouble()) < deadZone) return null
        val degrees = (atan2(x.toDouble(), -y.toDouble()) * 180.0 / PI + 360.0) % 360.0
        return (degrees / 22.5).roundToInt() % 16
    }
}

data class SuhairPetRuntime(
    var intent: SuhairIntent = SuhairIntent.IDLE,
    var settleIntent: SuhairIntent = SuhairIntent.IDLE,
    var startedAt: Long = System.currentTimeMillis(),
    var expiresAt: Long = Long.MAX_VALUE,
    var region: SuhairRegion = SuhairRegion.CENTER,
    var originRegion: SuhairRegion = SuhairRegion.CENTER,
    var targetRegion: SuhairRegion = SuhairRegion.CENTER,
    var message: String = "",
    var source: String = "",
    var progress: Float = -1f,
    var scene: SuhairScene = SuhairScene.PARK,
    var originScene: SuhairScene = SuhairScene.PARK,
    var targetScene: SuhairScene = SuhairScene.PARK,
    var sceneTransitionActive: Boolean = false,
    var regionTransitionActive: Boolean = false,
    var pendingMessage: String = "",
    var pendingSource: String = "",
    var pendingProgress: Float = -1f,
    var lastRoamAt: Long = System.currentTimeMillis(),
    var roamStep: Int = 0
)

/**
 * Deterministic semantic state machine. The renderer consumes this state but never interprets
 * notification text, executes commands, or lets external text address arbitrary atlas cells.
 */
object SuhairBehavior {
    private const val MOVE_MS = 950L

    fun safeExternalText(value: String, maxChars: Int = 96): String = value
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(maxChars)

    fun request(
        state: SuhairPetRuntime,
        intent: SuhairIntent,
        now: Long = System.currentTimeMillis(),
        message: String = "",
        source: String = "",
        progress: Float? = null,
        region: SuhairRegion? = null
    ) {
        val previousStable = when (state.intent) {
            SuhairIntent.IDLE, SuhairIntent.WAIT, SuhairIntent.WORK, SuhairIntent.REVIEW -> state.intent
            else -> state.settleIntent
        }
        state.intent = intent
        state.startedAt = now
        state.message = safeExternalText(message)
        state.source = safeExternalText(source, 24)
        state.progress = progress?.coerceIn(0f, 1f) ?: -1f
        state.settleIntent = when (intent) {
            SuhairIntent.MOVE_LEFT, SuhairIntent.MOVE_RIGHT -> previousStable
            SuhairIntent.GREET, SuhairIntent.CELEBRATE, SuhairIntent.EXPLAIN,
            SuhairIntent.WARN, SuhairIntent.FAIL, SuhairIntent.SHOW_NOTIFICATION -> SuhairIntent.IDLE
            else -> intent
        }
        state.expiresAt = when (intent) {
            SuhairIntent.MOVE_LEFT, SuhairIntent.MOVE_RIGHT -> now + MOVE_MS
            SuhairIntent.GREET -> now + 1_800L
            SuhairIntent.CELEBRATE -> now + 2_200L
            SuhairIntent.EXPLAIN -> now + 4_500L
            SuhairIntent.WARN -> now + 5_000L
            SuhairIntent.FAIL -> now + 5_500L
            SuhairIntent.SHOW_NOTIFICATION -> now + 7_500L
            SuhairIntent.IDLE, SuhairIntent.WAIT, SuhairIntent.WORK, SuhairIntent.REVIEW -> Long.MAX_VALUE
        }

        when (intent) {
            SuhairIntent.MOVE_LEFT -> beginMove(state, region ?: state.region.left())
            SuhairIntent.MOVE_RIGHT -> beginMove(state, region ?: state.region.right())
            else -> region?.let {
                state.region = it
                state.originRegion = it
                state.targetRegion = it
            }
        }
    }

    /**
     * Changes rooms without interpreting the bubble text. While the two cached room bitmaps slide,
     * the avatar uses the approved running row. The requested semantic intent starts only after the
     * walk completes, so animation timing and speech remain deterministic.
     */
    fun requestInScene(
        state: SuhairPetRuntime,
        intent: SuhairIntent,
        scene: SuhairScene,
        now: Long = System.currentTimeMillis(),
        message: String = "",
        source: String = "",
        progress: Float? = null,
        region: SuhairRegion? = null
    ) {
        if (!state.sceneTransitionActive && scene == state.scene) {
            if (region != null && region != state.region) {
                state.regionTransitionActive = true
                state.pendingMessage = safeExternalText(message)
                state.pendingSource = safeExternalText(source, 24)
                state.pendingProgress = progress?.coerceIn(0f, 1f) ?: -1f
                val runIntent = if (region.ordinal > state.region.ordinal)
                    SuhairIntent.MOVE_RIGHT else SuhairIntent.MOVE_LEFT
                request(state, runIntent, now, region = region)
                state.settleIntent = intent
            } else {
                request(state, intent, now, message, source, progress, region)
            }
            return
        }

        val from = if (state.sceneTransitionActive) state.targetScene else state.scene
        state.scene = from
        state.originScene = from
        state.targetScene = scene
        state.sceneTransitionActive = true
        state.regionTransitionActive = false
        state.pendingMessage = safeExternalText(message)
        state.pendingSource = safeExternalText(source, 24)
        state.pendingProgress = progress?.coerceIn(0f, 1f) ?: -1f
        val runIntent = if (scene.ordinal >= from.ordinal) SuhairIntent.MOVE_RIGHT else SuhairIntent.MOVE_LEFT
        request(state, runIntent, now, region = region ?: state.region)
        state.settleIntent = intent
    }

    fun advance(state: SuhairPetRuntime, now: Long = System.currentTimeMillis()) {
        if (now < state.expiresAt) return
        if (state.intent == SuhairIntent.MOVE_LEFT || state.intent == SuhairIntent.MOVE_RIGHT) {
            state.region = state.targetRegion
            state.originRegion = state.targetRegion
            if (state.sceneTransitionActive) {
                val nextIntent = state.settleIntent
                val nextMessage = state.pendingMessage
                val nextSource = state.pendingSource
                val nextProgress = state.pendingProgress.takeIf { it >= 0f }
                state.scene = state.targetScene
                state.originScene = state.targetScene
                state.sceneTransitionActive = false
                state.pendingMessage = ""
                state.pendingSource = ""
                state.pendingProgress = -1f
                request(state, nextIntent, now, nextMessage, nextSource, nextProgress, state.region)
                return
            }
            if (state.regionTransitionActive) {
                val nextIntent = state.settleIntent
                val nextMessage = state.pendingMessage
                val nextSource = state.pendingSource
                val nextProgress = state.pendingProgress.takeIf { it >= 0f }
                state.regionTransitionActive = false
                state.pendingMessage = ""
                state.pendingSource = ""
                state.pendingProgress = -1f
                request(state, nextIntent, now, nextMessage, nextSource, nextProgress, state.region)
                return
            }
        }
        state.intent = state.settleIntent
        state.startedAt = now
        state.expiresAt = Long.MAX_VALUE
        if (state.intent == SuhairIntent.IDLE) {
            state.message = ""
            state.source = ""
            state.progress = -1f
        }
    }

    /** Calm, deterministic roaming. It is disabled while a face or meaningful task is active. */
    fun maybeRoam(state: SuhairPetRuntime, now: Long, facePresent: Boolean): Boolean {
        if (facePresent || state.intent != SuhairIntent.IDLE || now - state.lastRoamAt < 8_500L) return false
        val destinations = arrayOf(SuhairRegion.LEFT, SuhairRegion.CENTER, SuhairRegion.RIGHT, SuhairRegion.CENTER)
        val destination = destinations[state.roamStep % destinations.size]
        state.roamStep += 1
        state.lastRoamAt = now
        if (destination == state.region) return false
        request(
            state,
            if (destination.ordinal < state.region.ordinal) SuhairIntent.MOVE_LEFT else SuhairIntent.MOVE_RIGHT,
            now,
            region = destination
        )
        return true
    }

    fun movementProgress(state: SuhairPetRuntime, now: Long): Float {
        if (state.intent != SuhairIntent.MOVE_LEFT && state.intent != SuhairIntent.MOVE_RIGHT) return 1f
        return ((now - state.startedAt).toFloat() / MOVE_MS).coerceIn(0f, 1f)
    }

    /** Trusted semantic mapping; raw notification/bubble content is deliberately not inspected. */
    fun defaultSceneFor(intent: SuhairIntent, current: SuhairScene): SuhairScene = when (intent) {
        SuhairIntent.IDLE, SuhairIntent.GREET, SuhairIntent.CELEBRATE, SuhairIntent.WAIT -> SuhairScene.PARK
        SuhairIntent.WORK -> SuhairScene.STUDIO
        SuhairIntent.REVIEW, SuhairIntent.EXPLAIN -> SuhairScene.OFFICE
        SuhairIntent.WARN, SuhairIntent.FAIL -> SuhairScene.LAB
        SuhairIntent.SHOW_NOTIFICATION -> SuhairScene.COMMAND
        SuhairIntent.MOVE_LEFT, SuhairIntent.MOVE_RIGHT -> current
    }

    /** Foreground applications may select a dedicated safe, bundled room. */
    fun sceneForContext(kind: String, intent: SuhairIntent, current: SuhairScene): SuhairScene =
        when (kind.trim().lowercase()) {
            "files", "explorer", "file-explorer" -> SuhairScene.ARCHIVE
            "chatgpt", "codex" -> if (intent in setOf(
                SuhairIntent.WORK, SuhairIntent.WAIT, SuhairIntent.REVIEW,
                SuhairIntent.EXPLAIN)) SuhairScene.COMMAND else defaultSceneFor(intent, current)
            else -> defaultSceneFor(intent, current)
        }

    /**
     * Converts trusted bridge metadata into a companion state. A generic browser marked "active"
     * is not work by itself, while ChatGPT/Codex/Claude/Cowork activity is. A real background
     * project state may take precedence when the foreground window is an unrelated application.
     */
    fun intentForContext(kind: String, state: String, backgroundProjectState: String? = null): SuhairIntent {
        val normalizedKind = kind.trim().lowercase()
        val normalizedState = state.trim().lowercase()
        val aiWorkspace = normalizedKind in setOf("chatgpt", "codex", "claude", "cowork")
        val projectState = backgroundProjectState?.trim()?.lowercase().orEmpty()
        val effectiveState = if (!aiWorkspace && projectState in setOf(
                "working", "running", "thinking", "active", "waiting", "paused",
                "needs_input", "needs-input", "review")) projectState else normalizedState
        return when (effectiveState) {
            "working", "running", "thinking" -> SuhairIntent.WORK
            "active" -> if (aiWorkspace || projectState.isNotBlank()) SuhairIntent.WORK else SuhairIntent.IDLE
            "waiting", "paused" -> SuhairIntent.WAIT
            "needs_input", "needs-input", "review" -> SuhairIntent.REVIEW
            "done", "completed", "success" -> SuhairIntent.CELEBRATE
            "error", "failed", "failure" -> SuhairIntent.FAIL
            else -> SuhairIntent.IDLE
        }
    }

    private fun beginMove(state: SuhairPetRuntime, destination: SuhairRegion) {
        state.originRegion = state.region
        state.targetRegion = destination
        state.message = ""
        state.source = ""
        state.progress = -1f
    }

    private fun SuhairRegion.left(): SuhairRegion = when (this) {
        SuhairRegion.LEFT -> SuhairRegion.LEFT
        SuhairRegion.CENTER -> SuhairRegion.LEFT
        SuhairRegion.RIGHT -> SuhairRegion.CENTER
    }

    private fun SuhairRegion.right(): SuhairRegion = when (this) {
        SuhairRegion.LEFT -> SuhairRegion.CENTER
        SuhairRegion.CENTER -> SuhairRegion.RIGHT
        SuhairRegion.RIGHT -> SuhairRegion.RIGHT
    }
}
