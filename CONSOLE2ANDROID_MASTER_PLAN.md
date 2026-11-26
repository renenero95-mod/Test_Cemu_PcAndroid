# Console2Android — Master Project Specification

**Document status:** Approved product direction / implementation not yet proven  
**Initial target:** Android ARM64  
**Initial console backend:** Xbox 360  
**Reference device:** POCO F6 Pro (Snapdragon 8 Gen 2, Adreno GPU)  
**Future expansion:** PS3 backend, only after the backend interfaces and first platform are stable

---

## 1. Vision

Build one Android application that manages a library of user-provided Xbox 360 game data and attempts to produce a converted Android-native game package.

The core concept is a hybrid conversion-and-runtime system:

- Perform as much translation as possible before gameplay (AOT/static recompilation).
- Validate translated output rigorously.
- Recover unsupported portions using alternative translation, compatibility implementations, or an interpreter.
- Use JIT recompilation only where it is technically valid and safe.
- Cache verified reusable results.
- Launch the converted game from a separate section inside the same APK.
- Allow the user to perform final manual gameplay testing and inspect focused diagnostics.

This is **not** intended to be a conventional emulator-only launcher. It aims to create a persistent converted output that can run through an Android runtime. However, the runtime may still contain compatibility services and interpreter/JIT components for code that cannot be translated ahead of time. Therefore, “native port” does not mean every original subsystem can be replaced by Android-native code.

## 2. Product goals

1. Provide an Android app with a game library and conversion workflow.
2. Keep the conversion pipeline modular and backend-specific.
3. Support Xbox 360 first; do not mix PS3-specific implementation into the first release.
4. Produce a separate per-game converted output directory/package.
5. Launch converted results inside the same app, through a clearly separated **Converted Games** section.
6. Prefer verified native translation, but provide fallback where semantically correct.
7. Maintain a shared conversion cache and per-game cache with strict compatibility keys.
8. Generate concise, downloadable diagnostic reports.
9. Provide graphics settings, per-game profiles, touch controls, external controller support, and FPS/frame-time overlay.
10. Run a final automated validation and then allow user-led manual gameplay testing.
11. Use staged development, automated tests, and GitHub Actions to reduce wasted build cycles.

## 3. Non-goals for the first release

- General support for every Xbox 360 game.
- PS3 support.
- Guaranteed performance parity with original hardware.
- Automatic repair of every unknown system call or GPU behavior.
- A claim that compile success proves game correctness.
- Distribution of game images, copyrighted assets, firmware, console keys, or proprietary SDKs.
- A universal “ISO to APK” conversion promise.

## 4. Terminology

- **Source game data:** legally obtained files imported by the user.
- **Conversion job:** a reproducible set of analysis, translation, asset conversion, and validation tasks.
- **Converted Game Package (CGP):** output directory containing generated code, converted assets, metadata, profiles, cache references, and reports.
- **AOT:** ahead-of-time translation performed before gameplay.
- **Interpreter:** executes supported original instructions/operations without first translating them into native machine code.
- **JIT:** compiles eligible code at runtime. It is not a generic solution for missing APIs, GPU features, or incorrect semantics.
- **Compatibility Layer:** implements source-platform services expected by the game.
- **Smart Conversion Cache:** reusable, versioned storage for verified translation artifacts.
- **Strict Conversion:** policy that rejects or quarantines uncertain results instead of silently accepting them.
- **Final Conversion Validator (FCV):** independent final validation stage.
- **Manual Gameplay Validation:** user-run test after conversion, with a dedicated report/debug view.

## 5. User journey

### 5.1 Import and analyze

1. User selects legally obtained game data in a supported format.
2. App identifies the input type and validates basic integrity.
3. App creates a conversion job and records source hashes and toolchain versions.
4. Analyzer identifies known executable/container formats and assets. XEX inspection is a static-analysis task: parse the documented header/metadata and any structurally readable sections without launching the file.
5. If a section is encrypted, compressed with an unsupported method, malformed, or otherwise unreadable, report that specific limitation and continue with independent metadata/sections where safe. Never guess.

The initial implementation must explicitly document which input forms are supported. Do not claim that every Xbox 360 ISO/XEX can be fully parsed or decrypted. Static XEX inspection (magic, header, module metadata, section table, hashes, offsets, flags, and readable data) is in scope and must not be treated as execution or circumvention. Encrypted retail sections may remain opaque; do not decrypt them by bypassing access controls or inventing keys.

### 5.2 Convert

