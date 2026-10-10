package com.coucou.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coucou.android.core.Palette
import com.coucou.android.core.Tokens

/**
 * The look of the Coucou website (docs/site.css): near-black page, slightly lighter cards with a thin
 * border, soft blue accent, rounded corners and pill-shaped buttons. Mochi itself is untouched.
 */
private fun scheme(p: Palette, t: Tokens, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Surfaces and text come from the redesign's tokens, so every screen sits on the same black (or paper)
    // as the new ones; the accent still comes from Palette until each screen moves over (U2..U6).
    return base.copy(
        background = Color(t.bg), surface = Color(t.bg),
        surfaceVariant = Color(t.panel), surfaceContainer = Color(t.panel),
        onBackground = Color(t.text), onSurface = Color(t.text), onSurfaceVariant = Color(t.textDim),
        outline = Color(t.line), outlineVariant = Color(t.line),
        primary = Color(p.accent), onPrimary = Color(p.onAccent), error = Color(p.error),
    )
}

private val Dark = scheme(Palette.DARK, Tokens.DARK, dark = true)
private val Light = scheme(Palette.LIGHT, Tokens.LIGHT, dark = false)

/** Link status colours (the dot next to "Connected"). */
object StatusColors {
    val online = Color(0xFF4ADE80)
    val busy = Color(0xFFFBBF24)
    val offline = Color(0xFF6B7280)
}

@Composable
fun CoucouTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (dark) Dark else Light
    // The redesign's tokens ride along; screens that have not moved over still use the Material scheme above.
    CompositionLocalProvider(LocalTokens provides if (dark) Tokens.DARK else Tokens.LIGHT) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val CardShape = RoundedCornerShape(14.dp)

/** The gutter at the side of every screen, and the gap between cards (one rhythm everywhere). */
val Gutter = 16.dp
val Gap = 12.dp

/** The website's `.card`: a flat surface with a one-pixel border; [emphasis] draws it in the accent colour. */
@Composable
fun CoucouCard(modifier: Modifier = Modifier, emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val line = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Card(
        modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, line),
        elevation = CardDefaults.cardElevation(0.dp),
        content = content,
    )
}
