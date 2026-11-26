# XEX reference identifiers and static source analysis

Status: **implemented (`:core`, JVM-tested) / app UI implemented, not built here**. Probe: **experimental**.

## Import file format (`.txt`, UTF-8)
```
XEX1: <code>
XEX2: <code>
```
Case-insensitive keys, `:` or `=`, optional quotes, `#` comments, BOM/CRLF accepted. Both fields required, no duplicates,
value 1–256 chars from `A-Z a-z 0-9 - _ . : + / =`, file ≤ 64 KiB. All errors are listed at once; line content is never echoed.

## Semantics (documented assumption)
The values are **opaque identifiers**. They are never keys, never decrypt or unlock anything, nothing is executed.
- Stored only in app-private, backup-excluded storage (`XexIdentifierStore`, canonical text, re-validated on load).
- Reports/UI show **length + 8-hex fingerprint only**, never the raw value (`XexIdentifierSummary`).
- Comparison (`XexReferenceCheck`): a value that is a SHA-256 (64 hex, `:`/`-` allowed) is compared with the SHA-256 of the
  analysed source → match / differs; any other shape → `NOT_COMPARABLE` (never reported as match). FCV checks are
  non-mandatory (mismatch ⇒ PASS WITH WARNINGS). Identifiers are job metadata, not cache-key inputs.
- If the owner intends another meaning for the codes (e.g. Title/Media ID), add a new comparator; do not repurpose them as keys.

## Static source analysis (`SourceAnalyzer`, `XexHeaderProbe`)
One streaming pass: size + SHA-256 + first 64 KiB. Probe reads only the XEX2 base header words and the raw optional-header
`(key, value)` table, bounds-checked against the real file size and capped (≤ 1024 entries). `XEX1` is recognised by magic
only. Flags, sections, compression and encryption are **not interpreted** (reported opaque). Layout follows public format
documentation but is **not validated against real files** (none are in the repo); tests use synthetic buffers.

## Limits / not implemented
No section-table parsing, no disassembly, no source copy into immutable storage (Phase 2), no job pipeline yet.
