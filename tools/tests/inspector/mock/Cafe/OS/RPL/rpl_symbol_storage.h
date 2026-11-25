#pragma once
struct RPLStoredSymbol { MPTR address; void* libName; void* symbolName; uint32 flags; RPLStoredSymbol* previous; };
RPLStoredSymbol* rplSymbolStorage_getByAddress(MPTR address);
RPLStoredSymbol* rplSymbolStorage_getByClosestAddress(MPTR address);
