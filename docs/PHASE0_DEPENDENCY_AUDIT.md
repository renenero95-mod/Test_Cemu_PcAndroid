# Phase 0 — Dependency, Feasibility and Legal Audit

Status: **first pass, session 1.** Machine-readable twin: `docs/dependencies.json`.
Method: upstream repository pages read during session 1. GitHub API/LICENSE files were not reachable from the
authoring sandbox, so **licenses marked UNVERIFIED must be read from the LICENSE file before any integration**.
No dependency is approved.

## 1. Candidates

| Component | Role | License | Android/ARM64 | State | Decision |
|---|---|---|---|---|---|
| XenonRecomp | PPC → C++ recompiler | UNVERIFIED | host tool; not an Android runtime | not assessed | candidate |
| XenosRecomp | Xenos shader recompiler | MIT (shown on page) | not assessed | not assessed | candidate |
| UnleashedRecomp | game-specific reference | UNVERIFIED | Windows/Linux only | — | reference only |
| ReXGlue SDK | recomp runtime + toolkit | UNVERIFIED | Android not listed in CI | early dev, API unstable | candidate |
| xenia-android | Xenia Canary port | BSD-3-Clause (shown) | yes (Vulkan 1.0, API 24+) | archived, EOL | reference only |

## 2. Findings that shape the design

1. **Recompilers produce C++ that must be compiled.** XenonRecomp/ReXGlue-style output is C++ built with a
   native toolchain. An APK that "converts on the device" would need to ship a compiler + NDK sysroot, or
   use another backend (own IR → ARM64 emission, or interpreter). This is the central feasibility risk for
   *"conversion directly on Android"* and is not solved by the audited projects.
2. **Whole-game recompilation is per-game work.** The only audited full example (UnleashedRecomp) is a
   dedicated project for one title with hand-written GPU/audio/OS layers. A generic recompile-any-title
   engine is a research problem. Hence roadmap Phase 3 is deliberately a *narrow* proof of concept.
3. **Xenia is an emulator**, not a recompiler; reuse is for architecture reference (kernel/HLE, Vulkan) and only
   if its license and a maintained fork permit it. The Android port is EOL.
4. **Input handling.** UnleashedRecomp requires *unmodified user-owned game files*. That matches the
   project's legal stance (user-supplied data only, no assets distributed).

## 3. Legal boundaries (binding for development)

- No DRM/encryption circumvention, no key extraction, no bundled keys, no console firmware, no game assets.
- Retail XEX files are encrypted/compressed containers. **Reading them requires handling decryption keys.**
  Whether and how that is acceptable is a legal decision, not an engineering one → see
  `PROJECT_PROGRESS.md` → *Blockers and decisions* (D1). Until decided, the codebase handles only the
  project's own synthetic fixture format (`docs/PHASE0_POC_PROPOSAL.md`).
- Every future dependency needs: name, upstream URL, version, license text read, patent/trademark note,
  `docs/dependencies.json` entry. GPL/AGPL/unclear-license code is not linked into the APK without explicit approval.

## 4. What must be written by us (not reusable as-is)

Android shell and storage; job/state machine and checkpoints (done: `:core`); diagnostics/report model
(done: `:core`); Final Conversion Validator; cache keys/policy; runtime host (audio/video/input) for Android;
any generic CPU backend for ARM64; test fixtures.

## 5. Open items for Phase 0 completion

- Read the real LICENSE files (XenonRecomp, ReXGlue, Xenia Canary, plume, any shader/ISA libraries).
- Decide the conversion backend family (see feasibility finding 1): AOT-C++ with bundled toolchain,
  own IR→ARM64, interpreter-first. Needs a spike measuring size/time on a real device.
- Legal decision D1 (XEX handling).
