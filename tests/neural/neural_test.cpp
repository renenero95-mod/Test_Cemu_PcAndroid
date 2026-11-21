// Tests for the neural JIT advisor: core MLP, features, guards, learning, scheduling with a fake engine.
// Levels: SIMPLE, WRONG input, HARD (learning, poisoning, adversarial), AGGRESSIVE (threads), plus performance numbers.
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitSession.h"
#include "NeuroJitSynthetic.h"
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <deque>
#include <limits>
#include <map>
#include <set>
#include <thread>
#include <tuple>
#include <vector>

using namespace NeuroJit;
using Neural::Rng;

static int g_fail = 0;
#define CHECK(c) do { if(!(c)) { std::printf("  FAIL line %d: %s\n", __LINE__, #c); g_fail++; } } while(0)
static double NowMsReal() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

// ---------------------------------------------------------------------------------------------
static void testSimple()
{
	std::printf("[SIMPLE]\n");
	// 1) forward pass equals a hand computed 2-layer example: y = 2*tanh(1*x0 + 0.5*x1 + 0.1) - 1
	Neural::MLP m; const int d[3] = { 2, 1, 1 };
	CHECK(m.Init(d, 2));
	m.W[0][0] = 1.0f; m.W[0][1] = 0.5f; m.B[0][0] = 0.1f; m.W[1][0] = 2.0f; m.B[1][0] = -1.0f;
	float in[2] = { 0.3f, -0.2f }, out[1];
	m.Forward(in, out);
	CHECK(std::fabs(out[0] - (2.0f * std::tanh(0.3f - 0.1f + 0.1f) - 1.0f)) < 1e-6f);
	// 2) serialization round trip is bit exact
	Neural::MLP r; const int d3[4] = { kFeatureCount, 16, 8, 1 };
	r.Init(d3, 3); r.InitRandom(5);
	std::vector<uint8_t> blob(r.SerializedSize());
	CHECK(r.Serialize(blob.data(), blob.size(), kModelId));
	Neural::MLP r2; r2.Init(d3, 3);
	CHECK(r2.Deserialize(blob.data(), blob.size(), kModelId, d3, 3));
	float x[kFeatureCount]; for (int i = 0; i < kFeatureCount; i++) x[i] = 0.05f * (float)i;
	float o1[1], o2[1]; r.Forward(x, o1); r2.Forward(x, o2);
	CHECK(o1[0] == o2[0]);
	// 3) built-in weights load and give a sensible order: more code = more expensive
	Advisor adv;
	CHECK(adv.Stats().modelLoaded);
	CodeFeatures small, big; small.valid = big.valid = true; small.v[FEAT_WORDS] = 20; small.v[FEAT_COND] = 2; big.v[FEAT_WORDS] = 2000; big.v[FEAT_COND] = 200;
	CHECK(adv.PredictLogMs(small) < adv.PredictLogMs(big));
	// 4) feature extractor on a hand-assembled function: li, cmpwi, beq +8, li, blr   (+ a loop and a call)
	std::vector<uint32_t> code = { 0x38600001, 0x2C030000, 0x41820008, 0x38600002, 0x4E800020, 0x48000011, 0x4BFFFFF8, 0x4E800020 };
	auto rd = [&](uint32_t a) { return code[(a - 0x1000) / 4]; };
	auto f = ExtractFeatures(0x1000, 0x1000 + 4 * code.size(), rd);
	CHECK(f.valid && f.v[FEAT_WORDS] == 5 && f.v[FEAT_COND] == 1 && f.v[FEAT_BLR] == 1 && f.v[FEAT_SPAN] == 4); // stops at the first blr after the branch target
	auto f2 = ExtractFeatures(0x1014, 0x1000 + 4 * code.size(), rd); // bl, b (backward), blr
	CHECK(f2.valid && f2.v[FEAT_CALLS] == 1 && f2.v[FEAT_BACK] == 1 && f2.v[FEAT_BLR] == 1);
	// 5) the built-in weights are the ones the trainer produced: ordering by size is monotonic over a sweep
	float prev = -100.0f; bool mono = true;
	for (int w = 8; w <= 4096; w *= 2) { CodeFeatures g; g.valid = true; g.v[FEAT_WORDS] = (float)w; g.v[FEAT_COND] = w * 0.1f; float p = adv.PredictLogMs(g); if (p < prev) mono = false; prev = p; }
	CHECK(mono);
	// 6) mode text parser
	int md = -1;
	CHECK(ParseModeText("  Active\r\n", md) && md == 2); CHECK(ParseModeText("OFF", md) && md == 0); CHECK(ParseModeText("auto", md) && md == 3);
	CHECK(!ParseModeText("banana", md)); CHECK(!ParseModeText("", md)); CHECK(!ParseModeText(std::string(500, 'x'), md));
}

// ---------------------------------------------------------------------------------------------
static void testWrongInput()
{
	std::printf("[WRONG INPUT]\n");
	Advisor adv;
	const float nan = std::numeric_limits<float>::quiet_NaN(), inf = std::numeric_limits<float>::infinity();
	const float nasty[] = { nan, inf, -inf, 1e30f, -1e30f, 0.0f, -1.0f, 1e-30f };
	for (float v : nasty)
	{
		CodeFeatures f; f.valid = true;
		for (int i = 0; i < kFeatureCount; i++) f.v[i] = v;
		float p = adv.PredictLogMs(f);
		CHECK(Neural::IsFiniteF(p) && p >= kLogMsMin && p <= kLogMsMax);
		adv.Observe(f, v); // must not crash, corrupt, or produce NaN weights
	}
	CodeFeatures ok; ok.valid = true; ok.v[FEAT_WORDS] = 100;
	CHECK(Neural::IsFiniteF(adv.PredictLogMs(ok)));
	for (float ms : { nan, inf, -5.0f, 0.0f, 1e20f, 1e-20f })
		adv.Observe(ok, ms);
	CHECK(adv.PredictLogMs(ok) >= kLogMsMin && adv.PredictLogMs(ok) <= kLogMsMax);
	// corrupted blobs are always rejected and never touch the live model (single bit flips, truncation, extension, dimension games)
	const std::vector<uint8_t> good = adv.SerializeModel();
	CHECK(!good.empty());
	Rng rng(99); int accepted = 0;
	for (int i = 0; i < 20000; i++)
	{
		std::vector<uint8_t> b = good;
		switch (i % 4)
		{
		case 0: b[rng.NextU64() % b.size()] ^= (uint8_t)(1u << (rng.NextU64() % 8)); break;               // bit flip
		case 1: b.resize(rng.NextU64() % b.size()); break;                                                  // truncate
		case 2: b.push_back((uint8_t)rng.NextU64()); break;                                                 // extend
		case 3: for (int k = 0; k < 8; k++) b[rng.NextU64() % b.size()] = (uint8_t)rng.NextU64(); break;    // random garbage bytes
		}
		Advisor probe; const float before = probe.PredictLogMs(ok);
		const bool r = probe.LoadWeights(b.data(), b.size());
		if (r) accepted++;
		if (!r) CHECK(probe.PredictLogMs(ok) == before); // rejected blob left the model unchanged
	}
	CHECK(accepted == 0);
	CHECK(!adv.LoadWeights(nullptr, 0)); CHECK(!adv.LoadWeights(good.data(), 3));
	// valid CRC but NaN weight inside: must still be rejected (weights validation)
	{
		std::vector<uint8_t> b = good; float nanv = nan; std::memcpy(&b[40], &nanv, 4);
		uint32_t crc = Neural::Crc32(b.data(), b.size() - 4); std::memcpy(&b[b.size() - 4], &crc, 4);
		Advisor probe; CHECK(!probe.LoadWeights(b.data(), b.size()));
		float huge = 1000.0f; std::memcpy(&b[40], &huge, 4); crc = Neural::Crc32(b.data(), b.size() - 4); std::memcpy(&b[b.size() - 4], &crc, 4);
		CHECK(!probe.LoadWeights(b.data(), b.size()));
	}
	// feature extractor: bad addresses/sections and a reader that must never be called outside the section
	uint32_t minSeen = 0xFFFFFFFFu, maxSeen = 0;
	auto spy = [&](uint32_t a) { minSeen = std::min(minSeen, a); maxSeen = std::max(maxSeen, a); return 0x38600001u; };
	CHECK(!ExtractFeatures(0x1002, 0x2000, spy).valid);            // unaligned pc
	CHECK(!ExtractFeatures(0x1000, 0x1000, spy).valid);            // empty section
	CHECK(!ExtractFeatures(0x2000, 0x1000, spy).valid);            // end before start
	CHECK(!ExtractFeatures(0x1000, 0x1002, spy).valid);            // unaligned end
	auto fz = ExtractFeatures(0x1000, 0x1010, spy);                // only 4 words are available
	CHECK(fz.valid && fz.v[FEAT_WORDS] == 4 && maxSeen == 0x100C && minSeen == 0x1000);
	minSeen = 0xFFFFFFFFu; maxSeen = 0;
	auto fe = ExtractFeatures(0xFFFFFFF0u, 0x100000000ull, spy);   // up to the very end of the 32 bit address space, no wrap
	CHECK(fe.valid && fe.v[FEAT_WORDS] == 4 && maxSeen == 0xFFFFFFFCu && minSeen == 0xFFFFFFF0u);
	maxSeen = 0; auto fl = ExtractFeatures(0x1000, 0x1000 + 4u * 100000u, spy); // huge section: scan is capped
	CHECK(fl.valid && fl.v[FEAT_WORDS] == kMaxScanWords && maxSeen == 0x1000 + 4u * (kMaxScanWords - 1));
	// garbage instruction words never break the extractor (all 2^k patterns in a sample)
	Rng g(3); for (int i = 0; i < 20000; i++) { auto fg = ExtractFeatures(0x1000, 0x1000 + 4 * 64, [&](uint32_t) { return (uint32_t)g.NextU64(); }); CHECK(fg.valid || fg.v[FEAT_WORDS] == 0); if (g_fail > 20) break; }
}

