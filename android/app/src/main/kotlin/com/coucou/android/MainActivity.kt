package com.coucou.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coucou.android.app.AppModel
import com.coucou.android.app.BiometricGate
import com.coucou.android.app.CoucouApp
import com.coucou.android.app.Mode
import com.coucou.android.app.Notifications
import com.coucou.android.core.Pills
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.LinkState
import com.coucou.android.link.PairingPayload
import com.coucou.android.mochi.BotEmote
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import com.coucou.android.ui.CoucouCard
import com.coucou.android.ui.CoucouTheme
import com.coucou.android.core.HomePanel
import com.coucou.android.ui.AgentCard
import com.coucou.android.ui.ChatEntry
import com.coucou.android.ui.DesignScreen
import com.coucou.android.ui.PairConfirm
import com.coucou.android.ui.ScanScreen
import com.coucou.android.ui.SessionScreen
import com.coucou.android.ui.ChatScreen
import com.coucou.android.ui.AgentChipRow
import com.coucou.android.ui.MochiTouch
import com.coucou.android.ui.HistoryScreen
import com.coucou.android.ui.SettingsScreen
import com.coucou.android.ui.StatusColors
import com.coucou.android.ui.Gap
import com.coucou.android.ui.Gutter
import com.coucou.android.ui.GearButton
import com.coucou.android.ui.ScreenTitle
import com.coucou.android.ui.SectionTitle
import com.coucou.android.ui.linkDotColor
import com.coucou.android.ui.linkStatusText

private enum class Screen { HOME, GALLERY, SETTINGS, HISTORY, CHAT, SESSION, SCAN, DESIGN }

