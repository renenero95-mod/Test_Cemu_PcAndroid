#!/usr/bin/env python3
"""Every dependencies/... path that Cemu's own CMake files mention must exist. Catches an empty or half-installed dependency folder in
seconds (a CMake configure error would only appear after the build has started).  Usage: python3 tools/deps/check_cmake_paths.py [root]"""
import os, re, sys

def referenced_paths(root):
    refs = {}
    for dp, dn, fn in os.walk(root):
        rel = os.path.relpath(dp, root)
        if rel.startswith(("dependencies", ".git", "tests", "tools", "build")):
            continue
        for f in fn:
            if f == "CMakeLists.txt" or f.endswith(".cmake"):
                p = os.path.join(dp, f)
                text = open(p, errors="ignore").read()
                text = re.sub(r"(?m)^\s*#.*$", "", text)  # comments
                for m in re.finditer(r'dependencies/([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*)', text):
                    path = "dependencies/" + m.group(1)
                    if "${" not in path and not path.endswith("."):
                        refs.setdefault(path, []).append(os.path.relpath(p, root))
    return refs

def main(root):
    refs = referenced_paths(root)
    missing = [(r, ps[0]) for r, ps in sorted(refs.items()) if not os.path.exists(os.path.join(root, r))]
    if not missing:
        print("Percorsi CMake OK (%d controllati)." % len(refs))
        return 0
    print("PERCORSI CHE CMAKE CERCA E CHE NON ESISTONO:")
    for r, p in missing:
        print("  %-60s (citato in %s)" % (r, p))
    return 1

if __name__ == "__main__":
    sys.exit(main(os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))))