// ---------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------
// Fake engine: queues of addresses with synthetic code, invalidation, stale entries, stop signal, virtual clock.
struct FakeEngine
{
	using R = NeuroJitSynth::Regime;
	std::deque<uint32_t> high, low;
	std::set<uint32_t> visited;                  // addresses still waiting
	std::set<uint32_t> compiledSet;
	std::vector<uint32_t> compileLog;            // order in which compiles happened
	std::vector<NeuroJit::CodeFeatures> featOf;  // by address index
	std::vector<double> costOf;                  // true cost in ms (virtual)
	std::vector<bool> failsToCompile;
	double now = 0;
	int stopAfterCompiles = -1;
	uint32_t base = 0x02000000u;
	uint64_t readCount = 0, readOutside = 0;
	uint32_t sectionEnd = 0;
	Rng rng{ 77 };
	std::vector<uint32_t> invalidateOnCompile;   // compiling this address invalidates (un-visits) another one
	std::vector<uint32_t> invalidateTarget;

	// ---- Hooks
	bool StopRequested() { return stopAfterCompiles >= 0 && (int)compileLog.size() >= stopAfterCompiles; }
	size_t PopWindow(std::vector<uint32_t>& out, size_t maxCount)
	{
		bool fromHigh = false, set = false;
		while (out.size() < maxCount)
		{
			std::deque<uint32_t>* q = !high.empty() ? &high : (!low.empty() ? &low : nullptr);
			if (!q) break;
			const bool isHigh = (q == &high);
			if (set && isHigh != fromHigh) break;
			uint32_t a = q->front(); q->pop_front();
			if (!visited.count(a)) continue;
			if (!set) { set = true; fromHigh = isHigh; }
			out.push_back(a);
		}
		return out.size();
	}
	bool StillQueued(uint32_t a) { return visited.count(a) != 0; }
	void Compile(uint32_t a)
	{
		if (!visited.count(a)) staleCompiles++; // the scheduler must never compile something that was invalidated meanwhile
		const size_t i = (a - base) / 4;
		now += costOf[i];
		compileLog.push_back(a);
		visited.erase(a);
		if (!failsToCompile[i]) compiledSet.insert(a);
		for (size_t k = 0; k < invalidateOnCompile.size(); k++) if (invalidateOnCompile[k] == a) visited.erase(invalidateTarget[k]);
	}
	bool Compiled(uint32_t a) { return compiledSet.count(a) != 0 && !failsToCompile[(a - base) / 4]; }
	double NowMs() { return now; }
	double featureCostMs = 0; double invalidFrac = 0; uint64_t featureCalls = 0; uint64_t staleCompiles = 0;
	bool Features(uint32_t a, NeuroJit::CodeFeatures& out)
	{
		now += featureCostMs; featureCalls++;
		const size_t i = (a - base) / 4;
		out = featOf[i];
		if (invalidFrac > 0 && (i * 2654435761u % 1000) < (uint32_t)(invalidFrac * 1000)) { out = NeuroJit::CodeFeatures(); return false; }
		return out.valid;
	}

	void Fill(int n, R regime, float highFrac, uint64_t seed, float sigma = 0.25f)
	{
		Rng r(seed);
		featOf.assign(n, {}); costOf.assign(n, 0); failsToCompile.assign(n, false);
		for (int i = 0; i < n; i++)
		{
			featOf[i] = NeuroJitSynth::RandomFeatures(r);
			costOf[i] = NeuroJitSynth::MeasuredMs(featOf[i], regime, r, sigma);
			const uint32_t a = base + 4u * i;
			visited.insert(a);
			((r.NextFloat() < highFrac) ? high : low).push_back(a);
		}
	}
};

struct CountingSink { int windows = 0; double planSum = 0; void OnWindow(const std::vector<WindowEntry>&, double p) { windows++; planSum += p; } };

static double totalCompletion(const FakeEngine& e)
{
	double t = 0, sum = 0;
	for (uint32_t a : e.compileLog) { t += e.costOf[(a - e.base) / 4]; sum += t; }
	return sum;
}

static double meanAbsErr(const Advisor& a, NeuroJitSynth::Regime r, uint64_t seed, int n = 2000)
{
	Rng rng(seed); double e = 0;
	for (int i = 0; i < n; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); e += std::fabs(a.PredictLogMs(f) - std::log(NeuroJitSynth::TrueMs(f, r))); }
	return e / n;
}
static double heuristicErr(NeuroJitSynth::Regime r, uint64_t seed, int n = 2000)
{
	Rng rng(seed); double e = 0;
	for (int i = 0; i < n; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); e += std::fabs(HeuristicLogMs(f) - std::log(NeuroJitSynth::TrueMs(f, r))); }
	return e / n;
}

