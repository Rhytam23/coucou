package com.coucou.android

import com.coucou.android.core.HomePanel
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePanelTest {
    private fun s(id: String, st: BotState) = SessionInfo(id, id, st, "", 0, 0, 0L)

    @Test fun theAgentThatNeedsYouMostIsInTheBigCard() {
        val list = listOf(s("a", BotState.WORKING), s("b", BotState.APPROVAL), s("c", BotState.ERROR), s("d", BotState.IDLE))
        assertEquals("b", HomePanel.focus(list, null)!!.pillId)
        assertEquals("c", HomePanel.focus(list.filter { it.pillId != "b" }, null)!!.pillId)
        assertEquals("a", HomePanel.focus(listOf(s("d", BotState.IDLE), s("a", BotState.WORKING)), null)!!.pillId)
    }

    @Test fun onATieTheFirstOneWins() {
        assertEquals("a", HomePanel.focus(listOf(s("a", BotState.WORKING), s("b", BotState.WORKING)), null)!!.pillId)
    }

    @Test fun aTappedAgentStaysInTheCardWhileItExists() {
        val list = listOf(s("a", BotState.APPROVAL), s("b", BotState.IDLE))
        assertEquals("b", HomePanel.focus(list, "b")!!.pillId)
        assertEquals("a", HomePanel.focus(list, "gone")!!.pillId)
    }

    @Test fun noAgentMeansNoFocus() {
        assertNull(HomePanel.focus(emptyList(), null))
        assertNull(HomePanel.focus(emptyList(), "a"))
    }

    @Test fun theOthersExcludeTheFocusAndComeInTwos() {
        val list = listOf(s("a", BotState.IDLE), s("b", BotState.IDLE), s("c", BotState.IDLE), s("d", BotState.IDLE), s("e", BotState.IDLE))
        val others = HomePanel.others(list, list[0])
        assertEquals(listOf("b", "c", "d", "e"), others.map { it.pillId })
        assertEquals(listOf(2, 2), HomePanel.rows(others).map { it.size })
        assertEquals(listOf(2, 1), HomePanel.rows(others.take(3)).map { it.size })
        assertEquals(5, HomePanel.others(list, null).size)
    }

    @Test fun theLinkPairsWhenThereIsNoComputerAndOpensSettingsOtherwise() {
        assertEquals(HomePanel.Link.PAIR, HomePanel.link(paired = false, demo = false))
        assertEquals(HomePanel.Link.SETTINGS, HomePanel.link(paired = true, demo = false))
        assertEquals(HomePanel.Link.SETTINGS, HomePanel.link(paired = false, demo = true))
    }

    @Test fun pillColoursBecomeMochiBodies() {
        val c = HomePanel.rgb("#8AB4F8")!!
        assertEquals(0x8A / 255.0, c.r, 1e-9)
        assertEquals(0xB4 / 255.0, c.g, 1e-9)
        assertEquals(0xF8 / 255.0, c.b, 1e-9)
        assertNull(HomePanel.rgb("blue"))
        assertNull(HomePanel.rgb("#12"))
        assertNull(HomePanel.rgb("#GGGGGG"))
    }
}
