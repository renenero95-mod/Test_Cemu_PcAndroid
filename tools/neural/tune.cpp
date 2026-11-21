// Time-to-benefit experiment: how many real compiles does the network need before its ORDER beats the baseline, per regime and
// per learning-rate setting? Metric = what the scheduler optimises: sum of completion times of a window of 512 functions.
//   g++ -std=c++20 -O2 -Isrc -Itests/neural tools/neural/tune.cpp -o /tmp/tune && /tmp/tune
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitAdvisor.h"
#include "NeuroJitSynthetic.h"
#include <cstdio>
using namespace NeuroJit;
using R = NeuroJitSynth::Regime;

struct Score { double gainNet, gainHeur, gapOracle; };
static Score evaluate(const Advisor& adv, R regime, uint64_t seed)
{
	Neural::Rng rng(seed); double sumF = 0, sumN = 0, sumH = 0, sumO = 0; const int windows = 30, n = 512;
	for (int w = 0; w < windows; w++)
	{
		std::vector<WindowEntry> e(n);
		for (auto& x : e) { x.f = NeuroJitSynth::RandomFeatures(rng); x.compiled = true; x.durMs = NeuroJitSynth::TrueMs(x.f, regime); x.predLogMs = adv.PredictLogMs(x.f); }
		std::vector<uint32_t> fifo(n), net, heur, best; for (int i = 0; i < n; i++) fifo[i] = i;
		Advisor::PredictedOrder(e, net);
		std::vector<WindowEntry> h = e; for (auto& x : h) x.predLogMs = HeuristicLogMs(x.f); Advisor::PredictedOrder(h, heur);
		best = fifo; std::stable_sort(best.begin(), best.end(), [&](uint32_t a, uint32_t b) { return e[a].durMs < e[b].durMs; });
		sumF += Advisor::TotalCompletion(e, fifo); sumN += Advisor::TotalCompletion(e, net); sumH += Advisor::TotalCompletion(e, heur); sumO += Advisor::TotalCompletion(e, best);
	}
	return { (sumF - sumN) / sumF, (sumF - sumH) / sumF, (sumN - sumO) / sumF };
}

int main()
{
	struct Cfg { const char* name; float lr0, lrMin; };
	const Cfg cfgs[] = { {"current lr0=.004 min=.0005", 0.004f, 0.0005f}, {"lr0=.01  min=.001", 0.01f, 0.001f}, {"lr0=.02  min=.002", 0.02f, 0.002f}, {"lr0=.05  min=.003", 0.05f, 0.003f} };
	const struct { R r; const char* name; } regimes[] = { {R::Device, "Device"}, {R::Spiky, "Spiky"}, {R::Inverted, "Inverted"}, {R::Slow100, "Slow100"} };
	const int marks[] = { 500, 2000, 5000, 20000, 60000 };
	for (auto& reg : regimes)
	{
		std::printf("== regime %s: gain vs FIFO of the network order | heuristic order | gap to perfect, after N compiles\n", reg.name);
		for (auto& cfg : cfgs)
		{
			AdvisorConfig ac; ac.lr0 = cfg.lr0; ac.lrMin = cfg.lrMin; Advisor adv(ac); Neural::Rng rng(3); int done = 0;
			std::printf("  %-28s", cfg.name);
			for (int m : marks)
			{
				for (; done < m; done++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, reg.r, rng)); }
				const Score s = evaluate(adv, reg.r, 99);
				std::printf(" | %5d: %+5.1f%% (h %+5.1f%%) gap %4.1f%%", m, 100 * s.gainNet, 100 * s.gainHeur, 100 * s.gapOracle);
			}
			std::printf("   rollbacks %llu\n", (unsigned long long)adv.Stats().rollbacks);
		}
	}
}
