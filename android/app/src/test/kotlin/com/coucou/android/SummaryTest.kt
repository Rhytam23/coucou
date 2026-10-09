package com.coucou.android

import com.coucou.android.core.Summary
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryTest {
    private fun s(state: BotState) = SessionInfo("p$state", "Agent", state, "", 0, 0, 0)

    @Test fun noSessionsMeansIdle() = assertEquals(BotState.IDLE, Summary.headerState(emptyList(), false))

    @Test fun aWaitingApprovalAlwaysWins() {
        assertEquals(BotState.APPROVAL, Summary.headerState(listOf(s(BotState.WORKING)), hasApproval = true))
    }

    @Test fun troubleBeatsWorkAndWorkBeatsRest() {
        assertEquals(BotState.ERROR, Summary.headerState(listOf(s(BotState.WORKING), s(BotState.ERROR)), false))
        assertEquals(BotState.WORKING, Summary.headerState(listOf(s(BotState.SLEEPING), s(BotState.WORKING)), false))
        assertEquals(BotState.QUESTION, Summary.headerState(listOf(s(BotState.ERROR), s(BotState.QUESTION)), false))
    }

    @Test fun everyStateHasARank() {
        // A state added to the engine later must not silently fall to IDLE.
        for (state in BotState.entries) assertEquals(state, Summary.headerState(listOf(s(state)), false))
    }

    @Test fun progressIsClampedAndSafe() {
        assertEquals(0f, Summary.progress(3, 0), 0f)
        assertEquals(4f / 6f, Summary.progress(3, 6), 1e-6f)
        assertEquals(1f, Summary.progress(9, 6), 0f)
        assertEquals(1f / 6f, Summary.progress(-2, 6), 1e-6f)
    }

    @Test fun theStepShownIsOneBasedAndNeverOutOfRange() {
        assertEquals(1, Summary.stepNumber(0, 8))
        assertEquals(3, Summary.stepNumber(2, 8))
        assertEquals(8, Summary.stepNumber(40, 8))
        assertEquals(1, Summary.stepNumber(-5, 8))
        assertNull(Summary.stepNumber(2, 0))
        assertNull(Summary.stepNumber(2, -1))
    }
}
