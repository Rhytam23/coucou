package com.coucou.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotEmote
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView

class MainActivity : ComponentActivity() {
    private val model get() = (application as CoucouApp).model
    private var gallery by mutableStateOf(false)
    private var confirming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        model.resume()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (gallery) Gallery(onBack = { gallery = false })
                    else Home(model, onApprove = ::approve, onGallery = { gallery = true })
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** A coucou://pair link (from the camera app or a browser) or a tap on an approval notification. */
    private fun handle(i: Intent?) {
        i ?: return
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
            if (request != null) approve(request) else model.message = "That request is no longer pending."
        }
    }

    /** Allow: only after the biometric / screen-lock check. */
    private fun approve(r: ApprovalRequest) {
        if (confirming) return // a second tap must not open a second prompt
        confirming = true
        BiometricGate.confirm(
            this, "Allow ${r.tool}?", r.command,
            onSuccess = { confirming = false; model.decide(r.fingerprint, allow = true) },
            onFail = { confirming = false; if (it.isNotBlank()) model.message = it },
        )
    }
}

@Composable
private fun Home(model: AppModel, onApprove: (ApprovalRequest) -> Unit, onGallery: () -> Unit) {
    val engines = remember { HashMap<String, MochiEngine>() }
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(statusLine(model), style = MaterialTheme.typography.bodyMedium)
            }
        }
        model.message?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f))
                        TextButton(onClick = { model.message = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
        if (model.mode == Mode.NONE) item { PairCard(model) }

        items(model.approvals, key = { it.fingerprint }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.titleMedium)
                    Text(model.agentName(r.pillId), style = MaterialTheme.typography.labelMedium)
                    Text("${r.tool}: ${r.command}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onApprove(r) }) { Text(stringResource(R.string.action_allow)) }
                        OutlinedButton(onClick = { model.decide(r.fingerprint, allow = false) }) { Text(stringResource(R.string.action_deny)) }
                    }
                }
            }
        }

        if (model.mode != Mode.NONE) {
            item { Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleMedium) }
            if (model.sessions.isEmpty()) item { Text(stringResource(R.string.sessions_empty)) }
            items(model.sessions, key = { it.pillId }) { s ->
                val engine = engines.getOrPut(s.pillId) { MochiEngine(clock, sound = model.sounds) }
                if (engine.state != s.state) engine.setState(s.state)
                SessionRow(s, engine)
            }
        }

        item {
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (model.mode) {
                    Mode.DEMO -> OutlinedButton(onClick = { model.stopDemo() }) { Text(stringResource(R.string.demo_leave)) }
                    Mode.PAIRED -> OutlinedButton(onClick = { model.unpair() }) { Text(stringResource(R.string.action_unpair)) }
                    Mode.NONE -> {}
                }
                TextButton(onClick = onGallery) { Text(stringResource(R.string.gallery)) }
            }
            Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_repo), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_assets), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pair_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pair_paste)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (PairingPayload.parse(text) != null) model.pair(text) else model.message = "That is not a Coucou pairing link." }) {
                    Text(stringResource(R.string.pair_button))
                }
                OutlinedButton(onClick = { model.startDemo() }) { Text(stringResource(R.string.demo_try)) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: SessionInfo, engine: MochiEngine) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MochiView(engine, Modifier.size(88.dp))
            Column(Modifier.weight(1f)) {
                Text(s.agent.ifBlank { Pills.byId(s.pillId)?.name.orEmpty() }, style = MaterialTheme.typography.titleMedium)
                Text(s.statusText, style = MaterialTheme.typography.bodyMedium)
                if (s.stepCount > 0) Text("${s.stepIndex}/${s.stepCount}", style = MaterialTheme.typography.labelSmall)
            }
        }
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
