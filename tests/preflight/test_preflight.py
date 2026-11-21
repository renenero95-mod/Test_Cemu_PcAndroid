#!/usr/bin/env python3
"""Tests of the preflight checks: each check must FAIL on a deliberately broken tree and pass on a good one, and the CI shell steps
must work end to end (with a fake 'git' so that no network is needed)."""
import io, os, shutil, subprocess, sys, tempfile, contextlib, textwrap
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "preflight")); sys.path.insert(0, os.path.join(ROOT, "tools", "deps"))
import preflight as pf

fails = 0
def check(c, msg):
    global fails
    if not c:
        print("  FAIL:", msg); fails += 1

def w(root, rel, text=""):
    p = os.path.join(root, rel); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "w").write(text)

GRADLE = 'android { compileSdk = 36\n ndkVersion = "29.0.1"\n defaultConfig { ndk {\n // abiFilters("arm64-v8a", "x86_64")\n abiFilters("arm64-v8a")\n } }\n externalNativeBuild { cmake { path = file("../../../CMakeLists.txt") } } }\n'
WF = """name: t
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Fetch vcpkg
        run: echo 1
      - name: Verify dependencies
        run: python3 tools/deps/check_dependencies.py --ci
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          java-version: '21'
      - name: Install
        run: |
          sdkmanager --install "platforms;android-36" "ndk;29.0.1"
          echo "ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/29.0.1" >> "$GITHUB_ENV"
      - name: Build APK
        run: ./gradlew :app:assembleRelease -PcemuNeural=${{ inputs.neural }}
      - name: Upload APK
        uses: actions/upload-artifact@v4
"""

def good_tree(root):
    w(root, "src/android/app/build.gradle.kts", GRADLE + "// cemuNeural\n")
    w(root, ".github/workflows/build_apk.yml", WF)
    w(root, "CMakeLists.txt", "cmake_minimum_required(VERSION 3.25)\n")
    w(root, "src/android/gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n")
    w(root, "src/android/gradle/wrapper/gradle-wrapper.jar", "x"); w(root, "src/android/gradlew", "#!/bin/sh\n")
    w(root, "src/android/gradle/libs.versions.toml", '[versions]\nagp = "9.1.1"\n')
    w(root, "vcpkg.json", '{"builtin-baseline": "%s", "dependencies": ["a", "b"]}' % ("a" * 40))
    w(root, "dependencies/vcpkg_overlay_ports/p/portfile.cmake"); w(root, "dependencies/vcpkg_overlay_ports/p/vcpkg.json", "{}")
    w(root, "src/Cafe/Foo.h", "#pragma once\n"); w(root, "src/Cafe/Foo.cpp", '#include "Foo.h"\n#include "Bar/Baz.h"\n#include <boost/x.hpp>\n#include "curl/curl.h"\n'); w(root, "src/Cafe/Bar/Baz.h")
    w(root, "src/CMakeLists.txt", "add_library(x Cafe/Foo.cpp Cafe/Foo.h)\n")
    w(root, "tools/deps/check_dependencies.py")

def run_check(fn, root, ci=False):
    return fn(root, ci)

def levels(res): return {l for l, _ in res}

print("[SIMPLE] a consistent tree passes every check")
with tempfile.TemporaryDirectory() as tmp:
    good_tree(tmp)
    for name, fn in (("android", pf.check_android), ("vcpkg", pf.check_vcpkg), ("workflow", pf.check_workflow), ("includes", pf.check_includes), ("cmake sources", pf.check_cmake_sources)):
        r = run_check(fn, tmp); check(levels(r) <= {pf.OK, pf.WARN}, "%s flagged a good tree: %s" % (name, r))

print("[WRONG] each deliberately broken tree is caught")
def broken(mutate, fn, needle, label):
    with tempfile.TemporaryDirectory() as tmp:
        good_tree(tmp); mutate(tmp)
        r = run_check(fn, tmp); hit = any(l == pf.FAIL and needle in m for l, m in r)
        check(hit, "%s not detected (looking for %r): %s" % (label, needle, r))
