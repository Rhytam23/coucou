package com.coucou.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.coucou.android.core.ToolLabels
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiConst
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import com.coucou.android.mochi.Rgb

internal fun stateLabel(state: BotState): Int = when (state) {
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

internal fun rgbColor(c: Rgb) = Color(c.r.toFloat(), c.g.toFloat(), c.b.toFloat())

/** Idle is "Ready" with a green dot, as on the PC; every other state wears its own colour. */
private fun dotColor(state: BotState): Color =
    if (state == BotState.IDLE) StatusColors.online else MochiConst.STATES[state]?.color?.let(::rgbColor) ?: StatusColors.offline

/**
 * The PC's Home panel, for a phone, and the hero of the screen: the big Mochi of the agent that
 * matters, its name and kind, a status line with a coloured dot, what it is doing in plain words
 * (never a raw tool name), "Step 3 of 8" with its bar, and at most one link.
 */
@Composable
fun AgentCard(
    focus: SessionInfo?, engine: MochiEngine, touch: Modifier, link: HomePanel.Link, onLink: () -> Unit,
    /** Opens the session's detail screen; null when the computer sent no details for it. */
    onDetails: (() -> Unit)? = null,
) {
    val state = focus?.state ?: BotState.IDLE
    CoucouCard(if (onDetails != null) Modifier.clickable(onClick = onDetails) else Modifier) {
        Row(Modifier.padding(Gutter), verticalAlignment = Alignment.CenterVertically) {
            MochiView(engine, Modifier.size(112.dp).then(touch))
            Spacer(Modifier.width(Gutter))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val pillName = focus?.let { Pills.byId(it.pillId)?.name }.orEmpty()
                val name = focus?.agent?.ifBlank { pillName }.orEmpty().ifBlank { stringResource(R.string.app_name) }
                Column {
                    Text(
                        name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (pillName.isNotBlank() && pillName != name) pillName else stringResource(R.string.agent_kind),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(dotColor(state)))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                val detail = ToolLabels.label(focus?.statusText.orEmpty())
                if (detail.isNotBlank()) {
                    Text(
                        detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else if (focus == null) {
                    Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val step = focus?.let { Summary.stepNumber(it.stepIndex, it.stepCount) }
                if (focus != null && step != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.step_of, step, focus.stepCount),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        LinearProgressIndicator(
                            progress = { Summary.progress(focus.stepIndex, focus.stepCount) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (onDetails != null) {
                    Text(
                        stringResource(R.string.session_details) + "  ›", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                }
                if (link != HomePanel.Link.NONE) {
                    TextButton(onClick = onLink, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (link == HomePanel.Link.PAIR) R.string.link_pair else R.string.link_approval))
                    }
                }
            }
        }
    }
}

/**
 * One of the other agents: its little Mochi in the agent's colour and its full name (two lines at
 * most, smaller text rather than a cut-off one). Tap to focus it; the chosen one has a clear frame.
 */
@Composable
fun AgentChip(s: SessionInfo, engine: MochiEngine, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val def = Pills.byId(s.pillId)
    val tint = HomePanel.colorHex(s)?.let { HomePanel.rgb(it) }?.let(::rgbColor) ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(CHIP_HEIGHT).clip(shape)
            .background(if (selected) tint.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) tint else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MochiView(engine, Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            s.agent.ifBlank { def?.name.orEmpty() }, Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CHIP_HEIGHT = 64.dp

/** Two equal columns, the same height; a lone pill keeps the left column. */
@Composable
fun AgentChipRow(
    row: List<SessionInfo>, engineFor: (SessionInfo) -> MochiEngine, selected: String?, onPick: (SessionInfo) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        for (s in HomePanel.cells(row)) {
            if (s == null) Spacer(Modifier.weight(1f).height(CHIP_HEIGHT))
            else AgentChip(s, engineFor(s), s.pillId == selected, Modifier.weight(1f)) { onPick(s) }
        }
    }
}
