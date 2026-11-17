// Standalone tests for PPCPreExecutionAnalyzer (no Cemu dependencies needed).
// Build & run: see tests/standalone/run_tests.sh
// Levels: SIMPLE, WRONG input, HARD (property test vs reference model), AGGRESSIVE (multi-thread stress).
#include "Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.h"
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <random>
#include <set>
#include <thread>
#include <vector>

using namespace PPCPreExecutionAnalyzer;

static int g_fail = 0;
#define CHECK(c) do { if(!(c)) { std::printf("  FAIL %s:%d  %s\n", __FILE__, __LINE__, #c); g_fail++; } } while(0)

static void putBE(std::vector<uint8_t>& v, uint32_t raw) { v.push_back(raw >> 24); v.push_back(raw >> 16); v.push_back(raw >> 8); v.push_back(raw); }

static void testSimple()
{
	std::printf("[SIMPLE]\n");
	ClearTitle();
	std::vector<uint8_t> code;
	putBE(code, 0x38600001); // li r3,1
	putBE(code, 0x48000011); // bl
	putBE(code, 0x4E800020); // blr
	putBE(code, 0x4E800420); // bctr (bcctr)
	CHECK(ScanExecutableSection(0x02000000, code.data(), code.size(), "T"));
	CHECK(GetSectionCount() == 1);
	CHECK(IsKnownCode(0x02000000));
	CHECK(IsKnownCode(0x0200000C));
	CHECK(!IsKnownCode(0x02000010)); // one past the end
	CHECK(!IsKnownCode(0x01FFFFFC));
	CHECK(!IsKnownCode(0x02000002)); // unaligned
	SectionInfo si;
	CHECK(GetSectionInfo(0x02000004, si));
	CHECK(si.instrCount == 4 && si.blrCount == 1 && si.branchCount == 3);
	CHECK(si.opcodeHistogram[14] == 1 && si.opcodeHistogram[18] == 1 && si.opcodeHistogram[19] == 2);
	InstructionMetadata m;
	CHECK(Lookup(0x02000008, m) && m.sectionBase == 0x02000000 && m.sectionSize == 16 && m.guestPc == 0x02000008);
	InvalidateRange(0x02000008, 0x02000008);
	CHECK(!IsKnownCode(0x02000000)); // whole section dropped (conservative)
	CHECK(GetSectionCount() == 0);
}

static void testWrong()
{
	std::printf("[WRONG INPUT]\n");
	ClearTitle();
	uint8_t buf[16] = {};
	CHECK(!ScanExecutableSection(0x1000, nullptr, 16, "null"));
	CHECK(!ScanExecutableSection(0x1000, buf, 0, "zero"));
	CHECK(!ScanExecutableSection(0x1000, buf, 3, "short"));
	CHECK(!ScanExecutableSection(0x1002, buf, 16, "unaligned"));
	CHECK(!ScanExecutableSection(0xFFFFFFF8u, buf, 16, "wraps"));
	CHECK(!ScanExecutableSection(0x00000000u, buf, (size_t)1 << 33, "huge-size")); // never dereferenced
	CHECK(GetSectionCount() == 0);
	CHECK(ScanExecutableSection(0xFFFFFFF0u, buf, 16, "ends exactly at 4GiB"));
	CHECK(IsKnownCode(0xFFFFFFFCu));
	CHECK(ScanExecutableSection(0x2000, buf, 15, "partial tail")); // 12 usable bytes
	SectionInfo si; CHECK(GetSectionInfo(0x2000, si) && si.byteSize == 12);
	CHECK(!IsKnownCode(0x200C));
	// overlapping rescan replaces old entries, no stale data
	CHECK(ScanExecutableSection(0x2008, buf, 16, "overlap"));
	CHECK(GetSectionCount() == 2);
	CHECK(!IsKnownCode(0x2000)); // old section was dropped entirely
	CHECK(IsKnownCode(0x2008));
	// inverted invalidate range must not crash and still works
	InvalidateRange(0x3000, 0x1000);
	CHECK(GetSectionCount() == 1); // only 0xFFFFFFF0 survives
	InvalidateRange(0, 0xFFFFFFFFu);
	CHECK(GetSectionCount() == 0);
	ClearTitle(); ClearTitle(); // double clear is fine
	InstructionMetadata m; CHECK(!Lookup(0, m)); CHECK(!Lookup(0xFFFFFFFFu, m));
}