static void testLearning()
{
	std::printf("[HARD: online learning]\n");
	using R = NeuroJitSynth::Regime;
	// A) the device is different from what the heuristic assumes: the model must adapt and beat the heuristic
	{
		Advisor adv; Rng rng(11);
		const double before = meanAbsErr(adv, R::Device, 500), heur = heuristicErr(R::Device, 500);
		for (int i = 0; i < 30000; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Device, rng)); }
		const double after = meanAbsErr(adv, R::Device, 500);
		std::printf("  Device regime: error before %.3f, after 30k observations %.3f, heuristic %.3f (ln units)\n", before, after, heur);
		CHECK(after < 0.6 * before);
		CHECK(after < 0.8 * heur);
		CHECK(adv.Stats().rollbacks < 5);
	}
	// B) data poisoning: 10% of the compile times are garbage (huge/tiny/NaN): the model must stay sane
	{
		Advisor adv; Rng rng(12);
		for (int i = 0; i < 30000; i++)
		{
			auto f = NeuroJitSynth::RandomFeatures(rng);
			float ms = NeuroJitSynth::MeasuredMs(f, R::Baseline, rng);
			const uint64_t k = rng.NextU64() % 100;
			if (k < 3) ms = 1.0e6f; else if (k < 6) ms = 1.0e-6f; else if (k < 8) ms = std::numeric_limits<float>::quiet_NaN(); else if (k < 10) ms = -ms;
			adv.Observe(f, ms);
		}
		const double err = meanAbsErr(adv, R::Baseline, 600);
		std::printf("  10%% poisoned labels: error %.3f (clean baseline noise floor ~0.20)\n", err);
		CHECK(err < 0.45);
		CHECK(adv.SerializeModel().size() == 1448);
	}
	// C) divergence: a burst of consistent but absurd, very noisy labels. What matters for scheduling is the ORDER, not the scale.
	{
		auto pairAcc = [](const Advisor& adv, uint64_t seed) {
			Rng r(seed); int ok = 0, tot = 0;
			for (int i = 0; i < 6000; i++)
			{
				auto a = NeuroJitSynth::RandomFeatures(r), b = NeuroJitSynth::RandomFeatures(r);
				const float ta = NeuroJitSynth::TrueMs(a, R::Baseline), tb = NeuroJitSynth::TrueMs(b, R::Baseline);
				if (ta < 1.3f * tb && tb < 1.3f * ta) continue;
				tot++; if ((adv.PredictLogMs(a) < adv.PredictLogMs(b)) == (ta < tb)) ok++;
			}
			return (double)ok / tot;
		};
		Advisor adv; Rng rng(13);
		for (int i = 0; i < 4000; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Baseline, rng)); }
		const double accGood = pairAcc(adv, 700);
		for (int i = 0; i < 3000; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Baseline, rng, 2.5f) * 200.0f); }
		const double accBad = pairAcc(adv, 700);
		std::printf("  absurd noisy label burst: ordering accuracy %.1f%% -> %.1f%%, rollbacks %llu, outliers skipped %llu, calibration offset %+.1f ln\n", 100 * accGood, 100 * accBad, (unsigned long long)adv.Stats().rollbacks, (unsigned long long)adv.Stats().skippedOutliers, adv.Calibration());
		CHECK(accGood > 0.9);
		CHECK(accBad > 0.5); // noisy labels hurt but the order stays better than a coin flip
		// the real protection is the gate: put the damaged model in charge of a real queue in auto mode
		FakeEngine e; e.Fill(20000, R::Baseline, 0.0f, 21); DrainQueue(e, adv);
		FakeEngine f; f.Fill(20000, R::Baseline, 0.0f, 21); Advisor off; off.ForceMode(Neural::Mode::Off); DrainQueue(f, off);
		const double ratio = totalCompletion(e) / totalCompletion(f);
		std::printf("  damaged model in auto mode: total completion %.3fx of FIFO (<1 is better)\n", ratio);
		CHECK(ratio < 1.03);
	}
	// D) the model never leaves its bounds, whatever it is fed
	{
		Advisor adv; Rng rng(14);
		for (int i = 0; i < 20000; i++) { CodeFeatures f = NeuroJitSynth::RandomFeatures(rng); for (int k = 0; k < kFeatureCount; k++) if (rng.NextFloat() < 0.05f) f.v[k] *= 1e6f; adv.Observe(f, std::exp(rng.NextRange(-9.0f, 12.0f))); }
		Neural::MLP probe; const int d3[4] = { kFeatureCount, 16, 8, 1 }; auto blob = adv.SerializeModel();
		CHECK(probe.Deserialize(blob.data(), blob.size(), kModelId, d3, 3) && probe.WeightsValid());
	}
	// E) determinism: same inputs, same learned model
	{
		auto run = [] { Advisor a; Rng r(15); for (int i = 0; i < 3000; i++) { auto f = NeuroJitSynth::RandomFeatures(r); a.Observe(f, NeuroJitSynth::MeasuredMs(f, NeuroJitSynth::Regime::Device, r)); } return a.SerializeModel(); };
		CHECK(run() == run());
	}
}

// ---------------------------------------------------------------------------------------------
static void testGate()
{
	std::printf("[GATE]\n");
	Neural::AuthorityGate g;
	for (int i = 0; i < 40; i++) g.Feed(0.0f);
	CHECK(g.GetMode() == Neural::Mode::Shadow);                       // no benefit => never promoted
	for (int i = 0; i < 40; i++) g.Feed(0.08f);
	CHECK(g.GetMode() == Neural::Mode::Active);                       // measured benefit => promoted
	for (int i = 0; i < 40; i++) g.Feed(-0.05f);
	CHECK(g.GetMode() == Neural::Mode::Shadow);                       // measured harm => demoted
	// pure noise around zero (std 0.115 per window): must stay put
	{
		int worst = 0;
		for (uint64_t seed = 1; seed <= 50; seed++)
		{
			Neural::AuthorityGate n; Rng rng(seed);
			for (int i = 0; i < 2000; i++) n.Feed(rng.NextRange(-0.2f, 0.2f));
			worst = std::max<int>(worst, (int)n.Transitions());
		}
		std::printf("  zero-mean noise, 50 runs x 2000 windows: worst case %d mode changes\n", worst);
		CHECK(worst <= 2);
	}
	// real but noisy benefit (mean +8%, std 10%): promoted and kept
	{
		Neural::AuthorityGate n; Rng rng(9);
		for (int i = 0; i < 400; i++) n.Feed(0.08f + rng.NextRange(-0.17f, 0.17f));
		std::printf("  noisy real benefit: mode %s, %u mode changes\n", Neural::ModeName(n.GetMode()), n.Transitions());
		CHECK(n.GetMode() == Neural::Mode::Active && n.Transitions() <= 2);
	}
	// forced modes never change by themselves; NaN/inf are ignored
	Neural::AuthorityGate f; f.Force(Neural::Mode::Active);
	for (int i = 0; i < 1000; i++) f.Feed(-1.0f);
	CHECK(f.GetMode() == Neural::Mode::Active);
	Neural::AuthorityGate z; z.Feed(std::numeric_limits<float>::quiet_NaN()); z.Feed(std::numeric_limits<float>::infinity()); CHECK(z.Evaluations() == 0);
	Neural::AuthorityGate off; off.Force(Neural::Mode::Off); for (int i = 0; i < 1000; i++) off.Feed(1.0f); CHECK(off.GetMode() == Neural::Mode::Off);
}

