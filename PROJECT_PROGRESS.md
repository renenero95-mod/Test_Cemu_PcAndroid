# PROJECT_PROGRESS

Last update: 2026-11-26 · Session 4 · C2A-FX1 proof-of-concept conversion pipeline (PPC subset → C2A-IR), package cache, validator, app wiring

## Session protocol
Same project, same ZIP, updated each session; update this file at the end of every session; minimal chat;
ZIP is extracted → GitHub → Actions builds the APK. Details: `AI_DEVELOPMENT_RULES.md` §12.
**Upload the *contents* of the extracted folder to the GitHub repo root** (`.github/` must be at the root).

## Understanding (short)
One APK containing: Library, Conversion Engine, Smart Conversion Cache, Final Conversion Validator,
Converted Games (separate library), Game Runtime, Diagnostics, Settings. Conversion and launch are inside the
same app. Strict honesty: status comes from independent validation, never from a conversion percentage.

## Completed
- **Session 4 (Phase 3 PoC):** `:core/ppc` (PowerPC subset decoder + reference interpreter + assembler), `:core/fx1` (synthetic container, sample),
  `:core/ir` (C2A-IR, AOT translator, executor with explicit counted interpreter fallback, canonical text), `:core/convert` (content-addressed
  `PackageStore`, cache key, `PackageRuntime`, `PackageValidator` = FCV with reproducibility + differential smoke test, resumable checkpointed
  `JobRunner` + 6 stages). App: "Add sample", Run/Pause/Resume/Cancel/Report for jobs, Converted games list with Run. Docs: `docs/FX1_PIPELINE.md`.
  Bug found by tests and fixed: translator treated the word after an unconditional `blr` as reachable.
  XEX sources are rejected by the pipeline with `FORMAT-UNSUPPORTED` (explicit; not converted).
- **Session 3 (Phase 2 slice):** `SourceLibrary` (content-addressed immutable storage, streaming SHA-256, atomic, cancel/space/IO safe,
  verify/remove), `JobFactory` + `JobStore` (persistent jobs with reference metadata). App: "Add file…", library list with
  Create job / Verify / Remove, Jobs list with Cancel / Remove. Docs: `docs/LIBRARY_AND_JOBS.md`.
- **Session 3:** `:core/xex` — identifier-file parser (`XexIdentifierParser`), report-safe summaries (length + fingerprint),
  `XexHeaderProbe` (experimental, bounds-checked XEX2 base header; XEX1 magic only), `SourceAnalyzer` (streaming SHA-256 +
  size + header probe), `XexReferenceCheck` (compare with source SHA-256 → FCV checks + events). Integrated in
  `ConversionJob.reference` and `DiagnosticReport.xexReference` (TXT/JSON). App: Library import of `.txt`, private store,
  "Analyze file…" (read-only), Diagnostics shows reference + probe events. Docs: `docs/XEX_IDENTIFIERS.md`.
- **Session 3:** Gradle wrapper (8.9) + `.gitattributes`; CI `sdkmanager` step made robust; preflight checks wrapper/CI version match.
- Clarified XEX workflow: static inspection/parsing/hashing/disassembly is allowed and does not execute XEX.
- Removed blanket assumption that all XEX reading requires decryption; encrypted sections are reported opaque.
- Clarified `.xex` preflight exclusion as repository hygiene only, not a ban on external user-selected inputs or parser development.
- Read all spec files + owner instructions (photos).
- **Phase 0 (first pass):** `docs/PHASE0_DEPENDENCY_AUDIT.md`, `docs/dependencies.json`,
  `docs/PHASE0_POC_PROPOSAL.md` (synthetic C2A-FX1 fixture slice). Nothing approved.
