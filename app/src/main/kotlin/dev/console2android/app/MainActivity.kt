package dev.console2android.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.JobState
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.report.DiagnosticReport
import dev.console2android.core.report.ReportFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                C2AApp()
            }
        }
    }
}

/** The five product areas of the single APK (see master plan, section 14). */
private enum class Section(val title: String, val short: String) {
    LIBRARY("Library", "L"),
    JOBS("Jobs", "J"),
    CONVERTED("Converted", "C"),
    DIAGNOSTICS("Diagnostics", "D"),
    SETTINGS("Settings", "S"),
}

@Composable
private fun C2AApp() {
    var selected by rememberSaveable { mutableStateOf(0) }
    val sections = Section.entries
    Scaffold(
        bottomBar = {
            NavigationBar {
                sections.forEachIndexed { i, s ->
                    NavigationBarItem(
                        selected = i == selected,
                        onClick = { selected = i },
                        icon = { Text(s.short) },
                        label = { Text(s.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (sections[selected]) {
                Section.LIBRARY -> LibraryScreen()
                Section.JOBS -> JobsScreen()
                Section.CONVERTED -> ConvertedScreen()
                Section.DIAGNOSTICS -> DiagnosticsScreen()
                Section.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun NotImplemented(text: String) = Card(Modifier.fillMaxWidth()) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LibraryScreen() {
    Title("Source games")
    NotImplemented("No source games. Import and library management are not implemented yet (Phase 2).")
    Button(onClick = {}, enabled = false) { Text("Import (not available yet)") }
}

@Composable
private fun JobsScreen() {
    Title("Conversion jobs")
    NotImplemented("No jobs. The conversion engine is not implemented yet. Job pipeline modelled in :core:")
    SelectionContainer {
        Text(JobState.PIPELINE.joinToString("  →  ") { it.name }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConvertedScreen() {
    Title("Converted games")
    NotImplemented("No converted games. Only packages that pass the launch gate will appear here, separate from source games.")
}

private fun environmentReport(): DiagnosticReport = DiagnosticReport(
    reportId = UUID.randomUUID().toString(),
    gameId = null,
    packageVersion = null,
    runtimeVersion = "none (Phase 1 shell)",
    sourceSha256 = null,
    device = DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    ),
    status = null,
    fcv = null,
    createdAtEpochMs = System.currentTimeMillis(),
)

private fun shareText(context: Context, subject: String, body: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val report = remember { environmentReport() }
    val text = remember(report) { ReportFormatter.toText(report) }
    Title("Diagnostics")
    Text("Environment report (no game loaded). Nothing is sent automatically; export is manual.")
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    Text("Exported metadata:", style = MaterialTheme.typography.titleSmall)
    ReportFormatter.EXPORTED_METADATA.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { shareText(context, "Console2Android report (TXT)", text) }) { Text("Share TXT") }
        Button(onClick = { shareText(context, "Console2Android report (JSON)", ReportFormatter.toJson(report)) }) { Text("Share JSON") }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }
    Title("Settings")
    NotImplemented("Performance, graphics, controls and runtime settings are not implemented yet.")
    Text("App version: $version")
    Text("Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    Text("Default conversion mode: ${ConversionMode.STRICT.name}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Modes: STRICT (no unverified paths), BALANCED (tested fallbacks allowed), EXPERIMENTAL (never reported as Stable).",
        style = MaterialTheme.typography.bodySmall,
    )
}