static void testScheduler()
{
	std::printf("[SCHEDULER: fake engine]\n");
	using R = NeuroJitSynth::Regime;
	// 1) every queued function is compiled exactly once, in every mode, with failures and invalidations mixed in
	for (Neural::Mode mode : { Neural::Mode::Off, Neural::Mode::Shadow, Neural::Mode::Active })
		for (uint64_t seed = 1; seed <= 40; seed++)
		{
			FakeEngine e; e.Fill(500, R::Baseline, 0.1f, seed);
			Rng r(seed * 31);
			for (int k = 0; k < 20; k++) { e.invalidateOnCompile.push_back(e.base + 4u * (r.NextU64() % 500)); e.invalidateTarget.push_back(e.base + 4u * (r.NextU64() % 500)); }
			for (int k = 0; k < 30; k++) e.failsToCompile[r.NextU64() % 500] = true;
			Advisor adv; adv.ForceMode(mode); CountingSink sink;
			DrainQueue(e, adv, sink);
			std::set<uint32_t> uniq(e.compileLog.begin(), e.compileLog.end());
			CHECK(uniq.size() == e.compileLog.size());          // never compiled twice
			CHECK(e.visited.empty());                            // nothing left behind (compiled or invalidated)
			for (uint32_t a : e.compileLog) CHECK(e.visited.count(a) == 0);
			CHECK(e.staleCompiles == 0);
			if (mode == Neural::Mode::Off) CHECK(e.featureCalls == 0);   // Off really means off: no code analysis either
			if (g_fail > 20) return;
		}
	// 2) invalidated (stale) entries are never compiled
	{
		FakeEngine e; e.Fill(200, R::Baseline, 0.0f, 3);
		for (uint32_t i = 0; i < 200; i += 3) e.visited.erase(e.base + 4 * i);
		std::set<uint32_t> expected = e.visited;
		Advisor adv; adv.ForceMode(Neural::Mode::Active); DrainQueue(e, adv);
		std::set<uint32_t> got(e.compileLog.begin(), e.compileLog.end());
		CHECK(got == expected);
	}
	// 3) high priority class is always compiled before the low class, also when reordering
	{
		FakeEngine e; e.Fill(400, R::Baseline, 0.3f, 4);
		std::set<uint32_t> highSet(e.high.begin(), e.high.end());
		Advisor adv; adv.ForceMode(Neural::Mode::Active); DrainQueue(e, adv);
		bool seenLow = false, ok = true;
		for (uint32_t a : e.compileLog) { if (!highSet.count(a)) seenLow = true; else if (seenLow) ok = false; }
		CHECK(ok);
	}
	// 4) bounded reordering: no function waits behind more than one full window of later arrivals (no starvation)
	{
		FakeEngine e; e.Fill(1000, R::Device, 0.0f, 5);
		std::vector<uint32_t> arrival(e.low.begin(), e.low.end());
		Advisor adv; adv.ForceMode(Neural::Mode::Active); DrainQueue(e, adv);
		std::map<uint32_t, size_t> pos; for (size_t i = 0; i < arrival.size(); i++) pos[arrival[i]] = i;
		size_t worst = 0;
		for (size_t i = 0; i < e.compileLog.size(); i++) { size_t a = pos[e.compileLog[i]]; worst = std::max(worst, a > i ? a - i : i - a); }
		std::printf("  largest displacement from arrival order: %zu (window %u)\n", worst, kDefaultWindow);
		CHECK(worst < kDefaultWindow);
	}
	// 5) stop signal: no compile after the stop request
	{
		FakeEngine e; e.Fill(300, R::Baseline, 0.0f, 6); e.stopAfterCompiles = 37;
		Advisor adv; adv.ForceMode(Neural::Mode::Active); DrainQueue(e, adv);
		CHECK(e.compileLog.size() == 37);
	}
	// 6) the model reads guest memory only inside the section: tested at feature level above (spy reader)
	// 7) benefit: in a regime the prior understands, active ordering beats FIFO on average completion time
	{
		double fifo = 0, act = 0;
		for (uint64_t seed = 1; seed <= 20; seed++)
		{
			FakeEngine a; a.Fill(2000, R::Baseline, 0.0f, seed); Advisor off; off.ForceMode(Neural::Mode::Off); DrainQueue(a, off);
			FakeEngine b; b.Fill(2000, R::Baseline, 0.0f, seed); Advisor on; on.ForceMode(Neural::Mode::Active); DrainQueue(b, on);
			fifo += totalCompletion(a); act += totalCompletion(b);
		}
		std::printf("  Baseline regime: mean completion time FIFO %.0f vs neural order %.0f  (%.1f%% lower)\n", fifo / 20, act / 20, 100.0 * (fifo - act) / fifo);
		CHECK(act < 0.92 * fifo);
	}
	// 8) NEGATIVE CONTROL: cost unrelated to anything the model sees -> the gate must not hand over control
	{
		int promoted = 0;
		for (uint64_t seed = 1; seed <= 10; seed++)
		{
			FakeEngine e; e.Fill(20000, R::Random, 0.0f, seed); Advisor adv; DrainQueue(e, adv);
			if (adv.GetMode() == Neural::Mode::Active) promoted++;
			if (seed == 1) std::printf("  random costs: gain vs FIFO mean %+.2f%% +-%.2f%% (mode %s, %u mode changes)\n", 100.0 * adv.Gate().GainMean(), 100.0 * adv.Gate().GainStdErr(), Neural::ModeName(adv.GetMode()), adv.Gate().Transitions());
		}
		CHECK(promoted <= 1);
	}
	// 9) ADVERSARIAL: bigger functions are cheaper (the prior is exactly wrong): damage must be bounded and the model must recover
	{
		FakeEngine e; e.Fill(60000, R::Inverted, 0.0f, 8, 0.1f);
		Advisor adv; DrainQueue(e, adv);
		FakeEngine f; f.Fill(60000, R::Inverted, 0.0f, 8, 0.1f); Advisor off; off.ForceMode(Neural::Mode::Off); DrainQueue(f, off);
		const double ratio = totalCompletion(e) / totalCompletion(f);
		std::printf("  inverted costs: auto mode total completion = %.3fx of plain FIFO (1.0 = equal), final mode %s, mode changes %u, gain EMA %+.1f%%\n", ratio, Neural::ModeName(adv.GetMode()), adv.Gate().Transitions(), 100.0 * adv.Gate().GainMean());
		CHECK(ratio < 1.05); // never meaningfully worse than FIFO
	}
	// 10) the learned order gets close to the perfect order in the Device regime after learning
	{
		FakeEngine e; e.Fill(60000, R::Device, 0.0f, 9, 0.1f);
		Advisor adv; DrainQueue(e, adv);
		std::printf("  Device regime (learning online, auto mode): gain vs FIFO %+.1f%%, gap to perfect order %.1f%%, final mode %s\n", 100.0 * adv.Stats().gainVsFifo.value, 100.0 * adv.Stats().oracleGap.value, Neural::ModeName(adv.GetMode()));
		CHECK(adv.Stats().gainVsFifo.value > 0.05f);
	}
}


// ---------------------------------------------------------------------------------------------
static void testGaps()
{
	std::printf("[GAPS found by the coverage report]\n");
	using R = NeuroJitSynth::Regime;
	namespace fs = std::filesystem;
	// A) heuristic-only advisor: orders by the baseline heuristic, never learns
	{
		AdvisorConfig cfg; cfg.useModel = false; Advisor h(cfg); Advisor n;
		CodeFeatures a, b; a.valid = b.valid = true; a.v[FEAT_WORDS] = 10; b.v[FEAT_WORDS] = 1000;
		CHECK(h.PredictLogMs(a) < h.PredictLogMs(b));
		const auto before = h.SerializeModel(); Rng r(3);
		for (int i = 0; i < 500; i++) { auto f = NeuroJitSynth::RandomFeatures(r); h.Observe(f, 5.0f); }
		CHECK(h.SerializeModel() == before && h.Stats().observed == 0);
		CHECK(n.Stats().observed == 0);
	}
	// B) planning too slow => the advisor switches itself off and the queue is processed in plain arrival order afterwards
	{
		FakeEngine e; e.Fill(30000, R::Device, 0.0f, 31); e.featureCostMs = 1.0; // 1 ms per function just to analyse it: absurdly slow
		std::vector<uint32_t> arrival(e.low.begin(), e.low.end());
		Advisor adv; DrainQueue(e, adv);
		std::printf("  slow planning: mode %s, reason '%s'\n", Neural::ModeName(adv.GetMode()), adv.Stats().disabledReason);
		CHECK(adv.GetMode() == Neural::Mode::Off); CHECK(std::string(adv.Stats().disabledReason) == "planning too slow");
		// the tail of the run must be plain FIFO
		bool fifoTail = true; for (size_t i = e.compileLog.size() - 2000; i < e.compileLog.size(); i++) if (e.compileLog[i] != arrival[i]) fifoTail = false;
		CHECK(fifoTail);
	}
	// C) functions whose code could not be analysed (outside any known section) stay where they arrived, nothing breaks
	{
		FakeEngine e; e.Fill(3000, R::Baseline, 0.0f, 32); e.invalidFrac = 0.3; Advisor adv; adv.ForceMode(Neural::Mode::Active); DrainQueue(e, adv);
		std::set<uint32_t> u(e.compileLog.begin(), e.compileLog.end()); CHECK(u.size() == 3000 && e.visited.empty());
	}
	// D) periodic report/save without Flush, and the mode switch is picked up on the same tick
	{
		const fs::path dir = fs::temp_directory_path() / "cemu_neural_test2"; std::error_code ec; fs::remove_all(dir, ec);
		SessionConfig cfg; cfg.reportIntervalSec = 0; cfg.saveIntervalSec = 0; cfg.saveMinObservations = 1;
		Advisor adv; Session s(adv, dir, cfg); Rng r(4);
		for (int i = 0; i < 50; i++) { auto f = NeuroJitSynth::RandomFeatures(r); adv.Observe(f, 3.0f); }
		{ std::ofstream(dir / "mode.txt") << "active"; }
		std::vector<WindowEntry> w(1); s.OnWindow(w, 0);
		CHECK(fs::exists(dir / "report.txt") && fs::exists(dir / "jit_cost.weights") && adv.GetMode() == Neural::Mode::Active);
		fs::remove_all(dir, ec);
	}
	// E) file helpers fail cleanly
	{
		std::string out; CHECK(!ReadSmallFile("/nonexistent_dir_xyz/file", out, 100));
		CHECK(!AtomicWriteFile("/nonexistent_dir_xyz/file", "abc", 3));
		const fs::path p = fs::temp_directory_path() / "cemu_neural_small.txt"; { std::ofstream(p) << std::string(1000, 'a'); }
		CHECK(!ReadSmallFile(p, out, 100)); CHECK(ReadSmallFile(p, out, 2000) && out.size() == 1000); std::error_code ec; fs::remove(p, ec);
	}
	// F) blob header fields are all enforced (valid CRC, wrong content)
	{
		Advisor a; const auto good = a.SerializeModel(); const int d3[4] = { kFeatureCount, 16, 8, 1 };
		auto reCrc = [](std::vector<uint8_t>& b) { uint32_t crc = Neural::Crc32(b.data(), b.size() - 4); std::memcpy(&b[b.size() - 4], &crc, 4); };
		auto tryPatch = [&](size_t off, uint32_t v) { auto b = good; std::memcpy(&b[off], &v, 4); reCrc(b); Neural::MLP m; m.Init(d3, 3); return m.Deserialize(b.data(), b.size(), kModelId, d3, 3); };
		CHECK(tryPatch(0, 0x12345678u) == false); // magic
		CHECK(tryPatch(4, 2u) == false);          // version
		CHECK(tryPatch(8, 0x11111111u) == false); // model id
		CHECK(tryPatch(12, 2u) == false);         // layer count
		CHECK(tryPatch(16, 11u) == false);        // input width
		CHECK(tryPatch(12, 9u) == false);         // absurd layer count
		Neural::MLP m; m.Init(d3, 3); CHECK(m.Deserialize(good.data(), good.size(), kModelId, d3, 3)); // control: unpatched is accepted
		Neural::MLP empty; float in[1] = { 1 }, o[1] = { 7 }; empty.Forward(in, o); CHECK(o[0] == 7); CHECK(empty.TrainStep(in, o, 0.1f, 1, 1, 0) == 0.0f);
		CHECK(!empty.Serialize(nullptr, 0, 1));
		int bad[2] = { 0, 1 }; Neural::MLP z; CHECK(!z.Init(bad, 1)); int bad2[5] = { 1, 1, 1, 1, 1 }; CHECK(!z.Init(bad2, 4));
	}
	// G) feature extractor details: zero padding ends the scan, bctr ends a function, blr before a pending forward branch target does not
	{
		std::vector<uint32_t> code = { 0x38600001, 0x38600002, 0x00000000, 0x4E800020 };
		auto rd = [&](uint32_t a) { return code[(a - 0x1000) / 4]; };
		CHECK(ExtractFeatures(0x1000, 0x1010, rd).v[FEAT_WORDS] == 2);
		std::vector<uint32_t> code2 = { 0x41820010 /*beq +16*/, 0x4E800020 /*blr (early return)*/, 0x38600001, 0x38600002, 0x4E800420 /*bctr*/, 0x38600003 };
		auto rd2 = [&](uint32_t a) { return code2[(a - 0x1000) / 4]; };
		auto f = ExtractFeatures(0x1000, 0x1018, rd2);
		CHECK(f.v[FEAT_WORDS] == 5 && f.v[FEAT_INDIRECT] == 1 && f.v[FEAT_BLR] == 1); // the early blr did not end the scan; the bctr did
		CHECK(std::string(Neural::ModeName(Neural::Mode::Off)) == "off" && std::string(Neural::ModeName((Neural::Mode)9)) == "?");
		Neural::Ema e; e.Add(std::numeric_limits<float>::quiet_NaN(), 0.1f); CHECK(e.count == 0); e.Add(2.0f, 0.1f); e.Add(4.0f, 0.5f); CHECK(std::fabs(e.value - 3.0f) < 1e-6f);
	}
}


