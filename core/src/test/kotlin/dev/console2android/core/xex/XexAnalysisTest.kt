package dev.console2android.core.xex

import dev.console2android.core.diag.Severity
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.report.DiagnosticReport
import dev.console2android.core.report.ReportFormatter
import dev.console2android.core.validation.CheckResult
import dev.console2android.core.validation.Fcv
import dev.console2android.core.validation.FcvOverall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class XexAnalysisTest {
    private fun be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Synthetic header per the documented layout; NOT game data. */
    private fun header(
        magic: String = "XEX2", flags: Long = 1, pe: Long = 0x100, sec: Long = 0x80, count: Long = 2,
        entries: List<Pair<Long, Long>> = listOf(0x103FFL to 0x40L, 0x10100L to 0x82000000L), size: Int = 0x200,
    ): ByteArray {
        val out = ByteArray(size)
        val head = magic.toByteArray(Charsets.ISO_8859_1) + be(flags) + be(pe) + be(0) + be(sec) + be(count) +
            entries.flatMap { (k, v) -> (be(k) + be(v)).toList() }.toByteArray()
        System.arraycopy(head, 0, out, 0, minOf(head.size, size))
        return out
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun probe(b: ByteArray, size: Long = b.size.toLong()) = XexHeaderProbe.probe(b, size)
    private fun codes(r: XexProbeResult) = when (r) {
        is XexProbeResult.Parsed -> r.issues.map { it.code }
        is XexProbeResult.Malformed -> r.issues.map { it.code }
        else -> emptyList()
    }

    @Test fun parsesValidSyntheticHeaderAsOpaque() {
        val r = probe(header()) as XexProbeResult.Parsed
        assertEquals("XEX2", r.header.magic)
        assertEquals(0x100L, r.header.peDataOffset)
        assertEquals(2L, r.header.optionalHeaderCount)
        assertEquals(XexOptionalHeader(0x103FF, 0x40), r.header.optionalHeaders[0])
        assertEquals(0x82000000L, r.header.optionalHeaders[1].value) // unsigned, no sign extension
        assertTrue("XEX-OPAQUE" in codes(r))
        assertFalse(r.header.optionalHeadersTruncated)
    }

    @Test fun nonXexAndXex1() {
        assertEquals(XexProbeResult.NotXex, probe("MZ\u0090\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(XexProbeResult.NotXex, probe(ByteArray(2)))
        assertTrue(probe(header("XEX1")) is XexProbeResult.UnsupportedVariant)
    }

    @Test fun truncatedHeader() {
        val r = probe(header().copyOf(0x10), 0x10)
        assertEquals(listOf("XEX-TRUNCATED"), codes(r))
    }

    @Test fun badOffsetsAreRejected() {
        assertTrue("XEX-PE-OFFSET" in codes(probe(header(pe = 0x10))))
        assertTrue("XEX-PE-OFFSET" in codes(probe(header(pe = 0x9999))))
        assertTrue("XEX-SEC-OFFSET" in codes(probe(header(sec = 0x200))))
        assertTrue("XEX-SEC-OFFSET" in codes(probe(header(sec = 4))))
    }

    @Test fun optionalHeaderCountLimitsAndTableBounds() {
        assertTrue("XEX-OPT-COUNT" in codes(probe(header(count = 0xFFFFFFFFL))))
        assertTrue("XEX-OPT-TABLE" in codes(probe(header(pe = 0x20, count = 2))))
    }

    @Test fun prefixShorterThanFileIsReportedNotGuessed() {
        val full = header(pe = 0x100, count = 20, entries = emptyList(), size = 0x200)
        val r = XexHeaderProbe.probe(full.copyOf(0x30), 0x200) as XexProbeResult.Parsed
        assertTrue(r.header.optionalHeadersTruncated)
        assertEquals(3, r.header.optionalHeaders.size)
        assertTrue(r.issues.any { it.code == "XEX-PREFIX-LIMIT" && it.severity == Severity.WARNING })
    }

    private val ok1 = XexIdentifierParser.parse("XEX1: abc\nXEX2: def").let { (it as XexParseResult.Success).ids }

    @Test fun analyzerStreamsHashAndBoundsPrefix() {
        val data = header(size = 300_000)
        val a = SourceAnalyzer.analyze(ByteArrayInputStream(data), null)
        assertEquals(300_000L, a.facts.sizeBytes)
        assertEquals(sha(data), a.facts.sha256)
        assertEquals(SourceAnalyzer.PREFIX_BYTES, a.facts.headPrefix.size)
        assertTrue(a.probe is XexProbeResult.Parsed)
        assertEquals(null, a.reference)
    }

    @Test fun analyzerHandlesEmptyInput() {
        val a = SourceAnalyzer.analyze(ByteArrayInputStream(ByteArray(0)), null)
        assertEquals(0L, a.facts.sizeBytes)
        assertEquals(XexProbeResult.NotXex, a.probe)
    }

    @Test fun comparisonMatchMismatchNotComparable() {
        val data = header()
        val h = sha(data)
        val ids = XexIdentifiers(h.uppercase(), "f".repeat(64))
        val c = XexReferenceCheck.compare(ids, h)
        assertEquals(IdComparison.MATCHES_SOURCE_SHA256, c.xex1)
        assertEquals(IdComparison.DIFFERS_FROM_SOURCE_SHA256, c.xex2)
        val c2 = XexReferenceCheck.compare(ok1, h)
        assertEquals(IdComparison.NOT_COMPARABLE, c2.xex1)
        val colon = XexIdentifiers(h.chunked(2).joinToString(":"), "x")
        assertEquals(IdComparison.MATCHES_SOURCE_SHA256, XexReferenceCheck.compare(colon, h).xex1)
    }

    @Test fun fcvAndEventsReflectComparison() {
        val h = sha(header())
        val c = XexReferenceCheck.compare(XexIdentifiers(h, "0".repeat(64)), h)
        val checks = XexReferenceCheck.fcvChecks(c)
        assertEquals(CheckResult.PASS, checks[0].result)
        assertEquals(CheckResult.FAIL, checks[1].result)
        assertFalse(checks.any { it.mandatory })
        assertEquals(FcvOverall.PASS_WITH_WARNINGS, Fcv.overall(checks))
        assertEquals(listOf("XEX-REF-MISMATCH"), XexReferenceCheck.events(c).map { it.code })
        // not comparable -> not applicable, and alone it is "no evidence" => FAIL (policy: absence of evidence is not a pass)
        val nc = XexReferenceCheck.fcvChecks(XexReferenceCheck.compare(ok1, h))
        assertTrue(nc.all { it.result == CheckResult.NOT_APPLICABLE })
        assertEquals(FcvOverall.FAIL, Fcv.overall(nc))
    }

    @Test fun reportsContainFingerprintsButNeverRawValues() {
        val ids = XexIdentifiers("SECRETVALUE-ONE", "SECRETVALUE-TWO")
        val r = DiagnosticReport(
            "r", null, null, "none", null, DeviceInfo("m", "14", "arm64-v8a"), null, null,
            createdAtEpochMs = 0, xexReference = XexReferenceCheck.summarize(ids, "a".repeat(64)),
        )
        val txt = ReportFormatter.toText(r)
        val json = ReportFormatter.toJson(r)
        for (s in listOf(txt, json)) {
            assertFalse(s.contains("SECRETVALUE"))
            assertTrue(s.contains(ids.summary().xex1Fingerprint))
        }
        assertTrue(txt.contains("not comparable"))
        assertTrue(json.contains("\"xexReference\": {"))
        assertTrue(json.contains("\"comparison\": \"NOT_COMPARABLE\""))
        // no reference -> section absent, json null
        val plain = r.copy(xexReference = null)
        assertFalse(ReportFormatter.toText(plain).contains("XEX REFERENCE"))
        assertTrue(ReportFormatter.toJson(plain).contains("\"xexReference\": null"))
    }

    @Test fun jobCarriesReferenceWithoutAffectingState() {
        val j = ConversionJob("j", "x360", "a".repeat(64), ConversionMode.STRICT)
        val s = XexReferenceCheck.summarize(ok1, "a".repeat(64))
        val j2 = j.withReference(s)
        assertEquals(s, j2.reference)
        assertEquals(j.state, j2.state)
        assertEquals(null, j.reference)
    }
}

class XexDescribeTest {
    @Test fun describeCoversAllVariants() {
        assertTrue(XexProbeResult.NotXex.describe().startsWith("Not an XEX"))
        assertTrue(XexProbeResult.UnsupportedVariant("XEX1", "n").describe().startsWith("XEX1"))
        val bad = XexHeaderProbe.probe("XEX2".toByteArray(), 4)
        assertTrue(bad.describe().contains("malformed"))
        val ok = ByteArray(0x200).also {
            "XEX2".toByteArray().copyInto(it); it[11] = 0x40; it[19] = 0x30; it[23] = 0 // pe=0x40, sec=0x30, count=0
        }
        val r = XexHeaderProbe.probe(ok, ok.size.toLong())
        assertTrue(r.describe(), r.describe().contains("0 optional headers"))
    }
}
