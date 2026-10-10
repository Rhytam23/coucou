package com.coucou.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the redesign's building blocks (the Compose code cannot be rendered in a JVM test, so
 * these read the sources): accessible controls, colours only from the tokens, no icon library.
 */
class DesignSystemTest {
    private fun src(name: String) = File("src/main/kotlin/com/coucou/android/$name").readText()
    private val system get() = src("ui/DesignSystem.kt")
    private val controls get() = src("ui/DesignControls.kt")
    private val icons get() = src("ui/DesignIcons.kt")
    private val screen get() = src("ui/DesignScreen.kt")

    @Test fun theSwitchIsARealToggleForTalkBack() {
        assertTrue(controls.contains("role = Role.Switch"))
        assertTrue(controls.contains("toggleable("))
    }

    @Test fun theSliderCanBeSteppedByTalkBack() {
        assertTrue(controls.contains("progressBarRangeInfo"))
        assertTrue(controls.contains("setProgress"))
        assertTrue(controls.contains("detectTapGestures") && controls.contains("detectHorizontalDragGestures"))
    }

    @Test fun everyControlIsAtLeast48DpToHit() {
        assertTrue(controls.contains("Spacing.MIN_TOUCH.dp"))
        assertTrue(system.contains("heightIn(min = Spacing.MIN_TOUCH.dp)"))
    }

    @Test fun buttonsAndRowsAnnounceThemselvesAsButtons() {
        assertTrue(system.contains("role = Role.Button"))
    }

    @Test fun coloursComeFromTheTokensNotFromLiterals() {
        for ((name, text) in listOf("DesignSystem" to system, "DesignControls" to controls, "DesignIcons" to icons)) {
            assertFalse("$name has a colour literal", Regex("""Color\(0x""").containsMatchIn(text))
        }
    }

    @Test fun iconsAreDrawnInCodeWithNoIconLibrary() {
        assertTrue(icons.contains("IconSpec.shapes("))
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("material-icons"))
        assertFalse(gradle.contains("androidx.navigation"))
        for (text in listOf(system, controls, icons, screen)) assertFalse(text.contains("androidx.compose.material.icons"))
    }

    @Test fun thePanelsHaveNoShadow() {
        assertFalse(system.contains("shadow("))
        assertFalse(system.contains("elevation"))
    }

    @Test fun theThemeHandsOutTheTokensAndTheDesignScreenIsReachableFromTheGallery() {
        assertTrue(src("ui/Theme.kt").contains("LocalTokens provides"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("onDesign = { screen = Screen.DESIGN }"))
        assertTrue(main.contains("Screen.DESIGN -> Screen.GALLERY"))
    }

    @Test fun existingScreensAreNotMovedYet() {
        // U0 only adds the blocks. Each screen moves in its own stage, together with its guard tests.
        for (file in listOf("MainActivity.kt", "ui/SettingsScreens.kt", "ui/HomePanel.kt", "ui/ChatScreen.kt", "ui/SessionScreen.kt")) {
            assertFalse("$file already uses the new panel", Regex("""\bPanel\(""").containsMatchIn(src(file)))
        }
    }
}
