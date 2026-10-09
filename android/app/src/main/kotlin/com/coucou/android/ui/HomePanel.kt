package com.coucou.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coucou.android.R
import com.coucou.android.core.HomePanel
import com.coucou.android.core.Pills
import com.coucou.android.core.Summary
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiConst
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import com.coucou.android.mochi.Rgb

private fun stateLabel(state: BotState): Int = when (state) {
    BotState.IDLE -> R.string.state_ready
    BotState.WORKING -> R.string.state_working
    BotState.THINKING -> R.string.state_thinking
    BotState.SEARCHING -> R.string.state_searching
    BotState.APPROVAL -> R.string.state_approval
    BotState.QUESTION -> R.string.state_question
    BotState.ERROR -> R.string.state_error
    BotState.FINISHED -> R.string.state_finished
    BotState.RATELIMIT -> R.string.state_ratelimit
    BotState.SLEEPING -> R.string.state_sleeping
    BotState.DIZZY -> R.string.state_dizzy
}

private fun rgbColor(c: Rgb) = Color(c.r.toFloat(), c.g.toFloat(), c.b.toFloat())

/** Idle is "Ready" with a green dot, as on the PC; every other state wears its own colour. */
private fun dotColor(state: BotState): Color =
    if (state == BotState.IDLE) StatusColors.online else MochiConst.STATES[state]?.color?.let(::rgbColor) ?: StatusColors.offline

/**
 * The PC's Home panel, for a phone: the big Mochi of the agent that matters, its name and kind, a
 * status line with a coloured dot, one small link, then the other agents as pills with their own
 * little Mochi.
 */
@Composable
fun AgentCard(
    focus: SessionInfo?, engine: MochiEngine, touch: Modifier, link: HomePanel.Link, onLink: () -> Unit,
) {
    val state = focus?.state ?: BotState.IDLE
    CoucouCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MochiView(engine, Modifier.size(110.dp).then(touch))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val pillName = focus?.let { Pills.byId(it.pillId)?.name }.orEmpty()
                val name = focus?.agent?.ifBlank { pillName }.orEmpty().ifBlank { stringResource(R.string.app_name) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (pillName.isNotBlank() && pillName != name) pillName else stringResource(R.string.agent_kind),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor(state)))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.bodyMedium)
                }
                val detail = focus?.statusText.orEmpty()
                if (detail.isNotBlank()) {
                    Text(
                        detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else if (focus == null) {
                    Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (focus != null && focus.stepCount > 0) {
                    LinearProgressIndicator(
                        progress = { Summary.progress(focus.stepIndex, focus.stepCount) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                }
                TextButton(onClick = onLink, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(if (link == HomePanel.Link.PAIR) R.string.link_pair else R.string.link_settings))
                }
            }
        }
    }
}

/** One of the other agents: its little Mochi in the agent's colour and its name. Tap to focus it. */
@Composable
fun AgentChip(s: SessionInfo, engine: MochiEngine, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val def = Pills.byId(s.pillId)
    val tint = def?.colorHex?.let { HomePanel.rgb(it) }?.let(::rgbColor) ?: MaterialTheme.colorScheme.primary
    Row(
        modifier.clip(RoundedCornerShape(26.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, if (selected) tint else tint.copy(alpha = 0.35f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MochiView(engine, Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            s.agent.ifBlank { def?.name.orEmpty() }, color = tint, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two chips per row; a lone one keeps its half of the width. */
@Composable
fun AgentChipRow(
    row: List<SessionInfo>, engineFor: (SessionInfo) -> MochiEngine, selected: String?, onPick: (SessionInfo) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in row) AgentChip(s, engineFor(s), s.pillId == selected, Modifier.weight(1f)) { onPick(s) }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}
