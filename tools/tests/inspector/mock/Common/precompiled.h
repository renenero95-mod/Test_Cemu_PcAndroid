#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <string_view>
#include <vector>
#include <span>
#include <format>
#include <filesystem>
#include <map>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <fstream>
#include <cctype>
using uint8 = uint8_t; using uint16 = uint16_t; using uint32 = uint32_t; using uint64 = uint64_t;
using sint8 = int8_t; using sint16 = int16_t; using sint32 = int32_t; using sint64 = int64_t;
using MPTR = uint32;
namespace fs = std::filesystem;
namespace fmt { using std::format; }
enum class LogType { Force };
template<typename... Args> void cemuLog_log(LogType, std::format_string<Args...> f, Args&&... a) { printf("[log] %s\n", std::format(f, std::forward<Args>(a)...).c_str()); }
namespace boost { namespace container { template<class T, int N> using small_vector = std::vector<T>; } }
