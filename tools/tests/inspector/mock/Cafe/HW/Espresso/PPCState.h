#pragma once
struct PPCInterpreter_t { uint32 instructionPointer; uint32 gpr[32]; struct { uint32 LR; } spr; };
