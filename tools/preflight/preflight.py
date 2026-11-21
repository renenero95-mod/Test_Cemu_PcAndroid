#!/usr/bin/env python3
"""Pre-flight checks that predict, in seconds, the build failures that would otherwise appear after an hour of GitHub Actions.
Everything here is a STATIC check: it cannot replace the real compilation, but it catches the classes of failure we have actually seen
(missing/incompatible dependencies, missing files, inconsistent SDK/NDK/ABI) and several more.
  python3 tools/preflight/preflight.py [--ci] [--root DIR]      exit code 0 = no blocking problem
Checks: dependencies, CMake paths, CMake source files, #include files, Gradle/NDK/JDK/ABI consistency, vcpkg manifest, workflow, engine edits."""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT0 = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT0, "tools", "deps"))
sys.path.insert(0, HERE)

OK, WARN, FAIL = "OK", "ATTENZIONE", "ERRORE"


def read(p):
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


# ---- individual checks: each returns a list of (level, message) ------------------------------------------------------------
def check_deps(root, ci):
    import check_dependencies as cd, deps_common as dc
    out = []
    manifest = dc.load_manifest()
    for e in manifest:
        miss = dc.missing_markers(e, root)
        if miss:
            lvl = WARN if e.get("ci_clone") and not ci else FAIL
            out.append((lvl, "dipendenza %s: mancano %s%s" % (e["name"], ", ".join(miss), " (la CI la clona)" if e.get("ci_clone") else "")))
        elif e["name"] == "imgui":
            for p in dc.check_imgui_dir(os.path.join(root, e["path"])):
                out.append((FAIL, "imgui: " + p))
    if not out:
        out.append((OK, "dipendenze presenti e imgui compatibile (%d controllate)" % len(manifest)))
    return out


def check_cmake_paths(root, ci):
    import check_cmake_paths as ccp
    refs = ccp.referenced_paths(root)
    miss = [r for r in sorted(refs) if not os.path.exists(os.path.join(root, r))]
    out = []
    for r in miss:
        cloned = r.startswith("dependencies/vcpkg/") and not ci
        out.append((WARN if cloned else FAIL, "CMake cerca %s che non esiste%s" % (r, " (lo clona la CI)" if cloned else "")))
    return out or [(OK, "tutti i percorsi dependencies/ citati da CMake esistono (%d)" % len(refs))]


def check_cmake_sources(root, ci):
    import check_cmake_sources as ccs
    total, missing = ccs.check(root)
    if missing:
        return [(FAIL, "file nominato in CMake ma inesistente: %s:%d %s" % m) for m in missing[:30]]
    return [(OK, "tutti i file nominati nei CMakeLists esistono (%d)" % total)]


THIRD_PARTY_PREFIX = ("curl/", "openssl/", "boost/", "pugixml", "zlib", "libusb", "platform/", "TargetConditionals", "zstd", "rapidjson/", "fmt/",
                      "glslang/", "SPIRV/", "libzip", "zip.h", "hidapi", "SDL", "wx/", "gamemode", "stb_", "cubeb/", "zarchive/", "xbyak", "adrenotools/",
                      "vulkan/", "vk_mem_alloc", "pugiconfig", "tinyxml", "png.h", "ih264d", "ithread", "iv.h", "ivd.h", "imgui")


