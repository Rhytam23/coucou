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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of the Coucou website (docs/site.css): near-black page, slightly lighter cards with a thin
 * border, soft blue accent, rounded corners and pill-shaped buttons. Mochi itself is untouched.
 */
private val Dark = darkColorScheme(
    background = Color(0xFF0E0F12),
    surface = Color(0xFF0E0F12),
    surfaceVariant = Color(0xFF16171B),
    surfaceContainer = Color(0xFF16171B),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFFA1A6B0),
    outline = Color(0xFF26282E),
    outlineVariant = Color(0xFF26282E),
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0E0F12),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16171B),
    onSurface = Color(0xFF16171B),
    onSurfaceVariant = Color(0xFF5F646D),
    outline = Color(0xFFE6E7EA),
    outlineVariant = Color(0xFFE6E7EA),
    primary = Color(0xFF2F6FE0),
    onPrimary = Color(0xFFFFFFFF),
    error = Color(0xFFC62828),
)

/** Link status colours (the dot next to "Connected"). */
object StatusColors {
    val online = Color(0xFF4ADE80)
    val busy = Color(0xFFFBBF24)
    val offline = Color(0xFF6B7280)
}

@Composable
fun CoucouTheme(content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}

val CardShape = RoundedCornerShape(14.dp)

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
