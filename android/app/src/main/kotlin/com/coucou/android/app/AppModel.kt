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
import com.coucou.android.core.ChatTab
import com.coucou.android.core.ChatTabState
import com.coucou.android.core.Decision
import com.coucou.android.core.DecisionLog
import com.coucou.android.core.KeyValueStore
import com.coucou.android.core.IslandPlan
import com.coucou.android.core.OverlayChoice
import com.coucou.android.core.StatusPolicy
import com.coucou.android.core.UserSettings
import com.coucou.android.core.OverlayPolicy
import com.coucou.android.core.WishStore
import com.coucou.android.core.Pills
import com.coucou.android.ui.IslandOverlay
import com.coucou.android.link.Protocol
import com.coucou.android.link.ChatModel
import com.coucou.android.core.PairingScan
import com.coucou.android.core.ScanDecision
import com.coucou.android.core.ChatHistory
import com.coucou.android.core.ChatMessage
import com.coucou.android.core.ChatModels
import com.coucou.android.core.ChatSession
import com.coucou.android.link.AddressFinder
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.Cancelable
import com.coucou.android.link.Discovery
import com.coucou.android.link.DiscoveryPolicy
import com.coucou.android.link.DiscoveryState
import com.coucou.android.link.PinnedProbe
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

    // ── Finding the computer again when its address changed (docs/ANDROID_LINK.md, "Finding the computer") ──
    /** What the search for the computer is doing, for the status line and the hint. */
    var discovery by mutableStateOf(DiscoveryState.IDLE); private set
    /** The pairing the running link uses (with the address in force); the token stays in memory only as long as the link does. */
    @Volatile private var currentPairing: PairingPayload? = null
    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "coucou-probe").apply { isDaemon = true } }
    private val netWatch = NetworkWatch(context) { up -> main.post { onNetworkChanged(up) } }
    private val finder = AddressFinder(
        source = NsdDiscovery(context), probe = PinnedProbe,
        pairing = { currentPairing }, allowed = { mayDiscover() },
        onAddress = { host, port -> main.post { onAddressFound(host, port) } },
        onState = { st -> main.post { discovery = st } },
        scheduler = { delay, task -> val r = Runnable { task() }; main.postDelayed(r, delay); Cancelable { main.removeCallbacks(r) } },
        runner = { task -> probeExecutor.execute { task() } },
    )

    private fun mayDiscover() = DiscoveryPolicy.mayDiscover(
        paired = currentPairing != null, demo = mode == Mode.DEMO, wifiUp = netWatch.wifiUp, linkConnected = linkState == LinkState.CONNECTED,
    )

    private val prefs = context.getSharedPreferences("coucou_ui", Context.MODE_PRIVATE)
    private val kv = object : KeyValueStore {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
        override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
        override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
        override fun put(key: String, value: Any) {
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }.apply()
        }
    }

    /** What the user chose in Settings (sound, notices, quiet hours). */
    var settings by mutableStateOf(UserSettings.load(kv)); private set

    fun updateSettings(next: UserSettings) {
        settings = next
        UserSettings.save(kv, next)
        applySound()
    }

    private fun applySound() {
        sounds.enabled = settings.soundOn
        sounds.volume = settings.volume
    }

    init { applySound() }

    /** The phone's own list of decisions. Never sent anywhere. */
    private val decisionLog = DecisionLog(kv)
    var decisions by mutableStateOf(decisionLog.all()); private set

    fun clearDecisions() {
        decisionLog.clear()
        decisions = emptyList()
    }

    /** Also used by the debug receiver, to fill the history without a real decision. */
    fun recordDecision(d: Decision) {
        decisionLog.add(d)
        decisions = decisionLog.all()
    }

    // ── Chat through the computer (docs/ANDROID_LINK.md). The UI comes in the next step. ──────────

    private val chatHistory = ChatHistory(ChatFile(java.io.File(context.filesDir, "chat-history.json")))
    private val chatSession = ChatSession(chatHistory.load())

    /** What the user reads: this phone's own copy, kept in a private file. */
    var chatMessages by mutableStateOf(chatSession.messages); private set

    /** An answer is being waited for. */
    var chatBusy by mutableStateOf(false); private set

    /** The computer offers chat to this phone right now (its switch is on and we are connected). */
    var chatOffered by mutableStateOf(false); private set

    /** The models the user allowed on the computer; the keys never leave it. */
    var chatModels by mutableStateOf<List<ChatModel>>(emptyList()); private set

    var chatModel by mutableStateOf<String?>(prefs.getString("chat_model", null)); private set

    /** The computer offers session details (steps, last message, project folder, colour) to this phone. */
    var detailsOffered by mutableStateOf(false); private set

    /** Debug builds only (see [debugSeedChat]): pretends chat is available so the screen can be looked at. */
    private var chatForced = false

    /** Debug builds only: shows one state of the Chat tab without a computer (see [debugChatState]). */
    private var chatStateOverride by mutableStateOf<ChatTabState?>(null)

    /** What the Chat tab shows: not paired, not reachable, chat off on the computer, no model allowed, or ready. */
    val chatTabState: ChatTabState get() = chatStateOverride ?: ChatTab.state(
        paired = mode == Mode.PAIRED, connected = linkState == LinkState.CONNECTED, offered = chatOffered,
        modelCount = chatModels.size, forcedReady = chatForced,
    )

    internal fun debugChatState(state: ChatTabState?) { chatStateOverride = state }

    /** Chat is usable: offered, at least one model allowed, connected. */
    val chatAvailable: Boolean get() = chatStateOverride?.let { it == ChatTabState.READY } ?: (chatForced || (chatOffered && chatModels.isNotEmpty() && linkState == LinkState.CONNECTED))

    /** Debug receiver only: fake models and a sample conversation, no computer needed. Nothing can be sent in this mode. */
    internal fun debugSeedChat(models: List<ChatModel>, messages: List<ChatMessage>) {
        chatForced = true
        chatOffered = true
        chatModels = models
        chatModel = ChatModels.pick(models, chatModel)
        chatSession.replace(messages)
        chatChanged(save = false)
    }

    private fun chatChanged(save: Boolean) {
        chatMessages = chatSession.messages
        chatBusy = chatSession.running != null
        if (save) chatHistory.save(chatSession.messages)
    }

    fun chatSelect(id: String) {
        if (chatModels.any { it.id == id }) {
            chatModel = id
            prefs.edit().putString("chat_model", id).apply()
        }
    }

    /** Asks the computer again which models are allowed (when the Chat screen opens). */
    fun chatRefreshModels() { link?.chatModels() }

    /** Null when sent, else why not (for a message under the box). */
    fun chatSend(text: String): ChatSession.Refusal? {
        val sent = chatSession.send(text, chatModel, connected = chatAvailable)
        return when (sent) {
            is ChatSession.Sent.No -> sent.why
            is ChatSession.Sent.Ok -> {
                chatChanged(save = true)
                if (link?.chatSend(sent.msg.id, sent.msg.model, sent.msg.text) != true) {
                    chatSession.onDisconnected()
                    chatChanged(save = true)
                }
                null
            }
        }
    }

    fun chatCancel() {
        chatSession.cancel()?.let { link?.chatCancel(it) }
        chatChanged(save = true)
    }

    /** Clear: forgets the history here and on the computer. */
    fun chatClear() {
        chatSession.cancel()?.let { link?.chatCancel(it) }
        chatSession.clear()
        chatHistory.clear()
        link?.chatReset()
        chatChanged(save = false)
    }

    private fun chatLinkLost() {
        detailsOffered = false
        chatForced = false
        chatOffered = false
        chatModels = emptyList()
        if (chatSession.running != null) {
            chatSession.onDisconnected()
            chatChanged(save = true)
        }
    }

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
            if (value) {
                overlay.hide()
                notifier.cancelAllStatus()
            } else {
                refreshIsland() // the user left the app: an agent at work shows on the island at once
            }
        }

    private val overlay = IslandOverlay(
        context, { android.os.SystemClock.elapsedRealtimeNanos() / 1e6 },
        onAllow = { notifier.openAllow(it) },
        onDeny = { decide(it, allow = false) },
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
        refreshIsland()
    }

    private fun islandAllowed(): Boolean {
        val connected = mode != Mode.PAIRED || linkState == LinkState.CONNECTED
        val why = OverlayPolicy.blocker(choice.wished, overlay.permitted(), inForeground)
            ?: if (!connected) "not connected to a computer" else null
        if (why != null) Log.d("CoucouOverlay", "no island: $why")
        return why == null
    }

    /**
     * What the island shows right now (an agent at work, a question, a request), from the current
     * sessions and requests. When it may not show at all it is removed completely.
     */
    private fun refreshIsland() {
        if (!islandAllowed()) {
            overlay.hide()
            return
        }
        overlay.setActive(IslandPlan.active(sessions, approvals, allowed = true, this::agentName))
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

    /**
     * A pairing link waiting for the user's OK: read from the QR code, or opened from the camera app. Held in
     * memory only while the question is on screen; it is never logged and goes nowhere but into [pair].
     */
    var pairRequest by mutableStateOf<String?>(null); private set

    /** True if [text] is a Coucou pairing link (the user is now asked to confirm); false for any other text. */
    fun requestPairing(text: String?): Boolean {
        val d = PairingScan.decide(text)
        if (d !is ScanDecision.Pairing) return false
        pairRequest = d.link
        return true
    }

    fun confirmPairing() {
        val link = pairRequest ?: return
        pairRequest = null
        pair(link)
    }

    fun cancelPairing() { pairRequest = null }

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
        currentPairing = p
        link = LinkClient(p, Build.MODEL ?: "Android", this).also { it.start() }
        netWatch.start()
        LinkService.start(context)
    }

    /** The saved address is replaced; the token and the pinned certificate are the same. The link restarts at once. */
    private fun useAddress(host: String, port: Int) {
        val p = currentPairing ?: return
        val q = Discovery.withAddress(p, host, port)
        store.savePairing(q)
        currentPairing = q
        link?.stop()
        link = LinkClient(q, Build.MODEL ?: "Android", this).also { it.start() }
    }

    /** A computer matching the paired fingerprint answered with the pinned certificate (checked by a bare TLS handshake). */
    private fun onAddressFound(host: String, port: Int) {
        if (mode != Mode.PAIRED || currentPairing == null) return
        Log.d("CoucouDiscovery", "address updated")
        useAddress(host, port)
    }

    private fun onNetworkChanged(wifiUp: Boolean) {
        if (mode != Mode.PAIRED) return
        if (!wifiUp) { finder.stop(); return } // no Wi-Fi: nothing to scan
        link?.retryNow() // do not wait out the backoff on a new network
        if (mayDiscover()) finder.networkChanged()
    }

    /**
     * "Enter address manually": a host or host:port typed by the user. The pairing, the token and the pinned
     * certificate are unchanged, so a wrong or hostile address still cannot get past the certificate check.
     */
    fun setAddress(text: String): Boolean {
        val (host, port) = Discovery.parseAddress(text) ?: return false
        if (currentPairing == null) return false
        useAddress(host, port)
        return true
    }

    /** Debug only (see DebugPillReceiver): pretend the computer's address changed, so the search has to repair it. */
    fun debugAddressChanged() {
        if (currentPairing == null) return
        useAddress("192.0.2.77", currentPairing!!.port) // a documentation address that never answers
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
        finder.stop()
        netWatch.stop()
        currentPairing = null
        link?.stop()
        link = null
        mode = Mode.NONE
        linkState = LinkState.DISCONNECTED
        desktopName = null
        sessions = emptyList()
        dropApprovals()
        chatLinkLost()
        wantAllow = null
        lastState.clear()
        notifier.cancelAllStatus()
    }

    /** True if the decision was sent (or applied to the demo). Allow is gated by the biometric prompt in the UI. */
    fun decide(fingerprint: String, allow: Boolean): Boolean {
        val request = approvals.firstOrNull { it.fingerprint == fingerprint }
        val ok = link?.decide(fingerprint, allow) ?: false
        if (ok && request != null) {
            recordDecision(Decision(agentName(request.pillId), request.tool, request.command, allow, System.currentTimeMillis()))
        }
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
        refreshIsland()
    }

    private fun removeApproval(fingerprint: String) {
        expiry.remove(fingerprint)?.let { main.removeCallbacks(it) }
        approvals = approvals.filter { it.fingerprint != fingerprint }
        notifier.cancelApproval(fingerprint)
        refreshIsland()
    }

    // ── LinkListener (link thread) ───────────────────────────────────────────

    override fun onState(state: LinkState) {
        main.post {
            linkState = state
            if (state == LinkState.CONNECTED) finder.connected() // found it (or never lost it): stop looking
            // A card for a request we can no longer answer would only produce "no longer pending".
            if (state != LinkState.CONNECTED && mode == Mode.PAIRED) dropApprovals()
            if (state != LinkState.CONNECTED) chatLinkLost()
            refreshIsland()
            notifier.updateOngoing(this)
        }
    }

    override fun onWelcome(desktopName: String, os: String) {
        main.post { if (mode != Mode.DEMO && desktopName.isNotBlank()) this.desktopName = desktopName }
    }

    override fun onCaps(caps: Set<String>) {
        main.post {
            chatOffered = Protocol.CAP_CHAT in caps
            detailsOffered = Protocol.CAP_DETAILS in caps
        }
    }

    override fun onChatModels(models: List<ChatModel>) {
        main.post {
            chatModels = models
            chatModel = ChatModels.pick(models, chatModel)
        }
    }

    override fun onChatDelta(id: String, text: String) {
        main.post { chatSession.onDelta(id, text); chatChanged(save = false) }
    }

    override fun onChatDone(id: String, text: String?) {
        main.post { chatSession.onDone(id, text); chatChanged(save = true) }
    }

    override fun onChatError(id: String, reason: String, message: String) {
        main.post { chatSession.onError(id, reason); chatChanged(save = true) }
    }

    override fun onSessions(sessions: List<SessionInfo>) {
        main.post {
            this.sessions = sessions
            val minute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            for (s in sessions) {
                val prev = lastState.put(s.pillId, s.state)
                val name = agentName(s.pillId)
                val sound = com.coucou.android.mochi.MochiConst.STATE_SOUND[s.state]
                if (sound != null && StatusPolicy.stateSoundWanted(prev, s.state, settings)) sounds.play(sound)
                val a = StatusPolicy.announce(prev, s.state, settings, minute, inForeground)
                if (a.sound && sound != null) sounds.play(sound)
                if (a.notify) notifier.showStatus(s.pillId, name, s.state, s.statusText)
                IslandPlan.flash(prev, s, settings, minute, islandAllowed(), this::agentName)?.let { overlay.flash(it) }
                // The agent moved on: the notice about the last state is stale.
                if (prev != s.state && s.state !in NEWSWORTHY) notifier.cancelStatus(s.pillId)
            }
            lastState.keys.retainAll(sessions.map { it.pillId }.toSet())
            refreshIsland()
            notifier.updateOngoing(this)
        }
    }

    override fun onApproval(request: ApprovalRequest) {
        main.post {
            approvals = approvals.filter { it.fingerprint != request.fingerprint } + request
            // One announcement only: the island if it can show, else the heads-up notification.
            refreshIsland()
            notifier.showApproval(request, agentName(request.pillId), OverlayPolicy.approvalAlert(overlay.isShowingRequest()))
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

    override fun onConnectFailed(consecutive: Int) {
        // The saved address failed: now (and only now) look for the computer on the network.
        if (consecutive >= DiscoveryPolicy.FAILURES_BEFORE_DISCOVERY) main.post { if (mayDiscover()) finder.request() }
    }

    override fun onError(code: String, message: String) {
        main.post {
            this.message = if (code == "auth") context.getString(R.string.msg_rejected)
            else "${context.getString(R.string.msg_link_error)}: $message"
        }
    }

    private companion object {
        val NEWSWORTHY = setOf(BotState.QUESTION, BotState.ERROR, BotState.FINISHED, BotState.RATELIMIT)
    }
}
