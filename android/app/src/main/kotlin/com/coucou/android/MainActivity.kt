package com.coucou.android

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coucou.android.mochi.BotEmote
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Gallery() } }
    }
}

/** Shows every state and emote; tap a Mochi to greet. Phase 2 showcase, replaced by real sessions later. */
@Composable
fun Gallery() {
    val states = remember {
        BotState.entries.map { s ->
            s to MochiEngine({ SystemClock.elapsedRealtimeNanos() / 1e6 }).apply { setState(s, force = true) }
        }
    }
    val emotes = remember {
        BotEmote.entries.map { e ->
            e to MochiEngine({ SystemClock.elapsedRealtimeNanos() / 1e6 }).apply { triggerEmote(e, duration = 3600.0) }
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_assets), style = MaterialTheme.typography.bodySmall)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(states) { (s, engine) -> Cell(s.key, engine) }
            items(emotes) { (e, engine) -> Cell(e.key, engine) }
        }
    }
}

@Composable
private fun Cell(label: String, engine: MochiEngine) {
    Column(Modifier.clickable { engine.greet() }, horizontalAlignment = Alignment.CenterHorizontally) {
        MochiView(engine, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
