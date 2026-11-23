# PROJECT_PROGRESS

Last update: 2026-11-23 · Session 1 · Phase 0 (first pass) + Phase 1 (shell, partial)

## Session protocol
Same project, same ZIP, updated each session; update this file at the end of every session; minimal chat;
ZIP is extracted → GitHub → Actions builds the APK. Details: `AI_DEVELOPMENT_RULES.md` §12.
**Upload the *contents* of the extracted folder to the GitHub repo root** (`.github/` must be at the root).

## Understanding (short)
One APK containing: Library, Conversion Engine, Smart Conversion Cache, Final Conversion Validator,
Converted Games (separate library), Game Runtime, Diagnostics, Settings. Conversion and launch are inside the
same app. Strict honesty: status comes from independent validation, never from a conversion percentage.

## Completed
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
**Phase 1 (finish):** confirm first green CI run; make lint blocking; add Gradle wrapper (jar cannot be produced offline);
pin GitHub Actions to commit SHAs; app instrumented/UI smoke test; app-side persistence skeleton (job/report store).
**Phase 0 (finish):** read real LICENSE files; choose backend family (AOT-C++ + toolchain vs own IR→ARM64 vs interpreter-first)
via a device spike; legal decision D1.
**Phase 2:** import via Storage Access Framework, hashing, format detection (fixture first), library UI.
**Phase 3:** C2A-FX1 generator + PPC subset interpreter + first conversion backend (see PoC proposal).
**Later phases:** cache (key = source hash + tool versions + options), FCV with real checks, runtime host
(video/audio/input), recovery routes, performance work, release signing.

## Blockers and decisions
- **D1 (needs owner/legal):** Retail XEX files are encrypted; reading them needs decryption keys. Rules forbid
  circumvention and bundled keys. Proposed: continue with the synthetic fixture only until D1 is decided.
- **D2 (technical, needs spike):** "convert on device" requires a code-generation path on Android; recompilers
  found emit C++ needing a compiler. Backend choice drives size/time/feasibility (see audit §2).
- **D3:** Whole-title compatibility is per-game research; keep the claim to what validated tests show.

## Verification status
| Item | Status |
|---|---|
| `:core` compile + 28 JUnit tests | **RUN, PASS** (Kotlin 2.0.21 compiler + JUnit 4.13.2, locally, outside Gradle) |
| JSON output parses | **RUN, PASS** (Python `json`) |
| `tools/preflight.py` | **RUN, PASS** (see last session command in chat; re-run before zipping) |
| Gradle build of `:core`/`:app` | **NOT RUN** (no network for plugins in sandbox) |
| Compose `:app` compile, APK | **NOT RUN** — first CI run is the verification |
| Dependency versions (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.12.01, …) | **UNVERIFIED** against registries |
| Workflow YAML semantics, SDK package names | **UNVERIFIED** (syntax only) |
| Licenses (XenonRecomp, ReXGlue) | **UNVERIFIED** |

## If CI fails (first run)
Send the failing job log. Likely suspects: AGP/Gradle/Kotlin/Compose version mismatch, SDK package names, Compose API drift.
