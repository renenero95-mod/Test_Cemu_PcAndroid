# Diagnostics and Reports

## 1. Two output levels

### User report (default)
Short and understandable. Download as TXT and JSON. Include:
- game/package identifier and source hash;
- conversion/runtime/tool versions;
- device model, Android version, ABI;
- overall conversion and validation status;
- critical errors and important warnings;
- unsupported components;
- recovery attempts and final route (native, compatibility layer, interpreter, JIT);
- relevant function/module identifiers;
- timestamps and reproducible issue IDs.

Do not include every successful operation.

### Developer trace (opt-in)
More detailed events for debugging:
- module and stage;
- source operation/function identifier;
- guest address or stable symbol ID where available;
- translation/interpreter/JIT route;
- fallback reason;
- validation result;
- error code and relevant state metadata.

Do not dump entire memory, copyrighted assets, secrets, or personal data.

## 2. Gameplay logging

- Default: errors and important warnings only.
- Fallback/JIT events: aggregate counts and deduplicated event summaries.
- Detailed per-call tracing: user-enabled developer mode only.
- Asynchronous logging, bounded queues, circular buffers, rate limiting.
- Avoid synchronous disk writes in frame-critical paths.
- If the queue overflows, increment a dropped-event counter and report it.

## 3. Example user report

```text
CONSOLE2ANDROID — GAME DIAGNOSTIC REPORT
Game: <game-id>
Package: <package-version>
Runtime: <runtime-version>
Device: <device-model>
Android: <version> / arm64-v8a

Overall: PLAYABLE
FCV: PASS WITH WARNINGS

IMPORTANT ISSUES
[WARNING] GPU — shader variant unsupported; fallback renderer path used.
[WARNING] CPU — guest block <id> executed by interpreter; JIT not promoted.

RECOVERY SUMMARY
AOT: <count or ratio>
Compatibility layer: <count>
Interpreter: <count>
JIT: <count>
Unresolved: <count>

CRITICAL ERRORS: 0
Warnings: 2
Dropped diagnostic events: 0

Report ID: <unique-id>
```

Values must be measured, not hard-coded.

## 4. Privacy

Before export, show the user what metadata is included. Do not include account identifiers, precise location, personal files, tokens, or unrelated device data.
