#include <signal.h>
#include <execinfo.h>
#include <string.h>
#include <string>
#include "config/CemuConfig.h"
#include "util/helpers/StringHelpers.h"
#include "ExceptionHandler.h"

#include "Cafe/HW/Espresso/Debugger/GDBStub.h"
#include "Cafe/HW/Espresso/Debugger/GDBBreakpoints.h"

#if BOOST_OS_LINUX
#include "ELFSymbolTable.h"
#endif

#if BOOST_PLAT_ANDROID
#include <boost/stacktrace.hpp>
#include <dlfcn.h>
#include <sys/ucontext.h>
#include <cstdio>
#include <exception>
#include <fstream>
#include <typeinfo>

extern uint8* memory_base; // base of the reserved 4GB Wii U address space (MMU.cpp)

namespace
{
	// returns the line of /proc/self/maps that contains addr (range, permissions, backing file) or an empty string
	std::string AndroidDescribeAddress(uint64 addr)
	{
		std::ifstream maps("/proc/self/maps");
		std::string line;
		while (std::getline(maps, line))
		{
			unsigned long long start = 0, end = 0;
			if (std::sscanf(line.c_str(), "%llx-%llx", &start, &end) == 2 && addr >= start && addr < end)
				return line;
		}
		return {};
	}

	std::string AndroidDescribeCodeAddress(uint64 addr)
	{
		Dl_info dlInfo{};
		if (addr != 0 && dladdr(reinterpret_cast<void*>(addr), &dlInfo) && dlInfo.dli_fname)
		{
			const std::string_view fileName{dlInfo.dli_fname};
			const size_t slash = fileName.find_last_of('/');
			std::string text = fmt::format("{}+0x{:x}", slash == std::string_view::npos ? fileName : fileName.substr(slash + 1), addr - reinterpret_cast<uint64>(dlInfo.dli_fbase));
			if (dlInfo.dli_sname)
				text += fmt::format(" ({})", dlInfo.dli_sname);
			return text;
		}
		return "not inside a loaded library (generated JIT code or unknown memory)";
	}

	// extra information that makes crashes inside the PPC recompiler diagnosable
	void AndroidWriteCrashContext(const siginfo_t* info, const void* context)
	{
#if defined(__aarch64__)
		const auto* uc = static_cast<const ucontext_t*>(context);
		const uint64 pc = uc->uc_mcontext.pc;
		const uint64 faultAddr = reinterpret_cast<uint64>(info->si_addr);
		CrashLog_WriteLine("-----------------------------------------");
		CrashLog_WriteLine("   Android fault details");
		CrashLog_WriteLine("-----------------------------------------");
		const char* codeName = "other";
		if (info->si_signo == SIGSEGV)
			codeName = info->si_code == SEGV_MAPERR ? "address not mapped" : (info->si_code == SEGV_ACCERR ? "access not permitted" : "other");
		CrashLog_WriteLine(fmt::format("signal {} code {} ({}) fault address 0x{:x}", info->si_signo, info->si_code, codeName, faultAddr));
		CrashLog_WriteLine(fmt::format("PC 0x{:x}: {}", pc, AndroidDescribeCodeAddress(pc)));
		const uint64 lr = uc->uc_mcontext.regs[30];
		CrashLog_WriteLine(fmt::format("LR 0x{:x}: {}", lr, AndroidDescribeCodeAddress(lr)));
		CrashLog_WriteLine(fmt::format("SP 0x{:x}", static_cast<uint64>(uc->uc_mcontext.sp)));
		const std::string pcMapping = AndroidDescribeAddress(pc);
		if (!pcMapping.empty())
			CrashLog_WriteLine(fmt::format("PC mapping: {}", pcMapping));
		if (info->si_signo == SIGSEGV || info->si_signo == SIGBUS)
		{
			const uint64 base = reinterpret_cast<uint64>(memory_base);
			if (memory_base && faultAddr >= base && faultAddr < base + 0x100000000ull)
				CrashLog_WriteLine(fmt::format("The fault address is inside the Wii U memory space: guest address 0x{:08x}", static_cast<uint32>(faultAddr - base)));
			else
				CrashLog_WriteLine(fmt::format("The fault address is outside of the Wii U memory space (base 0x{:x})", base));
			const std::string faultMapping = AndroidDescribeAddress(faultAddr);
			CrashLog_WriteLine(fmt::format("Fault mapping: {}", faultMapping.empty() ? std::string("none") : faultMapping));
		}
		for (int i = 0; i < 31; i += 4)
		{
			std::string line;
			for (int j = i; j < i + 4 && j < 31; j++)
				line += fmt::format("x{:<2}=0x{:016x} ", j, static_cast<uint64>(uc->uc_mcontext.regs[j]));
			CrashLog_WriteLine(line);
		}
		CrashLog_WriteLine("");
#endif
	}