broken(lambda t: w(t, "src/android/app/build.gradle.kts", GRADLE.replace("29.0.1", "28.0.0")), pf.check_android, "NDK diverso", "NDK mismatch")
broken(lambda t: w(t, "src/android/app/build.gradle.kts", GRADLE.replace('abiFilters("arm64-v8a")', 'abiFilters("arm64-v8a", "x86_64")')), pf.check_android, "ABI attive", "x86_64 ABI active")
broken(lambda t: w(t, "src/android/app/build.gradle.kts", GRADLE.replace('abiFilters("arm64-v8a")', "")), pf.check_android, "nessun filtro", "no ABI filter at all (all ABIs built)")
broken(lambda t: w(t, "src/android/app/build.gradle.kts", GRADLE.replace("compileSdk = 36", "compileSdk = 35")), pf.check_android, "compileSdk", "compileSdk mismatch")
broken(lambda t: w(t, ".github/workflows/build_apk.yml", WF.replace("'21'", "'11'")), pf.check_android, "JDK", "old JDK")
broken(lambda t: w(t, ".github/workflows/build_apk.yml", WF.replace("ndk/29.0.1", "ndk/28.0.0")), pf.check_android, "ANDROID_NDK_HOME", "wrong ANDROID_NDK_HOME")
broken(lambda t: w(t, "src/android/gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x/gradle-8.14-bin.zip\n"), pf.check_android, "AGP", "AGP 9 with Gradle 8")
broken(lambda t: os.remove(os.path.join(t, "src/android/gradlew")), pf.check_android, "gradlew", "missing gradlew")
broken(lambda t: w(t, ".github/workflows/build_apk.yml", WF + "      - run: x\n".replace("x", "x") if False else WF.replace("-PcemuNeural", "-PsomethingElse")), pf.check_android, "somethingElse", "workflow passes a property Gradle never reads")
broken(lambda t: w(t, "vcpkg.json", '{"builtin-baseline": "xyz", "dependencies": []}'), pf.check_vcpkg, "baseline", "bad vcpkg baseline")
broken(lambda t: w(t, "vcpkg.json", "{not json"), pf.check_vcpkg, "JSON", "broken vcpkg.json")
broken(lambda t: os.remove(os.path.join(t, "dependencies/vcpkg_overlay_ports/p/portfile.cmake")), pf.check_vcpkg, "overlay", "overlay port without portfile")
broken(lambda t: w(t, "src/Cafe/Foo.cpp", '#include "Missing/Thing.h"\n'), pf.check_includes, "Missing/Thing.h", "missing project include")
broken(lambda t: os.remove(os.path.join(t, "src/Cafe/Foo.cpp")), pf.check_cmake_sources, "Foo.cpp", "source file named in CMake is missing")
broken(lambda t: w(t, ".github/workflows/build_apk.yml", WF.replace("tools/deps/check_dependencies.py", "tools/deps/nope.py")), pf.check_workflow, "nope.py", "workflow runs a missing script")
broken(lambda t: w(t, ".github/workflows/build_apk.yml", WF.replace("Fetch vcpkg", "Zz").replace("Verify dependencies", "Fetch vcpkg")), pf.check_workflow, "ordine", "steps in the wrong order")
broken(lambda t: w(t, "src/Cafe/CMakeLists.txt", "add_library(x a.cpp)\n"), pf.check_engine_edits, "PPCPreExecutionAnalyzer.cpp", "analyzer .cpp not listed in CMake")
broken(lambda t: (w(t, "src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp", "void f(){ ActiveSettings::GetUserDataPath(); }\n")), pf.check_engine_edits, "ActiveSettings", "edit uses a symbol whose header is not included")

print("[CI SHELL STEPS] the real steps of the workflow, run offline with a fake git")
import yaml
with tempfile.TemporaryDirectory() as tmp:
    proj = os.path.join(tmp, "proj"); os.makedirs(proj + "/dependencies")
    for item in ("src", "CMakeLists.txt", "vcpkg.json", "cmake", "tools", ".github"):
        if os.path.exists(os.path.join(ROOT, item)): os.symlink(os.path.join(ROOT, item), os.path.join(proj, item))
    for d in sorted(os.listdir(os.path.join(ROOT, "dependencies"))):
        s = os.path.join(ROOT, "dependencies", d)
        if d == "vcpkg": continue                                   # the CI clones it
        if d == "libadrenotools": shutil.copytree(s, os.path.join(proj, "dependencies", d))   # the step below deletes/recreates a folder inside it
        else: os.symlink(s, os.path.join(proj, "dependencies", d))
    shutil.rmtree(os.path.join(proj, "dependencies/libadrenotools/lib/linkernsbypass"))        # as if liblinkernsbypass was not provided
    shim = os.path.join(tmp, "bin"); os.makedirs(shim)
    open(os.path.join(shim, "git"), "w").write(textwrap.dedent("""#!/bin/bash
        # fake git: 'git clone [--depth 1] URL DIR' creates a plausible checkout, anything else is a no-op
        if [ "$1" = clone ]; then
          dir="${@: -1}"; url="${@: -2:1}"; mkdir -p "$dir"
          case "$url" in
            *vcpkg*) mkdir -p "$dir/scripts/buildsystems"; echo "# fake" > "$dir/scripts/buildsystems/vcpkg.cmake" ;;
            *liblinkernsbypass*) echo "project(linkernsbypass)" > "$dir/CMakeLists.txt" ;;
          esac
          echo "$url" >> "%s/git.log"
        fi
        exit 0
        """) % tmp)
    os.chmod(os.path.join(shim, "git"), 0o755)
    steps = yaml.safe_load(open(os.path.join(ROOT, ".github/workflows/build_apk.yml")))["jobs"]["build"]["steps"]
    env = dict(os.environ, PATH=shim + ":" + os.environ["PATH"])
    ran = []
    for s in steps:
        n = s.get("name", "")
        if any(k in n for k in ("Fetch vcpkg", "Fetch liblinkernsbypass", "Verify dependencies", "Preflight")):
            r = subprocess.run(["bash", "-e", "-c", s["run"]], cwd=proj, env=env, capture_output=True, text=True)
            ran.append(n[:24])
            check(r.returncode == 0, "step '%s' failed (rc %d):\\n%s%s" % (n, r.returncode, r.stdout[-600:], r.stderr[-600:]))
    log = open(os.path.join(tmp, "git.log")).read() if os.path.exists(os.path.join(tmp, "git.log")) else ""
    check("microsoft/vcpkg" in log and "bylaws/liblinkernsbypass" in log, "the two clones did not happen: %r" % log)
    print("  steps run offline:", ", ".join(ran), "| clones:", log.replace("\\n", " ").strip())
    check(len(ran) >= 3, "expected at least 3 shell steps, ran %s" % ran)

print("RESULT:", "ALL PASSED" if not fails else "%d FAILURE(S)" % fails)
sys.exit(1 if fails else 0)
