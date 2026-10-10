package com.coucou.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coucou.android.R
import com.coucou.android.core.IconKind
import com.coucou.android.core.Spacing
import com.coucou.android.core.TypeScale
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiConst

/**
 * Every building block of the new look in one place, to check it by eye on the phone in both themes
 * and at large font sizes. Reached from the Mochi gallery. Nothing else uses these blocks yet.
 */
@Composable
fun DesignScreen(onBack: () -> Unit) {
    val t = tokens()
    var on by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(0.6f) }
    LazyColumn(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
        item { ScreenTitle(stringResource(R.string.design_title), onBack) }
        item { Text(stringResource(R.string.design_note), style = TypeScale.SECONDARY.style(t.textDim.c())) }

        item { SectionHeading("Colours") }
        item {
            Panel {
                Column(Modifier.padding(Spacing.INSIDE.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val swatches = listOf(
                        "bg" to t.bg, "panel" to t.panel, "panel2" to t.panel2, "line" to t.line, "text" to t.text,
                        "textDim" to t.textDim, "textFaint" to t.textFaint, "primary" to t.primaryButton,
                        "secondary" to t.secondaryButton, "danger" to t.danger, "online" to t.online,
                    )
                    for (row in swatches.chunked(4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((name, color) in row) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(color.c()).padding(1.dp))
                                    Text(name, style = TypeScale.LABEL.style(t.textDim.c()), maxLines = 1)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        item { SectionHeading("Type scale") }
        item {
            Panel {
                Column(Modifier.padding(Spacing.INSIDE.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (step in TypeScale.ALL) {
                        Text("${step.name}  ${step.sizeSp}/${step.lineSp}", style = step.style(t.text.c()))
                    }
                }
            }
        }

        item { SectionHeading("Buttons and chips") }
        item {
            Panel {
                Column(Modifier.padding(Spacing.INSIDE.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    PillButton("Main action", {}, Modifier.fillMaxWidth(), PillKind.PRIMARY, icon = { CoucouIcon(IconKind.LOCK, tint = t.onPrimaryButton.c(), size = 18.dp) })
                    Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                        PillButton("Other", {}, Modifier.weight(1f))
                        PillButton("Disconnect", {}, Modifier.weight(1f), PillKind.DANGER)
                    }
                    PillButton("Disabled", {}, Modifier.fillMaxWidth(), enabled = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Project: korus")
                        Chip("Working", tint = MochiConst.STATES[BotState.WORKING]?.color?.let(::rgbColor))
                    }
                }
            }
        }

        item { SectionHeading("Switch and slider") }
        item {
            Panel {
                ListRow("A setting", hint = "With a hint under it", trailing = { CoucouSwitch(on, { on = it }, label = "A setting") })
                RowDivider()
                Column(Modifier.padding(horizontal = Spacing.INSIDE.dp, vertical = 8.dp)) {
                    Row { Text("Volume", Modifier.weight(1f), style = TypeScale.BODY.style(t.text.c())); Text("${(volume * 100).toInt()}%", style = TypeScale.SECONDARY.style(t.textDim.c())) }
                    CoucouSlider(volume, { volume = it }, label = "Volume")
                }
            }
        }

        item { SectionHeading("Panels with a glow (one per state)") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (state in listOf(BotState.WORKING, BotState.APPROVAL, BotState.QUESTION, BotState.FINISHED, BotState.ERROR)) {
                    val color = MochiConst.STATES[state]?.color?.let(::rgbColor)
                    Panel(wash = color) {
                        ListRow(state.key, hint = "The glow rises from the bottom edge", trailing = { if (color != null) StateDot(color) })
                    }
                }
            }
        }

        item { SectionHeading("Hero panel (black in both themes)") }
        item {
            HeroPanel(wash = MochiConst.STATES[BotState.WORKING]?.color?.let(::rgbColor)) {
                Column(Modifier.padding(Spacing.INSIDE.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Korus", style = TypeScale.TITLE.style(Color.White))
                    Text("Running the database migration", style = TypeScale.HEADLINE.style(Color.White))
                }
            }
        }

        item { SectionHeading("Icons") }
        item {
            Panel {
                Column(Modifier.padding(Spacing.INSIDE.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    for (row in IconKind.entries.chunked(5)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (k in row) CoucouIcon(k, size = 28.dp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(Gutter)) }
    }
}
