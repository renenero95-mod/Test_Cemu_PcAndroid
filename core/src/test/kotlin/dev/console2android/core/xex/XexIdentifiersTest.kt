package dev.console2android.core.xex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class XexIdentifiersTest {
    private fun ok(text: String) = XexIdentifierParser.parse(text) as XexParseResult.Success
    private fun errs(text: String) = (XexIdentifierParser.parse(text) as XexParseResult.Failure).errors.map { it.code }

    @Test fun parsesBothFields() {
        val r = ok("XEX1: ABC-123\nXEX2: def_456\n")
        assertEquals("ABC-123", r.ids.xex1)
        assertEquals("def_456", r.ids.xex2)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun toleratesBomCrlfCaseCommentsQuotesAndEquals() {
        val r = ok("﻿# comment\r\n\r\nxex1 = \"A1B2\"\r\n  Xex2:\tC3D4  \r\n")
        assertEquals("A1B2", r.ids.xex1)
        assertEquals("C3D4", r.ids.xex2)
    }

    @Test fun oldMacLineEndings() {
        val r = ok("XEX1: a\rXEX2: b")
        assertEquals("a", r.ids.xex1); assertEquals("b", r.ids.xex2)
    }

    @Test fun missingFields() {
        assertEquals(listOf(XexParseErrorCode.MISSING_XEX2), errs("XEX1: a"))
        assertEquals(listOf(XexParseErrorCode.MISSING_XEX1), errs("XEX2: a"))
        assertEquals(listOf(XexParseErrorCode.MISSING_XEX1, XexParseErrorCode.MISSING_XEX2), errs("hello"))
    }

    @Test fun emptyAndBlankFile() {
        assertEquals(listOf(XexParseErrorCode.EMPTY_FILE), errs(""))
        assertEquals(listOf(XexParseErrorCode.EMPTY_FILE), errs(" \n\t\n"))
        assertEquals(listOf(XexParseErrorCode.EMPTY_FILE), (XexIdentifierParser.parse(ByteArray(0)) as XexParseResult.Failure).errors.map { it.code })
    }

    @Test fun emptyValueIsErrorNotMissing() {
        assertEquals(listOf(XexParseErrorCode.EMPTY_VALUE), errs("XEX1:\nXEX2: b"))
    }

    @Test fun duplicateFieldIsError() {
        val e = (XexIdentifierParser.parse("XEX1: a\nXEX1: b\nXEX2: c") as XexParseResult.Failure).errors
        assertEquals(1, e.size)
        assertEquals(XexParseErrorCode.DUPLICATE_XEX1, e[0].code)
        assertEquals(2, e[0].line)
    }

    @Test fun invalidCharactersAndTooLong() {
        assertEquals(listOf(XexParseErrorCode.INVALID_CHARACTERS), errs("XEX1: a b\nXEX2: c"))
        assertEquals(listOf(XexParseErrorCode.INVALID_CHARACTERS), errs("XEX1: a;rm\nXEX2: c"))
        assertEquals(listOf(XexParseErrorCode.VALUE_TOO_LONG), errs("XEX1: ${"a".repeat(257)}\nXEX2: c"))
        ok("XEX1: ${"a".repeat(256)}\nXEX2: c")
    }

    @Test fun allErrorsCollectedAtOnce() {
        assertEquals(
            listOf(XexParseErrorCode.INVALID_CHARACTERS, XexParseErrorCode.MISSING_XEX2),
            errs("XEX1: bad value!"),
        )
    }

    @Test fun unrecognizedLinesWarnWithoutEchoingContent() {
        val r = ok("secret-looking-line\nXEX1: a\nXEX2: b")
        assertEquals(listOf(XexParseWarningCode.UNRECOGNIZED_LINE), r.warnings.map { it.code })
        assertEquals(1, r.warnings[0].line)
        assertFalse(r.warnings[0].message.contains("secret"))
    }

    @Test fun identicalValuesWarn() {
        assertEquals(listOf(XexParseWarningCode.IDENTICAL_VALUES), ok("XEX1: a\nXEX2: a").warnings.map { it.code })
    }

    @Test fun invalidUtf8Rejected() {
        val bytes = "XEX1: a\nXEX2: ".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(listOf(XexParseErrorCode.INVALID_UTF8), (XexIdentifierParser.parse(bytes) as XexParseResult.Failure).errors.map { it.code })
    }

    @Test fun oversizedFileRejectedAndStreamReadIsBounded() {
        val big = ByteArray(XexIdentifierParser.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(listOf(XexParseErrorCode.FILE_TOO_LARGE), (XexIdentifierParser.parse(big) as XexParseResult.Failure).errors.map { it.code })
        var consumed = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { consumed++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); consumed += len; return len }
        }
        assertEquals(listOf(XexParseErrorCode.FILE_TOO_LARGE), (XexIdentifierParser.parse(endless) as XexParseResult.Failure).errors.map { it.code })
        assertTrue(consumed <= XexIdentifierParser.MAX_FILE_BYTES + 1L)
    }

    @Test fun streamParse() {
        val r = XexIdentifierParser.parse(ByteArrayInputStream("XEX1: a\nXEX2: b\n".toByteArray())) as XexParseResult.Success
        assertEquals("b", r.ids.xex2)
    }

    @Test fun canonicalTextRoundTrips() {
        val ids = ok("xex1 = 'Z9.z'\nXEX2: q/w+e=").ids
        val again = ok(XexIdentifierParser.toCanonicalText(ids)).ids
        assertEquals(ids, again)
        assertEquals("XEX1: Z9.z\nXEX2: q/w+e=\n", XexIdentifierParser.toCanonicalText(ids))
    }

    @Test fun toStringAndSummaryNeverExposeValues() {
        val ids = ok("XEX1: SUPERSECRET1\nXEX2: SUPERSECRET2").ids
        assertFalse(ids.toString().contains("SUPERSECRET"))
        val s = ids.summary()
        assertEquals(12, s.xex1Length)
        assertEquals(8, s.xex1Fingerprint.length)
        assertFalse(s.toString().contains("SUPERSECRET"))
        assertFalse(s.identical)
    }

    @Test fun fingerprintIsDeterministicAndKeySeparated() {
        assertEquals(XexIdentifiers.fingerprint("XEX1", "a"), XexIdentifiers.fingerprint("XEX1", "a"))
        assertTrue(XexIdentifiers.fingerprint("XEX1", "a") != XexIdentifiers.fingerprint("XEX2", "a"))
        assertTrue(XexIdentifiers.fingerprint("XEX1", "a") != XexIdentifiers.fingerprint("XEX1", "b"))
    }
}
