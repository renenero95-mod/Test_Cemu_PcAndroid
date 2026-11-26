# Development Roadmap

## Phase 0 — Feasibility and dependency audit

Deliverables:
- Compare candidate open-source projects and tools for Xbox 360 executable analysis/recompilation.
- Verify current maintenance, license, Android feasibility, architecture, and build requirements.
- Produce a dependency/license matrix.
- Identify what can be reused, what must be written, and what cannot be legally or technically integrated.

Exit gate:
- No unverified dependency is treated as selected.
- A narrow, legal proof-of-concept path is documented.

## Phase 1 — Repository and Android shell

Deliverables:
- Android project builds for `arm64-v8a`.
- Main navigation: Library, Conversion Jobs, Converted Games, Diagnostics, Settings.
- Structured job model and report model.
- CI preflight and debug APK build.

Exit gate:
- Clean checkout builds reproducibly in CI.

## Phase 2 — Input validation and manifest

Deliverables:
- Documented supported input fixture/format.
- Safe file validation and hashing.
- Import workflow.
- Immutable source storage.
- Conversion manifest and resumable job state.

Exit gate:
- Invalid and malformed inputs fail safely with readable reports.

## Phase 3 — CPU translation proof of concept

Deliverables:
- Documented narrow guest instruction subset.
- Reference semantics/tests.
- Translator to ARM64 or a validated intermediate representation.
- Differential tests.
- Runtime dispatch interface.

Exit gate:
- All supported test cases match reference behavior.

## Phase 4 — Interpreter and runtime fallback

Deliverables:
- Interpreter for the same documented subset.
- Native/interpreter state handoff.
- Exception and memory-state tests.
- Structured fallback events.

Exit gate:
- Mixed execution passes deterministic state-equivalence tests.

## Phase 5 — JIT and cache

Deliverables:
- JIT only for eligible code paths.
- Code invalidation and synchronization.
- Global/per-game content-addressed cache.
- Cache verification and corruption tests.

Exit gate:
- JIT output passes the same correctness tests as AOT output; invalid cache entries are rejected.

## Phase 6 — Compatibility services and assets

Deliverables:
- Implement only documented, testable service/asset subsets.
- Explicit unsupported-feature reporting.
- Audio and graphics integration tests.

Exit gate:
- No unsupported feature is silently accepted.

## Phase 7 — Graphics/runtime vertical slice

Deliverables:
- Renderer path for a documented feature subset.
- Shader translation tests.
- Resolution scale and supported quality controls.
- Input and audio path as supported by the chosen fixture.

Exit gate:
- End-to-end fixture renders and responds to input without critical validation failures.

## Phase 8 — Final validator and diagnostics

Deliverables:
- Independent FCV.
- Concise TXT/JSON reports.
- Optional developer trace.
- Manual gameplay session annotations.

Exit gate:
- Injected faults are detected and reported accurately.

## Phase 9 — User experience and performance controls

Deliverables:
- Touch layout editor.
- External controller support.
- FPS/frame-time overlay.
- Per-game graphics profiles.
- Safe profile switching and reset.

Exit gate:
- Profiles do not modify baseline assets; unsupported options are hidden or disabled.

## Phase 10 — First supported title/fixture

Deliverables:
- Reproducible conversion and launch of the legally usable test target.
- Documented known limitations.
- Manual user test procedure.
- Release-quality CI artifact.

Exit gate:
- Evidence-backed Stable/Playable/Experimental classification.

## Phase 11 — Expand compatibility

Only after the first vertical slice is proven:
- Expand instruction coverage.
- Expand graphics and system API coverage.
- Add more fixtures/titles where legally available.
- Measure real compatibility and performance.

## Phase 12 — Possible PS3 backend

Start only after backend interfaces, runtime dispatch, cache, diagnostics, and validation are stable. Perform a separate PS3 feasibility study; do not assume Xbox 360 components transfer directly.