- **`:core` (pure JVM Kotlin, no Android dep):**
  - Job state machine (QUEUED→…→READY, PAUSED/NEEDS_USER_ACTION resume to same stage, terminal states),
    checkpoints (reuse only if verified + same input hash + same tool version; verified never overwritten by failed),
    typed `StageResult`, `complete(fcv)` refuses READY when FCV = FAIL.
  - Diagnostics: severity/domain/route enums, `BoundedEventLog` (dedup+count, bounded, severity eviction, dropped counter).
  - Validation: FCV aggregation (no evidence ⇒ FAIL), `StatusPolicy` (no percentage / no user annotation as input;
    EXPERIMENTAL mode never Stable).
  - Report: TXT + JSON writers; "not measured" for unmeasured counts; user annotation separate and labelled.
- **`:app` (Kotlin/Compose shell):** 5 sections (Library, Jobs, Converted, Diagnostics, Settings), honest
  "not implemented" states, environment report with manual TXT/JSON share and list of exported metadata.
- **CI** `.github/workflows/android-ci.yml`: preflight → core tests → (lint, non-blocking) → debug APK + validation + BUILD_INFO.
- Tools: `tools/preflight.py`, `tools/update_manifest.py`; `PACKAGE_MANIFEST.json` regenerated.

## Remaining
**Phase 1 (finish):** confirm first green CI run; make lint blocking;
pin GitHub Actions to commit SHAs; app instrumented/UI smoke test; app-side persistence skeleton (job/report store).
**Phase 0 (finish):** read real LICENSE files; choose backend family (AOT-C++ + toolchain vs own IR→ARM64 vs interpreter-first)
via a device spike. XEX static metadata/parser work can proceed independently.
**Phase 2 (finish):** storage quota/eviction, flag jobs whose source was removed, mode picker in UI, format detection beyond XEX magic
(fixture first), XEX section table parsing (static, opaque if encrypted), instrumented test of SAF import.
**Phase 3 (finish):** real XEX loading (section table, imports, kernel/system API layer) — large, unresearched; wider PPC coverage (rotates, shifts,
byte/half loads, FP/VMX); native ARM64 emission or JIT (backend decision D2); graphics/audio/input host; performance measurements on a device;
recovery stage (`RECOVERING`) beyond the runtime interpreter fallback; storage quota/eviction for packages and work dirs.
**Later phases:** cache (key = source hash + tool versions + options), FCV with real checks, runtime host
(video/audio/input), recovery routes, performance work, release signing.

## Blockers and decisions
- **D4 (owner to confirm):** meaning of XEX1/XEX2 codes is unspecified; implemented as opaque identifiers (never keys).
  Only SHA-256-shaped values are comparable with the source hash; others are reported NOT_COMPARABLE.
- **D2 (technical, needs spike):** "convert on device" requires a code-generation path on Android; recompilers
  found emit C++ needing a compiler. Backend choice drives size/time/feasibility (see audit §2).
- **D3:** Whole-title compatibility is per-game research; keep the claim to what validated tests show.

## Verification status
| Item | Status |
|---|---|
| `:core` compile + 142 JUnit tests (incl. differential interpreter-vs-IR, pipeline/resume/cache/cancel/FCV-fail; 1 root-skipped) | **RUN, PASS** (Kotlin 2.0.21 + JUnit 4.13.2, locally, outside Gradle); mutation spot-checks of runner/validator caught |
| `:app` Kotlin code vs. API stubs (syntax/types of own code) | **RUN, PASS**; real Compose/AndroidX API not checked |
| JSON output parses | **RUN, PASS** (Python `json`) |
| `tools/preflight.py` | **RUN, PASS** (final count in CHECK.md) |
| Workflow YAML parse (PyYAML) | **RUN, PASS** (syntax only) |
| Gradle build of `:core`/`:app` | **NOT RUN** (no network for plugins in sandbox) |
| Compose `:app` compile, APK | **NOT RUN** — first CI run is the verification |
| Dependency versions (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.12.01, …) | **UNVERIFIED** against registries |
| Workflow YAML semantics, SDK package names | **UNVERIFIED** (syntax only) |
| Licenses (XenonRecomp, ReXGlue) | **UNVERIFIED** |

## If CI fails (first run)
Send the failing job log. Likely suspects: AGP/Gradle/Kotlin/Compose version mismatch, SDK package names, Compose API drift.
