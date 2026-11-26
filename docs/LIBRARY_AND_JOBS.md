# Source library and job store (Phase 2, first slice)

Status: **implemented in `:core` (JVM-tested) and wired in the app UI (typechecked against stubs, not built here)**.

## Source library (`SourceLibrary`)
- Layout under `<filesDir>/c2a/`: `sources/<sha256>.bin` (read-only), `library/<sha256>.properties`, `tmp/` (in-flight copies).
- Import = one streaming pass: copy to `tmp/*.part` while hashing (bounded memory, any file size), then atomic rename. Identity is the
  SHA-256 of the stored bytes, so the same file is never stored twice. Failure/cancel/IO error leaves no partial file; leftovers are
  deleted at startup. Free-space floor 64 MiB, re-checked every 8 MiB. Names are sanitised metadata only (never used as paths).
- `verify()` re-hashes the stored copy; `remove()` is user-initiated. Corrupt/orphan index entries are skipped, never thrown.
- Format detection: XEX2/XEX1 by magic via `XexHeaderProbe`, `C2AFX1` by its own magic; everything else is `UNKNOWN` (accepted, not rejected).
- Android: files are picked with the Storage Access Framework (no storage permission); `allowBackup=false`.

## Jobs (`JobFactory`, `JobStore`)
- `JobFactory.create` builds a `QUEUED` job from a library entry; `backend = "unselected"` because the backend is open decision D2.
  User XEX reference fingerprints + comparison are attached as metadata (never cache-key inputs).
- `JobStore` persists each job atomically as a `.properties` file (all fields round-trip, incl. checkpoints and reference summary);
  raw XEX values are never written. Jobs run through the C2A-FX1 proof-of-concept pipeline (see `docs/FX1_PIPELINE.md`); each job also has a work directory `<root>/work/<jobId>/` that is deleted with the job.

## Not implemented
Quotas/eviction policy, resumable imports, jobs referencing removed sources (job keeps its hash; UI does not yet flag it),
mode picker (UI always creates STRICT), XISO/other container detection.
