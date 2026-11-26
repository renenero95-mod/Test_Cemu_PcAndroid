package dev.console2android.core.xex

import java.io.InputStream
import java.security.MessageDigest

/** Facts gathered in one streaming pass. [headPrefix] is at most [SourceAnalyzer.PREFIX_BYTES] long. */
class SourceFacts(val sizeBytes: Long, val sha256: String, val headPrefix: ByteArray)

data class SourceAnalysis(val facts: SourceFacts, val probe: XexProbeResult, val reference: XexReferenceSummary?)

/**
 * Static analysis of a user-selected file: streaming SHA-256 + size + header probe. The file is only read
 * (never executed, never copied, never modified); memory use is bounded by [PREFIX_BYTES] regardless of file size.
 */
object SourceAnalyzer {
    const val PREFIX_BYTES = 64 * 1024

    fun readFacts(input: InputStream): SourceFacts {
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            if (prefix.size() < PREFIX_BYTES) prefix.write(buf, 0, minOf(n, PREFIX_BYTES - prefix.size()))
            total += n
        }
        return SourceFacts(total, md.digest().toHex(), prefix.toByteArray())
    }

    fun analyze(input: InputStream, ids: XexIdentifiers?): SourceAnalysis {
        val facts = readFacts(input)
        val probe = XexHeaderProbe.probe(facts.headPrefix, facts.sizeBytes)
        return SourceAnalysis(facts, probe, ids?.let { XexReferenceCheck.summarize(it, facts.sha256) })
    }
}
