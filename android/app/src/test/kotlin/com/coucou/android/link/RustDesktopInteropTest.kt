package com.coucou.android.link

import com.coucou.android.ReferenceFiles
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real [LinkClient] against the real desktop server of the Windows/Linux app
 * (windows/src-tauri/src/phone_link), a separate implementation of docs/ANDROID_LINK.md in Rust
 * with its own TLS stack (rustls) and its own certificate.
 *
 * The server is the ignored cargo test `phone_link::interop::interop_server`, started here and
 * driven over stdin. Compiling it takes minutes, so this only runs when COUCOU_RUST_INTEROP=1
 * (the `interop` job in .github/workflows/android.yml sets it) and cargo is installed.
 */
class RustDesktopInteropTest {
    private var proc: Process? = null
    private var stdin: PrintWriter? = null
    private val lines = LinkedBlockingQueue<String>()

    @After fun cleanup() {
        stdin?.println("quit")
        stdin?.flush()
        proc?.waitFor(5, TimeUnit.SECONDS)
        proc?.destroyForcibly()
    }

    private fun startDesktop(): JSONObject {
        assumeTrue("set COUCOU_RUST_INTEROP=1 to run against the Rust desktop", System.getenv("COUCOU_RUST_INTEROP") == "1")
        val manifest = ReferenceFiles.file("windows/Cargo.toml").absolutePath
        proc = try {
            ProcessBuilder(
                "cargo", "test", "--manifest-path", manifest, "-p", "coucou", "--lib", "--locked", "--",
                "--ignored", "--nocapture", "phone_link::interop::interop_server",
            ).redirectErrorStream(true).start()
        } catch (_: Exception) {
            assumeTrue("cargo not available", false)
            return JSONObject()
        }
        stdin = PrintWriter(proc!!.outputStream, true)
        Thread {
            BufferedReader(InputStreamReader(proc!!.inputStream)).forEachLine { lines.add(it) }
        }.apply { isDaemon = true; start() }
        // The first start compiles the whole app: allow for it.
        val deadline = System.currentTimeMillis() + 20 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val l = lines.poll(5, TimeUnit.SECONDS) ?: continue
            if (l.startsWith("{\"listening\"")) return JSONObject(l)
        }
        error("the Rust desktop did not start")
    }

    /** Sends one command and waits until the desktop has done it. */
    private fun command(text: String) {
        stdin!!.println(text)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val l = lines.poll(1, TimeUnit.SECONDS) ?: continue
            if (l == "OK $text") return
        }
        error("no answer to '$text'")
    }

    /** True if the desktop printed [expected] within [seconds]. */
    private fun sawLine(expected: String, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val l = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (l == expected) return true
        }
        return false
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

    private fun connect(info: JSONObject, rec: Rec, token: String? = null): LinkClient {
        val pairing = PairingPayload.parse(info.getString("link"))
        assertNotNull("the Rust desktop's pairing link was not accepted: ${info.getString("link")}", pairing)
        val client = LinkClient(if (token == null) pairing!! else pairing!!.copy(token = token), "Interop Pixel", rec, readTimeoutMs = 5_000)
        client.start()
        return client
    }

    @Test fun pairsReceivesSessionsAndApprovalAndAllows() {
        val info = startDesktop()
        val rec = Rec()
        val client = connect(info, rec)
        try {
            assertEquals("Rust desktop", rec.welcome.poll(15, TimeUnit.SECONDS))
            command("sessions")
            var sessions = rec.sessions.poll(10, TimeUnit.SECONDS)
            while (sessions != null && sessions.isEmpty()) sessions = rec.sessions.poll(10, TimeUnit.SECONDS)
            assertNotNull("no sessions arrived", sessions)
            assertEquals(listOf("integration_claude", "agent_gemini"), sessions!!.map { it.pillId })
            assertEquals("Claude Code", sessions[0].agent)
            assertEquals(2, sessions[0].stepIndex)
            assertEquals(6, sessions[0].stepCount)

            command("approval req-1 npm run build")
            val req = rec.approvals.poll(10, TimeUnit.SECONDS)
            assertNotNull("no approval request arrived", req)
            assertEquals("npm run build", req!!.command)
            assertEquals("Bash", req.tool)
            // The Rust fingerprint must be the one the Mac-compatible Kotlin derivation yields.
            assertEquals(Fingerprint.of("integration_claude", "interop", "Bash", "npm run build", "req-1"), req.fingerprint)

            assertTrue(client.decide(req.fingerprint, allow = true))
            assertEquals(req.fingerprint, rec.resolved.poll(10, TimeUnit.SECONDS))
            assertTrue("the desktop never applied the decision", sawLine("DECISION allow req-1", 10))
        } finally {
            client.stop()
        }
    }

    @Test fun denyReachesTheDesktop() {
        val info = startDesktop()
        val rec = Rec()
        val client = connect(info, rec)
        try {
            assertNotNull(rec.welcome.poll(15, TimeUnit.SECONDS))
            command("approval req-9 rm -rf build")
            val req = rec.approvals.poll(10, TimeUnit.SECONDS)!!
            assertTrue(client.decide(req.fingerprint, allow = false))
            assertTrue(sawLine("DECISION deny req-9", 10))
        } finally {
            client.stop()
        }
    }

    @Test fun aRequestAnsweredAtTheDeskCannotBeAnsweredFromThePhone() {
        val info = startDesktop()
        val rec = Rec()
        val client = connect(info, rec)
        try {
            assertNotNull(rec.welcome.poll(15, TimeUnit.SECONDS))
            command("approval req-2 ls")
            val req = rec.approvals.poll(10, TimeUnit.SECONDS)!!
            command("clear") // allowed on the desktop
            assertEquals(req.fingerprint, rec.resolved.poll(10, TimeUnit.SECONDS))
            assertFalse("the phone no longer offers it", client.decide(req.fingerprint, allow = true))
            assertFalse(sawLine("DECISION allow req-2", 1))
        } finally {
            client.stop()
        }
    }

    @Test fun aWrongTokenIsRefused() {
        val info = startDesktop()
        val rec = Rec()
        val client = connect(info, rec, token = "x".repeat(30))
        try {
            assertEquals("auth", rec.errors.poll(15, TimeUnit.SECONDS))
            assertNull(rec.welcome.poll(1, TimeUnit.SECONDS))
        } finally {
            client.stop()
        }
    }

    @Test fun pairingAgainDisconnectsThePhoneThatHadTheOldCode() {
        val info = startDesktop()
        val rec = Rec()
        val client = connect(info, rec)
        try {
            assertNotNull(rec.welcome.poll(15, TimeUnit.SECONDS))
            command("repair brand-new-token-0123456789")
            assertEquals("auth", rec.errors.poll(10, TimeUnit.SECONDS))
        } finally {
            client.stop()
        }
    }
}
