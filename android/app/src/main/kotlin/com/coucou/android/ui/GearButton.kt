package com.coucou.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The gear at the top right of Home, like the PC's. Drawn in code (no icon library); 48 dp to tap. */
@Composable
fun GearButton(description: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val c = center
            val r = size.minDimension / 2
            val stroke = 2.dp.toPx()
            drawCircle(color, radius = r * 0.5f, center = c, style = Stroke(stroke))
            for (i in 0 until 8) {
                val a = i * PI / 4
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(color, Offset(c.x + dx * r * 0.7f, c.y + dy * r * 0.7f), Offset(c.x + dx * r * 0.95f, c.y + dy * r * 0.95f), stroke * 1.5f, StrokeCap.Round)
            }
        }
    }
}
