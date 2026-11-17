// Compiles and runs the REAL engine hook code that lives in PPCRecompiler.cpp (extracted at build time, see run_tests.sh)
// against a stub of the engine types, together with the real PPC analyzer and the real scheduler/advisor.
// This catches type errors and logic errors in the glue code that the full Cemu build would otherwise only reveal in CI.
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <map>
#include <queue>
#include <set>
#include <vector>
#include "Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.h"

#define CEMU_NEURAL 1
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuroJitSession.h"
#include "NeuroJitSynthetic.h"

// ---- stubs with the same shapes as the engine ------------------------------------------------
using uint32 = uint32_t; using uint64 = uint64_t;
using MPTR = uint32;
typedef void (*PPCREC_JUMP_ENTRY)();
struct PPCRecompilerInstanceData_t { PPCREC_JUMP_ENTRY ppcRecompilerDirectJumpTable[1 << 16]; };
static std::unique_ptr<PPCRecompilerInstanceData_t> s_data;
PPCRecompilerInstanceData_t* ppcRecompilerInstanceData;
static void visitedFn() {} static void unvisitedFn() {} static void compiledFn() {}
void (*PPCRecompiler_leaveRecompilerCode_visited)() = visitedFn;
void (*PPCRecompiler_leaveRecompilerCode_unvisited)() = unvisitedFn;
std::atomic_bool s_recompilerThreadStopSignal{ false };
class FSpinlock { public: void lock() const { while (f.test_and_set(std::memory_order_acquire)) {} } void unlock() const { f.clear(std::memory_order_release); } mutable std::atomic_flag f = ATOMIC_FLAG_INIT; };
struct { FSpinlock recompilerSpinlock; std::queue<MPTR> targetQueue; std::queue<MPTR> targetQueueHigh; } PPCRecompilerState;

static std::vector<uint32_t> g_code; static uint32_t g_codeBase = 0x1000; static uint32_t g_minRead = 0xFFFFFFFFu, g_maxRead = 0;
static std::set<uint32_t> g_readAddrs;
uint32_t memory_readU32(uint32_t a) { g_minRead = std::min(g_minRead, a); g_maxRead = std::max(g_maxRead, a); g_readAddrs.insert(a); return g_code[(a - g_codeBase) / 4]; }
static std::vector<MPTR> g_compileOrder; static std::set<MPTR> g_failing;
static std::map<MPTR, MPTR> g_invalidates; // compiling key invalidates (un-visits) value, like invalidateRange() would
static int g_staleCompiles = 0;
void PPCRecompiler_recompileAtAddress(MPTR a)
{
	if (ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[a / 4] != PPCRecompiler_leaveRecompilerCode_visited)
		g_staleCompiles++; // the engine asserts that only 'visited' addresses are compiled
	g_compileOrder.push_back(a);
	ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[a / 4] = g_failing.count(a) ? PPCRecompiler_leaveRecompilerCode_visited : compiledFn;
	auto it = g_invalidates.find(a);
	if (it != g_invalidates.end() && ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[it->second / 4] == PPCRecompiler_leaveRecompilerCode_visited)
		ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[it->second / 4] = PPCRecompiler_leaveRecompilerCode_unvisited;
}

#include "hooks_extract.inc" // generated: NeuroJitEngineHooks from PPCRecompiler.cpp

static int g_fail = 0;
#define CHECK(c) do { if(!(c)) { std::printf("  FAIL line %d: %s\n", __LINE__, #c); g_fail++; } } while(0)

