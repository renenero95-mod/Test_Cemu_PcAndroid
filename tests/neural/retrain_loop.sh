#!/usr/bin/env bash
# End-to-end test of the data loop: trace file written like on the phone -> offline training -> new default weights -> better held-out ordering.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd); W=$(mktemp -d); H=Cafe/HW/Espresso/Recompiler/Neural
FL="-std=c++20 -O2 -I$R/src -I$R/tests/neural"
g++ $FL $R/tests/neural/trace_gen.cpp -o $W/trace_gen && $W/trace_gen $W/data 12000
echo "trace: $(wc -l < $W/data/jit_cost_trace.csv) lines, $(head -c 200 $W/data/jit_cost_trace.csv | head -2 | tail -1)"
g++ $FL $R/tools/neural/train_priors.cpp -o $W/train && mkdir -p $W/new/$H && cp $R/src/$H/*.h $W/new/$H/ && $W/train $W/new/$H/NeuroJitDefaultWeights.h $W/data/jit_cost_trace.csv | grep -E "loaded|final MAE"
g++ $FL $R/tests/neural/eval_weights.cpp -o $W/eval_old
g++ -std=c++20 -O2 -I$W/new -I$R/src -I$R/tests/neural $R/tests/neural/eval_weights.cpp -o $W/eval_new
OLD=$($W/eval_old); NEW=$($W/eval_new); echo "built-in priors : $OLD"; echo "trained on trace: $NEW"
python3 - "$OLD" "$NEW" << 'PY'
import sys
o=[float(x) for x in sys.argv[1].split() if x.replace('.','',1).isdigit()]; n=[float(x) for x in sys.argv[2].split() if x.replace('.','',1).isdigit()]
assert n[0] < 0.5*o[0], f"error did not improve enough: {o[0]} -> {n[0]}"
assert n[1] > o[1] + 0.03, f"ordering did not improve: {o[1]} -> {n[1]}"
print("RETRAIN LOOP: OK (held-out error %.3f -> %.3f, ordering %.1f%% -> %.1f%%)" % (o[0], n[0], 100*o[1], 100*n[1]))
PY
