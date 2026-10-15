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
    @Test fun theBarAlwaysHasHomeChatAndSettingsInThatOrder() {
        assertEquals(listOf(Tab.HOME, Tab.CHAT, Tab.SETTINGS), Nav.tabs())
    }

    @Test fun theBarShowsOnTheThreeRootsOnly() {
        for (s in listOf(Screen.HOME, Screen.CHAT, Screen.SETTINGS)) assertTrue("$s", Nav.barVisible(s, keyboardOpen = false))
        for (s in listOf(Screen.HISTORY, Screen.GALLERY, Screen.DIAGNOSTICS, Screen.LICENCES, Screen.SESSION, Screen.SCAN)) assertFalse("$s", Nav.barVisible(s, false))
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
        assertEquals(Screen.SETTINGS, Nav.back(Screen.DIAGNOSTICS))
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
        for (s in listOf(Screen.SETTINGS, Screen.HISTORY, Screen.GALLERY, Screen.DIAGNOSTICS)) assertEquals(Tab.SETTINGS, Nav.tabOf(s))
        assertNull(Nav.tabOf(Screen.SCAN))
        assertNull(Nav.tabOf(Screen.SESSION))
    }

    @Test fun everyTabLeadsToItsOwnScreen() {
        for (t in Tab.entries) assertEquals(t, Nav.tabOf(Nav.screenOf(t)))
    }

    @Test fun predictiveBackIsOnAndBackFromEveryScreenReachesHomeThenLeaves() {
        // android:enableOnBackInvokedCallback shows the system's back preview; it needs every screen to answer Back through a handler.
        assertTrue(java.io.File("src/main/AndroidManifest.xml").readText().contains("""android:enableOnBackInvokedCallback="true""""))
        for (s in Screen.entries) {
            var at = s
            var steps = 0
            while (Nav.back(at) != null) { at = Nav.back(at)!!; steps++; assertTrue("$s loops", steps <= 2) }
            assertEquals("$s ends on Home", Screen.HOME, at)
        }
        assertNull("Back on Home leaves the app", Nav.back(Screen.HOME))
        // Every sheet closes itself on Back (BottomSheetHost) and every dialog through its dismiss request.
        val sheets = java.io.File("src/main/kotlin/com/coucou/android/ui/Sheets.kt").readText()
        assertTrue(sheets.contains("BackHandler(onBack = onDismiss)"))
    }
}
