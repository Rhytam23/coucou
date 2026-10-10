package com.coucou.android

import com.coucou.android.core.Nav
import com.coucou.android.core.Screen
import com.coucou.android.core.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test fun chatIsInTheBarOnlyWhenOfferedOrThereIsAConversation() {
        assertEquals(listOf(Tab.HOME, Tab.SETTINGS), Nav.tabs(chatOffered = false, hasConversation = false))
        assertEquals(listOf(Tab.HOME, Tab.CHAT, Tab.SETTINGS), Nav.tabs(true, false))
        assertEquals(listOf(Tab.HOME, Tab.CHAT, Tab.SETTINGS), Nav.tabs(false, true))
    }

    @Test fun homeIsFirstAndSettingsLast() {
        for (o in listOf(true, false)) for (c in listOf(true, false)) {
            val t = Nav.tabs(o, c)
            assertEquals(Tab.HOME, t.first())
            assertEquals(Tab.SETTINGS, t.last())
        }
    }

    @Test fun theBarShowsOnTheThreeRootsOnly() {
        for (s in listOf(Screen.HOME, Screen.CHAT, Screen.SETTINGS)) assertTrue("$s", Nav.barVisible(s, keyboardOpen = false))
        for (s in listOf(Screen.HISTORY, Screen.GALLERY, Screen.DESIGN, Screen.SESSION, Screen.SCAN)) assertFalse("$s", Nav.barVisible(s, false))
    }

    @Test fun theBarHidesWhileTheKeyboardIsOpen() {
        for (s in Screen.entries) assertFalse("$s", Nav.barVisible(s, keyboardOpen = true))
    }

    @Test fun backGoesUpOneLevelAndLeavesFromHome() {
        assertNull(Nav.back(Screen.HOME))
        assertEquals(Screen.HOME, Nav.back(Screen.CHAT))
        assertEquals(Screen.HOME, Nav.back(Screen.SETTINGS))
        assertEquals(Screen.HOME, Nav.back(Screen.SESSION))
        assertEquals(Screen.HOME, Nav.back(Screen.SCAN))
        assertEquals(Screen.SETTINGS, Nav.back(Screen.HISTORY))
        assertEquals(Screen.SETTINGS, Nav.back(Screen.GALLERY))
        assertEquals(Screen.GALLERY, Nav.back(Screen.DESIGN))
    }

    @Test fun backAlwaysReachesHomeAndNeverLoops() {
        for (start in Screen.entries) {
            var s = start
            var steps = 0
            while (true) {
                s = Nav.back(s) ?: break
                assertTrue("loop from $start", ++steps < 10)
            }
            assertEquals("from $start", Screen.HOME, s)
        }
    }

    @Test fun pagesBelowSettingsKeepSettingsHighlighted() {
        for (s in listOf(Screen.SETTINGS, Screen.HISTORY, Screen.GALLERY, Screen.DESIGN)) assertEquals(Tab.SETTINGS, Nav.tabOf(s))
        assertNull(Nav.tabOf(Screen.SCAN))
        assertNull(Nav.tabOf(Screen.SESSION))
    }

    @Test fun everyTabLeadsToItsOwnScreen() {
        for (t in Tab.entries) assertEquals(t, Nav.tabOf(Nav.screenOf(t)))
    }

    @Test fun aVanishedChatTabSendsTheUserHome() {
        val noChat = Nav.tabs(false, false)
        assertEquals(Screen.HOME, Nav.resolve(Screen.CHAT, noChat))
        assertEquals(Screen.SETTINGS, Nav.resolve(Screen.SETTINGS, noChat))
        assertEquals(Screen.SCAN, Nav.resolve(Screen.SCAN, noChat))
        assertEquals(Screen.CHAT, Nav.resolve(Screen.CHAT, Nav.tabs(true, false)))
    }
}