// ---------------------------------------------------------------------------------------------
// Tests added after mutation testing showed behaviours that nobody was checking.
static void testStrength()
{
	std::printf("[STRENGTH: behaviours found untested by mutation testing]\n");
	using R = NeuroJitSynth::Regime;
	const float nan = std::numeric_limits<float>::quiet_NaN(), inf = std::numeric_limits<float>::infinity();
	const int d3[4] = { kFeatureCount, 16, 8, 1 };
	auto reCrc = [](std::vector<uint8_t>& b) { uint32_t crc = Neural::Crc32(b.data(), b.size() - 4); std::memcpy(&b[b.size() - 4], &crc, 4); };
	// 1) the MLP protects itself even when called directly with garbage
	{
		Neural::MLP m; int d[3] = { 3, 4, 1 }; m.Init(d, 2); m.InitRandom(1); float bad[3] = { nan, 1e30f, -inf }, o[1];
		m.Forward(bad, o); CHECK(Neural::IsFiniteF(o[0]));
	}
	// 2) blobs: extended with a VALID crc, wrong width, too small a buffer
	{
		Advisor a; auto good = a.SerializeModel();
		auto ext = good; ext.resize(good.size() - 4); ext.insert(ext.end(), 8, 0); ext.resize(ext.size() + 4); reCrc(ext);
		CHECK(!a.LoadWeights(ext.data(), ext.size()));
		Neural::MLP wide; const int dw[4] = { kFeatureCount + 2, 16, 8, 1 }; wide.Init(dw, 3); wide.InitRandom(3);
		std::vector<uint8_t> wb(wide.SerializedSize()); CHECK(wide.Serialize(wb.data(), wb.size(), kModelId));
		CHECK(!a.LoadWeights(wb.data(), wb.size()));
		Neural::MLP m; m.Init(d3, 3); std::vector<uint8_t> buf(m.SerializedSize()); CHECK(!m.Serialize(buf.data(), buf.size() - 1, kModelId));
	}
	// 3) gate: exact promotion timing, dwell after promotion, clamp, Off stays off
	{
		Neural::AuthorityGate g; for (int i = 0; i < 4; i++) g.Feed(0.5f); CHECK(g.GetMode() == Neural::Mode::Shadow);
		g.Feed(0.5f); CHECK(g.GetMode() == Neural::Mode::Active);
		int k = 0; while (g.GetMode() == Neural::Mode::Active && k < 20) { g.Feed(-1.0f); k++; }
		CHECK(k >= 4 && k <= 10);                                 // demoted, but not before the dwell time
		// dwell: a gate that STARTS in Active mode must not be demoted during its first dwellWindows evaluations, however bad the gain
		Neural::AuthorityGate dw; dw.SetAuto(Neural::Mode::Active);
		for (int i = 0; i < 3; i++)
			dw.Feed(-1.0f);
		CHECK(dw.GetMode() == Neural::Mode::Active);
		dw.Feed(-1.0f); CHECK(dw.GetMode() == Neural::Mode::Shadow);
		Neural::AuthorityGate c; c.Feed(-100.0f); CHECK(c.GainMean() == -1.0f); c.Feed(100.0f); CHECK(std::fabs(c.GainMean()) < 1e-6f);
		Neural::AuthorityGate o; o.SetAuto(Neural::Mode::Off); for (int i = 0; i < 200; i++) o.Feed(0.9f); CHECK(o.GetMode() == Neural::Mode::Off);
	}
	// 4) features: a caller-supplied scan length can not exceed the hard cap
	{
		auto f = ExtractFeatures(0x1000, 0x1000 + 4u * 100000u, [](uint32_t) { return 0x38600001u; }, 1000000);
		CHECK(f.valid && f.v[FEAT_WORDS] == kMaxScanWords);
	}
	// 5) a network that outputs nonsense stays within the configured bound of the baseline
	{
		Neural::MLP m; m.Init(d3, 3); m.B[2][0] = 25.0f; std::vector<uint8_t> b(m.SerializedSize()); m.Serialize(b.data(), b.size(), kModelId);
		Advisor a; CHECK(a.LoadWeights(b.data(), b.size()));
		CodeFeatures f; f.valid = true; f.v[FEAT_WORDS] = 100; const float h = HeuristicLogMs(f);
		CHECK(std::fabs(a.PredictLogMs(f) - (h + AdvisorConfig().maxDevFromBaseline)) < 1e-4f);
	}
	// 6) learning: absurd single label is ignored; Off mode does not learn; TotalCompletion ignores uncompiled jobs
	{
		Advisor a; CodeFeatures f; f.valid = true; f.v[FEAT_WORDS] = 50; f.v[FEAT_COND] = 5;
		const auto s0 = a.SerializeModel(); a.Observe(f, std::exp(9.0f));
		CHECK(a.SerializeModel() == s0 && a.Stats().skippedOutliers == 1);
		a.ForceMode(Neural::Mode::Off); a.Observe(f, 3.0f); CHECK(a.SerializeModel() == s0 && a.Stats().observed == 0);
		std::vector<WindowEntry> w(3); w[0].compiled = true; w[0].durMs = 2; w[1].compiled = false; w[1].durMs = 50; w[2].compiled = true; w[2].durMs = 3;
		CHECK(Advisor::TotalCompletion(w, { 0, 1, 2 }) == 2.0 + 5.0);
	}
	// 7) rollback really restores the last known-good snapshot
	{
		Advisor a; Rng rng(41);
		for (int i = 0; i < 4096; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); a.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Baseline, rng)); }
		const auto snap = a.SerializeSnapshot(); CHECK(!snap.empty());
		int guard = 0; while (a.Stats().rollbacks == 0 && guard++ < 20000) { auto f = NeuroJitSynth::RandomFeatures(rng); a.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Baseline, rng, 2.5f) * 200.0f); }
		CHECK(a.Stats().rollbacks >= 1);
		CHECK(a.SerializeModel() == a.SerializeSnapshot());      // model == snapshot right after the rollback
		std::printf("  rollback after %d absurd labels; snapshot restored bit-exactly\n", guard);
	}
	// 8) windows with too few compiled jobs are not used to judge the model
	{
		FakeEngine e; e.Fill(10, R::Baseline, 0.0f, 51); Advisor a; DrainQueue(e, a); CHECK(a.Gate().Evaluations() == 0 && a.Stats().windows == 1);
	}
	// 9) a device 100x slower than the heuristic assumes: calibration keeps the ordering information (a fixed bound would flatten it)
	{
		Advisor a; Rng rng(52);
		for (int i = 0; i < 6000; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); a.Observe(f, NeuroJitSynth::MeasuredMs(f, R::Slow100, rng)); }
		int ok = 0, tot = 0; Rng r2(53);
		for (int i = 0; i < 4000; i++) { auto x = NeuroJitSynth::RandomFeatures(r2), y = NeuroJitSynth::RandomFeatures(r2); const float tx = NeuroJitSynth::TrueMs(x, R::Slow100), ty = NeuroJitSynth::TrueMs(y, R::Slow100); if (tx < 1.3f * ty && ty < 1.3f * tx) continue; tot++; if ((a.PredictLogMs(x) < a.PredictLogMs(y)) == (tx < ty)) ok++; }
		std::printf("  100x slower device: calibration offset %+.2f ln (true +4.61), ordering accuracy %.1f%%\n", a.Calibration(), 100.0 * ok / tot);
		CHECK(a.Calibration() > 3.5f && (double)ok / tot > 0.95);
	}
	// 10) recovery through the whole scheduler: the prior is exactly wrong, learning must turn it into a real and LASTING gain.
	//     (regression test for a real bug: learning in execution order made the gain decay from +22% to ~1%)
	{
		struct Probe { Advisor* a; int w = 0; float early = 0, late = 0; void OnWindow(const std::vector<WindowEntry>&, double) { w++; if (w == 30) early = a->Gate().GainMean(); if (w == 110) late = a->Gate().GainMean(); } };
		FakeEngine e; e.Fill(60000, R::Inverted, 0.0f, 54, 0.1f); Advisor a; Probe p{ &a }; DrainQueue(e, a, p);
		std::printf("  inverted prior, auto mode: gain per window %+.1f%% at window 30, %+.1f%% at window 110, final mode %s\n", 100.0 * p.early, 100.0 * p.late, Neural::ModeName(a.GetMode()));
		CHECK(a.GetMode() == Neural::Mode::Active && p.early > 0.15f && p.late > 0.8f * p.early);
	}
	// 11) the mode file is applied once per change: an "auto" file must not keep resetting an advisor that has earned control
	{
		namespace fs = std::filesystem; const fs::path dir = fs::temp_directory_path() / "cemu_neural_test3"; std::error_code ec; fs::remove_all(dir, ec);
		Advisor a; Session s(a, dir); { std::ofstream(dir / "mode.txt") << "auto"; } s.ReadModeFile();
		FakeEngine e; e.Fill(20000, R::Baseline, 0.0f, 55); DrainQueue(e, a);
		CHECK(a.GetMode() == Neural::Mode::Active);
		s.ReadModeFile(); s.ReadModeFile(); CHECK(a.GetMode() == Neural::Mode::Active);
		fs::remove_all(dir, ec);
	}
}