def check_includes(root, ci):
    src = os.path.join(root, "src")
    roots = [src, os.path.join(root, "dependencies/imgui"), os.path.join(root, "dependencies/imgui/misc/cpp"), os.path.join(root, "dependencies/Vulkan-Headers/include"),
             os.path.join(root, "dependencies/ZArchive/include"), os.path.join(root, "dependencies/cubeb/include"), os.path.join(root, "dependencies/xbyak_aarch64"),
             os.path.join(root, "dependencies/libadrenotools/include"), os.path.join(root, "dependencies/stb"), os.path.join(root, "dependencies/ih264d"),
             os.path.join(root, "dependencies/ih264d/common"), os.path.join(root, "dependencies/ih264d/decoder"),
             os.path.join(root, "src/gui/interface"), os.path.join(root, "src/imgui"), os.path.join(root, "src/Common")]
    # CMake adds every first-level folder of src/ (Cafe, Cemu, util, config, ...) as an include root
    roots += [os.path.join(src, d) for d in sorted(os.listdir(src)) if os.path.isdir(os.path.join(src, d))]
    inc = re.compile(r'^\s*#\s*include\s+"([^"]+)"', re.M)
    miss = {}
    skip = ("/gui/", "/resource/", "/Metal", "/OpenGL", "/windows", "/win32", "/mac", "/android/app/src/main/java")
    scanned = 0
    for dp, dn, fn in os.walk(src):
        d = dp.replace("\\", "/") + "/"
        if any(s in d for s in skip):
            continue
        for f in fn:
            if not f.endswith((".cpp", ".h", ".hpp", ".c", ".inl")):
                continue
            scanned += 1
            for m in inc.finditer(read(os.path.join(dp, f))):
                h = m.group(1)
                if h.startswith(THIRD_PARTY_PREFIX) or os.path.exists(os.path.join(dp, h)) or any(os.path.exists(os.path.join(r, h)) for r in roots):
                    continue
                miss.setdefault(h, os.path.relpath(os.path.join(dp, f), root))
    if miss:
        return [(FAIL, "#include \"%s\" non trovato (in %s)" % (h, w)) for h, w in sorted(miss.items())[:30]]
    return [(OK, "tutti i #include \"...\" del progetto si risolvono (%d file letti)" % scanned)]


def check_android(root, ci):
    out = []
    g = read(os.path.join(root, "src/android/app/build.gradle.kts"))
    wf = read(os.path.join(root, ".github/workflows/build_apk.yml"))
    gradle_ndk = re.search(r'ndkVersion\s*=\s*"([\d.]+)"', g)
    gradle_sdk = re.search(r'compileSdk\s*=\s*(\d+)', g)
    wf_ndk = re.search(r'ndk;([\d.]+)', wf)
    wf_sdk = re.search(r'platforms;android-(\d+)', wf)
    if not gradle_ndk or not wf_ndk:
        out.append((FAIL, "NDK non trovato in build.gradle.kts o nella workflow"))
    elif gradle_ndk.group(1) != wf_ndk.group(1):
        out.append((FAIL, "NDK diverso: Gradle vuole %s, la workflow installa %s" % (gradle_ndk.group(1), wf_ndk.group(1))))
    else:
        out.append((OK, "NDK coerente (%s)" % wf_ndk.group(1)))
    if re.search(r'ANDROID_NDK_HOME=.*ndk/([\d.]+)', wf) and wf_ndk and re.search(r'ANDROID_NDK_HOME=.*ndk/([\d.]+)', wf).group(1) != wf_ndk.group(1):
        out.append((FAIL, "ANDROID_NDK_HOME punta a un NDK diverso da quello installato (vcpkg lo usa)"))
    if gradle_sdk and wf_sdk and gradle_sdk.group(1) != wf_sdk.group(1):
        out.append((FAIL, "compileSdk %s ma la workflow installa platforms;android-%s" % (gradle_sdk.group(1), wf_sdk.group(1))))
    elif gradle_sdk and wf_sdk:
        out.append((OK, "compileSdk coerente (%s)" % wf_sdk.group(1)))
    jv = re.search(r"java-version:\s*'?(\d+)", wf)
    if not jv or int(jv.group(1)) < 17:
        out.append((FAIL, "JDK della workflow < 17 (AGP 8/9 richiede almeno 17)"))
    else:
        out.append((OK, "JDK %s" % jv.group(1)))
    active = [l for l in g.splitlines() if "abiFilters" in l and not l.strip().startswith("//")]
    abis = set(re.findall(r'"([\w\-]+)"', " ".join(active)))
    if abis != {"arm64-v8a"}:
        out.append((FAIL, "ABI attive %s: libadrenotools accetta solo arm64-v8a (CMake fatale con altre)" % (sorted(abis) or "nessun filtro: Gradle compila tutte")))
    else:
        out.append((OK, "ABI solo arm64-v8a"))
    cm = re.search(r'path\s*=\s*file\("([^"]+)"\)', g)
    if cm and not os.path.isfile(os.path.normpath(os.path.join(root, "src/android/app", cm.group(1)))):
        out.append((FAIL, "Gradle punta a un CMakeLists.txt che non esiste: " + cm.group(1)))
    wrapper = read(os.path.join(root, "src/android/gradle/wrapper/gradle-wrapper.properties"))
    gv = re.search(r'gradle-([\d.]+)-', wrapper)
    agp = re.search(r'^agp\s*=\s*"([\d.]+)"', read(os.path.join(root, "src/android/gradle/libs.versions.toml")), re.M)
    if gv and agp and int(agp.group(1).split(".")[0]) >= 9 and int(gv.group(1).split(".")[0]) < 9:
        out.append((FAIL, "AGP %s richiede Gradle 9+, il wrapper usa %s" % (agp.group(1), gv.group(1))))
    elif gv and agp:
        out.append((OK, "Gradle %s con AGP %s" % (gv.group(1), agp.group(1))))
    for need in ("src/android/gradlew", "src/android/gradle/wrapper/gradle-wrapper.jar"):
        if not os.path.isfile(os.path.join(root, need)):
            out.append((FAIL, "manca " + need))
    # properties the workflow passes must be read by build.gradle.kts, or the option silently does nothing
    for prop in re.findall(r'-P(\w+)=', wf):
        if prop not in g:
            out.append((FAIL, "la workflow passa -P%s ma build.gradle.kts non lo legge" % prop))
    return out


