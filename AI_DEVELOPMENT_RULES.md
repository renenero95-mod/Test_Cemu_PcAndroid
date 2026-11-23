# AI Development Rules — Console2Android

These instructions are mandatory for any AI coding agent working on this repository.

## 1. Read before coding

Read, in order:
1. `README.md`
2. `CONSOLE2ANDROID_MASTER_PLAN.md`
3. `ARCHITECTURE.md`
4. `DEVELOPMENT_ROADMAP.md`
5. `TESTING_AND_VALIDATION.md`
6. Relevant documents under `docs/`
7. `BUILD_AND_CI.md`

Do not start implementation until you can summarize the architecture, constraints, first milestone, and known feasibility risks.

## 2. Work in controlled checkpoints

Use the hybrid autonomous approach:
- Work autonomously within the current approved milestone.
- Do not skip milestone exit criteria.
- Stop when an architectural decision, legal/license issue, unsupported format, or correctness question blocks progress.
- Ask for human approval before changing core architecture, adding a major dependency, changing supported input formats, or weakening Strict-mode requirements.
- Do not wait for approval for routine implementation details that remain within the approved design.

## 3. Never fabricate functionality

- Do not invent Xbox 360 formats, APIs, instruction semantics, library names, tool capabilities, or build flags.
- Verify every external dependency against its official documentation/source and current license.
- If network access is unavailable, record the uncertainty and do not pretend verification occurred.
- Do not claim arbitrary ISO support unless implemented and tested.
- Do not claim a title is playable based only on static analysis or compilation.
- Do not generate placeholder methods that return success for unimplemented functionality.
- Do not hide unsupported functions behind a successful status.

## 4. Implement one vertical slice first

Start with a legal test fixture or purpose-built executable and a small documented feature subset. Build:
- input validation;
- manifest;
- one translation path;
- one runtime path;
- one correctness test;
- one Android build;
- one diagnostic report.

Only expand after the vertical slice works reproducibly.

## 5. Required module workflow

For every module:
1. State the purpose and interfaces.
2. List assumptions and supported subset.
3. Implement the smallest correct increment.
4. Add unit tests.
5. Add integration tests where relevant.
6. Run formatting, static analysis, and tests.
7. Build the affected target.
8. Update docs and limitations.
9. Record evidence and remaining risks.
10. Mark status as `implemented`, `partial`, `experimental`, or `not implemented`.

## 6. Correctness rules

- Prefer semantic equivalence over speed.
- Preserve guest-visible CPU state, memory ordering, exceptions, flags, and timing assumptions as required by the supported subset.
- Use differential testing against a trusted reference implementation where legally and technically possible.
- Fallback transitions must preserve state and calling conventions.
- JIT code must be invalidated when source code or relevant state changes.
- Do not mark a function verified from one successful execution alone.
- GPU, audio, system API, and synchronization failures need domain-specific recovery; do not route every issue through CPU JIT.
- Any approximate behavior must be explicitly classified and must not qualify as Stable under Strict mode.

## 7. Cache rules

- Cache keys must include all inputs that can affect generated output.
- Verify hashes on read and write.
- Store toolchain and runtime versions.
- Do not reuse entries with mismatched ABI, compiler, configuration, dependencies, or verification status.
- Never overwrite a verified entry with an unverified one.
- Add cache corruption and invalidation tests.

## 8. Diagnostics rules

- User-facing reports must be concise and understandable.
- Detailed tracing is opt-in.
- Logging must be asynchronous, bounded, rate-limited, and low overhead.
- Deduplicate repeated errors.
- Include identifiers, module, stage, severity, fallback/recovery action, and version metadata.
- Do not include secrets, personal data, or copyrighted assets in reports.

## 9. Android and CI rules

- Initial ABI: `arm64-v8a`.
- Pin Gradle plugins, dependencies, NDK, CMake, and tool versions.
- Avoid unpinned `latest` dependencies.
- Use a fast preflight before expensive builds.
- Separate lint/static checks, native tests, Android unit tests, and APK assembly.
- Upload APK only after all required jobs pass.
- Do not commit signing keys or secrets.
- Use debug signing for CI artifacts unless a secure release-signing process is explicitly configured.

## 10. Legal and security rules

- Do not implement DRM circumvention, key extraction, firmware dumping, or security bypass.
- Do not bundle commercial game content or proprietary console SDKs.
- Review all dependency licenses.
- Validate untrusted binary inputs and decompression sizes.
- Keep JIT memory handling compliant with Android restrictions and device policy.

## 11. Reporting format at every checkpoint

At the end of each checkpoint, provide:
- milestone and commit/hash;
- files changed;
- features implemented;
- tests run and exact results;
- build result;
- supported subset;
- known limitations;
- unresolved blockers;
- next milestone.

Never report “done” without test evidence.

## 12. Session and delivery protocol (owner instructions)

- Work step by step on the **same project and the same ZIP** (`Console2Android_AI_Starter.zip`). Update its
  contents as work proceeds; never create a new archive per session.
- At the end of every session update `PROJECT_PROGRESS.md`: what is completed, what remains, blockers,
  verification status (what was actually run vs. not run).
- Chat output is minimal (token-saving): the ZIP, `PROJECT_PROGRESS.md` and a few words. Analysis, plans and
  open questions belong in `PROJECT_PROGRESS.md`, not in chat. This overrides the long first-response
  format in `AI_START_PROMPT.md`.
- The finished ZIP is extracted by the owner and uploaded to GitHub; **GitHub Actions builds the APK**.
  Keep the repo root layout CI-ready (`.github/workflows` at the root of the extracted contents) and run
  `python3 tools/update_manifest.py && python3 tools/preflight.py` before zipping.
- Product constraint: the single APK must contain everything — Library, Conversion Engine, Smart Conversion
  Cache, Final Conversion Validator, Converted Games, Game Runtime, Diagnostics, Settings. Conversion and
  launch are features of the same app, not preparation of files for another program.
