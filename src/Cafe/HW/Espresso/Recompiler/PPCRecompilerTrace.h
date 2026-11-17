#pragma once

// Verbose JIT tracing (per-function phase logs, queue logs, register allocator dumps for specific addresses).
// LogType::Force is written on every call, which is costly on mobile storage -> OFF by default.
// Enable with the CMake option CEMU_JIT_TRACE (or -DCEMU_JIT_TRACE=1).
// PPCREC_TRACE keeps the statement compiled (if constexpr) so it cannot rot, with zero runtime cost when off.
#ifndef CEMU_JIT_TRACE
#define CEMU_JIT_TRACE 0
#endif

#define PPCREC_TRACE(...) do { if constexpr (CEMU_JIT_TRACE) { cemuLog_log(LogType::Force, __VA_ARGS__); } } while (0)
