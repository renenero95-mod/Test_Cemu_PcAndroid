#pragma once

#include <cstdint>
#include <string>

struct PPCInterpreter_t;

// "Inspector point": checks of the game code made by Cemu itself.
// - ScanLoadedModules() runs once, after the game modules are loaded and before the game starts, and stores a report in <user data>/inspector
// - LogCrashContext() adds an annotated disassembly of the code around the faulting instruction to the crash log
namespace GameInspector
{
	// progress of the scan for the user interface: -1 when nothing is running, 0-99 while scanning, 100 when finished
	int32_t GetProgress();
	std::string GetStage();

	void ScanLoadedModules();

	void LogCrashContext(const PPCInterpreter_t* hCPU);
}
