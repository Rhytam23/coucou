package com.coucou.android.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalBookTest {
    private var now = 1_000_000L
    private val book = ApprovalBook { now }
    private fun req(fp: String, created: Long = now) = ApprovalRequest("p", fp, "Bash", "ls", created)

    @Test fun claimedRequestCanOnlyBeDecidedOnce() {
        book.add(req("a"))
        assertNotNull(book.claim("a"))
        assertNull(book.claim("a"))
    }

    @Test fun unknownRequestCannotBeDecided() {
        assertNull(book.claim("never-seen"))
    }

    @Test fun expiresAfterTheTtl() {
        book.add(req("a"))
        now += Protocol.APPROVAL_TTL_MS - 1
        assertEquals(1, book.active().size)
        now += 1
        assertEquals(0, book.active().size)
        book.add(req("b", created = now - Protocol.APPROVAL_TTL_MS))
        assertNull(book.claim("b"))
    }

    @Test fun resolvedByTheDesktopDisappears() {
        book.add(req("a"))
        book.resolve("a")
        assertNull(book.claim("a"))
    }

    @Test fun keepsArrivalOrder() {
        book.add(req("a")); book.add(req("b")); book.add(req("c"))
        assertEquals(listOf("a", "b", "c"), book.active().map { it.fingerprint })
    }
}
