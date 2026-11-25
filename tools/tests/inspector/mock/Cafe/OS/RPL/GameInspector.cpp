#include "Cafe/OS/RPL/GameInspector.h"
#include "Cafe/OS/RPL/rpl.h"
#include "Cafe/OS/RPL/rpl_structs.h"
#include "Cafe/OS/RPL/rpl_symbol_storage.h"
#include "Cafe/HW/Espresso/PPCState.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/CafeSystem.h"
#include "Cemu/PPCAssembler/ppcAssembler.h"
#include "Common/ExceptionHandler/ExceptionHandler.h"
#include "config/ActiveSettings.h"
#include "config/CemuConfig.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <fstream>
#include <map>
#include <mutex>
#include <vector>

namespace GameInspector
{
	namespace
	{
		std::atomic<sint32> s_progress{-1};
		std::mutex s_stageMutex;
		std::string s_stage;

		void SetState(const std::string& stage, sint32 progress)
		{
			{
				std::lock_guard<std::mutex> lock(s_stageMutex);
				s_stage = stage;
			}
			s_progress = progress;
		}

		constexpr uint32 MIN_CODE_ADDRESS = 0x01000000;

		bool ReadWord(uint32 address, uint32& value)
		{
			if (address < MIN_CODE_ADDRESS || (address & 3) != 0 || !memory_isAddressRangeAccessible(address, 4))
				return false;
			value = memory_readU32(address);
			return true;
		}

		// name of the function that contains the address, for example "Run__26CDelayedArchivesOpenThreadFv+0xc4"
		std::string DescribeAddress(uint32 address)
		{
			const RPLStoredSymbol* symbol = rplSymbolStorage_getByClosestAddress(address);
			if (symbol == nullptr || symbol->symbolName == nullptr)
				return {};
			const char* libName = static_cast<const char*>(symbol->libName);
			const char* symbolName = static_cast<const char*>(symbol->symbolName);
			std::string text;
			if (libName != nullptr && libName[0] != '\0')
				text = fmt::format("{}.", libName);
			text += symbolName;
			if (address > symbol->address)
				text += fmt::format("+0x{:x}", address - symbol->address);
			return text;
		}

		// name of a function whose first instruction is at the address (call targets)
		std::string DescribeCallTarget(uint32 address)
		{
			const RPLStoredSymbol* symbol = rplSymbolStorage_getByAddress(address);
			if (symbol == nullptr || symbol->symbolName == nullptr)
				return DescribeAddress(address);
			const char* libName = static_cast<const char*>(symbol->libName);
			const char* symbolName = static_cast<const char*>(symbol->symbolName);
			if (libName != nullptr && libName[0] != '\0')
				return fmt::format("{}.{}", libName, symbolName);
			return symbolName;
		}

		bool IsCall(uint32 opcode, uint32 address, uint32& targetOut)
		{
			// b / bl / ba / bla: primary opcode 18, LK bit set
			if ((opcode >> 26) != 18 || (opcode & 1) == 0)
				return false;
			sint32 displacement = static_cast<sint32>(opcode & 0x03FFFFFC);
			if (displacement & 0x02000000)
				displacement -= 0x04000000;
			targetOut = (opcode & 2) ? static_cast<uint32>(displacement) : address + static_cast<uint32>(displacement);
			return true;
		}

