// Cost of the always-on profiling scope timer (used per HLE call) on 1 and 3 threads sharing the same counter.
#define CEMU_PROFILING 1
#include "util/CemuProfiling.h"
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>
static volatile uint64_t sink;
static double run(int threads, bool profiled, int iters)
{
	auto t0 = std::chrono::steady_clock::now();
	std::vector<std::thread> v;
	for (int t = 0; t < threads; t++) v.emplace_back([&] {
		for (int i = 0; i < iters; i++) {
			if (profiled) { CemuProfiling::ScopedTimer st(CemuProfiling::HLE()); sink = sink + i; }
			else sink = sink + i;
		}
	});
	for (auto& x : v) x.join();
	return std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - t0).count() / iters;
}
int main()
{
	const int N = 3000000;
	for (int th : { 1, 3 })
		std::printf("threads=%d  plain=%.1f ns/call  with ScopedTimer=%.1f ns/call\n", th, run(th, false, N), run(th, true, N));
}
