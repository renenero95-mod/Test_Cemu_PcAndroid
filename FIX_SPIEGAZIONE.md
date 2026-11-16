# FIX_SPIEGAZIONE.md — cosa è stato cambiato e perché
Data: 2026-11-16 · Per un'AI che riprende il lavoro · Albero di partenza: zip `CemuFixerAPC-android-port.zip` (snapshot PR #1909 + modifiche locali dell'utente)

## 0. Stato di verifica (leggere prima)
- **Nessuna build eseguita** (qui mancano NDK/CMake/rete/dispositivo). Compilazione e prova su telefono toccano all'utente/CI.
- Test eseguiti davvero: vedi §4. Il resto è verificato leggendo il codice.
- `python3 scripts/verify_tree.py .` → deve stampare `OK` (controlla CMake, include, static data, YAML, marker di conflitto).

## 1. Scoperta principale: la base era vecchia rispetto a Cemu PC
Il tuo albero era la PR #1909 (port Android di SSimco) con **solo parte** dei file di upstream/main. Nel bundle `audit_evidence/original_bundle` c'erano gli alberi completi di `upstream/main` (b487cb7) e della PR (7c2324e) più la patch della PR.
Metodo: **merge a tre vie** in un repo di lavoro (non incluso)
- base = snapshot PR **meno** la patch PR (ricostruita con `git apply -R`; ignorati i gitlink `libadrenotools`/`ZArchive`)
- ours = albero dell'utente (senza `audit_evidence`) · theirs = snapshot `upstream__main`
- Risultato: **295 file aggiornati (+9325/−6737)**, **solo 4 conflitti**, risolti a mano (§2).

Cosa arriva dal PC (per area): Latte 60 file (texture, shader decompiler, buffer cache), recompiler Espresso/IML/ARM64 25 file, coreinit 26, gx2 14, IOSU 15, Vulkan 8, audio `snd_core` 8, input 6, h264 3, `CafeStaticVar` (nuovo meccanismo per stato statico Wii U), `Socket`, `ZipArchive`, scrittura file atomica (`FileStream_unix::WriteFileAtomic`).
Cambi rilevanti per Android verificati nel diff: FPCR ARM64 senza `DN` e con FZ (NaN/denormali più fedeli); `InputManager` `shared_lock`→`unique_lock` (race touch/mouse); Vulkan: `buffer_bindVertexBuffers` a batch, `SurfaceSync`, divisore attributi vertice, formati alternativi; coreinit: TLS/`CafeStaticVar`; `swkbd` riscritta con firme tipizzate.
File rimossi perché rimossi da upstream (nessun riferimento rimasto): `src/util/boost/bluetooth.h`, `src/Cafe/OS/libs/nlibcurl/nlibcurlDebug.hpp`, `src/resource/update.sh`, `dependencies/vcpkg_overlay_ports/wxwidgets/fix-listctrl-layout.patch`.
Non toccati: `SUBMODULES.lock` (vedi rischio R1), Gradle/Kotlin salvo §3, workflow Android (cambiate solo righe Windows/macOS di `build.yml`).

## 2. Conflitti risolti a mano
| File | Scelta |
|---|---|
| `src/Cafe/OS/libs/swkbd/swkbd.cpp` | Presa la versione upstream (firme tipizzate, `return 1;`, `CafeStaticVar`) e **rimesse le callback Android** (`g_swkbdCallbacks`, `swkbd::setSwkbdCallbacks`, `showSoftwareKeyboard` in AppearInputForm/AppearKeyboard, `hideSoftwareKeyboard` in Disappear*). Il codice usa `swkbdInternalState->maxTextLength` e `keyboardArg.receiverArg.stringBufSize` che esistono in upstream. |
| `src/CMakeLists.txt` | Mantenuto il ramo `if(ANDROID)` della PR; il blocco MSVC/PDB di upstream aggiunto nel ramo `else()`. |
| `src/util/CMakeLists.txt` | Tenuti **entrambi** `helpers/ZArchiveHelpers.h` (PR) e `helpers/ZipArchive.h` (upstream). |
| `README.md` | Tenuto quello dell'utente (solo documentazione). |

