package com.coucou.android

import com.coucou.android.core.OverlayChoice
import com.coucou.android.core.OverlayPolicy
import com.coucou.android.core.WishStore
import com.coucou.android.mochi.BotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPolicyTest {
    @Test fun finishingFailingAskingAndRateLimitsWakeIt() {
        for (s in listOf(BotState.FINISHED, BotState.ERROR, BotState.QUESTION, BotState.RATELIMIT)) {
            assertTrue("$s", OverlayPolicy.shouldFlash(BotState.WORKING, s))
        }
    }

    @Test fun ordinaryWorkDoesNotDisturb() {
        for (s in listOf(BotState.IDLE, BotState.WORKING, BotState.THINKING, BotState.SEARCHING, BotState.SLEEPING, BotState.DIZZY)) {
            assertFalse("$s", OverlayPolicy.shouldFlash(BotState.IDLE, s))
        }
    }

    @Test fun anApprovalHasItsOwnCardNotAStatusPill() {
        assertFalse(OverlayPolicy.shouldFlash(BotState.WORKING, BotState.APPROVAL))
    }

    @Test fun theSameStateSentAgainStaysQuiet() {
        assertFalse(OverlayPolicy.shouldFlash(BotState.FINISHED, BotState.FINISHED))
        assertTrue(OverlayPolicy.shouldFlash(BotState.WORKING, BotState.ERROR))
    }

    @Test fun onlyShownWhenWantedPermittedAndYouAreElsewhere() {
        assertTrue(OverlayPolicy.shouldShow(enabled = true, permitted = true, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = false, permitted = true, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = true, permitted = false, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = true, permitted = true, appInForeground = true))
    }

    @Test fun theFirstPictureAfterConnectingNeverWakesIt() {
        // Sessions that finished long ago are sent again on every reconnect: they stay quiet.
        for (s in listOf(BotState.FINISHED, BotState.ERROR, BotState.QUESTION, BotState.RATELIMIT)) {
            assertFalse("$s", OverlayPolicy.shouldFlash(null, s))
        }
    }

    @Test fun theReasonItStaysAwayIsNamed() {
        assertEquals("switch is off", OverlayPolicy.blocker(false, true, false))
        assertTrue(OverlayPolicy.blocker(true, false, false)!!.contains("not allowed"))
        assertEquals("the app is on screen", OverlayPolicy.blocker(true, true, true))
        assertEquals(null, OverlayPolicy.blocker(true, true, false))
    }

    private class Memory : WishStore {
        var saved = false
        var writes = 0
        override fun read() = saved
        override fun write(on: Boolean) { saved = on; writes++ }
    }

    @Test fun theWishIsSavedAtTheTapEvenIfThePermissionIsNotThereYet() {
        val disk = Memory()
        OverlayChoice(disk).choose(true) // tap; Android now opens its settings screen
        assertTrue(disk.saved)
        // Android killed and restarted the app while the user was in Settings: a new object, same disk.
        val afterRestart = OverlayChoice(disk)
        assertTrue(afterRestart.wished)
        assertFalse(afterRestart.active(permitted = false)) // still waiting for the system permission
        assertTrue(afterRestart.active(permitted = true)) // granted: works without another tap
    }

    @Test fun grantingThePermissionLaterFromAndroidSettingsIsEnough() {
        val choice = OverlayChoice(Memory().apply { saved = true })
        var permitted = false
        assertFalse(choice.active(permitted))
        permitted = true // the user allowed it in Android's own settings
        assertTrue(choice.active(permitted))
    }

    @Test fun switchingOffIsRememberedAndNeverShowsAgain() {
        val disk = Memory().apply { saved = true }
        val choice = OverlayChoice(disk)
        choice.choose(false)
        assertFalse(OverlayChoice(disk).active(permitted = true))
    }
}