// ---------------------------------------------------------------------------------------------
static void testNetworkAddsValue()
{
	std::printf("[VALUE: does the network add anything beyond the calibrated heuristic?]\n");
	using R = NeuroJitSynth::Regime;
	// control group: heuristic with the BEST possible constant calibration (what the 'model off' path can do at most)
	auto evalRegime = [](R regime, const char* name, int obs) {
		Advisor adv; Rng rng(61);
		for (int i = 0; i < obs; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, regime, rng)); }
		Rng r(62); double off = 0; const int n = 4000; std::vector<CodeFeatures> fs_; for (int i = 0; i < n; i++) { fs_.push_back(NeuroJitSynth::RandomFeatures(r)); off += std::log(NeuroJitSynth::TrueMs(fs_.back(), regime)) - HeuristicLogMs(fs_.back()); }
		off /= n; double eNet = 0, eCal = 0; int okNet = 0, okHeur = 0, tot = 0;
		for (int i = 0; i < n; i++) { const double t = std::log(NeuroJitSynth::TrueMs(fs_[i], regime)); eNet += std::fabs(adv.PredictLogMs(fs_[i]) - t); eCal += std::fabs(HeuristicLogMs(fs_[i]) + off - t); }
		for (int i = 0; i + 1 < n; i += 2) { const float a = NeuroJitSynth::TrueMs(fs_[i], regime), b = NeuroJitSynth::TrueMs(fs_[i + 1], regime); if (a < 1.3f * b && b < 1.3f * a) continue; tot++; if ((adv.PredictLogMs(fs_[i]) < adv.PredictLogMs(fs_[i + 1])) == (a < b)) okNet++; if ((HeuristicLogMs(fs_[i]) < HeuristicLogMs(fs_[i + 1])) == (a < b)) okHeur++; }
		std::printf("  %-9s after %5d observations: error network %.3f vs calibrated heuristic %.3f ln; ordering accuracy network %.1f%% vs heuristic %.1f%%\n", name, obs, eNet / n, eCal / n, 100.0 * okNet / tot, 100.0 * okHeur / tot);
		return std::make_tuple(eNet / n, eCal / n, (double)okNet / tot, (double)okHeur / tot);
	};
	auto [eN, eC, aN, aH] = evalRegime(R::Device, "Device", 30000);
	CHECK(eN < 0.8 * eC);        // better calibrated than the best constant correction of the heuristic ...
	CHECK(aN > aH - 0.01);       // ... and not worse at ordering (size already explains almost everything here)
	auto [eS, eSC, aS, aSH] = evalRegime(R::Spiky, "Spiky", 30000);
	CHECK(eS < 0.5 * eSC);       // when cost is NOT driven by size the network must clearly beat the heuristic
	CHECK(aS > aSH + 0.05);
	auto [eN2, eC2, aN2, aH2] = evalRegime(R::Baseline, "Baseline", 30000);
	CHECK(eN2 < 1.3 * eC2 + 0.05); // where the heuristic is already right the network must not be much worse
	CHECK(aN2 > aH2 - 0.02);
}

