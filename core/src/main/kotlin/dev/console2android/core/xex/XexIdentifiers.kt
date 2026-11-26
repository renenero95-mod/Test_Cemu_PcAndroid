package dev.console2android.core.xex

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * User-supplied reference identifiers read from an imported `.txt` file:
 *
 * ```
 * XEX1: <code>
 * XEX2: <code>
 * ```
 *
 * The values are **opaque identifiers**. They are validated, fingerprinted, stored, reported and compared with
 * facts computed by static analysis (see [XexReferenceCheck]). They are never used as keys, never used to decrypt
 * or unlock anything, and nothing is executed.
 *
 * [toString] never prints the raw values (they may be sensitive); reports use [XexIdentifierSummary].
 */
class XexIdentifiers(val xex1: String, val xex2: String) {
    init {
        require(XexIdentifierParser.validateValue(xex1) == null) { "invalid XEX1 value" }
        require(XexIdentifierParser.validateValue(xex2) == null) { "invalid XEX2 value" }
    }

    fun summary(): XexIdentifierSummary = XexIdentifierSummary(
        xex1Length = xex1.length, xex1Fingerprint = fingerprint(XexIdentifierParser.KEY_1, xex1),
        xex2Length = xex2.length, xex2Fingerprint = fingerprint(XexIdentifierParser.KEY_2, xex2),
        identical = xex1 == xex2,
    )

    override fun equals(other: Any?): Boolean = other is XexIdentifiers && other.xex1 == xex1 && other.xex2 == xex2
    override fun hashCode(): Int = 31 * xex1.hashCode() + xex2.hashCode()
    override fun toString(): String = "XexIdentifiers(xex1=<${xex1.length} chars>, xex2=<${xex2.length} chars>)"

    companion object {
        /** Short, domain-separated SHA-256 label (8 hex chars): enough to tell values apart, too short to be a usable secret oracle. */
        fun fingerprint(key: String, value: String): String =
            sha256Hex("C2A-XEX-ID-v1|$key|$value".toByteArray(Charsets.UTF_8)).substring(0, 8)
    }
}

/** What may leave the device in reports/UI: lengths and short fingerprints only, never the raw values. */
data class XexIdentifierSummary(
    val xex1Length: Int,
    val xex1Fingerprint: String,
    val xex2Length: Int,
    val xex2Fingerprint: String,
    val identical: Boolean,
)

enum class XexParseErrorCode {
    FILE_TOO_LARGE, EMPTY_FILE, INVALID_UTF8, MISSING_XEX1, MISSING_XEX2, DUPLICATE_XEX1, DUPLICATE_XEX2,
    EMPTY_VALUE, VALUE_TOO_LONG, INVALID_CHARACTERS,
}

enum class XexParseWarningCode { UNRECOGNIZED_LINE, IDENTICAL_VALUES }

/** [line] is 1-based when the problem is tied to a line. The offending text is never echoed. */
data class XexParseError(val code: XexParseErrorCode, val line: Int? = null, val key: String? = null) {
    val message: String
        get() = when (code) {
            XexParseErrorCode.FILE_TOO_LARGE -> "File is larger than ${XexIdentifierParser.MAX_FILE_BYTES} bytes."
            XexParseErrorCode.EMPTY_FILE -> "File is empty."
            XexParseErrorCode.INVALID_UTF8 -> "File is not valid UTF-8 text."
            XexParseErrorCode.MISSING_XEX1 -> "Field XEX1 is missing."
            XexParseErrorCode.MISSING_XEX2 -> "Field XEX2 is missing."
            XexParseErrorCode.DUPLICATE_XEX1 -> "Field XEX1 appears more than once (line ${line ?: "?"})."
            XexParseErrorCode.DUPLICATE_XEX2 -> "Field XEX2 appears more than once (line ${line ?: "?"})."
            XexParseErrorCode.EMPTY_VALUE -> "Field ${key ?: "?"} has an empty value (line ${line ?: "?"})."
            XexParseErrorCode.VALUE_TOO_LONG ->
                "Field ${key ?: "?"} is longer than ${XexIdentifierParser.MAX_VALUE_LENGTH} characters (line ${line ?: "?"})."
            XexParseErrorCode.INVALID_CHARACTERS ->
                "Field ${key ?: "?"} contains characters outside A-Z a-z 0-9 - _ . : + / = (line ${line ?: "?"})."
        }
}

data class XexParseWarning(val code: XexParseWarningCode, val line: Int? = null) {
    val message: String
        get() = when (code) {
            XexParseWarningCode.UNRECOGNIZED_LINE -> "Line ${line ?: "?"} is not an XEX1/XEX2 field and was ignored."
            XexParseWarningCode.IDENTICAL_VALUES -> "XEX1 and XEX2 have the same value."
        }
}

