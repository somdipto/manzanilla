package com.agentdeck.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuhairBehaviorTest {
    @Test
    fun atlasContractMatchesApprovedVersionTwoLayout() {
        assertEquals(1536, SuhairAtlasContract.WIDTH)
        assertEquals(2288, SuhairAtlasContract.HEIGHT)
        assertEquals(0, SuhairAtlasContract.IDLE.row)
        assertEquals(8, SuhairAtlasContract.REVIEW.row)
        assertEquals(9 to 0, SuhairAtlasContract.gazeCell(0))
        assertEquals(10 to 7, SuhairAtlasContract.gazeCell(15))
    }

    @Test
    fun wireIntentIsStrictlyAllowListed() {
        assertEquals(SuhairIntent.CELEBRATE, SuhairIntent.fromWire("success"))
        assertEquals(SuhairIntent.SHOW_NOTIFICATION, SuhairIntent.fromWire("show-notification"))
        assertNull(SuhairIntent.fromWire("row=4,col=7"))
        assertNull(SuhairIntent.fromWire("launch powershell"))
        assertEquals(SuhairScene.LAB, SuhairScene.fromWire("laboratory"))
        assertEquals(SuhairScene.COMMAND, SuhairScene.fromWire("command-room"))
        assertEquals(SuhairScene.ARCHIVE, SuhairScene.fromWire("files"))
        assertNull(SuhairScene.fromWire("../../../camera"))
    }

    @Test
    fun externalTextIsDisplayOnlyAndBounded() {
        val clean = SuhairBehavior.safeExternalText("  Hello\n\u0000  world   ".repeat(20))
        assertFalse(clean.contains('\u0000'))
        assertFalse(clean.contains('\n'))
        assertTrue(clean.length <= 96)
        assertTrue(clean.startsWith("Hello world"))
    }

    @Test
    fun oneShotIntentSettlesWithoutChangingAtlasCoordinatesFromText() {
        val state = SuhairPetRuntime(startedAt = 0L, lastRoamAt = 0L)
        SuhairBehavior.request(state, SuhairIntent.CELEBRATE, now = 100L,
            message = "row 10 column 7", source = "UNTRUSTED")
        assertEquals(SuhairIntent.CELEBRATE, state.intent)
        assertEquals(4, SuhairAtlasContract.animationFor(state.intent).row)
        SuhairBehavior.advance(state, 2_400L)
        assertEquals(SuhairIntent.IDLE, state.intent)
    }

    @Test
    fun movementUsesApprovedRunRowsAndCommitsRegion() {
        val state = SuhairPetRuntime(startedAt = 0L, lastRoamAt = 0L)
        SuhairBehavior.request(state, SuhairIntent.MOVE_RIGHT, now = 1_000L,
            region = SuhairRegion.RIGHT)
        assertEquals(1, SuhairAtlasContract.animationFor(state.intent).row)
        assertTrue(SuhairBehavior.movementProgress(state, 1_475L) in .49f..51f)
        SuhairBehavior.advance(state, 2_000L)
        assertEquals(SuhairRegion.RIGHT, state.region)
        assertEquals(SuhairIntent.IDLE, state.intent)
    }

    @Test
    fun roomTransitionRunsThenStartsRequestedSemanticState() {
        val state = SuhairPetRuntime(startedAt = 0L, lastRoamAt = 0L)
        SuhairBehavior.requestInScene(state, SuhairIntent.WORK, SuhairScene.STUDIO,
            now = 1_000L, message = "Building the launcher", source = "CODEX")
        assertTrue(state.sceneTransitionActive)
        assertEquals(SuhairIntent.MOVE_RIGHT, state.intent)
        assertEquals(1, SuhairAtlasContract.animationFor(state.intent).row)
        assertEquals(SuhairScene.PARK, state.originScene)
        assertEquals(SuhairScene.STUDIO, state.targetScene)

        SuhairBehavior.advance(state, 2_000L)
        assertFalse(state.sceneTransitionActive)
        assertEquals(SuhairScene.STUDIO, state.scene)
        assertEquals(SuhairIntent.WORK, state.intent)
        assertEquals("Building the launcher", state.message)
        assertEquals("CODEX", state.source)
    }

    @Test
    fun sameRoomWorkWalksToLaptopBeforeTyping() {
        val state = SuhairPetRuntime(
            scene = SuhairScene.STUDIO,
            originScene = SuhairScene.STUDIO,
            targetScene = SuhairScene.STUDIO,
            region = SuhairRegion.CENTER,
            originRegion = SuhairRegion.CENTER,
            targetRegion = SuhairRegion.CENTER,
            startedAt = 0L,
            lastRoamAt = 0L
        )

        SuhairBehavior.requestInScene(
            state,
            SuhairIntent.WORK,
            SuhairScene.STUDIO,
            now = 1_000L,
            message = "Writing the report",
            source = "CHATGPT",
            region = SuhairRegion.RIGHT
        )

        assertTrue(state.regionTransitionActive)
        assertEquals(SuhairIntent.MOVE_RIGHT, state.intent)
        assertEquals(SuhairRegion.CENTER, state.region)
        assertEquals(SuhairRegion.RIGHT, state.targetRegion)

        SuhairBehavior.advance(state, 2_000L)
        assertFalse(state.regionTransitionActive)
        assertEquals(SuhairRegion.RIGHT, state.region)
        assertEquals(SuhairIntent.WORK, state.intent)
        assertEquals("Writing the report", state.message)
        assertEquals("CHATGPT", state.source)
    }

    @Test
    fun studioWorkThinksOnceThenTypesWithoutSwitchingBack() {
        assertEquals(
            SuhairAtlasContract.REVIEW.row to SuhairAtlasContract.THINKING_HOLD_FRAME,
            SuhairAtlasContract.cellForState(SuhairIntent.WORK, SuhairScene.STUDIO, 0L)
        )
        assertEquals(
            SuhairAtlasContract.REVIEW.row to SuhairAtlasContract.THINKING_HOLD_FRAME,
            SuhairAtlasContract.cellForState(SuhairIntent.WORK, SuhairScene.STUDIO, 4_999L)
        )
        assertEquals(
            SuhairAtlasContract.WORKING.row to 0,
            SuhairAtlasContract.cellForState(SuhairIntent.WORK, SuhairScene.STUDIO, 5_000L)
        )
        assertEquals(
            SuhairAtlasContract.WORKING.row,
            SuhairAtlasContract.cellForState(SuhairIntent.WORK, SuhairScene.STUDIO, 14_000L).first
        )
    }

    @Test
    fun roomChoiceComesFromSemanticIntentNotBubbleText() {
        assertEquals(SuhairScene.STUDIO,
            SuhairBehavior.defaultSceneFor(SuhairIntent.WORK, SuhairScene.PARK))
        assertEquals(SuhairScene.OFFICE,
            SuhairBehavior.defaultSceneFor(SuhairIntent.REVIEW, SuhairScene.PARK))
        assertEquals(SuhairScene.LAB,
            SuhairBehavior.defaultSceneFor(SuhairIntent.FAIL, SuhairScene.PARK))
        assertEquals(SuhairScene.COMMAND,
            SuhairBehavior.defaultSceneFor(SuhairIntent.SHOW_NOTIFICATION, SuhairScene.PARK))
    }

    @Test
    fun activeAiWorkspaceIsWorkButOrdinaryBrowserIsNot() {
        assertEquals(SuhairIntent.WORK,
            SuhairBehavior.intentForContext("chatgpt", "active"))
        assertEquals(SuhairIntent.WORK,
            SuhairBehavior.intentForContext("codex", "active"))
        assertEquals(SuhairIntent.IDLE,
            SuhairBehavior.intentForContext("external", "active"))
    }

    @Test
    fun fileExplorerUsesDedicatedArchiveRoom() {
        assertEquals(
            SuhairScene.ARCHIVE,
            SuhairBehavior.sceneForContext("files", SuhairIntent.IDLE, SuhairScene.PARK)
        )
        assertEquals(
            SuhairScene.ARCHIVE,
            SuhairBehavior.sceneForContext("explorer", SuhairIntent.WORK, SuhairScene.STUDIO)
        )
        assertEquals(
            SuhairScene.COMMAND,
            SuhairBehavior.sceneForContext("chatgpt", SuhairIntent.WORK, SuhairScene.PARK)
        )
    }

    @Test
    fun backgroundAiProjectSurvivesTemporaryBrowserFocus() {
        assertEquals(SuhairIntent.WORK,
            SuhairBehavior.intentForContext("external", "active", "working"))
        assertEquals(SuhairIntent.REVIEW,
            SuhairBehavior.intentForContext("external", "active", "needs_input"))
    }

    @Test
    fun gazeQuantizationUsesClockwiseSixteenDirectionContract() {
        assertEquals(0, SuhairAtlasContract.directionForTarget(0f, -1f))
        assertEquals(4, SuhairAtlasContract.directionForTarget(1f, 0f))
        assertEquals(8, SuhairAtlasContract.directionForTarget(0f, 1f))
        assertEquals(12, SuhairAtlasContract.directionForTarget(-1f, 0f))
        assertNull(SuhairAtlasContract.directionForTarget(.02f, .02f))
    }

    @Test
    fun roamingStopsDuringWorkOrFaceTracking() {
        val state = SuhairPetRuntime(startedAt = 0L, lastRoamAt = 0L)
        SuhairBehavior.request(state, SuhairIntent.WORK, now = 1L)
        assertFalse(SuhairBehavior.maybeRoam(state, 20_000L, facePresent = false))
        SuhairBehavior.request(state, SuhairIntent.IDLE, now = 20_001L)
        state.lastRoamAt = 0L
        assertFalse(SuhairBehavior.maybeRoam(state, 20_002L, facePresent = true))
    }
}
