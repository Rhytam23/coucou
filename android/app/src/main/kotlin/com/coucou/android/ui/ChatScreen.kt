package com.coucou.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.coucou.android.R
import com.coucou.android.app.AppModel
import com.coucou.android.core.ChatMessage
import com.coucou.android.core.ChatReasons
import com.coucou.android.core.ChatRole
import com.coucou.android.core.ChatSession
import com.coucou.android.core.ChatStatus
import com.coucou.android.core.MarkdownLite
import com.coucou.android.core.MdBlock
import com.coucou.android.core.MdSpan
import com.coucou.android.link.Protocol

/** The words for a code the computer (or this app) gives when an answer did not complete. */
@Composable
private fun reasonText(code: String?): String = stringResource(
    when (ChatReasons.key(code)) {
        "off" -> R.string.chat_err_off
        "not_allowed" -> R.string.chat_err_not_allowed
        "busy" -> R.string.chat_err_busy
        "rate" -> R.string.chat_err_rate
        "too_long" -> R.string.chat_err_too_long
        "empty" -> R.string.chat_err_empty
        "no_key" -> R.string.chat_err_no_key
        "unreachable" -> R.string.chat_err_unreachable
        "auth" -> R.string.chat_err_auth
        "provider" -> R.string.chat_err_provider
        "canceled" -> R.string.chat_err_canceled
        "connection" -> R.string.chat_err_connection
        "interrupted" -> R.string.chat_err_interrupted
        else -> R.string.chat_err_internal
    },
)

/**
 * Chat with the computer's AI providers, like the PC's chat panel: your messages on the right, the
 * answers as plain light Markdown, three dots while waiting, the model above the box. The computer's
 * API key is used on the computer; this screen only ever has text.
 */
@Composable
fun ChatScreen(model: AppModel, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var refusal by remember { mutableStateOf<ChatSession.Refusal?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messages = model.chatMessages

    LaunchedEffect(Unit) { model.chatRefreshModels() }
    // Follow the answer as it grows.
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        ScreenTitle(
            stringResource(R.string.chat_title), onBack,
            trailing = {
                if (messages.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.chat_clear), maxLines = 1) }
                }
            },
        )
        Text(
            stringResource(R.string.chat_note), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!model.chatAvailable) {
            CoucouCard(Modifier.padding(vertical = 4.dp)) {
                Text(stringResource(R.string.chat_unavailable), Modifier.padding(Gutter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(
                    stringResource(R.string.chat_empty), Modifier.align(Alignment.Center).padding(Gutter),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    items(messages, key = { it.id }) { m -> Bubble(m) }
                }
            }
        }
        Composer(
            model, text, refusal,
            onText = { text = it; refusal = null },
            onSend = {
                val r = model.chatSend(text)
                if (r == null) { text = ""; refusal = null } else refusal = r
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = { Text(stringResource(R.string.chat_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; model.chatClear() }) {
                    Text(stringResource(R.string.chat_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    if (m.role == ChatRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(
                    m.text,
                    Modifier.widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.text.isEmpty() && m.status == ChatStatus.STREAMING) TypingDots()
        else if (m.text.isNotEmpty()) SelectionContainer { MarkdownText(m.text) }
        if (m.status == ChatStatus.FAILED) {
            Text(reasonText(m.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The three dots of the PC's chat; they only move while this is on screen. */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dots",
    )
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val on = phase.toInt() == i
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (on) 1f else 0.35f)),
            )
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val code = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Para -> Text(styled(b.spans, code), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(styled(b.spans, code), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> Text(
                    b.text,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(code)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun styled(spans: List<MdSpan>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style) { append(s.text) }
    }
}

@Composable
private fun Composer(model: AppModel, text: String, refusal: ChatSession.Refusal?, onText: (String) -> Unit, onSend: () -> Unit) {
    val limit = Protocol.CHAT_MAX_TEXT
    val length = text.trim().length
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelChip(model)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { onText(it.take(limit + 200)) }, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                maxLines = 4, shape = RoundedCornerShape(20.dp), enabled = model.chatAvailable,
            )
            if (model.chatBusy) {
                OutlinedButton(onClick = { model.chatCancel() }, Modifier.height(52.dp), shape = CircleShape) {
                    Text(stringResource(R.string.chat_stop), maxLines = 1)
                }
            } else {
                Button(
                    onClick = onSend, Modifier.height(52.dp), shape = CircleShape,
                    enabled = model.chatAvailable && length in 1..limit,
                ) { Text(stringResource(R.string.chat_send), maxLines = 1) }
            }
        }
        val note = when {
            refusal == ChatSession.Refusal.TOO_LONG || length > limit -> stringResource(R.string.chat_ref_too_long)
            refusal == ChatSession.Refusal.NO_MODEL -> stringResource(R.string.chat_ref_no_model)
            refusal == ChatSession.Refusal.BUSY -> stringResource(R.string.chat_ref_busy)
            refusal == ChatSession.Refusal.OFFLINE -> stringResource(R.string.chat_ref_offline)
            else -> null
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (length > limit * 9 / 10) {
            Text(stringResource(R.string.chat_count, length, limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The model above the box, like the PC's: tap to pick another of the ones the computer allows. */
@Composable
private fun ModelChip(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val models = model.chatModels
    val current = models.firstOrNull { it.id == model.chatModel }
    Box {
        OutlinedButton(
            onClick = { open = true }, enabled = models.size > 1, shape = CircleShape,
            modifier = Modifier.height(40.dp),
        ) {
            Text(
                stringResource(R.string.chat_model, current?.label ?: "—"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            )
            if (models.size > 1) Text("  ▾", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in models) {
                DropdownMenuItem(
                    text = { Text(m.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { model.chatSelect(m.id); open = false },
                )
            }
        }
    }
}

/** A slim card on Home: only when chat is available (or there is something to read). */
@Composable
fun ChatEntry(onOpen: () -> Unit) {
    CoucouCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(Gutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chat_entry_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.chat_entry_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.size(Gap))
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
