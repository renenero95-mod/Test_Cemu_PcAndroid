package info.cemu.cemu.common.io

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

fun unzip(stream: InputStream, targetDir: String) {
    ZipInputStream(stream).use { zipInputStream ->
        val buffer = ByteArray(8192)

        var zipEntry: ZipEntry? = zipInputStream.nextEntry

        while (zipEntry != null) {
            extractZipEntry(zipInputStream, zipEntry, buffer, targetDir)
            zipEntry = zipInputStream.nextEntry
        }
    }
}

fun unzip(stream: InputStream, targetDir: Path) = unzip(stream, targetDir.toString())

private fun extractZipEntry(
    zipInputStream: ZipInputStream,
    zipEntry: ZipEntry,
    buffer: ByteArray,
    targetDir: String,
) {
    val targetRoot = Paths.get(targetDir).toAbsolutePath().normalize()
    val entryPath = targetRoot.resolve(zipEntry.name.trimStart('/', '\\')).normalize()
    if (!entryPath.startsWith(targetRoot)) {
        throw IOException("Zip entry is outside of the target directory: ${zipEntry.name}")
    }
    val file = entryPath.toFile()
    if (zipEntry.isDirectory) {
        file.apply { if (!isDirectory) mkdirs() }
        return
    }
    // some zip files do not contain entries for directories
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { fileOutputStream ->
        var bytesRead: Int
        while ((zipInputStream.read(buffer).also { bytesRead = it }) > 0) {
            fileOutputStream.write(buffer, 0, bytesRead)
        }
    }
}
