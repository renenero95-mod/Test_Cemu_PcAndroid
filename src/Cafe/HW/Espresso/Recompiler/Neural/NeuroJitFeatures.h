#pragma once
// Static features of a PowerPC function, computed from its machine code, used to predict how expensive it is to JIT-compile.
// The scan NEVER reads outside [pc, sectionEnd): reading unmapped guest memory would crash the emulator.
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace NeuroJit
{
	constexpr int kFeatureCount = 12;
	constexpr uint32_t kMaxScanWords = 4096;

	enum FeatureIndex
	{
		FEAT_WORDS = 0, FEAT_COND, FEAT_UNCOND, FEAT_CALLS, FEAT_BACK, FEAT_FP, FEAT_LOAD, FEAT_STORE, FEAT_INDIRECT, FEAT_BLR, FEAT_SPAN, FEAT_ALU
	};

	struct CodeFeatures
	{
		float v[kFeatureCount] = {}; // raw counts
		bool valid = false;
	};

	inline int32_t SignExtend(uint32_t value, int bits)
	{
		const uint32_t m = 1u << (bits - 1);
		return (int32_t)((value ^ m) - m);
	}

	// rd(address) -> big-endian-decoded 32 bit instruction word at a guest address. Only called for addresses in [pc, sectionEnd).
	template<class ReadU32>
	CodeFeatures ExtractFeatures(uint32_t pc, uint64_t sectionEnd, ReadU32&& rd, uint32_t maxWords = kMaxScanWords)
	{
		CodeFeatures f;
		if ((pc & 3u) != 0 || sectionEnd <= pc || (sectionEnd & 3u) != 0)
			return f;
		maxWords = std::min(maxWords, kMaxScanWords);
		const uint64_t availWords = (sectionEnd - pc) / 4;
		const uint32_t words = (uint32_t)std::min<uint64_t>(maxWords, availWords);
		int64_t maxForwardTarget = -1; // in words relative to pc
		uint32_t n = 0;
		for (uint32_t i = 0; i < words; i++)
		{
			const uint32_t raw = rd(pc + 4u * i);
			if (raw == 0)
				break; // zero padding: end of code
			n++;
			const uint32_t primary = raw >> 26;
			bool endHere = false;
			switch (primary)
			{
			case 18: // b / bl / ba / bla
			{
				const int32_t li = SignExtend(raw & 0x03FFFFFCu, 26);
				if (raw & 1u)
					f.v[FEAT_CALLS] += 1;
				else
				{
					f.v[FEAT_UNCOND] += 1;
					if (!(raw & 2u))
					{
						if (li < 0)
							f.v[FEAT_BACK] += 1;
						else
							maxForwardTarget = std::max<int64_t>(maxForwardTarget, (int64_t)i + li / 4);
					}
				}
				break;
			}
			case 16: // bc
			{
				const int32_t bd = SignExtend(raw & 0xFFFCu, 16);
				f.v[FEAT_COND] += 1;
				if (!(raw & 2u) && !(raw & 1u))
				{
					if (bd < 0)
						f.v[FEAT_BACK] += 1;
					else
						maxForwardTarget = std::max<int64_t>(maxForwardTarget, (int64_t)i + bd / 4);
				}
				break;
			}
			case 19:
			{
				const uint32_t xo = (raw >> 1) & 0x3FFu;
				if (xo == 16) // bclr
				{
					f.v[FEAT_BLR] += 1;
					if ((raw & 0x03E00000u) == 0x02800000u && !(raw & 1u)) // BO=20 (always), no link: plain blr
						endHere = (int64_t)i >= maxForwardTarget;
				}
				else if (xo == 528) // bcctr
				{
					f.v[FEAT_INDIRECT] += 1;
					if ((raw & 0x03E00000u) == 0x02800000u && !(raw & 1u)) // bctr: tail call / jump table
						endHere = (int64_t)i >= maxForwardTarget;
				}
				else
					f.v[FEAT_ALU] += 1; // cr logic, isync...
				break;
			}
			case 32: case 33: case 34: case 35: case 40: case 41: case 42: case 43: case 46:
				f.v[FEAT_LOAD] += 1;
				break;
			case 36: case 37: case 38: case 39: case 44: case 45: case 47:
				f.v[FEAT_STORE] += 1;
				break;
			case 48: case 49: case 50: case 51:
				f.v[FEAT_LOAD] += 1; f.v[FEAT_FP] += 1;
				break;
			case 52: case 53: case 54: case 55:
				f.v[FEAT_STORE] += 1; f.v[FEAT_FP] += 1;
				break;
			case 4: case 59: case 63:
				f.v[FEAT_FP] += 1;
				break;
			default:
				f.v[FEAT_ALU] += 1;
				break;
			}
			if (endHere)
				break;
		}
		if (n == 0)
			return f;
		f.v[FEAT_WORDS] = (float)n;
		f.v[FEAT_SPAN] = (float)std::max<int64_t>(0, maxForwardTarget);
		f.valid = true;
		return f;
	}

	// features as model input: log-scaled into roughly [0, 1]
	inline void NormalizeFeatures(const CodeFeatures& f, float* x)
	{
		for (int i = 0; i < kFeatureCount; i++)
		{
			const float c = (f.v[i] >= 0.0f && f.v[i] < 1.0e9f) ? f.v[i] : 0.0f;
			x[i] = std::log1p(c) * 0.125f; // ln(1+4096)=8.3 -> ~1.04
		}
	}

	// hand-written baseline cost model, ln(milliseconds). Used as a sanity bound for the learned model and as a fallback.
	inline float HeuristicLogMs(const CodeFeatures& f)
	{
		const float words = std::max(f.v[FEAT_WORDS], 1.0f);
		const float ms = 0.05f + 0.006f * std::pow(words, 1.1f) + 0.004f * f.v[FEAT_COND] + 0.01f * f.v[FEAT_INDIRECT];
		return std::log(ms);
	}
}
