// Held-out quality of whatever weights are compiled in as defaults (see retrain_loop.sh): Spiky regime, never seen during training.
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitAdvisor.h"
#include "NeuroJitSynthetic.h"
#include <cstdio>
int main()
{
	NeuroJit::Advisor adv; Neural::Rng rng(777); double err = 0; int ok = 0, tot = 0; const int n = 4000;
	std::vector<NeuroJit::CodeFeatures> f; for (int i = 0; i < n; i++) f.push_back(NeuroJitSynth::RandomFeatures(rng));
	// compare in ln-space up to a constant (the advisor recalibrates the scale online; ordering is what scheduling needs)
	double off = 0; for (auto& x : f) off += std::log(NeuroJitSynth::TrueMs(x, NeuroJitSynth::Regime::Spiky)) - adv.PredictLogMs(x); off /= n;
	for (auto& x : f) err += std::fabs(adv.PredictLogMs(x) + off - std::log(NeuroJitSynth::TrueMs(x, NeuroJitSynth::Regime::Spiky)));
	for (int i = 0; i + 1 < n; i += 2) { const float a = NeuroJitSynth::TrueMs(f[i], NeuroJitSynth::Regime::Spiky), b = NeuroJitSynth::TrueMs(f[i + 1], NeuroJitSynth::Regime::Spiky); if (a < 1.3f * b && b < 1.3f * a) continue; tot++; if ((adv.PredictLogMs(f[i]) < adv.PredictLogMs(f[i + 1])) == (a < b)) ok++; }
	std::printf("MAE_ln %.4f  ordering %.4f\n", err / n, (double)ok / tot);
}