		std::string Disassemble(uint32 address, uint32 opcode)
		{
			PPCDisassembledInstruction instruction{};
			ppcAssembler_disassemble(address, opcode, &instruction);
			if (instruction.ppcAsmCode == PPCASM_OP_UKN)
				return fmt::format(".long 0x{:08x}", opcode);
			std::string text = ppcAssembler_getInstructionName(instruction.ppcAsmCode);
			std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
			bool firstOperand = true;
			for (sint32 i = 0; i < PPCASM_OPERAND_COUNT; i++)
			{
				if (((instruction.operandMask >> i) & 1) == 0)
					continue;
				text += firstOperand ? " " : ", ";
				firstOperand = false;
				const PPCDisassemblerOperand& operand = instruction.operand[i];
				switch (operand.type)
				{
				case PPCASM_OPERAND_TYPE_GPR:
					text += fmt::format("r{}", operand.registerIndex);
					break;
				case PPCASM_OPERAND_TYPE_FPR:
					text += fmt::format("f{}", operand.registerIndex);
					break;
				case PPCASM_OPERAND_TYPE_SPR:
					text += fmt::format("spr{}", operand.registerIndex);
					break;
				case PPCASM_OPERAND_TYPE_CR:
					text += fmt::format("cr{}", operand.registerIndex);
					break;
				case PPCASM_OPERAND_TYPE_CIMM:
					text += fmt::format("0x{:08x}", operand.immU32);
					break;
				case PPCASM_OPERAND_TYPE_MEM:
					if (operand.immS32 < 0)
						text += fmt::format("[r{} - 0x{:x}]", operand.registerIndex, -static_cast<sint64>(operand.immS32));
					else
						text += fmt::format("[r{} + 0x{:x}]", operand.registerIndex, operand.immU32);
					break;
				case PPCASM_OPERAND_TYPE_IMM:
					if (operand.isSignedImm && operand.immS32 < 0)
						text += fmt::format("-0x{:x}", -static_cast<sint64>(operand.immS32));
					else
						text += fmt::format("0x{:x}", operand.immU32);
					break;
				default:
					text += "?";
					break;
				}
			}
			return text;
		}

		// "address  word  instruction ; annotation" with the call target resolved to a function name
		std::string FormatLine(uint32 address, uint32 opcode, bool highlight)
		{
			std::string line = fmt::format("{} 0x{:08x}  {:08x}  {}", highlight ? ">>>" : "   ", address, opcode, Disassemble(address, opcode));
			uint32 callTarget = 0;
			if (IsCall(opcode, address, callTarget))
			{
				const std::string name = DescribeCallTarget(callTarget);
				if (!name.empty())
					line += fmt::format("   ; call {}", name);
			}
			return line;
		}

		void LogCodeWindow(const char* title, uint32 center, sint32 before, sint32 after)
		{
			CrashLog_WriteLine("");
			CrashLog_WriteLine(fmt::format("{} {}", title, DescribeAddress(center)));
			for (sint32 i = -before; i <= after; i++)
			{
				const uint32 address = center + static_cast<uint32>(i * 4);
				uint32 opcode = 0;
				if (!ReadWord(address, opcode))
				{
					CrashLog_WriteLine(fmt::format("    0x{:08x}  (not readable)", address));
					continue;
				}
				CrashLog_WriteLine(FormatLine(address, opcode, i == 0));
			}
		}

		// register written by the instruction, or -1 when it is not one of the common forms
		sint32 WrittenRegister(uint32 opcode)
		{
			const uint32 primary = opcode >> 26;
			const sint32 rD = static_cast<sint32>((opcode >> 21) & 31);
			const sint32 rA = static_cast<sint32>((opcode >> 16) & 31);
			switch (primary)
			{
			case 7: case 8: case 12: case 13: case 14: case 15: // mulli subfic addic addic. addi addis
			case 32: case 33: case 34: case 35: case 40: case 41: case 42: case 43: // loads
				return rD;
			case 21: case 24: case 25: case 26: case 27: case 28: case 29: // rlwinm ori oris xori xoris andi. andis.
				return rA;
			case 31:
			{
				const uint32 xo10 = (opcode >> 1) & 0x3FF;
				const uint32 xo9 = xo10 & 0x1FF;
				if (xo9 == 266 || xo9 == 40 || xo9 == 235 || xo9 == 104 || xo9 == 10 || xo9 == 138 || xo9 == 8 || xo9 == 136 || xo9 == 491 || xo9 == 459)
					return rD; // add subf mullw neg addc adde subfc subfe divw divwu
				if (xo10 == 23 || xo10 == 87 || xo10 == 279 || xo10 == 343 || xo10 == 55 || xo10 == 119 || xo10 == 311 || xo10 == 375)
					return rD; // lwzx lbzx lhzx lhax lwzux lbzux lhzux lhaux
				if (xo10 == 444 || xo10 == 28 || xo10 == 316 || xo10 == 24 || xo10 == 536 || xo10 == 792 || xo10 == 824 || xo10 == 922 || xo10 == 954 || xo10 == 26 || xo10 == 124 || xo10 == 60 || xo10 == 412 || xo10 == 476)
					return rA; // or and xor slw srw sraw srawi extsh extsb cntlzw nor andc orc nand
				return -1;
			}
			default:
				return -1;
			}
		}

