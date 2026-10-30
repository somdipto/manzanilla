package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class UpdateStatusTextTest {
    private val spanish = mapOf(
        UpdateStatusText.MANUAL_MESSAGE to "Se conserva la actualización verificada. Instale la actualización oficial firmada desde {url} sin desinstalar la aplicación; los datos se conservarán.",
        "Could not check for updates ({stage}, HTTP {status}). Tap to retry." to "No se pudo buscar la actualización ({stage}, HTTP {status}). Reintente.",
        "Downloading {percent}%" to "Descargando {percent}%"
    )
    private fun text(id: String, values: Map<String, String>): String = values.entries.fold(spanish[id] ?: "unavailable") {
        result, entry -> result.replace("{${entry.key}}", entry.value)
    }

    @Test fun manualFallbackRetainsOfficialRouteAndDataInstructionsInSelectedLocale() {
        val shown = UpdateStatusText.render("confirm:${UpdateStatusText.MANUAL_INSTALL}", ::text)
        assertTrue(shown.startsWith("Se conserva"))
        assertTrue(shown.contains(UpdateStatusText.OFFICIAL_URL))
        assertTrue(shown.contains("sin desinstalar"))
        assertTrue(shown.contains("datos se conservarán"))
        assertFalse(shown.contains("{url}"))
    }

    @Test fun staffSeesActualFailureCodeAndDownloadProgress() {
        assertEquals("No se pudo buscar la actualización (metadata, HTTP 403). Reintente.",
            UpdateStatusText.render("failed:Could not check for updates (metadata, HTTP 403). Tap to retry.", ::text))
        assertEquals("Descargando 37%", UpdateStatusText.render("progress:37", ::text))
    }
}