## 3. Fix applicati (Android)
| ID | File | Problema | Fix | Perché è sicuro |
|---|---|---|---|---|
| F2 | `nativeinterface/NativeException.kt` | Costruita solo da JNI (`ThrowNew`), nessun `@Keep`; R8 attivo in release (`proguard-rules.pro` ha solo `-dontobfuscate`) | `@Keep` | Solo annotazione |
| F3 | `Vulkan/VulkanAPI.cpp` `get_custom_driver_lib_name` | `meta.json` vuoto/troncato/non-oggetto → `HasMember` su documento non oggetto (UB). `libraryName` poteva uscire dalla cartella del driver | `HasParseError()/IsObject()`; rifiuta `libraryName` vuoto, assoluto o con `..` | Ricade sul driver di sistema (comportamento già previsto) |
| F4 | `common/io/ZipUtils.kt` | zip-slip (`../`); zip senza entry-cartella → `FileNotFoundException` | normalizza il percorso, rifiuta se fuori da `targetDir` (IOException), `parentFile.mkdirs()`; i `/` iniziali sono tolti come prima | Usato da install driver (ha già `catch`) e download graphic pack (§F9) |
| F6 | `VulkanAPI.cpp` `InitializeGlobalVulkan` | riapriva il loader (e ricopiava il driver custom) a ogni chiamata anche se già inizializzato | controllo `g_vulkan_available` **prima** di `dlopen_vulkan_loader()` | Stesso risultato, niente handle persi |
| F7 | `VulkanAPI.cpp` `load_custom_driver` | errore di `fs::copy` ignorato: poteva caricare un `custom_vulkan.so` vecchio rimasto dopo un crash | se `ec` → log e `nullptr` (driver di sistema) | Il chiamante gestisce già `nullptr` |
| F8 | `emulation/EmulationViewModel.kt` | `exception.message!!` → NPE nel gestore errori | `?: ""` | — |
| F9 | `graphicpacks/GraphicPacksDownloader.kt` | `renameTo` ignorato; errori di unzip non gestiti; zip vuoto/corrotto registrato come installato (`version.txt` scritto con 0 file) | fallback copia+cancella; guard `tempDir.list().isNullOrEmpty()` → IOException; `try/catch` in `download()` che emette `ERROR` (rilancia `CancellationException`) | Flusso errore già esistente |

| F12 | `emulation/CanvasOnTouchListener.kt` | `currentPointerId` non veniva mai assegnato (controllo morto); `ACTION_CANCEL` ignorato → touch emulato **bloccato premuto** dopo gesto di sistema/tendina; con 2 dita il tocco saltava (D>D>U>m) | tracciamento del primo dito, `findPointerIndex` nel MOVE, `ACTION_CANCEL` → `onTouchUp` con ultima posizione; un `ACTION_DOWN` riparte sempre da zero | Un dito solo si comporta come prima |
| F13 | `inputoverlay/inputs/{Button,Joystick,DPad}.kt` | nessun `ACTION_CANCEL`; `resetInput()` non viene mai chiamato → pulsante/joystick **bloccato** (il personaggio continua a muoversi) dopo un gesto di sistema | ramo `ACTION_CANCEL` che rilascia/ricentra l'input se era attivo | Come l'UP, solo quando `currentPointerId != -1` |
| F14 | `common/either/Either.kt` `attempt()` | catturava anche `CancellationException`: una coroutine annullata (es. si esce dalla schermata durante `prepareTitle`) diventava un errore mostrato all'utente | rilancia `CancellationException` | Solo semantica di cancellazione |
| F15 | `titlemanager/usecases/InstallTitleUseCase.kt` | `renameTo(backupFile)` ignorato: se falliva si installava sopra il titolo esistente e un errore successivo poteva cancellarlo | se il rename fallisce → `IOException` prima di iniziare (titolo esistente intatto) | Solo percorso di errore |

Idee applicate (**opzionali**, patch separata `optional_I1_I4_I6.patch`, annullabile con `git apply -R`; da provare sul telefono): I1 `ANativeWindow_setFrameRate(60, FIXED_SOURCE)` in `setSurface` (guardato da `__ANDROID_API__ >= 30`); I4 `requestUnbufferedDispatch` su `ACTION_DOWN` (canvas e overlay); I6 Game Mode (`res/xml/game_mode_config.xml` + `meta-data android.game_mode_config`).