		// explains where the base register of the faulting instruction got its value
		void LogTraceBack(const PPCInterpreter_t* hCPU, uint32 faultAddress, sint32 reg, sint32 depth)
		{
			if (reg <= 0 || depth > 2)
				return;
			for (sint32 i = 1; i <= 96; i++)
			{
				const uint32 address = faultAddress - static_cast<uint32>(i * 4);
				uint32 opcode = 0;
				if (!ReadWord(address, opcode))
					return;
				uint32 callTarget = 0;
				if (IsCall(opcode, address, callTarget))
				{
					if (reg >= 3 && reg <= 12)
					{
						const std::string name = DescribeCallTarget(callTarget);
						CrashLog_WriteLine(fmt::format("    r{} comes from a call (volatile register): 0x{:08x}  {}", reg, address, name.empty() ? std::string("call 0x") + fmt::format("{:08x}", callTarget) : "bl " + name));
						CrashLog_WriteLine(fmt::format("    -> if r{} is 0 the function {} returned 0", reg, name.empty() ? "called there" : name));
						return;
					}
					continue;
				}
				// bctrl / blrl: indirect call
				if ((opcode >> 26) == 19 && (opcode & 1) != 0 && (((opcode >> 1) & 0x3FF) == 528 || ((opcode >> 1) & 0x3FF) == 16))
				{
					if (reg >= 3 && reg <= 12)
					{
						CrashLog_WriteLine(fmt::format("    r{} comes from an indirect call (bctrl/blrl) at 0x{:08x}", reg, address));
						return;
					}
					continue;
				}
				if (WrittenRegister(opcode) == reg)
				{
					CrashLog_WriteLine(fmt::format("    r{} written at: {}", reg, FormatLine(address, opcode, false)));
					// "mr rX, rY" is "or rX, rY, rY": follow the source once
					if ((opcode >> 26) == 31 && ((opcode >> 1) & 0x3FF) == 444 && ((opcode >> 21) & 31) == ((opcode >> 11) & 31))
						LogTraceBack(hCPU, address, static_cast<sint32>((opcode >> 21) & 31), depth + 1);
					return;
				}
			}
			CrashLog_WriteLine(fmt::format("    r{}: no write found in the previous 96 instructions (it is an argument or was set earlier)", reg));
		}
	} // namespace

	sint32 GetProgress()
	{
		return s_progress;
	}

	std::string GetStage()
	{
		std::lock_guard<std::mutex> lock(s_stageMutex);
		return s_stage;
	}