def check_vcpkg(root, ci):
    out = []
    try:
        d = json.loads(read(os.path.join(root, "vcpkg.json")))
    except ValueError as e:
        return [(FAIL, "vcpkg.json non è JSON valido: %s" % e)]
    if not re.fullmatch(r"[0-9a-f]{40}", d.get("builtin-baseline", "")):
        out.append((FAIL, "vcpkg.json: builtin-baseline mancante o non è un commit di 40 caratteri"))
    names = [x if isinstance(x, str) else x.get("name") for x in d.get("dependencies", [])]
    dup = sorted({n for n in names if names.count(n) > 1 and n})
    if dup:
        out.append((WARN, "vcpkg.json: dipendenze elencate più volte (%s); vcpkg le unisce, ma controlla" % ", ".join(dup)))
    bad = []
    for ovl in sorted(os.listdir(os.path.join(root, "dependencies"))) if os.path.isdir(os.path.join(root, "dependencies")) else []:
        if ovl.startswith("vcpkg_overlay_ports"):
            base = os.path.join(root, "dependencies", ovl)
            for port in os.listdir(base):
                pd = os.path.join(base, port)
                if os.path.isdir(pd) and not (os.path.isfile(os.path.join(pd, "portfile.cmake")) and os.path.isfile(os.path.join(pd, "vcpkg.json"))):
                    bad.append("%s/%s" % (ovl, port))
    if bad:
        out.append((FAIL, "porte overlay incomplete (servono portfile.cmake e vcpkg.json): " + ", ".join(bad)))
    return out or [(OK, "vcpkg.json valido, baseline presente, porte overlay complete")]


def check_workflow(root, ci):
    out = []
    try:
        import yaml
        y = yaml.safe_load(read(os.path.join(root, ".github/workflows/build_apk.yml")))
    except Exception as e:
        return [(FAIL, "workflow non valida: %s" % e)]
    steps = y["jobs"]["build"]["steps"]
    for s in steps:
        for m in re.finditer(r'python3\s+(tools/[\w/]+\.py)', s.get("run", "")):
            if not os.path.isfile(os.path.join(root, m.group(1))):
                out.append((FAIL, "la workflow lancia %s che non esiste" % m.group(1)))
    names = [s.get("name", "") for s in steps]
    order = {k: next((i for i, n in enumerate(names) if k in n), -1) for k in ("Fetch vcpkg", "Verify dependencies", "Build APK")}
    if not (0 <= order["Fetch vcpkg"] < order["Verify dependencies"] < order["Build APK"]):
        out.append((FAIL, "ordine dei passi sbagliato (vcpkg, verifica, build): %s" % order))
    up = [s for s in steps if "upload-artifact" in str(s.get("uses", ""))]
    if not up:
        out.append((FAIL, "nessun passo di upload dell'APK"))
    return out or [(OK, "workflow valida, passi nell'ordine giusto, script esistenti")]


