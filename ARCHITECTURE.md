# Console2Android — Architecture

## 1. High-level layers

```text
Android Application (single APK)
├── UI / Library / Conversion Jobs / Converted Games
├── Conversion Orchestrator
│   ├── Input Validator & Manifest
│   ├── Asset Extractor
│   ├── Binary Analyzer
│   ├── Xbox 360 Backend
│   │   ├── CPU Translator
│   │   ├── Graphics Translator
│   │   ├── Audio Adapters
│   │   └── Platform API Compatibility
│   ├── Recovery Coordinator
│   ├── Smart Conversion Cache
│   └── Final Conversion Validator
├── Runtime
│   ├── Android ARM64 Host
│   ├── Guest State / Memory Model
│   ├── Native Translated Code
│   ├── Interpreter (supported subset)
│   ├── JIT (eligible code only)
│   ├── Compatibility Layer
│   ├── Vulkan Renderer
│   └── Audio / Input
├── Diagnostics & Reports
└── Per-game Profiles / Assets / Cache
```

## 2. Separation of responsibilities

- **UI:** presentation only; no binary parsing or recompilation logic.
- **Orchestrator:** schedules stages, checkpoints, cancellation, resume, and status.
- **Backend:** console-specific semantics and format knowledge.
- **Translator:** converts a documented source subset to a documented target representation.
- **Runtime:** executes translated code and supported fallbacks.
- **Compatibility Layer:** implements source-platform services.
- **Cache:** stores versioned artifacts, not arbitrary mutable runtime state.
- **Validator:** independently checks output.
- **Diagnostics:** collects structured events and formats reports.

## 3. Backend interface

Define a stable interface before implementing the Xbox backend. Conceptual operations:
- `identify_input()`
- `validate_input()`
- `extract_supported_content()`
- `analyze_executable()`
- `translate_cpu_subset()`
- `translate_graphics_subset()`
- `convert_supported_assets()`
- `describe_unsupported_features()`
- `run_backend_validation()`

The interface is a design contract, not a claim that all operations are already implemented. Use typed result objects with explicit `success`, `partial`, `unsupported`, and `failure` states.

## 4. Conversion job state machine

Suggested states:
`QUEUED → VALIDATING → ANALYZING → EXTRACTING → TRANSLATING → RECOVERING → PACKAGING → FINAL_VALIDATION → READY`

Terminal/side states:
`PAUSED`, `CANCELLED`, `FAILED`, `NEEDS_USER_ACTION`.

Each stage is checkpointed. Resume only from a verified checkpoint whose input hashes and toolchain versions still match.

## 5. Converted Game Package

Illustrative layout; refine based on implementation:

```text
ConvertedGames/<game-id>/
├── package.json
├── manifest.json
├── native/
├── translated/
├── assets/
│   ├── graphics/
│   ├── audio/
│   └── data/
├── compatibility/
├── cache-index/
├── profiles/
│   ├── Original.json
│   ├── Quality.json
│   ├── Balanced.json
│   ├── Performance.json
│   └── Custom.json
├── reports/
└── validation/
```

The original imported data must be stored separately and never modified by conversion or graphics profiles.

## 6. Cache model

Global and per-game cache entries use content-addressed keys. Cache metadata includes source/backend, tool versions, target ABI, runtime compatibility, options, dependency hashes, and validation evidence.

## 7. Runtime dispatch

For each supported execution unit:
1. Find a verified native translation compatible with the current runtime.
2. If unavailable, dispatch to a supported interpreter.
3. If eligible and policy allows, compile a JIT version.
4. Verify the generated result before promoting it into the trusted cache.
5. If no correct path exists, raise a structured unsupported event.

Not every guest operation is executable code. System calls, GPU commands, audio operations, and device services must route to their respective compatibility subsystems, not blindly to the CPU interpreter.

## 8. PS3 future backend

PS3 is not part of the first milestone. Preserve backend boundaries so a future PS3 implementation can supply its own executable analysis, PPU/SPU translation, RSX translation, and system compatibility. Do not add PS3 stubs that pretend to work.