The conversion pipeline is resumable and checkpointed:
1. Source validation and manifest.
2. Extraction of supported user-provided content.
3. Executable and metadata analysis.
4. CPU translation for a documented instruction/function subset.
5. Graphics/shader translation for a documented subset.
6. Audio and asset conversion for documented formats.
7. Compatibility-layer binding.
8. Recovery attempts for unsupported elements.
9. Package assembly.
10. Final automated validation.

Every stage records input/output hashes, tool version, status, and concise failure information. A failed stage must not corrupt completed checkpoints.

### 5.3 Open converted game

The same APK has distinct navigation sections:
- **Library / Source Games:** imported originals and conversion status.
- **Converted Games:** only generated packages that passed the minimum launch gate.
- **Conversion Jobs:** progress, resume, cancel, and reports.
- **Diagnostics:** downloadable reports and optional detailed logs.
- **Settings:** app-wide settings and toolchain/runtime information.

The Converted Games section must not confuse source images with converted outputs.

### 5.4 Gameplay

The runtime loads the selected Converted Game Package. It provides:
- touch controls and controller mapping;
- per-game graphics profiles;
- optional FPS/frame-time overlay;
- lightweight error/fallback diagnostics;
- pause/resume and safe shutdown;
- export of a diagnostic bundle after a problem.

### 5.5 User manual validation

After automated conversion validation, the user performs real gameplay testing. The app must make this easy:
- Launch the converted result from **Converted Games**.
- Open a compact in-game diagnostics overlay or pause-screen diagnostics panel.
- Save a manual test session summary.
- Export a report containing the game/package ID, runtime/build versions, profile, device/ABI, relevant errors and fallback events.
- Let the user mark the result as “User tested”, “Playable with issues”, or “Failed during gameplay”. This is a user annotation, not an automatically inferred certification.

## 6. Conversion correctness and recovery

The target is not a superficial high conversion percentage. The system must maximize correct coverage while preserving observable behavior.

Recovery order:
1. Direct AOT translation.
2. Alternative verified translation/lowering.
3. Compatibility Layer implementation for missing platform services.
4. Interpreter fallback for supported original operations.
5. JIT compilation for eligible hot code paths.
6. Mark unsupported if no correct implementation exists.

Do not treat every failure as a CPU translation problem. Classify failures by domain: CPU semantics, system API, memory model, graphics, audio, asset format, timing/synchronization, or unknown.

A recovery implementation is accepted only after appropriate unit, differential, integration, and runtime tests. If it is unverified, label it experimental and prevent Strict mode from presenting it as stable.

## 7. Strict Conversion Engine

Strict mode is the default.

Strict rules:
- Unknown instructions or APIs are not silently mapped to approximate behavior.
- Generated code must pass correctness checks before being marked verified.
- Fallback must be explicit and observable.
- Unsupported behavior must be reported.
- Critical validation failures block Stable status.
- Original source data is immutable.
- A failed recovery attempt cannot overwrite a known-good implementation.
- All generated artifacts are traceable to tool versions and input hashes.

Modes:
- **Strict (default):** verified implementations only for Stable classification; uncertain paths are reported or isolated.
- **Balanced:** permits explicitly tested fallbacks and known limitations.
- **Experimental:** allows unverified experiments, clearly marked; never labels the result Stable.

## 8. Result statuses

- **Stable:** required automated gates pass; no unresolved critical issues; supported test coverage meets project policy.
- **Playable:** launches and operates with known limitations; no unresolved issue that blocks the tested gameplay path.
- **Experimental:** incomplete or insufficiently verified; may fail.
- **Failed:** cannot pass essential conversion or launch gates.

A conversion percentage is informational only. It must never determine status by itself. A game with 99% translated code can still fail if the missing 1% is essential.

## 9. Final Conversion Validator

FCV is independent from the conversion stages and checks the assembled output.

Required checks:
- package manifest and required files;
- file hashes and package integrity;
- executable/library architecture (Android ARM64 where required);
- unresolved symbols and dependency closure;
- translated code validation and metadata;
- compatibility-layer API availability;
- shader/asset format validation;
- cache key and cache integrity;
- runtime startup smoke test;
- logging/reporting path;
- graphics/input/audio smoke tests where supported;
- package version and toolchain provenance.

FCV must report **PASS**, **PASS WITH WARNINGS**, or **FAIL** per check and an overall result. It must not claim that automated smoke tests prove every level or gameplay situation is correct.

After FCV, the user manually tests the game. Manual testing is a separate final acceptance step.

## 10. Diagnostics

