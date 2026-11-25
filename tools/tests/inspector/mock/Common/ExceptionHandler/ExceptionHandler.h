#pragma once
void CrashLog_WriteHeader(const char* header);
void CrashLog_WriteLine(std::string_view text, bool newLine = true);
