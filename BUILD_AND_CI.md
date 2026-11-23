# Build and GitHub Actions Strategy

## 1. Principle

Catch cheap failures before expensive native/Android builds. CI must fail fast when repository structure, dependency declarations, formatting, static analysis, or tests fail.

A successful APK build proves packaging/compilation, not game compatibility.

## 2. CI stages

1. **Repository preflight**
   - required files/directories;
   - no accidental huge game dumps or secrets;
   - valid JSON/YAML/Gradle syntax where practical;
   - no broken local paths.
2. **Dependency validation**
   - pinned versions;
   - license inventory;
   - Android SDK/NDK/CMake/Gradle compatibility.
3. **Static checks**
   - formatting;
   - lint;
   - static analysis;
   - dependency/license checks.
4. **Unit/native tests**
   - host-side tests where possible;
   - ARM64 cross-compilation checks;
   - Android unit tests.
5. **Integration tests**
   - runtime interfaces;
   - package manifest;
   - cache and report tests.
6. **APK assembly**
   - debug APK for CI;
   - upload only on success.
7. **Artifact validation**
   - APK exists and is non-empty;
   - inspect package/ABI;
   - checksum and artifact metadata.

## 3. Cost control

- Use path filters where safe.
- Cache Gradle and compiler downloads by lock/version hashes.
- Avoid rebuilding unchanged native components.
- Keep a fast `preflight` job independent from full build.
- Do not run expensive jobs after preflight/test failure.
- Pin action versions to immutable commit SHAs before production use.
- Set timeouts and concurrency cancellation.
- Preserve logs and test results as artifacts.

## 4. Required environment metadata

Record:
- JDK version;
- Gradle wrapper version;
- Android Gradle Plugin version;
- compile/target/min SDK;
- NDK version;
- CMake version;
- ABI;
- compiler versions;
- dependency lock/checksum data.

## 5. Signing

CI should produce a debug-signed APK by default. Release signing must use GitHub secrets or a secure release system. Never commit keystores, passwords, signing keys, or tokens.

## 6. Starter workflow status

`.github/workflows/android-ci.yml` is a starter skeleton and intentionally does not claim to build the app until the Android project and pinned toolchain exist. The AI must update it to match the actual Gradle project, pin actions, and test it on a clean checkout.

## 7. Local preflight checklist

Before a full build:
- clean checkout succeeds;
- wrapper exists and is executable;
- Gradle files parse;
- dependency resolution succeeds;
- SDK/NDK versions match;
- native CMake configuration succeeds;
- unit tests pass;
- ABI is `arm64-v8a`;
- no required local-only file is missing.
