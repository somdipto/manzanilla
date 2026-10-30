package com.agentdeck.hotel

/** Both native staff maintenance and the personal-test page consume the same updater status. */
internal object UpdateStatusText {
    const val MANUAL_INSTALL = "manual-install"
    const val OFFICIAL_URL = "https://github.com/somdipto/manzanilla/releases/latest"
    const val MANUAL_MESSAGE = "The verified update is kept. Ask staff to install the official signed update from {url} without uninstalling this app; app data stays in place."

    fun message(value: String, text: (String, Map<String, String>) -> String): String {
        if (value == MANUAL_INSTALL) return text(MANUAL_MESSAGE, mapOf("url" to OFFICIAL_URL))
        Regex("^Update (.+) is downloaded\\. It will apply when reception is idle\\.$").matchEntire(value)?.let {
            return text("Update {version} is downloaded. It will apply when reception is idle.", mapOf("version" to it.groupValues[1]))
        }
        Regex("^Could not check for updates \\(([a-z ]+), HTTP ([0-9]{3})\\)\\. Tap to retry\\.$").matchEntire(value)?.let {
            return text("Could not check for updates ({stage}, HTTP {status}). Tap to retry.",
                mapOf("stage" to it.groupValues[1], "status" to it.groupValues[2]))
        }
        return text(value, emptyMap())
    }

    fun render(status: String, text: (String, Map<String, String>) -> String): String {
        val value = status.substringAfter(':', "")
        return when (status.substringBefore(':')) {
            "checking" -> text("Checking for updates…", emptyMap())
            "current" -> text("Up to date · {version}", mapOf("version" to BuildConfig.VERSION_NAME))
            "offer" -> text("Update {version} downloaded. It will apply when this device is idle.", mapOf("version" to value))
            "installed" -> text("Updated", emptyMap())
            "progress" -> {
                val percent = value.toIntOrNull()
                when {
                    percent != null && percent >= 0 -> text("Downloading {percent}%", mapOf("percent" to value))
                    percent != null || value.isBlank() -> text("Starting download…", emptyMap())
                    value.startsWith("m") && value.drop(1).toLongOrNull() != null -> text("Downloading {size} MB", mapOf("size" to value.drop(1)))
                    else -> message(value, text)
                }
            }
            else -> message(value, text)
        }
    }
}
