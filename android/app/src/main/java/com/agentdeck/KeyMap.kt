package com.agentdeck

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File

/**
 * Two layers:
 *  1. raw Android keycode -> ROLE ("d0".."d9","star","pound", Fn.*) — the
 *     physical identity of the key, learned once via Key-Learn.
 *  2. ROLE -> ACTION — fully user-remappable, synced from the PC console
 *     ("break the connection between the keys": any key can do anything).
 */
object Fn {
    const val CALL = "call"; const val END = "end"
    const val MINUS = "minus"; const val PLUS = "plus"
    const val MUTE = "mute"; const val SPEAKER = "speaker"
    const val TRANSFER = "transfer"; const val HOLD = "hold"
    const val CONF = "conf"; const val RKEY = "r"
    val LEARNABLE = listOf(CALL, END, MINUS, PLUS, MUTE, SPEAKER, TRANSFER, HOLD, CONF, RKEY)
    val LABELS = mapOf(
        CALL to "GREEN CALL key", END to "RED END key",
        MINUS to "MINUS ( - ) key", PLUS to "PLUS ( + ) key",
        MUTE to "MIC-MUTE key", SPEAKER to "SPEAKER key",
        TRANSFER to "TRANSFER key", HOLD to "HOLD ( II ) key",
        CONF to "CONFERENCE key", RKEY to "R key"
    )
}

/** the action catalog — everything a key can be assigned to do */
object Act {
    const val SLOT = "slot"                // digit selects that agent slot
    const val HOME = "home"; const val AGENTS = "agents"; const val FILES = "files"
    const val SETTINGS = "settings"; const val KBD = "kbd_mode"; const val CAMERA = "camera"
    const val ACCEPT = "accept"; const val REJECT = "reject"; const val KILL = "kill"
    const val PTT_GLOBAL = "ptt_global"; const val ALERTS = "alerts"
    const val HANDOFF = "handoff"; const val PAUSE = "pause"; const val BROADCAST = "broadcast"
    const val REPEAT = "repeat"; const val SCROLL_UP = "scroll_up"; const val SCROLL_DOWN = "scroll_down"
    const val DIAL = "dial"; const val SAVER = "saver"; const val STORE = "store"
    const val SPAWN = "spawn_menu"; const val THEMES = "themes"; const val NONE = "none"
    const val STREAM = "stream_deck"; const val TASKS = "tasks"
}

class KeyMap(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "keymap.json")
    private val actFile get() = File(ctx.filesDir, "actions.json")
    private val codeToRole = HashMap<Int, String>()
    private val roleToAct = HashMap<String, String>()

    init {
        codeToRole[KeyEvent.KEYCODE_CALL] = Fn.CALL
        codeToRole[KeyEvent.KEYCODE_ENDCALL] = Fn.END
        codeToRole[KeyEvent.KEYCODE_VOLUME_DOWN] = Fn.MINUS
        codeToRole[KeyEvent.KEYCODE_VOLUME_UP] = Fn.PLUS
        codeToRole[KeyEvent.KEYCODE_MUTE] = Fn.MUTE
        roleToAct.putAll(DEFAULT_ACTIONS)
        load()
        // PHONE and ENDCALL are stable Linux/Android identities on the ADOC
        // handset. An early calibration accidentally saved both as END; never
        // allow a stale learned file to double-book these safety-critical keys.
        codeToRole[KeyEvent.KEYCODE_CALL] = Fn.CALL
        codeToRole[KeyEvent.KEYCODE_ENDCALL] = Fn.END
    }

    fun digit(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    fun isStar(code: Int) = code == KeyEvent.KEYCODE_STAR || code == KeyEvent.KEYCODE_NUMPAD_MULTIPLY
    fun isPound(code: Int) = code == KeyEvent.KEYCODE_POUND

    /** physical role of a keycode */
    fun role(code: Int): String? {
        digit(code)?.let { return "d$it" }
        if (isStar(code)) return "star"
        if (isPound(code)) return "pound"
        return codeToRole[code]
    }

    fun fn(code: Int): String? = codeToRole[code]

    /** the assigned action for a role */
    fun action(role: String): String = roleToAct[role] ?: Act.NONE

    fun learn(code: Int, fnRole: String) { codeToRole[code] = fnRole; save() }

    /** apply a full role->action layout (from the PC console) */
    fun applyActions(o: JSONObject) {
        for (k in o.keys()) roleToAct[k] = o.getString(k)
        val out = JSONObject(); for ((k, v) in roleToAct) out.put(k, v)
        actFile.writeText(out.toString())
    }

    private fun load() {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) codeToRole[k.toInt()] = o.getString(k)
            }
            if (actFile.exists()) {
                val o = JSONObject(actFile.readText())
                for (k in o.keys()) roleToAct[k] = o.getString(k)
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val o = JSONObject(); for ((k, v) in codeToRole) o.put(k.toString(), v)
        file.writeText(o.toString())
    }

    companion object {
        val DEFAULT_ACTIONS = mapOf(
            "d1" to Act.SLOT, "d2" to Act.SLOT, "d3" to Act.SLOT,
            "d4" to Act.SLOT, "d5" to Act.SLOT, "d6" to Act.SLOT,
            "d7" to Act.SLOT, "d8" to Act.SLOT, "d9" to Act.SLOT,
            "d0" to Act.HOME,
            "star" to Act.KBD, "pound" to Act.CAMERA,
            Fn.CALL to Act.ACCEPT,        // tap action; hold is always push-to-talk
            Fn.END to Act.REJECT,         // tap; double = ctrl-c, hold = kill (fixed)
            Fn.MINUS to Act.SCROLL_UP, Fn.PLUS to Act.SCROLL_DOWN,
            Fn.MUTE to Act.PTT_GLOBAL, Fn.SPEAKER to Act.ALERTS,
            Fn.TRANSFER to Act.HANDOFF, Fn.HOLD to Act.PAUSE,
            Fn.CONF to Act.BROADCAST, Fn.RKEY to Act.REPEAT
        )
    }
}