int main()
{
	std::printf("[ENGINE HOOKS (real glue code, stub engine)]\n");
	s_data = std::make_unique<PPCRecompilerInstanceData_t>();
	ppcRecompilerInstanceData = s_data.get();
	for (auto& e : s_data->ppcRecompilerDirectJumpTable) e = PPCRecompiler_leaveRecompilerCode_unvisited;

	// a "game": 600 functions of varying size laid out back to back in one executable section
	Neural::Rng rng(5);
	std::vector<uint32_t> starts;
	for (int i = 0; i < 600; i++)
	{
		starts.push_back(g_codeBase + 4u * (uint32_t)g_code.size());
		const int len = 4 + (int)(std::exp(rng.NextRange(0.0f, 5.5f)));
		for (int k = 0; k < len - 1; k++) g_code.push_back(0x38600001u + (uint32_t)(k & 7)); // li variants
		g_code.push_back(0x4E800020u);                                                       // blr
	}
	g_code.push_back(0); g_code.push_back(0);
	std::vector<uint8_t> raw(g_code.size() * 4);
	for (size_t i = 0; i < g_code.size(); i++) { uint32_t w = g_code[i]; raw[4 * i] = w >> 24; raw[4 * i + 1] = w >> 16; raw[4 * i + 2] = w >> 8; raw[4 * i + 3] = (uint8_t)w; }
	PPCPreExecutionAnalyzer::ClearTitle();
	CHECK(PPCPreExecutionAnalyzer::ScanExecutableSection(g_codeBase, raw.data(), raw.size(), "test"));
	const uint32_t sectionEnd = g_codeBase + (uint32_t)raw.size();

	// queue them: 10% high priority, a few already compiled/invalidated, one address OUTSIDE any known section
	std::set<MPTR> highSet, stale;
	for (size_t i = 0; i < starts.size(); i++)
	{
		const MPTR a = starts[i];
		if (i % 17 == 3) { ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[a / 4] = compiledFn; stale.insert(a); }   // compiled by someone else
		else ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[a / 4] = PPCRecompiler_leaveRecompilerCode_visited;
		if (i % 10 == 0) { PPCRecompilerState.targetQueueHigh.push(a); highSet.insert(a); } else PPCRecompilerState.targetQueue.push(a);
	}
	g_failing.insert(starts[7]); g_failing.insert(starts[40]); // two compiles that fail (function stays 'visited')
	// 60 compiles invalidate another queued function (e.g. self-modifying code): the victim must then NOT be compiled if it is still waiting
	for (int i = 0; i < 60; i++) g_invalidates[starts[100 + i * 7]] = starts[101 + i * 7 + (i % 5)];
	const MPTR outside = 0x8000;
	ppcRecompilerInstanceData->ppcRecompilerDirectJumpTable[outside / 4] = PPCRecompiler_leaveRecompilerCode_visited;
	PPCRecompilerState.targetQueue.push(outside);

	NeuroJit::Advisor advisor; advisor.ForceMode(Neural::Mode::Active);
	NeuroJitEngineHooks hooks; NeuroJit::NullSink sink;
	NeuroJit::DrainQueue(hooks, advisor, sink);

	std::set<MPTR> compiled(g_compileOrder.begin(), g_compileOrder.end());
	CHECK(compiled.size() == g_compileOrder.size());                       // nothing compiled twice
	for (MPTR a : stale) CHECK(!compiled.count(a));                         // stale entries skipped
	CHECK(compiled.count(outside) == 1);                                    // unknown-section function still compiled (just no features)
	CHECK(PPCRecompilerState.targetQueue.empty() && PPCRecompilerState.targetQueueHigh.empty());
	size_t invalidatedBeforeTurn = 0;
	{
		std::set<MPTR> victims;   // distinct victims that were still waiting, and were invalidated before their turn (never compiled)
		for (auto& kv : g_invalidates) if (!compiled.count(kv.second) && !stale.count(kv.second)) victims.insert(kv.second);
		invalidatedBeforeTurn = victims.size();
	}
	std::printf("  %zu queued functions were invalidated while waiting and correctly skipped; stale compiles: %d\n", invalidatedBeforeTurn, g_staleCompiles);
	CHECK(g_staleCompiles == 0);                                            // never compiles an address that is no longer 'visited'
	CHECK(invalidatedBeforeTurn > 0);                                       // the scenario really happened
	for (MPTR a : stale) CHECK(!g_readAddrs.count(a));                      // code of stale entries was not even analysed
	CHECK(advisor.Stats().compiles == compiled.size() - g_failing.size());  // failed compiles are not counted as successes
	size_t expected = starts.size() - stale.size() + 1 - invalidatedBeforeTurn;
	CHECK(compiled.size() == expected);
	bool seenLow = false, ok = true;
	for (MPTR a : g_compileOrder) { if (!highSet.count(a)) seenLow = true; else if (seenLow) ok = false; }
	CHECK(ok);                                                              // high priority class first
	CHECK(g_minRead >= g_codeBase && g_maxRead < sectionEnd);              // guest memory only read inside the registered section
	std::printf("  compiled %zu of %zu queued, guest reads within [0x%X, 0x%X) as required, mode %s\n", compiled.size(), starts.size() + 1, g_minRead, g_maxRead + 4, Neural::ModeName(advisor.GetMode()));
	// the order inside the low-priority class is cheapest-first by predicted cost: the first compiled low entries are small functions
	size_t firstLow = 0; while (firstLow < g_compileOrder.size() && highSet.count(g_compileOrder[firstLow])) firstLow++;
	auto sizeOf = [&](MPTR a) { return NeuroJit::ExtractFeatures(a, sectionEnd, [&](uint32_t x) { return g_code[(x - g_codeBase) / 4]; }).v[NeuroJit::FEAT_WORDS]; };
	double firstAvg = 0, lastAvg = 0; const size_t n = 40;
	for (size_t i = 0; i < n; i++) { firstAvg += sizeOf(g_compileOrder[firstLow + i]); lastAvg += sizeOf(g_compileOrder[g_compileOrder.size() - 2 - i]); }
	std::printf("  average size of the first %zu low-priority compiles %.0f words vs the last %zu: %.0f words\n", n, firstAvg / n, n, lastAvg / n);
	CHECK(firstAvg < lastAvg);

	// stop signal honoured by the hook
	s_recompilerThreadStopSignal = true; CHECK(hooks.StopRequested()); s_recompilerThreadStopSignal = false;
	std::printf(g_fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", g_fail);
	return g_fail ? 1 : 0;
}
