#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

// PPC Pre-Execution Analyzer (passive).
//
// Records, for every executable section handed over by the RPL/ELF loader, a compact summary
// (address range + opcode statistics). Memory use is O(number of sections), NOT O(number of
// instructions): the previous implementation kept one hash-map node per guest instruction,
// which costs several hundred MB for large titles and delays game start.
//
// The analyzer never modifies or skips guest code. If it knows nothing about an address,
// every consumer must behave exactly like stock Cemu.
namespace PPCPreExecutionAnalyzer
{
	struct SectionInfo
	{
		uint32_t guestBase = 0;
		uint32_t byteSize = 0;       // always a multiple of 4
		uint32_t instrCount = 0;
		uint32_t branchCount = 0;    // b/bl/bc/bclr/bcctr
		uint32_t blrCount = 0;       // plain 'blr' (function return) - rough function count hint
		uint32_t opcodeHistogram[64] = {}; // indexed by primary opcode (bits 0-5)
		std::string moduleName;
	};

	struct InstructionMetadata
	{
		uint32_t guestPc = 0;
		uint32_t sectionBase = 0;
		uint32_t sectionSize = 0;
	};

	// Returns false (and stores nothing) for invalid input: null/short buffer, unaligned base,
	// or a range that would wrap around the 32bit guest address space.
	// Re-scanning overlapping ranges replaces the older entries (no stale data).
	bool ScanExecutableSection(uint32_t guestBase, const uint8_t* bytes, size_t size, const std::string& moduleName);

	// Drops every section overlapping [guestStart, guestEnd] (inclusive). Dropping knowledge is always safe.
	void InvalidateRange(uint32_t guestStart, uint32_t guestEnd);
	void ClearTitle();

	// true if guestPc is a 4-byte aligned address inside a scanned executable section
	bool IsKnownCode(uint32_t guestPc);
	bool Lookup(uint32_t guestPc, InstructionMetadata& out);

	// diagnostics / tests
	size_t GetSectionCount();
	bool GetSectionInfo(uint32_t guestPc, SectionInfo& out);
}
