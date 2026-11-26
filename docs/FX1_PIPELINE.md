# C2A-FX1 conversion pipeline (Phase 3, proof of concept)

Status: **implemented in `:core` (JVM-tested, differential + mutation checked) and wired in the app (typechecked against stubs, not built here)**.
Scope: the project's own **synthetic** fixture container, `C2A-FX1`, holding a documented PowerPC 32-bit subset. It is **not** an Xbox 360
format. Real XEX1/XEX2 executables are **not converted** (the pipeline rejects them with `FORMAT-UNSUPPORTED`); they remain importable,
analysable and comparable with the XEX1/XEX2 reference identifiers. No protection is circumvented: nothing here decrypts or unlocks anything.

## Stages (`Fx1Stages.kt`, run by `JobRunner` in `Pipeline.kt`)
| State | Stage | Output hash chains to |
|---|---|---|
| VALIDATING | source kind is C2AFX1, size bound, stored copy re-hashed | source SHA-256 |
| ANALYZING | strict parse of the container (structured `FX1-*` events) | image hash (entry + code + data) |
| EXTRACTING | writes `code.bin`, `data.bin` to the job work dir | extract hash |
| TRANSLATING | `PpcTranslator` -> C2A-IR text (`program.c2air`) | SHA-256 of the IR text |
| PACKAGING | content-addressed package `packages/<cacheKey>/`; cache hit reuses an intact package | cache key + program hash |
| FINAL_VALIDATION | `PackageValidator` (always re-run, never reused) | status + FCV |

`RECOVERING` is optional and not used yet (the interpreter fallback is explicit and counted at run time).

## Rules enforced by the runner
- Job is persisted after every transition; a paused or interrupted job resumes from verified checkpoints, which are re-checked
  against the files on disk (`CHECKPOINT-STALE` otherwise). Same input + same tool version = reuse; anything else re-runs.
- STRICT rejects partial results (any unsupported instruction / external branch target) -> `STRICT-PARTIAL`; BALANCED/EXPERIMENTAL continue
  and report them; execution later stops explicitly at the unsupported instruction, never approximates it.
- READY only through `ConversionJob.complete(fcv)`; a FAIL from the validator makes the job FAILED. Exceptions become `STAGE-EXCEPTION`
  (class name only). Cancel / pause are cooperative between stages.
- Cache key = `sha256("c2a-cache/1|source|translator|irFormat|mode")`. A corrupt cached package is deleted and rebuilt, never trusted.
- `report.txt` / `report.json` are written to the job work dir (`jobs` work area); XEX reference values appear only as length + fingerprint.

## Final Conversion Validator checks (`PackageValidator`)
manifest, tool versions, cache key, artifact hashes, IR loadable, reproducible (re-translation gives identical program), dependency closure
(no dangling direct branch targets), unresolved operations (mandatory in STRICT), differential smoke test (reference interpreter vs.
package runtime: registers, memory, output, exit code, steps), optional XEX reference check. `status.properties` carries
`scope=synthetic-fixture-checks`: the status says what these checks exercised, **not** that any game works.

## Runtime
`PackageRuntime` runs the program in the C2A-IR executor with the interpreter as an explicit, counted fallback. Host calls: print-int, exit.
No graphics, audio, input, filesystem or network. Guest memory is bounds- and permission-checked.

## Known limits
Instruction subset only (add/subf/and/or, addi/addis/ori, lwz/stw, cmp, b/bc/bclr, mfspr/mtspr LR/CTR, sc). No native ARM64 emission;
no real XEX loading/sections/imports/kernel; performance not measured. The sample (`Fx1Samples.sumAndDouble`) sums 1..10, calls a
subroutine and prints `110, 55`.
