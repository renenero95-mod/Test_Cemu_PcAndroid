#include "Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.h"
#include <algorithm>
#include <mutex>
#include <shared_mutex>
#include <vector>

namespace PPCPreExecutionAnalyzer
{
	namespace
	{
		// sorted by guestBase, non-overlapping
		std::vector<SectionInfo> s_sections;
		std::shared_mutex s_mutex;

		inline uint64_t SectionEnd(const SectionInfo& s)
		{
			return (uint64_t)s.guestBase + s.byteSize;
		}

		// index of the section containing pc, or -1. Caller must hold the lock.
		int64_t FindSectionLocked(uint32_t pc)
		{
			auto it = std::upper_bound(s_sections.begin(), s_sections.end(), pc,
				[](uint32_t value, const SectionInfo& s) { return value < s.guestBase; });
			if (it == s_sections.begin())
				return -1;
			--it;
			if ((uint64_t)pc < SectionEnd(*it))
				return it - s_sections.begin();
			return -1;
		}

		// removes all sections overlapping [start, endExclusive). Caller must hold the unique lock.
		void EraseOverlappingLocked(uint64_t start, uint64_t endExclusive)
		{
			s_sections.erase(std::remove_if(s_sections.begin(), s_sections.end(),
				[&](const SectionInfo& s) { return (uint64_t)s.guestBase < endExclusive && SectionEnd(s) > start; }),
				s_sections.end());
		}
	}

	bool ScanExecutableSection(uint32_t guestBase, const uint8_t* bytes, size_t size, const std::string& moduleName)
	{
		if (bytes == nullptr || size < 4 || (guestBase & 3) != 0)
			return false;
		size &= ~(size_t)3; // ignore a trailing partial instruction
		if ((uint64_t)guestBase + size > 0x100000000ULL)
			return false; // would wrap around the guest address space
		if (size > 0xFFFFFFFFu)
			return false;

		// build the summary outside of the lock (the loader thread may scan tens of MB)
		SectionInfo info;
		info.guestBase = guestBase;
		info.byteSize = (uint32_t)size;
		info.instrCount = (uint32_t)(size / 4);
		info.moduleName = moduleName;
		for (size_t offset = 0; offset < size; offset += 4)
		{
			const uint32_t raw = (uint32_t(bytes[offset]) << 24) | (uint32_t(bytes[offset + 1]) << 16) | (uint32_t(bytes[offset + 2]) << 8) | uint32_t(bytes[offset + 3]);
			const uint32_t primary = raw >> 26;
			info.opcodeHistogram[primary]++;
			if (primary == 18 || primary == 16)
				info.branchCount++;
			else if (primary == 19)
			{
				const uint32_t xo = (raw >> 1) & 0x3FF;
				if (xo == 16 || xo == 528)
					info.branchCount++;
				if (raw == 0x4E800020u)
					info.blrCount++;
			}
		}

		std::unique_lock lock(s_mutex);
		EraseOverlappingLocked(guestBase, (uint64_t)guestBase + size);
		auto pos = std::lower_bound(s_sections.begin(), s_sections.end(), guestBase,
			[](const SectionInfo& s, uint32_t value) { return s.guestBase < value; });
		s_sections.insert(pos, std::move(info));
		return true;
	}

	void InvalidateRange(uint32_t guestStart, uint32_t guestEnd)
	{
		if (guestEnd < guestStart)
			std::swap(guestStart, guestEnd);
		std::unique_lock lock(s_mutex);
		EraseOverlappingLocked(guestStart, (uint64_t)guestEnd + 1);
	}

	void ClearTitle()
	{
		std::unique_lock lock(s_mutex);
		s_sections.clear();
		s_sections.shrink_to_fit();
	}

	bool IsKnownCode(uint32_t guestPc)
	{
		if ((guestPc & 3) != 0)
			return false;
		std::shared_lock lock(s_mutex);
		return FindSectionLocked(guestPc) >= 0;
	}

	bool Lookup(uint32_t guestPc, InstructionMetadata& out)
	{
		if ((guestPc & 3) != 0)
			return false;
		std::shared_lock lock(s_mutex);
		const int64_t idx = FindSectionLocked(guestPc);
		if (idx < 0)
			return false;
		out.guestPc = guestPc;
		out.sectionBase = s_sections[idx].guestBase;
		out.sectionSize = s_sections[idx].byteSize;
		return true;
	}

	size_t GetSectionCount()
	{
		std::shared_lock lock(s_mutex);
		return s_sections.size();
	}

	bool GetSectionInfo(uint32_t guestPc, SectionInfo& out)
	{
		std::shared_lock lock(s_mutex);
		const int64_t idx = FindSectionLocked(guestPc);
		if (idx < 0)
			return false;
		out = s_sections[idx];
		return true;
	}
}
