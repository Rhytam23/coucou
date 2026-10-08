package com.coucou.android.link

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

interface LinkListener {
    fun onState(state: LinkState) {}
    fun onWelcome(desktopName: String, os: String) {}
    fun onSessions(sessions: List<SessionInfo>) {}
    fun onApproval(request: ApprovalRequest) {}
    fun onApprovalResolved(fingerprint: String) {}
    fun onError(code: String, message: String) {}
}

/** What the UI talks to: the real [LinkClient] or the offline [DemoLink]. */
interface DesktopLink {
    val approvals: ApprovalBook
    fun start()
    fun stop()
    /** allow/deny for a pending approval; false if unknown, expired, already decided or not connected. */
    fun decide(fingerprint: String, allow: Boolean): Boolean
}

/** Opens the (TLS) connection to the desktop. Swappable so tests can use plain sockets. */
fun interface Connector { fun connect(timeoutMs: Int): Socket }

object PinnedTls {
    fun connector(p: PairingPayload) = Connector { timeoutMs ->
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(PinnedTrustManager(p.certSha256)), SecureRandom())
        }
        val raw = Socket()
        raw.connect(InetSocketAddress(p.host, p.port), timeoutMs)
        val tls = ctx.socketFactory.createSocket(raw, p.host, p.port, true) as javax.net.ssl.SSLSocket
        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        tls.soTimeout = timeoutMs
        tls.startHandshake()
        tls
    }
}

/**
 * Background connection to one paired desktop: hello, then messages until the link drops, then
 * reconnect with backoff. Plain threads, no coroutines, no polling: the thread blocks on the
 * socket, so an idle link costs no CPU. Listener callbacks arrive on the link thread.
 */
class LinkClient(
    private val pairing: PairingPayload,
    private val deviceName: String,
    private val listener: LinkListener,
    private val connector: Connector = PinnedTls.connector(pairing),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val readTimeoutMs: Int = 60_000,
    private val pingEveryMs: Long = 20_000,
    private val backoffMs: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
) : DesktopLink {
    override val approvals = ApprovalBook(clockMs)

    @Volatile private var running = false
    /** Set by an auth or version error: retrying with the same pairing can never work. */
    @Volatile private var fatal = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var thread: Thread? = null
    private val writeLock = Any()
    /** Android forbids network I/O on the main thread, and the UI calls decide() and stop(): writes happen here. */
    @Volatile private var writer: ExecutorService? = null

    @Synchronized override fun start() {
        if (running) return
        running = true
        fatal = false
        writer = Executors.newSingleThreadExecutor { r -> Thread(r, "coucou-link-writer").apply { isDaemon = true } }
        thread = Thread(::loop, "coucou-link").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        running = false
        val s = socket
        // Sent and closed off the caller's thread; "bye" is best effort.
        writer?.let { w ->
            runCatching { w.execute { runCatching { send(ClientMsg.Bye) }; runCatching { s?.close() } } }
            w.shutdown()
        } ?: runCatching { s?.close() }
        writer = null
        thread?.interrupt()
        thread = null
    }

    /** Sends allow/deny for a pending approval. False if it is unknown, expired, decided or the link is down. */
    override fun decide(fingerprint: String, allow: Boolean): Boolean {
        val w = writer
        if (out == null || w == null) return false
        val request = approvals.claim(fingerprint) ?: return false
        return try {
            w.execute {
                try {
                    send(ClientMsg.Decision(request.fingerprint, allow))
                } catch (_: IOException) {
                    approvals.add(request) // not sent: keep it so the user can retry while it is still valid
                    listener.onError("send", "could not reach the desktop")
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            approvals.add(request)
            false
        }
    }

    private fun send(m: ClientMsg) {
        val o = out ?: throw IOException("not connected")
        synchronized(writeLock) {
            o.write((Wire.encode(m) + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        }
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            listener.onState(LinkState.CONNECTING)
            var connectedOnce = false
            try {
                val s = connector.connect(10_000)
                socket = s
                s.soTimeout = readTimeoutMs
                s.tcpNoDelay = true
                out = s.getOutputStream()
                send(ClientMsg.Hello(Protocol.VERSION, pairing.token, deviceName))
                val pinger = startPinger()
                try {
                    read(BufferedInputStream(s.getInputStream())) { connectedOnce = true }
                } finally {
                    pinger.interrupt()
                }
            } catch (_: IOException) {
                // fall through to reconnect
            } catch (_: InterruptedException) {
                // stop() was called
            } finally {
                out = null
                runCatching { socket?.close() }
                socket = null
                approvals.clear()
            }
            if (fatal) running = false
            if (!running) break
            listener.onState(LinkState.DISCONNECTED)
            if (connectedOnce) attempt = 0
            val wait = backoffMs[minOf(attempt, backoffMs.size - 1)]
            attempt++
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
        listener.onState(LinkState.DISCONNECTED)
    }

    private fun startPinger() = Thread({
        try {
            while (true) {
                Thread.sleep(pingEveryMs)
                send(ClientMsg.Ping)
            }
        } catch (_: Exception) {
            // interrupted or link closed: the reader notices on its own
        }
    }, "coucou-link-ping").apply { isDaemon = true; start() }

    private fun read(input: InputStream, onFirstMessage: () -> Unit) {
        var first = true
        while (running) {
            val line = readLine(input) ?: return
            val msg = Wire.decodeServer(line) ?: continue
            if (first) { first = false; onFirstMessage() }
            when (msg) {
                is ServerMsg.Welcome -> {
                    if (msg.version != Protocol.VERSION) { listener.onError("version", "desktop speaks v${msg.version}"); fatal = true; return }
                    listener.onState(LinkState.CONNECTED)
                    listener.onWelcome(msg.desktopName, msg.os)
                }
                is ServerMsg.Sessions -> listener.onSessions(msg.sessions)
                is ServerMsg.Approval -> { approvals.add(msg.request); listener.onApproval(msg.request) }
                is ServerMsg.ApprovalResolved -> { approvals.resolve(msg.fingerprint); listener.onApprovalResolved(msg.fingerprint) }
                ServerMsg.Pong -> {}
                is ServerMsg.Error -> { listener.onError(msg.code, msg.message); if (msg.code == "auth") { fatal = true; return } }
            }
        }
    }

    /** Reads one '\n'-terminated line; null at end of stream. Throws if a line passes the size limit. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8)
            if (buf.size() >= Protocol.MAX_LINE_BYTES) throw IOException("line too long")
            buf.write(b)
        }
    }
}