	void LogCrashContext(const PPCInterpreter_t* hCPU)
	{
		if (hCPU == nullptr)
			return;
		const uint32 ip = hCPU->instructionPointer;
		CrashLog_WriteHeader("Inspector: code around the faulting instruction");
		CrashLog_WriteLine("The instruction pointer is exact for interpreted code; for recompiled code it may point to the start of the running function.");
		LogCodeWindow("Faulting instruction at", ip, 20, 6);

		uint32 opcode = 0;
		if (ReadWord(ip, opcode))
		{
			const uint32 primary = opcode >> 26;
			// D-form loads and stores: lwz, lwzu, lbz, lbzu, stw, stwu, stb, stbu, lhz, lhzu, lha, lhau, sth, sthu, lmw, stmw, lfs..stfdu
			if ((primary >= 32 && primary <= 55))
			{
				const sint32 rA = static_cast<sint32>((opcode >> 16) & 31);
				const sint32 displacement = static_cast<sint16>(opcode & 0xFFFF);
				const uint32 base = rA == 0 ? 0 : hCPU->gpr[rA];
				const uint32 effectiveAddress = base + static_cast<uint32>(displacement);
				CrashLog_WriteLine("");
				CrashLog_WriteLine(fmt::format("Effective address: base r{} = 0x{:08x}, offset {}, address 0x{:08x}", rA, base, displacement, effectiveAddress));
				if (effectiveAddress < 0x10000)
					CrashLog_WriteLine(fmt::format("NULL POINTER ACCESS: the game used address 0x{:x} (null + offset). Look at how r{} got its value:", effectiveAddress, rA));
				else
					CrashLog_WriteLine("The address is not in the first 64 KB: it may be a wrong or freed pointer. Where r" + std::to_string(rA) + " came from:");
				LogTraceBack(hCPU, ip, rA, 0);
			}
		}

		// last calls made before the faulting instruction
		CrashLog_WriteLine("");
		CrashLog_WriteLine("Calls made shortly before (oldest first):");
		std::vector<std::string> calls;
		for (sint32 i = 120; i >= 1; i--)
		{
			const uint32 address = ip - static_cast<uint32>(i * 4);
			uint32 word = 0;
			uint32 target = 0;
			if (ReadWord(address, word) && IsCall(word, address, target))
			{
				const std::string name = DescribeCallTarget(target);
				calls.push_back(fmt::format("    0x{:08x}  bl {}", address, name.empty() ? fmt::format("0x{:08x}", target) : name));
			}
		}
		const size_t firstCall = calls.size() > 8 ? calls.size() - 8 : 0;
		for (size_t i = firstCall; i < calls.size(); i++)
			CrashLog_WriteLine(calls[i]);
		if (calls.empty())
			CrashLog_WriteLine("    (none found in the previous 120 instructions)");

		// callers: the return address of the running function is LR; the callers above it are saved in the stack frames
		// (back chain at [sp], the return address that a function saves lives at [back chain + 4])
		CrashLog_WriteLine("");
		CrashLog_WriteHeader("Inspector: callers");
		std::vector<uint32> returnAddresses;
		returnAddresses.push_back(hCPU->spr.LR);
		uint32 stackPointer = hCPU->gpr[1];
		for (sint32 depth = 0; depth < 12; depth++)
		{
			if (stackPointer < MIN_CODE_ADDRESS || !memory_isAddressRangeAccessible(stackPointer, 8))
				break;
			const uint32 backChain = memory_readU32(stackPointer);
			if (backChain <= stackPointer || backChain < MIN_CODE_ADDRESS || !memory_isAddressRangeAccessible(backChain, 8))
				break;
			const uint32 savedReturnAddress = memory_readU32(backChain + 4);
			if (savedReturnAddress != returnAddresses.back())
				returnAddresses.push_back(savedReturnAddress);
			stackPointer = backChain;
		}
		for (size_t frame = 0; frame < returnAddresses.size() && frame < 10; frame++)
		{
			const uint32 returnAddress = returnAddresses[frame];
			if (returnAddress < MIN_CODE_ADDRESS)
				break;
			uint32 callWord = 0;
			std::string callee;
			uint32 target = 0;
			if (ReadWord(returnAddress - 4, callWord) && IsCall(callWord, returnAddress - 4, target))
				callee = DescribeCallTarget(target);
			CrashLog_WriteLine(fmt::format("frame {}: returns to 0x{:08x} {}{}", frame, returnAddress, DescribeAddress(returnAddress),
				callee.empty() ? std::string() : fmt::format("   (it called {})", callee)));
			if (frame == 0)
				LogCodeWindow("Code around the return address", returnAddress, 8, 2);
		}
	}

	namespace
	{
		struct ScanPattern
		{
			const char* name;
			uint32 extendedOpcode;
			bool hasOverflowBit; // XO-form: the lowest 9 bits identify the instruction
			bool recompilerSupported;
		};

		// primary opcode 31 instructions that were reported as suspects; the PPC recompiler does not translate SUBFME and ICBI
		constexpr ScanPattern SCAN_PATTERNS[] = {
			{"SUBFME (31/232)", 232, true, false},
			{"SUBFZE (31/200)", 200, true, true},
			{"ADDME (31/234)", 234, true, true},
			{"ADDZE (31/202)", 202, true, true},
			{"ICBI (31/982)", 982, false, false},
			{"DCBST (31/54)", 54, false, true},
			{"DCBF (31/86)", 86, false, true},
		};
		constexpr size_t SCAN_PATTERN_COUNT = sizeof(SCAN_PATTERNS) / sizeof(SCAN_PATTERNS[0]);
	} // namespace

