#pragma once
// CEMU_NEURAL=1 enables the learned JIT queue advisor (see NeuroJitAdvisor.h). It is set by CMake (option CEMU_ENABLE_NEURAL);
// without the option the project is built exactly like the plain version.
#ifndef CEMU_NEURAL
#define CEMU_NEURAL 0
#endif
