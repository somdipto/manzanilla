package com.agentdeck.ui

/** Exact bundled resources available to the isolated reception WebView. */
internal object ReceptionAssets {
    const val HOST = "manzania-reception.invalid"
    private val paths = setOf("/index.html", "/reception.js", "/reception.css", "/language-turns.js", "/locales.js")

    fun allows(scheme: String?, host: String?, path: String?, query: String?, method: String?): Boolean =
        scheme == "https" && host == HOST && path in paths && query == null && method == "GET"
}
