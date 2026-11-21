#!/usr/bin/env python3
"""Checks that every dependency Cemu needs is present and usable. Used by the GitHub workflow BEFORE the build, so a problem is reported
in seconds with the exact links, instead of an obscure CMake/compiler error after an hour.
  python3 tools/deps/check_dependencies.py            human readable
  python3 tools/deps/check_dependencies.py --ci       also prints GitHub annotations
  python3 tools/deps/check_dependencies.py --links    only the download links of what is missing
Exit code 0 = all good, 1 = something missing/incompatible."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from deps_common import *


def run(root=ROOT, ci=False, only_links=False):
    manifest = load_manifest()
    problems = []
    for e in manifest:
        miss = missing_markers(e, root)
        if miss:
            problems.append((e, "mancano i file: " + ", ".join(miss)))
        elif e["name"] == "imgui":
            for p in check_imgui_dir(os.path.join(root, e["path"])):
                problems.append((e, p))
    if only_links:
        for e in {id(x): x for x, _ in problems}.values():
            print(e["zip"])
        return 1 if problems else 0
    if not problems:
        w = imgui_version_warning(os.path.join(root, "dependencies", "imgui"))
        if w:
            print("ATTENZIONE:", w)
            if ci:
                print("::warning title=imgui::" + w)
        print("Dipendenze OK (%d controllate)." % len(manifest))
        return 0
    print("DIPENDENZE MANCANTI O NON COMPATIBILI:\n")
    shown = set()
    for e, why in problems:
        print(" - %s  (cartella %s)\n     %s" % (e["name"], e["path"], why))
        if ci:
            print("::error title=Dipendenza %s::%s (%s)" % (e["name"], why, e["path"]))
        if e["name"] not in shown:
            shown.add(e["name"])
            print("     scarica: %s\n     nota: %s" % (e["zip"], e["note"]))
    print("\nCome sistemare: scarica gli zip indicati e dalli a Claude (o lancia tools/deps/install_dependency_zips.py <zip...>);"
          "\nnon cancellare le cartelle dependencies/ del repository: caricale piene.")
    return 1


if __name__ == "__main__":
    sys.exit(run(ci="--ci" in sys.argv, only_links="--links" in sys.argv))
