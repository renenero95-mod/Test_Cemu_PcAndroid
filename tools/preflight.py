#!/usr/bin/env python3
"""Cheap fail-fast repo checks (run first in CI, and locally before zipping). Stdlib only.

Checks: required files, JSON validity, manifest integrity, pinned versions, ABI policy,
forbidden/large files, obvious secrets, workflow action pinning, PROJECT_PROGRESS.md sections.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import update_manifest  # noqa: E402

REQUIRED = [
    "AI_START_PROMPT.md", "AI_DEVELOPMENT_RULES.md", "ARCHITECTURE.md", "BUILD_AND_CI.md",
    "CONSOLE2ANDROID_MASTER_PLAN.md", "DEVELOPMENT_ROADMAP.md", "README.md", "TESTING_AND_VALIDATION.md",
    "PROJECT_PROGRESS.md", "PACKAGE_MANIFEST.json", ".github/workflows/android-ci.yml",
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
    "core/build.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
    "docs/PHASE0_DEPENDENCY_AUDIT.md", "docs/dependencies.json",
]
PROGRESS_SECTIONS = ["## Completed", "## Remaining", "## Blockers and decisions", "## Verification status"]
FORBIDDEN_EXT = {".xex", ".xbe", ".iso", ".stfs", ".keystore", ".jks", ".p12", ".pem", ".apk", ".aab"}
MAX_BYTES = 50 * 1024 * 1024
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"ghp_[A-Za-z0-9]{36}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{50,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
]
TEXT_EXT = {".md", ".kt", ".kts", ".xml", ".yml", ".yaml", ".json", ".toml", ".properties", ".py", ".txt", ""}

errors, notes = [], []


def err(msg):
    errors.append(msg)


def main():
    for rel in REQUIRED:
        p = os.path.join(ROOT, rel)
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            err(f"missing or empty required file: {rel}")

    files = list(update_manifest.project_files(ROOT))
    for rel in files:
        p = os.path.join(ROOT, rel)
        ext = os.path.splitext(rel)[1].lower()
        if ext in FORBIDDEN_EXT:
            err(f"forbidden file type in repo: {rel}")
        if os.path.getsize(p) > MAX_BYTES:
            err(f"file too large (>50MB): {rel}")
        if ext in TEXT_EXT:
            try:
                text = open(p, encoding="utf-8").read()
            except UnicodeDecodeError:
                err(f"not valid UTF-8: {rel}")
                continue
            if rel != "tools/preflight.py":
                for pat in SECRET_PATTERNS:
                    if pat.search(text):
                        err(f"possible secret in {rel}")
            if ext == ".json":
                try:
                    json.loads(text)
                except ValueError as e:
                    err(f"invalid JSON {rel}: {e}")

    # manifest integrity
    mp = os.path.join(ROOT, "PACKAGE_MANIFEST.json")
    if os.path.isfile(mp):
        recorded = {f["path"]: f["sha256"] for f in json.load(open(mp, encoding="utf-8")).get("files", [])}
        actual = {f["path"]: f["sha256"] for f in update_manifest.build(ROOT)["files"]}
        if recorded != actual:
            diff = sorted(set(recorded) ^ set(actual)) + sorted(k for k in recorded.keys() & actual.keys() if recorded[k] != actual[k])
            err("PACKAGE_MANIFEST.json out of date (run tools/update_manifest.py): " + ", ".join(diff[:8]))

    # pinned versions
    toml = open(os.path.join(ROOT, "gradle/libs.versions.toml"), encoding="utf-8").read()
    for line in toml.splitlines():
        code = line.split("#", 1)[0]
        m = re.match(r'\s*\w+\s*=\s*"([^"]*)"\s*$', code)
        if m and (m.group(1) in ("latest", "") or "+" in m.group(1) or m.group(1).startswith("[")):
            err(f"unpinned version in libs.versions.toml: {line.strip()}")
    for rel in ("app/build.gradle.kts", "core/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts"):
        t = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        if re.search(r'["\'][^"\']*:(latest\.[a-z]+|\+)["\']', t):
            err(f"dynamic dependency version in {rel}")

    # ABI policy: only arm64-v8a for the primary target
    app = open(os.path.join(ROOT, "app/build.gradle.kts"), encoding="utf-8").read()
    abis = re.findall(r'abiFilters\s*\+?=\s*(?:listOf\()?\s*"([^"]+)"', app)
    if abis != ["arm64-v8a"]:
        err(f"abiFilters must be exactly arm64-v8a for now, found {abis}")

    # workflow: every action reference carries an explicit version
    wf = open(os.path.join(ROOT, ".github/workflows/android-ci.yml"), encoding="utf-8").read()
    for m in re.finditer(r"uses:\s*(\S+)", wf):
        if "@" not in m.group(1):
            err(f"workflow action without version: {m.group(1)}")
    if "permissions:" not in wf:
        err("workflow must declare explicit permissions")
    try:
        import yaml  # optional
        yaml.safe_load(wf)
    except ImportError:
        notes.append("PyYAML not available: workflow syntax not parsed")
    except Exception as e:  # noqa: BLE001
        err(f"workflow YAML invalid: {e}")

    # progress file structure
    pp = os.path.join(ROOT, "PROJECT_PROGRESS.md")
    if os.path.isfile(pp):
        t = open(pp, encoding="utf-8").read()
        for s in PROGRESS_SECTIONS:
            if s not in t:
                err(f"PROJECT_PROGRESS.md missing section: {s}")

    for n in notes:
        print("note:", n)
    if errors:
        print(f"PREFLIGHT FAILED ({len(errors)} problem(s)):")
        for e in errors:
            print(" -", e)
        sys.exit(1)
    print(f"PREFLIGHT OK ({len(files)} files checked)")


if __name__ == "__main__":
    main()
