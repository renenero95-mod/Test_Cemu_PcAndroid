// Virtual-time MODEL of the JIT worker loop (not the engine code): compares the old loop
// (sleep 10 ms, at most 64 pops per batch, stale pops also consume the budget) with the new one
// (sleep 10 ms only when the queue is empty, drain everything). Checks correctness properties and prints warm-up times.
#include <cstdio>
#include <deque>
#include <set>
#include <vector>
#include <random>
struct Entry { int id; bool stale; bool high; };
struct Result { double ms; std::vector<int> compiled; };
static const double SLEEP_MS = 10.0, STALE_POP_MS = 0.0005;

static Result run(const std::vector<Entry>& all, double compileMs, bool capped)
{
	std::deque<Entry> hi, lo; for (auto& e : all) (e.high ? hi : lo).push_back(e);
	Result r{ 0, {} }; double t = 0;
	while (!hi.empty() || !lo.empty())
	{
		t += SLEEP_MS;                       // top-of-loop sleep
		int pops = 0;
		while (true)
		{
			if (capped && pops >= 64) break;
			pops++;
			Entry e;
			if (!hi.empty()) { e = hi.front(); hi.pop_front(); }
			else if (!lo.empty()) { e = lo.front(); lo.pop_front(); }
			else break;
			if (e.stale) { t += STALE_POP_MS; continue; }
			t += compileMs; r.compiled.push_back(e.id);
		}
	}
	r.ms = t; return r;
}
int main()
{
	int fail = 0; std::mt19937 rng(7);
	std::printf("burst of 20000 queued addresses (game start)\n");
	std::printf("%-12s %-8s %12s %12s %8s\n", "compile(ms)", "stale%", "old(s)", "new(s)", "speedup");
	for (double cms : { 0.2, 0.5, 1.0, 2.0 })
		for (int stalePct : { 0, 50, 80 })
		{
			std::vector<Entry> v; for (int i = 0; i < 20000; i++) v.push_back({ i, (int)(rng() % 100) < stalePct, i % 50 == 0 });
			auto a = run(v, cms, true), b = run(v, cms, false);
			// same set of compiled functions, and the high-priority ones come first in both
			std::set<int> sa(a.compiled.begin(), a.compiled.end()), sb(b.compiled.begin(), b.compiled.end());
			if (sa != sb || a.compiled.size() != b.compiled.size()) { std::printf("  FAIL: different work done\n"); fail++; }
			if (b.ms > a.ms) { std::printf("  FAIL: new loop slower\n"); fail++; }
			bool seenLow = false; for (int id : b.compiled) { bool hi = (id % 50 == 0); if (!hi) seenLow = true; else if (seenLow) { std::printf("  FAIL: priority order\n"); fail++; break; } }
			std::printf("%-12.1f %-8d %12.1f %12.1f %7.2fx\n", cms, stalePct, a.ms / 1000, b.ms / 1000, a.ms / b.ms);
		}
	std::printf("small queue (10 entries): old %.1f ms, new %.1f ms\n", run({ {1,false,false} }, 0.5, true).ms, run({ {1,false,false} }, 0.5, false).ms);
	std::printf(fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", fail);
	return fail ? 1 : 0;
}
