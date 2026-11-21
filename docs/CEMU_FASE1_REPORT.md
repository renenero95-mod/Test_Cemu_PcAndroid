# Cemu Android Ultimate — Report Fasi 1, 1b, 2a, 2b, 2c (analisi, pulizia, collo di bottiglia JIT)

Target: Poco F6 Pro, Snapdragon 8 Gen 2, Vulkan. Nessuna funzionalità nuova in questa fase: prima si toglie ciò che costa senza dare nulla.

## Come è fatto il progetto (minimo utile)
- Base Cemu + app Android (Kotlin/Compose in `src/android`, nativo C++ via CMake+vcpkg, build con Gradle, solo arm64-v8a, minSdk 30).
- Aggiunte tue/precedenti: `PPCPreExecutionAnalyzer` (README "Cemu+ Inspection"), `util/CemuProfiling.h`, `CemuThreadWatchdog.h`, `CemuDiagV2.h`, timer di debug nel JIT/RA/present, limite `MERGE CAP` a 600 segmenti, coda JIT prioritaria per 0x067xxxxx.
- Gli `patch_*.py` in root erano già applicati (tranne `patch_ra_cap_v3`, mai applicato). Spostati in `tools/legacy_patches/`.

## Problemi trovati
| # | Problema | Effetto | Prova |
|---|---|---|---|
| 1 | Analizzatore: 1 nodo `unordered_map` per ogni istruzione PPC | ~320–350 MB RAM extra e ~0,4–0,5 s di scansione con 24–32 MB di codice; rischio kill di Android su giochi grandi | benchmark, sotto |
| 2 | `elf.cpp`: l'analizzatore leggeva `shSize` byte senza limitarli ai dati presenti nel file | lettura fuori buffer con ELF troncato/malformato (crash) | lettura codice + test "input sbagliato" sul nuovo analizzatore |
| 3 | Profiling attivo di default (timer su ogni chiamata HLE, contatori nell'interprete, audio, draw call; `thread_local` per istruzione interpretata) | ~79 ns/chiamata (1 thread) e ~225 ns (3 thread sullo stesso contatore) nel microbenchmark x86 | benchmark |
| 4 | Log `Force` nel percorso caldo: `[ATTEMPT-0675]` a ogni ingresso JIT, `[PHASE]`×3 + `[RECOMPILE]` per funzione, `[QUEUE]`, `[PPC PC SAMPLE]` ogni 4096 istruzioni | scritture continue su storage/logcat, stutter durante il warm-up del JIT | lettura codice (impatto da misurare su device) |
| 5 | Watchdog: thread che logga ogni 2 s | rumore + thread inutile | lettura codice |
| 6 | `VulkanRenderer.cpp` usava `CemuWatchdog::` senza includere l'header | codice morto oggi, errore di compilazione se si attivano le diagnostiche | controllo include |
| 7 | CI: nessuna cache, dipendenze solo da submodule | build lente, falliscono se le cartelle sono vuote | lettura workflow |

## Modifiche fatte (tutte reversibili)
1. **Analizzatore riscritto** (`Analyzer/PPCPreExecutionAnalyzer.*`): salva un riassunto per sezione (range, istruzioni, branch, `blr`, istogramma opcode), memoria O(n° sezioni). Input non valido → ignorato (`false`). Rescan di range sovrapposti sostituisce i dati vecchi. API vecchia mantenuta (`ScanExecutableSection`, `InvalidateRange`, `ClearTitle`, `Lookup`) + `IsKnownCode`.
2. **Politica JIT invariata**: funzioni > 10000 byte restano all'interprete solo se l'analizzatore conosce l'indirizzo (stesso comportamento di prima). Soglia ora è la costante `PPCREC_ANALYZER_MAX_JIT_RANGE_BYTES`; il log `[ANALYZER]` ora mostra la dimensione.
3. **`elf.cpp`**: scansione limitata a `min(shSize, size - shOffset)`.
4. **Diagnostiche spente di default**, accendibili senza toccare il codice: CMake `-DCEMU_ENABLE_DIAGNOSTICS=ON`, `-DCEMU_JIT_TRACE=ON`; in CI: input `diagnostics` / `jit_trace` del workflow (Run workflow → spunta); in locale: `-PcemuDiagnostics=true`. Tutti gli usi di `CemuDiagV2/CemuWatchdog/CemuProfiling` sono dentro `#if`. Con diagnostiche ON il comportamento è quello di prima (conteggio chiamate invariato).
5. **Log JIT** dietro `PPCREC_TRACE` (compilato con `if constexpr`, zero costo a runtime se OFF). Restano sempre attivi i log utili (scattano solo se qualcosa è lento o saltato): `[ANALYZER]`, `[PHASE SLOW]`, `[OPT SLOW]`, `[MERGE CAP]`, `[PRESENT SLOW]`. `[JIT SLOW]`, `[JIT]`, `[JIT TOP]`, `[PROFILE 5s]` ora compaiono solo con diagnostiche ON.
6. **Workflow**: cache vcpkg+Gradle, `concurrency` (annulla build duplicate), fallback clone delle dipendenze se vuote, input diagnostica.
7. `docs/THIRD_PARTY_DEPENDENCIES.md`, `tests/`, `tools/legacy_patches/README.md`.

Non toccati di proposito: priorità coda 0x067xxxxx (non so a quale gioco serva: se non lo sai, vedi Fase 2), `MERGE CAP`, timer `PRESENT SLOW` (costo trascurabile, informativi).

## Test eseguiti (sandbox senza rete/NDK: build completa NON possibile qui)
Eseguibili da root: `bash tests/standalone/run_tests.sh` e `python3 tests/static/ifdef_brace_check.py`.
- **Semplice**: scan/lookup/invalidate su un mini-codice con `bl`, `blr`, `bctr`. OK.
- **Sbagliato**: nullptr, size 0/3/15, base non allineata, range che supera 4 GiB, size 8 GiB, invalidate con range invertito, doppio `ClearTitle`, rescan sovrapposto. OK.
- **Difficile**: 200 seed × 3000 operazioni casuali confrontate con un modello di riferimento. OK.
- **Aggressivo**: 8 thread (lookup, scan, invalidate, clear contemporanei) ~1,5 s: ASan+UBSan, TSan, -O2. OK, nessuna race.
- **Statico**: bilanciamento `#if/#endif`, `{}`, `()` nei 9 file modificati in tutte le 8 combinazioni di macro. OK; il controllo è stato validato anche con 3 errori inseriti apposta (li trova).
- **Benchmark analizzatore** (x86, dati sintetici): 24 MB codice → vecchio 412 ms / +320 MB; nuovo 27 ms / ~0 MB. 32 MB → 502 ms / +348 MB vs 17 ms / ~0. Lookup: 1M in ~25 ms vs 109–393 ms.
- Limite: i numeri ARM/device possono differire; la compilazione dell'intero progetto avviene solo nella tua GitHub Action.

## Cosa può ancora andare storto
- Errore di compilazione in file non compilabili qui (VulkanRenderer*, PPCRecompiler). Rischio basso (solo `#if` e un include già usato altrove), ma non provato. Se la build fallisce, mandami il log: correggo.
- Il fallback del workflow clona l'ultimo commit delle dipendenze, non quello fissato: preferibile mantenere i submodule.
- `build.gradle.kts` modificato (2 `if`): non compilabile qui.

## Da fare con te (serve il dispositivo)
1. Build normale (diagnostiche OFF) → misura avvio gioco e fluidità. Poi build con `diagnostics=true` e invia il log: conteggi `[ANALYZER] ... too large`, `[PROFILE 5s]`, `[JIT TOP]`.
2. Dimmi quale gioco usi/il problema originale a 0x0675xxxx.

## Idee Fase 2+ (non applicate: servono misure)
| Idea | Guadagno atteso | Rischio | Verifica |
|---|---|---|---|
| JIT a livelli: funzioni grandi compilate senza passi costosi invece di restare all'interprete per sempre | alto sui giochi con funzioni grandi | medio | A/B su device, contare `[ANALYZER]` |
| Coda JIT "hot-first" generica (contatori di visita) al posto dell'hardcode 0x067x | medio | basso | log `[JIT TOP]` |
| 2 thread JIT (8 core disponibili) | warm-up più veloce | alto (allocatore codice/stato condiviso) | audit thread-safety + stress |
| Usare i dati dell'analizzatore (stima n° funzioni da `blr`, densità branch) per pre-dimensionare tabelle e priorità | basso–medio | basso | test sintetici |
| Verificare pipeline cache Vulkan salvata su disco e pre-riscaldata, scelta driver Turnip via libadrenotools | alto sugli stutter shader | medio | da leggere in Fase 2 (non ancora analizzato) |
| Frame pacing / hint prestazioni Android (affinità core grandi, PerformanceHint) | medio | medio | test su device |
Un repository esterno (opzionale) non serve ancora: ne parliamo dopo la Fase 2.


---
## Fase 1b — le due patch che bloccavano l'avvio (audio Mario Kart, watchdog)
**Trovate nel codice**
- **Watchdog all'avvio** = `CemuWatchdog::Start()` nel thread JIT + un `Touch` a ogni timeslice dei thread PPC + log ogni 2 s. Ora compilato fuori di default (Fase 1).
- **Patch audio** = guardie aggiunte in `snd_user.cpp` (`MIXInitDeviceControl`, `_MIXControl_SetDevicePan`): controlli su puntatori nulli, indici e "audio non inizializzato". Tutti i `return` avvengono PRIMA di `AXVoiceBegin`, quindi non lasciano lock aperti: da sole non possono bloccare il gioco. Unico effetto collaterale: il dispositivo `AX_DEV_RMT` (Wii remote) veniva rifiutato con una riga di log `Force` a ogni chiamata (MK8 inizializza molte voci al secondo).
- **Debug mirato a due funzioni di gioco** (0x05BBECA0 e 0x0596BE6C) dentro il register allocator: log `[REGALLOC ...]` e contatori.

**Modifiche**
1. RMT ora accettato in silenzio in `_MIXControl_SetDevicePan` (nessuna scrittura, come prima, ma senza log). Tipi davvero invalidi: log una sola volta.
2. I 4 blocchi di log per quegli indirizzi + `[REGALLOC RETRY]`/`[REGALLOC STRATEGY]` ora dietro `CEMU_JIT_TRACE` (nuovo header `Recompiler/PPCRecompilerTrace.h`, condiviso con `PPCRecompiler.cpp`). Il comportamento dell'allocatore non cambia.
3. Le guardie audio restano (evitano accessi fuori array).

**Causa del blocco "parte e torna al menù": NON identificata con certezza** (serve il dispositivo). Nelle ricerche fatte non c'è codice che chiude il gioco di proposito: l'uscita avviene solo da `onQuit` (manuale) o da un crash nativo. Ipotesi, in ordine: (1) log `Force` in percorsi caldi + profiling/watchdog (ora tolti: se era questo, è già risolto); (2) JIT: funzione enorme o retry del register allocator molto lungo; (3) crash nativo.
**Cosa mandarmi se succede ancora:** il file `log.txt` nella cartella dati di Cemu (su crash nativo Cemu ci scrive lo stack), meglio se la build è fatta con `diagnostics` e `jit_trace` attivi. Con quello indico la riga esatta.

**Test:** controllo statico esteso a 12 file (OK in tutte le 8 combinazioni di macro), test analizzatore invariati (OK). Un primo tentativo di modifica automatica del register allocator aveva sbagliato i punti di chiusura (le `{}` dentro le stringhe di log): l'ho annullato e rifatto ignorando le stringhe; il diff finale contiene solo righe `#if/#endif` e due chiamate `PPCREC_TRACE`.


---
## Fase 2a — crash da lettura di puntatori NULL del gioco (probabile causa di "parte e torna al menù")
**Trovato.** Su Linux/Android la memoria guest da 4 GB è riservata `PROT_NONE` (`util/MemMapper/MemMapperUnix.cpp`) e nessuna `MMURange` copre l'indirizzo guest 0x0–0xFFFF (`CODE_LOW0` parte da 0x10000). Sulla Wii U una lettura tipo `lwz r12,0x494(r3)` con `r3=0` non va in errore; su Android invece scatta SIGSEGV e l'app si chiude. Il progetto aveva già una correzione (`CemuFixer_EnsureLowGuestMemory` in `coreinit_MEM.cpp`), ma copriva solo 0x0–0x1FFF e sostituiva l'intero intervallo con un unico `mmap`.

**Modificato.** Ora copre 0x0–0xFFFF (tutto il "buco" sotto `CODE_LOW0`, 64 KB di RAM) e mappa solo le pagine realmente illeggibili, una a una (pagine zero, lettura/scrittura): ciò che fosse già mappato non viene mai azzerato. Dimensione calcolata sulla pagina del dispositivo (4K o 16K).
**Effetto atteso:** letture/scritture tramite puntatore NULL con offset fino a 64 KB non fanno più crashare; il gioco legge zeri come su console. Rischio: nasconde un eventuale errore reale del gioco (prima crash, ora prosegue); nessun impatto sulle prestazioni.
**Non provato su dispositivo.** Se il tuo blocco era un crash con offset > 0x2000 (altrimenti la vecchia correzione bastava), questa modifica lo risolve; se era altro, resta l'indagine con `log.txt`.

**Test (codice reale estratto da `coreinit_MEM.cpp`, `tests/standalone/lowmem_test.cpp`):** semplice (riserva PROT_NONE → lettura/scrittura OK, 0x10000 resta illeggibile), sbagliato (base nulla, chiamate ripetute, regione già piena di dati: invariata), difficile (layout misto: pagine 0 e 2 con dati preservate, le altre mappate), aggressivo (8 thread × 200 chiamate). ASan+UBSan superati. **Correzione:** nella prima versione TSan segnalava una race quando la funzione veniva chiamata da più thread insieme (in produzione è chiamata una volta per avvio del titolo, quindi innocua, ma il test non era pulito e io non me ne ero accorto). Ora la funzione è protetta da un mutex e TSan non segnala più nulla.

**Altro controllato, nessun problema:** `CafeSystem.cpp` (solo stile originale), `MERGE CAP` nel register allocator (esce all'inizio, prima di modificare qualsiasi stato: sicuro).


---
## Fase 2b — IL PROBLEMA PRINCIPALE: la CI compilava il codice nativo in *Debug*
**Trovato (verificato leggendo CMake, workflow e `precompiled.h`).** La workflow eseguiva `./gradlew :app:assembleDebug`. Per la variante debug Gradle passa a CMake `-DCMAKE_BUILD_TYPE=Debug`, quindi tutto Cemu (interprete, JIT, Latte/Vulkan, audio) veniva compilato **senza ottimizzazione (-O0)** e **senza LTO** (`CMAKE_INTERPROCEDURAL_OPTIMIZATION` è attivo solo per Release/RelWithDebInfo). In più `CMakeLists.txt` definisce `CEMU_DEBUG_ASSERT` per Debug: in quel caso `cemu_assert_debug` (se falso), `cemu_assert_unimplemented()` e `cemu_assert_suspicious()` eseguono `raise(SIGTRAP)`. L'handler di Cemu (`ExceptionHandler_posix.cpp`) scrive un crash log e chiude l'app. In una build Release queste tre funzioni sono vuote (come nel Cemu desktop che usi di solito).

**Perché conta per i tuoi blocchi.** Molti giochi (Mario Kart 8 incluso) attraversano percorsi "non implementati/sospetti" senza conseguenze in Release. In Debug ognuno chiude il gioco: "parte e subito dopo torna al menù". Le patch audio/watchdog erano probabilmente tentativi di aggirare asserzioni una per una. È la spiegazione più coerente con il sintomo, ma **non l'ho potuta confermare su dispositivo**. Prova: in `log.txt` dopo il crash c'è `Trace/breakpoint trap` (segnale 5) con lo stack.

**Modificato.**
1. Workflow: nuovo input `build_type` (**Release** di default, oppure Debug). Il nativo ora è ottimizzato, con LTO e asserzioni spente. L'APK Release usa la chiave di debug come firma (già previsto in `build.gradle.kts` quando non ci sono segreti).
2. Chiave di firma stabile tra le build (cache di `~/.android/debug.keystore`): prima ogni run generava una chiave nuova, quindi un APK nuovo non si installava sopra il vecchio. Se la cache resta inutilizzata 7 giorni GitHub la cancella e la firma cambia; per averla permanente servono i segreti del repository (`ANDROID_STORE_FILE`, ecc.).
3. `proguard-rules.pro`: regola `-keep` su tutto `info.cemu.cemu.nativeinterface.**` (il codice nativo cerca quelle classi per nome; R8 non lo vede). Quasi tutte avevano già `@Keep`, `NativeException` no.
4. Upload dell'APK per qualsiasi variante; il nome dell'artefatto include il tipo.

**Cose da sapere prima di installare**
- L'APK Release ha un altro identificativo (`info.cemu.cemu`) rispetto al Debug (`info.cemu.cemu.debug`): si installa **accanto** al vecchio e parte **senza** impostazioni, giochi, chiavi e shader cache. Comodo per confrontare, ma devi riconfigurare le cartelle (e rigenerare le shader cache).
- La prima build Release è più lenta (LTO + librerie vcpkg Release); le successive usano la cache.
- Non provato qui (niente rete/NDK): se il Release fallisce in compilazione o R8, rilancia con `build_type=Debug` e mandami il log dell'errore.
- In Debug restano attivi i controlli: scegli Debug solo per cercare un bug.

**Impatto atteso.** Molto superiore a tutte le altre modifiche messe insieme: codice nativo ottimizzato (ordini di grandezza sull'interprete e sul render thread) e fine dei crash da asserzione. Da misurare su Mario Kart 8: avvio, FPS, `log.txt`.


---
## Fase 2c — collo di bottiglia nel thread JIT e lavoro inutile
**Trovato.** Il ciclo del thread JIT (`PPCRecompiler_thread`) aveva un limite aggiunto a mano: dopo 64 estrazioni dalla coda tornava a dormire 10 ms. Anche le voci già compilate (che si scartano in pochi nanosecondi) consumavano quel budget. Risultato: tetto di circa 6400 estrazioni al secondo, anche quando il lavoro vero è poco. All'avvio di un gioco la coda può contenere decine di migliaia di indirizzi; finché non è svuotata il gioco resta nell'interprete, molto più lento. Il Cemu originale svuota la coda per intero.

**Modificato.** Il ciclo ora svuota sempre la coda (priorità alta prima, come prima) e controlla il segnale di stop a ogni giro. Il thread resta a priorità ridotta (`nice(10)`), quindi non serve altro limite.

**Simulazione** (modello a tempo virtuale in `tests/standalone/jit_queue_model.cpp`: è un modello della logica, non una misura su dispositivo; la percentuale di voci già compilate è un'ipotesi). Coda di 20000 indirizzi in burst:
| tempo medio di compilazione | voci già compilate | vecchio | nuovo |
|---|---|---|---|
| 0,2 ms | 0 / 50 / 80% | 7,1 / 5,1 / 3,9 s | 4,0 / 2,0 / 0,8 s |
| 0,5 ms | 0 / 50 / 80% | 13,1 / 8,1 / 5,1 s | 10,0 / 5,0 / 2,0 s |
| 2,0 ms | 0 / 50 / 80% | 43,1 / 23,1 / 11,1 s | 40,0 / 20,0 / 8,0 s |
Controlli superati: stesso insieme di funzioni compilate, ordine di priorità rispettato, mai più lento. Il guadagno reale dipende dai tuoi giochi: lo vedi come "il gioco diventa fluido prima dopo l'avvio".

**Altro lavoro inutile tolto.** Il register allocator contava istruzioni e range di *ogni* segmento per *ogni* funzione compilata, solo per una riga di log che ora è spenta: ora il conteggio c'è solo con `CEMU_JIT_TRACE`.

**Altre conferme sul problema "Debug".** Nell'interprete l'istruzione tutta a zero (salto in memoria vuota) in Debug esegue `assert_dbg()` e poi entra in un `while(true) sleep(1s)`: un blocco permanente di quel thread. In Release salta semplicemente l'istruzione. È un altro modo in cui la build Debug poteva "bloccarsi" dopo l'avvio.

**Esaminato, nessun problema:** thread grafico (`LatteThread`: attese solo all'avvio), thread audio, percorso input JNI (stile originale), `PPCRecompiler_visitAddressNoBlock` (identico all'originale tranne la coda prioritaria 0x067x).

## Ancora da verificare sul telefono (non applicato)
1. `nice(10)` sul thread JIT: con tutti i core occupati rallenta il riscaldamento; potrebbe convenire toglierlo. Va provato A/B.
2. Il ciclo di retry del register allocator (`while(true)`) non ha un limite: se non fa progressi il thread JIT resta bloccato. Un limite sicuro richiede di abbandonare la funzione in modo pulito; da fare con un log reale in mano (`[REGALLOC RETRY]` con `jit_trace`).
3. Affinità dei thread PPC/GPU sui core grandi (Prime + Performance) del Snapdragon 8 Gen 2: idea valida ma il vantaggio non è verificabile senza misure.
