#pragma once
template<typename T> struct MEMPTR { uint32 mptr; uint32 GetMPTR() const { return mptr; } };
struct RPLModule { MEMPTR<void> regionMappingBase_text; uint32 regionSize_text; std::string moduleName; };