// reference model: set of covered instruction addresses
static void testProperty(uint32_t seed, int ops)
{
	ClearTitle();
	std::mt19937 rng(seed);
	std::set<uint32_t> model; // instruction addresses currently "known"
	std::vector<uint8_t> blob(4096 * 4, 0);
	// model of the conservative semantic: sections are atomic units
	struct Sec { uint32_t base, size; };
	std::map<uint32_t, Sec> secs;
	auto eraseOverlap = [&](uint64_t s, uint64_t e) {
		for (auto it = secs.begin(); it != secs.end();) {
			if ((uint64_t)it->second.base < e && (uint64_t)it->second.base + it->second.size > s) it = secs.erase(it); else ++it;
		}
	};
	auto known = [&](uint32_t pc) {
		if (pc & 3) return false;
		for (auto& [b, s] : secs) if (pc >= s.base && (uint64_t)pc < (uint64_t)s.base + s.size) return true;
		return false;
	};
	for (int i = 0; i < ops; i++)
	{
		int op = rng() % 10;
		uint32_t base = (rng() % 512) * 4 + 0x1000;
		uint32_t words = rng() % 64;
		if (op < 5) {
			bool ok = ScanExecutableSection(base, blob.data(), words * 4, "p");
			CHECK(ok == (words >= 1));
			if (words >= 1) { eraseOverlap(base, (uint64_t)base + words * 4); secs[base] = { base, words * 4 }; }
		} else if (op < 7) {
			uint32_t a = (rng() % 600) * 4 + 0x1000, b = a + (rng() % 40) * 4;
			InvalidateRange(a, b);
			eraseOverlap(a, (uint64_t)b + 1);
		} else if (op == 7 && (rng() % 20) == 0) {
			ClearTitle(); secs.clear();
		}
		for (int q = 0; q < 6; q++) {
			uint32_t pc = 0x1000 + (rng() % 700) * 4 + (rng() % 8 == 0 ? 1 : 0);
			if (IsKnownCode(pc) != known(pc)) { CHECK(false); std::printf("    seed=%u op=%d pc=%08x\n", seed, i, pc); return; }
		}
		if (GetSectionCount() != secs.size()) { CHECK(false); return; }
	}
}

static void testHard()
{
	std::printf("[HARD: 200 seeds x 3000 ops vs reference model]\n");
	for (uint32_t s = 1; s <= 200; s++) testProperty(s, 3000);
}

static void testAggressive()
{
	std::printf("[AGGRESSIVE: 8 threads, scan/lookup/invalidate/clear at the same time]\n");
	ClearTitle();
	std::atomic<bool> stop{ false };
	std::atomic<uint64_t> lookups{ 0 }, scans{ 0 };
	std::vector<uint8_t> blob(1 << 16, 0x4E);
	std::vector<std::thread> th;
	for (int t = 0; t < 4; t++) th.emplace_back([&, t] {
		std::mt19937 rng(100 + t);
		while (!stop) { InstructionMetadata m; IsKnownCode((rng() % 0x4000) * 4); Lookup((rng() % 0x4000) * 4, m); SectionInfo si; GetSectionInfo((rng() % 0x4000) * 4, si); lookups++; }
	});
	for (int t = 0; t < 3; t++) th.emplace_back([&, t] {
		std::mt19937 rng(200 + t);
		while (!stop) { ScanExecutableSection((rng() % 0x2000) * 4, blob.data(), ((rng() % 2000) + 1) * 4, "mt"); scans++; }
	});
	th.emplace_back([&] {
		std::mt19937 rng(300);
		while (!stop) { uint32_t a = (rng() % 0x4000) * 4; InvalidateRange(a, a + (rng() % 4000) * 4); if (rng() % 50 == 0) ClearTitle(); }
	});
	std::this_thread::sleep_for(std::chrono::milliseconds(1500));
	stop = true;
	for (auto& x : th) x.join();
	std::printf("  lookups=%llu scans=%llu\n", (unsigned long long)lookups.load(), (unsigned long long)scans.load());
	CHECK(lookups > 1000 && scans > 100);
	ClearTitle();
	CHECK(GetSectionCount() == 0);
}

int main()
{
	testSimple(); testWrong(); testHard(); testAggressive();
	std::printf(g_fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", g_fail);
	return g_fail ? 1 : 0;
}
