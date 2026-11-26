package dev.console2android.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.console2android.core.convert.ConvertedPackage
import dev.console2android.core.convert.JobRunner
import dev.console2android.core.convert.PackageRuntime
import dev.console2android.core.convert.PackageStore
import dev.console2android.core.diag.BoundedEventLog
import dev.console2android.core.fx1.Fx1Samples
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.library.ImportError
import dev.console2android.core.library.ImportResult
import dev.console2android.core.library.JobFactory
import dev.console2android.core.library.JobStore
import dev.console2android.core.library.LibraryEntry
import dev.console2android.core.library.SourceKind
import dev.console2android.core.library.SourceLibrary
import dev.console2android.core.job.JobState
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.report.DiagnosticReport
import dev.console2android.core.report.ReportFormatter
import dev.console2android.core.xex.IdComparison
import dev.console2android.core.xex.SourceAnalyzer
import dev.console2android.core.xex.SourceFacts
import dev.console2android.core.xex.XexHeaderProbe
import dev.console2android.core.xex.XexIdentifierParser
import dev.console2android.core.xex.XexIdentifiers
import dev.console2android.core.xex.XexParseResult
import dev.console2android.core.xex.XexProbeResult
import dev.console2android.core.xex.XexReferenceCheck
import dev.console2android.core.xex.describe
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

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

/** Result of the last static source-file analysis (read-only: hashing + header probe, nothing is executed). */
private sealed interface AnalysisUi {
    data object Idle : AnalysisUi
    data object Running : AnalysisUi
    data class Done(val facts: SourceFacts, val probe: XexProbeResult) : AnalysisUi
    data class Failed(val message: String) : AnalysisUi
}

