package com.coucou.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the approval and question sheets (Compose cannot be rendered in a JVM test, so these read the
 * sources). The point of the redesign here: no raw command on screen unless asked for, and nothing about Allow gets weaker.
 */
class SheetsTest {
    private fun src(name: String) = File("src/main/kotlin/com/coucou/android/$name").readText()
    private val sheets get() = src("ui/Sheets.kt")
    private val main get() = src("MainActivity.kt")

    @Test fun theCommandIsOnlyDrawnAfterShowExactCommand() {
        assertEquals("the command is drawn in exactly one place", 1, Regex("""r\.command""").findAll(sheets).count())
        val before = sheets.substringBefore("r.command")
        assertTrue(before.substringAfterLast("if (showCommand)").length < 400)
        assertTrue(before.contains("if (showCommand)"))
    }

    @Test fun homeNoLongerDrawsARawCommand() {
        // the only place left is the lock prompt, which must keep repeating the exact command
        assertEquals(1, Regex("""r\.command""").findAll(main).count())
        assertTrue(main.contains("\"${'$'}{r.tool}: ${'$'}{r.command}\""))
        assertFalse(main.contains("fun ApprovalCard"))
        assertFalse(src("ui/HomePanel.kt").contains(".command"))
    }

    @Test fun theSheetSaysTheKindOfActionAndNotTheTool() {
        assertTrue(sheets.contains("ApprovalSheetPlan.action(r.tool)"))
        assertTrue(sheets.contains("R.string.approval_wants"))
    }

    @Test fun allowStillGoesThroughTheLockAndDenyNeedsNone() {
        assertFalse("the sheet never allows by itself", Regex("""allow\s*=\s*true""").containsMatchIn(sheets))
        assertTrue(sheets.contains("onAllow(r)"))
        assertTrue(main.contains("onAllow = ::approve"))
        assertTrue(main.contains("BiometricGate.confirm("))
        assertTrue(sheets.contains("model.decide(r.fingerprint, allow = false)"))
    }

    @Test fun bothButtonsIgnoreTapsDuringTheFirstMoments() {
        assertTrue(sheets.contains("delay(ApprovalSheetPlan.GUARD_MS)"))
        assertEquals(2, Regex("""enabled = armed""").findAll(sheets).count())
    }

    @Test fun theSheetCanBeClosedAndNeverCatchesTapsMeantForTheScrim() {
        assertTrue(sheets.contains("BackHandler(onBack = onDismiss)"))
        assertTrue(sheets.contains("detectTapGestures { }"))
        assertTrue(main.contains("closedApproval = pending.fingerprint"))
        assertTrue("Review request reopens it", main.contains("onReview = { closedApproval = null }"))
    }

    @Test fun theSheetIsBlackInBothThemesAndNotOverTheCamera() {
        assertTrue(sheets.contains("IslandSurface.PANEL"))
        assertTrue(main.contains("screen != Screen.SCAN"))
    }

    @Test fun aQuestionIsReadOnlyForNow() {
        assertTrue(sheets.contains("R.string.question_body"))
        assertTrue(main.contains("focus.state == BotState.QUESTION"))
        assertFalse(sheets.contains("model.answer"))
    }
}
