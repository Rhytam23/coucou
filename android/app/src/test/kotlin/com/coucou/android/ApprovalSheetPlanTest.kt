package com.coucou.android

import com.coucou.android.core.ApprovalSheetPlan
import com.coucou.android.core.OverlayPolicy
import com.coucou.android.link.ApprovalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSheetPlanTest {
    private fun r(fp: String, tool: String = "Bash") = ApprovalRequest("p", fp, tool, "rm -rf /tmp/x", 0L)

    @Test fun theFirstRequestShowsUntilTheUserClosesIt() {
        val list = listOf(r("a"), r("b"))
        assertEquals("a", ApprovalSheetPlan.next(list, null)!!.fingerprint)
        assertEquals("b", ApprovalSheetPlan.next(list, "a")!!.fingerprint)
        assertNull(ApprovalSheetPlan.next(listOf(r("a")), "a"))
        assertNull(ApprovalSheetPlan.next(emptyList(), null))
    }

    @Test fun aClosedRequestComesBackWhenHomeAsksForIt() {
        assertEquals("a", ApprovalSheetPlan.next(listOf(r("a")), null)!!.fingerprint)
    }

    @Test fun theSheetNamesTheKindOfActionNotTheCommand() {
        assertEquals("run a command", ApprovalSheetPlan.action("Bash"))
        assertEquals("edit files", ApprovalSheetPlan.action("Write"))
        assertFalse(ApprovalSheetPlan.action("Bash").contains("rm"))
    }

    @Test fun earlyTapsAreIgnoredLikeOnTheIsland() {
        assertEquals(OverlayPolicy.TAP_GUARD_MS.toLong(), ApprovalSheetPlan.GUARD_MS)
        assertFalse(ApprovalSheetPlan.tapsAccepted(1_000, 1_599))
        assertTrue(ApprovalSheetPlan.tapsAccepted(1_000, 1_600))
    }
}
