# Console2Android

**AI development brief and technical starter specification**

Console2Android is a planned Android application that aims to transform legally obtained Xbox 360 game data into a reusable Android-native game package, using ahead-of-time recompilation where feasible, compatibility implementations, and interpreter/JIT fallback for code that cannot safely be translated in advance.

The first backend is **Xbox 360**. A future PS3 backend may be added behind the same high-level interfaces, but PS3 is explicitly out of scope for the initial implementation.

> **Important feasibility statement:** this repository is a specification and development starter, not a working Xbox 360-to-Android converter. A general-purpose converter for arbitrary commercial Xbox 360 games is a major research and engineering project. The AI must not claim that a feature works until it has a tested implementation. Start with a narrowly scoped proof of concept and expand only when evidence supports it.

## Product idea

1. The user imports legally obtained game data.
2. The app analyzes it and creates a conversion job.
3. The conversion pipeline extracts/analyzes assets and executable content, translates what it can, and records unsupported or uncertain parts.
4. Recovery strategies are attempted in a controlled order.
5. A final automated validation runs.
6. The result is stored as a separate converted-game folder/package.
7. The same Android app exposes a separate **Converted Games** section where the user can launch the result, configure graphics/input, inspect diagnostics, and perform manual gameplay testing.
8. During gameplay, untranslated or unsupported code may use a compatible interpreter/JIT path when technically possible. Such events are recorded in a lightweight diagnostic log.

## XEX analysis scope

XEX is a supported static-analysis target, not an instruction to execute a game. The project may inspect headers and metadata, validate section tables and offsets, hash files, disassemble readable code, and build parser/tests without launching the input. Encrypted or unsupported sections are reported as opaque; this does not block analysis of readable parts or other project work. The repository rejects committed game binaries (including `.xex`) for repository hygiene; users may select external files locally. No key extraction or access-control circumvention is part of this scope.

## Non-negotiable engineering principles

- Correctness before conversion percentage or speed.
- Never silently label unverified output as stable.
- Never invent console APIs, binary formats, or tool capabilities.
- Every module must have tests and documented limitations.
- Keep the original imported game data immutable.
- Keep generated output, runtime, global cache, per-game cache, profiles, and reports separate.
- Runtime fallback must preserve the source platform's observable semantics; if it cannot, report the limitation rather than guessing.
- The app must not distribute copyrighted game content, console firmware, keys, or proprietary SDK components.
- Respect licenses of all third-party code and tools.
- Do not bypass console security or DRM. Work only with legally obtained, user-provided data and supported research workflows.

## Documents

- [`CONSOLE2ANDROID_MASTER_PLAN.md`](CONSOLE2ANDROID_MASTER_PLAN.md) — product and technical specification.
- [`AI_DEVELOPMENT_RULES.md`](AI_DEVELOPMENT_RULES.md) — instructions for the AI coding agent.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — component boundaries and data flow.
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md) — staged implementation and exit criteria.
- [`BUILD_AND_CI.md`](BUILD_AND_CI.md) — local and GitHub Actions build strategy.
- [`TESTING_AND_VALIDATION.md`](TESTING_AND_VALIDATION.md) — quality gates and final checks.
- [`docs/DIAGNOSTICS_AND_REPORTS.md`](docs/DIAGNOSTICS_AND_REPORTS.md) — concise user report and technical logs.
- [`docs/RECOVERY_CACHE_RUNTIME.md`](docs/RECOVERY_CACHE_RUNTIME.md) — recovery, interpreter/JIT, and cache requirements.
- [`docs/GRAPHICS_INPUT_UI.md`](docs/GRAPHICS_INPUT_UI.md) — graphics profiles, touch controls, FPS overlay, and app UX.
- [`docs/XEX_IDENTIFIERS.md`](docs/XEX_IDENTIFIERS.md) — XEX1/XEX2 identifier import, static source analysis, limits.
- [`docs/FX1_PIPELINE.md`](docs/FX1_PIPELINE.md) — proof-of-concept conversion pipeline (synthetic C2A-FX1 fixture), validator, limits.
- [`docs/LEGAL_AND_DEPENDENCIES.md`](docs/LEGAL_AND_DEPENDENCIES.md) — licensing and dependency policy.
- [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) — starter CI workflow; it must be adapted to the actual project and pinned dependencies before release.

## First milestone

Do not begin by promising support for arbitrary Xbox 360 titles. First prove the pipeline with:
- a legally usable test executable or purpose-built test fixture;
- a documented, narrow CPU instruction subset;
- a minimal runtime bridge on Android ARM64;
- deterministic correctness tests comparing reference and translated behavior;
- one minimal rendering/input/audio path, only as the scope permits.

A successful first milestone is a reproducible, testable vertical slice—not a visually complete launcher with a nonfunctional backend.

## Current state

See `PROJECT_PROGRESS.md` (completed / remaining / blockers / verification). Session rules: `AI_DEVELOPMENT_RULES.md` §12.
Phase 0 audit: `docs/PHASE0_DEPENDENCY_AUDIT.md`. Phase 0/3 PoC proposal: `docs/PHASE0_POC_PROPOSAL.md`.
