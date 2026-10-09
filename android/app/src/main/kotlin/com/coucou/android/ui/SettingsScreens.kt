package com.coucou.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coucou.android.Attribution
import com.coucou.android.R
import com.coucou.android.app.AppModel
import com.coucou.android.app.Mode
import com.coucou.android.core.HistoryDays
import com.coucou.android.core.QuietHours
import com.coucou.android.sound.SoundVolume
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Back button and the screen's title on one line; a long title is cut with "…", never wrapped letter by letter. */
@Composable
fun ScreenTitle(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), maxLines = 1) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

/** A small heading above a card, the same on every screen. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(start = 4.dp, top = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(Gutter), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Gap))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A card row that opens another screen. */
@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun clock(min: Int) = "%02d:%02d".format(QuietHours.wrap(min) / 60, QuietHours.wrap(min) % 60)

@Composable
private fun TimeRow(label: String, minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = Gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onChange(minutes - QuietHours.STEP) }, enabled = enabled) { Text("−") }
        Text(clock(minutes), Modifier.padding(horizontal = Gap), fontFamily = FontFamily.Monospace)
        OutlinedButton(onClick = { onChange(minutes + QuietHours.STEP) }, enabled = enabled) { Text("+") }
    }
}

@Composable
fun SettingsScreen(
    model: AppModel, onBack: () -> Unit, onHistory: () -> Unit, onGallery: () -> Unit, onOverlay: (Boolean) -> Unit,
) {
    val s = model.settings
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
        item { ScreenTitle(stringResource(R.string.settings_title), onBack) }

        item { SectionTitle(stringResource(R.string.settings_section_computer)) }
        item {
            CoucouCard {
                Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(linkDotColor(model)))
                        Spacer(Modifier.width(8.dp))
                        Text(linkStatusText(model), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    when (model.mode) {
                        Mode.PAIRED -> OutlinedButton(onClick = { model.unpair() }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
                            Text(stringResource(R.string.action_unpair), color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Mode.DEMO -> OutlinedButton(onClick = { model.stopDemo() }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
                            Text(stringResource(R.string.demo_leave), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Mode.NONE -> Text(stringResource(R.string.settings_computer_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_display)) }
        item {
            CoucouCard { SwitchRow(stringResource(R.string.overlay_title), stringResource(R.string.overlay_hint), model.overlayOn, onOverlay) }
        }

        item { SectionTitle(stringResource(R.string.settings_section_sound)) }
        item {
            CoucouCard {
                SwitchRow(stringResource(R.string.settings_sound), null, s.soundOn) { model.updateSettings(s.copy(soundOn = it)) }
                Column(Modifier.padding(start = Gutter, end = Gutter, bottom = 8.dp)) {
                    Text(stringResource(R.string.settings_volume), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = s.volume, onValueChange = { model.updateSettings(s.copy(volume = SoundVolume.clamp(it))) },
                        valueRange = 0f..SoundVolume.MAX, enabled = s.soundOn,
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_notices)) }
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
                Spacer(Modifier.height(8.dp))
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_more)) }
        item {
            CoucouCard {
                LinkRow(stringResource(R.string.history_title), onHistory)
                LinkRow(stringResource(R.string.gallery), onGallery)
            }
        }

        // Required by Louis Raillé's permission: visible, one tap from Home.
        item { SectionTitle(stringResource(R.string.settings_section_about)) }
        item {
            CoucouCard {
                Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.about_repo),
                        Modifier.clickable { runCatching { uri.openUri(Attribution.REPO_URL) } },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.about_assets), style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
        }
        item { Spacer(Modifier.height(Gutter)) }
    }
}

@Composable
fun HistoryScreen(model: AppModel, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    val groups = HistoryDays.group(model.decisions, now, zone)
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    LazyColumn(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
        item { ScreenTitle(stringResource(R.string.history_title), onBack) }
        item {
            Text(stringResource(R.string.history_note), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (groups.isEmpty()) {
            item {
                CoucouCard {
                    Text(stringResource(R.string.history_empty), Modifier.padding(Gutter), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        for ((key, list) in groups) {
            val (day, date) = key
            item {
                SectionTitle(
                    when (day) {
                        HistoryDays.Day.TODAY -> stringResource(R.string.history_today)
                        HistoryDays.Day.YESTERDAY -> stringResource(R.string.history_yesterday)
                        HistoryDays.Day.OTHER -> dateFormat.format(date)
                    },
                )
            }
            items(list) { d ->
                CoucouCard {
                    Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (d.allowed) stringResource(R.string.history_allowed) else stringResource(R.string.history_denied),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = if (d.allowed) StatusColors.online else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(d.agent, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                timeFormat.format(java.time.Instant.ofEpochMilli(d.atMs).atZone(zone).toLocalTime()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
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
        if (groups.isNotEmpty()) {
            item { TextButton(onClick = { model.clearDecisions() }) { Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error) } }
        }
        item { Spacer(Modifier.height(Gutter)) }
    }
}