// ---------------------------------------------------------------------------------------------
static void testSession()
{
	std::printf("[SESSION: files]\n");
	namespace fs = std::filesystem;
	const fs::path dir = fs::temp_directory_path() / "cemu_neural_test";
	std::error_code ec; fs::remove_all(dir, ec);
	{
		Advisor adv; Session s(adv, dir);
		CHECK(!s.LoadedFromFile());
		Rng rng(1); for (int i = 0; i < 500; i++) { auto f = NeuroJitSynth::RandomFeatures(rng); adv.Observe(f, NeuroJitSynth::MeasuredMs(f, NeuroJitSynth::Regime::Device, rng)); }
		std::vector<WindowEntry> w(1); w[0].f = NeuroJitSynth::RandomFeatures(rng); w[0].compiled = true; w[0].durMs = 2.0f; s.OnWindow(w, 0);
		s.Flush();
	}
	CHECK(fs::exists(dir / "jit_cost.weights")); CHECK(fs::exists(dir / "report.txt")); CHECK(fs::exists(dir / "jit_cost_trace.csv"));
	{
		Advisor adv2; Session s2(adv2, dir); CHECK(s2.LoadedFromFile());      // learned weights come back after a restart
		std::string text = "off"; { std::ofstream(dir / "mode.txt") << text; } s2.ReadModeFile(); CHECK(adv2.GetMode() == Neural::Mode::Off);
		{ std::ofstream(dir / "mode.txt") << "ACTIVE\n"; } s2.ReadModeFile(); CHECK(adv2.GetMode() == Neural::Mode::Active);
		{ std::ofstream(dir / "mode.txt") << "nonsense"; } s2.ReadModeFile(); CHECK(adv2.GetMode() == Neural::Mode::Active); // unknown text ignored
		{ std::ofstream(dir / "mode.txt") << "auto"; } s2.ReadModeFile(); CHECK(adv2.GetMode() == Neural::Mode::Shadow && adv2.Gate().IsAuto());
	}
	// damaged weights file -> ignored, built-in priors used
	{
		{ std::ofstream f(dir / "jit_cost.weights", std::ios::binary | std::ios::trunc); f << "this is not a weight file"; }
		Advisor adv3; Session s3(adv3, dir); CHECK(!s3.LoadedFromFile()); CHECK(adv3.Stats().modelLoaded);
		std::vector<uint8_t> big(100000, 0xAB); { std::ofstream f(dir / "jit_cost.weights", std::ios::binary | std::ios::trunc); f.write((const char*)big.data(), big.size()); }
		Advisor adv4; Session s4(adv4, dir); CHECK(!s4.LoadedFromFile());
	}
	// directory that cannot be created (a file is in the way): everything degrades silently
	{
		const fs::path blocker = fs::temp_directory_path() / "cemu_neural_blocker"; fs::remove_all(blocker, ec); { std::ofstream(blocker) << "x"; }
		Advisor adv5; Session s5(adv5, blocker / "sub"); std::vector<WindowEntry> w(1); s5.OnWindow(w, 0); s5.Flush(); CHECK(adv5.Stats().modelLoaded);
		fs::remove(blocker, ec);
	}
	// trace cap: the csv never grows beyond the configured size
	{
		fs::remove_all(dir, ec); SessionConfig cfg; cfg.traceCapBytes = 4096; Advisor adv6; Session s6(adv6, dir, cfg); Rng rng(2);
		std::vector<WindowEntry> w(64); for (auto& x : w) { x.f = NeuroJitSynth::RandomFeatures(rng); x.compiled = true; x.durMs = 1.0f; }
		for (int i = 0; i < 100; i++) s6.OnWindow(w, 0);
		CHECK(fs::file_size(dir / "jit_cost_trace.csv") < 4096 + 64 * 200);
	}
	fs::remove_all(dir, ec);
}



// ---------------------------------------------------------------------------------------------
static void testWindowing()
{
	std::printf("[WINDOW SIZE: counterfactual report and tunable window]\n");
	using R = NeuroJitSynth::Regime;
	// 1) the group evaluator equals an independent O(n^2) formula, and its delay bound holds
	{
		Rng rng(81); int bad = 0;
		for (int t = 0; t < 300; t++)
		{
			const size_t n = 1 + rng.NextU64() % 120; std::vector<float> pred(n), dur(n);
			for (size_t i = 0; i < n; i++) { pred[i] = (float)(rng.NextU64() % 20); dur[i] = rng.NextRange(0.1f, 50.0f); } // many ties on purpose
			const GroupResult r = Advisor::EvaluateGroup(pred.data(), dur.data(), n);
			double tF = 0, tP = 0, worst = 0, total = 0;
			for (size_t i = 0; i < n; i++)
			{
				double cf = 0, cp = 0; total += dur[i];
				for (size_t j = 0; j <= i; j++) cf += dur[j];
				for (size_t j = 0; j < n; j++) if (pred[j] < pred[i] || (pred[j] == pred[i] && j <= i)) cp += dur[j];  // stable order
				tF += cf; tP += cp; worst = std::max(worst, cp - cf);
			}
			if (std::fabs(r.tFifo - tF) > 1e-6 * tF || std::fabs(r.tPred - tP) > 1e-6 * tP || std::fabs(r.worstDelayMs - worst) > 1e-6 * (worst + 1)) bad++;
			CHECK(r.worstDelayMs >= 0 && r.worstDelayMs <= total + 1e-6);   // nobody finishes later than the end of their group
		}
		CHECK(bad == 0);
		std::vector<float> sorted = { 1, 2, 3, 4 }, d = { 5, 6, 7, 8 };
		const GroupResult same = Advisor::EvaluateGroup(sorted.data(), d.data(), 4); CHECK(same.tFifo == same.tPred && same.worstDelayMs == 0); // already in predicted order
		CHECK(Advisor::EvaluateGroup(nullptr, nullptr, 0).tFifo == 0);
	}
	// 2) the advisor's counterfactual numbers agree with what really happens when the window is made bigger
	{
		Advisor shadow; shadow.ForceMode(Neural::Mode::Shadow);
		FakeEngine e; e.Fill(4096, R::Baseline, 0.0f, 90, 0.05f); DrainQueue(e, shadow);
		std::printf("  one shadow run with window 512 reports the saved share of total waiting time for 512/1024/2048/4096: %.1f%% / %.1f%% / %.1f%% / %.1f%%\n", 100.0 * shadow.Stats().windowStats[0].gain.value, 100.0 * shadow.Stats().windowStats[1].gain.value, 100.0 * shadow.Stats().windowStats[2].gain.value, 100.0 * shadow.Stats().windowStats[3].gain.value);
		FakeEngine ref; ref.Fill(4096, R::Baseline, 0.0f, 90, 0.05f);
		double tFifo = 0, t = 0; for (size_t i = 0; i < 4096; i++) { t += ref.costOf[i]; tFifo += t; }
		for (int m = 0; m < kWindowMultiples; m++)
		{
			const uint32_t w = 512u << m;
			Advisor act; act.ForceMode(Neural::Mode::Active); act.SetWindow(w);
			FakeEngine b; b.Fill(4096, R::Baseline, 0.0f, 90, 0.05f); DrainQueue(b, act);
			double tb = 0; t = 0; for (size_t i = 0; i < 4096; i++) { t += b.costOf[(b.compileLog[i] - b.base) / 4]; tb += t; }
			const double measured = (tFifo - tb) / tFifo, predicted = shadow.Stats().windowStats[m].gain.value;
			std::printf("  window %4u: counterfactual %.1f%%  really running it %.1f%%   (worst delay of one function: %.0f ms)\n", w, 100.0 * predicted, 100.0 * measured, (double)shadow.Stats().windowStats[m].maxDelayMs);
			CHECK(std::fabs(measured - predicted) < 0.01);
			if (m > 0) CHECK(shadow.Stats().windowStats[m].gain.value > shadow.Stats().windowStats[m - 1].gain.value);       // bigger windows save more ...
			if (m > 0) CHECK(shadow.Stats().windowStats[m].maxDelayMs >= shadow.Stats().windowStats[m - 1].maxDelayMs);      // ... and delay the unluckiest function more
		}
	}
	// 2b) a burst longer than one chunk of 8 windows: the time already spent earlier in the burst must be part of the baseline
	{
		Advisor shadow; shadow.ForceMode(Neural::Mode::Shadow);
		FakeEngine e; e.Fill(6144, R::Baseline, 0.0f, 92, 0.05f); DrainQueue(e, shadow);   // 12 windows of 512: a chunk of 8 and a chunk of 4
		FakeEngine ref; ref.Fill(6144, R::Baseline, 0.0f, 92, 0.05f);
		double tFifo = 0, t = 0; for (size_t i = 0; i < 6144; i++) { t += ref.costOf[i]; tFifo += t; }
		for (int m = 0; m < 3; m++)
		{
			Advisor act; act.ForceMode(Neural::Mode::Active); act.SetWindow(512u << m);
			FakeEngine b; b.Fill(6144, R::Baseline, 0.0f, 92, 0.05f); DrainQueue(b, act);
			double tb = 0; t = 0; for (size_t i = 0; i < 6144; i++) { t += b.costOf[(b.compileLog[i] - b.base) / 4]; tb += t; }
			const double measured = (tFifo - tb) / tFifo, predicted = shadow.Stats().windowStats[m].gain.value;
			std::printf("  12-window burst, window %4u: counterfactual %.1f%%  really running it %.1f%%\n", 512u << m, 100.0 * predicted, 100.0 * measured);
			CHECK(std::fabs(measured - predicted) < 0.01);
		}
	}
	// 3) windows of different bursts are never merged
	{
		Advisor a; a.ForceMode(Neural::Mode::Shadow);
		for (int burst = 0; burst < 2; burst++) { FakeEngine e; e.Fill(3 * 512, R::Baseline, 0.0f, 91 + burst); DrainQueue(e, a); }
		const auto& s = a.Stats().windowStats;
		std::printf("  two bursts of 3 windows: groups of 1/2/4/8 windows = %u / %u / %u / %u\n", s[0].groups, s[1].groups, s[2].groups, s[3].groups);
		CHECK(s[0].groups == 4 && s[1].groups == 2 && s[2].groups == 0 && s[3].groups == 0); // per burst: 3 windows -> prefix of 2 (2 groups of 1, 1 group of 2)
	}
	// 4) the window is respected and clamped
	{
		struct Sizes { size_t biggest = 0; int windows = 0; void OnWindow(const std::vector<WindowEntry>& e, double) { biggest = std::max(biggest, e.size()); windows++; } };
		Advisor a; a.SetWindow(100); Sizes s; FakeEngine e; e.Fill(1000, R::Baseline, 0.0f, 95); DrainQueue(e, a, s);
		CHECK(s.biggest == 100 && s.windows == 10);
		a.SetWindow(1); CHECK(a.Window() == kMinWindow); a.SetWindow(10000000); CHECK(a.Window() == kMaxWindowLimit);
		a.SetWindow(256); CHECK(a.Stats().windowStats[0].groups == 0); // changing the size starts the statistics afresh
		CHECK(a.BuildReport().find("window size now 256") != std::string::npos);
	}
	// 5) mode.txt: "mode [window]"
	{
		int md, w;
		CHECK(ParseModeText("active 2048", md, &w) && md == 2 && w == 2048);
		CHECK(ParseModeText("auto", md, &w) && md == 3 && w == -1);
		CHECK(ParseModeText("off abc", md, &w) && md == 0 && w == -1);
		CHECK(ParseModeText("active 2048x", md, &w) && md == 2 && w == -1);     // not a clean number: ignored
		CHECK(ParseModeText("SHADOW   64\r\n", md, &w) && md == 1 && w == 64);
		CHECK(ParseModeText("active 99999999999999", md, &w) && w == -1);       // absurd length: ignored
		CHECK(ParseModeText("active", md) && md == 2);                          // old two-argument form still works
		namespace fs = std::filesystem; const fs::path dir = fs::temp_directory_path() / "cemu_neural_test4"; std::error_code ec; fs::remove_all(dir, ec);
		Advisor a; Session s(a, dir);
		{ std::ofstream(dir / "mode.txt") << "active 2048"; } s.ReadModeFile(); CHECK(a.Window() == 2048 && a.GetMode() == Neural::Mode::Active);
		{ std::ofstream(dir / "mode.txt") << "active 99999"; } s.ReadModeFile(); CHECK(a.Window() == kMaxWindowLimit);
		{ std::ofstream(dir / "mode.txt") << "shadow"; } s.ReadModeFile(); CHECK(a.Window() == kMaxWindowLimit && a.GetMode() == Neural::Mode::Shadow); // no number: window kept
		fs::remove_all(dir, ec);
	}
}

