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
}