class MainActivity : ComponentActivity() {
    private val model get() = (application as CoucouApp).model
    private var screen by mutableStateOf(Screen.HOME)
    /** The session whose details are open. */
    private var detailPill by mutableStateOf<String?>(null)
    private var confirming = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        model.resume()
        setContent {
            CoucouTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                        BackHandler(enabled = screen != Screen.HOME) {
                            screen = when (screen) {
                                Screen.HISTORY, Screen.GALLERY -> Screen.SETTINGS
                                Screen.DESIGN -> Screen.GALLERY
                                else -> Screen.HOME
                            }
                        }
                        when (screen) {
                            Screen.SCAN -> ScanScreen(
                                onBack = { screen = Screen.HOME },
                                // True if it was a pairing code: the camera is released and the user is asked to confirm.
                                onText = { text -> model.requestPairing(text).also { if (it) screen = Screen.HOME } },
                            )
                            Screen.CHAT -> ChatScreen(model, onBack = { screen = Screen.HOME })
                            Screen.SESSION -> SessionScreen(model, detailPill.orEmpty(), onBack = { screen = Screen.HOME })
                            Screen.GALLERY -> Gallery(onBack = { screen = Screen.SETTINGS }, onDesign = { screen = Screen.DESIGN })
                            Screen.DESIGN -> DesignScreen(onBack = { screen = Screen.GALLERY })
                            Screen.SETTINGS -> SettingsScreen(
                                model, onBack = { screen = Screen.HOME }, onHistory = { screen = Screen.HISTORY },
                                onGallery = { screen = Screen.GALLERY }, onOverlay = ::setOverlay,
                            )
                            Screen.HISTORY -> HistoryScreen(model, onBack = { screen = Screen.SETTINGS })
                            Screen.HOME -> Home(
                                model, onApprove = ::approve,
                                onSettings = { screen = Screen.SETTINGS }, onOverlay = ::setOverlay,
                                onChat = { screen = Screen.CHAT },
                                onSession = { detailPill = it; screen = Screen.SESSION },
                                onScan = { screen = Screen.SCAN },
                            )
                        }
                        // A scanned code or a link from the camera app: the user decides before anything is paired.
                        model.pairRequest?.let { PairConfirm(model, it) }
                    }
                }
            }
        }
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        model.inForeground = true
        model.refreshOverlayPermission() // maybe granted meanwhile, from this app's switch or Android's settings
    }

    override fun onStop() {
        model.inForeground = false
        super.onStop()
    }

    /**
     * The pill over other apps needs a one-time system permission. The choice is saved first: even if
     * Android recreates the app while the user is in Settings, it is remembered, and the pill works as
     * soon as the permission is there.
     */
    private fun setOverlay(on: Boolean) {
        model.useOverlay(on)
        if (on && !model.overlayPermitted()) {
            model.message = getString(R.string.msg_overlay_permission)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** A coucou://pair link (from the camera app or a browser) or a tap on an approval notification. */
    private fun handle(i: Intent?) {
        i ?: return
        Log.d("CoucouLaunch", "MainActivity intent: action=${i.action} allow=${i.getBooleanExtra(Notifications.EXTRA_ALLOW, false)} data=${i.data != null}")
        i.data?.let { uri ->
            if (uri.scheme == "coucou") {
                // Opened from the camera app or a browser: never paired without the user's OK.
                if (!model.requestPairing(uri.toString())) model.message = getString(R.string.msg_bad_link)
                i.data = null // handled once: a rotation must not ask again
            }
        }
        val fp = i.getStringExtra(Notifications.EXTRA_FP) ?: return
        if (i.getBooleanExtra(Notifications.EXTRA_ALLOW, false)) {
            i.removeExtra(Notifications.EXTRA_ALLOW)
            val request = model.approvals.firstOrNull { it.fingerprint == fp }
            when {
                request != null -> approve(request)
                // The app was closed: the desktop offers the request again once the link is back.
                model.linkState != LinkState.CONNECTED -> model.wantAllow = fp
                else -> model.message = getString(R.string.msg_not_pending)
            }
        }
    }

    /** Allow: only after the biometric / screen-lock check. */
    private fun approve(r: ApprovalRequest) {
        if (confirming) return // a second tap must not open a second prompt
        confirming = true
        BiometricGate.confirm(
            this, getString(R.string.action_allow), "${r.tool}: ${r.command}", getString(R.string.msg_need_lock),
            onSuccess = { confirming = false; model.decide(r.fingerprint, allow = true) },
            onFail = { confirming = false; if (it.isNotBlank()) model.message = it },
        )
    }
}

@Composable
private fun Home(model: AppModel, onApprove: (ApprovalRequest) -> Unit, onSettings: () -> Unit, onOverlay: (Boolean) -> Unit, onChat: () -> Unit, onSession: (String) -> Unit, onScan: () -> Unit) {
    val engines = remember { HashMap<String, MochiEngine>() }
    val miniEngines = remember { HashMap<String, MochiEngine>() }
    val touches = remember { HashMap<String, MochiTouch>() }
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Allow tapped on a notification before the link was back: open the prompt once the request is here.
    LaunchedEffect(model.approvals, model.wantAllow) {
        val fp = model.wantAllow ?: return@LaunchedEffect
        model.approvals.firstOrNull { it.fingerprint == fp }?.let {
            model.wantAllow = null
            onApprove(it)
        }
    }

    // The PC's Home panel: the agent that matters in a big card, the others as pills.
    val focus = HomePanel.focus(model.sessions, selected)
    val key = focus?.pillId ?: HOME_KEY
    val engine = engines.getOrPut(key) { MochiEngine(clock, sound = model.sounds) }
    val touch = touches.getOrPut(key) { MochiTouch(engine, model.sounds) { model.sessions.firstOrNull { it.pillId == key }?.state ?: BotState.IDLE } }
    val state = focus?.state ?: BotState.IDLE
    if (engine.state != state && !touch.dizzy) engine.setState(state)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = Gutter),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        item { Header(model, onSettings) }
        model.message?.let { msg ->
            item {
                CoucouCard {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.message = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }

        // What is waiting for an answer comes first.
        items(model.approvals, key = { it.fingerprint }) { r -> ApprovalCard(model, r, onApprove) }

        val pair = HomePanel.link(model.isPaired, model.mode == Mode.DEMO, hasApproval = model.approvals.isNotEmpty())
        // Header, [message], approvals, the agent card, then the pairing card.
        val firstApproval = 1 + (if (model.message != null) 1 else 0)
        val pairIndex = firstApproval + model.approvals.size + 1
        item {
            AgentCard(
                focus, engine, touch.modifier, pair,
                onDetails = if (focus != null && HomePanel.hasDetails(focus)) ({ onSession(focus.pillId) }) else null,
                onLink = {
                    when (pair) {
                        HomePanel.Link.PAIR -> scope.launch { listState.animateScrollToItem(pairIndex) }
                        HomePanel.Link.APPROVAL -> scope.launch { listState.animateScrollToItem(firstApproval) }
                        HomePanel.Link.NONE -> {}
                    }
                },
            )
        }
        if (model.mode == Mode.NONE) item { PairCard(model, onScan) }

        val rows = HomePanel.rows(HomePanel.others(model.sessions, focus))
        if (rows.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.sessions_title)) }
            items(rows, key = { row -> row.first().pillId }) { row ->
                AgentChipRow(
                    row,
                    engineFor = { s ->
                        miniEngines.getOrPut(s.pillId) {
                            MochiEngine(clock).apply {
                                isMini = true
                                bodyColor = HomePanel.colorHex(s)?.let { HomePanel.rgb(it) }
                            }
                        }.also {
                            it.bodyColor = HomePanel.colorHex(s)?.let { hex -> HomePanel.rgb(hex) }
                            if (it.state != s.state) it.setState(s.state)
                        }
                    },
                    selected = focus?.pillId, onPick = { selected = it.pillId },
                )
            }
        }

        // Chat through the computer, only when the computer offers it (or there is a conversation to read).
        if (model.chatAvailable || model.chatMessages.isNotEmpty()) item { ChatEntry(onChat) }

        // Only when the switch is on but Android still refuses: the one thing Home must say about it.
        if (model.overlayWished && !model.overlayPermission) item { OverlayHint(onOverlay) }
        item { Spacer(Modifier.height(Gutter)) }
    }
}

