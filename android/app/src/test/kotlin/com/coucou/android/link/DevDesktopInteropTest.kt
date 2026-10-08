package com.coucou.android.link

import com.coucou.android.ReferenceFiles
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real [LinkClient] against android/tools/dev-desktop.mjs, a separate implementation of the
 * protocol (Node's TLS stack, its own certificate). Skipped when node or openssl is missing.
 */
class DevDesktopInteropTest {
    private var proc: Process? = null
    private val lines = LinkedBlockingQueue<String>()

    @After fun cleanup() { proc?.destroyForcibly() }

    private fun have(cmd: String) = try {
        ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
    } catch (_: Exception) { false }

    private fun startDesktop(vararg extra: String): JSONObject {
        assumeTrue("node not available", have("node"))
        assumeTrue("openssl not available", have("openssl"))
        val script = ReferenceFiles.file("android/tools/dev-desktop.mjs").absolutePath
        proc = ProcessBuilder(listOf("node", script, "--host", "127.0.0.1", "--port", "0", "--quiet", "--step", "300") + extra)
            .redirectErrorStream(true).start()
        Thread {
            BufferedReader(InputStreamReader(proc!!.inputStream)).forEachLine { lines.add(it) }
        }.apply { isDaemon = true; start() }
        val first = lines.poll(20, TimeUnit.SECONDS)
        assertNotNull("dev desktop did not start", first)
        return JSONObject(first!!)
    }

    private class Rec : LinkListener {
        val welcome = LinkedBlockingQueue<String>()
        val sessions = LinkedBlockingQueue<List<SessionInfo>>()
        val approvals = LinkedBlockingQueue<ApprovalRequest>()
        val resolved = LinkedBlockingQueue<String>()
        val errors = LinkedBlockingQueue<String>()
        override fun onWelcome(desktopName: String, os: String) { welcome.add(desktopName) }
        override fun onSessions(sessions: List<SessionInfo>) { this.sessions.add(sessions) }
        override fun onApproval(request: ApprovalRequest) { approvals.add(request) }
        override fun onApprovalResolved(fingerprint: String) { resolved.add(fingerprint) }
        override fun onError(code: String, message: String) { errors.add(code) }
    }

    @Test fun pairsReceivesSessionsAndApprovalAndDecides() {
        val info = startDesktop("--once")
        val pairing = PairingPayload.parse(info.getString("link"))
        assertNotNull("pairing link not parsed: ${info.getString("link")}", pairing)
        val rec = Rec()
        val client = LinkClient(pairing!!, "Interop Pixel", rec, readTimeoutMs = 5_000)
        try {
            client.start()
            assertNotNull(rec.welcome.poll(10, TimeUnit.SECONDS))
            assertTrue(rec.sessions.poll(10, TimeUnit.SECONDS)!!.isNotEmpty())
            val req = rec.approvals.poll(10, TimeUnit.SECONDS)
            assertNotNull("no approval request arrived", req)
            // The fingerprint must be the one the Mac-compatible derivation yields for these fields.
            assertTrue(Wire.isFingerprint(req!!.fingerprint))
            assertTrue(client.decide(req.fingerprint, allow = true))
            assertEquals(req.fingerprint, rec.resolved.poll(10, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 10_000
            var sawDecision = false
            while (!sawDecision && System.currentTimeMillis() < deadline) {
                val l = lines.poll(1, TimeUnit.SECONDS) ?: continue
                if (l == "DECISION allow ${req.fingerprint}") sawDecision = true
            }
            assertTrue("desktop never logged the decision", sawDecision)
        } finally {
            client.stop()
        }
    }

    @Test fun wrongTokenIsRejectedByTheNodeDesktop() {
        val info = startDesktop()
        val good = PairingPayload.parse(info.getString("link"))!!
        val rec = Rec()
        val client = LinkClient(good.copy(token = "x".repeat(24)), "Interop Pixel", rec, readTimeoutMs = 5_000)
        try {
            client.start()
            assertEquals("auth", rec.errors.poll(10, TimeUnit.SECONDS))
            assertTrue(rec.welcome.isEmpty())
        } finally {
            client.stop()
        }
    }
}
