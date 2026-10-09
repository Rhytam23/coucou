package com.coucou.android.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coucou.android.R
import com.coucou.android.core.OverlayChoice
import com.coucou.android.core.OverlayPolicy
import com.coucou.android.core.WishStore
import com.coucou.android.core.Pills
import com.coucou.android.ui.IslandOverlay
import com.coucou.android.link.Protocol
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

    private val prefs = context.getSharedPreferences("coucou_ui", Context.MODE_PRIVATE)

    private val choice = OverlayChoice(object : WishStore {
        override fun read() = prefs.getBoolean("overlay", false)
        // commit(): a tiny file, and it must survive the process being killed while the user is in Settings.
        override fun write(on: Boolean) { prefs.edit().putBoolean("overlay", on).commit() }
    })

    /** The user's wish for the pill over other apps (off until they turn it on). Saved when they tap. */
    var overlayWished by mutableStateOf(choice.wished); private set

    /** The system permission "display over other apps", re-read when the app comes to the front. */
    var overlayPermission by mutableStateOf(false); private set

    /** The app is on screen: it shows everything itself, so the pill stays away. */
    var inForeground = false
        set(value) {
            field = value
            if (value) overlay.hide()
        }

    private val overlay = IslandOverlay(
        context, { android.os.SystemClock.elapsedRealtimeNanos() / 1e6 },
        onAllow = { notifier.openAllow(it.fingerprint) },
        onDeny = { decide(it.fingerprint, allow = false) },
        onOpen = { notifier.openApp() },
    )

    fun overlayPermitted() = overlay.permitted()

    /** What the switch shows: wished and allowed by the system. */
    val overlayOn: Boolean get() = overlayWished && overlayPermission

    fun refreshOverlayPermission() { overlayPermission = overlay.permitted() }

    /** The user tapped the switch: remember the choice right now (see [OverlayChoice]). */
    fun useOverlay(on: Boolean) {
        choice.choose(on)
        overlayWished = on
        refreshOverlayPermission()
        if (!on) overlay.hide()
    }

    private fun overlayWanted(): Boolean {
        val why = OverlayPolicy.blocker(choice.wished, overlay.permitted(), inForeground)
        if (why != null) Log.d("CoucouOverlay", "no pill: $why")
        return why == null
    }

    /**
     * Allow was tapped on a notification before the link was back (the app had been closed): the
     * desktop offers the request again as soon as it reconnects, and the biometric prompt opens then.
     */
    var wantAllow by mutableStateOf<String?>(null)

    private val expiry = HashMap<String, Runnable>()
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
        if (p == null) { message = context.getString(R.string.msg_bad_link); return false }
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
        dropApprovals()
        wantAllow = null
        lastState.clear()
    }

    /** True if the decision was sent (or applied to the demo). Allow is gated by the biometric prompt in the UI. */
    fun decide(fingerprint: String, allow: Boolean): Boolean {
        val ok = link?.decide(fingerprint, allow) ?: false
        if (!ok) {
            message = context.getString(R.string.msg_not_pending)
            removeApproval(fingerprint) // it cannot be answered any more: no card or pill that lingers
        }
        return ok
    }

    /** Every card goes: the desktop offers a request again if it is still pending once we reconnect. */
    private fun dropApprovals() {
        overlay.hide()
        expiry.values.forEach { main.removeCallbacks(it) }
        expiry.clear()
        approvals.forEach { notifier.cancelApproval(it.fingerprint) }
        approvals = emptyList()
    }

    private fun removeApproval(fingerprint: String) {
        expiry.remove(fingerprint)?.let { main.removeCallbacks(it) }
        approvals = approvals.filter { it.fingerprint != fingerprint }
        notifier.cancelApproval(fingerprint)
        overlay.hideApproval(fingerprint)
    }

    // ── LinkListener (link thread) ───────────────────────────────────────────

    override fun onState(state: LinkState) {
        main.post {
            linkState = state
            // A card for a request we can no longer answer would only produce "no longer pending".
            if (state != LinkState.CONNECTED && mode == Mode.PAIRED) dropApprovals()
            notifier.updateOngoing(this)
        }
    }

    override fun onWelcome(desktopName: String, os: String) {
        main.post { if (mode != Mode.DEMO && desktopName.isNotBlank()) this.desktopName = desktopName }
    }

    override fun onSessions(sessions: List<SessionInfo>) {
        main.post {
            this.sessions = sessions
            for (s in sessions) {
                val prev = lastState.put(s.pillId, s.state)
                if (prev != s.state && s.state in LOUD) sounds.play(com.coucou.android.mochi.MochiConst.STATE_SOUND[s.state] ?: continue)
                if (OverlayPolicy.shouldFlash(prev, s.state) && overlayWanted()) overlay.showStatus(agentName(s.pillId), s.state, s.statusText)
            }
            lastState.keys.retainAll(sessions.map { it.pillId }.toSet())
            notifier.updateOngoing(this)
        }
    }

    override fun onApproval(request: ApprovalRequest) {
        main.post {
            approvals = approvals.filter { it.fingerprint != request.fingerprint } + request
            // One announcement only: the pill if it can show, else the heads-up notification.
            val pill = overlayWanted() && overlay.showApproval(request, agentName(request.pillId))
            notifier.showApproval(request, agentName(request.pillId), OverlayPolicy.approvalAlert(pill))
            // Offered for 120 s from now, by this phone's clock (the notification times out then too).
            expiry.remove(request.fingerprint)?.let { main.removeCallbacks(it) }
            val gone = Runnable { removeApproval(request.fingerprint) }
            expiry[request.fingerprint] = gone
            main.postDelayed(gone, Protocol.APPROVAL_TTL_MS)
        }
    }

    override fun onApprovalResolved(fingerprint: String) {
        main.post {
            removeApproval(fingerprint)
            if (wantAllow == fingerprint) wantAllow = null
        }
    }

    override fun onError(code: String, message: String) {
        main.post {
            this.message = if (code == "auth") context.getString(R.string.msg_rejected)
            else "${context.getString(R.string.msg_link_error)}: $message"
        }
    }

    private companion object {
        val LOUD = setOf(BotState.APPROVAL, BotState.QUESTION, BotState.ERROR, BotState.FINISHED)
    }
}
