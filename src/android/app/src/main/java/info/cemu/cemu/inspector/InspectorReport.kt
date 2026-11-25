package info.cemu.cemu.inspector

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds log_inspector_point.txt from the log of the last game session (log.txt) and the scan that Cemu made before
 * the game started (inspector/<title id>_..._scan.txt). It runs in the background when the user is back in the menu.
 */
object InspectorReport {
    const val REPORT_FILE_NAME = "log_inspector_point.txt"
    private const val LOG_FILE_NAME = "log.txt"
    private const val INSPECTOR_FOLDER_NAME = "inspector"
    private const val MAX_LISTED_ITEMS = 40
    private const val MAX_CRASH_LINES = 450
    private const val MAX_HEADER_LINES = 90
    private const val MAX_SCAN_LINES = 120

    /** true when there is a game session log that has no report yet */
    fun isReportNeeded(userDataFolder: File): Boolean {
        val log = File(userDataFolder, LOG_FILE_NAME)
        if (!log.isFile || log.length() == 0L) {
            return false
        }
        val report = File(userDataFolder, REPORT_FILE_NAME)
        return !report.isFile || report.lastModified() < log.lastModified()
    }

    fun reportFile(userDataFolder: File): File = File(userDataFolder, REPORT_FILE_NAME)

    fun generate(userDataFolder: File, onProgress: (Int) -> Unit): File? {
        val log = File(userDataFolder, LOG_FILE_NAME)
        if (!log.isFile) {
            return null
        }
        onProgress(0)
        val lines = log.readLines()
        onProgress(20)

        val unsupportedCalls = LinkedHashMap<String, Int>()
        val filesNotFound = LinkedHashMap<String, Int>()
        val pipelineFailures = ArrayList<String>()
        val vulkanLines = ArrayList<String>()
        var titleId: String? = null
        var runTitleIndex = -1
        var crashIndex = -1

        for ((index, line) in lines.withIndex()) {
            if (index % 4000 == 0) {
                onProgress(20 + (index.toLong() * 50L / lines.size.coerceAtLeast(1)).toInt())
            }
            if (titleId == null && line.contains("Mounting title ")) {
                titleId = line.substringAfter("Mounting title ").trim().take(16).lowercase(Locale.ROOT)
            }
            if (runTitleIndex < 0 && line.contains("Run title")) {
                runTitleIndex = index
            }
            if (crashIndex < 0 && (line.startsWith("Error: signal") || line.contains("Unhandled C++ exception"))) {
                crashIndex = index
            }
            if (line.contains("Unsupported lib call: ")) {
                val name = line.substringAfter("Unsupported lib call: ").trim()
                unsupportedCalls[name] = (unsupportedCalls[name] ?: 0) + 1
            } else if (line.contains("FS: not found: ")) {
                val path = line.substringAfter("FS: not found: ").trim()
                filesNotFound[path] = (filesNotFound[path] ?: 0) + 1
            } else if (line.contains("Failed to create graphics pipeline")) {
                pipelineFailures.add(line.trim())
            } else if (line.contains("Vulkan:") && vulkanLines.size < 40) {
                vulkanLines.add(line.trim())
            }
        }
        onProgress(75)

        val report = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        report.appendLine("=== Cemu inspector point ===")
        report.appendLine("Generated: ${dateFormat.format(Date())}")
        report.appendLine("Session log: $LOG_FILE_NAME, ${log.length()} bytes, ${lines.size} lines, last modified ${dateFormat.format(Date(log.lastModified()))}")

        if (runTitleIndex < 0) {
            report.appendLine()
            report.appendLine("The last log does not contain a game session (no 'Run title' line).")
            return writeReport(userDataFolder, report, onProgress)
        }

        val crashed = crashIndex >= 0
        report.appendLine("Result: " + if (crashed) "the session ended with a CRASH" else "no crash recorded (the game was closed or returned to the menu)")

        report.appendLine()
        report.appendLine("=== Game and settings (start of the log) ===")
        for (line in lines.take(minOf(runTitleIndex + 1, MAX_HEADER_LINES))) {
            report.appendLine(line)
        }

        if (crashed) {
            report.appendLine()
            report.appendLine("=== Crash ===")
            for (line in lines.drop(crashIndex).take(MAX_CRASH_LINES)) {
                report.appendLine(line)
            }
        }

        val logText = lines.joinToString("\n")
        report.appendLine()
        report.appendLine("=== Hints ===")
        report.append(buildHints(logText, crashed, pipelineFailures.size, unsupportedCalls.size))

        report.appendLine()
        report.appendLine("=== Unsupported API calls (${unsupportedCalls.size} different) ===")
        appendTopCounts(report, unsupportedCalls)

        report.appendLine()
        report.appendLine("=== Files or folders the game looked for and did not find (${filesNotFound.size} different) ===")
        appendTopCounts(report, filesNotFound)

        report.appendLine()
        report.appendLine("=== Graphics ===")
        report.appendLine("Pipeline creation failures: ${pipelineFailures.size}")
        for (line in pipelineFailures.take(MAX_LISTED_ITEMS)) {
            report.appendLine(line)
        }
        if (pipelineFailures.isNotEmpty()) {
            report.appendLine("The shaders of the failed pipelines are saved in the folder shaderDump/failedPipelines")
        }
        for (line in vulkanLines) {
            report.appendLine(line)
        }
        onProgress(90)

        appendScanReport(report, userDataFolder, titleId)
        return writeReport(userDataFolder, report, onProgress)
    }

