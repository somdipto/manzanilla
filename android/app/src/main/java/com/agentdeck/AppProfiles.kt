package com.agentdeck

/** App-specific Stream Deck controls shown after holding a numbered shortcut. */
data class AppControl(val id: String, val label: String)
data class AppProfile(val id: String, val title: String, val controls: List<AppControl>)

object AppProfiles {
    private fun controls(vararg pairs: Pair<String, String>) =
        pairs.map { AppControl(it.first, it.second) }

    private val profiles = mapOf(
        "whatsapp" to AppProfile("whatsapp", "WHATSAPP", controls(
            "new" to "NEW CHAT", "search" to "SEARCH", "next" to "NEXT CHAT",
            "previous" to "PREV CHAT", "group" to "NEW GROUP", "archive" to "ARCHIVE",
            "mute_chat" to "MUTE CHAT", "copy" to "COPY", "paste" to "PASTE"
        )),
        "chatgpt" to AppProfile("chatgpt", "MICRO CHATGPT", controls(
            "new" to "NEW CHAT", "talk" to "PUSH TO TALK", "send" to "SEND",
            "stop" to "STOP", "steer" to "STEER", "accept" to "ACCEPT",
            "reject" to "REJECT", "search" to "SEARCH", "next" to "NEXT CHAT",
            "effort_low" to "EFFORT LOW", "effort_medium" to "EFFORT MED",
            "effort_high" to "EFFORT HIGH", "model" to "SWITCH MODEL",
            "chat_mode" to "CHAT MODE", "work_mode" to "WORK MODE",
            "codex_mode" to "CODEX MODE", "copy" to "COPY", "paste" to "PASTE"
        )),
        "codex" to AppProfile("codex", "MICRO CODEX", controls(
            "new" to "NEW TASK", "talk" to "PUSH TO TALK", "send" to "SEND",
            "stop" to "STOP", "steer" to "STEER", "accept" to "APPROVE",
            "reject" to "REJECT", "next" to "NEXT TASK", "previous" to "PREV TASK",
            "effort_low" to "EFFORT LOW", "effort_medium" to "EFFORT MED",
            "effort_high" to "EFFORT HIGH", "model" to "SWITCH MODEL",
            "review_pr" to "REVIEW PR", "debug" to "DEBUG",
            "refactor" to "REFACTOR", "copy" to "COPY", "paste" to "PASTE"
        )),
        "claude" to AppProfile("claude", "MICRO CLAUDE", controls(
            "new" to "NEW CHAT", "search" to "SEARCH", "prompt" to "PROMPT",
            "talk" to "TALK", "send" to "SEND", "stop" to "STOP",
            "copy" to "COPY", "paste" to "PASTE", "next" to "NEXT CHAT"
        )),
        "chrome" to browser("chrome", "CHROME CONTROL"),
        "comet" to browser("comet", "COMET CONTROL"),
        "spotify" to AppProfile("spotify", "SPOTIFY CONTROL", controls(
            "play" to "PLAY / PAUSE", "next" to "NEXT", "prev" to "PREVIOUS",
            "volup" to "VOLUME +", "voldown" to "VOLUME -", "mute" to "MUTE",
            "search" to "SEARCH", "like" to "LIKE", "focus" to "OPEN APP"
        )),
        "notion" to AppProfile("notion", "NOTION CONTROL", controls(
            "new" to "NEW PAGE", "search" to "QUICK FIND", "back" to "BACK",
            "forward" to "FORWARD", "copy" to "COPY", "paste" to "PASTE",
            "undo" to "UNDO", "redo" to "REDO", "focus" to "OPEN APP"
        )),
        "files" to AppProfile("files", "FILES CONTROL", controls(
            "new" to "NEW WINDOW", "address" to "LOCATION", "back" to "BACK",
            "forward" to "FORWARD", "up" to "UP FOLDER", "search" to "SEARCH",
            "copy" to "COPY", "paste" to "PASTE", "refresh" to "REFRESH"
        )),
        "discord" to AppProfile("discord", "DISCORD CONTROL", controls(
            "quick" to "QUICK SWITCH", "next" to "NEXT CHANNEL", "previous" to "PREV CHANNEL",
            "mute_mic" to "MUTE MIC", "deafen" to "DEAFEN", "search" to "SEARCH",
            "copy" to "COPY", "paste" to "PASTE", "focus" to "OPEN APP"
        )),
        "slack" to AppProfile("slack", "SLACK CONTROL", controls(
            "quick" to "QUICK SWITCH", "search" to "SEARCH", "new" to "NEW MESSAGE",
            "next" to "NEXT CHANNEL", "previous" to "PREV CHANNEL", "threads" to "THREADS",
            "copy" to "COPY", "paste" to "PASTE", "focus" to "OPEN APP"
        )),
        "zoom" to AppProfile("zoom", "ZOOM CONTROL", controls(
            "mute_mic" to "MUTE MIC", "video" to "VIDEO", "share" to "SHARE",
            "chat" to "CHAT", "people" to "PEOPLE", "gallery" to "GALLERY",
            "raise_hand" to "RAISE HAND", "focus" to "OPEN APP", "escape" to "CLOSE PANEL"
        ))
    )

    private fun browser(id: String, title: String) = AppProfile(id, title, controls(
        // Page 1: the user's most-used web destinations.
        "new_tab" to "NEW TAB", "address" to "ADDRESS", "url_meet" to "GOOGLE MEET",
        "url_outlook" to "OUTLOOK", "url_teams" to "TEAMS", "url_youtube" to "YOUTUBE",
        "url_onedrive" to "ONEDRIVE", "url_whatsapp" to "WHATSAPP WEB", "url_chatgpt" to "CHATGPT WEB",
        // Page 2: normal browser navigation.
        "url_instagram" to "INSTAGRAM", "back" to "BACK", "forward" to "FORWARD", "reload" to "RELOAD",
        "next_tab" to "NEXT TAB", "previous_tab" to "PREV TAB", "reopen_tab" to "REOPEN TAB",
        "close_tab" to "CLOSE TAB", "search" to "FIND PAGE"
    ))

    fun get(id: String) = profiles[id]

    fun infer(label: String, value: String = "", match: String = ""): AppProfile? {
        val s = "$label $value $match".lowercase()
        val id = when {
            "whatsapp" in s -> "whatsapp"
            "chatgpt" in s -> "chatgpt"
            "codex" in s -> "codex"
            "claude" in s -> "claude"
            "comet" in s || "perplexity" in s -> "comet"
            "chrome" in s -> "chrome"
            "spotify" in s -> "spotify"
            "notion" in s -> "notion"
            "explorer" in s || "files" in s -> "files"
            "discord" in s -> "discord"
            "slack" in s -> "slack"
            "zoom" in s -> "zoom"
            else -> return null
        }
        return profiles[id]
    }
}