Non fatti (decisione): F5 (`ANativeWindow_acquire` extra in `NativeEmulation.cpp:283`, leak di 1 ref): toglierlo rompe il confronto per puntatore `surface.wait(m_currentWindow)` (ABA) → servirebbe un contatore di generazione, da provare su telefono. F1 (firma APC): non è codice, servono i 3 secret GitHub (`ANDROID_STORE_FILE_BASE64`, `ANDROID_KEY_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, stessa password per store e key). F10 trascurabile.

## 4. Test eseguiti (reali) e loro limiti
| Test | Dove | Esito |
|---|---|---|
| unzip vecchio vs nuovo con 13 zip (validi, senza cartelle, `../`, assoluto, backslash, troncato, garbage, vuoto, nome 400 car., bomba 64 MB, duplicati) | `tools/tests/unzip/UnzipTest.java` (`java UnzipTest.java`, serve `cases/` generata) | **Vecchio: scrive `evil.txt` fuori dalla cartella; fallisce su zip senza cartelle. Nuovo: 0 file fuori, zip senza cartelle OK.** Garbage/vuoto → 0 entry senza errore (coperto dal guard F9). Bomba: nessun limite (accettato) |
| controllo `libraryName` (stessa espressione di F3) | `tools/tests/fspath_test.cpp` (`g++ -std=c++20`) | 12/12 casi OK |
| struttura albero | `scripts/verify_tree.py` | finale OK; sull'originale segnalava il file orfano `bluetooth.h`; **5/5 guasti introdotti di proposito rilevati** |
| listener touch + joystick overlay: vecchio vs nuovo su 6 scenari + overlay | `tools/tests/touch/TouchListenerSim.java` (`java TouchListenerSim.java`) | **Vecchio: S3 (CANCEL) e S6 lasciano il touch BLOCCATO, S2/S4 sequenza D>D>U>m; joystick dopo CANCEL resta a x=1.0. Nuovo: tutto ok** (simulazione della logica, non il Kotlin reale) |
| bilanciamento parentesi dei file modificati | script ad hoc | OK |
**Limiti:** la logica Kotlin è stata riprodotta riga per riga in Java (non eseguibile qui); F3/F7/F6 non sono eseguibili senza rapidjson/Adrenotools; nessuna compilazione. Se la CI fallisce, il primo sospetto sono i punti di §2 e le firme cambiate da upstream in `VulkanRenderer.h` (`DeviceInfo` ora prende `std::span`).

## 5. Rischi aperti
- **R1 ZArchive:** la PR pinna `965b66c` (ha `OpenFromStream`, usato da `ZArchiveHelpers.h`), upstream pinna `d2c7177`. Tenuto il pin della PR. Se la build fallisce su API ZArchive, è qui.
- **R2** Dipendenza `libzip` per `ZipArchive.h`: header elencato in CMake ma usato solo da GUI wx (non compilata su Android).
- **R3** File orfano `src/util/Fiber/FiberUnix.cpp` (non in CMake, sostituito da `FiberUContext`): lasciato.
- **R4** `bin/gameProfiles` identico tra PR e upstream, nessun aggiornamento.
- **R5** Il merge porta ~9k righe del PC: possibili regressioni solo visibili in esecuzione (Latte, recompiler, coreinit). Prima prova: build CI, poi un gioco noto con driver di sistema.

## 6. Come riprodurre / continuare
1. `python3 scripts/verify_tree.py .` 2. Push → Actions (`build_check.yml`) → scaricare APK. 3. Se fallisce, incollare il log di compilazione. 4. Idee non ancora applicate: vedi `check.md` (I1 `ANativeWindow_setFrameRate`, I4 `requestUnbufferedDispatch`, I6 Game Mode, I2 ADPF, I3 thermal; I11 prova barriere accurate OFF).
`changes_vs_original.patch` = differenza completa rispetto allo zip che mi hai dato (`git apply` dalla radice del progetto).