	void ScanLoadedModules()
	{
		if (!GetConfig().inspector_enabled)
		{
			SetState("", 100);
			return;
		}
		SetState("Preparing", 0);
		try
		{
			const std::string fileName = fmt::format("inspector/{:016x}_{:08x}_scan.txt", CafeSystem::GetForegroundTitleId(), CafeSystem::GetRPXHashBase());
			const fs::path reportPath = ActiveSettings::GetUserDataPath(fileName);
			std::error_code ec;
			if (fs::exists(reportPath, ec))
			{
				SetState("", 100);
				return;
			}

			const std::span<RPLModule*> modules = RPLLoader_GetModuleList();
			uint64 totalWords = 0;
			for (const RPLModule* module : modules)
				totalWords += module->regionSize_text / 4;
			if (totalWords == 0)
				totalWords = 1;

			std::vector<uint32> totals(SCAN_PATTERN_COUNT, 0);
			std::map<std::string, uint32> functionHits; // functions containing instructions the recompiler cannot translate
			uint32 symbolLookups = 0;
			std::string moduleLines;
			uint64 processedWords = 0;

			for (const RPLModule* module : modules)
			{
				const uint32 base = module->regionMappingBase_text.GetMPTR();
				const uint32 wordCount = module->regionSize_text / 4;
				SetState(fmt::format("Scanning {}", module->moduleName), static_cast<sint32>(std::min<uint64>(99, processedWords * 100 / totalWords)));
				std::vector<uint32> moduleTotals(SCAN_PATTERN_COUNT, 0);
				for (uint32 i = 0; i < wordCount; i++)
				{
					const uint32 opcode = memory_readU32(base + i * 4);
					if ((opcode >> 26) == 31)
					{
						const uint32 xo10 = (opcode >> 1) & 0x3FF;
						const uint32 xo9 = xo10 & 0x1FF;
						for (size_t p = 0; p < SCAN_PATTERN_COUNT; p++)
						{
							const ScanPattern& pattern = SCAN_PATTERNS[p];
							if ((pattern.hasOverflowBit ? xo9 : xo10) != pattern.extendedOpcode)
								continue;
							moduleTotals[p]++;
							totals[p]++;
							if (!pattern.recompilerSupported && symbolLookups < 20000)
							{
								symbolLookups++;
								const std::string name = DescribeAddress(base + i * 4);
								const size_t plus = name.rfind("+0x");
								functionHits[plus == std::string::npos ? name : name.substr(0, plus)]++;
							}
						}
					}
					if ((i & 0xFFFF) == 0xFFFF)
					{
						const uint64 done = processedWords + i;
						SetState(fmt::format("Scanning {}", module->moduleName), static_cast<sint32>(std::min<uint64>(99, done * 100 / totalWords)));
					}
				}
				processedWords += wordCount;
				uint32 moduleHitCount = 0;
				for (uint32 count : moduleTotals)
					moduleHitCount += count;
				moduleLines += fmt::format("  {:<28} text 0x{:08x} size 0x{:x}  matches {}\n", module->moduleName, base, module->regionSize_text, moduleHitCount);
			}

			SetState("Writing report", 99);
			std::string report;
			report += "Cemu game inspector - scan before launch\n";
			report += fmt::format("Title: {:016x}  {}\n", CafeSystem::GetForegroundTitleId(), CafeSystem::GetForegroundTitleName());
			report += fmt::format("Main executable hash: {:08x}\n", CafeSystem::GetRPXHashBase());
			report += fmt::format("Modules scanned: {}\n\nModules:\n{}\n", modules.size(), moduleLines);
			report += "Instruction counts (the scan reads every word of the code regions, so a few matches can be data):\n";
			for (size_t p = 0; p < SCAN_PATTERN_COUNT; p++)
				report += fmt::format("  {:<18} {:>8}  {}\n", SCAN_PATTERNS[p].name, totals[p], SCAN_PATTERNS[p].recompilerSupported ? "translated by the recompiler" : "NOT translated: the function runs in the slower interpreter");
			std::vector<std::pair<std::string, uint32>> sorted(functionHits.begin(), functionHits.end());
			std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
			report += fmt::format("\nFunctions with instructions the recompiler does not translate ({} found, first 60):\n", sorted.size());
			for (size_t i = 0; i < sorted.size() && i < 60; i++)
				report += fmt::format("  {:>4}  {}\n", sorted[i].second, sorted[i].first.empty() ? std::string("(no symbol)") : sorted[i].first);

			fs::create_directories(reportPath.parent_path(), ec);
			std::ofstream file(reportPath, std::ios::binary);
			if (file.is_open())
				file << report;
			SetState("", 100);
		}
		catch (const std::exception& e)
		{
			cemuLog_log(LogType::Force, "Game inspector: scan failed: {}", e.what());
			SetState("", 100);
		}
	}
} // namespace GameInspector
