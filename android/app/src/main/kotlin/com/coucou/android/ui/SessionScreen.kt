package com.coucou.android.ui

import android.os.SystemClock
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coucou.android.R
import com.coucou.android.app.AppModel
import com.coucou.android.core.HomePanel
import com.coucou.android.core.Pills
import com.coucou.android.core.Summary
import com.coucou.android.core.ToolLabels
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView

/**
 * One agent in detail, from what the computer sends with "Show session details on the phone": the project's
 * folder name, how far it got, the last message and the steps it took (newest first, in plain words).
 * Without that switch there is nothing to show but a hint on where to turn it on.
 */
@Composable
fun SessionScreen(model: AppModel, pillId: String, onBack: () -> Unit) {
    val s = model.sessions.firstOrNull { it.pillId == pillId }
    val clock = remember { { SystemClock.elapsedRealtimeNanos() / 1e6 } }
    val engine = remember(pillId) { MochiEngine(clock) }
    if (s == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
            ScreenTitle(stringResource(R.string.session_details), onBack)
            Text(stringResource(R.string.session_ended), Modifier.padding(Gutter), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (engine.state != s.state) engine.setState(s.state)
    val name = s.agent.ifBlank { Pills.byId(s.pillId)?.name.orEmpty() }
    val accent = HomePanel.colorHex(s)?.let { HomePanel.rgb(it) }?.let(::rgbColor) ?: MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
        item { ScreenTitle(name, onBack) }
        item {
            CoucouCard {
                Row(Modifier.padding(Gutter), verticalAlignment = Alignment.CenterVertically) {
                    MochiView(engine, Modifier.size(96.dp))
                    Spacer(Modifier.width(Gutter))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.project?.let {
                            Text(stringResource(R.string.session_project, it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(stateLabel(s.state)), style = MaterialTheme.typography.bodyMedium)
                        }
                        val step = Summary.stepNumber(s.stepIndex, s.stepCount)
                        if (step != null) {
                            Text(stringResource(R.string.step_of, step, s.stepCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(
                                progress = { Summary.progress(s.stepIndex, s.stepCount) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = accent, trackColor = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
        if (!s.finalLine.isNullOrBlank()) {
            item { SectionTitle(stringResource(R.string.session_last)) }
            item {
                CoucouCard {
                    SelectionContainer { Text(s.finalLine, Modifier.padding(Gutter), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.session_steps)) }
        val steps = HomePanel.stepsNewestFirst(s)
        if (steps.isEmpty()) {
            item {
                CoucouCard {
                    Text(
                        stringResource(if (model.detailsOffered) R.string.session_no_steps else R.string.session_hint),
                        Modifier.padding(Gutter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                CoucouCard {
                    SelectionContainer {
                        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            steps.forEachIndexed { i, step ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(if (i == 0) accent else MaterialTheme.colorScheme.outline))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        ToolLabels.label(step), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(Gutter)) }
    }
}
