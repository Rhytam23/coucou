package com.coucou.android.link

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pretend desktop: real TLS with the test certificate, speaking the v1 protocol. */
private class FakeDesktop(tls: Boolean) : AutoCloseable {
    val server: ServerSocket
    val certSha256: String
    val received = LinkedBlockingQueue<String>()
    @Volatile var peer: Socket? = null
    val peerClosed = CountDownLatch(1)
    @Volatile var welcomeVersion = Protocol.VERSION
    @Volatile var expectedToken = "T".repeat(20)
    private val fp = "f".repeat(64)

    init {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/test-desktop.p12").use { ks.load(it, "changeit".toCharArray()) }
        certSha256 = MessageDigest.getInstance("SHA-256")
            .digest(ks.getCertificate("desktop").encoded).joinToString("") { "%02x".format(it) }
        server = if (tls) {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket)
        } else ServerSocket(0)
        Thread {
            try {
                while (true) serve(server.accept())
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
    }

    val port get() = server.localPort

    private fun serve(s: Socket) {
        peer = s
        Thread {
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()
                fun send(line: String) { out.write((line + "\n").toByteArray()); out.flush() }
                while (true) {
                    val line = r.readLine() ?: break
                    received.add(line)
                    if (line.contains("\"type\":\"hello\"")) {
                        if (!line.contains("\"token\":\"$expectedToken\"")) { send("""{"type":"error","code":"auth","message":"bad token"}"""); break }
                        send("""{"type":"welcome","v":$welcomeVersion,"desktop":"Test PC","os":"windows"}""")
                        send("""{"type":"sessions","sessions":[{"pillId":"agent_codex","state":"working"}]}""")
                    }
                    if (line.contains("\"type\":\"ping\"")) send("""{"type":"pong"}""")
                }
            } catch (_: Exception) { } finally { runCatching { s.close() }; peerClosed.countDown() }
        }.apply { isDaemon = true; start() }
    }

    fun push(line: String) { peer!!.getOutputStream().apply { write((line + "\n").toByteArray()); flush() } }
    fun approvalJson(created: Long) =
        """{"type":"approval","pillId":"agent_codex","fingerprint":"$fp","tool":"Bash","command":"ls","createdAt":$created}"""
    val approvalFingerprint get() = fp
    fun dropConnection() { peer?.close() }
    override fun close() { runCatching { peer?.close() }; runCatching { server.close() } }
}

private class Recorder : LinkListener {
    val states = CopyOnWriteArrayList<LinkState>()
    val sessions = LinkedBlockingQueue<List<SessionInfo>>()
    val approvals = LinkedBlockingQueue<ApprovalRequest>()
    val resolved = LinkedBlockingQueue<String>()
    val errors = LinkedBlockingQueue<String>()
    val connected = CountDownLatch(1)
    @Volatile var desktop: String? = null
    override fun onState(state: LinkState) { states.add(state) }
    override fun onWelcome(desktopName: String, os: String) { desktop = desktopName; connected.countDown() }
    override fun onSessions(sessions: List<SessionInfo>) { this.sessions.add(sessions) }
    override fun onApproval(request: ApprovalRequest) { approvals.add(request) }
    override fun onApprovalResolved(fingerprint: String) { resolved.add(fingerprint) }
    override fun onError(code: String, message: String) { errors.add(code) }
}

class LinkClientTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private var now = System.currentTimeMillis()
    private val token = "T".repeat(20)

    @After fun cleanup() = closeables.forEach { runCatching { it.close() } }

    private fun <T : AutoCloseable> T.track(): T { closeables.add(this); return this }

    private fun payload(d: FakeDesktop, fp: String = d.certSha256) = PairingPayload("127.0.0.1", d.port, fp, token, "Test PC")

    private fun client(d: FakeDesktop, rec: Recorder, p: PairingPayload = payload(d), plain: Boolean = false, backoff: LongArray = longArrayOf(50)): LinkClient {
        val connector = if (plain) Connector { ms -> Socket().apply { connect(java.net.InetSocketAddress(p.host, p.port), ms) } } else PinnedTls.connector(p)
        return LinkClient(p, "Pixel", rec, connector, clockMs = { now }, readTimeoutMs = 2_000, pingEveryMs = 100, backoffMs = backoff)
            .also { closeables.add(AutoCloseable { it.stop() }) }
    }

    @Test fun connectsOverPinnedTlsAndReceivesSessions() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        client(d, rec).start()
        assertTrue("never connected", rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals("Test PC", rec.desktop)
        val s = rec.sessions.poll(5, TimeUnit.SECONDS)
        assertEquals("agent_codex", s!![0].pillId)
        assertTrue(rec.states.contains(LinkState.CONNECTED))
        assertTrue(d.received.poll(2, TimeUnit.SECONDS)!!.contains("\"device\":\"Pixel\""))
    }

    @Test fun refusesADesktopWithAnotherCertificate() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        client(d, rec, payload(d, fp = "00".repeat(32))).start()
        assertFalse("connected to an unpinned desktop", rec.connected.await(1500, TimeUnit.MILLISECONDS))
        assertNull(d.received.poll(200, TimeUnit.MILLISECONDS))
        assertFalse(rec.states.contains(LinkState.CONNECTED))
    }

    @Test fun neverSendsTheTokenInTheClear() {
        // A plain-text server must not be able to complete the pinned-TLS connector's handshake.
        val d = FakeDesktop(tls = false).track()
        val rec = Recorder()
        client(d, rec, payload(d)).start()
        assertFalse(rec.connected.await(1500, TimeUnit.MILLISECONDS))
        assertTrue("token leaked: ${d.received.toList()}", d.received.none { it.contains("token") })
    }

    @Test fun approvalCanBeAllowedOnceAndTheDesktopReceivesIt() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        val c = client(d, rec)
        c.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        d.push(d.approvalJson(now))
        val req = rec.approvals.poll(5, TimeUnit.SECONDS)
        assertNotNull(req)
        assertTrue(c.decide(req!!.fingerprint, allow = true))
        assertFalse("second decision for the same request", c.decide(req.fingerprint, allow = false))
        val decision = generateSequence { d.received.poll(3, TimeUnit.SECONDS) }.first { it.contains("decision") }
        assertTrue(decision.contains("\"decision\":\"allow\"") && decision.contains(req.fingerprint))
    }

    @Test fun cannotDecideAnUnknownRequest() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        val c = client(d, rec)
        c.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        assertFalse(c.decide("e".repeat(64), allow = true))
    }

    @Test fun expiredApprovalIsRefused() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        val c = client(d, rec)
        c.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        d.push(d.approvalJson(now))
        val req = rec.approvals.poll(5, TimeUnit.SECONDS)!!
        now += Protocol.APPROVAL_TTL_MS
        assertFalse(c.decide(req.fingerprint, allow = true))
        assertTrue("no decision must reach the desktop", d.received.none { it.contains("\"type\":\"decision\"") })
    }

    @Test fun desktopResolvingTheApprovalRemovesIt() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        val c = client(d, rec)
        c.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        d.push(d.approvalJson(now))
        val req = rec.approvals.poll(5, TimeUnit.SECONDS)!!
        d.push("""{"type":"approvalResolved","fingerprint":"${req.fingerprint}"}""")
        assertEquals(req.fingerprint, rec.resolved.poll(5, TimeUnit.SECONDS))
        assertFalse(c.decide(req.fingerprint, allow = true))
    }

    @Test fun reconnectsAfterTheLinkDrops() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        client(d, rec).start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        rec.sessions.poll(5, TimeUnit.SECONDS)
        d.dropConnection()
        val again = rec.sessions.poll(8, TimeUnit.SECONDS)
        assertNotNull("did not reconnect", again)
        assertTrue(rec.states.count { it == LinkState.CONNECTED } >= 2)
    }

    @Test fun wrongTokenGetsAnAuthErrorAndTheClientKeepsBackingOff() {
        val d = FakeDesktop(tls = true).track().also { it.expectedToken = "other-token-123456789" }
        val rec = Recorder()
        client(d, rec).start()
        assertEquals("auth", rec.errors.poll(5, TimeUnit.SECONDS))
        assertFalse(rec.connected.await(300, TimeUnit.MILLISECONDS))
    }

    @Test fun versionMismatchIsReportedNotAccepted() {
        val d = FakeDesktop(tls = true).track().also { it.welcomeVersion = 99 }
        val rec = Recorder()
        client(d, rec).start()
        assertEquals("version", rec.errors.poll(5, TimeUnit.SECONDS))
        assertFalse(rec.states.contains(LinkState.CONNECTED))
    }

    @Test fun sendsKeepAlivePings() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        client(d, rec).start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        val ping = generateSequence { d.received.poll(3, TimeUnit.SECONDS) }.take(10).any { it.contains("\"type\":\"ping\"") }
        assertTrue(ping)
    }

    @Test fun stopEndsTheLinkAndStaysStopped() {
        val d = FakeDesktop(tls = true).track()
        val rec = Recorder()
        val c = client(d, rec)
        c.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        c.stop() // "bye" is best effort; the desktop must at least see the connection end
        assertTrue("desktop never saw the link close", d.peerClosed.await(5, TimeUnit.SECONDS))
        assertFalse(c.decide("a".repeat(64), true))
        val deadline = System.currentTimeMillis() + 3000
        while (rec.states.lastOrNull() != LinkState.DISCONNECTED && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(LinkState.DISCONNECTED, rec.states.last())
    }
}
