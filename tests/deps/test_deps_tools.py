#!/usr/bin/env python3
"""Tests of the dependency tools with synthetic GitHub-style zips (no network needed)."""
import io, json, os, shutil, stat, sys, tempfile, zipfile, contextlib
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "deps"))
import deps_common as dc, check_dependencies as cd, install_dependency_zips as inst, check_imgui_symbols as cis, check_cmake_paths as ccp

fails = 0
def check(cond, msg):
    global fails
    if not cond:
        print("  FAIL:", msg); fails += 1

IMGUI_OK = '#define IMGUI_VERSION "1.89.9"\ntypedef void* ImTextureID;\nstruct ImFont { float FallbackAdvanceX; };\nstruct ImGuiIO { float NavInputs[21]; void* ImeWindowHandle; void AddKeyEvent(int,bool); };\n'
IMGUI_192 = '#define IMGUI_VERSION "1.92.0"\ntypedef ImU64 ImTextureID;\nstruct ImFontBaked { float FallbackAdvanceX; };\nstruct ImGuiIO { void AddKeyEvent(int,bool); };\n'

def make_zip(path, root, files, extra=None):
    with zipfile.ZipFile(path, "w") as z:
        for name, content in files.items():
            z.writestr(root + name, content)
        for name, data in (extra or {}).items():
            z.writestr(name, data)

def zips_for(tmp, imgui=IMGUI_OK, rename=False):
    d = os.path.join(tmp, "zips"); os.makedirs(d, exist_ok=True)
    manifest = dc.load_manifest(); out = []
    for i, e in enumerate(manifest):
        files = {m: "" for m in e["markers"]}
        for f, text in e.get("content", {}).items():
            files[f] = "project(%s)" % text       # e.g. liblinkernsbypass: its CMakeLists must mention linkernsbypass
        if e["name"] == "imgui":
            files["imgui.h"] = imgui
        p = os.path.join(d, ("repository (%d).zip" % i) if rename else e["name"] + "-main.zip")
        make_zip(p, e["name"] + "-main/", files); out.append(p)
    return out

def quiet(fn, *a, **k):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        r = fn(*a, **k)
    return r, buf.getvalue()

print("[SIMPLE] install everything from GitHub-style zips with meaningless names")
with tempfile.TemporaryDirectory() as tmp:
    root = os.path.join(tmp, "repo"); os.makedirs(root)
    zs = zips_for(tmp, rename=True)
    rc, out = quiet(inst.main, zs, root)
    check(rc == 0, "install returned %d\n%s" % (rc, out))
    check(os.path.isfile(os.path.join(root, "dependencies/cubeb/cmake/sanitizers-cmake/cmake/FindSanitizers.cmake")), "sanitizers-cmake not nested inside cubeb")
    check(os.path.isfile(os.path.join(root, "dependencies/libadrenotools/lib/linkernsbypass/CMakeLists.txt")), "linkernsbypass not nested inside libadrenotools")
    check(cd.run(root) == 0 if True else False, "checker disagrees after install")
    rc2, out2 = quiet(inst.main, zs, root)
    check("già presente" in out2 and rc2 == 0, "second run should say already present")
    shutil.rmtree(os.path.join(root, "dependencies/imgui/")); 
    rc3, out3 = quiet(inst.main, zs[::-1], root)  # reverse order: children first, parent later
    check(rc3 == 0 and os.path.isfile(os.path.join(root, "dependencies/cubeb/cmake/sanitizers-cmake/cmake/FindSanitizers.cmake")), "nested dependency lost when the parent was (re)installed after it")

print("[WRONG] incompatible imgui, unknown zip, broken zip, no files")
with tempfile.TemporaryDirectory() as tmp:
    root = os.path.join(tmp, "repo"); os.makedirs(root)
    zs = zips_for(tmp, imgui=IMGUI_192)
    rc, out = quiet(inst.main, zs, root)
    check(rc == 1, "imgui 1.92 must be refused")
    for needle in ("1.92.0", "ImTextureID", "FallbackAdvanceX", "NavInputs", "ImeWindowHandle"):
        check(needle in out, "message should mention " + needle)
    bad = os.path.join(tmp, "random.zip"); make_zip(bad, "x/", {"hello.txt": "hi"})
    rc, out = quiet(inst.main, [bad], root); check(rc == 1 and "NON RICONOSCIUTO" in out, "unknown zip not reported")
    junk = os.path.join(tmp, "junk.zip"); open(junk, "wb").write(os.urandom(500))
    rc, out = quiet(inst.main, [junk], root); check(rc == 1 and "ERRORE" in out, "broken zip not reported")
    rc, out = quiet(inst.main, [], root); check(rc == 2, "no arguments should print usage")
    emptyroot = os.path.join(tmp, "empty"); os.makedirs(emptyroot)
    rc, out = quiet(cd.run, emptyroot, True); check(rc == 1 and "::error" in out and "sanitizers-cmake" in out and "github.com" in out, "checker should list what is missing with links and annotations")
    rc, out = quiet(cd.run, emptyroot, False, True); check(rc == 1 and out.count("https://github.com") == len(dc.load_manifest()), "--links should print one link per missing dependency")

