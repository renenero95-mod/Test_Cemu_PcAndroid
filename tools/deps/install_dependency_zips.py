#!/usr/bin/env python3
"""Installs downloaded GitHub 'repository.zip' files into dependencies/. The kind of each zip is recognised from its content, so file
names do not matter. Nested dependencies (sanitizers-cmake inside cubeb, linkernsbypass inside libadrenotools) go to the right place.
  python3 tools/deps/install_dependency_zips.py file1.zip file2.zip folder_with_zips/ [--force] [--dry-run]"""
import os, shutil, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from deps_common import *
import check_dependencies


def collect(args):
    out = []
    for a in args:
        if os.path.isdir(a):
            out += [os.path.join(a, f) for f in sorted(os.listdir(a)) if f.lower().endswith(".zip")]
        elif os.path.isfile(a):
            out.append(a)
        else:
            print("non trovato:", a)
    return out


def main(argv, root=ROOT):
    force = "--force" in argv
    dry = "--dry-run" in argv
    manifest = load_manifest()
    zips = collect([a for a in argv if not a.startswith("--")])
    if not zips:
        print(__doc__)
        return 2
    # parents before children: cubeb before sanitizers-cmake, libadrenotools before linkernsbypass
    found = []
    ok = True
    for z in zips:
        try:
            entry, zroot = classify_zip(z, manifest)
        except Exception as ex:  # not a zip, damaged...
            print("  ERRORE %s: %s" % (os.path.basename(z), ex))
            ok = False
            continue
        if entry is None:
            print("  NON RICONOSCIUTO %s (non è nessuna delle dipendenze attese)" % os.path.basename(z))
            ok = False
            continue
        found.append((entry, zroot, z))
    found.sort(key=lambda t: len(t[0]["path"]))
    for entry, zroot, z in found:
        dest = os.path.join(root, entry["path"])
        if os.path.isdir(dest) and not missing_markers(entry, root) and not force:
            print("  già presente: %-16s (uso --force per sostituire)" % entry["name"])
            continue
        if dry:
            print("  installerei %-16s -> %s  (da %s)" % (entry["name"], entry["path"], os.path.basename(z)))
            continue
        # keep nested dependencies that were already installed inside this folder
        keep = {}
        for other in manifest:
            if other is not entry and other["path"].startswith(entry["path"] + "/") and not missing_markers(other, root):
                keep[other["path"]] = tempfile.mkdtemp()
                shutil.copytree(os.path.join(root, other["path"]), keep[other["path"]], dirs_exist_ok=True)
        if os.path.isdir(dest):
            shutil.rmtree(dest)
        try:
            n = safe_extract(z, zroot, dest)
        except Exception as ex:
            print("  ERRORE %-16s %s" % (entry["name"], ex))
            ok = False
            continue
        for p, tmp in keep.items():
            shutil.copytree(tmp, os.path.join(root, p), dirs_exist_ok=True)
            shutil.rmtree(tmp, ignore_errors=True)
        print("  installato:   %-16s %5d file -> %s" % (entry["name"], n, entry["path"]))
    print()
    rc = check_dependencies.run(root)
    return 0 if (ok and rc == 0) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
