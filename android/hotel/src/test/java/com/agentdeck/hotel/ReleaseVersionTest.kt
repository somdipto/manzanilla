package com.agentdeck.hotel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun compiledProviderConfigurationNeverContainsCredentials() {
        // CI supplies synthetic env/property values to catch accidental injection.
        // Inspect generated fields, not source text or inlined constants.
        for (field in BuildConfig::class.java.fields) {
            if (field.name.endsWith("_API_KEY") || field.name == "FISH_VOICE_ID") {
                assertTrue("Provider configuration must be user-supplied: ${field.name}",
                    field.type == String::class.java && (field.get(null) as String).isEmpty())
            }
        }
    }

    @Test fun explicitCodeAndSemanticVersionMustBothAdvance() {
        assertTrue(ReleaseVersion.offer("v17.0.5", "17.0.4", 34, 35))
        assertFalse(ReleaseVersion.offer("v17.0.5", "17.0.4", 35, 35))
        assertFalse(ReleaseVersion.offer("v17.0.5", "17.0.4", 36, 35))
        assertFalse(ReleaseVersion.offer("v17.0.4", "17.0.4", 34, 35))
        assertFalse(ReleaseVersion.offer("v17.0.3", "17.0.4", 34, 35))
        assertFalse(ReleaseVersion.offer("v9999", "17.0.4", 34, 35))
    }

    @Test fun comparisonsUseNumericComponentsNotLexicalOrderOrRunCounters() {
        assertTrue(ReleaseVersion.newer("17.0.10", "17.0.9"))
        assertFalse(ReleaseVersion.newer("17.0.9", "17.0.10"))
        assertTrue(ReleaseVersion.newer("18.0.0", "17.99.99"))
        assertFalse(ReleaseVersion.newer("3000", "17.0.4"))
        assertFalse(ReleaseVersion.newer("17.00.5", "17.0.4"))
        assertFalse(ReleaseVersion.newer("17.0.5garbage", "17.0.4"))
        assertFalse(ReleaseVersion.newer("999999999999999999999.0.0", "17.0.4"))
    }

    @Test fun prereleasePrecedenceFollowsSemverAndBuildMetadataDoesNotAdvance() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue("$b must follow $a", ReleaseVersion.newer(b, a))
            assertFalse(ReleaseVersion.newer(a, b))
        }
        assertFalse(ReleaseVersion.newer("1.0.0+build.2", "1.0.0+build.1"))
        assertFalse(ReleaseVersion.valid("1.0.0-01"))
    }
}