# curated: (file, symbol that the edit introduced, header text that must be reachable in the same file)
ENGINE_EDITS = [
    ("src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp", "ActiveSettings::", 'config/ActiveSettings.h'),
    ("src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp", "PPCREC_TRACE", 'Neural/NeuroJitSession.h|PPCRecompilerTrace.h'),
    ("src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp", "PPCPreExecutionAnalyzer::", 'Analyzer/PPCPreExecutionAnalyzer.h'),
    ("src/Cafe/HW/Espresso/Recompiler/IML/IMLRegisterAllocator.cpp", "PPCREC_TRACE", 'PPCRecompilerTrace.h'),
    ("src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp", "CemuWatchdog::", 'CemuThreadWatchdog.h'),
    ("src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp", "CemuDiagV2::", 'CemuDiagV2.h'),
    ("src/Cafe/OS/RPL/elf.cpp", "PPCPreExecutionAnalyzer::", 'PPCPreExecutionAnalyzer.h'),
    ("src/Cafe/OS/RPL/rpl.cpp", "PPCPreExecutionAnalyzer::", 'PPCPreExecutionAnalyzer.h'),
]


def check_engine_edits(root, ci):
    out = []
    for f, sym, hdr in ENGINE_EDITS:
        t = read(os.path.join(root, f))
        if sym in t and not any(h in t for h in hdr.split("|")):
            out.append((FAIL, "%s usa %s ma non include %s" % (f, sym, hdr)))
    # the old analyzer API must not be used anywhere (fields removed in the rewrite)
    for dp, dn, fn in os.walk(os.path.join(root, "src")):
        for f in fn:
            if f.endswith((".cpp", ".h")):
                t = read(os.path.join(dp, f))
                if re.search(r'InstructionMetadata[^;]*;[^}]*\.(rawOpcode|primaryOpcode|secondaryOpcode)', t) and "Analyzer/" not in dp:
                    out.append((FAIL, "%s usa campi rimossi di InstructionMetadata" % os.path.relpath(os.path.join(dp, f), root)))
    # source files that we added or rewrote must actually be part of a CMake target, or the linker fails with 'undefined reference'
    cmake_text = "".join(read(os.path.join(dp, "CMakeLists.txt")) for dp, dn, fn in os.walk(os.path.join(root, "src")) if "CMakeLists.txt" in fn)
    for cpp in ("PPCPreExecutionAnalyzer.cpp",):
        if cpp not in cmake_text:
            out.append((FAIL, "%s non è elencato in nessun CMakeLists.txt (errore di link: undefined reference)" % cpp))
    for hdr in os.listdir(os.path.join(root, "src/Cafe/HW/Espresso/Recompiler/Neural")) if os.path.isdir(os.path.join(root, "src/Cafe/HW/Espresso/Recompiler/Neural")) else []:
        if hdr.endswith(".cpp"):
            out.append((WARN, "Neural/%s è un .cpp: i moduli neurali devono restare solo header (non sono in CMake)" % hdr))
    return out or [(OK, "include richiesti dalle modifiche al motore presenti (%d controlli)" % len(ENGINE_EDITS))]


CHECKS = [("Dipendenze", check_deps), ("Percorsi CMake", check_cmake_paths), ("File dei CMakeLists", check_cmake_sources), ("#include", check_includes),
          ("Android/Gradle/NDK", check_android), ("vcpkg", check_vcpkg), ("Workflow", check_workflow), ("Modifiche al motore", check_engine_edits)]


def run(root=ROOT0, ci=False, out=sys.stdout):
    blocking = 0
    for name, fn in CHECKS:
        try:
            results = fn(root, ci)
        except Exception as e:  # a bug in a check must never look like a pass
            results = [(FAIL, "il controllo è andato in errore: %r" % (e,))]
        for lvl, msg in results:
            print("[%-10s] %-20s %s" % (lvl, name, msg), file=out)
            if lvl == FAIL:
                blocking += 1
                if ci:
                    print("::error title=Preflight %s::%s" % (name, msg), file=out)
            elif lvl == WARN and ci:
                print("::warning title=Preflight %s::%s" % (name, msg), file=out)
    print("\nPREFLIGHT: %s" % ("nessun problema bloccante" if not blocking else "%d PROBLEMI BLOCCANTI" % blocking), file=out)
    return 1 if blocking else 0


if __name__ == "__main__":
    root = ROOT0
    if "--root" in sys.argv:
        root = os.path.abspath(sys.argv[sys.argv.index("--root") + 1])
    sys.exit(run(root, "--ci" in sys.argv))
