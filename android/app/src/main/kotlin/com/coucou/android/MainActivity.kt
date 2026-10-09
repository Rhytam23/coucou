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
import androidx.compose.material3.LinearProgressIndicator
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
import com.coucou.android.core.Summary
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.LinkState
import com.coucou.android.link.PairingPayload
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotEmote
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import com.coucou.android.ui.CoucouCard
import com.coucou.android.ui.CoucouTheme
import com.coucou.android.ui.MochiTouch
import com.coucou.android.ui.HistoryScreen
import com.coucou.android.ui.SettingsScreen
import com.coucou.android.ui.StatusColors

private enum class Screen { HOME, GALLERY, SETTINGS, HISTORY }

class MainActivity : ComponentActivity() {
    private val model get() = (application as CoucouApp).model
    private var screen by mutableStateOf(Screen.HOME)
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
                            screen = if (screen == Screen.HISTORY) Screen.SETTINGS else Screen.HOME
                        }
                        when (screen) {
                            Screen.GALLERY -> Gallery(onBack = { screen = Screen.HOME })
                            Screen.SETTINGS -> SettingsScreen(model, onBack = { screen = Screen.HOME }, onHistory = { screen = Screen.HISTORY })
                            Screen.HISTORY -> HistoryScreen(model, onBack = { screen = Screen.SETTINGS })
                            Screen.HOME -> Home(
                                model, onApprove = ::approve, onGallery = { screen = Screen.GALLERY },
                                onSettings = { screen = Screen.SETTINGS }, onOverlay = ::setOverlay,
                            )
                        }
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
                model.pair(uri.toString())
                i.data = null // handled once: a rotation must not pair again
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
private fun Home(model: AppModel, onApprove: (ApprovalRequest) -> Unit, onGallery: () -> Unit, onSettings: () -> Unit, onOverlay: (Boolean) -> Unit) {
    val engines = remember { HashMap<String, MochiEngine>() }
    val touches = remember { HashMap<String, MochiTouch>() }
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }

    // Allow tapped on a notification before the link was back: open the prompt once the request is here.
    LaunchedEffect(model.approvals, model.wantAllow) {
        val fp = model.wantAllow ?: return@LaunchedEffect
        model.approvals.firstOrNull { it.fingerprint == fp }?.let {
            model.wantAllow = null
            onApprove(it)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(model, clock) }
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
        if (model.mode == Mode.NONE) item { PairCard(model) }

        // What is waiting for an answer comes first.
        items(model.approvals, key = { it.fingerprint }) { r -> ApprovalCard(model, r, onApprove) }

        if (model.mode != Mode.NONE) {
            item { SectionTitle(stringResource(R.string.sessions_title)) }
            if (model.sessions.isEmpty()) {
                item {
                    CoucouCard {
                        Text(
                            stringResource(R.string.sessions_empty),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(model.sessions, key = { it.pillId }) { s ->
                val engine = engines.getOrPut(s.pillId) { MochiEngine(clock, sound = model.sounds) }
                val touch = touches.getOrPut(s.pillId) { MochiTouch(engine, model.sounds) { model.sessions.firstOrNull { it.pillId == s.pillId }?.state ?: BotState.IDLE } }
                if (engine.state != s.state && !touch.dizzy) engine.setState(s.state)
                SessionRow(s, engine, touch)
            }
        }

        item { OverlayCard(model, onOverlay) }
        item { Footer(model, onGallery, onSettings) }
    }
}

@Composable
private fun Header(model: AppModel, clock: () -> Double) {
    val engine = remember { MochiEngine(clock, sound = model.sounds) }
    val state = Summary.headerState(model.sessions, model.approvals.isNotEmpty())
    val touch = remember(engine) { MochiTouch(engine, model.sounds) { Summary.headerState(model.sessions, model.approvals.isNotEmpty()) } }
    if (engine.state != state && !touch.dizzy) engine.setState(state)
    val dot = when {
        model.mode == Mode.DEMO -> StatusColors.busy
        model.mode == Mode.PAIRED && model.linkState == LinkState.CONNECTED -> StatusColors.online
        model.mode == Mode.PAIRED && model.linkState == LinkState.CONNECTING -> StatusColors.busy
        else -> StatusColors.offline
    }
    Row(Modifier.padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        MochiView(engine, Modifier.size(64.dp).then(touch.modifier))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(statusLine(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusLine(model: AppModel): String = when (model.mode) {
    Mode.NONE -> stringResource(R.string.status_not_connected)
    Mode.DEMO -> stringResource(R.string.demo_mode)
    Mode.PAIRED -> when (model.linkState) {
        LinkState.CONNECTED -> "${stringResource(R.string.status_connected)} · ${model.desktopName.orEmpty()}"
        LinkState.CONNECTING -> stringResource(R.string.status_connecting)
        LinkState.DISCONNECTED -> stringResource(R.string.status_not_connected)
    }
}

@Composable
private fun PairCard(model: AppModel) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val notALink = stringResource(R.string.msg_bad_link)
    fun pairWith(link: String) {
        if (PairingPayload.parse(link) != null) model.pair(link) else model.message = notALink
    }
    CoucouCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.pair_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pair_paste)) }, singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pairWith(text) }, shape = CircleShape) { Text(stringResource(R.string.pair_button)) }
                OutlinedButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty().trim()
                        text = pasted
                        if (pasted.isNotEmpty()) pairWith(pasted)
                    },
                    shape = CircleShape,
                ) { Text(stringResource(R.string.pair_clipboard)) }
            }
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
private fun SessionRow(s: SessionInfo, engine: MochiEngine, touch: MochiTouch) {
    CoucouCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MochiView(engine, Modifier.size(80.dp).then(touch.modifier))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    s.agent.ifBlank { Pills.byId(s.pillId)?.name.orEmpty() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                if (s.statusText.isNotBlank()) {
                    Text(
                        s.statusText, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (s.stepCount > 0) {
                    LinearProgressIndicator(
                        progress = { Summary.progress(s.stepIndex, s.stepCount) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayCard(model: AppModel, onOverlay: (Boolean) -> Unit) {
    CoucouCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.overlay_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.overlay_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = model.overlayOn, onCheckedChange = onOverlay)
        }
    }
}

@Composable
private fun Footer(model: AppModel, onGallery: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            when (model.mode) {
                Mode.DEMO -> TextButton(onClick = { model.stopDemo() }) { Text(stringResource(R.string.demo_leave)) }
                Mode.PAIRED -> TextButton(onClick = { model.unpair() }) {
                    Text(stringResource(R.string.action_unpair), color = MaterialTheme.colorScheme.error)
                }
                Mode.NONE -> {}
            }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
            TextButton(onClick = onGallery) { Text(stringResource(R.string.gallery)) }
        }
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodySmall, color = dim)
        Text(stringResource(R.string.about_repo), style = MaterialTheme.typography.bodySmall, color = dim)
        Text(stringResource(R.string.about_assets), style = MaterialTheme.typography.bodySmall, color = dim)
    }
}

/** Every state and emote, drawn live. Handy to check the port by eye. */
@Composable
private fun Gallery(onBack: () -> Unit) {
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }
    val states = remember { BotState.entries.map { s -> s to MochiEngine(clock).apply { setState(s, force = true) } } }
    val emotes = remember { BotEmote.entries.map { e -> e to MochiEngine(clock).apply { setPermanentEmote(e) } } }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_close)) }
            Text(stringResource(R.string.gallery), style = MaterialTheme.typography.titleMedium)
        }
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
