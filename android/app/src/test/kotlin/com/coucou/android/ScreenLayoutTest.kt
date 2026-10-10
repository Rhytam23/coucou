package com.coucou.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for where things live on screen (the Compose code cannot be rendered in a JVM test, so
 * these read the sources). Home is the agent card; Settings holds the rest, including the notice
 * Louis Raillé's permission requires, which must stay reachable and visible.
 */
class ScreenLayoutTest {
    private fun src(name: String) = File("src/main/kotlin/com/coucou/android/$name").readText()
    private val main get() = src("MainActivity.kt")
    private val settings get() = src("ui/SettingsScreens.kt")
    private val homePanel get() = src("ui/HomePanel.kt")

    @Test fun homeHasNoGearAndTheBarLeadsToSettings() {
        assertFalse("the sun-like gear is gone", main.contains("GearButton("))
        assertFalse(File("src/main/kotlin/com/coucou/android/ui/GearButton.kt").exists())
        assertTrue(main.contains("BottomBar("))
        val bar = src("ui/BottomBar.kt")
        assertTrue("Settings is drawn as sliders", bar.contains("Tab.SETTINGS -> IconKind.SLIDERS"))
        for (s in listOf("R.string.action_unpair", "R.string.about_unofficial", "R.string.about_assets", "R.string.overlay_hint")) {
            assertFalse("Home must not carry $s", main.contains(s))
        }
        assertFalse("the old footer row is gone", main.contains("fun Footer"))
    }

    @Test fun disconnectSitsAtTheBottomOfTheComputerPanel() {
        val computer = settings.substringAfter("settings_section_computer").substringBefore("settings_section_display")
        assertTrue(computer.indexOf("StateDot(") < computer.indexOf("R.string.action_unpair"))
        assertTrue("it is the red pill", computer.contains("PillKind.DANGER"))
    }

    @Test fun theTabScreensScrollAboveTheBar() {
        assertTrue(main.contains("PaddingValues(bottom = BarClearance)"))
        assertTrue(settings.contains("PaddingValues(bottom = BarClearance)"))
        assertTrue("chat keeps its message box above the bar", main.contains("screen == Screen.CHAT) BarClearance"))
    }

    @Test fun settingsHoldsDisconnectGalleryOverlayAndTheRequiredNotice() {
        for (s in listOf(
            "R.string.action_unpair", "R.string.demo_leave", "R.string.gallery", "R.string.overlay_title",
            "R.string.about_unofficial", "R.string.about_repo", "R.string.about_assets", "R.string.settings_section_about",
        )) assertTrue("Settings must carry $s", settings.contains(s))
    }

    @Test fun theNoticeNamesTheOriginalAuthorAndHisRepository() {
        val xml = File("src/main/res/values/strings.xml").readText()
        assertTrue(xml.contains("Unofficial Android port based on Coucou by Louis Raillé"))
        assertTrue(xml.contains("github.com/Louis-CFM/coucou"))
        assertTrue(xml.contains("© Louis Raillé"))
        assertTrue(xml.contains("MIT License"))
    }

    @Test fun backFromTheGalleryGoesToSettings() {
        assertTrue(main.contains("Screen.GALLERY -> Gallery(onBack = { screen = Screen.SETTINGS }"))
        assertTrue("the design screen goes back to the gallery", main.contains("Screen.DESIGN -> DesignScreen(onBack = { screen = Screen.GALLERY })"))
    }

    @Test fun theAgentCardNeverShowsTheRawStatusText() {
        assertTrue(homePanel.contains("ToolLabels.label("))
        assertFalse("the raw text must not be drawn directly", Regex("""Text\(\s*(focus\??\.|detail = focus)?statusText""").containsMatchIn(homePanel))
    }

    @Test fun pillsAreEqualColumnsWithAFixedHeight() {
        assertTrue(homePanel.contains("HomePanel.cells(row)"))
        assertTrue(homePanel.contains("CHIP_HEIGHT"))
        assertEquals(2, Regex("""\.weight\(1f\)""").findAll(homePanel.substringAfter("fun AgentChipRow")).count())
    }

    @Test fun noScreenUsesAnOddGutter() {
        for (file in listOf("MainActivity.kt", "ui/SettingsScreens.kt")) {
            val text = src(file)
            assertFalse("$file: use Gutter/Gap", Regex("""padding\(horizontal = 1[0-5]\.dp\)""").containsMatchIn(text))
        }
    }
}
