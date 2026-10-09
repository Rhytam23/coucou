package com.coucou.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coucou.android.R
import com.coucou.android.app.AppModel
import com.coucou.android.core.HistoryDays
import com.coucou.android.core.QuietHours
import com.coucou.android.sound.SoundVolume
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun ScreenTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun clock(min: Int) = "%02d:%02d".format(QuietHours.wrap(min) / 60, QuietHours.wrap(min) % 60)

@Composable
private fun TimeRow(label: String, minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onChange(minutes - QuietHours.STEP) }, enabled = enabled) { Text("−") }
        Text(clock(minutes), Modifier.padding(horizontal = 12.dp), fontFamily = FontFamily.Monospace)
        OutlinedButton(onClick = { onChange(minutes + QuietHours.STEP) }, enabled = enabled) { Text("+") }
    }
}

@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onHistory: () -> Unit) {
    val s = model.settings
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(stringResource(R.string.settings_title), onBack) }

        item {
            CoucouCard {
                SwitchRow(stringResource(R.string.settings_sound), null, s.soundOn) { model.updateSettings(s.copy(soundOn = it)) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Text(stringResource(R.string.settings_volume), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = s.volume, onValueChange = { model.updateSettings(s.copy(volume = SoundVolume.clamp(it))) },
                        valueRange = 0f..SoundVolume.MAX, enabled = s.soundOn,
                    )
                }
            }
        }

        item {
            CoucouCard {
                SwitchRow(stringResource(R.string.notify_done), stringResource(R.string.notify_done_hint), s.notifyDone) {
                    model.updateSettings(s.copy(notifyDone = it))
                }
                SwitchRow(stringResource(R.string.quiet_hours), stringResource(R.string.quiet_hint), s.quiet.enabled) {
                    model.updateSettings(s.copy(quiet = s.quiet.copy(enabled = it)))
                }
                TimeRow(stringResource(R.string.quiet_from), s.quiet.fromMin, s.quiet.enabled) {
                    model.updateSettings(s.copy(quiet = s.quiet.copy(fromMin = QuietHours.wrap(it))))
                }
                TimeRow(stringResource(R.string.quiet_to), s.quiet.toMin, s.quiet.enabled) {
                    model.updateSettings(s.copy(quiet = s.quiet.copy(toMin = QuietHours.wrap(it))))
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
        }

        item {
            CoucouCard(Modifier.clickable { onHistory() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.history_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
fun HistoryScreen(model: AppModel, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    val groups = HistoryDays.group(model.decisions, now, zone)
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenTitle(stringResource(R.string.history_title), onBack) }
        if (groups.isEmpty()) {
            item {
                CoucouCard {
                    Text(stringResource(R.string.history_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        for ((key, list) in groups) {
            val (day, date) = key
            item {
                Text(
                    when (day) {
                        HistoryDays.Day.TODAY -> stringResource(R.string.history_today)
                        HistoryDays.Day.YESTERDAY -> stringResource(R.string.history_yesterday)
                        HistoryDays.Day.OTHER -> dateFormat.format(date)
                    },
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list) { d ->
                CoucouCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (d.allowed) stringResource(R.string.history_allowed) else stringResource(R.string.history_denied),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                color = if (d.allowed) StatusColors.online else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(d.agent, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                timeFormat.format(java.time.Instant.ofEpochMilli(d.atMs).atZone(zone).toLocalTime()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${d.tool}: ${d.command}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.history_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (groups.isNotEmpty()) {
            item { TextButton(onClick = { model.clearDecisions() }) { Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error) } }
        }
        item { Spacer(Modifier.padding(bottom = 16.dp)) }
    }
}
