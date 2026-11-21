// Benchmark: OLD analyzer (one unordered_map node per instruction, copied from the original code) vs NEW (section summary).
// Usage: bench_analyzer old|new [megabytes]
#include "Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.h"
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <random>
#include <shared_mutex>
#include <unordered_map>
#include <vector>
#include <sys/resource.h>

namespace OldAnalyzer {
	struct InstructionMetadata { uint32_t guestPc; uint32_t rawOpcode; uint8_t primaryOpcode; uint16_t secondaryOpcode; };
	static std::unordered_map<uint32_t, InstructionMetadata> s_db;
	static std::shared_mutex s_dbMutex;
	void Scan(uint32_t guestBase, const uint8_t* bytes, size_t size) {
		std::unique_lock lock(s_dbMutex);
		for (size_t offset = 0; offset + 4 <= size; offset += 4) {
			uint32_t raw = (uint32_t(bytes[offset]) << 24) | (uint32_t(bytes[offset+1]) << 16) | (uint32_t(bytes[offset+2]) << 8) | uint32_t(bytes[offset+3]);
			InstructionMetadata meta; meta.guestPc = guestBase + offset; meta.rawOpcode = raw; meta.primaryOpcode = (raw >> 26) & 0x3F; meta.secondaryOpcode = 0;
			s_db[meta.guestPc] = meta;
		}
	}
	bool Lookup(uint32_t pc) { std::shared_lock lock(s_dbMutex); return s_db.find(pc) != s_db.end(); }
}

static long rssMB() { rusage u; getrusage(RUSAGE_SELF, &u); return u.ru_maxrss / 1024; }

int main(int argc, char** argv)
{
	bool useOld = argc > 1 && !strcmp(argv[1], "old");
	size_t mb = argc > 2 ? atoi(argv[2]) : 24;
	std::vector<uint8_t> code(mb << 20);
	std::mt19937 rng(1); for (auto& b : code) b = (uint8_t)rng();
	long base = rssMB();
	auto t0 = std::chrono::steady_clock::now();
	if (useOld) OldAnalyzer::Scan(0x02000000, code.data(), code.size());
	else PPCPreExecutionAnalyzer::ScanExecutableSection(0x02000000, code.data(), code.size(), "bench");
	auto t1 = std::chrono::steady_clock::now();
	// 1M lookups
	uint64_t hits = 0;
	for (int i = 0; i < 1000000; i++) { uint32_t pc = 0x02000000 + ((rng() % (code.size() / 4)) * 4); hits += useOld ? OldAnalyzer::Lookup(pc) : PPCPreExecutionAnalyzer::IsKnownCode(pc); }
	auto t2 = std::chrono::steady_clock::now();
	auto ms = [](auto a, auto b) { return (long)std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count(); };
	std::printf("%s  code=%zuMB  scan=%ldms  1M lookups=%ldms  hits=%llu  RSS before=%ldMB after=%ldMB  (analyzer cost ~%ldMB)\n",
		useOld ? "OLD" : "NEW", mb, ms(t0, t1), ms(t1, t2), (unsigned long long)hits, base, rssMB(), rssMB() - base);
}
