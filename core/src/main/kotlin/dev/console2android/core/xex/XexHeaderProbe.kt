package dev.console2android.core.xex

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.Severity

/** One raw optional-header table entry. Not interpreted: [key] and [value] are the two big-endian u32 words. */
data class XexOptionalHeader(val key: Long, val value: Long)

/** Raw XEX2 base-header words (big-endian u32, shown unsigned). Flags are NOT interpreted. */
data class XexHeader(
    val magic: String,
    val moduleFlags: Long,
    val peDataOffset: Long,
    val securityInfoOffset: Long,
    val optionalHeaderCount: Long,
    val optionalHeaders: List<XexOptionalHeader>,
    /** True when the table extends beyond the inspected prefix, so [optionalHeaders] is incomplete. */
    val optionalHeadersTruncated: Boolean,
)

sealed interface XexProbeResult {
    /** Magic is neither `XEX1` nor `XEX2`. Says nothing about whether the file is valid for any other purpose. */
    data object NotXex : XexProbeResult

    /** Magic recognised but this build does not parse that variant. Reported, never guessed. */
    data class UnsupportedVariant(val magic: String, val note: String) : XexProbeResult

    /** Base header read and bounds-checked. Section/encryption state is NOT analysed: treat contents as opaque. */
    data class Parsed(val header: XexHeader, val issues: List<DiagnosticEvent>) : XexProbeResult

    data class Malformed(val magic: String, val issues: List<DiagnosticEvent>) : XexProbeResult
}

/**
 * Static, read-only inspection of the first bytes of a candidate XEX file. Status: **experimental**.
 *
 * Reads only the base header words of the publicly documented XEX2 layout:
 * `magic, moduleFlags, peDataOffset, reserved, securityInfoOffset, optionalHeaderCount`, followed by
 * `count` entries of `(key, value)` u32 pairs; everything big-endian. Every offset/length is bounds-checked
 * against the real file size. It never decrypts, decompresses, executes or interprets payload data, and
 * has no key handling. Only synthetic buffers are used in tests (no game data in the repository), so the
 * layout has not been validated against real files here.
 */
object XexHeaderProbe {
    const val BASE_HEADER_SIZE = 0x18
    const val ENTRY_SIZE = 8
    const val MAX_OPTIONAL_HEADERS = 0x400L
    private const val MODULE = "xex-probe"
    private const val STAGE = "analyze"

    private fun ev(code: String, sev: Severity, msg: String) =
        DiagnosticEvent(code, sev, FailureDomain.ASSET_FORMAT, MODULE, STAGE, msg)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    /** [prefix] = first bytes of the file (any length), [fileSize] = real total size in bytes. */
    fun probe(prefix: ByteArray, fileSize: Long): XexProbeResult {
        if (prefix.size < 4) return XexProbeResult.NotXex
        val magic = String(prefix, 0, 4, Charsets.ISO_8859_1)
        if (magic != "XEX1" && magic != "XEX2") return XexProbeResult.NotXex
        if (magic == "XEX1") {
            return XexProbeResult.UnsupportedVariant(
                magic, "XEX1 header recognised by magic only; layout parsing is not implemented. Contents treated as opaque.",
            )
        }

        val issues = mutableListOf<DiagnosticEvent>()
        if (fileSize < BASE_HEADER_SIZE || prefix.size < BASE_HEADER_SIZE) {
            issues += ev("XEX-TRUNCATED", Severity.ERROR, "File is shorter than the $BASE_HEADER_SIZE-byte XEX2 base header.")
            return XexProbeResult.Malformed(magic, issues)
        }
        val flags = u32(prefix, 4)
        val peOff = u32(prefix, 8)
        val secOff = u32(prefix, 16)
        val count = u32(prefix, 20)

        var fatal = false
        if (peOff < BASE_HEADER_SIZE || peOff > fileSize) {
            issues += ev("XEX-PE-OFFSET", Severity.ERROR, "PE data offset $peOff is outside the valid range [$BASE_HEADER_SIZE, $fileSize].")
            fatal = true
        }
        if (secOff < BASE_HEADER_SIZE || secOff >= fileSize) {
            issues += ev("XEX-SEC-OFFSET", Severity.ERROR, "Security info offset $secOff is outside the file ($fileSize bytes).")
            fatal = true
        }
        if (count > MAX_OPTIONAL_HEADERS) {
            issues += ev("XEX-OPT-COUNT", Severity.ERROR, "Optional header count $count exceeds the sanity limit $MAX_OPTIONAL_HEADERS.")
            fatal = true
        }
        if (!fatal) {
            val tableEnd = BASE_HEADER_SIZE + count * ENTRY_SIZE
            if (tableEnd > peOff) {
                issues += ev("XEX-OPT-TABLE", Severity.ERROR, "Optional header table ends at $tableEnd, past the header size $peOff.")
                fatal = true
            }
        }
        if (fatal) return XexProbeResult.Malformed(magic, issues)

        val entries = ArrayList<XexOptionalHeader>()
        var truncated = false
        for (i in 0 until count.toInt()) {
            val off = BASE_HEADER_SIZE + i * ENTRY_SIZE
            if (off + ENTRY_SIZE > prefix.size) { truncated = true; break }
            entries += XexOptionalHeader(u32(prefix, off), u32(prefix, off + 4))
        }
        if (truncated) {
            issues += ev("XEX-PREFIX-LIMIT", Severity.WARNING, "Only ${entries.size} of $count optional headers lie within the inspected prefix.")
        }
        issues += ev(
            "XEX-OPAQUE", Severity.INFO,
            "Section table, compression and encryption state were not analysed; payload is reported as opaque.",
        )
        return XexProbeResult.Parsed(XexHeader(magic, flags, peOff, secOff, count, entries, truncated), issues)
    }
}

/** Short, user-readable (and report-safe) description of a probe result. */
fun XexProbeResult.describe(): String = when (this) {
    is XexProbeResult.NotXex -> "Not an XEX file (magic is not XEX1/XEX2)."
    is XexProbeResult.UnsupportedVariant -> "$magic: $note"
    is XexProbeResult.Malformed -> "$magic header is malformed: " + issues.joinToString(" ") { it.message }
    is XexProbeResult.Parsed ->
        "${header.magic} base header read (EXPERIMENTAL): header size ${header.peDataOffset} B, " +
            "${header.optionalHeaderCount} optional headers" + (if (header.optionalHeadersTruncated) " (partly inspected)" else "") +
            ". Payload/encryption not analysed (opaque)."
}