print("[CONTENT] a zip with only a CMakeLists.txt is NOT taken for liblinkernsbypass")
with tempfile.TemporaryDirectory() as tmp:
    root = os.path.join(tmp, "repo"); os.makedirs(root)
    only = os.path.join(tmp, "only.zip"); make_zip(only, "something-main/", {"CMakeLists.txt": "project(other)"})
    rc, out = quiet(inst.main, [only], root); check(rc == 1 and "NON RICONOSCIUTO" in out, "unrelated CMake project wrongly accepted\n" + out)
    real = os.path.join(tmp, "lib.zip"); make_zip(real, "liblinkernsbypass-master/", {"CMakeLists.txt": "project(linkernsbypass)"})
    rc, out = quiet(inst.main, [real], root); check("installato:   liblinkernsbypass" in out, "real liblinkernsbypass not recognised\n" + out)
    check(os.path.isfile(os.path.join(root, "dependencies/libadrenotools/lib/linkernsbypass/CMakeLists.txt")), "not installed in lib/linkernsbypass")
    mn = dc.load_manifest(); e = [x for x in mn if x["name"] == "liblinkernsbypass"][0]
    open(os.path.join(root, e["path"], "CMakeLists.txt"), "w").write("project(other)")
    check(any("contenuto" in x for x in dc.missing_markers(e, root)), "wrong content of an existing folder not detected")

print("[AGGRESSIVE] zip-slip, symlinks, huge names")
with tempfile.TemporaryDirectory() as tmp:
    root = os.path.join(tmp, "repo"); os.makedirs(root)
    e = [x for x in dc.load_manifest() if x["name"] == "ZArchive"][0]
    files = {m: "" for m in e["markers"]}
    evil = os.path.join(tmp, "evil.zip"); make_zip(evil, "ZArchive-main/", files, extra={"ZArchive-main/../../outside.txt": "pwned"})
    rc, out = quiet(inst.main, [evil], root)
    check(rc == 1 and not os.path.exists(os.path.join(tmp, "outside.txt")) and not os.path.exists(os.path.join(root, "outside.txt")), "zip-slip entry was written outside the target\n" + out)
    sym = os.path.join(tmp, "sym.zip")
    with zipfile.ZipFile(sym, "w") as z:
        for m in e["markers"]: z.writestr("ZArchive-main/" + m, "")
        zi = zipfile.ZipInfo("ZArchive-main/link"); zi.external_attr = (stat.S_IFLNK | 0o777) << 16; z.writestr(zi, "/etc/passwd")
    rc, out = quiet(inst.main, [sym], os.path.join(tmp, "repo2")); 
    check(not os.path.lexists(os.path.join(tmp, "repo2/dependencies/ZArchive/link")), "symlink entry must be skipped")

print("[HARD] imgui symbol scan on Cemu's real sources")
import re
real_src = os.path.join(ROOT, "src")
words, io_names, font_names = set(), set(), set()
for p, t in cis.sources_using_imgui(real_src):
    if os.sep + "imgui_impl_" in p: continue
    words |= set(re.findall(r"\w+", t))
    for line in t.splitlines():
        line = line.split("//")[0]
        io_names |= set(cis.IOM.findall(line)); font_names |= set(cis.FONTM.findall(line))

def header(version, *, old_members):
    """A realistic imgui.h: every name Cemu uses is declared, members inside the right struct."""
    ioNames = io_names if old_members else io_names - {"NavInputs", "ImeWindowHandle"}
    s = '#define IMGUI_VERSION "%s"\n' % version
    s += "typedef void* ImTextureID;\n" if old_members else "typedef ImU64 ImTextureID;\n"
    used = words - {"NavInputs", "ImeWindowHandle", "FallbackAdvanceX"}
    if not old_members:
        used -= {"ImGuiNavInput_Cancel", "ImGuiNavInput_Input"}   # removed together with io.NavInputs
    s += " ".join(sorted(used)) + "\n"
    s += "struct ImGuiIO {\n" + ("float NavInputs[21]; void* ImeWindowHandle;\n" if old_members else "") + "void AddKeyEvent(int,bool);\n" + "".join("int %s;\n" % n for n in sorted(ioNames - {"NavInputs", "ImeWindowHandle", "AddKeyEvent"})) + "};\n"
    members = "".join("int %s;\n" % n for n in sorted(font_names - {"FallbackAdvanceX"}))
    s += "struct ImFont {\n" + ("float FallbackAdvanceX;\n" if old_members else "") + members + "};\n"
    if not old_members:
        s += "struct ImFontBaked { float FallbackAdvanceX; };   // the member moved here\n"
    return s

