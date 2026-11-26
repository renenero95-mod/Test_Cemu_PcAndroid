#!/usr/bin/env python3
"""Regenerate PACKAGE_MANIFEST.json (sha256 + size of every project file). Run before zipping."""
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", ".gradle", "build", ".idea", "__pycache__"}
MANIFEST = "PACKAGE_MANIFEST.json"


def project_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            rel = os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/")
            if rel != MANIFEST and not name.endswith(".pyc"):
                yield rel


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(root=ROOT):
    prev = {}
    p = os.path.join(root, MANIFEST)
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            prev = json.load(f)
    files = [
        {"path": rel, "size_bytes": os.path.getsize(os.path.join(root, rel)), "sha256": sha256(os.path.join(root, rel))}
        for rel in project_files(root)
    ]
    return {
        "project": prev.get("project", "Console2Android"),
        "package_type": "AI development project (specification + incremental implementation)",
        "status": prev.get("status_override", "in_development_see_PROJECT_PROGRESS_md"),
        "files": files,
    }


if __name__ == "__main__":
    manifest = build()
    with open(os.path.join(ROOT, MANIFEST), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"{MANIFEST}: {len(manifest['files'])} files")
    sys.exit(0)
