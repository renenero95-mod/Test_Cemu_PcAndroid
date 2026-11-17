// Tests the REAL CemuFixer low-guest-memory code from coreinit_MEM.cpp (extracted at build time, see run_tests.sh).
// Models the host layout used by Cemu on Linux/Android: a PROT_NONE reservation, guest address = memory_base + addr.
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>
typedef uint8_t uint8;
enum LogType { Force };
static uint8* memory_base = nullptr;
template<class... A> void cemuLog_log(LogType, const char*, A&&...) {}
#include "lowmem_extract.inc"   // generated: the #if defined(__linux__)... block of coreinit_MEM.cpp

static int g_fail = 0;
#define CHECK(c) do { if(!(c)) { std::printf("  FAIL line %d: %s\n", __LINE__, #c); g_fail++; } } while(0)
static const size_t RES = 1 << 20; // 1 MB stand-in for the 4 GB reservation

static uint8* reserve() { void* p = mmap(nullptr, RES, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); return (uint8*)p; }
static bool readable(void* p) { return CemuFixer_IsHostByteReadable(p); }

int main()
{
	const size_t ps = (size_t)sysconf(_SC_PAGESIZE);
	std::printf("[SIMPLE] page size %zu\n", ps);
	memory_base = reserve();
	CHECK(!readable(memory_base)); CHECK(!readable(memory_base + 0x494));
	CemuFixer_EnsureLowGuestMemory();
	for (size_t off = 0; off < 0x10000; off += 0x100) { CHECK(readable(memory_base + off)); CHECK(memory_base[off] == 0); }
	memory_base[0x494] = 0x7F; CHECK(memory_base[0x494] == 0x7F);      // writable too
	CHECK(!readable(memory_base + 0x10000));                            // guest 0x10000 (CODE_LOW0) untouched
	CHECK(!readable(memory_base + 0x10000 + ps));

	std::printf("[WRONG] null base, repeated calls, already-mapped data must survive\n");
	uint8* saved = memory_base; memory_base = nullptr; CemuFixer_EnsureLowGuestMemory(); memory_base = saved; // must not crash
	CemuFixer_EnsureLowGuestMemory(); CemuFixer_EnsureLowGuestMemory();
	CHECK(memory_base[0x494] == 0x7F);                                  // idempotent, data not wiped
	// fully readable region beforehand (e.g. someone mapped MEM0 already): nothing may change
	uint8* r2 = reserve(); mprotect(r2, 0x20000, PROT_READ | PROT_WRITE); std::memset(r2, 0xAB, 0x20000);
	memory_base = r2; CemuFixer_EnsureLowGuestMemory();
	CHECK(r2[0] == 0xAB && r2[0xFFFF] == 0xAB && r2[0x10000] == 0xAB);

	std::printf("[HARD] mixed layout: pages 0 and 2 readable with data, others PROT_NONE\n");
	uint8* r3 = reserve(); mprotect(r3, ps, PROT_READ | PROT_WRITE); mprotect(r3 + 2 * ps, ps, PROT_READ | PROT_WRITE);
	r3[5] = 0x11; r3[2 * ps + 7] = 0x22; memory_base = r3; CemuFixer_EnsureLowGuestMemory();
	CHECK(r3[5] == 0x11 && r3[2 * ps + 7] == 0x22);                     // existing pages preserved
	for (size_t off = 0; off < 0x10000; off += ps) CHECK(readable(r3 + off));
	CHECK(r3[ps + 1] == 0);

	std::printf("[AGGRESSIVE] 8 threads x 200 calls on a fresh reservation\n");
	uint8* r4 = reserve(); memory_base = r4;
	std::vector<std::thread> th;
	for (int t = 0; t < 8; t++) th.emplace_back([] { for (int i = 0; i < 200; i++) CemuFixer_EnsureLowGuestMemory(); });
	for (auto& x : th) x.join();
	for (size_t off = 0; off < 0x10000; off += ps) CHECK(readable(r4 + off));
	CHECK(!readable(r4 + 0x10000));

	std::printf(g_fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", g_fail);
	return g_fail ? 1 : 0;
}
