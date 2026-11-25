#include "Cafe/OS/RPL/GameInspector.h"
#include "Cafe/OS/RPL/rpl.h"
#include "Cafe/OS/RPL/rpl_structs.h"
#include "Cafe/OS/RPL/rpl_symbol_storage.h"
#include "Cafe/HW/Espresso/PPCState.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/CafeSystem.h"
#include "Cemu/PPCAssembler/ppcAssembler.h"
#include "Common/ExceptionHandler/ExceptionHandler.h"
#include <cstring>

std::map<uint32, uint32> g_mem;
std::vector<std::string> g_crashLines;
struct Sym { uint32 addr; const char* lib; const char* name; };
std::vector<Sym> g_syms;
std::vector<RPLStoredSymbol> g_stored;
std::vector<RPLModule*> g_modules;

bool memory_isAddressRangeAccessible(MPTR a, uint32 s) { for (uint32 o = 0; o < s; o += 4) if (!g_mem.count(a + o)) return false; return true; }
uint32 memory_readU32(uint32 a) { return g_mem.at(a); }
void CrashLog_WriteHeader(const char* h) { g_crashLines.push_back(std::string("== ") + h + " =="); }
void CrashLog_WriteLine(std::string_view t, bool) { g_crashLines.emplace_back(t); }
namespace CafeSystem { uint64 GetForegroundTitleId() { return 0x0005000010142300ull; } uint32 GetRPXHashBase() { return 0xABCD1234; } std::string GetForegroundTitleName() { return "Mock Game"; } }
std::span<RPLModule*> RPLLoader_GetModuleList() { return std::span<RPLModule*>(g_modules.data(), g_modules.size()); }
RPLStoredSymbol* rplSymbolStorage_getByAddress(MPTR a) { for (auto& s : g_stored) if (s.address == a) return &s; return nullptr; }
RPLStoredSymbol* rplSymbolStorage_getByClosestAddress(MPTR a) { RPLStoredSymbol* best = nullptr; for (auto& s : g_stored) if (s.address <= a && (!best || s.address > best->address)) best = &s; return best; }

// disassemblatore minimale con gli stessi campi della struttura reale
const char* ppcAssembler_getInstructionName(uint32 op) { return op == PPCASM_OP_BL ? "BL" : op == PPCASM_OP_LWZ ? "LWZ" : op == PPCASM_OP_ADDI ? "ADDI" : "UKN"; }
void ppcAssembler_disassemble(uint32 va, uint32 opc, PPCDisassembledInstruction* d) {
	std::memset(d, 0, sizeof(*d)); d->ppcAsmCode = PPCASM_OP_UKN;
	uint32 primary = opc >> 26;
	if (primary == 18 && (opc & 1)) { int32_t disp = (int32_t)(opc & 0x03FFFFFC); if (disp & 0x02000000) disp -= 0x04000000; d->ppcAsmCode = PPCASM_OP_BL; d->operandMask = 1; d->operand[0].type = PPCASM_OPERAND_TYPE_CIMM; d->operand[0].immU32 = va + disp; }
	else if (primary == 32) { d->ppcAsmCode = PPCASM_OP_LWZ; d->operandMask = 3; d->operand[0].type = PPCASM_OPERAND_TYPE_GPR; d->operand[0].registerIndex = (opc >> 21) & 31; d->operand[1].type = PPCASM_OPERAND_TYPE_MEM; d->operand[1].registerIndex = (opc >> 16) & 31; d->operand[1].immS32 = (int16_t)(opc & 0xFFFF); }
	else if (primary == 14) { d->ppcAsmCode = PPCASM_OP_ADDI; d->operandMask = 7; d->operand[0].type = PPCASM_OPERAND_TYPE_GPR; d->operand[0].registerIndex = (opc >> 21) & 31; d->operand[1].type = PPCASM_OPERAND_TYPE_GPR; d->operand[1].registerIndex = (opc >> 16) & 31; d->operand[2].type = PPCASM_OPERAND_TYPE_IMM; d->operand[2].isSignedImm = true; d->operand[2].immS32 = (int16_t)(opc & 0xFFFF); }
}
static uint32 bl(uint32 from, uint32 to) { return 0x48000001u | ((to - from) & 0x03FFFFFCu); }
int main() {
	g_syms = {};
	g_stored = { {0x02100000, (void*)"nn_boss", (void*)"FooBar", 0, nullptr}, {0x02975900, (void*)"", (void*)"UpdateScene__5CGame", 0, nullptr}, {0x024d7000, (void*)"", (void*)"LoadLevel__6CWorld", 0, nullptr} };
	// codice del chiamato: addi r4,r31,0x10; bl nn_boss.FooBar; ori r0,r0,0; lwz r0,0x18(r3)  <- guasto
	g_mem[0x02975a00] = 0x389F0010; g_mem[0x02975a04] = bl(0x02975a04, 0x02100000); g_mem[0x02975a08] = 0x60000000; g_mem[0x02975a0c] = 0x80030018; g_mem[0x02975a10] = 0x60000000;
	// chiamante: bl UpdateScene alla 0x024d71d0
	g_mem[0x024d71d0] = bl(0x024d71d0, 0x02975900); g_mem[0x024d71d4] = 0x60000000;
	// pila: sp=0x0e27ff58 -> back chain 0x0e27ff98 ; [0x0e27ff98+4] = return 0x024d71d4
	g_mem[0x0e27ff58] = 0x0e27ff98; g_mem[0x0e27ff5c] = 0; g_mem[0x0e27ff98] = 0x0e27ffd8; g_mem[0x0e27ff9c] = 0x024d71d4; g_mem[0x0e27ffd8] = 0x0e280018; g_mem[0x0e27ffdc] = 0x02000104; g_mem[0x02000100] = bl(0x02000100, 0x024d7000); g_mem[0x02000104] = 0x60000000;
	PPCInterpreter_t cpu{}; cpu.instructionPointer = 0x02975a0c; cpu.gpr[3] = 0; cpu.gpr[1] = 0x0e27ff58; cpu.spr.LR = 0x024d71d4;
	GameInspector::LogCrashContext(&cpu);
	for (auto& l : g_crashLines) printf("%s\n", l.c_str());
	// ---- scansione
	std::printf("\n#### SCANSIONE ####\n");
	static RPLModule m; m.moduleName = "mock.rpx"; m.regionMappingBase_text = {0x02000000}; m.regionSize_text = 0x40;
	g_modules = { &m };
	uint32 words[] = { 0x7C0001D0, 0x7C0007AC, 0x7C0007AC, 0x60000000, 0x7C000194, 0x7C000374, 0x7C0000AC, 0x7C00006C, 0, 0, 0, 0, 0x7C0001D0, 0, 0, 0 };
	for (int i = 0; i < 16; i++) g_mem[0x02000000 + i * 4] = words[i];
	g_stored.push_back({0x02000000, (void*)"", (void*)"HotLoop__5CGame", 0, nullptr});
	std::filesystem::remove_all("/tmp/mock_userdata");
	GameInspector::ScanLoadedModules();
	std::printf("progress dopo la scansione: %d\n", GameInspector::GetProgress());
	std::ifstream f("/tmp/mock_userdata/inspector/0005000010142300_abcd1234_scan.txt"); std::string line; while (std::getline(f, line)) std::printf("%s\n", line.c_str());
	GameInspector::ScanLoadedModules(); std::printf("seconda esecuzione (cache): progress=%d\n", GameInspector::GetProgress());
	return 0;
}