/** UI state shared by the screens. Background threads only write Compose state; no coroutines dependency needed. */
private class AppState(
    private val store: XexIdentifierStore,
    private val library: SourceLibrary,
    private val jobStore: JobStore,
    private val packages: PackageStore,
) {
    var ids by mutableStateOf<XexIdentifiers?>(store.load())
    var entries by mutableStateOf(library.list())
    var jobs by mutableStateOf(jobStore.list())
    var libraryMessage by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
    @Volatile private var cancelImport = false
    var importMessage by mutableStateOf<String?>(null)
    var analysis by mutableStateOf<AnalysisUi>(AnalysisUi.Idle)
    var converted by mutableStateOf(visiblePackages())
    var runningJobId by mutableStateOf<String?>(null)
    var jobMessage by mutableStateOf<String?>(null)
    var reportText by mutableStateOf<String?>(null)
    var runOutput by mutableStateOf<String?>(null)
    @Volatile private var cancelRun = false
    @Volatile private var pauseRun = false

    /** Only packages whose independent validation did not fail are offered as playable results. */
    private fun visiblePackages(): List<ConvertedPackage> =
        packages.list().filter { p -> p.status().let { it["fcv"] != null && it["fcv"] != "FAIL" && it["status"] != "FAILED" } }

    fun importIdentifiers(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        thread(name = "xex-id-import") {
            importMessage = try {
                val result = resolver.openInputStream(uri)?.use { XexIdentifierParser.parse(it) }
                when (result) {
                    null -> "Could not open the selected file."
                    is XexParseResult.Failure -> "Import rejected:\n" + result.errors.joinToString("\n") { "• " + it.message }
                    is XexParseResult.Success -> {
                        store.save(result.ids)
                        ids = result.ids
                        val s = result.ids.summary()
                        "Imported XEX1 (${s.xex1Length} chars, fp ${s.xex1Fingerprint}) and XEX2 (${s.xex2Length} chars, fp ${s.xex2Fingerprint})." +
                            result.warnings.joinToString("") { "\n• " + it.message }
                    }
                }
            } catch (e: Exception) {
                "Import failed (${e.javaClass.simpleName})."
            }
        }
    }

    /** Copies the selected file into private, content-addressed storage (streamed, hashed, atomic). Never executes it. */
    fun addToLibrary(context: Context, uri: Uri) {
        if (importing) return
        val resolver = context.applicationContext.contentResolver
        importing = true
        cancelImport = false
        libraryMessage = "Importing…"
        thread(name = "library-import") {
            libraryMessage = try {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val stream = resolver.openInputStream(uri)
                if (stream == null) "Could not open the selected file."
                else when (val r = stream.use { library.importSource(it, name, cancelled = { cancelImport }) }) {
                    is ImportResult.Added -> "Added ${r.entry.displayName} (${r.entry.sizeBytes} bytes, ${r.entry.kind})."
                    is ImportResult.AlreadyPresent -> "Already in the library: ${r.entry.displayName}."
                    is ImportResult.Failed -> when (r.error) {
                        ImportError.EMPTY -> "The file is empty."
                        ImportError.TOO_LARGE -> "The file is too large."
                        ImportError.INSUFFICIENT_SPACE -> "Not enough free storage to import this file."
                        ImportError.CANCELLED -> "Import cancelled."
                        ImportError.IO -> "Import failed (${r.detail ?: "I/O error"})."
                    }
                }
            } catch (e: Exception) {
                "Import failed (${e.javaClass.simpleName})."
            }
            entries = library.list()
            importing = false
        }
    }

    fun cancelLibraryImport() { cancelImport = true }

    /** Adds the built-in synthetic C2A-FX1 fixture (our own test container, not game content) to the library. */
    fun addSample() {
        if (importing) return
        importing = true
        thread(name = "library-sample") {
            libraryMessage = try {
                when (val r = library.importSource(ByteArrayInputStream(Fx1Samples.sumAndDouble()), Fx1Samples.SUM_AND_DOUBLE_NAME)) {
                    is ImportResult.Added -> "Added the sample fixture (${r.entry.sizeBytes} bytes)."
                    is ImportResult.AlreadyPresent -> "The sample fixture is already in the library."
                    is ImportResult.Failed -> "Could not add the sample (${r.error})."
                }
            } catch (e: Exception) {
                "Could not add the sample (${e.javaClass.simpleName})."
            }
            entries = library.list()
            importing = false
        }
    }

    private fun deviceInfo() = DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    )

    /** Runs (or resumes) a job on a background thread. One job at a time; pause/cancel are cooperative between stages. */
    fun runJob(job: ConversionJob) {
        if (runningJobId != null || job.state.isTerminal) return
        runningJobId = job.id
        cancelRun = false
        pauseRun = false
        jobMessage = "Running job ${job.id.take(8)}…"
        thread(name = "conversion-job") {
            jobMessage = try {
                val o = JobRunner(library, packages, jobStore, deviceInfo()).run(job, { cancelRun }, { pauseRun })
                reportText = null
                val problem = o.events.firstOrNull { it.event.severity >= dev.console2android.core.diag.Severity.ERROR }?.event?.message
                "Job ${o.job.id.take(8)}: ${o.job.state}" + (o.validation?.let { " · status ${it.status}" } ?: "") + (problem?.let { " · $it" } ?: "")
            } catch (e: Exception) {
                "Job failed unexpectedly (${e.javaClass.simpleName})."
            }
            jobs = jobStore.list()
            converted = visiblePackages()
            runningJobId = null
        }
    }

    fun pauseRunningJob() { pauseRun = true }

    fun showReport(job: ConversionJob) {
        val f = jobStore.workDir(job.id).resolve("report.txt")
        reportText = if (f.isFile) f.readText(Charsets.UTF_8) else "No report yet for this job."
    }

    /** Executes a converted package in the IR runtime (sandboxed interpreter; no native code, no file or network access). */
    fun runPackage(pkg: ConvertedPackage) {
        thread(name = "package-run") {
            runOutput = try {
                when (val l = PackageRuntime.load(pkg)) {
                    is PackageRuntime.LoadResult.Failed -> l.reason
                    is PackageRuntime.LoadResult.Ok -> {
                        val r = PackageRuntime.run(l.loaded)
                        "Stop: ${r.run.stop}" + (r.run.exitCode?.let { " (exit code $it)" } ?: "") +
                            "\nSteps: ${r.run.steps} (${r.fallbackSteps} via interpreter fallback)" +
                            "\nGuest output: ${r.run.output.joinToString(", ").ifEmpty { "none" }}" +
                            (r.run.detail?.let { "\nDetail: $it" } ?: "")
                    }
                }
            } catch (e: Exception) {
                "Run failed (${e.javaClass.simpleName})."
            }
        }
    }

    fun removePackage(pkg: ConvertedPackage) {
        packages.remove(pkg.id)
        converted = visiblePackages()
        runOutput = null
    }

    fun verifyEntry(entry: LibraryEntry) {
        libraryMessage = "Verifying ${entry.displayName}…"
        thread(name = "library-verify") {
            libraryMessage = if (library.verify(entry.sha256)) "${entry.displayName}: stored copy matches its SHA-256."
            else "${entry.displayName}: stored copy does NOT match its SHA-256 (corrupt or missing). Remove and re-import it."
        }
    }

    fun removeEntry(entry: LibraryEntry) {
        libraryMessage = if (library.remove(entry.sha256)) "Removed ${entry.displayName}." else "Could not remove ${entry.displayName}."
        entries = library.list()
    }

    /** Queues a job; start it from the Jobs tab. XEX reference fingerprints are attached as metadata only. */
    fun createJob(entry: LibraryEntry) {
        val job = JobFactory.create(entry, ConversionMode.STRICT, ids)
        try {
            jobStore.save(job)
            libraryMessage = "Job ${job.id.take(8)} queued for ${entry.displayName}."
        } catch (e: Exception) {
            libraryMessage = "Could not save the job (${e.javaClass.simpleName})."
        }
        jobs = jobStore.list()
    }

    fun cancelJob(job: ConversionJob) {
        if (runningJobId == job.id) { cancelRun = true; return }
        if (!job.state.isTerminal) {
            try { jobStore.save(job.cancel()) } catch (e: Exception) { libraryMessage = "Could not cancel the job (${e.javaClass.simpleName})." }
        }
        jobs = jobStore.list()
    }

    fun deleteJob(job: ConversionJob) {
        if (runningJobId == job.id) return
        jobStore.delete(job.id)
        jobs = jobStore.list()
    }

    fun clearIdentifiers() {
        store.clear()
        ids = null
        importMessage = "Identifiers removed from this device."
    }

    fun analyze(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        analysis = AnalysisUi.Running
        thread(name = "source-analysis") {
            analysis = try {
                val facts = resolver.openInputStream(uri)?.use { SourceAnalyzer.readFacts(it) }
                if (facts == null) AnalysisUi.Failed("Could not open the selected file.")
                else AnalysisUi.Done(facts, XexHeaderProbe.probe(facts.headPrefix, facts.sizeBytes))
            } catch (e: Exception) {
                AnalysisUi.Failed("Analysis failed (${e.javaClass.simpleName}).")
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
    val appContext = LocalContext.current.applicationContext
    val state = remember {
        val root = File(appContext.filesDir, "c2a")
        AppState(XexIdentifierStore(appContext), SourceLibrary(root), JobStore(root), PackageStore(root))
    }
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
                Section.LIBRARY -> LibraryScreen(state)
                Section.JOBS -> JobsScreen(state)
                Section.CONVERTED -> ConvertedScreen(state)
                Section.DIAGNOSTICS -> DiagnosticsScreen(state)
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
private fun LibraryScreen(state: AppState) {
    val context = LocalContext.current
    val pickIds = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) state.importIdentifiers(context, uri)
    }
    val pickLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) state.addToLibrary(context, uri)
    }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) state.analyze(context, uri)
    }
    val ids = state.ids
    val analysis = state.analysis

    Title("Source games")
    NotImplemented(
        "Only the built-in C2A-FX1 sample fixture can be converted today. Xbox 360 executables (XEX1/XEX2) can be stored, " +
            "analysed and compared with the reference identifiers, but they are not converted.",
    )

    Text("XEX reference identifiers", style = MaterialTheme.typography.titleMedium)
    Text(
        "Import a .txt file with two lines, XEX1: <code> and XEX2: <code>. The values are treated as opaque identifiers: " +
            "stored privately on this device, never exported in clear and never used to decrypt or unlock anything.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { pickIds.launch(arrayOf("*/*")) }) { Text("Import .txt") }
        Button(onClick = { state.clearIdentifiers() }, enabled = ids != null) { Text("Remove") }
    }
    if (ids != null) {
        val s = ids.summary()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("XEX1: ${s.xex1Length} chars · fingerprint ${s.xex1Fingerprint}")
                Text("XEX2: ${s.xex2Length} chars · fingerprint ${s.xex2Fingerprint}")
            }
        }
    } else {
        Text("No identifiers imported.", style = MaterialTheme.typography.bodySmall)
    }
    state.importMessage?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } }

    Text("Source library", style = MaterialTheme.typography.titleMedium)
    Text(
        "Files are copied once into private app storage, identified by SHA-256 and kept read-only. Nothing is executed.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { pickLibrary.launch(arrayOf("*/*")) }, enabled = !state.importing) { Text("Add file…") }
        Button(onClick = { state.cancelLibraryImport() }, enabled = state.importing) { Text("Cancel import") }
        Button(onClick = { state.addSample() }, enabled = !state.importing) { Text("Add sample") }
    }
    state.libraryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.entries.isEmpty()) Text("The library is empty.", style = MaterialTheme.typography.bodySmall)
    state.entries.forEach { e ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.displayName, style = MaterialTheme.typography.titleSmall)
                Text("${e.sizeBytes} bytes · ${e.kind} · SHA-256 ${e.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                Text(e.probeNote, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.createJob(e) }, enabled = e.kind == SourceKind.C2AFX1) { Text("Create job") }
                    Button(onClick = { state.verifyEntry(e) }) { Text("Verify") }
                    Button(onClick = { state.removeEntry(e) }) { Text("Remove") }
                }
            }
        }
    }

    Text("Static file analysis", style = MaterialTheme.typography.titleMedium)
    Text(
        "Streams the file once to compute size and SHA-256 and inspects the XEX header if present. The file is not copied, " +
            "modified or executed.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = { pickSource.launch(arrayOf("*/*")) }, enabled = analysis !is AnalysisUi.Running) { Text("Analyze file…") }
    when (analysis) {
        AnalysisUi.Idle -> Unit
        AnalysisUi.Running -> Text("Analyzing…")
        is AnalysisUi.Failed -> NotImplemented(analysis.message)
        is AnalysisUi.Done -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Size: ${analysis.facts.sizeBytes} bytes")
                SelectionContainer { Text("SHA-256: ${analysis.facts.sha256}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(analysis.probe.describe(), style = MaterialTheme.typography.bodySmall)
                if (ids != null) {
                    val c = XexReferenceCheck.compare(ids, analysis.facts.sha256)
                    Text("XEX1 reference: ${comparisonLabel(c.xex1)}")
                    Text("XEX2 reference: ${comparisonLabel(c.xex2)}")
                } else {
                    Text("Import XEX1/XEX2 identifiers to compare them with this file.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun comparisonLabel(c: IdComparison): String = when (c) {
    IdComparison.MATCHES_SOURCE_SHA256 -> "matches the file's SHA-256"
    IdComparison.DIFFERS_FROM_SOURCE_SHA256 -> "looks like a SHA-256 but DIFFERS from the file's"
    IdComparison.NOT_COMPARABLE -> "not a SHA-256: not comparable (no match claimed)"
}

@Composable
private fun JobsScreen(state: AppState) {
    Title("Conversion jobs")
    Text(
        if (state.ids != null) "XEX1/XEX2 reference identifiers are loaded and will be attached to new jobs (metadata only)."
        else "No XEX1/XEX2 reference identifiers imported (Library).",
        style = MaterialTheme.typography.bodySmall,
    )
    NotImplemented(
        "Jobs run the proof-of-concept pipeline (validate, analyse, extract, translate to C2A-IR, package, final validation) " +
            "for C2A-FX1 sources only. A paused job continues from its verified checkpoints.",
    )
    state.jobMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.jobs.isEmpty()) Text("No jobs. Create one from a library entry.", style = MaterialTheme.typography.bodySmall)
    state.jobs.forEach { j ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Job ${j.id.take(8)} · ${j.state} · ${j.mode}", style = MaterialTheme.typography.titleSmall)
                Text("Source SHA-256 ${j.sourceSha256.take(16)}… · backend: ${j.backend}", style = MaterialTheme.typography.bodySmall)
                j.reference?.let { r ->
                    Text(
                        "XEX1 ${r.ids.xex1Fingerprint}: ${r.comparison?.xex1?.let(::comparisonLabel) ?: "not compared"}\n" +
                            "XEX2 ${r.ids.xex2Fingerprint}: ${r.comparison?.xex2?.let(::comparisonLabel) ?: "not compared"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val running = state.runningJobId == j.id
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.runJob(j) }, enabled = !j.state.isTerminal && state.runningJobId == null) {
                        Text(if (j.state.isSuspended) "Resume" else "Run")
                    }
                    Button(onClick = { state.pauseRunningJob() }, enabled = running) { Text("Pause") }
                    Button(onClick = { state.cancelJob(j) }, enabled = running || !j.state.isTerminal) { Text("Cancel") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.showReport(j) }) { Text("Report") }
                    Button(onClick = { state.deleteJob(j) }, enabled = !running) { Text("Remove") }
                }
            }
        }
    }
    state.reportText?.let { r -> SelectionContainer { Text(r, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
    SelectionContainer {
        Text(JobState.PIPELINE.joinToString("  →  ") { it.name }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConvertedScreen(state: AppState) {
    Title("Converted games")
    NotImplemented(
        "Only packages whose independent validation did not fail appear here, separate from source games. " +
            "Packages run in the IR runtime: there is no graphics, audio or input yet, so only the sample fixture is meaningful.",
    )
    if (state.converted.isEmpty()) Text("No converted packages.", style = MaterialTheme.typography.bodySmall)
    state.converted.forEach { p ->
        val st = p.status()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Package ${p.id.take(12)}… · ${p.mode}", style = MaterialTheme.typography.titleSmall)
                Text("Status ${st["status"] ?: "?"} · validator ${st["fcv"] ?: "?"} · ${p.manifest["instructions"] ?: "?"} instructions · unsupported ${st["unsupported"] ?: "?"}", style = MaterialTheme.typography.bodySmall)
                Text("Source SHA-256 ${p.sourceSha256.take(16)}… · ${p.manifest["translator"]}", style = MaterialTheme.typography.bodySmall)
                Text("Scope: the status reflects the implemented checks on the synthetic fixture only; it makes no claim about game compatibility.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.runPackage(p) }) { Text("Run") }
                    Button(onClick = { state.removePackage(p) }) { Text("Remove") }
                }
            }
        }
    }
    state.runOutput?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
}

private fun environmentReport(state: AppState): DiagnosticReport = DiagnosticReport(
    reportId = UUID.randomUUID().toString(),
    gameId = null,
    packageVersion = null,
    runtimeVersion = "none (Phase 1 shell)",
    sourceSha256 = (state.analysis as? AnalysisUi.Done)?.facts?.sha256,
    device = DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    ),
    status = null,
    fcv = null,
    events = reportEvents(state),
    createdAtEpochMs = System.currentTimeMillis(),
    xexReference = state.ids?.let { XexReferenceCheck.summarize(it, (state.analysis as? AnalysisUi.Done)?.facts?.sha256) },
)

/** Probe issues and reference-comparison events of the last analysis, deduplicated by the bounded log. */
private fun reportEvents(state: AppState) = BoundedEventLog(64).also { log ->
    val a = state.analysis as? AnalysisUi.Done ?: return@also
    when (val p = a.probe) {
        is XexProbeResult.Parsed -> p.issues.forEach(log::record)
        is XexProbeResult.Malformed -> p.issues.forEach(log::record)
        else -> Unit
    }
    state.ids?.let { XexReferenceCheck.events(XexReferenceCheck.compare(it, a.facts.sha256)).forEach(log::record) }
}.snapshot()

private fun shareText(context: Context, subject: String, body: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

@Composable
private fun DiagnosticsScreen(state: AppState) {
    val context = LocalContext.current
    val report = remember(state.ids, state.analysis) { environmentReport(state) }
    val text = remember(report) { ReportFormatter.toText(report) }
    Title("Diagnostics")
    Text("Environment report. Nothing is sent automatically; export is manual.")
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
