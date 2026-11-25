package info.cemu.cemu.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeEmulation
import kotlinx.coroutines.delay

/** Progress bar of the scan Cemu makes on the game code before the game starts. Nothing is shown when no scan is running. */
@Composable
fun InspectorProgressOverlay() {
    var progress by remember { mutableStateOf(-1) }
    var stage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val value = NativeEmulation.getInspectorProgress()
            progress = if (value in 0..99) value else -1
            stage = if (progress >= 0) NativeEmulation.getInspectorStage() else ""
            delay(200)
        }
    }

    if (progress < 0) {
        return
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 24.dp, start = 48.dp, end = 48.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                text = "${tr("Analyzing the game")}: $progress%" + if (stage.isNotEmpty()) " ($stage)" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
