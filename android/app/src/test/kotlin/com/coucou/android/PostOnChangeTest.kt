package com.coucou.android

import com.coucou.android.core.PostOnChange
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostOnChangeTest {
    @Test fun postsOnlyWhenTheTextChanges() {
        val g = PostOnChange<List<String>>()
        assertTrue("the first one", g.shouldPost(listOf("Claude Code", "Working")))
        assertFalse("the same words", g.shouldPost(listOf("Claude Code", "Working")))
        assertTrue("a new word", g.shouldPost(listOf("Claude Code", "Done")))
        assertFalse(g.shouldPost(listOf("Claude Code", "Done")))
    }

    @Test fun aFailedPostOrAnotherPathIsAccountedFor() {
        val g = PostOnChange<String?>()
        g.remember("Connected") // posted by the service's start
        assertFalse(g.shouldPost("Connected"))
        g.forget() // the post failed: try again next time
        assertTrue(g.shouldPost("Connected"))
        assertTrue(g.shouldPost(null))
        assertFalse(g.shouldPost(null))
    }

    @Test fun theOngoingNotificationUsesIt() {
        val n = File("src/main/kotlin/com/coucou/android/app/Notifications.kt").readText()
        assertTrue(n.substringAfter("fun updateOngoing").substringBefore("fun showApproval").contains("shown.shouldPost(g)"))
    }
}