private const val HOME_KEY = "home"

@Composable
private fun Header(model: AppModel, onSettings: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(linkDotColor(model)))
                Spacer(Modifier.width(8.dp))
                Text(linkStatusText(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Gap))
        GearButton(stringResource(R.string.settings_title), onSettings)
    }
}

@Composable
private fun PairCard(model: AppModel, onScan: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val notALink = stringResource(R.string.msg_bad_link)
    fun pairWith(link: String) {
        if (PairingPayload.parse(link) != null) model.pair(link) else model.message = notALink
    }
    CoucouCard {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pair_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onScan, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
                Text(stringResource(R.string.scan_button), maxLines = 1)
            }
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pair_paste)) }, singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedButton(onClick = { pairWith(text) }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
                Text(stringResource(R.string.pair_button), maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty().trim()
                    text = pasted
                    if (pasted.isNotEmpty()) pairWith(pasted)
                },
                Modifier.fillMaxWidth().height(48.dp), shape = CircleShape,
            ) { Text(stringResource(R.string.pair_clipboard), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { model.startDemo() }) { Text(stringResource(R.string.demo_try)) }
        }
    }
}

@Composable
private fun ApprovalCard(model: AppModel, r: ApprovalRequest, onApprove: (ApprovalRequest) -> Unit) {
    CoucouCard(emphasis = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.agentName(r.pillId), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            // The command exactly as the agent will run it, in the website's code style.
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(r.tool, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    r.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApprove(r) }, Modifier.weight(1f).height(48.dp), shape = CircleShape) {
                    Text(stringResource(R.string.action_allow))
                }
                OutlinedButton(
                    onClick = { model.decide(r.fingerprint, allow = false) },
                    Modifier.weight(1f).height(48.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.action_deny)) }
            }
            Text(stringResource(R.string.approval_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverlayHint(onOverlay: (Boolean) -> Unit) {
    CoucouCard {
        Row(Modifier.padding(start = Gutter, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.overlay_missing), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOverlay(true) }) { Text(stringResource(R.string.overlay_allow), maxLines = 1) }
        }
    }
}

/** Every state and emote, drawn live. Handy to check the port by eye. */
@Composable
private fun Gallery(onBack: () -> Unit, onDesign: () -> Unit) {
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }
    val states = remember { BotState.entries.map { s -> s to MochiEngine(clock).apply { setState(s, force = true) } } }
    val emotes = remember { BotEmote.entries.map { e -> e to MochiEngine(clock).apply { setPermanentEmote(e) } } }
    Column(Modifier.padding(horizontal = Gutter)) {
        ScreenTitle(stringResource(R.string.gallery), onBack)
        TextButton(onClick = onDesign) { Text(stringResource(R.string.design_entry)) }
        Spacer(Modifier.height(Gap))
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(Gap), verticalArrangement = Arrangement.spacedBy(Gap)) {
            gridItems(states) { (s, e) -> GalleryCell(s.key, e) }
            gridItems(emotes) { (m, e) -> GalleryCell(m.key, e) }
        }
    }
}

@Composable
private fun GalleryCell(label: String, engine: MochiEngine) {
    Box(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MochiView(engine, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
