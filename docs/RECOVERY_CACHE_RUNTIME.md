# Recovery, Cache, Interpreter, JIT, and Runtime

## 1. Recovery classification

First classify the issue:
- CPU instruction/semantics;
- guest memory or synchronization;
- source-platform system API;
- graphics command/shader;
- audio;
- asset format;
- timing;
- unknown.

Do not route every problem to the CPU interpreter.

## 2. Recovery sequence

1. Verified direct AOT translation.
2. Alternative lowering/translation with equivalent semantics.
3. Compatibility Layer implementation for missing platform service.
4. Interpreter for operations covered by the interpreter.
5. JIT for eligible code paths.
6. Unsupported status and report.

A JIT cannot automatically repair a missing system service, shader feature, audio codec, or incorrect hardware model.

## 3. Interpreter/JIT correctness

- Define guest state explicitly.
- Define calling convention and transition protocol.
- Preserve required registers, flags, memory ordering, exceptions, and control flow.
- Validate native-to-interpreter and interpreter-to-native transitions.
- JIT only eligible code and obey Android executable-memory/security restrictions.
- Invalidate generated code when source bytes or dependencies change.
- Do not promote generated code to verified cache without tests.
- Provide a safe fallback if JIT is unavailable on a device.

## 4. Smart Conversion Cache

### Global cache
Reusable verified platform-level artifacts only.

### Game cache
Title-specific translated blocks, shader variants, asset derivatives, and metadata.

Each entry needs:
- SHA-256 content hash;
- source/backend identifier;
- translator/compiler version;
- runtime ABI/version;
- options and feature flags;
- dependency hashes;
- verification status;
- test provenance.

Cache hits must validate all compatibility dimensions. Address equality alone is not enough.

## 5. Runtime dispatch

- Prefer verified AOT native output.
- Otherwise use supported interpreter.
- JIT may optimize eligible hot paths.
- System/GPU/audio operations route to their dedicated compatibility subsystem.
- If no correct implementation exists, emit an explicit unsupported event and fail safely.

## 6. Performance

Measure frame time and fallback overhead. Avoid claims that a higher AOT percentage automatically means faster gameplay. Profile real workloads and thermal behavior on the target device.
