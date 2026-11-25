# check.md — CemuFixerAPC android-port
Aggiornato: 2026-11-18 · Fase: **v2 dopo i test su telefono: crash JIT da diagnosticare, import update/DLC, opzione bypass**

## Contesto fisso
- Repo `renenero95-mod/CemuFixerAPC`; base PR #1909 (SSimco, 7c2324e) + upstream/main b487cb7 (merge fatto qui). APK da Actions (`build_check.yml`, `assembleRelease`, JDK21). Target: POCO F6 Pro, Snapdragon 8 Gen 2 (Adreno 740), solo Vulkan, arm64-v8a, minSdk 30, NDK 29.0.14206865, AGP 9.2.1.
- Regole utente: chat con poche parole; test li faccio io; a fine progetto `FIX_SPIEGAZIONE.md` + zip modificato.
- Nessuna build/NDK/rete/dispositivo qui → **nessun test dinamico dell'emulatore**. [DEVICE] = da provare sul telefono.

## Fatto (v2, dopo i log dell'utente)
- Build: corretti `FileStreamUnix` e `ANativeWindow_setFrameRate` (dlsym) [da riconfermare con CI].
- Crash Watch Dogs / NSLU: SIGSEGV nel codice JIT (stesso offset `+0xDD0408`); con *Load shared libraries* OFF Watch Dogs parte ma si chiude a inizio gameplay. Aggiunta diagnostica di crash (indirizzo guasto, PC/LR, mappe, registri, eccezioni C++) e flush cache istruzioni JIT.
- Zelda: messaggio *system update* (probabilmente dalle librerie condivise reali); aggiunti log del contenuto del .wua e opzione *Bypass system update check* (default OFF, sperimentale).
- Update/DLC: menu "+" → *Import update or DLC from ZIP* e *Install from folder* (cerca update/DLC dentro qualunque cartella).
- Mario Kart: il log ora dice se l'Adreno supporta il divisore degli attributi vertice (ipotesi paracadute); il merge porta già il supporto upstream, da riprovare.
- Mario Kart (paracadute invisibile): non risolto; Adreno 740 senza `attachment_feedback_loop`/`pipeline_robustness`, driver 2023: provare driver custom.

- v2.2: emulazione del divisore degli attributi (Vulkan), diagnostica e retry delle pipeline fallite (-13), "Load shared libraries" spento di default su Android (prima la schermata diceva spento ma il motore lo accendeva), barriera memoria JIT, menu "Refresh game list", log dei file non trovati col bypass. Dettagli in `FIX_SPIEGAZIONE.md` §8.

- v2.3: tolto il bypass; sezione Generale → Sperimentale (divisore, retry pipeline, Map null page, log dettagliato); piano da 200 idee in `docs/PIANO_200_IDEE.md`.

- v2.4: **Game inspector** dentro Cemu: scansione prima dell'avvio con barra, crash log con disassemblato e origine del puntatore nullo, `log_inspector_point.txt` generato in background al ritorno nel menu e condivisibile («Share inspector report»). Dettagli in `FIX_SPIEGAZIONE.md` §10.

## Fatto (v1)
1. **Scoperta:** lo zip era la PR con solo parte di upstream. Nel bundle `audit_evidence` ho trovato gli alberi completi di upstream/main e PR → **merge a tre vie** (base = PR − patch): **295 file aggiornati, 4 conflitti** (swkbd, CMake×2, README) risolti. Dettagli: `FIX_SPIEGAZIONE.md` §1-2.
2. **Fix applicati:** F2 `@Keep` NativeException · F3 meta.json corrotto/`libraryName` fuori cartella · F4 zip-slip + zip senza cartelle · F6 init Vulkan non ripetuta · F7 copia driver fallita · F8 `message!!` · F9 graphic pack · **F12-F13 touch/overlay bloccati dopo `ACTION_CANCEL`** · F14 `attempt()` e cancellazione coroutine · F15 backup installazione titolo.
   **Idee applicate (opzionali, `optional_I1_I4_I6.patch`):** I1 frame rate 60, I4 touch non batchato, I6 Game Mode — da provare [DEVICE].
3. **Test reali:** simulazione touch/joystick (vecchio bloccato su CANCEL, nuovo ok) · unzip vecchio/nuovo su 13 zip (il vecchio scrive fuori dalla cartella e fallisce senza dir-entry; il nuovo no) · `libraryName` 12/12 · `scripts/verify_tree.py` OK sul finale e 5/5 guasti voluti rilevati · parentesi bilanciate. File in `tools/tests/`.

## Da fare dall'utente (priorità)
- **F1 firma APK:** creare keystore e 3 secret (`ANDROID_STORE_FILE_BASE64`, `ANDROID_KEY_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`; stessa password store/key), altrimenti ogni build ha firma diversa e per aggiornare disinstalli e perdi dati/cache in `Android/data`.
- Push → Actions. Se la build fallisce, incollare il log (sospetti: ZArchive R1, firme cambiate in `VulkanRenderer.h`).
- Prova [DEVICE]: avvio gioco noto con driver di sistema; poi background/foreground ×20; driver custom valido/corrotto.

## Rischi aperti
R1 pin ZArchive: PR `965b66c` (serve `OpenFromStream`) vs upstream `d2c7177` → tenuto PR. R2 `ZipArchive.h` richiede libzip (solo GUI wx, non Android). R3 file orfano `FiberUnix.cpp`. R5 9k righe dal PC: regressioni visibili solo in esecuzione.

## Non applicati (decisione)
- F5 leak `ANativeWindow_acquire` (NativeEmulation.cpp:283): toglierlo rompe il confronto per puntatore della surface (ABA) → serve contatore di generazione [DEVICE].
- Pre-rotazione swapchain (`preTransform=IDENTITY`, SwapchainInfoVk.cpp:~438): guadagno incerto, tocca overlay e touch → prima misurare.

## Idee nuove (assenti nel progetto), in ordine
| ID | Idea | Rischio | Stato |
|---|---|---|---|
| I1 | `ANativeWindow_setFrameRate(…60, FIXED_SOURCE)` in `setSurface` | basso | **applicata (opzionale)** [DEVICE] |
| I4 | `requestUnbufferedDispatch` nel listener touch e overlay | basso | **applicata (opzionale)** [DEVICE] |
| I6 | Game Mode (manifest + `game_mode_config`) | nullo | **applicata (opzionale)** |
| I11 | provare "barriere accurate" OFF su giochi pesanti (impostazione già in UI) | glitch rari | prova [DEVICE] |
| I2 | ADPF `PerformanceHintManager` sui thread emulazione (API 33 via dlsym) | medio | da studiare |
| I3 | thermal headroom → avviso/limite frame (API 30) | basso | da studiare |
| I5 | audio AAudio a latenza adattiva (cubeb) | medio-alto | rimandata |
| I13 | sensori gyro/accel a 100-200 Hz invece di `SENSOR_DELAY_GAME` (~50 Hz) in `DeviceMotionHandler` | batteria/CPU | da provare [DEVICE] |
| I12 | su `VK_ERROR_DEVICE_LOST` (ora `UnrecoverableError` lancia eccezione non gestita) mostrare errore in Kotlin invece di chiudere | medio | da studiare |

## Prossimo passo proposto
Dopo la build CI verde: misurare I1/I4/I6 sul telefono (se danno problemi: `git apply -R optional_I1_I4_I6.patch`); poi I2/I3/I13.
