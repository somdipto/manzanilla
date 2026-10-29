package com.agentdeck.hotel

import java.io.File
import org.json.JSONObject
import org.junit.Test

/** Produce the production app's exact English/Spanish provider bodies for the isolated probe. */
class FishPlanProviderFixture {
    @Test fun writePlan() {
        val output = System.getenv("FISH_PLAN_OUTPUT") ?: error("Missing fixture output path")
        val payload = JSONObject()
            .put("config", FishPlan.agentConfig(FishPlan.DEFAULT_VOICE, "es", "synthetic-tool-id"))
            .put("session", FishPlan.sessionBody("synthetic-agent-id", "en", "es", ""))
        File(output).writeText(payload.toString(), Charsets.UTF_8)
    }
}
