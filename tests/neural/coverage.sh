#!/usr/bin/env bash
# Line coverage of the neural code (union over all neural test programs). Usage: bash tests/neural/coverage.sh
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
W=$(mktemp -d); cd "$W"
python3 - "$R/src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp" "$W/hooks_extract.inc" << 'PY'
import sys
c=open(sys.argv[1]).read()
a=c.index("#if CEMU_NEURAL\nnamespace\n{\n\t// Engine side"); b=c.index("#endif\n", c.index("\t};\n}\n", a))
open(sys.argv[2],"w").write(c[a:b+7])
PY
mkdir -p a b c
FL="-std=c++20 -O0 --coverage -I$R/src -I$R/tests/neural -I$W"
(cd a && g++ $FL $R/tests/neural/neural_test.cpp -o t -pthread && ./t >/dev/null)
(cd b && g++ $FL $R/tests/neural/math_test.cpp -o t && ./t >/dev/null)
(cd c && g++ $FL $R/tests/neural/engine_hooks_test.cpp $R/src/Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.cpp -o t && ./t >/dev/null)
python3 - "$W" "$R" << 'PY'
import sys, subprocess, os, re, glob
W, R = sys.argv[1], sys.argv[2]
want = ["NeuralMLP.h","NeuralGuard.h","NeuroJitFeatures.h","NeuroJitAdvisor.h","NeuroJitScheduler.h","NeuroJitSession.h"]
cov = {w: {} for w in want}
for d in "abc":
    subprocess.run(["gcov","-r","-s",R+"/src","-o",".","t-"+("neural_test" if d=="a" else "math_test" if d=="b" else "engine_hooks_test")+".gcda"],cwd=f"{W}/{d}",capture_output=True)
    for g in glob.glob(f"{W}/{d}/*.gcov"):
        name=os.path.basename(g)
        for w in want:
            if name.startswith(w+".gcov"):
                for ln in open(g, errors="ignore"):
                    m = re.match(r"\s*([0-9#=\-]+\*?):\s*(\d+):", ln)
                    if not m or m.group(1) == "-": continue
                    c, n = m.group(1), int(m.group(2))
                    cur = cov[w].get(n)
                    hit = (c not in ("#####", "=====")) 
                    cov[w][n] = bool(cur) or hit
tot = hit = 0
for w in want:
    lines = cov[w]; h = sum(1 for v in lines.values() if v); t = len(lines)
    tot += t; hit += h
    miss = [n for n, v in sorted(lines.items()) if not v]
    print(f"{w:22s} {100.0*h/max(t,1):5.1f}%  ({h}/{t} lines)   uncovered: {miss[:25]}{' ...' if len(miss)>25 else ''}")
print(f"{'TOTAL':22s} {100.0*hit/max(tot,1):5.1f}%  ({hit}/{tot} lines)")
PY
