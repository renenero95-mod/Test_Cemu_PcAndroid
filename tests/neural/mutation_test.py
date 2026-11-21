#!/usr/bin/env python3
"""Mutation testing of the neural code: inject one deliberate bug at a time and check that the tests notice.
A mutant that survives means a behaviour nobody tests (or an equivalent mutation, which is then listed with a reason).
Usage: python3 tests/neural/mutation_test.py [first] [last]       (runs mutants first..last, default all; ~8 s each)"""
import os, shutil, subprocess, sys, tempfile, json

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NEURAL = "src/Cafe/HW/Espresso/Recompiler/Neural/"
ENGINE = "src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp"
H = lambda s: (NEURAL + s)

# (name, file, old, new, occurrence)  occurrence: 1 = first match only, 0 = every match (must be exactly 'count' matches)
MUTANTS = [
 ("MLP: IsFiniteF always true",           H("NeuralMLP.h"), "return (u & 0x7F800000u) != 0x7F800000u;", "return true;", 1),
 ("MLP: SanitizeFloat no clamp",          H("NeuralMLP.h"), "return x < lo ? lo : (x > hi ? hi : x);", "return x;", 1),
 ("MLP: Forward input not sanitized",     H("NeuralMLP.h"), "cur[i] = SanitizeFloat(in[i], -kInputClamp, kInputClamp, 0.0f);", "cur[i] = in[i];", 1),
 ("MLP: Huber linear zone -> quadratic",  H("NeuralMLP.h"), "delta[nLayers][o] = e > 0 ? huberDelta : -huberDelta;", "delta[nLayers][o] = e;", 1),
 ("MLP: no gradient clipping",            H("NeuralMLP.h"), "const float scale = (clipNorm > 0.0f && norm > clipNorm) ? clipNorm / norm : 1.0f;", "const float scale = 1.0f;", 1),
 ("MLP: tanh derivative missing",         H("NeuralMLP.h"), "delta[l][i] = s * (1.0f - act[l][i] * act[l][i]); // tanh'", "delta[l][i] = s;", 1),
 ("MLP: no weight clamp after step",      H("NeuralMLP.h"), "ClampWeights();\n\t\t\treturn loss;", "return loss;", 1),
 ("MLP: no weight decay",                 H("NeuralMLP.h"), "W[l][k] -= lr * (gW[l][k] * scale + weightDecay * W[l][k]);", "W[l][k] -= lr * (gW[l][k] * scale);", 1),
 ("MLP: bias update sign",                H("NeuralMLP.h"), "B[l][o] -= lr * gB[l][o] * scale;", "B[l][o] += lr * gB[l][o] * scale;", 1),
 ("MLP: blob CRC not checked",            H("NeuralMLP.h"), "if (Crc32(data, size - 4) != crcStored)", "if (false)", 1),
 ("MLP: blob weights not validated",      H("NeuralMLP.h"), "if (!tmp.WeightsValid())\n\t\t\t\treturn false;", "", 1),
 ("MLP: blob size not checked",           H("NeuralMLP.h"), "if (size != tmp.SerializedSize())", "if (false)", 1),
 ("MLP: blob dims not compared",          H("NeuralMLP.h"), "if (v < 1 || v > (uint32_t)kMaxWidth || (int)v != expectedDims[i])", "if (v < 1 || v > (uint32_t)kMaxWidth)", 1),
 ("MLP: blob model id ignored",           H("NeuralMLP.h"), "version != kBlobVersion || modelId != expectedModelId)", "version != kBlobVersion)", 1),
 ("MLP: weight limit not enforced",       H("NeuralMLP.h"), "!IsFiniteF(W[l][k]) || std::fabs(W[l][k]) > kMaxAbsWeight", "!IsFiniteF(W[l][k])", 1),
 ("MLP: Serialize capacity not checked",  H("NeuralMLP.h"), "if (nLayers == 0 || cap < SerializedSize())", "if (nLayers == 0)", 1),
 ("Gate: promote without confidence",     H("NeuralGuard.h"), "float promoteZ = 3.0f;", "float promoteZ = 0.0f;", 1),
 ("Gate: never demote",                   H("NeuralGuard.h"), "m_mean + m_cfg.demoteZ * m_se <= 0.0f", "m_mean + m_cfg.demoteZ * m_se <= -9.0f", 1),
 ("Gate: no dwell time",                  H("NeuralGuard.h"), "uint32_t dwellWindows = 4;", "uint32_t dwellWindows = 0;", 1),
 ("Gate: gain not clamped",               H("NeuralGuard.h"), "gain = gain < -1.0f ? -1.0f : (gain > 1.0f ? 1.0f : gain);", "", 1),
 ("Gate: Force keeps auto",               H("NeuralGuard.h"), "void Force(Mode m) { m_mode = m; m_auto = false; }", "void Force(Mode m) { m_mode = m; m_auto = true; }", 1),
 ("Gate: Off mode can change",            H("NeuralGuard.h"), "if (!m_auto || m_mode == Mode::Off || m_sinceChange", "if (!m_auto || m_sinceChange", 1),
 ("Gate: promotion needs no min windows", H("NeuralGuard.h"), "m_total >= m_cfg.minWindowsPromote && ", "", 1),
 ("Feat: section end not respected",      H("NeuroJitFeatures.h"), "const uint32_t words = (uint32_t)std::min<uint64_t>(maxWords, availWords);", "const uint32_t words = maxWords;", 1),
 ("Feat: zero padding ignored",           H("NeuroJitFeatures.h"), "if (raw == 0)", "if (false)", 1),
 ("Feat: backward cond branch inverted",  H("NeuroJitFeatures.h"), "if (bd < 0)\n\t\t\t\t\t\tf.v[FEAT_BACK] += 1;", "if (bd > 0)\n\t\t\t\t\t\tf.v[FEAT_BACK] += 1;", 1),
 ("Feat: blr always ends the scan",       H("NeuroJitFeatures.h"), "endHere = (int64_t)i >= maxForwardTarget;", "endHere = true;", 1),
 ("Feat: no sign extension",              H("NeuroJitFeatures.h"), "return (int32_t)((value ^ m) - m);", "return (int32_t)value;", 1),
 ("Feat: alignment not checked",          H("NeuroJitFeatures.h"), "if ((pc & 3u) != 0 || sectionEnd <= pc || (sectionEnd & 3u) != 0)", "if (sectionEnd <= pc)", 1),
 ("Feat: scan length not capped",         H("NeuroJitFeatures.h"), "maxWords = std::min(maxWords, kMaxScanWords);", "", 1),
 ("Feat: normalisation scale",            H("NeuroJitFeatures.h"), "std::log1p(c) * 0.125f;", "std::log1p(c) * 1.0f;", 1),
 ("Adv: no deviation bound",              H("NeuroJitAdvisor.h"), "p = std::min(std::max(p, h - m_cfg.maxDevFromBaseline), h + m_cfg.maxDevFromBaseline);", "", 1),
 ("Adv: outliers not skipped",            H("NeuroJitAdvisor.h"), "if (std::fabs(err) > m_cfg.maxOutlierLogErr)", "if (false)", 1),
 ("Adv: no rollback",                     H("NeuroJitAdvisor.h"), "m_model = m_snapshot; // last known good", "", 1),
 ("Adv: order descending",                H("NeuroJitAdvisor.h"), "return e[a].predLogMs < e[b].predLogMs;", "return e[a].predLogMs > e[b].predLogMs;", 1),
 ("Adv: gain sign flipped",               H("NeuroJitAdvisor.h"), "const float gain = (float)((tFifo - tPred) / tFifo);", "const float gain = (float)((tPred - tFifo) / tFifo);", 1),
 ("Adv: planning budget ignored",         H("NeuroJitAdvisor.h"), "m_stats.planMsPerEntry.value > m_cfg.planBudgetMsPerEntry", "m_stats.planMsPerEntry.value > 1.0e9f", 1),
 ("Adv: tiny windows judged",             H("NeuroJitAdvisor.h"), "if (valid < kMinEvalEntries)", "if (valid < 1)", 1),
 ("Adv: uncompiled jobs counted",         H("NeuroJitAdvisor.h"), "if (!e[idx].compiled)", "if (false)", 1),
 ("Adv: learns while Off",                H("NeuroJitAdvisor.h"), "m_gate.GetMode() == Neural::Mode::Off || !m_modelOk", "!m_modelOk", 1),
 ("Adv: no calibration",                  H("NeuroJitAdvisor.h"), "m_calib.Add(target - HeuristicLogMs(f), 0.02f);", "", 1),
 ("Adv: model not used",                  H("NeuroJitAdvisor.h"), "if (!m_modelOk || !m_cfg.useModel)", "if (true)", 1),
 ("Sched: stale jobs compiled",           H("NeuroJitScheduler.h"), "if (!h.StillQueued(e.addr))", "if (false)", 1),
 ("Sched: stop ignored inside window",    H("NeuroJitScheduler.h"), "\t\t\t\tif (h.StopRequested())\n\t\t\t\t\treturn;", "", 1),
 ("Sched: never learns",                  H("NeuroJitScheduler.h"), "adv.LearnWindow(entries);", "", 1),
 ("Sched: never reorders",                H("NeuroJitScheduler.h"), "if (mode == Neural::Mode::Active && entries.size() >= 2)", "if (false)", 1),
 ("Sched: windows never scored",          H("NeuroJitScheduler.h"), "adv.FinishWindow(entries, planMs);", "", 1),
 ("Sched: Off mode still analyses code",  H("NeuroJitScheduler.h"), "if (mode != Neural::Mode::Off)\n\t\t\t\t{\n\t\t\t\t\tif (!h.Features", "if (true)\n\t\t\t\t{\n\t\t\t\t\tif (!h.Features", 1),
 ("Sched: no permutation check",          H("NeuroJitScheduler.h"), "if (!IsPermutation(order, entries.size()))", "if (false)", 1),
 ("Sess: mode text case sensitive",       H("NeuroJitSession.h"), "t.push_back((char)((c >= 'A' && c <= 'Z') ? c - 'A' + 'a' : c));", "t.push_back(c);", 1),
 ("Sess: weights never replaced",         H("NeuroJitSession.h"), "std::filesystem::rename(tmp, p, ec);", "ec.clear();", 1),
 ("Sess: trace cap ignored",              H("NeuroJitSession.h"), "if (m_traceBytes >= m_cfg.traceCapBytes)", "if (false)", 1),
 ("Sess: file size limit ignored",        H("NeuroJitSession.h"), "if (ec || sz > maxBytes)", "if (ec)", 1),
 ("Sess: mode file re-applied every tick",H("NeuroJitSession.h"), "if (mode == m_lastModeFile)\n\t\t\t\treturn;", "", 1),
 ("Win: group coverage off by one",       H("NeuroJitAdvisor.h"), "for (size_t g = 0; g + k <= covered; g += k)", "for (size_t g = 0; g + k < covered; g += k)", 1),
 ("Win: earlier work not in baseline",    H("NeuroJitAdvisor.h"), "double offset = m_burstOffsetMs;", "double offset = 0.0;", 1),
 ("Win: burst never committed",           H("NeuroJitAdvisor.h"), "EvaluateChunk();\n			CommitBurst();", "EvaluateChunk();", 1),
 ("Win: group worst delay sign",          H("NeuroJitAdvisor.h"), "r.worstDelayMs = std::max(r.worstDelayMs, t - compFifo[idx]);", "r.worstDelayMs = std::max(r.worstDelayMs, compFifo[idx] - t);", 1),
 ("Win: SetWindow not clamped",           H("NeuroJitAdvisor.h"), "n = n < kMinWindow ? kMinWindow : (n > kMaxWindowLimit ? kMaxWindowLimit : n);", "", 1),
 ("Win: scheduler never ends a burst",    H("NeuroJitScheduler.h"), "adv.EndBurst(); // queue is empty: the windows seen so far belonged to one burst", "", 1),
 ("Win: window file value ignored",       H("NeuroJitSession.h"), "m_adv.SetWindow((uint32_t)window);", "", 1),
 ("Win: window number not validated",     H("NeuroJitSession.h"), "if (windowOut && !num.empty() && numberEndsCleanly)", "if (windowOut && !num.empty())", 1),
 ("Hook: priority classes mixed",         ENGINE, "if (!out.empty() && useHigh != windowIsHigh)\n\t\t\t\t\tbreak; // never mix priority classes inside one window", "", 1),
 ("Hook: stale entries not dropped",      ENGINE, "continue; // already compiled or invalidated", "; // mutated", 1),
 ("Hook: failed compile counted as ok",   ENGINE, "return funcPtr != PPCRecompiler_leaveRecompilerCode_visited && funcPtr != PPCRecompiler_leaveRecompilerCode_unvisited;", "return true;", 1),
 ("Hook: feature scan unbounded",         ENGINE, "(uint64_t)meta.sectionBase + meta.sectionSize,", "(uint64_t)0xFFFFFFFFull,", 1),
 ("Hook: StillQueued always true",        ENGINE, "return queued;", "return true;", 1),
]

