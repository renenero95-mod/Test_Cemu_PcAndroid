package info.cemu.cemu.titlemanager.usecases

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import info.cemu.cemu.common.io.unzip
import info.cemu.cemu.nativeinterface.NativeGameTitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.random.nextUInt

data class ImportResult(
    val installed: Int,
    val skipped: Int,
    val failed: Int,
    val notEnoughSpace: Boolean = false,
)

/**
 * Imports updates / DLC (or any title in the code, content, meta folder layout) from a .zip file or from a folder:
 * the content is extracted (or copied), every title folder found inside is moved to its place in the mlc folder
 * and added to the title list. A single archive or folder can contain more than one title.
 */
class ImportTitlesUseCase(
    private val scope: CoroutineScope,
    private val mlcPath: Path,
) {
    private val _inProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = _inProgress

    fun importFromZip(context: Context, zipUri: Uri, callback: (ImportResult) -> Unit) =
        runImport(callback) { tempDir ->
            val zipSize = DocumentFile.fromSingleUri(context, zipUri)?.length() ?: 0L
            // the extracted files need at least as much space as the archive, the compressed data usually needs more
            if (zipSize * 2 > tempDir.parentFile!!.freeSpace) {
                false
            } else {
                val inputStream = context.contentResolver.openInputStream(zipUri)
                    ?: throw IOException("Cannot open the selected file")
                inputStream.use { unzip(it, tempDir.path) }
                true
            }
        }

    /** Looks for titles (also inside sub folders) in the selected folder and imports only the title folders it finds. */
    fun importFromFolder(context: Context, folderUri: Uri, callback: (ImportResult) -> Unit) =
        runImport(callback) { tempDir ->
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw IOException("Cannot open the selected folder")
            val titleFolders = ArrayList<DocumentFile>()
            findTitleRootDocuments(folder, MAX_SEARCH_DEPTH, titleFolders)
            for ((index, titleFolder) in titleFolders.withIndex()) {
                copyDocumentFolder(context, titleFolder, File(tempDir, "title_$index"))
            }
            true
        }

    private fun runImport(callback: (ImportResult) -> Unit, prepare: (File) -> Boolean) {
        if (_inProgress.value) return
        _inProgress.value = true

        scope.launch(Dispatchers.IO) {
            val mlcDir = mlcPath.toFile()
            val tempDir = File(mlcDir, "import_tmp_${Random.nextUInt()}")
            var installed = 0
            var skipped = 0
            var failed = 0
            var notEnoughSpace = false

            try {
                tempDir.mkdirs()
                if (!prepare(tempDir)) {
                    notEnoughSpace = true
                } else {
                    val titleRoots = ArrayList<File>()
                    findTitleRoots(tempDir, MAX_SEARCH_DEPTH, titleRoots)
                    for (titleRoot in titleRoots) {
                        when (installTitle(titleRoot)) {
                            TitleImportOutcome.INSTALLED -> installed++
                            TitleImportOutcome.SKIPPED -> skipped++
                            TitleImportOutcome.FAILED -> failed++
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true)
                    notEnoughSpace = true
                else
                    failed++
            } finally {
                tempDir.deleteRecursively()
                _inProgress.value = false
            }

            callback(ImportResult(installed, skipped, failed, notEnoughSpace))
        }
    }

    private fun copyDocumentFolder(context: Context, folder: DocumentFile, destination: File) {
        destination.mkdirs()
        for (child in folder.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyDocumentFolder(context, child, File(destination, name))
            } else {
                val inputStream = context.contentResolver.openInputStream(child.uri) ?: continue
                inputStream.use { input ->
                    FileOutputStream(File(destination, name)).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun findTitleRootDocuments(folder: DocumentFile, depth: Int, roots: MutableList<DocumentFile>) {
        val children = folder.listFiles()
        val folderNames = children.filter { it.isDirectory }.mapNotNull { it.name?.lowercase() }
        if ("code" in folderNames && "content" in folderNames && "meta" in folderNames) {
            roots.add(folder)
            return
        }
        if (depth <= 0) return
        for (child in children) {
            if (child.isDirectory) findTitleRootDocuments(child, depth - 1, roots)
        }
    }

    private enum class TitleImportOutcome {
        INSTALLED,
        SKIPPED,
        FAILED,
    }

    private fun installTitle(titleRoot: File): TitleImportOutcome {
        val status = NativeGameTitles.checkIfTitleExists(titleRoot.absolutePath)
            ?: return TitleImportOutcome.FAILED

        // the same or a newer version is already installed
        if (status.existsError is NativeGameTitles.TitleExistsError.SameVersion ||
            status.existsError is NativeGameTitles.TitleExistsError.NewVersion
        ) {
            return TitleImportOutcome.SKIPPED
        }

        val target = File(status.targetLocation)
        val backup = File(target.parentFile, target.name + ".import_backup")
        val hadPreviousVersion = target.exists()

        return try {
            target.parentFile?.mkdirs()
            backup.deleteRecursively()
            if (hadPreviousVersion && !target.renameTo(backup)) {
                throw IOException("Failed to back up the existing installation")
            }
            try {
                if (!titleRoot.renameTo(target)) {
                    titleRoot.copyRecursively(target, overwrite = true)
                    titleRoot.deleteRecursively()
                }
            } catch (e: Exception) {
                target.deleteRecursively()
                if (hadPreviousVersion) backup.renameTo(target)
                throw e
            }
            backup.deleteRecursively()
            NativeGameTitles.addTitleFromPath(target.absolutePath)
            TitleImportOutcome.INSTALLED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TitleImportOutcome.FAILED
        }
    }

    // a title folder contains the code, content and meta folders; the archive may wrap them in other folders
    private fun findTitleRoots(dir: File, depth: Int, roots: MutableList<File>) {
        val children = dir.listFiles() ?: return
        val folderNames = children.filter { it.isDirectory }.map { it.name.lowercase() }
        if ("code" in folderNames && "content" in folderNames && "meta" in folderNames) {
            roots.add(dir)
            return
        }
        if (depth <= 0) return
        for (child in children) {
            if (child.isDirectory) findTitleRoots(child, depth - 1, roots)
        }
    }

    private companion object {
        const val MAX_SEARCH_DEPTH = 4
    }
}
