package com.agentdeck

/** Shared semantic model for the ORBIT cross-media bar. */
data class XmbItem(val id: String, val label: String, val icon: String)
data class XmbCategory(
    val id: String,
    val label: String,
    val icon: String,
    val items: List<XmbItem>
)

object XmbMenu {
    val categories = listOf(
        XmbCategory("ai", "AI", "agents", listOf(
            XmbItem("chatgpt", "ChatGPT", "chatgpt"),
            XmbItem("codex", "Codex", "codex"),
            XmbItem("clor", "Claude", "claude"),
            XmbItem("tasks", "Live task status", "tasks")
        )),
        XmbCategory("apps", "Apps", "chrome", listOf(
            XmbItem("chrome", "Chrome", "chrome"),
            XmbItem("comet", "Comet", "comet"),
            XmbItem("spotify", "Spotify", "spotify"),
            XmbItem("files", "Files", "files")
        )),
        XmbCategory("control", "Control", "stream", listOf(
            XmbItem("stream", "Stream Deck", "stream"),
            XmbItem("wispr", "Wispr Flow", "wispr"),
            XmbItem("camera", "Camera", "camera")
        )),
        XmbCategory("games", "Games", "games", listOf(
            XmbItem("games", "Games library", "games")
        )),
        XmbCategory("companion", "Companion", "pet", listOf(
            XmbItem("pet", "Pets", "pet"),
            XmbItem("saver", "Screensaver", "themes")
        )),
        XmbCategory("settings", "Settings", "settings", listOf(
            XmbItem("settings", "System settings", "settings"),
            XmbItem("themes", "Themes", "themes"),
            XmbItem("store", "Play Store", "store")
        ))
    )

    fun category(index: Int): XmbCategory = categories[index.coerceIn(categories.indices)]
}
