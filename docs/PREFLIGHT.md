# Controlli prima della build ("preflight")

Servono a scoprire in pochi secondi i fallimenti che altrimenti compaiono dopo un'ora di GitHub Actions. Sono controlli **statici**:
non sostituiscono la compilazione vera, ma coprono le classi di errore già viste e altre prevedibili.

`python3 tools/preflight/preflight.py` (in CI gira da solo dopo `vcpkg` e la verifica delle dipendenze, con `--ci`):
| Controllo | Cosa impedisce |
|---|---|
| Dipendenze | cartelle vuote/incomplete, imgui incompatibile (`ImTextureID`, `FallbackAdvanceX`, `NavInputs`, `ImeWindowHandle`) |
| Percorsi CMake | `dependencies/...` citato da CMake ma inesistente |
| File dei CMakeLists | file sorgente/header nominato in CMake ma assente dallo zip (1051 nomi controllati) |
| #include | `#include "..."` che non si risolve da nessuna parte |
| Android/Gradle | NDK, compileSdk, JDK, ABI (libadrenotools accetta solo arm64-v8a), versione Gradle/AGP, proprietà `-P` lette davvero |
| vcpkg | `vcpkg.json` valido, baseline, porte overlay complete |
| Workflow | YAML valido, script esistenti, ordine dei passi |
| Modifiche al motore | simboli usati senza l'include giusto, `.cpp` riscritti non elencati in CMake (errore di link) |

`tests/preflight/test_preflight.py` inietta 18 errori (NDK diverso, ABI x86_64, include mancante, file assente, baseline vcpkg rotta...) e verifica che
ognuno venga scoperto; esegue poi i veri passi shell della workflow offline con un `git` finto.

## Compilazione "a secco" dei file che abbiamo modificato (fatta fuori dal repository)
Con header finti per le librerie di terze parti (boost, fmt, glm...) ho compilato in modalità **AArch64** (`-fsyntax-only`) ciascun file modificato,
in tre configurazioni (predefinita, diagnostica+trace attive, neurale spento): nessun errore sulle righe che abbiamo toccato.
Il banco di prova è stato validato iniettando errori (compresi quelli nei rami solo-ARM64 e dentro `#if CEMU_PROFILING`): li ha trovati tutti.
Con imgui 1.89.9 le unità `LatteShaderCache.cpp`, `swkbd.cpp`, `LatteOverlay.cpp`, `imgui_impl_vulkan.cpp` non danno errori legati a imgui; rendendo
`ImTextureID` un intero a 64 bit, come fa imgui 1.92, il banco di prova riproduce l'errore "conflicting declaration ... ImTextureID" visto nella tua build.

## Cosa NON è verificabile qui
La compilazione vera di tutto Cemu, il collegamento, il download/compilazione dei pacchetti vcpkg, il tempo e la memoria sul runner, e il comportamento sul telefono.
