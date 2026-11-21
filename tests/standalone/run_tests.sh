#!/usr/bin/env bash
# Standalone tests - need only g++ (C++20). Run from the repository root:  bash tests/standalone/run_tests.sh
set -e
SRC=src
T=tests/standalone
OUT=$(mktemp -d)
A="$SRC/Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.cpp"
echo "== build (ASan+UBSan) =="; g++ -std=c++20 -O1 -g -Wall -Wextra -fsanitize=address,undefined -fno-sanitize-recover=all -I$SRC $T/analyzer_test.cpp $A -o $OUT/t_asan -pthread
echo "== run ASan+UBSan =="; $OUT/t_asan
echo "== build (TSan) =="; g++ -std=c++20 -O1 -g -fsanitize=thread -I$SRC $T/analyzer_test.cpp $A -o $OUT/t_tsan -pthread
echo "== run TSan =="; $OUT/t_tsan
echo "== build (-O2 plain) =="; g++ -std=c++20 -O2 -I$SRC $T/analyzer_test.cpp $A -o $OUT/t_o2 -pthread
echo "== run -O2 =="; $OUT/t_o2

echo "== low guest memory fix (real code extracted from coreinit_MEM.cpp) =="
python3 - "$SRC/Cafe/OS/libs/coreinit/coreinit_MEM.cpp" "$OUT/lowmem_extract.inc" << 'PY'
import sys
c=open(sys.argv[1]).read()
a=c.index("#if defined(__linux__) || defined(__ANDROID__)\n#include <sys/mman.h>")
b=c.index("#else\nstatic void CemuFixer_EnsureLowGuestMemory() {}")
open(sys.argv[2],"w").write(c[a:b]+"#endif\n")
PY
g++ -std=c++20 -O1 -g -Wall -fsanitize=address,undefined -fno-sanitize-recover=all -I$OUT -I$T $T/lowmem_test.cpp -o $OUT/l_asan -pthread && $OUT/l_asan
g++ -std=c++20 -O1 -g -fsanitize=thread -I$OUT -I$T $T/lowmem_test.cpp -o $OUT/l_tsan -pthread && $OUT/l_tsan
echo "== JIT queue drain model (virtual time) =="
g++ -std=c++20 -O2 -Wall -Wextra $T/jit_queue_model.cpp -o $OUT/jitmodel && $OUT/jitmodel
echo "== neural: extract the real engine glue code from PPCRecompiler.cpp =="
python3 - "$SRC/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp" "$OUT/hooks_extract.inc" << 'PY'
import sys
c=open(sys.argv[1]).read()
a=c.index("#if CEMU_NEURAL\nnamespace\n{\n\t// Engine side")
b=c.index("#endif\n", c.index("\t};\n}\n", a))
open(sys.argv[2],"w").write(c[a:b+7])
PY
N=tests/neural
NSRC="$N/neural_test.cpp"
echo "== neural suite (ASan+UBSan) =="; g++ -std=c++20 -O1 -g -Wall -Wextra -fsanitize=address,undefined -fno-sanitize-recover=all -I$SRC -I$N $NSRC -o $OUT/n_asan -pthread && $OUT/n_asan
echo "== neural suite (TSan) =="; g++ -std=c++20 -O1 -g -fsanitize=thread -I$SRC -I$N $NSRC -o $OUT/n_tsan -pthread && $OUT/n_tsan
echo "== neural suite (-O2) =="; g++ -std=c++20 -O2 -Wall -Wextra -I$SRC -I$N $NSRC -o $OUT/n_o2 -pthread && $OUT/n_o2
echo "== neural: engine glue code vs stub engine (ASan+UBSan) =="; g++ -std=c++20 -O1 -g -Wall -Wextra -fsanitize=address,undefined -fno-sanitize-recover=all -I$SRC -I$N -I$OUT $N/engine_hooks_test.cpp $A -o $OUT/h_asan && $OUT/h_asan
echo "== neural: MLP math (gradient check) =="; g++ -std=c++20 -O2 -Wall -Wextra -I$SRC $N/math_test.cpp -o $OUT/math_test && $OUT/math_test
if python3 -c "import numpy" 2>/dev/null; then echo "== neural: independent numpy reference =="; $OUT/math_test --dump | python3 $N/numpy_reference.py; else echo "== numpy not installed: reference comparison skipped =="; fi
echo "== neural: collect -> train -> deploy loop =="; bash $N/retrain_loop.sh
echo "== neural: trainer builds =="; g++ -std=c++20 -O2 -Wall -Wextra -I$SRC -I$N tools/neural/train_priors.cpp -o $OUT/train_priors
echo "== dependency tools (installer, checker, imgui name scan) =="; python3 tests/deps/test_deps_tools.py
echo "== preflight checks and CI shell steps =="; python3 tests/preflight/test_preflight.py
echo "== preflight on this tree =="; python3 tools/preflight/preflight.py
echo "== ALL SUITES PASSED (exit code 0, no sanitizer reports) =="