# mutants that provably change nothing observable (kept to document why a survivor is acceptable)
EQUIVALENT = {
 "Gate: Off mode can change": "Off is never Shadow or Active, so the promote/demote conditions are false anyway; the explicit check only documents intent.",
}

def build_and_run(idx, mut, work):
    name, rel, old, new, occ = mut
    d = os.path.join(work, f"m{idx}")
    shutil.copytree(os.path.join(ROOT, NEURAL), os.path.join(d, NEURAL))
    engine_copy = os.path.join(d, "PPCRecompiler.cpp"); shutil.copy(os.path.join(ROOT, ENGINE), engine_copy)
    target = engine_copy if rel == ENGINE else os.path.join(d, rel)
    text = open(target).read()
    if text.count(old) < 1:
        return "INVALID (pattern not found)"
    text = text.replace(old, new, 1)
    open(target, "w").write(text)
    # extract engine glue code from the (possibly mutated) engine file
    c = open(engine_copy).read()
    a = c.index("#if CEMU_NEURAL\nnamespace\n{\n\t// Engine side"); b = c.index("#endif\n", c.index("\t};\n}\n", a))
    open(os.path.join(d, "hooks_extract.inc"), "w").write(c[a:b + 7])
    # mutated copy of the neural headers FIRST in the include path; the rest of src/ from the repository
    inc = ["-I" + d + "/src", "-I" + os.path.join(ROOT, "src"), "-I" + os.path.join(ROOT, "tests/neural"), "-I" + d]
    flags = ["g++", "-std=c++20", "-O1", "-g0", "-w"] + inc
    tests = [("neural", [os.path.join(ROOT, "tests/neural/neural_test.cpp"), "-pthread"]),
             ("math", [os.path.join(ROOT, "tests/neural/math_test.cpp")]),
             ("hooks", [os.path.join(ROOT, "tests/neural/engine_hooks_test.cpp"), os.path.join(ROOT, "src/Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.cpp")])]
    if rel == ENGINE: tests = [tests[2]]
    elif "NeuralMLP" in rel: pass
    for tname, srcs in tests:
        exe = os.path.join(d, "t_" + tname)
        r = subprocess.run(flags + srcs + ["-o", exe], capture_output=True, text=True)
        if r.returncode != 0:
            return "INVALID (does not compile)"
        try:
            r = subprocess.run([exe], capture_output=True, text=True, timeout=120)
        except subprocess.TimeoutExpired:
            return f"KILLED by {tname} (timeout)"
        if r.returncode != 0:
            fails = [l.strip() for l in r.stdout.splitlines() if "FAIL" in l][:1]
            return f"KILLED by {tname}" + (f"  [{fails[0][:70]}]" if fails else "")
    return "SURVIVED (equivalent: " + EQUIVALENT[name] + ")" if name in EQUIVALENT else "SURVIVED"

def main():
    first = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    last = int(sys.argv[2]) if len(sys.argv) > 2 else len(MUTANTS)
    work = tempfile.mkdtemp(prefix="mut_")
    results = {}
    for i in range(first, last + 1):
        res = build_and_run(i, MUTANTS[i - 1], work)
        shutil.rmtree(os.path.join(work, f"m{i}"), ignore_errors=True)
        results[i] = res
        print(f"{i:3d}/{len(MUTANTS)} {MUTANTS[i-1][0]:42s} {res}", flush=True)
    killed = sum(1 for r in results.values() if r.startswith("KILLED")); surv = [i for i, r in results.items() if r == "SURVIVED"]
    equiv = [i for i, r in results.items() if r.startswith("SURVIVED (equivalent")]
    inv = sum(1 for r in results.values() if r.startswith("INVALID"))
    valid = len(results) - inv - len(equiv)
    print(f"\nkilled {killed} of {valid} non-equivalent valid mutants ({100.0*killed/max(valid,1):.0f}%), equivalent {len(equiv)}, invalid {inv}; real survivors: {surv}")
    sys.exit(1 if surv else 0)
    shutil.rmtree(work, ignore_errors=True)

main()