	// std::terminate is reached when a C++ exception is not caught (e.g. UnrecoverableError in the render thread): record what it was
	[[noreturn]] void AndroidTerminateHandler()
	{
		if (CrashLog_Create())
		{
			std::string what = "terminate() called without an active exception";
			if (const std::exception_ptr ex = std::current_exception())
			{
				try
				{
					std::rethrow_exception(ex);
				}
				catch (const std::exception& e)
				{
					what = fmt::format("Unhandled C++ exception ({}): {}", typeid(e).name(), e.what());
				}
				catch (...)
				{
					what = "Unhandled C++ exception of unknown type";
				}
			}
			CrashLog_WriteLine(what);
			CrashLog_WriteLine(to_string(boost::stacktrace::stacktrace()));
			CrashLog_SetOutputChannels(false, true);
			ExceptionHandler_LogGeneralInfo();
			CrashLog_SetOutputChannels(true, true);
		}
		_Exit(1);
	}
}
#endif

#if BOOST_OS_LINUX && !BOOST_PLAT_ANDROID
void DemangleAndPrintBacktrace(char** backtrace, size_t size)
{
	ELFSymbolTable symTable;
	for (char** i = backtrace; i < backtrace + size; i++)
	{
		std::string_view traceLine{*i};

		// basic check to see if the backtrace line matches expected format
		size_t parenthesesOpen = traceLine.find_last_of('(');
		size_t parenthesesClose = traceLine.find_last_of(')');
		size_t offsetPlus = traceLine.find_last_of('+');
		if (!parenthesesOpen || !parenthesesClose || !offsetPlus ||
			 offsetPlus < parenthesesOpen || offsetPlus > parenthesesClose)
		{
			// fall back to default string
            CrashLog_WriteLine(traceLine);
			continue;
		}

		// attempt to resolve symbol from regular symbol table if missing from dynamic symbol table
		uint64 newOffset = -1;
		std::string_view symbolName = traceLine.substr(parenthesesOpen+1, offsetPlus-parenthesesOpen-1);
		if (symbolName.empty())
		{
			uint64 symbolOffset = StringHelpers::ToInt64(traceLine.substr(offsetPlus+1,offsetPlus+1-parenthesesClose-1));
			symbolName = symTable.OffsetToSymbol(symbolOffset, newOffset);
		}

        CrashLog_WriteLine(traceLine.substr(0, parenthesesOpen+1), false);

        CrashLog_WriteLine(boost::core::demangle(symbolName.empty() ? "" : symbolName.data()), false);

		// print relative or existing symbol offset.
        CrashLog_WriteLine("+", false);
		if (newOffset != -1)
		{
            CrashLog_WriteLine(fmt::format("0x{:x}", newOffset), false);
            CrashLog_WriteLine(traceLine.substr(parenthesesClose));
		}
		else
		{
            CrashLog_WriteLine(traceLine.substr(offsetPlus+1));
		}
	}
}
#endif