with tempfile.TemporaryDirectory() as tmp:
    good = os.path.join(tmp, "imgui"); os.makedirs(good)
    open(os.path.join(good, "imgui.h"), "w").write(header("1.89.9", old_members=True))
    m = cis.scan(real_src, good); check(not m, "a header that declares every used name must pass: %s" % list(m)[:6])
    bad = os.path.join(tmp, "imgui192"); os.makedirs(bad)
    open(os.path.join(bad, "imgui.h"), "w").write(header("1.92.0", old_members=False))
    m = cis.scan(real_src, bad)
    print("  with an imgui-1.92-like header the scanner reports:", ", ".join(sorted(m)))
    check("font->FallbackAdvanceX" in m and any("LatteShaderCache.cpp" in w for w in m["font->FallbackAdvanceX"]), "FallbackAdvanceX in LatteShaderCache.cpp not found (member exists only in ImFontBaked)")
    check("io.NavInputs" in m and any("swkbd.cpp" in w for w in m["io.NavInputs"]), "NavInputs usage in swkbd.cpp not found")
    check("io.ImeWindowHandle" in m, "ImeWindowHandle usage not found")
    funcs = set(re.findall(r"ImGui::(\w+)", "".join(t for _, t in cis.sources_using_imgui(real_src))))
    check(len(funcs) >= 30, "scanner finds too few ImGui functions: %d" % len(funcs))
    print("  scanner sees %d distinct ImGui:: functions, %d io members, %d font members in Cemu's sources" % (len(funcs), len(io_names), len(font_names)))
    check(dc.check_imgui_dir(good) == [], "compatible imgui flagged: %s" % dc.check_imgui_dir(good))
    check(len(dc.check_imgui_dir(bad)) >= 4, "incompatible imgui not flagged enough: %s" % dc.check_imgui_dir(bad))
    # a different but compatible version (all names exist) is a warning, not an error
    odd = os.path.join(tmp, "imgui1911"); os.makedirs(odd)
    open(os.path.join(odd, "imgui.h"), "w").write(header("1.91.1", old_members=True))
    check(dc.check_imgui_dir(odd) == [] and dc.imgui_version_warning(odd) is not None, "1.91.1 with all names present should only warn")
    # a header that only mentions FallbackAdvanceX in a comment or in another struct must not satisfy the check
    sneaky = os.path.join(tmp, "sneaky"); os.makedirs(sneaky)
    open(os.path.join(sneaky, "imgui.h"), "w").write('#define IMGUI_VERSION "1.89.9"\ntypedef void* ImTextureID;\n// FallbackAdvanceX\nstruct ImFont { int x; };\nstruct ImGuiIO { int y; };\n')
    check(any("FallbackAdvanceX" in p for p in dc.check_imgui_dir(sneaky)), "comment/other-struct mention wrongly accepted")

print("[CMAKE PATHS] paths mentioned by CMake must exist")
with tempfile.TemporaryDirectory() as tmp:
    os.makedirs(os.path.join(tmp, "src")); os.makedirs(os.path.join(tmp, "dependencies/imgui"))
    open(os.path.join(tmp, "CMakeLists.txt"), "w").write('add_subdirectory("dependencies/imgui")\n# add_subdirectory("dependencies/commented")\ninclude(dependencies/vcpkg/scripts/buildsystems/vcpkg.cmake)\nset(X ${CMAKE_CURRENT_LIST_DIR}/../../dependencies/imgui/imgui.cpp)\n')
    open(os.path.join(tmp, "src/CMakeLists.txt"), "w").write('target_sources(a PRIVATE ../dependencies/ZArchive/src/x.cpp)\n')
    rc, out = quiet(ccp.main, tmp)
    check(rc == 1 and "dependencies/vcpkg/scripts/buildsystems/vcpkg.cmake" in out and "dependencies/ZArchive/src/x.cpp" in out, "missing paths not reported\n" + out)
    check("commented" not in out and "dependencies/imgui/imgui.cpp" in out, "comments must be ignored; the imgui.cpp path is missing in this fake tree")
    os.makedirs(os.path.join(tmp, "dependencies/vcpkg/scripts/buildsystems")); open(os.path.join(tmp, "dependencies/vcpkg/scripts/buildsystems/vcpkg.cmake"), "w").write("")
    os.makedirs(os.path.join(tmp, "dependencies/ZArchive/src")); open(os.path.join(tmp, "dependencies/ZArchive/src/x.cpp"), "w").write("")
    open(os.path.join(tmp, "dependencies/imgui/imgui.cpp"), "w").write("")
    rc, out = quiet(ccp.main, tmp); check(rc == 0, "all paths exist but checker complains\n" + out)
real_rc, real_out = quiet(ccp.main, ROOT)
if os.path.isfile(os.path.join(ROOT, "dependencies/imgui/imgui.h")):   # only in a tree that already contains the dependencies
  lines = [l for l in real_out.splitlines() if l.strip().startswith("dependencies/")]
  check(len(lines) == 1 and "vcpkg.cmake" in lines[0], "on the real tree only vcpkg (cloned by CI) may be missing:\n" + real_out)   # every other dependency path exists (these dependencies were installed from the uploaded zips)

print("RESULT:", "ALL PASSED" if not fails else "%d FAILURE(S)" % fails)
sys.exit(1 if fails else 0)
