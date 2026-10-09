package com.coucou.android

import com.coucou.android.core.OverlayPolicy
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
        assertTrue(OverlayPolicy.shouldFlash(null, BotState.ERROR))
    }

    @Test fun onlyShownWhenWantedPermittedAndYouAreElsewhere() {
        assertTrue(OverlayPolicy.shouldShow(enabled = true, permitted = true, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = false, permitted = true, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = true, permitted = false, appInForeground = false))
        assertFalse(OverlayPolicy.shouldShow(enabled = true, permitted = true, appInForeground = true))
    }
}
