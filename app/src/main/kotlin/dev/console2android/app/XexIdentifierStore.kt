package dev.console2android.app

import android.content.Context
import dev.console2android.core.xex.XexIdentifierParser
import dev.console2android.core.xex.XexIdentifiers
import dev.console2android.core.xex.XexParseResult
import java.io.File
import java.io.IOException

/**
 * App-private persistence of the imported XEX1/XEX2 reference identifiers (excluded from backups, see manifest).
 * Stored in canonical text form and re-validated by the same core parser on load.
 */
class XexIdentifierStore(context: Context) {
    private val dir: File = context.noBackupFilesDir
    private val file = File(dir, "xex_identifiers.txt")

    fun load(): XexIdentifiers? {
        if (!file.isFile) return null
        return try {
            (XexIdentifierParser.parse(file.readBytes()) as? XexParseResult.Success)?.ids
        } catch (e: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(ids: XexIdentifiers) {
        val tmp = File(dir, "xex_identifiers.txt.tmp")
        tmp.writeText(XexIdentifierParser.toCanonicalText(ids), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace identifier store")
        }
    }

    fun clear() {
        file.delete()
    }
}
