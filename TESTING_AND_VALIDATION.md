# Testing and Final Validation

## 1. Testing pyramid

- Unit tests: parsers, hashes, instruction semantics, cache keys, profile parsing, report formatting.
- Differential tests: translated/interpreted behavior compared with reference semantics.
- Integration tests: guest state handoff, compatibility services, graphics/audio/input adapters.
- Package tests: output manifest, dependencies, ABI, integrity.
- Runtime smoke tests: launch, initialize, render, input, audio, clean exit.
- Manual gameplay tests: user plays the converted result and records observations.

## 2. Final Conversion Validator (FCV)

FCV must be independent from conversion success flags. It reads the final package and verifies:
- manifest schema/version;
- required files and hashes;
- architecture/ABI;
- unresolved symbols;
- runtime dependency closure;
- translated code verification metadata;
- cache references and compatibility keys;
- shader/asset validity for supported formats;
- compatibility service availability;
- startup smoke test;
- diagnostic report generation;
- package/runtime version compatibility.

Each check returns `PASS`, `WARN`, `FAIL`, or `NOT APPLICABLE`, with a short reason and evidence reference.

Overall:
- `PASS`: all mandatory checks pass.
- `PASS WITH WARNINGS`: no mandatory failure, but limitations exist.
- `FAIL`: one or more mandatory checks fail.

## 3. User manual test after FCV

The app must clearly indicate that automated validation is complete and manual gameplay testing remains.

User can:
- launch from Converted Games;
- use the game normally;
- open diagnostics overlay/panel;
- save a session report;
- annotate the outcome: User tested / Playable with issues / Failed during gameplay.

The user's annotation must not overwrite machine validation results. Store them as separate fields.

## 4. Failure injection

Tests must deliberately simulate:
- truncated input;
- malformed offsets/lengths;
- corrupted cache entry;
- missing dependency;
- unsupported instruction;
- failed shader compilation;
- audio conversion failure;
- runtime crash;
- disk full;
- app process termination during conversion;
- incompatible cache version.

Expected behavior: safe failure, resumable checkpoints where possible, no corrupted final package, useful report.

## 5. Stability status

- Stable: mandatory FCV checks pass and no unresolved critical issue; supported test coverage is documented.
- Playable: launch and tested path work with documented limitations.
- Experimental: incomplete or not adequately verified.
- Failed: essential conversion/runtime checks fail.

A conversion percentage alone must never determine status.
