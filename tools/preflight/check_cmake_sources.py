#!/usr/bin/env python3
"""Every source/header file named in a CMakeLists.txt must exist. A missing file only shows up when CMake generates the build
(or when the compiler reaches it, an hour in). Checked for the project and for the dependencies that the Android build uses.
Tokens containing ${...} are skipped except ${CMAKE_CURRENT_SOURCE_DIR} / ${CMAKE_CURRENT_LIST_DIR}, which are resolved.
Usage: python3 tools/preflight/check_cmake_sources.py [root]"""
import os, re, sys

EXT = r"(?:cpp|cxx|cc|c|hpp|hh|h|inl|mm|m|S|s|asm|def|inc|rc|ico|plist|in|ui)"
TOKEN = re.compile(r'(?<![\w${}/.\-])((?:\$\{CMAKE_CURRENT_(?:SOURCE|LIST)_DIR\}/)?(?:\.\./|\./)*(?:[A-Za-z0-9_+\-]+/)*[A-Za-z0-9_+\-][A-Za-z0-9_+.\-]*\.' + EXT + r')(?![\w/])')
SKIP_DIRS = ("dependencies/vcpkg", "dependencies/vcpkg_overlay", "dependencies/Vulkan-Headers", "dependencies/imgui", "dependencies/metal-cpp",
             "dependencies/DirectX_2010", "dependencies/gamemode", "dependencies/stb", "dependencies/ih264d", "dependencies/cubeb/test", "dependencies/cubeb/googletest",
             "tests", "tools", ".git", "build")


def check(root):
    missing, total = [], 0
    for dp, dn, fn in os.walk(root):
        rel = os.path.relpath(dp, root).replace(os.sep, "/")
        dn[:] = [d for d in dn if not any((rel + "/" + d).lstrip("./").startswith(s) for s in SKIP_DIRS)]
        if "CMakeLists.txt" not in fn:
            continue
        path = os.path.join(dp, "CMakeLists.txt")
        text = open(path, errors="ignore").read()
        text = re.sub(r'(?m)#.*$', "", text)
        text = re.sub(r'(?is)\b(?:check_include_files?|check_symbol_exists|check_function_exists|check_c_source_compiles|check_cxx_source_compiles|find_path|find_file|find_library)\s*\([^)]*\)', "", text)  # system headers/libraries, not files of ours
        seen = set()
        for m in TOKEN.finditer(text):
            tok = m.group(1)
            rtok = tok.replace("${CMAKE_CURRENT_SOURCE_DIR}/", "").replace("${CMAKE_CURRENT_LIST_DIR}/", "")
            if rtok in seen or "${" in rtok:
                continue
            seen.add(rtok)
            total += 1
            if not os.path.exists(os.path.normpath(os.path.join(dp, rtok))):
                line = text[:m.start()].count("\n") + 1
                missing.append((os.path.relpath(path, root).replace(os.sep, "/"), line, rtok))
    return total, missing


def main(root):
    total, missing = check(root)
    if not missing:
        print("File dei CMakeLists OK (%d nomi di file controllati)." % total)
        return 0
    print("FILE NOMINATI IN CMAKE CHE NON ESISTONO (%d su %d):" % (len(missing), total))
    for f, l, t in missing[:80]:
        print("  %s:%d  %s" % (f, l, t))
    return 1


if __name__ == "__main__":
    sys.exit(main(os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))))