// handle signals that would dump core, print stacktrace and then dump depending on config
void handlerDumpingSignal(int sig, siginfo_t *info, void *context)
{
#if defined(ARCH_X86_64) && BOOST_OS_LINUX
	// Check for hardware breakpoints
	if (info->si_signo == SIGTRAP && info->si_code == TRAP_HWBKPT)
	{
		uint64 dr6 = _ReadDR6();
		g_gdbstub->HandleAccessException(dr6);
		return;
	}
#endif

    if(!CrashLog_Create())
        return; // give up if crashlog was already created

    char* sigName = strsignal(sig);
	if (sigName)
	{
		printf("%s!\n", sigName);
		CrashLog_WriteLine(fmt::format("Error: signal {}:", sigName));
	}
	else
	{
		// should never be the case
		printf("Unknown core dumping signal!\n");
		CrashLog_WriteLine(fmt::format("Error: signal {}:", sig));
	}

#if BOOST_PLAT_ANDROID
    CrashLog_WriteLine(to_string(boost::stacktrace::stacktrace()));
    AndroidWriteCrashContext(info, context);
#else
	void* backtraceArray[128];
	size_t size;

	// get void*'s for all entries on the stack
	size = backtrace(backtraceArray, 128);
    // replace the deepest entry with the actual crash address
#if defined(ARCH_X86_64) && BOOST_OS_LINUX > 0
    ucontext_t *uc = (ucontext_t *)context;
    backtraceArray[0] = (void *)uc->uc_mcontext.gregs[REG_RIP];
#endif

#if BOOST_OS_LINUX
	char** symbol_trace = backtrace_symbols(backtraceArray, size);

	if (symbol_trace)
	{
        DemangleAndPrintBacktrace(symbol_trace, size);
		free(symbol_trace);
	}
	else
	{
        CrashLog_WriteLine("Failed to read backtrace");
	}
#else
	backtrace_symbols_fd(backtraceArray, size, STDERR_FILENO);
#endif
#endif

    std::cerr << fmt::format("\nStacktrace and additional info written to:") << std::endl;
    std::cerr << cemuLog_GetLogFilePath().generic_string() << std::endl;

    CrashLog_SetOutputChannels(false, true);
    ExceptionHandler_LogGeneralInfo();
    CrashLog_SetOutputChannels(true, true);

	if (GetConfig().crash_dump == CrashDump::Enabled)
	{
		// reset signal handler to default and re-raise signal to dump core
		signal(sig, SIG_DFL);
		raise(sig);
		return;
	}
	// exit process ignoring all issues
	_Exit(1);
}

void handler_SIGINT(int sig)
{
	/*
	 * Received when pressing CTRL + C in a console
	 * Ideally should be exiting cleanly after saving settings but currently
	 * there's no clean exit pathway (at least on linux) and exiting the app
	 * by any mean ends up with a SIGABRT from the standard library destroying
	 * threads.
	 */
	_Exit(0);
}

void ExceptionHandler_Init()
{
#if BOOST_PLAT_ANDROID
	std::set_terminate(AndroidTerminateHandler);
#endif
	struct sigaction action;
	action.sa_flags = 0;
	sigfillset(&action.sa_mask); // don't allow signals to be interrupted

	action.sa_handler = handler_SIGINT;
	sigaction(SIGINT, &action, nullptr);
	sigaction(SIGTERM, &action, nullptr);

    action.sa_flags = SA_SIGINFO;
    action.sa_handler = nullptr;
	action.sa_sigaction = handlerDumpingSignal;
	sigaction(SIGABRT, &action, nullptr);
	sigaction(SIGBUS, &action, nullptr);
	sigaction(SIGFPE, &action, nullptr);
	sigaction(SIGILL, &action, nullptr);
	sigaction(SIGIOT, &action, nullptr);
	sigaction(SIGQUIT, &action, nullptr);
	sigaction(SIGSEGV, &action, nullptr);
	sigaction(SIGSYS, &action, nullptr);
	sigaction(SIGTRAP, &action, nullptr);
}