sealed interface XexParseResult {
    data class Success(val ids: XexIdentifiers, val warnings: List<XexParseWarning>) : XexParseResult
    data class Failure(val errors: List<XexParseError>) : XexParseResult
}

/**
 * Strict-but-tolerant parser for the identifier file.
 *
 * Accepted: UTF-8 (optional BOM), LF/CRLF/CR line ends, blank lines, `#` comment lines, keys `XEX1`/`XEX2`
 * (case-insensitive) followed by `:` or `=`, optional surrounding quotes around the value.
 * Rejected: missing/duplicate/empty fields, over-long values, characters outside the identifier alphabet,
 * files larger than [MAX_FILE_BYTES]. All errors are collected so the user can fix the file in one pass.
 */
object XexIdentifierParser {
    const val KEY_1 = "XEX1"
    const val KEY_2 = "XEX2"
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_VALUE_LENGTH = 256

    private val FIELD = Regex("""^\s*(XEX[12])\s*[:=]\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
    private val ALLOWED = Regex("""^[A-Za-z0-9._:+/=\-]+$""")

    /** Returns an error code for an invalid value, `null` when valid. */
    internal fun validateValue(v: String): XexParseErrorCode? = when {
        v.isEmpty() -> XexParseErrorCode.EMPTY_VALUE
        v.length > MAX_VALUE_LENGTH -> XexParseErrorCode.VALUE_TOO_LONG
        !ALLOWED.matches(v) -> XexParseErrorCode.INVALID_CHARACTERS
        else -> null
    }

    /** Reads at most [MAX_FILE_BYTES]+1 bytes from [input] (never the whole stream), then parses. */
    fun parse(input: InputStream): XexParseResult {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (total <= MAX_FILE_BYTES) {
            val n = input.read(chunk, 0, minOf(chunk.size, MAX_FILE_BYTES + 1 - total))
            if (n < 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        return parse(buf.toByteArray())
    }

    fun parse(bytes: ByteArray): XexParseResult {
        if (bytes.size > MAX_FILE_BYTES) return XexParseResult.Failure(listOf(XexParseError(XexParseErrorCode.FILE_TOO_LARGE)))
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return XexParseResult.Failure(listOf(XexParseError(XexParseErrorCode.INVALID_UTF8)))
        }
        return parse(text)
    }

    fun parse(rawText: String): XexParseResult {
        val text = rawText.removePrefix("﻿")
        if (text.isBlank()) return XexParseResult.Failure(listOf(XexParseError(XexParseErrorCode.EMPTY_FILE)))

        val errors = mutableListOf<XexParseError>()
        val warnings = mutableListOf<XexParseWarning>()
        val values = HashMap<String, String>()

        text.split("\r\n", "\n", "\r").forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val m = FIELD.matchEntire(line)
            if (m == null) {
                warnings += XexParseWarning(XexParseWarningCode.UNRECOGNIZED_LINE, lineNo)
                return@forEachIndexed
            }
            val key = m.groupValues[1].uppercase()
            var value = m.groupValues[2]
            if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
                value = value.substring(1, value.length - 1).trim()
            }
            if (key in values) {
                errors += XexParseError(if (key == KEY_1) XexParseErrorCode.DUPLICATE_XEX1 else XexParseErrorCode.DUPLICATE_XEX2, lineNo, key)
                return@forEachIndexed
            }
            val bad = validateValue(value)
            if (bad != null) {
                errors += XexParseError(bad, lineNo, key)
                values[key] = "" // field is present (but invalid): do not additionally report it as missing
                return@forEachIndexed
            }
            values[key] = value
        }

        if (KEY_1 !in values) errors += XexParseError(XexParseErrorCode.MISSING_XEX1, key = KEY_1)
        if (KEY_2 !in values) errors += XexParseError(XexParseErrorCode.MISSING_XEX2, key = KEY_2)
        if (errors.isNotEmpty()) return XexParseResult.Failure(errors)

        val ids = XexIdentifiers(values.getValue(KEY_1), values.getValue(KEY_2))
        if (ids.xex1 == ids.xex2) warnings += XexParseWarning(XexParseWarningCode.IDENTICAL_VALUES)
        return XexParseResult.Success(ids, warnings)
    }

    /** Canonical text written to app-private storage; parses back to an equal [XexIdentifiers]. */
    fun toCanonicalText(ids: XexIdentifiers): String = "$KEY_1: ${ids.xex1}\n$KEY_2: ${ids.xex2}\n"
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}
