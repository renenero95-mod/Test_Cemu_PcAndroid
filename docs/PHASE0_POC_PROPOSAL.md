# Phase 0/3 — Proof-of-Concept Proposal: C2A-FX1 synthetic fixture

**Goal:** the smallest end-to-end slice that exercises *every* pipeline stage with data we fully own,
so the pipeline, cache, validator and diagnostics are real before any proprietary format is touched.

## Scope (one narrow vertical slice)

- **Input:** `*.c2afx` — our own container, **not** an Xbox 360 executable and never described as one.
  Header: magic `C2AFX1\0\0`, little-endian u32 fields (entry, code offset/size, data offset/size), then a big-endian
  PowerPC code section and a data section. Built by a small generator in `tools/` (no external assets).
- **Instruction subset (initial):** `addi addis add subf ori and or lwz stw cmpwi cmplw b bl bc blr` plus one
  documented host call (`sc` → print integer / exit code). Everything else → `Unsupported` with an exact diagnostic.
- **Stages:** validate → analyse (basic blocks) → translate (reference interpreter first, then AOT/IR backend) →
  package → Final Conversion Validator → run in Game Runtime → report.
- **Acceptance (all machine-checked, in `:core` tests or CI):**
  1. Interpreter and converted output produce identical register/memory results on the fixture set.
  2. Unsupported opcode produces `Unsupported`, never a silent success; status is derived by `StatusPolicy`.
  3. Same input + same tool versions ⇒ cache hit; changed input or tool version ⇒ miss.
  4. Corrupt/truncated fixture ⇒ clean `FAILED` with a diagnostic, no crash.

## Non-goals

Real XEX, GPU/shader translation, audio, kernel HLE, compatibility claims about any real game.

## Why this first

It de-risks architecture (checkpoints, cache keys, failure routing) with zero legal exposure, and gives the
spike for the backend decision recorded in `PHASE0_DEPENDENCY_AUDIT.md` §5.