    private fun appendTopCounts(report: StringBuilder, counts: Map<String, Int>) {
        if (counts.isEmpty()) {
            report.appendLine("(none)")
            return
        }
        val sorted = counts.entries.sortedByDescending { it.value }
        for (entry in sorted.take(MAX_LISTED_ITEMS)) {
            report.appendLine("${entry.value}x ${entry.key}")
        }
        if (sorted.size > MAX_LISTED_ITEMS) {
            report.appendLine("... and ${sorted.size - MAX_LISTED_ITEMS} more")
        }
    }

    private fun buildHints(logText: String, crashed: Boolean, pipelineFailures: Int, unsupportedCalls: Int): String {
        val hints = StringBuilder()
        if (logText.contains("NULL POINTER ACCESS")) {
            hints.appendLine("- The game read through a null pointer. In the crash section look at 'Where r.. came from' and at 'Calls made shortly before': the function named there probably returned 0.")
            hints.appendLine("  Settings > General > Experimental > 'Map null page' makes such reads return zeros instead of closing the game.")
        }
        if (crashed && logText.contains("address not mapped")) {
            hints.appendLine("- The crash is an access to memory that is not mapped (see 'Fault address' in the crash section).")
        }
        if (logText.contains("Unhandled C++ exception")) {
            hints.appendLine("- A C++ exception was not handled: its text is in the crash section.")
        }
        if (logText.contains("Load shared libraries: true")) {
            hints.appendLine("- 'Load shared libraries' is on for this game. The real system libraries from cafeLibs make several games crash or show a system update message: try the game with it off.")
        }
        if (pipelineFailures > 0) {
            hints.appendLine("- $pipelineFailures graphics pipelines could not be created by the driver: the objects that use them are not drawn. Send the files of shaderDump/failedPipelines.")
        }
        if (logText.contains("vertex attribute divisor unsupported")) {
            hints.appendLine("- The GPU driver does not support vertex attribute divisors: Cemu emulates them when 'Emulate vertex attribute divisors' is on.")
        }
        if (unsupportedCalls > 0) {
            hints.appendLine("- The game called $unsupportedCalls system functions that Cemu does not implement (list below). The last ones before a crash are the most interesting.")
        }
        if (hints.isEmpty()) {
            hints.appendLine("- Nothing special found in the log.")
        }
        return hints.toString()
    }

    private fun appendScanReport(report: StringBuilder, userDataFolder: File, titleId: String?) {
        report.appendLine()
        report.appendLine("=== Scan made before the game started ===")
        val scanFolder = File(userDataFolder, INSPECTOR_FOLDER_NAME)
        val scanFile = scanFolder.listFiles { file -> file.isFile && file.name.endsWith("_scan.txt") && (titleId == null || file.name.startsWith(titleId)) }
            ?.maxByOrNull { it.lastModified() }
        if (scanFile == null) {
            report.appendLine("(no scan found: it is made when a game starts and the game inspector setting is on)")
            return
        }
        for (line in scanFile.readLines().take(MAX_SCAN_LINES)) {
            report.appendLine(line)
        }
    }

    private fun writeReport(userDataFolder: File, report: StringBuilder, onProgress: (Int) -> Unit): File {
        val file = File(userDataFolder, REPORT_FILE_NAME)
        file.writeText(report.toString())
        onProgress(100)
        return file
    }
}