// ---------------------------------------------------------------------------------------------
static void testSoak()
{
	std::printf("[SOAK: one advisor, 160k functions, the workload changes four times]\n");
	using R = NeuroJitSynth::Regime;
	const struct { R r; const char* name; } phases[] = { { R::Baseline, "Baseline" }, { R::Spiky, "Spiky" }, { R::Inverted, "Inverted" }, { R::Device, "Device" }, { R::Slow100, "Slow100" }, { R::Spiky, "Spiky again" } };
	Advisor adv; double worstRatio = 0;
	for (int p = 0; p < 6; p++)
	{
		FakeEngine e; e.Fill(27000, phases[p].r, 0.05f, 700 + p, 0.15f); DrainQueue(e, adv);
		FakeEngine f; f.Fill(27000, phases[p].r, 0.05f, 700 + p, 0.15f); Advisor off; off.ForceMode(Neural::Mode::Off); DrainQueue(f, off);
		const double ratio = totalCompletion(e) / totalCompletion(f); worstRatio = std::max(worstRatio, ratio);
		std::printf("  %-12s total completion %.4fx of FIFO | mode %-6s gain mean %+5.1f%% | transitions so far %u, rollbacks %llu\n", phases[p].name, ratio, Neural::ModeName(adv.GetMode()), 100.0 * adv.Gate().GainMean(), adv.Gate().Transitions(), (unsigned long long)adv.Stats().rollbacks);
		CHECK(ratio < 1.01);                       // never meaningfully worse than doing nothing, in any phase
	}
	CHECK(adv.Gate().Transitions() <= 14);           // no flapping over the whole run
	CHECK(adv.Stats().rollbacks <= 20);
	Neural::MLP probe; const int d3[4] = { kFeatureCount, 16, 8, 1 }; auto blob = adv.SerializeModel();
	CHECK(probe.Deserialize(blob.data(), blob.size(), kModelId, d3, 3) && probe.WeightsValid());
	std::printf("  worst phase: %.4fx of FIFO\n", worstRatio);
}

// ---------------------------------------------------------------------------------------------
static void testAggressive()
{
	std::printf("[AGGRESSIVE: 8 independent advisors on 8 threads]\n");
	std::atomic<int> failures{ 0 };
	std::vector<std::thread> th;
	for (int t = 0; t < 8; t++) th.emplace_back([&, t] {
		for (int round = 0; round < 6; round++)
		{
			FakeEngine e; e.Fill(3000, (round % 2) ? NeuroJitSynth::Regime::Device : NeuroJitSynth::Regime::Baseline, 0.1f, 100 + t * 10 + round);
			Advisor adv; if (round % 3 == 0) adv.ForceMode(Neural::Mode::Active);
			if (round == 5) e.stopAfterCompiles = 1000;
			DrainQueue(e, adv);
			std::set<uint32_t> u(e.compileLog.begin(), e.compileLog.end());
			if (u.size() != e.compileLog.size()) failures++;
			if (round != 5 && !e.visited.empty()) failures++;
		}
	});
	for (auto& x : th) x.join();
	CHECK(failures == 0);
}

static void testPerformance()
{
	std::printf("[PERFORMANCE (this machine, x86; phones are slower)]\n");
	Advisor adv; Rng rng(1);
	std::vector<CodeFeatures> fs_; for (int i = 0; i < 1000; i++) fs_.push_back(NeuroJitSynth::RandomFeatures(rng));
	double t0 = NowMsReal(); float sink = 0;
	for (int r = 0; r < 200; r++) for (auto& f : fs_) sink += adv.PredictLogMs(f);
	double t1 = NowMsReal();
	for (int r = 0; r < 50; r++) for (auto& f : fs_) adv.Observe(f, 1.0f + (float)(rng.NextU64() % 100) * 0.1f);
	double t2 = NowMsReal();
	std::vector<uint32_t> code(4096, 0x38600001);
	for (int r = 0; r < 200; r++) { auto f = ExtractFeatures(0x1000, 0x1000 + 4 * 4096, [&](uint32_t a) { return code[(a - 0x1000) / 4]; }); sink += f.v[0]; }
	double t3 = NowMsReal();
	std::printf("  predict %.2f us, learn step %.2f us, feature scan of 4096 words %.1f us (sink %.1f)\n", 1000.0 * (t1 - t0) / 200000, 1000.0 * (t2 - t1) / 50000, 1000.0 * (t3 - t2) / 200, sink);
	CHECK((t1 - t0) / 200000 < 0.05); // < 50 us per prediction, orders of magnitude below a compile
}

int main()
{
	testSimple(); testWrongInput(); testLearning(); testGate(); testScheduler(); testSession(); testGaps(); testStrength(); testNetworkAddsValue(); testWindowing(); testSoak(); testAggressive(); testPerformance();
	std::printf(g_fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", g_fail);
	return g_fail ? 1 : 0;
}
