# CemuFixerAPC — Technical Audit

Ultimo aggiornamento: 2026-11-12

## Repository e Git

- Repository: `renenero95-mod/CemuFixerAPC`
- Origin: `https://github.com/renenero95-mod/CemuFixerAPC.git`
- Upstream: `https://github.com/cemu-project/Cemu.git`
- Branch iniziale: `main`
- Branch di sviluppo creato: `android-port`
- Commit di partenza: `b487cb70`
- Working tree al controllo iniziale: pulito.

## Android upstream PR #1909

Riferimento locale:
`upstream/pr-1909`

Commit esaminato:
`7c2324eda01a3974ff89aa1e4ee7a594d278662a`

La PR introduce una struttura Android con:
- interfaccia Compose;
- integrazione JNI;
- Vulkan SurfaceView;
- input touch;
- Storage Access Framework;
- configurazione CMake e Gradle per Android;
- integrazione Adrenotools.

La PR è stata analizzata come riferimento tecnico, non integrata automaticamente.

## CMake e GUI

La configurazione esaminata separa la GUI Android da wxWidgets:
- `src/gui/androidgui`
- `src/gui/CMakeLists.txt`
- `src/gui/androidgui/CMakeLists.txt`

`CemuAndroidGui` è una libreria distinta.

In `AndroidWindowSystem.cpp` sono stati osservati metodi ancora vuoti o semplificati, tra cui:
- `UpdateWindowTitles`
- `NotifyGameLoaded`
- `NotifyGameExited`
- `RefreshGameList`
- `CaptureInput`

Devono essere verificati insieme al lifecycle Kotlin/JNI e alla gestione della superficie Vulkan.

## Gradle e Java

Configurazione esaminata nella PR:
- compile SDK 36;
- min SDK 30;
- target SDK 35;
- NDK 29.0.14206865;
- ABI `arm64-v8a`;
- Gradle Wrapper 9.4.1;
- CMake 3.25.0+;
- OpenJDK disponibile nell'ambiente Termux: 21.0.13.

### Punto da verificare: JAXB

Nel file `src/android/app/build.gradle.kts` è stato rilevato l'utilizzo di:

`javax.xml.bind.DatatypeConverter`

La disponibilità di JAXB con JDK 21 deve essere verificata. Potrebbe essere necessario sostituire la conversione esadecimale con codice Java/Kotlin standard oppure aggiungere una dipendenza appropriata.

Non è stata ancora eseguita una build per confermare l'errore.

## Storage Access Framework

Il backend `fscDeviceAndroidSAF.cpp` presenta aspetti da verificare:
- supporto alla scrittura non implementato nel backend esaminato;
- gestione dei file oltre 4 GB;
- enumerazione delle directory;
- comportamento degli URI e degli errori di accesso.

`NativeFiles.kt` e i callback JNI devono essere testati con file e directory reali.

## JNI e lifecycle

Le firme dei callback Kotlin/C++ esaminate risultano coerenti.

Aspetti da verificare:
- gestione degli errori JNI;
- attach/detach dei thread;
- costo delle chiamate tramite thread temporanei;
- inizializzazione e distruzione ordinata dell'emulazione.

## Vulkan e Adrenotools

Sono stati individuati:
- submodule Adrenotools;
- caricamento di driver Vulkan personalizzati;
- verifica del dispositivo KGSL;
- uso della directory privata dell'app.

La configurazione Android esaminata richiede `jniLibs.useLegacyPackaging = true`.

Devono ancora essere testati caricamento del driver, gestione degli errori e compatibilità effettiva sul dispositivo.

## Dispositivo di test

POCO F6 Pro:
- Snapdragon 8 Gen 2;
- GPU Adreno 740;
- RAM fisica 12 GB;
- RAM estesa dichiarata 6 GB;
- Android 14;
- ABI `arm64-v8a`.

La compatibilità effettiva deve essere verificata con APK installabile e test reali.

## Stato attuale

- [x] Repository verificato.
- [x] Remote verificati.
- [x] Branch `android-port` creato.
- [x] Riferimento PR #1909 disponibile.
- [x] Audit preliminare CMake, GUI, JNI, SAF e Vulkan.
- [ ] Documentazione verificata nel repository.
- [ ] Commit della documentazione.
- [ ] Build Android funzionante.
- [ ] GitHub Actions funzionante.
- [ ] APK testata sul dispositivo.

## Verifica JAXB — 2026-11-12

- Ambiente verificato: OpenJDK 21.0.13 su Termux ARM64.
- La PR #1909 importa `javax.xml.bind.DatatypeConverter` alla riga 5 di `src/android/app/build.gradle.kts`.
- La classe viene usata alla riga 185 per convertire il digest SHA in esadecimale.
- Test con `Class.forName("javax.xml.bind.DatatypeConverter")`: **JAXB ASSENTE**.
- Impatto: lo script Gradle può fallire durante la compilazione/configurazione dello script.
- Correzione proposta: sostituire `DatatypeConverter.printHexBinary(...)` con una conversione esadecimale basata sulle API standard Java/Kotlin, senza aggiungere JAXB.
- Stato: problema individuato, correzione non ancora applicata né testata.

## 7.43 — Confronto divergenza android-port / upstream/pr-1909 — 2026-11-14

Base comune: `c2e807f8a3ff90eaa4b6867e038c277226466c4e`.

Confronto:
- `android-port`: 149 commit non presenti in `upstream/pr-1909`.
- `upstream/pr-1909`: 21 commit non presenti in `android-port`.
- Differenza complessiva: 353 file modificati, 34.191 inserimenti e 401 rimozioni.
- File modificati sotto `src/android`: 246.
- File modificati fuori da `src/android`: 107.

Valutazione:
Il porting Android coinvolge anche il core condiviso Cemu. La sola importazione di `src/android` non è sufficiente. È escluso un merge indiscriminato in questa fase.

Strategia:
1. Analizzare separatamente i 107 file fuori da `src/android`.
2. Individuare dipendenze, modifiche necessarie e possibili conflitti con `android-port`.
3. Preparare un piano d'integrazione selettiva.
4. Preservare la modifica locale a `docs/TECHNICAL_AUDIT.md`.
5. Avviare la build soltanto dopo aver verificato la completezza dell'integrazione.

Stato: confronto quantitativo completato; analisi dettagliata e integrazione non ancora eseguite. Nessuna build avviata.