Two layers:
- **User report:** concise, readable, downloadable TXT and JSON; only important errors, warnings, unsupported components, recovery actions, and environment metadata.
- **Developer trace:** optional detailed log, disabled or minimized during normal gameplay; may include call identifiers, module, source address/operation, fallback reason, JIT outcome, and stack/context where safe.

Normal gameplay logging must be asynchronous, bounded, and low overhead. Do not synchronously write every event to disk. Provide rate limiting, deduplication, circular buffers, and opt-in detailed tracing.

Do not log personal data, secrets, access tokens, or raw copyrighted game content. Reports should include hashes/IDs rather than dumping assets.

## 11. Smart Conversion Cache

Two scopes:
- **Global Cache:** verified reusable platform-level translations, compatibility stubs, and tool artifacts.
- **Game Cache:** title-specific translated code, shaders, assets, and runtime metadata.

Every cache entry must include:
- content hash;
- source platform/backend;
- compiler/translator version;
- target ABI and runtime version;
- relevant configuration/options;
- dependency hashes;
- verification status and test provenance.

Only verified compatible entries may be reused automatically in Strict mode. Never share title-specific code merely because a function address matches. Cache invalidation must be deterministic.

## 12. Android graphics and performance controls

The app may expose settings only when the runtime/backend can actually implement them:
- internal render scale;
- texture quality/mip bias where supported;
- shadow quality or shadow rendering mode where controllable;
- lighting/effect quality where controllable;
- post-processing and antialiasing options supported by the renderer;
- frame-rate cap and VSync where supported.

Keep original converted assets immutable. Use reversible per-game profiles and generated variants/caches. Never destructively overwrite source or baseline assets.

Presets: Original, Quality, Balanced, Performance, Battery Saver, Custom. Presets are templates, not guarantees of a particular FPS.

## 13. Input and overlay

- configurable touch D-pad/sticks/buttons;
- per-game control layouts;
- external controller support through Android input APIs;
- optional hide/show controls;
- adjustable size, position, and opacity;
- FPS and frame-time overlay;
- optional CPU/GPU/runtime/fallback counters if reliable data is available.

Do not promise GPU utilization or temperature values unless the Android device exposes trustworthy metrics. FPS must be measured from actual presented/rendered frames and clearly define its measurement method.

## 14. Android packaging

One primary APK contains the launcher, runtime, supported backend, conversion tools required on-device, diagnostics, and UI. Converted games are stored as separate per-game data packages in app-managed storage, subject to Android storage constraints.

Do not assume a converted game must be embedded into a separate APK. A future export feature may be considered, but it is not required for the initial design.

Use Android ARM64 (`arm64-v8a`) as the initial target. Keep runtime and converted game package versioning separate so runtime updates do not require reconverting every game unless compatibility changes require it.

## 15. Target hardware

Reference device: POCO F6 Pro, Snapdragon 8 Gen 2, Adreno GPU. The app must query actual device capabilities at runtime rather than hard-code assumptions. Include thermal/performance safeguards and allow lower internal render scales.

## 16. Security, privacy, and legal requirements

- Work only with legally obtained user-provided game data.
- Do not include or distribute game images, proprietary assets, firmware, console keys, or proprietary SDK files.
- Do not implement DRM bypass, key extraction, or security circumvention.
- Review licenses of all tools, libraries, and reused code before integration or distribution.
- Treat imported files as untrusted input; validate bounds, lengths, offsets, and decompression limits.
- Static parsing, hashing, disassembly, metadata extraction, and format research do not execute imported code and are allowed project tasks.
- Never launch or execute an imported XEX during analysis. Runtime execution is a separate, explicit product feature and is not required for static-analysis milestones.
- Avoid arbitrary code execution from untrusted conversion artifacts.
- JIT must follow Android platform restrictions and use a documented, secure executable-memory strategy.
- Keep diagnostic exports free of personal data and secrets.

## 17. Definition of done for the first usable release

The first release is not “all Xbox 360 games supported”. It is:
- an installable Android app;
- a functioning library/import workflow for a documented test input;
- one end-to-end, legally usable proof-of-concept title/fixture;
- a reproducible conversion pipeline;
- a separate Converted Games area;
- runtime launch for the supported proof of concept;
- a verified fallback demonstration if supported by the chosen test;
- working diagnostics and final validation;
- touch/controller and FPS features to the extent supported by the proof-of-concept runtime;
- automated CI and documented limitations.

No feature may be marked complete based solely on generated code or a successful APK build.
