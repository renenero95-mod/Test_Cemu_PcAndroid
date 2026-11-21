// Produces a real-format trace (the exact file Session writes on the phone) from a synthetic "Spiky" game, to test the whole
// collect -> train -> deploy loop offline.  usage: trace_gen <dir> <numFunctions>
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitSession.h"
#include "NeuroJitSynthetic.h"
#include <cstdlib>
int main(int argc, char** argv)
{
	if (argc < 3) return 2;
	NeuroJit::Advisor adv; NeuroJit::SessionConfig cfg; cfg.traceCapBytes = 64u << 20;
	NeuroJit::Session s(adv, argv[1], cfg);
	Neural::Rng rng(2025); const int total = std::atoi(argv[2]);
	for (int done = 0; done < total; done += 256)
	{
		std::vector<NeuroJit::WindowEntry> w(256);
		for (auto& e : w) { e.f = NeuroJitSynth::RandomFeatures(rng); e.compiled = true; e.durMs = NeuroJitSynth::MeasuredMs(e.f, NeuroJitSynth::Regime::Spiky, rng); adv.Predict(e); e.addr = 0x02000000u + 4u * (uint32_t)done; }
		s.OnWindow(w, 0);
	}
	return 0;
}
