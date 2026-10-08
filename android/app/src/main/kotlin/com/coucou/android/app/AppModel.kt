package com.coucou.android.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coucou.android.core.Pills
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.DemoLink
import com.coucou.android.link.DesktopLink
import com.coucou.android.link.LinkClient
import com.coucou.android.link.LinkListener
import com.coucou.android.link.LinkState
import com.coucou.android.link.PairingPayload
import com.coucou.android.link.SessionInfo
import com.coucou.android.sound.SoundPlayer
import com.coucou.android.mochi.BotState

enum class Mode { NONE, DEMO, PAIRED }

/** The one place the UI, the service and the notification actions share. Created by [CoucouApp]. */
class AppModel(private val context: Context) : LinkListener {
    private val main = Handler(Looper.getMainLooper())
    private val store = SecureStore(context)
    val sounds = SoundPlayer(context)
    val notifier = Notifications(context)

    var mode by mutableStateOf(Mode.NONE); private set
    var linkState by mutableStateOf(LinkState.DISCONNECTED); private set
    var desktopName by mutableStateOf<String?>(null); private set
    var sessions by mutableStateOf<List<SessionInfo>>(emptyList()); private set
    var approvals by mutableStateOf<List<ApprovalRequest>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)

    private var link: DesktopLink? = null
    private val lastState = HashMap<String, BotState>()

    /** The name the user knows the agent by: the session's own name, else the pill catalog. */
    fun agentName(pillId: String): String =
        sessions.firstOrNull { it.pillId == pillId }?.agent?.takeIf { it.isNotBlank() } ?: Pills.byId(pillId)?.name ?: "An agent"

    val isPaired get() = store.loadPairing() != null

    /** Called at launch and by the service: reconnects to the stored desktop, if any. */
    fun resume() {
        if (mode == Mode.DEMO || link != null) return
        val p = store.loadPairing() ?: return
        connect(p)
    }

    fun pair(text: String): Boolean {
        val p = PairingPayload.parse(text)
        if (p == null) { message = "That is not a Coucou pairing link."; return false }
        stopLink()
        store.savePairing(p)
        connect(p)
        return true
    }

    private fun connect(p: PairingPayload) {
        mode = Mode.PAIRED
        desktopName = p.desktopName.ifBlank { p.host }
        link = LinkClient(p, Build.MODEL ?: "Android", this).also { it.start() }
        LinkService.start(context)
    }

    fun startDemo() {
        stopLink()
        mode = Mode.DEMO
        link = DemoLink(this).also { it.start() }
    }

    /** Forgets the desktop: closes the link and deletes the stored secret. */
    fun unpair() {
        stopLink()
        store.clearPairing()
        LinkService.stop(context)
    }

    fun stopDemo() = stopLink()

    private fun stopLink() {
        link?.stop()
        link = null
        mode = Mode.NONE
        linkState = LinkState.DISCONNECTED
        desktopName = null
        sessions = emptyList()
        approvals.forEach { notifier.cancelApproval(it.fingerprint) }
        approvals = emptyList()
        lastState.clear()
    }

    /** True if the decision was sent (or applied to the demo). Allow is gated by the biometric prompt in the UI. */
    fun decide(fingerprint: String, allow: Boolean): Boolean {
        val ok = link?.decide(fingerprint, allow) ?: false
        if (!ok) message = "That request is no longer pending."
        return ok
    }

    // ── LinkListener (link thread) ───────────────────────────────────────────

    override fun onState(state: LinkState) { main.post { linkState = state; notifier.updateOngoing(this) } }

    override fun onWelcome(desktopName: String, os: String) {
        main.post { if (mode != Mode.DEMO && desktopName.isNotBlank()) this.desktopName = desktopName }
    }

    override fun onSessions(sessions: List<SessionInfo>) {
        main.post {
            this.sessions = sessions
            for (s in sessions) {
                val prev = lastState.put(s.pillId, s.state)
                if (prev != s.state && s.state in LOUD) sounds.play(com.coucou.android.mochi.MochiConst.STATE_SOUND[s.state] ?: continue)
            }
            lastState.keys.retainAll(sessions.map { it.pillId }.toSet())
            notifier.updateOngoing(this)
        }
    }

    override fun onApproval(request: ApprovalRequest) {
        main.post {
            approvals = approvals.filter { it.fingerprint != request.fingerprint } + request
            notifier.showApproval(request, agentName(request.pillId))
        }
    }

    override fun onApprovalResolved(fingerprint: String) {
        main.post {
            approvals = approvals.filter { it.fingerprint != fingerprint }
            notifier.cancelApproval(fingerprint)
        }
    }

    override fun onError(code: String, message: String) {
        main.post { this.message = if (code == "auth") "The desktop rejected this phone. Pair again." else "Link error: $message" }
    }

    private companion object {
        val LOUD = setOf(BotState.APPROVAL, BotState.QUESTION, BotState.ERROR, BotState.FINISHED)
    }
}
