# CemuFixerAPC --- Audit tecnico Android

**Data:** 12 novembre 2026\
**Ultimo aggiornamento audit:** 15 novembre 2026\
**Stato:** audit in corso --- sola analisi, nessuna modifica al codice
applicativo\
**Repository:** `renenero95-mod/CemuFixerAPC`\
**Branch di lavoro:** `android-port`

> Questo documento raccoglie le verifiche e le osservazioni emerse nella
> conversazione. Distingue i fatti confermati dagli aspetti ancora da
> verificare. Non costituisce una dichiarazione di compatibilità Android
> completa.

## 1. Obiettivo e strategia

La strategia concordata è partire dal ramo ufficiale `main` di Cemu e
valutare/importare selettivamente le modifiche Android proposte nella PR
#1909 di SSimco. Non è previsto un merge indiscriminato della PR né
l'uso del fork Android di SSimco come motore di base.

Non sono state autorizzate modifiche al codice né l'importazione dei
file della PR. L'attività svolta finora è un audit. Le modifiche ai
documenti di audit non devono essere confuse con modifiche al codice
sorgente.

## 2. Stato Git e submodule

-   Repository locale: `~/CemuFixerAPC`
-   Branch attivo: `android-port`
-   `origin`: `https://github.com/renenero95-mod/CemuFixerAPC.git`
-   `upstream`: `https://github.com/cemu-project/Cemu.git`
-   Commit di base del branch `android-port`: `b487cb70` ---
    `UI: Update logging window mappings`.
-   PR #1909: branch Android separato, non integrato nel branch locale.
-   Submodule vcpkg previsto dal tree:
    `9849e070ad4c3383890fb1c80ae0c54325e2484a`.
-   Submodule `xbyak_aarch64` inizializzato al commit
    `904b8923457f3ec0d6f82ea2d6832a792851194d`.
-   Submodule `ZArchive` inizializzato al commit
    `d2c717730092c7bf8cbb033b12fd4001b7c4d932`.
-   Submodule `libadrenotools` previsto al commit
    `8fae8ce254dfc1344527e05301e43f37dea2df80`.

Lo stato Git riportato in precedenza era `## android-port` con
`M docs/TECHNICAL_AUDIT.md`. Prima di qualsiasi commit o modifica
ulteriore occorre ricontrollare lo stato effettivo.

## 3. Ambiente Android disponibile

Dispositivo dichiarato: - POCO F6 Pro - Snapdragon 8 Gen 2, GPU Adreno
740 - Android 14 - ABI `arm64-v8a` - 12 GB RAM fisica; ulteriori 6 GB di
RAM estesa dichiarata dal dispositivo - 512 GB di memoria interna

Ambiente Termux verificato: - OpenJDK 21.0.13 - Gradle installato
globalmente: 9.9.1 - Gradle Wrapper della PR: 9.4.1, avvio verificato -
Android SDK: `$PREFIX/opt/android-sdk` - NDK: `29.0.14206865` - SDK
CMake: `3.30.5` - Termux CMake: `4.4.5` - Android SDK platforms: 34, 35
e 36 - Build Tools: 35.0.0 e 36.0.0 - ADB: 35.0.3

Le variabili d'ambiente Android sono state provate in una shell, non
risulta confermata la loro persistenza:

``` bash
export ANDROID_HOME="$PREFIX/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Non è stato ancora eseguito un build Android completo.

## 4. PR Android #1909 --- quadro generale

Dati raccolti: - PR #1909, titolo "Add Android port". - 21 commit. - 353
file modificati, circa +34.191 / -401 righe. - Circa 246 file sotto
`src/android`. - UI Kotlin/Jetpack Compose, `SurfaceView` Vulkan, JNI,
overlay touch e integrazione con Android. - Include integrazione vcpkg
per Android, Boost.Context, accesso storage tramite SAF e altre
modifiche native. - La documentazione del branch Android descrive il
port come sperimentale/incompleto.

La PR non è stata importata nel branch locale.

## 5. Risultati CMake e dipendenze

### 5.1 CMake root e `src/CMakeLists.txt`

Dall'audit del diff della PR: - La versione minima CMake viene innalzata
da 3.21.1 a 3.25. - La configurazione vcpkg seleziona triplet Android in
base all'ABI: - `arm64-v8a` → `arm64-android` - `x86_64` →
`x64-android` - ABI non supportate → errore. - Vengono impostati
`ANDROID_NDK_HOME` e `VCPKG_CHAINLOAD_TOOLCHAIN_FILE` verso
`android.toolchain.cmake`. - Su Android è richiesto Boost con componenti
`context` e `iostreams`. - X11 e Feral Gamemode sono esclusi da
Android. - `libadrenotools` viene aggiunto su Android ARM64. - `stb`
viene aggiunto come dipendenza. - Con Vulkan, viene definito
`VK_USE_PLATFORM_ANDROID_KHR`. - Su Android `CemuBin` viene costruito
come libreria statica e viene aggiunto il sottoprogetto JNI Android;
desktop continua a usare il target eseguibile. - Nel diff PR
`CEMU_CXX_FLAGS` non viene più trattato con `separate_arguments` come
prima: possibile differenza di comportamento da controllare per le build
desktop.

### 5.2 Manifest vcpkg

Nel `vcpkg.json` esaminato: - `zstd` è dichiarato due volte nella stessa
lista `dependencies`, senza condizioni o feature differenti. La seconda
dichiarazione appare ridondante. - Sono presenti anche `pugixml`,
`zlib`, `libzip`, `rapidjson`, SDL3, numerosi componenti Boost, fmt,
libpng, glm, glslang, wxwidgets, openssl, curl, dbus (solo Linux), tiff
e libusb. - Il manifest include un `builtin-baseline`:
`f0fb3ddba5135b80982668de39dbaa139c00d281`. - Il submodule vcpkg è
fissato separatamente a: `9849e070ad4c3383890fb1c80ae0c54325e2484a`.

Il commit del submodule e il `builtin-baseline` sono due riferimenti
distinti: il primo identifica il repository vcpkg incluso; il secondo il
baseline dei port.

### 5.3 ZArchive e zstd

ZArchive è stato inizializzato al commit:
`d2c717730092c7bf8cbb033b12fd4001b7c4d932`.

Dal suo `CMakeLists.txt`: - libreria `zarchive` statica; - sorgenti
reader/writer e SHA-256; - C++20; - dipendenza zstd richiesta con
`find_package(zstd MODULE REQUIRED)`; - collegamento privato a
`zstd::zstd`; - viene anche dichiarato un eseguibile `zarchiveTool`; -
installazione di libreria, tool, header e file pkg-config. - Nel root
CMake della PR ZArchive viene aggiunto con `EXCLUDE_FROM_ALL`; resta da
verificare nel grafo reale quali target vengano effettivamente
costruiti.

Sono presenti due moduli: - `cmake/Findzstd.cmake` -
`dependencies/ZArchive/cmake/Findzstd.cmake`

Entrambi: 1. cercano prima la configurazione CMake di zstd; 2. se non
esiste `zstd::zstd`, provano a creare un alias verso
`zstd::libzstd_static` oppure `zstd::libzstd_shared`; 3. in fallback
cercano `libzstd` con pkg-config e creano l'alias `zstd::zstd`.

Differenza osservata: - il modulo principale usa
`find_package(zstd CONFIG)` senza `QUIET`; - quello di ZArchive usa
`find_package(zstd CONFIG QUIET)`.

Punti di utilizzo rilevati: - root `CMakeLists.txt:160`:
`find_package(zstd MODULE REQUIRED)`; -
`dependencies/ZArchive/CMakeLists.txt:39`: stessa ricerca in modalità
MODULE; - `dependencies/ZArchive/CMakeLists.txt:40`: link a
`zstd::zstd`; - `src/Cafe/CMakeLists.txt:656`: utilizzo di `zstd::zstd`.

Entrambi i moduli controllano `if (NOT TARGET zstd::zstd)` prima di
creare l'alias. Questo sembra gestire il caso in cui l'alias sia già
stato definito, ma il comportamento complessivo non è stato testato in
una configurazione Android reale.

**Valutazione:** la logica CMake è compatibile in linea di principio con
Android. Non è ancora verificato che il port zstd del baseline vcpkg
produca su Android uno dei target `zstd::libzstd_static` o
`zstd::libzstd_shared`. Il fallback pkg-config non è garantito
nell'ambiente Android. Nessun overlay zstd specifico è emerso dalla
ricerca eseguita, che ha trovato soltanto i due `Findzstd.cmake`.

### 5.4 `stb`

`dependencies/stb/CMakeLists.txt`: - progetto C; - libreria `stb` di
tipo `INTERFACE`; - include directory `include`.

È una dipendenza header-only, semplice da integrare in linea di
principio.

### 5.5 `ih264d`

L'audit del CMake di `ih264d` ha rilevato: - supporto esplicito per
architetture `aarch64` e `arm64`; - sorgenti ARMv8 assembly e routine
NEON; - definizione `ARMV8`; - percorso x86 con sorgenti SSE; - errore
fatale per architetture sconosciute.

**Valutazione:** esiste un percorso sorgente ARM64 adatto in linea di
principio a `arm64-v8a`. Resta da verificare che NDK e CMake forniscano
il valore di architettura atteso e che gli assembly vengano compilati
correttamente.

### 5.6 `xbyak_aarch64`

Submodule inizializzato al commit:
`904b8923457f3ec0d6f82ea2d6832a792851194d`.

Il CMake: - crea la libreria statica `xbyak_aarch64`; - espone alias
`xbyak_aarch64::xbyak_aarch64`; - usa C++11; - abilita PIC; - non mostra
dipendenze o condizioni specifiche desktop/Android.

La PR lo aggiunge quando `CEMU_ARCHITECTURE` corrisponde a `aarch64`,
`AARCH64`, `arm64` o `ARM64`. In `src/Cafe/CMakeLists.txt` aggiunge
`BackendAArch64.cpp/.h` e collega la libreria.

**Valutazione:** compatibile in linea di principio, ma occorre
verificare il valore effettivo di
`CMAKE_SYSTEM_PROCESSOR`/`CEMU_ARCHITECTURE` con il toolchain NDK e
compilare il target.

### 5.7 `libadrenotools`

Submodule previsto al commit:
`8fae8ce254dfc1344527e05301e43f37dea2df80`.

La PR lo usa su Android ARM64 per la gestione di driver Vulkan
personalizzati. Le istruzioni del progetto richiedono packaging legacy
delle librerie native (`useLegacyPackaging = true`), directory hook
corrispondente a `nativeLibraryDir` e directory driver privata dell'app.

Da verificare: packaging effettivo, caricamento driver, gestione degli
errori di copia e pulizia dei file temporanei sul dispositivo.

## 6. JNI, SAF e filesystem Android

Osservazioni raccolte dal codice della PR: - Il backend C++ SAF è
sostanzialmente read-only: `fscWriteData()` non implementa una scrittura
effettiva. - La gestione di file oltre 4 GB presenta assert/limiti da
verificare. - La logica di enumerazione directory contiene un controllo
sospetto su `m_files` dopo la creazione con `make_unique`; va verificato
nel contesto completo. - `FilesystemAndroid.cpp` conserva callback
condivise e inoltra operazioni alle callback Kotlin. -
`NativeEmulation.initializeEmulation()` registra le callback prima di
impostare percorsi e inizializzare il core Cemu. - I nomi di classe e
metodo JNI osservati in `AndroidFilesystemCallbacks` corrispondono alle
callback Kotlin `NativeFiles`. - `NativeFiles.kt` trasferisce la
proprietà del file descriptor con `detachFd()` per l'apertura URI. -
`listFiles` cattura eccezioni e restituisce un array vuoto. - `isFile` è
implementato come negazione di `isDirectory`: un URI inesistente o non
leggibile potrebbe essere classificato in modo improprio. - La
conversione/gestione degli URI va testata con provider SAF reali. -
`ActiveSettings::SetPaths` crea/verifica alcune directory utente,
configurazione e cache, ma non risulta un controllo esplicito
equivalente per `dataPath`. - `GetMlcPath()` usa per default
`GetUserDataPath("mlc01")`.

Questi punti sono osservazioni statiche; non sono ancora verificati con
test runtime su Android.

## 7. JNI e chiamate thread/fiber

-   `JNIUtils::FiberSafeJNICall` crea un `std::jthread` temporaneo; la
    distruzione del thread temporaneo comporta attesa/join, quindi il
    percorso risulta sostanzialmente sincrono e può introdurre overhead.
-   `GetEnv()` richiede ulteriore verifica per controlli null e gestione
    dell'attach al thread JVM.

## 8. Gradle e Java 21

Nel file Gradle Android della PR è presente:

``` kotlin
import javax.xml.bind.DatatypeConverter
```

e viene usato `DatatypeConverter.printHexBinary(...)`.

Nel runtime Java 21 verificato dall'utente,
`javax.xml.bind.DatatypeConverter` non è disponibile per default
(`Class.forName` ha restituito `JAXB ASSENTE`).

Possibile sostituzione, non applicata:

``` kotlin
hashOutFile.writeText(
    digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
)
```

Il file Gradle della PR non è stato importato nel branch locale e la
sostituzione non è stata applicata.

## 9. Ambiente e stato delle verifiche

Verifiche completate: - lettura statica di vari CMake e manifest; -
identificazione dei commit di alcuni submodule; - controllo
dell'ambiente Termux e delle versioni SDK/NDK/Java; - individuazione dei
punti di integrazione zstd; - controllo preliminare delle aree JNI, SAF
e Vulkan.

Non completato: - configurazione CMake Android end-to-end; - build NDK
di tutte le dipendenze; - link della libreria nativa Cemu; - packaging
APK; - installazione e avvio su POCO F6 Pro; - test input touch, audio,
Vulkan, storage SAF, shader cache, driver custom e giochi.

## 10. Rischi e questioni aperte

  -----------------------------------------------------------------------
  Area                    Stato                   Prossima verifica
  ----------------------- ----------------------- -----------------------
  zstd/vcpkg              Non verificato su       Controllare target
                          Android                 esportati dal port zstd
                                                  al baseline scelto

  Doppia dichiarazione    Anomalia osservata      Verificare manifest e
  zstd                                            rimuovere solo con
                                                  approvazione

  ZArchive                Compatibile in linea di Configurazione e build
                          principio               Android; verificare
                                                  target `zarchiveTool`

  `ih264d` ARM64          Supporto sorgente       Configurare e compilare
                          presente                con NDK

  `xbyak_aarch64`         Compatibile in linea di Verificare
                          principio               macro/architettura e
                                                  build NDK

  libadrenotools          Integrazione statica    Packaging e caricamento
                          esaminata               driver su dispositivo

  SAF                     Rischi statici          Test con provider e
                          individuati             file reali

  JNI thread              Possibile overhead      Profilazione e test di
                                                  correttezza

  Gradle/JAXB             Incompatibilità Java 21 Correzione solo dopo
                          identificata            approvazione

  Build completa          Non eseguita            Prima configurazione
                                                  Android controllata
  -----------------------------------------------------------------------

## 11. Regole operative concordate

-   Procedere con un comando alla volta in Termux.
-   Non eseguire modifiche al codice, import di file dalla PR, commit,
    push o cancellazioni senza approvazione esplicita.
-   Non inizializzare altri submodule se non necessario e concordato.
-   Distinguere sempre fatti verificati, inferenze e ipotesi.
-   Prima di un build completo, completare l'audit delle dipendenze e
    della configurazione.
-   Conservare questo documento come riferimento e aggiornarlo
    progressivamente quando vengono completate nuove verifiche.

------------------------------------------------------------------------

**Fine del rapporto --- audit ancora aperto.**


## 7. Gradle Android: verifiche aggiuntive

### 7.1 Versioni e configurazione

Dai file della PR esaminati: Gradle Wrapper `9.4.1`; Android Gradle Plugin `9.2.1`; Kotlin `2.3.21`; `compileSdk` 36; `targetSdk` 35; `minSdk` 30; NDK `29.0.14206865`; CMake `3.25.0+`; ABI abilitata `arm64-v8a` (`x86_64` commentata); `jniLibs.useLegacyPackaging = true`.

Il Wrapper `9.4.1` è stato avviato correttamente con Java 21 in Termux. Questo verifica l'avvio del Wrapper, non la compatibilità dell'intera build Android.

La combinazione AGP `9.2.1` e Kotlin `2.3.21` non rientra interamente nella matrice di compatibilità ufficiale Kotlin verificata durante l'audit. Non va quindi dichiarata ufficialmente supportata senza verifica aggiornata o build riuscita.

### 7.2 Argomenti CMake passati da Gradle

La PR passa a CMake:

```text
-DANDROID_STL=c++_shared
-DENABLE_VCPKG=ON
-DVCPKG_TARGET_ANDROID=ON
-DENABLE_SDL=OFF
-DENABLE_HIDAPI=OFF
-DENABLE_WXWIDGETS=OFF
-DENABLE_OPENGL=OFF
-DENABLE_BLUEZ=OFF
-DBUNDLE_SPEEX=ON
-DENABLE_DISCORD_RPC=OFF
-DENABLE_NSYSHID_LIBUSB=OFF
-DENABLE_WAYLAND=OFF
```

`VCPKG_TARGET_ANDROID=ON` è passato tramite `externalNativeBuild.cmake`, quindi è disponibile al root CMake per la selezione Android di overlay e triplet. L'ABI `arm64-v8a` corrisponde al dispositivo in esame.

### 7.3 Possibile problema JAXB con Java 21

Lo script Gradle importa `javax.xml.bind.DatatypeConverter` e usa `DatatypeConverter.printHexBinary(...)` per convertire il digest SHA-256 in esadecimale. Nel Termux dell'utente, `Class.forName("javax.xml.bind.DatatypeConverter")` ha restituito `JAXB ASSENTE`. JAXB non fa parte del runtime standard Java 21: è un potenziale problema da riprodurre eseguendo lo script Gradle con il Wrapper della PR.

Possibile sostituzione, non applicata:

```kotlin
hashOutFile.writeText(
    digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
)
```

### 7.4 Funzione `runCommand()` nello script Gradle

La funzione esaminata crea un `ProcessBuilder` dividendo la stringa del comando sugli spazi, attende la terminazione prima di leggere l'output e usa pipe per stdout/stderr.

Rischi statici: argomenti quotati e percorsi con spazi possono essere suddivisi male; attendere prima di consumare stdout/stderr può bloccare il processo se una pipe si riempie; stderr non viene letto nell'estratto; il timeout è gestito tramite `assert` senza gestione robusta del processo. Nell'estratto degli import non compare `java.util.concurrent.TimeUnit`, benché la funzione usi `TimeUnit.MINUTES`: serve controllare il file completo per escludere un import fuori dall'estratto.

### 7.5 Task di hash dei dati Cemu

`ComputeCemuDataFilesHashTask` usa `../../../bin` come input e `src/main/assets/hash.txt` come output predefinito. Esclude i file corrispondenti a `.*cemu\.mo` e `.*Cemu_(?:debug|release)`, calcola SHA-256 su percorsi e contenuti ordinati, scrive `invalid` se `bin` non è una directory valida ed è collegata a `preBuild`.

Nel codice esaminato, la scrittura usa `writeText()`; nell'estratto disponibile non compare una creazione esplicita della directory genitrice `src/main/assets`.

### 7.6 Verifica del tree Git della PR: `assets` e `hash.txt`

È stato eseguito sul riferimento remoto `upstream/pr-1909`:

```bash
git ls-tree -r --name-only upstream/pr-1909 | grep -E '(^|/)assets(/|$)|(^|/)hash\.txt$'
```

Il comando non ha restituito righe. Nel tree Git esaminato non risultano quindi percorsi tracciati contenenti una directory `assets` né file denominati `hash.txt`.

Questo conferma che la directory di output prevista non è presente tra i file tracciati e che `hash.txt` non è fornito già versionato. Se nessun altro task crea `src/main/assets` prima della scrittura, `writeText()` può fallire perché manca la directory genitrice. Non è ancora verificato se un'altra configurazione o task la crei.

Il precedente `git check-ignore` eseguito nel checkout locale non verifica le regole `.gitignore` del riferimento PR; non viene quindi considerato prova sul comportamento di ignore della PR.

**Prossimo controllo:** cercare nei file Gradle e nelle configurazioni della PR chiamate a `mkdirs()`/`mkdir()` o task che creino `src/main/assets` prima di `ComputeCemuDataFilesHashTask`.

## 8. Stato complessivo aggiornato

Non è stato eseguito un build Android completo, non è stato prodotto o installato un APK e non sono state importate modifiche della PR nel branch `android-port`. Le verifiche su CMake, Gradle, JNI e SAF sono prevalentemente analisi statica. Gli strumenti Termux risultano presenti, ma ciò non dimostra che l'intera toolchain sia pronta.

Le correzioni suggerite in questo documento non sono state applicate. Mantenere distinte le conclusioni **confermate dal tree/codice**, i **rischi individuati** e gli aspetti **non verificati con build o test sul dispositivo**.


### 7.7 Esito della ricerca di creazione della directory assets

È stata eseguita la ricerca:

```bash
git grep -n -E 'src/main/assets|mkdirs\(|createDirectories|assets.*mkdir' upstream/pr-1909 -- 'src/android'
```

Risultati rilevanti:

- `src/android/app/.gitignore:2` contiene `/src/main/assets/hash.txt`: il file generato è intenzionalmente escluso dal versionamento.
- `src/android/app/build.gradle.kts:157` dichiara `src/main/assets/hash.txt` come output della task.
- Le chiamate `mkdirs()` in `CemuApplication.kt` creano directory dell'app/dati e il genitore di un `outFile`; non risultano riferimenti a `src/main/assets`.
- Le altre chiamate trovate riguardano `ZipUtils.kt`, directory temporanee e directory di installazione titoli; non sono prove della creazione della directory assets usata dalla task Gradle.

**Conclusione aggiornata:** la ricerca nel codice `src/android` non ha trovato una creazione esplicita di `src/main/assets`. Il fatto che `hash.txt` sia ignorato da Git spiega perché non compare nel tree tracciato, ma non crea la directory sul filesystem. Rimane quindi un rischio concreto che `writeText()` fallisca quando il genitore non esiste. Non è ancora un errore riprodotto: serve verificare l'esecuzione della task Gradle o la creazione automatica della directory da parte della configurazione/build system.


### 7.8 Lettura del corpo completo di `ComputeCemuDataFilesHashTask`

L'utente ha fornito il corpo della task (estratto da `build.gradle.kts`). Il codice conferma:

1. `hashFile` ha come percorso predefinito `src/main/assets/hash.txt`.
2. Se `cemuDataFolder` non è una directory, la task esegue `hashOutFile.writeText("invalid")`.
3. Altrimenti calcola il digest SHA-256 e lo scrive con `hashOutFile.writeText(...)`.
4. In entrambi i rami non compare una creazione della directory genitrice di `hashOutFile` (`mkdirs()`/`createDirectories()`).

**Conclusione:** se `src/main/assets` non esiste al momento dell'esecuzione, la chiamata `writeText()` non crea da sola la directory genitrice e può fallire. La ricerca precedente non ha trovato un'altra creazione esplicita nel codice Android esaminato. Questo è un difetto potenziale ben supportato dal codice, ma non è ancora stato riprodotto con Gradle.

**Ulteriore punto da verificare:** `cemuDataFolder` è annotata `@InputDirectory` e viene valorizzata con `File(cemuDataFilesFolder)`, dove `cemuDataFilesFolder` è `../../../bin`. Gradle normalmente valida gli input richiesti prima di eseguire l'azione della task; se la directory `bin` manca, la task potrebbe fallire nella validazione dell'input prima di raggiungere il ramo `if (!cemuDataFilesDir.isDirectory)` che vorrebbe scrivere `invalid`. Il comportamento effettivo va verificato con la versione Gradle/AGP della PR.

Nessuna modifica al codice è stata applicata. La possibile correzione (da valutare solo dopo aver concordato il piano di porting) sarebbe creare `hashOutFile.parentFile` prima di entrambe le scritture e decidere esplicitamente se `bin` debba essere un input obbligatorio oppure un caso gestito dalla task.


### 7.9 Verifica della directory `bin` nel tree Git

L'utente ha eseguito `git ls-tree -r --name-only upstream/pr-1909 bin` e ha riportato numerosi percorsi tracciati sotto `bin/`, tra cui:

- `bin/gameProfiles/default/*.ini` (profili di gioco);
- `bin/resources/<lingua>/cemu.mo` (cataloghi di traduzione);
- `bin/resources/sharedFonts/*.ttf` (font condivisi).

Questo conferma che `bin/` e contenuti al suo interno sono presenti nel tree Git della PR. In un checkout completo della PR, il percorso `../../../bin` riferito dal modulo `src/android/app` dovrebbe quindi esistere. La precedente preoccupazione che `@InputDirectory` fallisca semplicemente perché `bin` non è tracciata non è supportata dai risultati e va considerata superata per un checkout completo.

La task filtra i percorsi che corrispondono a `.*cemu\.mo` e `.*Cemu_(?:debug|release)`. Perciò i file `cemu.mo` elencati sotto `bin/resources` sono esclusi dal digest; i profili `.ini` e i font `.ttf` non corrispondono ai pattern mostrati e vengono inclusi se sono file regolari. Non è stato verificato se questa scelta corrisponda all'intento funzionale dell'applicazione.

**Stato aggiornato dei due punti:**
- Input `../../../bin`: presente nel tree Git della PR; rischio di input mancante non confermato per un checkout completo.
- Output `src/main/assets/hash.txt`: directory non tracciata e nessuna creazione esplicita individuata nel codice Android esaminato; rischio di scrittura senza directory genitrice ancora aperto e non riprodotto.


### 7.10 Configurazione `sourceSets` e dipendenza `preBuild`

L'utente ha eseguito una ricerca nel `build.gradle.kts` della PR e ha riportato queste corrispondenze:

- riga 60: `sourceSets.getByName("main")`;
- riga 61: blocco `assets`;
- riga 157: percorso di output `src/main/assets/hash.txt`;
- righe 165, 189-190: azione e registrazione della task;
- riga 193: `tasks.preBuild.dependsOn(computeCemuDataFilesHashTask)`.

Questo conferma che il modulo dichiara un blocco `sourceSets.main.assets`, ma il solo risultato di `grep` non mostra quale directory venga aggiunta o se venga creata fisicamente. La dipendenza `preBuild.dependsOn(...)` conferma che la task hash è collegata a `preBuild`; non dimostra da sola l'ordine rispetto a eventuali task che preparino la directory assets.

**Punto ancora aperto:** leggere il blocco completo `sourceSets` attorno alle righe 55-70. Se si limita ad aggiungere `../../../bin` come directory sorgente degli assets, ciò non dimostra che `src/main/assets` venga creata prima della scrittura di `hash.txt`.


### 7.11 Contenuto completo del blocco `androidResources` e `sourceSets`

L'utente ha fornito il blocco attorno alle righe 54-70 del `build.gradle.kts` della PR:

```kotlin
androidResources {
    ignoreAssetsPattern = "!*cemu.mo:"
}

sourceSets.getByName("main") {
    assets {
        directories.add(cemuDataFilesFolder)
    }
}
```

Il blocco `sourceSets.main.assets` aggiunge `cemuDataFilesFolder` (che punta a `../../../bin`) alle directory sorgente degli assets Android. Non aggiunge `src/main/assets` e non contiene chiamate che creino la directory genitrice del file `hash.txt`.

`androidResources.ignoreAssetsPattern` configura un pattern di esclusione per il packaging delle risorse/asset Android; non crea la directory `src/main/assets` e non risolve il problema della scrittura del file hash.

**Conclusione aggiornata:** il blocco `sourceSets` non fornisce la creazione mancante. Rimane il rischio che la task scriva `src/main/assets/hash.txt` senza che la directory esista. Il comportamento va ancora riprodotto con Gradle/AGP, ma il codice mostrato non contiene la creazione esplicita.

Nota: il valore `ignoreAssetsPattern = "!*cemu.mo:"` va verificato nel contesto della semantica AGP e dei pattern di packaging prima di trarre conclusioni sul fatto che i file `.mo` siano inclusi o esclusi nel pacchetto finale. Non dedurre da questo solo valore che il filtro Kotlin della task hash e il filtro di packaging abbiano la stessa funzione.


### 7.12 Riferimento applicativo a `hash.txt`

La ricerca dell'utente:

```bash
git grep -n -E 'hash\.txt|cemuDataFilesHash|dataFilesHash' upstream/pr-1909 -- 'src/android'
```

ha trovato:

- `src/android/app/.gitignore:2`: `hash.txt` è ignorato da Git;
- `src/android/app/build.gradle.kts:157`: `hash.txt` è l'output della task Gradle;
- `src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:60`: il codice Kotlin dichiara `val hashFileName = "hash.txt"`.

Questo dimostra che `CemuApplication.kt` fa riferimento al nome `hash.txt`, ma il solo risultato di ricerca non chiarisce ancora da quale directory venga letto, come venga usato il contenuto o cosa succeda se il file manca/non è leggibile.

**Prossimo controllo:** esaminare il contesto di `CemuApplication.kt` attorno alle righe 50-75 per determinare percorso, gestione degli errori e conseguenze applicative.


### 7.13 Uso di `hash.txt` in `CemuApplication.saveDataFiles()`

L'utente ha fornito il contesto di `CemuApplication.kt` (righe 48-78). Il metodo `saveDataFiles()`:

1. risolve `internalCemuDataFolder` come `dataFolder`;
2. se la directory non esiste, prova a crearla con `mkdirs()` e ritorna se la creazione fallisce;
3. legge `hash.txt` dalla cartella dati interna; se il file non esiste o non è un file regolare, assegna `oldHash = "invalid"`;
4. apre l'asset APK `hash.txt` tramite `assets.open(hashFileName)` e legge il contenuto come `newHash`;
5. se l'apertura dell'asset genera `IOException`, ritorna senza proseguire;
6. se `oldHash == newHash`, ritorna senza ricopiare i dati;
7. se gli hash differiscono, elimina ricorsivamente `dataFolder`, la ricrea e scrive il nuovo hash nella cartella dati;
8. il frammento termina all'inizio della funzione locale `traverseAssets`; il codice successivo di copia non è stato ancora esaminato.

**Implicazioni:** `hash.txt` è un indicatore usato per decidere se i dati Cemu distribuiti nell'APK devono essere copiati/aggiornati nella directory privata dell'app. Non è un semplice file diagnostico. Se l'asset manca o non è leggibile, `saveDataFiles()` ritorna senza aggiornare i dati. Dal solo frammento non si può concludere che l'intero avvio dell'app venga bloccato: bisogna esaminare il punto in cui `saveDataFiles()` viene chiamato e il resto del metodo.

Il ramo che rileva un hash diverso cancella ricorsivamente l'intera `dataFolder`. Va verificato che `internalCemuDataFolder` contenga esclusivamente dati rigenerabili gestiti da questa procedura e non dati utente persistenti: il frammento da solo non permette di stabilirlo. È un punto importante da controllare prima di adottare questa logica nel porting.

**Collegamento con la task Gradle:** il task genera il file che viene poi incluso tra gli assets e letto con `assets.open("hash.txt")`. La mancata creazione di `src/main/assets/hash.txt` può quindi causare un problema a monte nella build; l'assenza dell'asset nell'APK causa invece il ritorno anticipato da `saveDataFiles()` a runtime.


## 7.14 `saveDataFiles()`: cancellazione della directory e copia selettiva degli asset

**Evidenza esaminata:** continuazione di `CemuApplication.kt`, metodo `saveDataFiles()`, righe mostrate dall'utente (circa 60–125).

Dopo aver letto `hash.txt` dagli asset dell'app e aver confrontato il valore con quello salvato in `dataFolder`, il metodo:

1. termina senza modifiche se i due hash coincidono;
2. se differiscono, esegue `dataFolder.deleteRecursively()`;
3. ricrea `dataFolder` e vi riscrive `hash.txt`;
4. attraversa gli asset dell'app e seleziona esclusivamente i percorsi che corrispondono a `gameProfiles/.*` oppure `resources/.*`;
5. crea le directory padre necessarie e copia i file selezionati nella directory interna.

### Fatti confermati

- Il metodo elimina ricorsivamente **l'intera `dataFolder`**, non soltanto i file che intende aggiornare.
- Dopo la cancellazione, la copia è limitata ai percorsi `gameProfiles/` e `resources/`.
- Se l'apertura dell'asset `hash.txt` genera `IOException`, il metodo ritorna prima di eseguire il confronto e la copia.
- Se l'hash salvato coincide con quello incluso nell'app, il metodo ritorna senza ricopiare gli asset.
- Il codice mostrato non contiene una migrazione selettiva dei contenuti preesistenti prima della cancellazione.

### Rischio e valutazione

**Rischio potenziale significativo, non ancora dimostrato come perdita di salvataggi:** se `internalCemuDataFolder` contiene file creati o modificati dall'utente, oppure altri dati persistenti non ricostruibili dagli asset `gameProfiles/` e `resources/`, un cambiamento dell'hash li eliminerebbe.

Non è corretto concludere, sulla sola base di questo frammento, che i salvataggi vengano effettivamente cancellati: occorre prima verificare la definizione e il valore di `internalCemuDataFolder`, gli altri percorsi usati per MLC/user data e la separazione tra dati distribuiti con l'app e dati dell'utente.

### Verifiche ancora necessarie

- Individuare la definizione di `internalCemuDataFolder` e confermare il percorso effettivo.
- Verificare dove viene chiamato `saveDataFiles()` e in quale fase dell'avvio.
- Verificare che MLC, salvataggi, configurazioni e altri dati modificabili dall'utente siano conservati fuori da questa directory.
- Confrontare l'insieme dei file usati dal task Gradle per calcolare `hash.txt` con l'insieme effettivamente copiato dal metodo; il frammento Kotlin mostra che la copia riguarda solo `gameProfiles/` e `resources/`.

**Stato:** comportamento distruttivo della directory confermato dal codice; impatto sui dati utente ancora da determinare. Nessuna modifica al repository effettuata.


## 7.15 Separazione tra `internalCemuDataFolder` e `internalCemuUserFolder`

**Evidenza esaminata:** porzione di `CemuApplication.kt` fornita dall'utente, metodo `initializeCemu()` e proprietà `internalCemuDataFolder` / `internalCemuUserFolder`.

Il codice assegna i percorsi così:

- `internalCemuDataFolder` = `internalFolder().resolve("data").toString()`
- `internalCemuUserFolder` = `internalFolder().toString()`

In `initializeCemu()` vengono passati a `initializeActiveSettings()`:
- `userDataPath = internalCemuUserFolder`
- `configPath = internalCemuUserFolder`
- `dataPath = internalCemuDataFolder`
- `cachePath = internalCemuUserFolder`

### Fatti confermati

- La directory cancellata da `saveDataFiles()` è la sottodirectory `data` di `internalFolder()`.
- La directory principale `internalFolder()` è invece il percorso assegnato a user data, configurazione e cache.
- Pertanto `dataFolder.deleteRecursively()` non elimina direttamente l'intera directory principale `internalFolder()` né, per il solo fatto di essere una sottodirectory, i suoi contenuti fratelli.
- `dataPath` punta espressamente alla sottodirectory `data`, la stessa usata da `saveDataFiles()`.

### Valutazione aggiornata

La separazione dei percorsi riduce il rischio generale: configurazione, cache e user data sono configurati nella directory principale, mentre gli asset distribuiti vengono copiati nella sottodirectory `data`.

Resta però da verificare se il codice nativo o Kotlin scriva dati persistenti modificabili dall'utente dentro `dataPath`. La sola configurazione dei percorsi non dimostra che quella directory contenga esclusivamente file rigenerabili. Non si può quindi dichiarare definitivamente esclusa la perdita di dati.

**Stato:** percorso e separazione confermati dal codice mostrato; impatto sui dati utente non ancora completamente verificato. Nessuna modifica al repository effettuata.


## 7.16 Uso di `dataPath` in `CafeSystem::LoadSharedData()`

**Evidenza esaminata:** frammento di `src/Cafe/CafeSystem.cpp` fornito dall'utente, funzione `LoadSharedData()` e tabella `shareddataDef`.

La tabella elenca quattro font condivisi (`CafeCn.ttf`, `CafeKr.ttf`, `CafeStd.ttf`, `CafeTw.ttf`) con:
- un percorso MLC sotto `sys/title/0005001b/10042400/content/`;
- un percorso alternativo sotto `resources/sharedFonts/`.

La funzione controlla se ciascun font esiste nell'MLC oppure in `ActiveSettings::GetDataPath(...)`. Se tutti i font sono disponibili, prova ad aprire ciascun file dall'MLC per primo; se l'apertura fallisce, usa il percorso `dataPath`. Legge quindi il contenuto del file in memoria dell'emulatore.

### Fatti confermati

- `dataPath` viene usato come percorso alternativo di lettura per i font distribuiti sotto `resources/sharedFonts/`.
- La priorità è data ai font presenti nell'MLC; le risorse incluse nell'app sono il fallback.
- Nel frammento esaminato non viene scritto né modificato alcun file sotto `dataPath`: i font vengono aperti, letti in memoria e chiusi.
- Questo specifico utilizzo non indica che i font installati nell'MLC vengano cancellati quando `dataFolder` viene ricreata.

### Valutazione aggiornata

L'uso di `dataPath` in `LoadSharedData()` è coerente con una directory di risorse statiche distribuite con l'app, non con un archivio di salvataggi. È un'evidenza favorevole alla sicurezza della cancellazione della sottodirectory `data`, ma riguarda soltanto questa funzione e non dimostra che tutti gli altri componenti trattino `dataPath` esclusivamente in lettura.

**Stato:** lettura/fallback dei font confermata; nessuna scrittura in `dataPath` nel frammento esaminato. Restano da controllare gli altri usi del percorso. Nessuna modifica al repository effettuata.


## 7.17 Inventario completo delle occorrenze `GetDataPath`

**Evidenza esaminata:** output di `git grep -n 'GetDataPath' upstream/pr-1909 -- src` fornito dall'utente.

L'output elenca queste occorrenze nel codice sorgente:

- `src/Cafe/CafeSystem.cpp:308` — controllo `fs::exists()` per la risorsa font condivisa.
- `src/Cafe/CafeSystem.cpp:333` — costruzione del percorso alternativo per aprire il font, già analizzato nella sezione 7.16.
- `src/Cafe/GameProfile/GameProfile.cpp:188` — costruzione del percorso `gameProfiles/default/{title_id}.ini`.
- `src/config/ActiveSettings.h:55` — definizione inline di `GetDataPath()`, che concatena il percorso base `s_data_path` con il percorso relativo.
- `src/gui/wxgui/CemuApp.cpp:536` — aggiunta della directory `resources` come prefisso di ricerca dei cataloghi di traduzione.
- `src/util/libusbWrapper/libusbWrapper.cpp:21` — costruzione del percorso `resources/libusb-1.0.dll`.

### Cosa dimostra l'inventario

- Nell'output fornito non compaiono altre chiamate a `GetDataPath()` oltre a quelle elencate.
- Le occorrenze individuate sono coerenti con percorsi di risorse: font, profili di gioco, cataloghi di traduzione e una libreria libusb.
- L'inventario da solo non dimostra il comportamento interno delle funzioni che consumano quei percorsi. In particolare, per `GameProfile.cpp`, `CemuApp.cpp` e `libusbWrapper.cpp` occorre leggere il contesto dei rispettivi callsite prima di classificare definitivamente ogni accesso come sola lettura.

### Valutazione

L'elenco rafforza l'ipotesi che `dataPath` sia destinato a risorse statiche dell'applicazione e non ai dati utente. Tuttavia, la classificazione definitiva richiede ancora l'ispezione dei tre callsite non analizzati nel dettaglio. L'assenza di altre occorrenze testuali di `GetDataPath()` nel grep fornito non esclude accessi al percorso tramite altre API o percorsi costruiti indirettamente.

**Stato:** inventario delle occorrenze registrato; analisi completa delle modalità di accesso ancora aperta. Nessuna modifica al repository effettuata.


## 7.18 `GameProfile::Load()`: profili personalizzati e profili predefiniti

**Evidenza esaminata:** frammento di `src/Cafe/GameProfile/GameProfile.cpp` fornito dall'utente, funzione `GameProfile::Load(uint64_t title_id)`.

La funzione costruisce prima un percorso tramite `ActiveSettings::GetConfigPath("gameProfiles/{:016x}.ini", title_id)` e tenta di caricare il file con `FileStream::LoadIntoMemory()`. Se il caricamento non riesce, usa come fallback `ActiveSettings::GetDataPath("gameProfiles/default/{:016x}.ini", title_id)`. Se anche il fallback non è disponibile, ritorna `false`. Quando usa il fallback imposta `m_is_default = true`; quando il profilo viene trovato nel percorso di configurazione imposta `m_is_default = false`. Il contenuto viene poi analizzato come INI.

### Fatti confermati

- I profili personalizzati vengono cercati in `configPath/gameProfiles/`.
- I profili predefiniti vengono cercati in `dataPath/gameProfiles/default/`.
- Il frammento mostra caricamento in memoria e parsing del contenuto; non mostra scritture o modifiche dei file in `dataPath`.
- Nel port Android `configPath` punta a `internalCemuUserFolder`, mentre `dataPath` punta a `internalCemuDataFolder`, cioè alla sottodirectory `data`.

### Valutazione

Questo callsite è coerente con la separazione tra profili personalizzati dell'utente e profili predefiniti distribuiti con l'app. La cancellazione e ricostruzione di `internalFolder()/data` può rimuovere i profili predefiniti copiati dall'APK, che vengono ripristinati quando il metodo di copia viene eseguito con un hash aggiornato; il codice esaminato non indica che i profili personalizzati in `configPath` siano coinvolti.

**Stato:** uso di `dataPath` per il fallback dei profili predefiniti confermato; nessuna scrittura in `dataPath` visibile nel frammento. Nessuna modifica al repository effettuata.


## 7.19 `CemuApp::GetAvailableTranslationLanguages()`: ricerca dei cataloghi di traduzione

**Evidenza esaminata:** frammento di `src/gui/wxgui/CemuApp.cpp` fornito dall'utente, funzione `CemuApp::GetAvailableTranslationLanguages(wxTranslations* translationsMgr)`.

La funzione aggiunge `ActiveSettings::GetDataPath("resources")` come prefisso di ricerca per `wxFileTranslationsLoader`, quindi interroga `translationsMgr->GetAvailableTranslations("cemu")` e converte i nomi delle lingue riconosciute in `wxLanguageInfo`.

### Fatti confermati

- `dataPath/resources` viene registrata come directory di ricerca dei cataloghi di traduzione.
- Il frammento mostra la ricerca e l'enumerazione delle traduzioni disponibili.
- Nel codice fornito non sono presenti operazioni di scrittura o modifica dei cataloghi in `dataPath`.

### Valutazione

Questo callsite è coerente con l'uso di `dataPath` come archivio di risorse dell'applicazione. Se `saveDataFiles()` ricrea la sottodirectory `data`, i cataloghi distribuiti con l'app vengono ricopiati dal pacchetto quando la funzione di aggiornamento dei dati viene eseguita con un hash diverso.

**Stato:** uso di `dataPath/resources` come percorso di ricerca confermato; nessuna scrittura visibile nel frammento. Nessuna modifica al repository effettuata.


## 7.20 `libusbWrapper::init()`: DLL Windows sotto `dataPath`

**Evidenza esaminata:** frammento di `src/util/libusbWrapper/libusbWrapper.cpp` fornito dall'utente, funzione `libusbWrapper::init()`.

L'accesso a `ActiveSettings::GetDataPath("resources/libusb-1.0.dll")` si trova all'interno del ramo di compilazione `#if BOOST_OS_WINDOWS`. In tale ramo il wrapper tenta prima `LoadLibraryW(L"libusb-1.0.dll")` e, se il caricamento fallisce, prova il percorso della DLL sotto `dataPath/resources`. Il ramo `#else` registra il messaggio `libusbWrapper: Not supported on this OS`.

### Fatti confermati

- Il percorso `dataPath/resources/libusb-1.0.dll` è usato nel ramo Windows.
- Il frammento mostra un tentativo di caricamento dinamico della DLL, non una scrittura del file.
- Per le piattaforme che compilano il ramo `#else`, il wrapper dichiara il supporto non disponibile e non esegue il caricamento dal percorso `dataPath`.
- Il codice mostrato non indica una dipendenza Android da questa DLL Windows.

### Valutazione

Questo callsite non aggiunge un rischio di cancellazione di dati utente in `dataPath`: il suo accesso è condizionato a Windows e consiste nel caricamento di una libreria. La classificazione effettiva del ramo Android dipende dalle macro di piattaforma definite dalla toolchain, ma il codice sorgente colloca chiaramente l'uso del percorso DLL nel solo ramo `BOOST_OS_WINDOWS`.

### Chiusura dell'inventario diretto `GetDataPath()`

Con questo frammento è stato esaminato anche l'ultimo callsite applicativo emerso dall'output `git grep` fornito dall'utente. I casi analizzati sono coerenti con risorse distribuite (font, profili predefiniti, traduzioni e DLL Windows); nei frammenti forniti non è emersa una scrittura in `dataPath`.

**Conclusione provvisoria:** l'evidenza raccolta supporta l'interpretazione di `internalFolder()/data` come directory di risorse dell'app ricostruibile dagli asset, separata dalla directory principale usata per user data, configurazione e cache. Questa conclusione resta limitata ai callsite diretti e ai frammenti effettivamente esaminati: non esclude accessi indiretti o scritture tramite altre API che non passano da `GetDataPath()`.

**Stato:** inventario diretto esaminato; nessuna modifica al repository effettuata.


## 7.21 Ricerca estesa di `dataPath` e `s_data_path`

**Evidenza esaminata:** output di `git grep -n -E 'GetDataPath|s_data_path|dataPath' upstream/pr-1909 -- src` fornito dall'utente.

L'output riporta:
- i tre callsite applicativi già esaminati: font in `CafeSystem.cpp`, profili predefiniti in `GameProfile.cpp`;
- il passaggio del parametro `dataPath` attraverso JNI (`NativeActiveSettings.kt` e `NativeActiveSettings.cpp`);
- l'assegnazione di `s_data_path` in `ActiveSettings.cpp`;
- le dichiarazioni di `dataPath` e `GetDataPath()` in `ActiveSettings.h`;
- l'impostazione Kotlin `dataPath = internalCemuDataFolder` in `CemuApplication.kt`.

Non compaiono ulteriori occorrenze di `dataPath`, `s_data_path` o `GetDataPath` nell'output fornito.

### Valutazione

Questa ricerca estesa non ha individuato nuovi callsite di utilizzo delle risorse oltre a quelli già analizzati. Le occorrenze aggiuntive sono passaggi e memorizzazione del percorso tra Kotlin, JNI e `ActiveSettings`.

Il risultato rafforza la conclusione provvisoria che `dataPath` sia usato come directory di risorse dell'app. Resta il limite della ricerca testuale: non rileva eventuali percorsi equivalenti costruiti con altre variabili/API o accessi indiretti che non menzionano questi identificatori.

**Stato:** ricerca estesa completata per i termini indicati; nessun nuovo callsite individuato. Nessuna modifica al repository effettuata.


## 7.22 Ordine di avvio: `saveDataFiles()` dopo `initializeCemu()`

**Evidenza esaminata:** estratto di `CemuApplication.kt` fornito dall'utente. In `Application.onCreate()` l'ordine è:

1. `super.onCreate()`
2. `configureExceptionHandler()`
3. `AppSettingsStore.init(this)`
4. `NativeFiles.initialize(contentResolver)`
5. `initializeTranslations()`
6. `initializeCemu()`
7. `saveDataFiles()`

La funzione `initializeCemu()` esegue, nell'ordine già mostrato in precedenza, `initializeActiveSettings(...)`, imposta i percorsi nativi, chiama `initializeEmulation()`, `initializeSwkbd()` e `refreshGraphicPacks()`.

### Implicazioni

- `saveDataFiles()` viene eseguita **dopo** l'inizializzazione dell'emulazione nativa e dopo `refreshGraphicPacks()`.
- Al primo avvio, se `internalCemuDataFolder` non contiene ancora le risorse copiate dall'APK, l'inizializzazione nativa e l'aggiornamento dei graphic pack avvengono prima della loro estrazione.
- Se cambia `hash.txt`, la funzione elimina e ricrea la cartella `data` soltanto dopo l'inizializzazione nativa. In quel caso Cemu può aver già inizializzato componenti usando i file della versione precedente prima che la cartella venga sostituita.
- Non è ancora dimostrato che questo ordine causi un malfunzionamento: occorre verificare quali componenti leggano effettivamente tali risorse durante `initializeEmulation()` e `refreshGraphicPacks()`, e se le letture siano ripetute più tardi.

### Valutazione

**Rischio concreto da verificare:** ordine potenzialmente errato per il primo avvio e per gli aggiornamenti degli asset. La soluzione architetturalmente più robusta sarebbe completare la sincronizzazione degli asset prima di inizializzare i componenti nativi che li possono leggere; questa è una raccomandazione di audit, non una modifica applicata.

**Stato:** ordine di chiamata confermato dal codice mostrato; impatto runtime non ancora riprodotto. Nessuna modifica al repository effettuata.


## 7.23 Corpo di `NativeEmulation_initializeEmulation()`

**Evidenza esaminata:** output di `grep -n -A100 -B10 'initializeEmulation'` su `NativeEmulation.cpp`, fornito dall'utente. Il corpo JNI è:

```cpp
FilesystemAndroid::SetFilesystemCallbacks(std::make_shared<AndroidFilesystemCallbacks>());
GetConfigHandle().SetFilename(ActiveSettings::GetConfigPath("settings.xml").generic_wstring());
NativeEmulation::CreateCemuDirectories();
NetworkConfig::LoadOnce();
ActiveSettings::Init();
LatteOverlay_init();
CemuCommonInit();
```

### Valutazione

- La funzione configura i callback SAF, imposta il file `settings.xml` sotto `configPath`, crea le directory Cemu, carica la configurazione di rete, inizializza `ActiveSettings`, l'overlay Latte e infine il nucleo comune Cemu.
- Nel corpo mostrato non compare un accesso esplicito a `GetDataPath()` né un'estrazione/copia di asset.
- Questo **non dimostra** che l'inizializzazione non legga `dataPath`: le funzioni chiamate (`CreateCemuDirectories`, `NetworkConfig::LoadOnce`, `ActiveSettings::Init`, `LatteOverlay_init`, `CemuCommonInit`) possono effettuare operazioni indirette. In particolare, `CemuCommonInit()` e l'inizializzazione dei graphic pack richiedono verifiche separate.
- Il rischio legato all'ordine `initializeCemu()` → `saveDataFiles()` rimane quindi aperto, ma non è ancora provato un errore di avvio.

**Stato:** corpo JNI esaminato; nessun accesso diretto a `dataPath` nel corpo; dipendenze chiamate ancora da verificare. Nessuna modifica al repository effettuata.


## 7.24 Completamento di `CreateCemuDirectories()`: file lingua e paese

**Evidenza esaminata:** completamento del corpo di `CreateCemuDirectories()` fornito dall'utente.

Dopo aver creato le directory MLC per sistema, titoli, DLC, aggiornamenti e salvataggi Mii Maker, la funzione:
- crea `mlc01/sys/title/0005001b/1005c000/content`;
- se `language.txt` non esiste, lo crea e vi scrive i codici `ja`, `en`, `fr`, `de`, `it`, `es`, `ko`, `nl`, `pt`, `ru`, `zh`;
- se `country.txt` non esiste, lo crea e scrive 201 voci ottenute da `NCrypto::GetCountryAsString(i)`, sostituendo il codice `NN` con `NULL`.

### Valutazione

- Tutte le operazioni mostrate sono riferite al percorso MLC (`ActiveSettings::GetMlcPath()`), non a `dataPath`.
- I file lingua e paese vengono creati solo se non esistono: la funzione non li sovrascrive a ogni avvio.
- Le chiamate a `fs::create_directories` e l'apertura degli `ofstream` non mostrano controlli espliciti dell'esito nel frammento fornito. Un errore di permessi o spazio potrebbe quindi lasciare la struttura incompleta senza una gestione locale evidente; non è stato riprodotto.
- La funzione non fornisce evidenza di letture anticipate degli asset `resources/` o `gameProfiles/`.

**Stato:** corpo esaminato fino alla creazione di `country.txt`; nessun accesso a `dataPath` nel codice mostrato. L'ordine `initializeCemu()` prima di `saveDataFiles()` resta da verificare nelle funzioni successive della catena di inizializzazione. Nessuna modifica al repository effettuata.


## 7.25 `CemuCommonInit()`: inizializzazione prima della copia degli asset

**Evidenza esaminata:** definizione di `CemuCommonInit()` in `src/main.cpp` (frammento fornito dall'utente, righe circa 115–160) e ordine di chiamata già verificato in `CemuApplication.onCreate()`.

La funzione esegue, nell'ordine mostrato:
- riconfigurazione dei driver OpenGL e Vulkan;
- inizializzazione AES e timer PPC;
- `WindowsInitCwd()` ed `ExceptionHandler_Init()`;
- caricamento della configurazione (`GetConfigHandle().Load()`) e della configurazione di rete, se presente;
- avvio asincrono dell'inizializzazione audio e di `GraphicPack2::LoadAll()`;
- caricamento dell'input e attesa (`wait()`) dei due task asincroni;
- `CafeSystem::Initialize()`;
- inizializzazione e aggiornamento degli elenchi titoli e salvataggi, usando user data path e MLC.

### Impatto sull'ordine di avvio Android

La catena già verificata è:
1. `CemuApplication.onCreate()` chiama `initializeCemu()`;
2. `initializeCemu()` chiama `initializeEmulation()`;
3. `initializeEmulation()` chiama `CemuCommonInit()`;
4. solo dopo il ritorno da `initializeCemu()`, `onCreate()` chiama `saveDataFiles()`.

Pertanto `CafeSystem::Initialize()` e `GraphicPack2::LoadAll()` vengono eseguite prima che `saveDataFiles()` copi gli asset in `internalCemuDataFolder`. I task audio e graphic-pack vengono attesi prima che `CemuCommonInit()` termini.

**Rischio concreto da verificare:** in precedenza è stato esaminato `CafeSystem::LoadSharedData()`: per i font condivisi prova prima il percorso MLC e, se il file non è disponibile lì, usa il fallback `dataPath/resources/sharedFonts`. Al primo avvio, se i font non sono già presenti nell'MLC, il fallback potrebbe essere eseguito prima che `saveDataFiles()` abbia copiato i font in `dataPath`. Questo può causare un caricamento incompleto dei font condivisi al primo avvio. Il frammento non dimostra che il problema si manifesti sempre: dipende dalla disponibilità dei file nell'MLC e dalla gestione degli errori di caricamento.

**Punto ancora aperto:** `GraphicPack2::LoadAll()` viene eseguita prima della copia degli asset, ma il frammento non dimostra che legga `dataPath`. Occorre esaminare la sua implementazione e i percorsi effettivamente usati prima di concludere che sia coinvolta.

**Stato:** ordine di chiamata confermato dal codice fornito; rischio di inizializzazione anticipata dei font individuato. Nessuna modifica al repository effettuata.


## 7.26 `ActiveSettings::GetPath()` e callsite di `_utf8ToPath`

**Evidenza esaminata:** estratto di `src/config/ActiveSettings.h` e output di `git grep -n -E 'SetPaths|_utf8ToPath' upstream/pr-1909 -- src`, forniti dall'utente.

### Implementazioni di `GetPath()`

L'header presenta quattro overload privati:

1. `GetPath(path, std::string_view format, args...)`: formatta con `fmt::format(fmt::runtime(...))`, converte il risultato tramite `_utf8ToPath()` e lo concatena al percorso base con `operator/`.
2. `GetPath(path, std::wstring_view format, args...)`: formatta il percorso wide con `{fmt}` e lo concatena al percorso base.
3. `GetPath(path, std::string_view p)`: costruisce una vista `char8_t` reinterpretando i byte della stringa e crea un `fs::path` UTF-8, poi lo concatena al percorso base.
4. `GetPath(path)`: restituisce il percorso base senza trasformazioni.

Gli accessor pubblici `GetUserDataPath`, `GetConfigPath`, `GetCachePath` e `GetDataPath` inoltrano gli argomenti ai rispettivi percorsi statici. `GetMlcPath(args...)` usa invece `GetPath(GetMlcPath(), ...)`.

### Limite del controllo sui percorsi assoluti

I due overload con formattazione contengono un `cemu_assert_debug` che verifica il primo carattere della stringa `format` (o `format` wide), rifiutando in debug un formato che inizi direttamente con `/` o `\\`.

Il controllo, tuttavia, viene eseguito sulla stringa di formato **prima** dell'espansione di `{fmt}`. Non verifica il percorso finale prodotto dagli argomenti. Un formato che inizi con un segnaposto, ad esempio `{}`, può quindi superare il controllo anche se il valore sostituito inizia con un percorso assoluto. L'overload semplice `GetPath(path, std::string_view p)` non esegue questo controllo.

Con la semantica di `std::filesystem::path`, concatenare con `/` un componente destro assoluto può sostituire il percorso base. È quindi un limite di robustezza da tenere presente; non è stata dimostrata una chiamata Android che lo sfrutti né un errore runtime.

### Risultati della ricerca `_utf8ToPath` e `SetPaths`

L'output individua:
- definizione inline di `_utf8ToPath` in `src/Common/precompiled.h:601`;
- implementazione di `ActiveSettings::SetPaths()` in `src/config/ActiveSettings.cpp:14`;
- chiamata Android a `ActiveSettings::SetPaths()` in `src/android/app/src/main/cpp/NativeActiveSettings.cpp:20`;
- ulteriori utilizzi di `_utf8ToPath` nel backend Android SAF (`fscDeviceAndroidSAF.cpp:196`), nel backend HostFS, in `GraphicPack2.cpp`, `TitleList.cpp` e `NativeGraphicPacks.cpp`, oltre ad altri callsite.

La presenza del callsite SAF conferma che la conversione è coinvolta nell'apertura dei file attraverso il backend Android; il solo risultato di `grep`, però, non permette di valutare la correttezza della conversione, la codifica ricevuta o il trattamento di percorsi assoluti.

### Valutazione e verifiche mancanti

- **Confermato:** gli accessor di `ActiveSettings` delegano la composizione dei percorsi agli overload descritti.
- **Confermato:** `_utf8ToPath` è definita in `precompiled.h` e viene usata dal backend SAF Android.
- **Rilievo statico:** l'asserzione sugli assoluti controlla il formato, non il risultato formattato; l'overload semplice non contiene l'asserzione.
- **Non verificato:** corpo di `_utf8ToPath`, comportamento effettivo con UTF-8 non ASCII e percorso NDK/Android.
- **Non verificato:** corpo di `SetPaths()` nella PR e valori effettivamente passati dal livello JNI.
- **Non dimostrato:** presenza di un bug runtime o di un percorso assoluto non previsto nei callsite Android.

**Prossima verifica consigliata:** leggere `src/Common/precompiled.h` intorno alla riga 601, poi esaminare `NativeActiveSettings.cpp` e `ActiveSettings.cpp` per collegare la conversione ai percorsi realmente inizializzati su Android.

**Stato:** analisi statica parziale; nessuna modifica al codice sorgente o al repository effettuata.

## 7.26 Conversione tra `std::string` UTF-8 e `fs::path`: `_utf8ToPath`

**Evidenza esaminata:** frammento di `src/Common/precompiled.h` fornito dall'utente, comprendente la definizione di `_utf8ToPath()` e la parte finale della funzione di conversione inversa.

Il codice mostrato per `_utf8ToPath()` è:

```cpp
inline fs::path _utf8ToPath(std::string_view input)
{
    std::basic_string_view<char8_t> v((char8_t*)input.data(), input.size());
    return fs::path(v);
}
```

La funzione:
- riceve una sequenza di byte tramite `std::string_view`;
- la espone come `std::basic_string_view<char8_t>` mediante cast del puntatore;
- costruisce un `fs::path` dalla vista UTF-8.

Nel frammento immediatamente precedente, appartenente alla conversione inversa, si vede inoltre:

```cpp
std::u8string strU8 = path.generic_u8string();
std::string v((const char*)strU8.data(), strU8.size());
return v;
```

Questo passaggio ottiene la rappresentazione generica UTF-8 del percorso e ne copia i byte in una `std::string`. Il nome e l'inizio della funzione inversa non sono visibili nel frammento, quindi non vengono attribuiti qui.

### Valutazione

- La coppia di conversioni è progettata per mantenere i byte UTF-8 nel passaggio tra `std::string` e `fs::path`; `_utf8ToPath()` non esegue una conversione da una code page locale a UTF-8, ma interpreta i byte ricevuti come UTF-8.
- Questo è coerente con l'uso di `_utf8ToPath()` nei callsite già individuati, inclusi i backend filesystem HostFS e Android SAF e i percorsi dei Graphic Pack.
- Il frammento non valida che `input` contenga UTF-8 ben formato. Non è quindi dimostrata una gestione esplicita di sequenze UTF-8 non valide.
- Il cast da `char*` a `char8_t*` è una reinterpretazione della memoria, non una conversione dei caratteri. È opportuno verificarne la portabilità e la correttezza rispetto alle regole di aliasing/lifetime del C++20 e alle implementazioni STL/NDK effettivamente usate. Dal solo frammento non si può concludere che provochi un errore su Android.
- La costruzione di `fs::path` da una vista `char8_t` dipende dal supporto C++20 della toolchain, già previsto dal progetto ma non ancora verificato con una build Android completa.

### Impatto sull'audit Android

Non emerge dal codice mostrato una conversione esplicita dipendente dalla locale che renda di per sé incompatibile il percorso Android. La funzione tratta i dati come UTF-8 e li consegna a `std::filesystem` tramite `char8_t`.

Restano da verificare in compilazione NDK e a runtime: percorsi con caratteri non ASCII, URI SAF, nomi con spazi e comportamento con input UTF-8 non valido. Nessuno di questi test risulta eseguito in questa fase dell'audit.

**Stato:** implementazione di `_utf8ToPath()` esaminata dal frammento fornito; comportamento di compilazione/runtime Android non verificato. Nessuna modifica al repository effettuata.



## 7.27 Inizializzazione JNI di `ActiveSettings` su Android

**Evidenza esaminata:** frammento di `src/android/app/src/main/cpp/NativeActiveSettings.cpp` fornito dall'utente.

Il codice JNI mostrato espone queste operazioni:

- `getMLCPath()` restituisce a Java il risultato di `ActiveSettings::GetMlcPath()` tramite `JNIUtils::ToJString()`.
- `getUserDataPath()` restituisce a Java `ActiveSettings::GetUserDataPath()` tramite la stessa conversione.
- `initializeActiveSettings()` riceve da Java quattro `jstring`: `userDataPath`, `configPath`, `dataPath` e `cachePath`. Li converte tramite `JNIUtils::FromJString()` e li passa a `ActiveSettings::SetPaths()`.
- La chiamata a `SetPaths()` passa `false` come `isPortableMode` e `{}` come `executablePath`; gli altri argomenti sono nell'ordine `userDataPath`, `configPath`, `cachePath`, `dataPath`, seguiti da `failedWriteAccess`.
- `setNativeLibDir()` inoltra il percorso ricevuto a `ActiveSettings::SetNativeLibPath()`.
- `setInternalDir()` inoltra il percorso ricevuto a `ActiveSettings::SetInternalDir()`.
- `hasRequiredOnlineFiles()` restituisce il risultato di `ActiveSettings::HasRequiredOnlineFiles()`.

### Valutazione

1. **Origine dei percorsi:** i quattro percorsi principali sono forniti dal lato Java e convertiti in `fs::path` tramite `JNIUtils::FromJString()`. Il frammento non mostra come Java calcoli o normalizzi tali percorsi, né l'implementazione della conversione JNI.
2. **Modalità portabile:** Android invoca esplicitamente `SetPaths(false, ...)`, quindi non abilita la modalità portabile attraverso questa chiamata.
3. **Percorso eseguibile:** viene passato un `fs::path` vuoto (`{}`). L'effetto di questa scelta dipende dall'implementazione di `ActiveSettings::SetPaths()` e dagli eventuali callsite successivi di `GetExecutablePath()`; il solo wrapper JNI non basta per valutarne l'impatto.
4. **Controllo scrittura:** viene creato un `std::set<fs::path> failedWriteAccess` e passato a `SetPaths()`, ma nel frammento non viene letto né restituito dopo la chiamata. Non è quindi possibile stabilire da questo solo codice se gli errori di accesso in scrittura vengano gestiti altrove o ignorati.
5. **Separazione dei percorsi:** `nativeLibDir` e `internalDir` sono impostati tramite chiamate JNI distinte dai quattro percorsi passati a `SetPaths()`. Il frammento non dimostra che coincidano con uno di essi.
6. **Getters:** i getter MLC e user-data espongono i percorsi correnti di `ActiveSettings`; non ne definiscono la costruzione, che va ricostruita in `SetPaths()` e `GetMlcPath()`.

### Verifica successiva necessaria

Esaminare il corpo completo di `ActiveSettings::SetPaths()` in `src/config/ActiveSettings.cpp` e, se necessario, la parte Java/Kotlin che invoca `initializeActiveSettings()`. In particolare occorre verificare assegnazioni dei path statici, creazione delle directory, test di scrittura e trattamento di `failedWriteAccess`.

**Stato:** wrapper JNI esaminato dal frammento fornito; i valori vengono passati dal livello Java a `SetPaths()` con `isPortableMode=false` ed `executablePath` vuoto. La derivazione dei percorsi, il controllo di scrittura e l'eventuale gestione degli errori restano da verificare. Nessuna modifica al repository effettuata.


## 7.28 Implementazione di `ActiveSettings::SetPaths()`

**Evidenza esaminata:** frammento di `src/config/ActiveSettings.cpp` fornito dall'utente, comprendente il corpo di `ActiveSettings::SetPaths()` e l'inizio delle funzioni successive.

### Comportamento verificato nel codice

- `SetPaths()` memorizza `isPortableMode`, `executablePath`, `userDataPath`, `configPath`, `cachePath` e `dataPath` nelle rispettive variabili statiche (`s_isPortableMode`, `s_executable_path`, `s_user_data_path`, `s_config_path`, `s_cache_path`, `s_data_path`).
- Svuota `failedWriteAccess` all'inizio della funzione.
- Il ciclo di creazione/verifica riguarda esclusivamente `userDataPath`, `configPath` e `cachePath`.
- Per ciascuno dei tre percorsi, se `fs::exists(path, ec)` restituisce falso, viene tentata `fs::create_directories(path, ec)`.
- Successivamente viene chiamata `TestWriteAccess(path)`. Se il test fallisce, il codice registra un log `Failed to write to ...` e inserisce il percorso in `failedWriteAccess`.
- `dataPath` viene assegnato a `s_data_path`, ma non compare nel ciclo: questa funzione non tenta di crearlo e non ne verifica l'accesso in scrittura.
- `s_executable_filename` viene impostato con `s_executable_path.filename()`. Nel percorso Android osservato nel wrapper JNI, `executablePath` è passato vuoto (`{}`); il valore risultante non è stato verificato a runtime.
- Infine `s_setPathsCalled` viene impostato a `true`.

### Valutazione e limiti

1. **Separazione tra dati scrivibili e `dataPath`:** il codice tratta `userDataPath`, `configPath` e `cachePath` come directory da creare e sottoporre a test di scrittura, mentre `dataPath` viene soltanto memorizzato. Questo è compatibile con un `dataPath` usato per risorse dell'applicazione in sola lettura, ma l'intento non è dimostrato da questa funzione isolata.
2. **Errori filesystem:** `std::error_code ec` viene passato a `exists()` e `create_directories()`, ma il frammento non controlla esplicitamente `ec` dopo queste chiamate. Un errore di creazione può quindi non essere diagnosticato direttamente in quel punto; il successivo `TestWriteAccess()` può rilevare alcuni problemi di accesso, ma il suo comportamento interno non è incluso nell'evidenza esaminata.
3. **`failedWriteAccess`:** la funzione popola il set per i tre percorsi verificati. Non è mostrato qui come il chiamante utilizzi il set dopo il ritorno.
4. **Chiamata singola:** `cemu_assert_debug(!s_setPathsCalled)` documenta l'aspettativa che i percorsi vengano impostati una sola volta. Essendo un'asserzione di debug, non va considerata da sola una protezione garantita nelle build release.
5. **Android:** combinando questo frammento con il wrapper JNI già esaminato, la chiamata Android usa `isPortableMode=false` e `executablePath` vuoto; i percorsi utente/config/cache/data arrivano invece da stringhe Java convertite con `JNIUtils::FromJString()`. La correttezza dei valori effettivi dipende quindi anche dal codice Java/Kotlin chiamante, non incluso in questo estratto.

### Verifiche ancora necessarie

- Esaminare `TestWriteAccess()` per determinare esattamente come viene effettuato il test di scrittura e quali errori intercetta.
- Esaminare il chiamante Java/Kotlin di `initializeActiveSettings()` per verificare origine, esistenza e normalizzazione dei quattro percorsi.
- Verificare i callsite di `GetDataPath()` e l'eventuale necessità di avere già esistente la directory indicata da `dataPath`.
- Non è stato eseguito alcun test Android di creazione directory, permessi o filesystem.

**Stato:** corpo di `SetPaths()` esaminato staticamente dal frammento fornito. È confermato che la creazione e il test di scrittura si applicano a `userDataPath`, `configPath` e `cachePath`, non a `dataPath`. Comportamento runtime Android non verificato. Nessuna modifica al repository effettuata.


## 7.29 Ricerca dell'implementazione di `TestWriteAccess()`

**Evidenza esaminata:** output di `git grep -n -A 35 -B 5 "TestWriteAccess" upstream/pr-1909 -- src` fornito dall'utente.

### Risultato

- L'output riportato mostra il solo punto d'uso individuato in `src/config/ActiveSettings.cpp`, righe 30–40: `TestWriteAccess(path)` viene invocata per ciascuno dei percorsi `userDataPath`, `configPath` e `cachePath`.
- Se la funzione restituisce `false`, il chiamante scrive un messaggio di log e inserisce il percorso nel set `failedWriteAccess`.
- Nell'output condiviso non compare il corpo né la dichiarazione di `TestWriteAccess()`. Di conseguenza non è ancora possibile stabilire se il test crei un file temporaneo, apra un file, controlli i permessi in altro modo o come gestisca gli errori.
- Non si può concludere che l'implementazione manchi dal repository: il risultato incollato potrebbe essere incompleto oppure la definizione potrebbe trovarsi fuori dal percorso `src` o usare una forma di dichiarazione diversa da quella cercata.

### Verifica successiva necessaria

Ripetere la ricerca sull'intero tree della ref `upstream/pr-1909`, senza limitare il percorso a `src`, per individuare sia la definizione sia eventuali dichiarazioni o wrapper.

**Stato:** callsite confermato; implementazione di `TestWriteAccess()` non ancora acquisita. Nessuna conclusione sul metodo concreto di verifica della scrittura e nessun test runtime Android eseguito. Nessuna modifica al repository effettuata.


## 7.30 Individuazione della definizione di `TestWriteAccess()`

**Evidenza esaminata:** output di `git grep -n -E "TestWriteAccess" upstream/pr-1909` fornito dall'utente.

### Risultato

La ricerca sull'intera ref `upstream/pr-1909` individua tre occorrenze:

- `src/config/ActiveSettings.cpp:35`: callsite che verifica i percorsi `userDataPath`, `configPath` e `cachePath` durante `ActiveSettings::SetPaths()`.
- `src/util/helpers/helpers.cpp:274`: definizione della funzione `bool TestWriteAccess(const fs::path& p)`.
- `src/util/helpers/helpers.h:48`: dichiarazione della funzione.

Questo conferma che l'implementazione è presente nel tree della PR e si trova sotto `src/util/helpers/`. Il precedente output condiviso non includeva la definizione; non è quindi corretto dedurre che la funzione fosse fuori da `src` o assente dal repository.

### Valutazione e limiti

- È confermata l'esistenza di una funzione helper dedicata al test di scrittura, dichiarata in `helpers.h` e definita in `helpers.cpp`.
- La firma accetta un `const fs::path&` e restituisce `bool`.
- Il solo numero di riga e la firma non rivelano quale operazione venga eseguita, se venga creato un file temporaneo, come venga scelto il nome del file, se il file venga rimosso né come siano gestiti gli errori.
- Non è ancora possibile valutare eventuali implicazioni specifiche per Android/SAF o per i filesystem usati dal port.

### Verifica successiva necessaria

Leggere il corpo della funzione e il contesto immediatamente circostante in `src/util/helpers/helpers.cpp` alla riga 274.

**Stato:** definizione e dichiarazione localizzate staticamente nella ref `upstream/pr-1909`; implementazione non ancora esaminata. Nessun test runtime Android eseguito. Nessuna modifica al repository effettuata.


## 7.31 Implementazione di `TestWriteAccess()`

**Evidenza esaminata:** corpo di `bool TestWriteAccess(const fs::path& p)` in `src/util/helpers/helpers.cpp`, righe intorno alla 274, fornito dall'utente dalla ref `upstream/pr-1909`.

### Comportamento verificato nel codice

1. Crea un `std::error_code ec` locale.
2. Verifica che `p` esista e sia una directory tramite `fs::exists(p, ec)` e `fs::is_directory(p, ec)`. Se una delle condizioni non è soddisfatta, restituisce `false`.
3. Esegue fino a tre tentativi. Per ogni tentativo costruisce un nome temporaneo nella directory `p`, con il formato `_<8 caratteri casuali>.tmp`, usando `GenerateRandomString(8)`.
4. Se `fs::exists(filename, ec)` indica che il nome è già occupato, passa al tentativo successivo.
5. Altrimenti costruisce `std::ofstream file(filename)`. Se `file.is_open()` è falso, interrompe il ciclo e restituisce `false`.
6. Se l'apertura riesce, chiude il file, tenta di rimuoverlo con `fs::remove(filename, ec)` e restituisce `true`.
7. Se i tentativi terminano senza creare/aprire il file, restituisce `false`.

### Valutazione e limiti

- **Test positivo di scrittura:** il risultato `true` dimostra che, in quel momento e tramite `std::ofstream`, è stato possibile aprire/creare un file con quel nome nella directory e chiuderlo. Non dimostra che tutte le successive operazioni di scrittura, rinomina, sincronizzazione o cancellazione funzioneranno.
- **Rimozione non verificata:** il valore restituito da `fs::remove(filename, ec)` viene ignorato. La funzione restituisce `true` anche se la rimozione fallisce o segnala un errore. Può quindi lasciare un file `_xxxxxxxx.tmp` nella directory senza segnalarlo al chiamante.
- **Errori filesystem non distinti:** `ec` viene passato a `exists()`, `is_directory()` e `remove()`, ma non viene controllato esplicitamente. In particolare, un errore di `remove()` non cambia il valore di ritorno. Per i controlli iniziali, la funzione riduce l'esito a `true/false` senza esporre al chiamante la causa dell'eventuale errore.
- **Collisione del nome:** se il nome generato esiste già, viene tentato un altro nome, fino a tre tentativi. Il frammento non mostra una gestione separata del caso in cui tutti e tre i nomi siano occupati.
- **Creazione del file:** il controllo usa `std::ofstream` con il comportamento predefinito; nel frammento non sono impostate opzioni esplicite di apertura né viene scritto contenuto nel file. Il test verifica quindi l'apertura/creazione e la chiusura, non una scrittura di payload.
- **Impatto sul port Android:** poiché `ActiveSettings::SetPaths()` invoca questa funzione per `userDataPath`, `configPath` e `cachePath`, il test esercita l'accesso filesystem nativo a tali directory. Non verifica `dataPath`. Non è stato eseguito un test sul dispositivo Android, quindi compatibilità concreta con i percorsi e i filesystem usati dall'app resta da verificare.

### Correzione/precisazione rispetto alla sezione 7.28

La sezione 7.28 osservava che il successivo `TestWriteAccess()` avrebbe potuto rilevare alcuni problemi di accesso. Ora che il corpo è disponibile, si precisa che il controllo consiste nell'apertura di un file temporaneo e non verifica l'esito della sua rimozione. Il codice non fornisce inoltre dettagli diagnostici dell'errore al chiamante, oltre al booleano.

### Verifiche ancora necessarie

- Verificare il codice Java/Kotlin che costruisce e passa `userDataPath`, `configPath`, `cachePath` e `dataPath`.
- Verificare sul dispositivo Android la creazione e la rimozione del file temporaneo nelle directory effettivamente passate a `SetPaths()`.
- Se il comportamento di pulizia è rilevante, verificare runtime cosa accade quando la cancellazione del file temporaneo è negata o fallisce.

**Stato:** corpo di `TestWriteAccess()` esaminato staticamente dal frammento fornito. È confermato che il test crea/apre e chiude un file temporaneo; il risultato della rimozione non viene controllato. Nessun test runtime Android eseguito. Nessuna modifica al repository effettuata.


## 7.32 JNI bridge `initializeActiveSettings()`: acquisizione parziale degli argomenti

**Evidenza esaminata:** output di `git grep -n -A 12 -B 12 "initializeActiveSettings" upstream/pr-1909 -- src/android` fornito dall'utente, relativo a `src/android/app/src/main/cpp/NativeActiveSettings.cpp`, righe 5–23.

### Risultato verificabile dal frammento

- `getMLCPath()` restituisce una stringa Java ottenuta da `ActiveSettings::GetMlcPath()` tramite `JNIUtils::ToJString()`.
- `getUserDataPath()` restituisce analogamente `ActiveSettings::GetUserDataPath()`.
- La funzione JNI `initializeActiveSettings()` riceve quattro `jstring`: `userDataPath`, `configPath`, `dataPath` e `cachePath`.
- All'inizio della funzione viene creato `std::set<fs::path> failedWriteAccess`.
- Il frammento mostra l'inizio della chiamata a `ActiveSettings::SetPaths()`: primo argomento `false`, secondo argomento `{}`, terzo argomento `JNIUtils::FromJString(env, userDataPath)`.

### Limiti dell'evidenza

Il testo fornito si interrompe alla conversione di `userDataPath`. Non mostra ancora gli argomenti successivi di `SetPaths()`, né l'eventuale uso di `failedWriteAccess` dopo la chiamata. Non è quindi corretto ricostruire da questo estratto l'ordine completo delle conversioni o concludere come il chiamante gestisca i percorsi non scrivibili.

### Verifica successiva necessaria

Acquisire le righe immediatamente successive della stessa funzione, fino alla sua parentesi di chiusura, per verificare la mappatura completa dei quattro percorsi e il trattamento del set `failedWriteAccess`.

**Stato:** ponte JNI e primi argomenti individuati; corpo della chiamata `SetPaths()` acquisito solo parzialmente. Nessun test runtime Android eseguito. Nessuna modifica al repository effettuata.

## 7.33 Individuazione del chiamante Kotlin di `initializeActiveSettings()`

**Evidenza esaminata:** output di `git grep -n -F 'initializeActiveSettings' upstream/pr-1909 -- src/android/app` fornito dall'utente.

### Risultato verificabile

La ricerca individua tre punti nel sorgente Android:

- `src/android/app/src/main/cpp/NativeActiveSettings.cpp:17`: implementazione JNI `Java_info_cemu_cemu_nativeinterface_NativeActiveSettings_initializeActiveSettings(...)`.
- `src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:8`: import Kotlin di `info.cemu.cemu.nativeinterface.NativeActiveSettings.initializeActiveSettings`.
- `src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:134`: punto di invocazione Kotlin `initializeActiveSettings(`.
- `src/android/app/src/main/java/info/cemu/cemu/nativeinterface/NativeActiveSettings.kt:5`: dichiarazione Kotlin `external fun initializeActiveSettings(...)`.

### Interpretazione e limiti

È ora verificato che l'inizializzazione dei percorsi nativi viene invocata da `CemuApplication.kt` e che la funzione è dichiarata come funzione esterna in `NativeActiveSettings.kt`. La sola ricerca delle occorrenze non mostra però i valori effettivamente passati, come vengono costruite le directory, né il contesto temporale dell'invocazione rispetto alla creazione delle directory e all'inizializzazione del core.

### Verifica successiva necessaria

Leggere il blocco di `CemuApplication.kt` intorno alla riga 134 e la dichiarazione completa in `NativeActiveSettings.kt`, per ricostruire i quattro argomenti e verificare l'ordine di inizializzazione.

**Stato:** chiamante Kotlin e dichiarazione esterna individuati staticamente. Costruzione dei percorsi e ordine runtime non ancora verificati. Nessun test runtime Android eseguito. Nessuna modifica al codice sorgente del repository effettuata.

## 7.34 Kotlin `initializeCemu()`: assegnazione dei percorsi e ordine di inizializzazione

**Evidenza esaminata:** frammento fornito dall'utente da `src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt`, contenente il corpo di `initializeCemu()` e le proprietà `internalCemuDataFolder` e `internalCemuUserFolder`.

### Percorsi passati al bridge JNI

La chiamata Kotlin assegna:

- `userDataPath = internalCemuUserFolder`
- `configPath = internalCemuUserFolder`
- `dataPath = internalCemuDataFolder`
- `cachePath = internalCemuUserFolder`

Le proprietà mostrate definiscono `internalCemuUserFolder` come `internalFolder().toString()` e `internalCemuDataFolder` come `internalFolder().resolve("data").toString()`.

**Conclusione verificata:** user data, configurazione e cache condividono la stessa directory radice interna dell'app; `dataPath` è la sottodirectory `data` sotto quella radice. Il frammento non mostra il corpo di `internalFolder()`, quindi non stabilisce il percorso assoluto risultante.

### Ordine delle operazioni in `initializeCemu()`

L'ordine esplicito nel frammento è:

1. lettura di `resources.displayMetrics`;
2. `setDPI(displayMetrics.density)`;
3. `initializeActiveSettings(...)`;
4. `setNativeLibDir(applicationInfo.nativeLibraryDir)`;
5. `setInternalDir(dataDir.absolutePath)`;
6. `initializeEmulation()`;
7. `initializeSwkbd()`;
8. `refreshGraphicPacks()`.

Il bridge `initializeActiveSettings()` viene quindi invocato prima di `setNativeLibDir()` e `setInternalDir()`. Queste ultime due impostazioni precedono `initializeEmulation()` e `refreshGraphicPacks()`.

### Correlazione con `saveDataFiles()`

L'audit precedente aveva rilevato che `saveDataFiles()` viene eseguita dopo `initializeCemu()`. Il frammento attuale conferma che `refreshGraphicPacks()` fa parte di `initializeCemu()` e quindi precede `saveDataFiles()` nel flusso descritto.

**Rischio da verificare:** risorse collocate in `internalCemuDataFolder` potrebbero essere richieste durante `initializeEmulation()` o `refreshGraphicPacks()` prima che `saveDataFiles()` completi l'estrazione o l'aggiornamento dei dati. È un rischio dedotto dall'ordine delle chiamate, non la prova di un errore runtime. Occorre verificare quali risorse siano lette in quelle fasi e se siano già presenti sul dispositivo.

### Limiti dell'evidenza

- Non è stato fornito il corpo di `internalFolder()`.
- Non è stato eseguito un test runtime Android per verificare l'accesso alle risorse prima dell'estrazione.

**Stato:** assegnazione dei quattro percorsi e ordine interno di `initializeCemu()` verificati staticamente dal frammento Kotlin fornito. Resta da verificare il corpo di `internalFolder()` e il comportamento runtime delle risorse. Nessuna modifica al codice sorgente del progetto.


## 7.35 Ricerca di `internalFolder()`: nessuna corrispondenza nella ricerca Kotlin iniziale

**Evidenza esaminata:** output vuoto del comando:

```bash
git grep -n -A 15 -B 10 'fun internalFolder\|internalFolder()' upstream/pr-1909 -- src/android/app/src/main/java
```

### Risultato verificabile

La ricerca non ha restituito occorrenze che corrispondano ai pattern indicati nei file sotto `src/android/app/src/main/java` della revisione `upstream/pr-1909`.

### Limiti dell'evidenza

L'assenza di risultati per questa ricerca non dimostra che `internalFolder` non esista. La dichiarazione potrebbe avere una forma sintattica differente, trovarsi in un file Java o in un'altra directory/modulo, oppure essere fornita da una dipendenza o da codice generato. Anche il pattern `internalFolder()` cerca una forma testuale specifica e può non intercettare dichiarazioni o riferimenti con formattazione diversa.

### Verifica successiva

Allargare la ricerca all'intero albero Android e cercare il token `internalFolder` senza vincolare la sintassi Kotlin, poi esaminare i risultati pertinenti.

**Stato:** nessuna corrispondenza nella ricerca iniziale limitata a `src/android/app/src/main/java`; origine e implementazione di `internalFolder()` ancora non determinate. Nessuna modifica al codice sorgente del progetto.


## 7.36 Individuazione della definizione di `internalFolder()`

**Evidenza esaminata:** output fornito dall'utente di `git grep -n -i 'internalFolder' upstream/pr-1909 -- src/android`.

### Risultato verificabile

La ricerca individua le seguenti occorrenze:

- `CemuApplication.kt:4`: import di `info.cemu.cemu.common.android.context.internalFolder`.
- `CemuApplication.kt:148`: `internalCemuDataFolder` risolve la sottocartella `data` a partire da `internalFolder()`.
- `CemuApplication.kt:151`: `internalCemuUserFolder` restituisce `internalFolder().toString()`.
- `ContextExtensions.kt:9`: dichiarazione `fun Context.internalFolder(): File`.
- `DocumentsProvider.kt:16` e `:26`: import e utilizzo di `internalFolder()` nel provider documenti.

### Conclusione

La definizione della funzione è stata individuata in `src/android/app/src/main/java/info/cemu/cemu/common/android/context/ContextExtensions.kt`, riga 9. La ricerca precedente, limitata a due forme testuali e al percorso Kotlin, non aveva trovato la definizione; non era quindi evidenza della sua assenza.

### Verifica successiva necessaria

Leggere il corpo di `ContextExtensions.kt` per determinare quale directory Android venga restituita e se la funzione ne assicuri la creazione. L'output corrente mostra soltanto la dichiarazione, non il corpo.

**Stato:** posizione della definizione e callsite individuati staticamente. Percorso assoluto e comportamento di creazione della directory ancora da verificare. Nessun test runtime Android eseguito; nessuna modifica al codice sorgente.


## 7.37 Corpo di `Context.internalFolder()`: directory esterna app-specifica e fallback interno

**Evidenza esaminata:** corpo di `src/android/app/src/main/java/info/cemu/cemu/common/android/context/ContextExtensions.kt` fornito dall'utente.

### Implementazione verificata

La funzione è:

```kotlin
fun Context.internalFolder(): File {
    val externalFilesDir = getExternalFilesDir(null)
    if (externalFilesDir != null) {
        return externalFilesDir
    }
    return filesDir
}
```

Quindi:

1. tenta di ottenere `getExternalFilesDir(null)`;
2. se il risultato non è `null`, restituisce quel `File`;
3. se il risultato è `null`, restituisce `filesDir`.

La funzione non chiama esplicitamente `mkdir()`, `mkdirs()` o altre operazioni di creazione della directory.

### Implicazioni per i percorsi Cemu

In base alle proprietà già esaminate in `CemuApplication.kt`:

- `internalCemuUserFolder` è il percorso restituito da `internalFolder()`;
- `internalCemuDataFolder` è la sottodirectory `data` sotto quel percorso;
- `userDataPath`, `configPath` e `cachePath` ricevono `internalCemuUserFolder`;
- `dataPath` riceve `internalCemuDataFolder`.

Il percorso effettivo dipende dunque dal valore restituito da `getExternalFilesDir(null)` sul dispositivo; solo se tale chiamata restituisce `null` viene usato `filesDir`. Il frammento non permette di determinare il percorso assoluto sul dispositivo dell'utente.

### Valutazione statica

**Fatto:** `internalFolder()` seleziona prima la directory app-specifica ottenuta tramite `getExternalFilesDir(null)` e usa `filesDir` come fallback.

**Fatto:** la funzione restituisce un oggetto `File` senza creare esplicitamente la directory o la sottodirectory `data`.

**Da verificare:** se e in quale fase Android o altro codice dell'app garantiscano l'esistenza delle directory restituite, e se la directory `data` venga creata prima che il codice nativo la utilizzi. La funzione mostrata, da sola, non garantisce la creazione di `data`.

**Stato:** corpo di `internalFolder()` esaminato staticamente. Non è stato verificato il percorso runtime sul dispositivo, né l'esistenza/creazione effettiva delle directory. Nessun test runtime Android eseguito; nessuna modifica al codice sorgente.


## 7.38 Correzione della ricerca di `saveDataFiles()` e `internalCemuDataFolder`

**Aggiornamento del 14 novembre 2026 — questa sezione sostituisce la precedente 7.38, che riportava un esito vuoto ormai superato da verifiche successive.**

**Evidenza esaminata:** risultati successivi di `git grep` e frammenti di `CemuApplication.kt` forniti dall'utente.

### Risultati corretti

Le verifiche successive hanno individuato in `src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt`:

- `saveDataFiles()` (definizione e chiamata);
- `internalCemuDataFolder`;
- la costruzione di `internalCemuDataFolder` come `internalFolder().resolve("data")`.

Il flusso di `onCreate()` verificato è:

1. `initializeTranslations()`;
2. `initializeCemu()`;
3. `saveDataFiles()`.

Di conseguenza `saveDataFiles()` viene eseguita dopo il ritorno da `initializeCemu()`, che include `initializeEmulation()` e `refreshGraphicPacks()`.

### Comportamento di `saveDataFiles()`

Dal corpo fornito:

- crea `dataFolder` con `mkdirs()` se non esiste;
- confronta il contenuto di `hash.txt` locale con l'asset `hash.txt`;
- se gli hash differiscono, elimina ricorsivamente `dataFolder`, la ricrea e scrive subito il nuovo `hash.txt`;
- successivamente copia gli asset che corrispondono a `gameProfiles/.*` e `resources/.*`.

**Rischio confermato nel codice:** il nuovo hash viene scritto prima che la copia di tutti gli asset sia terminata. Se la copia si interrompe o fallisce, un avvio successivo può trovare hash uguali e saltare la sincronizzazione, lasciando risorse incomplete.

Altri limiti osservati: l'esito di `deleteRecursively()` e della ricreazione della directory non viene controllato; gli errori di apertura/scrittura/copia dei singoli asset non sono gestiti localmente nel frammento. La cancellazione ricorsiva riguarda l'intera directory `dataFolder`, non soltanto i file riconosciuti dai pattern.

### Valutazione dell'ordine

È confermato che l'estrazione avviene dopo l'inizializzazione nativa. Questo fatto, da solo, non dimostra un difetto runtime: occorre distinguere le funzioni che leggono effettivamente `dataPath` durante l'inizializzazione da quelle che lo leggono più tardi, dopo il completamento di `onCreate()`.

**Stato:** la precedente ricerca senza risultati è superata e non deve essere usata come evidenza. Ordine e corpo di `saveDataFiles()` esaminati staticamente. Nessuna modifica al codice sorgente del repository.

## 7.39 `GraphicPack2::LoadAll()`: nessuna dipendenza da `dataPath` nel caricamento iniziale

**Evidenza esaminata:** `src/main.cpp` e `src/Cafe/GraphicPack/GraphicPack2.cpp` del branch Android della PR #1909 (`SSimco/Cemu`, head `7c2324eda01a3974ff89aa1e4ee7a594d278662a`).

### Catena verificata

In `CemuCommonInit()` (`src/main.cpp`), `GraphicPack2::LoadAll()` viene avviata in un task asincrono e il relativo future viene atteso con `futureInitGraphicPacks.wait()` prima che `CemuCommonInit()` termini.

L'implementazione di `GraphicPack2::LoadAll()`:

- costruisce la directory di base con `ActiveSettings::GetUserDataPath("graphicPacks")`;
- scorre ricorsivamente tale directory;
- individua i graphic pack tramite `rules.txt`;
- passa i pack trovati a `LoadGraphicPack()`.

Nel corpo esaminato non viene usato `ActiveSettings::GetDataPath()` e non viene letto alcun file sotto `dataPath/resources` o `dataPath/gameProfiles`.

### Conclusione

**Il caricamento iniziale dei graphic pack non risulta dipendere dagli asset estratti da `saveDataFiles()` in `dataPath`.** Il fatto che `refreshGraphicPacks()` preceda `saveDataFiles()` non costituisce, per questa specifica operazione, evidenza di una race sugli asset `resources/` o `gameProfiles/`.

Questo non esclude che altri componenti inizializzati in `CemuCommonInit()` o in `CafeSystem::Initialize()` leggano `dataPath`; la conclusione è limitata al corpo di `GraphicPack2::LoadAll()` e alla catena esaminata.

**Stato:** implementazione di `GraphicPack2::LoadAll()` verificata sul branch Android della PR. Nessuna modifica al repository.

## 7.40 Font condivisi: uso di `dataPath` e callsite ancora da collocare nella sequenza

**Evidenza esaminata:** `src/Cafe/CafeSystem.cpp` del branch Android della PR #1909, funzioni `LoadSharedData()` e `CafeSystem::Initialize()`.

### Comportamento verificato in `LoadSharedData()`

La tabella `shareddataDef` associa quattro font (`CafeCn.ttf`, `CafeKr.ttf`, `CafeStd.ttf`, `CafeTw.ttf`) a:

- un percorso sotto MLC (`sys/title/0005001b/10042400/content/...`);
- un percorso fallback sotto `dataPath/resources/sharedFonts/...`.

`LoadSharedData()` verifica per ciascun font se esiste almeno una delle due copie. Quando tutti i font risultano disponibili, prova ad aprirli prima dal MLC e, se l'apertura fallisce, usa `ActiveSettings::GetDataPath("resources/sharedFonts/...")`. Se i font non sono disponibili, tenta il fallback alternativo `GetUserDataPath("shareddata.bin")`.

Questa funzione costituisce quindi un **utilizzo reale di `dataPath`**, ma non dimostra da sola che venga invocata durante `initializeCemu()`.

### Distinzione rispetto a `CafeSystem::Initialize()`

Nel corpo esaminato di `CafeSystem::Initialize()` vengono inizializzati filesystem, memoria, PPC/RPL e moduli IOSU. In quel corpo non compare una chiamata diretta a `LoadSharedData()` né un accesso diretto a `GetDataPath()`.

Pertanto non è corretto attribuire automaticamente a `CafeSystem::Initialize()` la lettura anticipata dei font. Per chiudere il controllo temporale serve individuare il callsite effettivo di `LoadSharedData()` e collocarlo rispetto a `saveDataFiles()` e all'avvio di un titolo.

### Prossima verifica mirata

Eseguire sul repository locale:

```bash
git grep -n -E 'LoadSharedData|shareddataDef|shareddata\.bin' upstream/pr-1909 -- src
```

Poi acquisire il contesto del callsite di `LoadSharedData()` (non soltanto la definizione) per stabilire se la funzione venga chiamata:
- durante l'inizializzazione persistente di Cemu;
- durante l'avvio di un titolo;
- oppure in un'altra fase.

Se il callsite è raggiunto soltanto quando viene avviato un titolo, la sincronizzazione `saveDataFiles()` già completata durante `CemuApplication.onCreate()` precede quella lettura nel normale flusso di avvio dell'app. Se invece il callsite è nella fase di inizializzazione eseguita prima di `saveDataFiles()`, il rischio temporale resta concreto e va analizzato.

**Stato:** uso di `dataPath` per il fallback dei font confermato; `CafeSystem::Initialize()` non mostra una chiamata diretta a `LoadSharedData()`; callsite effettivo e collocazione temporale ancora da verificare. Nessuna modifica al codice sorgente del repository.


## 7.41 Callsite di `LoadSharedData()`: lettura durante la preparazione del titolo

**Aggiornamento del 14 novembre 2026.**

**Evidenza esaminata:** output `git grep` fornito dall'utente e contesto di `src/Cafe/CafeSystem.cpp` attorno alle righe 790–865 del branch Android della PR #1909.

### Occorrenze

La ricerca ha restituito:

- `src/Cafe/CafeSystem.cpp:301`: definizione di `uint32 LoadSharedData()`;
- `src/Cafe/CafeSystem.cpp:837`: chiamata a `LoadSharedData()`.

Il contesto della chiamata mostra:

```cpp
void SetupMemorySpace()
{
    memory_mapForCurrentTitle();
    LoadSharedData();
}
```

`SetupMemorySpace()` è invocata da `PrepareForegroundTitle(TitleId titleId)`, dopo il montaggio del titolo e il caricamento del game profile:

```cpp
PREPARE_STATUS_CODE r = LoadAndMountForegroundTitle(titleId);
if (r != PREPARE_STATUS_CODE::SUCCESS)
    return r;
gameProfile_load();
SetupMemorySpace();
PPCRecompiler_init();
```

### Significato per il percorso `dataPath`

`LoadSharedData()` verifica la presenza dei quattro font condivisi (`CafeCn.ttf`, `CafeKr.ttf`, `CafeStd.ttf`, `CafeTw.ttf`) nel MLC e sotto `dataPath/resources/sharedFonts`. Se i font non sono presenti nel MLC, il fallback `dataPath` è necessario per caricarli. Se i font sono già disponibili nel MLC, il codice prova prima quella copia.

### Conclusione temporale

Il callsite è nella preparazione della memoria di un titolo (`PrepareForegroundTitle()`), non nel corpo di `CafeSystem::Initialize()` e non nel caricamento iniziale dei Graphic Pack.

Nel flusso Android esaminato, `saveDataFiles()` viene eseguita dopo `initializeCemu()` durante `CemuApplication.onCreate()`. La preparazione del titolo avviene successivamente, quando viene avviato un gioco. Pertanto, nel normale flusso di avvio dell'app, l'estrazione di `resources/sharedFonts` precede la chiamata a `LoadSharedData()`.

**Esito:** il sospetto di una race tra `saveDataFiles()` e `LoadSharedData()` è chiuso per il normale flusso di avvio Android. Resta valido il distinto problema di integrità di `saveDataFiles()`: il nuovo `hash.txt` viene scritto prima del completamento della copia degli asset, quindi un'interruzione può lasciare risorse incomplete che non vengono ripristinate al successivo avvio se l'hash coincide.

**Limite della verifica:** la conclusione riguarda la sequenza ordinaria `onCreate()` → estrazione asset → successivo avvio del titolo. Non costituisce una garanzia per flussi anomali che avviassero un titolo prima del completamento di `saveDataFiles()` o per errori di estrazione.

Nessuna modifica al codice sorgente del repository.

## 7.42 Verifica checkout locale e presenza del porting Android nella PR #1909

**Aggiornamento del 14 novembre 2026 — controlli eseguiti dall'utente in Termux.**

### Stato Git del checkout locale

Output `git status --short --branch`, `git rev-parse HEAD` e `git submodule status`:

- branch attivo: `android-port`;
- commit `HEAD`: `6bfd5026eb23a118ba5bfa36af02e2ae7cf652d9`;
- file modificato localmente: `docs/TECHNICAL_AUDIT.md`;
- submodule inizializzati: `dependencies/ZArchive` (`d2c717730092c7bf8cbb033b12fd4001b7c4d932`) e `dependencies/xbyak_aarch64` (`904b8923457f3ec0d6f82ea2d6832a792851194d`);
- submodule non inizializzati: `Vulkan-Headers`, `cubeb`, `imgui`, `metal-cpp`, `vcpkg`.

La modifica locale a `docs/TECHNICAL_AUDIT.md` è stata ispezionata tramite `git diff`. Aggiunge la sezione **“Verifica JAXB — 2026-11-12”**: documenta OpenJDK 21.0.13 su Termux ARM64, l'assenza della classe `javax.xml.bind.DatatypeConverter` al test `Class.forName(...)` e una correzione proposta basata sulle API Java/Kotlin standard. La correzione è dichiarata non applicata e non testata. **La modifica locale deve essere preservata**; non è stata eseguita alcuna operazione di checkout, reset o ripristino.

### Submodule: configurazione e primi riferimenti

Il file `.gitmodules` dichiara sette submodule: `ZArchive`, `cubeb`, `vcpkg`, `Vulkan-Headers`, `imgui`, `metal-cpp`, `xbyak_aarch64`.

La ricerca `git grep` mostrata dall'utente ha individuato, tra gli altri, riferimenti a `vcpkg` nel `CMakeLists.txt` principale e nel workflow GitHub Actions; `ZArchive` e `cubeb` in `src/gui/wxgui/CMakeLists.txt`; sorgenti ImGui sotto `src/imgui`. Gli estratti ImGui includono backend OpenGL3 e Vulkan e menzionano anche il backend Metal. Questi risultati non bastano a stabilire quali submodule siano necessari alla configurazione Android: alcuni riferimenti appartengono esplicitamente alla GUI wxWidgets/desktop. La ricerca era inoltre limitata agli ultimi 100 risultati (`tail -n 100`), quindi l'assenza di un riferimento negli screenshot non è prova della sua assenza nel repository.

### Assenza dei file Android nel branch `android-port`

Controlli eseguiti dall'utente:

- `find src/android ...` ha restituito `No such file or directory`;
- la ricerca di directory `*android*`, `app` e `jni` entro tre livelli dalla root non ha mostrato risultati;
- `git ls-tree -r --name-only HEAD` filtrato per percorsi/nome file Android, Gradle e JNI non ha restituito risultati.

L'output `git branch -a` mostra `android-port` attivo, `main`, `upstream/main` e `upstream/pr-1909` (oltre al simbolico `upstream/HEAD -> upstream/main`).

### Presenza del porting Android in `upstream/pr-1909`

La successiva esecuzione di `git ls-tree -r --name-only upstream/pr-1909` filtrata per Android/Gradle/JNI ha restituito un ampio insieme di file, tra cui:

- `src/Common/android/` con `FdStream.h`, `FileStream_android.*`, `FileStream_saf.*`, `FilesystemAndroid.*`;
- `src/android/app/build.gradle.kts` e `src/android/app/src/main/AndroidManifest.xml`;
- `src/android/app/src/main/cpp/` con `CMakeLists.txt`, implementazioni JNI e callback Android;
- numerosi sorgenti Kotlin sotto `src/android/app/src/main/java/info/cemu/cemu/`.

Questo conferma che il riferimento Git `upstream/pr-1909` contiene il porting Android, mentre il `HEAD` locale `android-port` verificato non contiene file Android tracciati. Non è stato effettuato alcun merge, cherry-pick, checkout di file o importazione selettiva.

### Impatto sul piano di build

**La build Android non può ancora essere avviata dal checkout corrente**: mancano nel `HEAD` i file di progetto Android/Gradle e il codice Android/JNI che invece sono presenti in `upstream/pr-1909`. Il passo necessario è un confronto mirato tra `android-port`, `upstream/main` e `upstream/pr-1909`, seguito da un piano di importazione selettiva coerente con la strategia del progetto. Non va eseguito un merge indiscriminato della PR.

Prima di qualsiasi importazione occorre continuare a preservare la modifica locale `docs/TECHNICAL_AUDIT.md`. Nessun submodule è stato inizializzato durante questi controlli e nessun file sorgente è stato modificato.

**Stato:** divergenza strutturale del checkout confermata; porting Android presente nel ref `upstream/pr-1909`; importazione selettiva non ancora iniziata; build non ancora eseguita.

## 7.44 Inventario dei file modificati dalla PR #1909 fuori da `src/android`

**Aggiornamento del 14 novembre 2026.**

**Evidenza esaminata:** output fornito dall'utente del comando eseguito nella root del repository:

```bash
git diff --name-only android-port...upstream/pr-1909 -- . ':!src/android' | sort
```

Il comando ha restituito **107 percorsi**. L'esclusione `':!src/android'` elimina il sottoalbero `src/android`, ma non esclude gli altri percorsi Android-specifici collocati altrove nel repository.

### Interpretazione corretta del confronto

La sintassi a tre punti (`android-port...upstream/pr-1909`) confronta il tree del riferimento `upstream/pr-1909` con la base di merge comune tra i due riferimenti. L'elenco identifica quindi i percorsi modificati sul lato `upstream/pr-1909` rispetto a quella base, limitatamente al pathspec indicato.

**Non** è un elenco di file modificati nel working tree locale e, da solo, non dimostra che tutti questi file siano già stati importati nel branch `android-port`. Non dimostra neppure che ogni modifica sia necessaria per Android: il contenuto dei singoli diff deve essere classificato.

### Distribuzione per area

- **Root, CI, documentazione, submodule e manifest:** `.github/actions/...`, `.github/workflows/...`, `.gitmodules`, `BUILD.md`, `CMakeLists.txt`, `CODING_STYLE.md`, `vcpkg.json`.
- **Dipendenze:** `dependencies/ZArchive`, `dependencies/ih264d/decoder/ih264d_dpb_mgr.c`, `dependencies/libadrenotools`, `dependencies/stb/...`.
- **Core Cafe e filesystem:** account, CMake Cafe, `CafeSystem`, header filesystem FST/WUD/FSC, backend `fscDeviceAndroidSAF`, profili di gioco, renderer Latte/Metal/Vulkan, thread coreinit, swkbd e title list.
- **Common e filesystem multipiattaforma:** `src/Common/...`, inclusi i sette file sotto `src/Common/android/`, oltre ai backend Unix/Windows e alle modifiche comuni.
- **Audio e configurazione:** `src/audio/...`, `src/config/...`.
- **GUI:** `src/gui/CMakeLists.txt`, `src/gui/androidgui/...`, interfacce WindowSystem e file wxWidgets.
- **Input:** CMake input, controller Android, Device, DirectInput, InputManager e controller emulati.
- **Runtime e utility:** `src/main.cpp`, CMake util, Fiber per le varie piattaforme e `ZArchiveHelpers.h`.

### Evidenza Android-specifica presente fuori da `src/android`

L'elenco contiene esplicitamente percorsi Android che restano inclusi nel confronto:

- `src/Common/android/` — `FdStream.h`, `FileStream_android.cpp/.h`, `FileStream_saf.cpp/.h`, `FilesystemAndroid.cpp/.h`;
- `src/Cafe/Filesystem/fscDeviceAndroidSAF.cpp/.h`;
- `src/gui/androidgui/AndroidWindowSystem.cpp` e `src/gui/androidgui/CMakeLists.txt`;
- `src/input/api/Android/` — controller, provider e `ControllerManager`.

Questi percorsi sono parte del porting nativo Android anche se non si trovano sotto `src/android`. Pertanto il filtro usato non equivale a “escludi tutte le modifiche Android”: esclude soltanto il progetto/app Android sotto `src/android`.

### Inventario integrale restituito

```text
.github/actions/set-up-vcpkg-binary-cache-action/action.yml
.github/workflows/build.yml
.github/workflows/deploy_release.yml
.gitmodules
BUILD.md
CMakeLists.txt
CODING_STYLE.md
dependencies/ZArchive
dependencies/ih264d/decoder/ih264d_dpb_mgr.c
dependencies/libadrenotools
dependencies/stb/CMakeLists.txt
dependencies/stb/include/stb_image.h
src/CMakeLists.txt
src/Cafe/Account/Account.cpp
src/Cafe/Account/Account.h
src/Cafe/CMakeLists.txt
src/Cafe/CafeSystem.cpp
src/Cafe/CafeSystem.h
src/Cafe/Filesystem/FST/FST.h
src/Cafe/Filesystem/WUD/wud.h
src/Cafe/Filesystem/fsc.h
src/Cafe/Filesystem/fscDeviceAndroidSAF.cpp
src/Cafe/Filesystem/fscDeviceAndroidSAF.h
src/Cafe/Filesystem/fscDeviceHostFS.h
src/Cafe/GameProfile/GameProfile.cpp
src/Cafe/GameProfile/GameProfile.h
src/Cafe/HW/Latte/Core/LatteDraw.h
src/Cafe/HW/Latte/Core/LatteRenderTarget.cpp
src/Cafe/HW/Latte/Core/LatteTextureLoader.h
src/Cafe/HW/Latte/Renderer/Metal/MetalLayerHandle.cpp
src/Cafe/HW/Latte/Renderer/Vulkan/SwapchainInfoVk.cpp
src/Cafe/HW/Latte/Renderer/Vulkan/SwapchainInfoVk.h
src/Cafe/HW/Latte/Renderer/Vulkan/VsyncDriver.cpp
src/Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.cpp
src/Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h
src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp
src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h
src/Cafe/OS/libs/coreinit/coreinit_Thread.cpp
src/Cafe/OS/libs/coreinit/coreinit_Thread.h
src/Cafe/OS/libs/swkbd/swkbd.cpp
src/Cafe/OS/libs/swkbd/swkbd.h
src/Cafe/TitleList/TitleInfo.cpp
src/Cafe/TitleList/TitleList.cpp
src/Cemu/FileCache/FileCache.h
src/Common/CMakeLists.txt
src/Common/ExceptionHandler/ExceptionHandler_posix.cpp
src/Common/FileStream.h
src/Common/android/FdStream.h
src/Common/android/FileStream_android.cpp
src/Common/android/FileStream_android.h
src/Common/android/FileStream_saf.cpp
src/Common/android/FileStream_saf.h
src/Common/android/FilesystemAndroid.cpp
src/Common/android/FilesystemAndroid.h
src/Common/cpu_features.cpp
src/Common/cpu_features.h
src/Common/platform.h
src/Common/precompiled.h
src/Common/unix/FileStream_unix.cpp
src/Common/unix/FileStream_unix.h
src/Common/windows/FileStream_win32.h
src/audio/DirectSoundAPI.cpp
src/audio/IAudioAPI.cpp
src/audio/IAudioAPI.h
src/config/ActiveSettings.cpp
src/config/ActiveSettings.h
src/config/CemuConfig.cpp
src/config/CemuConfig.h
src/gui/CMakeLists.txt
src/gui/androidgui/AndroidWindowSystem.cpp
src/gui/androidgui/CMakeLists.txt
src/gui/interface/WindowSystem.h
src/gui/wxgui/GameProfileWindow.cpp
src/gui/wxgui/GeneralSettings2.cpp
src/gui/wxgui/MainWindow.cpp
src/gui/wxgui/PadViewFrame.cpp
src/gui/wxgui/helpers/wxHelpers.cpp
src/imgui/imgui_extension.cpp
src/input/CMakeLists.txt
src/input/ControllerFactory.cpp
src/input/InputManager.cpp
src/input/InputManager.h
src/input/api/Android/AndroidController.cpp
src/input/api/Android/AndroidController.h
src/input/api/Android/AndroidControllerProvider.cpp
src/input/api/Android/AndroidControllerProvider.h
src/input/api/Android/ControllerManager.cpp
src/input/api/Android/ControllerManager.h
src/input/api/Device/DeviceController.cpp
src/input/api/Device/DeviceController.h
src/input/api/Device/DeviceControllerProvider.cpp
src/input/api/Device/DeviceControllerProvider.h
src/input/api/DirectInput/DirectInputController.cpp
src/input/api/InputAPI.h
src/input/emulated/EmulatedController.cpp
src/input/emulated/EmulatedController.h
src/main.cpp
src/util/CMakeLists.txt
src/util/Fiber/Fiber.h
src/util/Fiber/FiberFContext.cpp
src/util/Fiber/FiberFContext.h
src/util/Fiber/FiberUContext.cpp
src/util/Fiber/FiberUContext.h
src/util/Fiber/FiberWin.cpp
src/util/Fiber/FiberWin.h
src/util/helpers/ZArchiveHelpers.h
vcpkg.json
```

### Valutazione e passo successivo

L'output allarga in modo sostanziale l'area dell'audit: oltre al modulo Android sotto `src/android`, la PR modifica numerosi componenti condivisi del core Cemu, del filesystem, della GUI, dell'input, del renderer e della configurazione delle dipendenze.

Non è ancora possibile classificare i 107 percorsi come modifiche obbligatorie, opzionali o desktop-only senza esaminare i rispettivi diff. In particolare, occorre distinguere:
1. modifiche necessarie per compilare/eseguire Android;
2. modifiche Android-specifiche collocate in directory condivise;
3. modifiche comuni che potrebbero essere regressioni o variazioni di comportamento anche su desktop;
4. modifiche desktop-only che la PR ha toccato incidentalmente o per compatibilità.

**Prossima verifica consigliata:** procedere per gruppi, iniziando dai file di build e dipendenze (`CMakeLists.txt`, `src/CMakeLists.txt`, `src/Common/CMakeLists.txt`, `src/Cafe/CMakeLists.txt`, `src/gui/CMakeLists.txt`, `src/input/CMakeLists.txt`, `src/util/CMakeLists.txt`, `vcpkg.json` e `.gitmodules`), quindi passare ai diff del core e dei backend. Per ogni gruppo registrare motivazione, dipendenze, impatto desktop, impatto Android e decisione di porting, senza importare o modificare codice.

**Stato:** inventario di 107 percorsi acquisito; classificazione funzionale dei singoli diff ancora da eseguire. Nessuna modifica al codice sorgente o al repository locale effettuata.


## 7.45 — Diff CMake e submodule: parte finale dell'output — 2026-11-14

**Fonte:** screenshot fornito in chat (`1000093260.jpg`), dichiarato dall'utente come parte finale dell'output del comando di confronto dei file CMake e delle dipendenze. La schermata mostra la coda del diff di `.gitmodules` e l'inizio/parte intermedia del diff di `CMakeLists.txt`; il contenuto si interrompe durante il ramo `VCPKG_TARGET_ANDROID`.

### `.gitmodules`

È visibile l'aggiunta del submodule:

- nome: `dependencies/libadrenotools`
- path: `dependencies/libadrenotools`
- URL: `https://github.com/bylaws/libadrenotools`

La schermata mostra anche il contesto del submodule `dependencies/xbyak_aarch64`, con URL `https://github.com/fujitsu/xbyak_aarch64`; non è visibile in questa porzione una modifica a quel submodule.

### `CMakeLists.txt` principale

Modifiche leggibili:

1. `cmake_minimum_required` passa da `3.21.1` a `3.25`. Questo innalza il requisito minimo degli strumenti CMake per configurare il progetto.
2. Il ramo precedente `if(UNIX AND NOT APPLE)` viene ristretto a `if(UNIX AND NOT APPLE AND NOT VCPKG_TARGET_ANDROID)`, evitando di trattare Android come Linux generico per la selezione degli overlay.
3. Il precedente ramo generico `else()` viene sostituito da `elseif(WIN32)`, con overlay Windows esplicito (`dependencies/vcpkg_overlay_ports_win`).
4. È aggiunto un ramo `elseif(VCPKG_TARGET_ANDROID)` che imposta `VCPKG_OVERLAY_PORTS` includendo un percorso Android. La schermata si interrompe prima di rendere leggibile l'intera espressione e l'eventuale chiusura dei rami condizionali: il valore completo non viene quindi ricostruito per supposizione.

### Implicazioni per il porting Android

- Il requisito CMake 3.25 è un prerequisito concreto da verificare nell'ambiente di build Android; non è dimostrato da questa sola schermata quale versione sia installata nell'ambiente target.
- L'aggiunta di `libadrenotools` indica una nuova dipendenza Git esterna associata alla revisione upstream. Va verificato nei successivi diff dove viene consumata e se è richiesta dal backend Android/Vulkan o da altri target.
- La separazione esplicita di `VCPKG_TARGET_ANDROID` dagli altri sistemi operativi è parte della configurazione del porting Android. Occorre acquisire il resto del blocco per identificare con precisione overlay e ordine di composizione.
- La modifica `elseif(WIN32)` rende esplicito il ramo Windows; non va interpretata come rimozione del supporto ad altri sistemi senza esaminare il seguito completo del file.

### Limiti della verifica

Questa è una porzione fotografata dell'output, non il diff testuale completo. Non consente ancora di verificare `src/CMakeLists.txt`, i CMakeLists dei singoli componenti, `vcpkg.json` né l'intero ramo Android del file principale. La riga `src/input/api/Device/DeviceControllerProvider.cpp` visibile in cima è soltanto la coda del contenuto precedente nella schermata e non fornisce da sola dettagli sufficienti per valutarne il diff.

**Stato:** acquisiti i cambiamenti visibili in `.gitmodules` e `CMakeLists.txt`; analisi CMake/dipendenze ancora parziale. Nessuna modifica al repository o al codice sorgente.


## 7.45 — Integrazione schermate del diff CMake e conferma inventario — 2026-11-14

Sono state esaminate le quattro schermate inviate dopo la sezione 7.44. Le immagini si sovrappongono parzialmente e mostrano due gruppi di contenuti: l'inventario dei percorsi modificati e l'inizio del diff di `CMakeLists.txt` insieme alla modifica di `.gitmodules`.

### Modifiche CMake visibili

Nel diff di `CMakeLists.txt` sono leggibili le seguenti modifiche:

- `cmake_minimum_required` passa da `3.21.1` a `3.25`.
- La selezione dell'overlay vcpkg per Unix non Apple viene limitata con `AND NOT VCPKG_TARGET_ANDROID`.
- Il ramo Apple continua a selezionare `dependencies/vcpkg_overlay_ports_mac`.
- Viene introdotto un ramo esplicito `elseif(WIN32)` per `dependencies/vcpkg_overlay_ports_win`.
- Viene introdotto un ramo `elseif(VCPKG_TARGET_ANDROID)` che imposta `VCPKG_OVERLAY_PORTS` a `dependencies/vcpkg_overlay_ports`, senza aggiungere in quel ramo un overlay specifico per Linux, macOS o Windows.
- Dopo il blocco condizionale, nelle schermate è visibile la rimozione dell'impostazione esplicita di `CMAKE_TOOLCHAIN_FILE` verso `dependencies/vcpkg/scripts/buildsystems/vcpkg.cmake`, con cache type `STRING` e descrizione `"Vcpkg toolchain file"`.

La schermata termina durante il diff relativo a `CMAKE_TOOLCHAIN_FILE`: non è visibile il seguito del file né il contesto sufficiente per stabilire se la toolchain venga impostata altrove, demandata al preset/configurazione Android o eliminata completamente. Non si trae quindi una conclusione sul comportamento finale di configurazione vcpkg.

### Submodule

La modifica a `.gitmodules` aggiunge:

- nome: `dependencies/libadrenotools`
- path: `dependencies/libadrenotools`
- URL: `https://github.com/bylaws/libadrenotools`

La presenza del submodule nel diff non dimostra da sola che sia già inizializzato nel checkout locale `android-port`; lo stato locale dei submodule resta quello già registrato nelle verifiche precedenti, salvo un controllo successivo.

### Inventario dei percorsi

Le schermate ripropongono parti dell'inventario dei 107 percorsi già documentato nella sezione 7.44. Non vengono conteggiati come nuovi percorsi e non modificano il totale già registrato.

### Stato dell'analisi

Queste schermate forniscono dettagli aggiuntivi sul ramo Android della configurazione vcpkg e sulla toolchain. Non è stata eseguita alcuna modifica al repository, né merge, cherry-pick o inizializzazione di submodule. Restano da leggere il seguito completo di `CMakeLists.txt` e i diff dei CMakeLists specifici dei componenti e delle dipendenze prima di definire il piano di build.


## 7.46 — Diff `src/CMakeLists.txt`: target Android e Vulkan — 2026-11-14

Sono state esaminate le tre schermate aggiuntive. La prima ripete in parte il diff di `CMakeLists.txt` e `.gitmodules` già registrato nella sezione 7.45; non viene conteggiata come nuova modifica. Le altre due mostrano il diff di `src/CMakeLists.txt`.

### Definizioni Vulkan per Android

Nel ramo `elseif(ANDROID)` viene aggiunta una condizione:

- se `ENABLE_VULKAN` è attivo, viene eseguito `add_compile_definitions(VK_USE_PLATFORM_ANDROID_KHR)`.

Il ramo non Android mostrato subito dopo continua a contenere le definizioni di piattaforma Vulkan legacy per Xlib e XCB. La schermata mostra anche il commento relativo al supporto legacy delle superfici XLIB.

### Struttura del target `CemuBin`

Il precedente `add_executable(CemuBin main.cpp mainLLE.cpp)` viene rimosso dalla posizione comune e sostituito da una selezione condizionale:

- `if(ANDROID)`: `add_library(CemuBin STATIC main.cpp mainLLE.cpp)`;
- il seguito del ramo `else()` non è completamente visibile nelle immagini ricevute.

Quindi è confermato che il target Android `CemuBin` viene costruito come libreria statica e che i due sorgenti elencati sono mantenuti. Non si conclude, in assenza della parte finale del diff, quale sia l'intera definizione del target non Android.

### Header precompilato e impostazioni MSVC

La chiamata `cemu_use_precompiled_header(CemuBin)` viene spostata dopo il blocco condizionale, rendendola comune ai rami Android e non Android.

Nelle schermate è inoltre visibile la rimozione del workaround specifico per `MSVC` versione `1940`, relativo alla dimensione dei file ILK generati da Visual Studio 17.10 e alla proprietà `LINK_FLAGS "/INCREMENTAL:NO"`. La schermata mostra la rimozione del blocco, ma non fornisce ulteriori motivazioni oltre al commento presente nel diff.

### Subdirectory

Nel contesto visibile prima del blocco del target sono presenti le chiamate:

- `add_subdirectory(input)`
- `add_subdirectory(audio)`
- `add_subdirectory(util)`
- `add_subdirectory(imgui)`
- `add_subdirectory(resource)`

Questa porzione non mostra modifiche a tali chiamate; non viene quindi attribuita loro una variazione specifica.

### Limiti delle schermate

Le immagini terminano durante il ramo `else()` della selezione del target. Per completare l'analisi servono le righe successive di `src/CMakeLists.txt`, in particolare la chiusura del ramo e le eventuali proprietà del target. Non è ancora possibile ricostruire da queste sole immagini l'intero grafo di linking Android o la relazione finale fra `CemuBin` statico e libreria JNI.

Nessuna modifica è stata applicata al repository; nessun merge, cherry-pick o inizializzazione di submodule è stato eseguito.


## 7.47 — Chiusura completa del diff `src/CMakeLists.txt` — 2026-11-14

L'utente ha fornito il testo completo del diff di `src/CMakeLists.txt`, completando le schermate della sezione 7.46.

### Selezione delle definizioni Vulkan

Il diff inserisce un ramo `elseif(ANDROID)` dopo il ramo precedente che definisce `_XOPEN_SOURCE`:

- se `ENABLE_VULKAN` è attivo, aggiunge `VK_USE_PLATFORM_ANDROID_KHR`;
- il successivo ramo `else()` aggiunge le definizioni Vulkan per XLIB e XCB.

Il testo fornito non include tutto il contesto iniziale dell'`if` esterno; la gerarchia completa delle condizioni di piattaforma non viene ricostruita oltre quanto visibile.

### Separazione del target Android e desktop

Il blocco viene riorganizzato in `if(ANDROID) ... else() ... endif()`.

**Ramo Android:**
- crea `CemuBin` come libreria statica con `add_library(CemuBin STATIC ...)`;
- include `main.cpp` e `mainLLE.cpp`;
- applica `cemu_use_precompiled_header(CemuBin)`;
- aggiunge `android/app/src/main/cpp` con `add_subdirectory(android/app/src/main/cpp)`.

**Ramo non Android:**
- crea `CemuBin` come eseguibile con `add_executable(CemuBin main.cpp mainLLE.cpp)`;
- applica `cemu_use_precompiled_header(CemuBin)`;
- conserva il workaround MSVC 1940 con `LINK_FLAGS "/INCREMENTAL:NO"`;
- su Windows aggiunge `resource/cemu.rc` e `../dist/windows/cemu.manifest`;
- conserva la configurazione Xcode `ENABLE_DEBUG_DYLIB[variant=Debug]` per Apple con generatore Xcode;
- calcola `CURRENT_YEAR` con `string(TIMESTAMP ... UTC)`;
- imposta `MSVC_RUNTIME_LIBRARY`, `WIN32_EXECUTABLE` e `OUTPUT_NAME` nel ramo non Android.

Le proprietà specifiche dell'eseguibile desktop e il workaround MSVC non vengono quindi applicati nel ramo Android. La chiamata al precompiled header è presente in entrambi i rami.

### Implicazioni per il grafo di build

Android non produce `CemuBin` come eseguibile autonomo: produce una libreria statica e aggiunge il sottoprogetto JNI/C++ in `src/android/app/src/main/cpp`. Il diff, da solo, non mostra ancora:
- quali sorgenti e librerie vengano aggiunti dal sottoprogetto JNI;
- come il target JNI colleghi `CemuBin`;
- il nome finale della shared library caricata dall'app Android;
- eventuali dipendenze o opzioni di linking introdotte nei CMake dei componenti.

Questi aspetti vanno verificati leggendo il CMake del sottoprogetto `src/android/app/src/main/cpp` e i CMake dei componenti interessati. Non vanno dedotti dal solo `add_subdirectory`.

### Stato

La parte finale del diff `src/CMakeLists.txt` è acquisita per intero. Non occorre ripetere il comando che la produceva. Nessuna modifica è stata applicata al repository; nessun merge, cherry-pick o inizializzazione di submodule è stato eseguito.


## 7.48 — CMake JNI Android: definizione della libreria condivisa `CemuAndroid` — 2026-11-14

Esaminata la schermata del nuovo file `src/android/app/src/main/cpp/CMakeLists.txt`. Il diff mostra un file nuovo (modalità `100644`, indice iniziale `00000000` e indice finale `f4ddc11d`) con il seguente contenuto:

```cmake
add_library(CemuAndroid SHARED
    AndroidFilesystemCallbacks.cpp
    AndroidInputHelpers.cpp
    CompressTitleCallbacks.cpp
    GameTitleLoader.cpp
    Image.cpp
    JNIUtils.cpp
    NativeAccount.cpp
    NativeActiveSettings.cpp
    NativeEmulatedUSBDevices.cpp
    NativeEmulation.cpp
    NativeGameTitles.cpp
    NativeGraphicPacks.cpp
    NativeInput.cpp
    NativeLib.cpp
    NativeLocalization.cpp
    NativeLogging.cpp
    NativeSettings.cpp
    NativeSwkbd.cpp
    WuaConverter.cpp
)
```

### Risultato certo

- Il sottoprogetto JNI/C++ definisce una libreria condivisa denominata `CemuAndroid`.
- La libreria include 19 file sorgente `.cpp`, che implementano callback filesystem/input, caricamento titoli, immagini, utility JNI e varie interfacce native (`Native*`), oltre a `WuaConverter.cpp`.
- Questo completa il quadro dei due target: `CemuBin` viene creato come libreria statica nel ramo Android di `src/CMakeLists.txt`, mentre `CemuAndroid` viene creato come libreria condivisa nel sottoprogetto JNI.

### Cosa non è ancora dimostrato

Nella schermata non compare alcuna chiamata `target_link_libraries`, né sono visibili direttive `include_directories`, `target_include_directories`, opzioni di compilazione o collegamenti espliciti verso `CemuBin` e le altre dipendenze. Non si deve quindi affermare ancora che `CemuAndroid` colleghi effettivamente `CemuBin`: la relazione va verificata nei successivi CMake o nel resto del progetto.

La schermata mostra il contenuto del nuovo file e termina dopo la chiusura di `add_library`; non si presume l'esistenza di righe ulteriori non mostrate.

### Stato

La definizione del target condiviso JNI `CemuAndroid` è acquisita. Non occorre ripetere il diff di questo file. Nessuna modifica è stata applicata al repository; nessun merge, cherry-pick o inizializzazione di submodule è stato eseguito.


## 7.49 — Avvio analisi `AndroidFilesystemCallbacks.cpp` — 2026-11-15

Esaminata la schermata del nuovo file `src/android/app/src/main/cpp/AndroidFilesystemCallbacks.cpp` (diff da `/dev/null`, file nuovo). La schermata mostra l'inizio dell'implementazione della classe `AndroidFilesystemCallbacks`.

### Inizializzazione dei riferimenti JNI

Nel costruttore:
- recupera l'ambiente JNI corrente con `JNIUtils::GetEnv()`;
- ottiene una classe Java tramite `JNIUtils::Scopedjclass` usando il nome `info/cemu/cemu/nativeinterface/NativeFiles`;
- risolve e memorizza gli ID dei metodi statici Java:
  - `openContentUri(String): int`;
  - `listFiles(String): String[]`;
  - `isDirectory(String): boolean`;
  - `isFile(String): boolean`;
  - `exists(String): boolean`.

Quindi la classe C++ usa un'interfaccia Java statica `NativeFiles` per operazioni su URI/percorso e interrogazioni del filesystem. La schermata non mostra il codice Java corrispondente, perciò non è ancora possibile verificare come questi metodi trattino URI SAF, permessi, errori o risultati null.

### Metodo `OpenContentUri`

La porzione visibile mostra che:
- inizializza il descrittore restituito a `-1`;
- esegue la chiamata Java all'interno di `JNIUtils::FiberSafeJNICALL`;
- converte il `std::filesystem::path` in `jstring` tramite `JNIUtils::ToJString`;
- invoca `NativeFiles.openContentUri` con `CallStaticIntMethod`;
- elimina il riferimento locale della stringa con `DeleteLocalRef`;
- restituisce il descrittore ottenuto.

Il valore iniziale `-1` costituisce il valore restituito se la chiamata non assegna un descrittore. La schermata non consente invece di stabilire se siano gestite eccezioni Java, eccezioni JNI pendenti o errori di conversione.

### Metodo `ListFiles`

È visibile l'inizio di `ListFiles(const std::filesystem::path& uri)`: crea un `std::vector<std::filesystem::path>` e avvia una chiamata `FiberSafeJNICALL`, convertendo l'URI in stringa Java. Il corpo è tagliato nella schermata; non vengono dedotti conversione dell'array, gestione degli elementi null o comportamento in caso di errore.

### Prossimo controllo necessario

Per completare questa verifica servono le righe successive di `AndroidFilesystemCallbacks.cpp` fino alla fine del file e, in seguito, la dichiarazione in `AndroidFilesystemCallbacks.h` e l'implementazione Java di `NativeFiles`. Il confronto tra i contratti JNI C++ e Java è necessario per verificare correttamente la gestione dei percorsi SAF.

### Stato

È acquisito soltanto l'inizio dell'implementazione C++. Nessuna modifica è stata applicata al repository; nessun merge, cherry-pick o inizializzazione di submodule è stato eseguito.


## 7.50 — Ulteriore diff CMake Android e callback filesystem JNI — 2026-11-15

**Evidenza esaminata:** output testuale di `git diff --no-ext-diff --unified=5 android-port...upstream/pr-1909 -- src/CMakeLists.txt`, limitato agli ultimi 100 record, fornito dall'utente. L'output contiene anche l'inizio del nuovo `src/android/app/src/main/cpp/CMakeLists.txt` e di `AndroidFilesystemCallbacks.cpp`, già parzialmente esaminati nelle sezioni 7.48 e 7.49.

### `src/CMakeLists.txt`: definizione Vulkan Android

Nel testo fornito compare l'aggiunta di:

```cmake
elseif(ANDROID)
    if(ENABLE_VULKAN)
        add_compile_definitions(VK_USE_PLATFORM_ANDROID_KHR)
    endif()
```

Il testo visualizzato colloca questa aggiunta dopo un blocco che termina con `endif()` e mostra un precedente `else()` associato alla definizione `_XOPEN_SOURCE`. Se questa sequenza rispecchia esattamente il file, `elseif(ANDROID)` risulterebbe collocato dopo la chiusura dell'istruzione condizionale e sarebbe sintatticamente invalido. Tuttavia, l'estratto è stato copiato da un output terminale con formattazione e righe duplicate/disallineate; prima di registrare un errore CMake come confermato occorre acquisire il blocco completo del file, con numeri di riga e contesto non troncato.

La definizione `VK_USE_PLATFORM_ANDROID_KHR` è coerente con la selezione della superficie Vulkan Android, ma il suo corretto posizionamento nel ramo di piattaforma resta da verificare.

### `src/CMakeLists.txt`: target `CemuBin`

Il testo ribadisce la riorganizzazione già descritta nelle sezioni 7.46–7.47:

- su Android `CemuBin` è una libreria `STATIC` composta da `main.cpp` e `mainLLE.cpp`;
- viene applicato `cemu_use_precompiled_header(CemuBin)`;
- viene aggiunto `src/android/app/src/main/cpp` tramite `add_subdirectory`;
- nel ramo non Android viene mantenuto il target eseguibile e sono collocate le impostazioni desktop.

Questa parte conferma quanto già registrato e non costituisce una nuova modifica distinta rispetto alla sezione 7.47.

### Nuovo `src/android/app/src/main/cpp/CMakeLists.txt`

L'estratto ripropone la creazione di `CemuAndroid` come libreria condivisa (`SHARED`) e l'elenco dei sorgenti JNI. Il testo disponibile è duplicato e si interrompe dopo le prime voci dell'elenco: non aggiunge informazioni sufficienti per stabilire le direttive di linking o le dipendenze finali del target. Resta valida la limitazione registrata nella sezione 7.48.

### `AndroidFilesystemCallbacks.cpp`

L'estratto ripropone il costruttore e l'inizio dei metodi `OpenContentUri()` e `ListFiles()` già descritti nella sezione 7.49. Non è un completamento del file: il corpo di `ListFiles()` e i metodi successivi non sono integralmente visibili nel testo ricevuto.

### Esito e prossimo controllo

- Non emerge da questo estratto una nuova conclusione sul linking tra `CemuAndroid` e `CemuBin`.
- La possibile anomalia di struttura `if/elseif/else/endif` nel blocco Vulkan deve essere verificata sul contenuto completo di `src/CMakeLists.txt`, senza affidarsi alla trascrizione troncata.
- Per chiudere il controllo servono il blocco completo delle condizioni di piattaforma in `src/CMakeLists.txt` e il contenuto completo dei due file CMake Android, in particolare tutte le chiamate `target_link_libraries`, `target_include_directories` e le opzioni di compilazione.

**Stato:** nuovo estratto acquisito; parte sostanziale già documentata nelle sezioni precedenti. Possibile problema sintattico CMake segnalato come non confermato. Nessuna modifica al codice applicativo, merge, cherry-pick o inizializzazione di submodule effettuati.


## 7.51 — Verifica presenza file Android e preparazione archivio comparativo — 2026-11-15

### Evidenza verificata tramite Git

Nel repository `~/CemuFixerAPC` sono state controllate le revisioni `android-port` e `upstream/pr-1909` con `git cat-file -e <ref>:<path>`.

| Percorso | `android-port` | `upstream/pr-1909` |
|---|---|---|
| `CMakeLists.txt` | Presente | Presente |
| `.gitmodules` | Presente | Presente |
| `src/CMakeLists.txt` | Presente | Presente |
| `src/android/app/src/main/cpp/CMakeLists.txt` | Assente | Presente |
| `src/android/app/src/main/cpp/AndroidFilesystemCallbacks.cpp` | Assente | Presente |

### Archivio creato

L'utente ha creato `Download/CemuFixer_Audit_Files.zip` con i file disponibili in ciascuna revisione. L'output `unzip -l` riportato in chat conferma la presenza nell'archivio di:
- per `android-port`: `CMakeLists.txt`, `.gitmodules`, `src/CMakeLists.txt`;
- per `upstream/pr-1909`: gli stessi tre file più `src/android/app/src/main/cpp/CMakeLists.txt` e `src/android/app/src/main/cpp/AndroidFilesystemCallbacks.cpp`.

Dimensione riportata: 17K; dimensione totale dei file elencati: 39.112 byte.

### Valutazione e limiti

La differenza di presenza dei due file Android è un fatto verificato sulle due revisioni Git. Da sola non dimostra che le relative funzionalità siano state eliminate: potrebbero essere state spostate, integrate altrove o sostituite. È necessario esaminare il contenuto dei file upstream e cercare eventuali equivalenti nel branch `android-port` prima di formulare una valutazione funzionale.

L'archivio è stato creato correttamente secondo l'elenco `unzip -l` fornito dall'utente, ma il file ZIP non è ancora stato allegato alla conversazione e quindi il suo contenuto non è stato ispezionato direttamente.

### Prossimo controllo

Ricevere l'allegato `CemuFixer_Audit_Files.zip`, esaminare i cinque file e confrontare in particolare i due file presenti soltanto in `upstream/pr-1909` con le implementazioni eventualmente equivalenti in `android-port`.


## 7.52 — Ispezione diretta dell'archivio comparativo e completamento del controllo CMake/JNI — 2026-11-15

**Evidenza esaminata:** contenuto effettivo dell'allegato `CemuFixer_Audit_Files.zip`. L'archivio contiene cinque file di sorgente/configurazione:
- `android-port/CMakeLists.txt`
- `android-port/.gitmodules`
- `android-port/src/CMakeLists.txt`
- `upstream/pr-1909/CMakeLists.txt`
- `upstream/pr-1909/.gitmodules`
- `upstream/pr-1909/src/CMakeLists.txt`
- `upstream/pr-1909/src/android/app/src/main/cpp/CMakeLists.txt`
- `upstream/pr-1909/src/android/app/src/main/cpp/AndroidFilesystemCallbacks.cpp`

Il conteggio effettivo è di otto file non-directory. Non sono inclusi `AndroidFilesystemCallbacks.h`, `NativeFiles.kt` o altri file JNI Kotlin.

### 7.52.1 CMake root: integrazione Android/vcpkg

Nel `CMakeLists.txt` upstream sono presenti modifiche assenti dal root `android-port`:

- selezione di overlay vcpkg specifici per Android tramite `VCPKG_TARGET_ANDROID`;
- selezione del triplet in base a `ANDROID_ABI`: `arm64-v8a` → `arm64-android`, `x86_64` → `x64-android`, altre ABI → errore fatale;
- impostazione di `ANDROID_NDK_HOME` e `VCPKG_CHAINLOAD_TOOLCHAIN_FILE` verso il toolchain NDK;
- esclusione del ramo overlay Linux per il caso Android;
- `find_package(Boost COMPONENTS context iostreams REQUIRED)` aggiuntivo quando `ANDROID` è vero;
- esclusione di Android dai blocchi Linux/Unix che cercano X11, GTK, Wayland e BlueZ e dal blocco Feral GameMode;
- aggiunta di `libadrenotools` su Android quando `CEMU_ARCHITECTURE` corrisponde a `aarch64` o `AARCH64`;
- aggiunta di `stb` su Android.

Queste differenze sono confermate dal contenuto dei due file. La loro correttezza operativa non è dimostrata dal solo confronto statico: restano necessarie configurazione CMake e build NDK.

### 7.52.2 `.gitmodules`

Il `.gitmodules` upstream aggiunge il submodule:

- `dependencies/libadrenotools` → `https://github.com/bylaws/libadrenotools`

Il riferimento non è presente nel `.gitmodules` di `android-port`. Questo è coerente con il nuovo `add_subdirectory(dependencies/libadrenotools ...)` nel root CMake upstream. L'archivio non contiene però i metadati Git dei submodule né il loro contenuto: non consente di verificare il commit effettivamente fissato o la disponibilità del checkout.

### 7.52.3 `src/CMakeLists.txt`: condizione Vulkan Android

Il file upstream contiene la struttura:

```cmake
elseif(UNIX)
    if(APPLE)
        ...
    elseif(ANDROID)
        if(ENABLE_VULKAN)
            add_compile_definitions(VK_USE_PLATFORM_ANDROID_KHR)
        endif()
    else()
        ...
    endif()
    ...
endif()
```

**Esito:** la definizione `VK_USE_PLATFORM_ANDROID_KHR` è collocata correttamente nel ramo `elseif(ANDROID)` della condizione interna `if(APPLE)`, a sua volta dentro `elseif(UNIX)`. Non emerge l'errore sintattico `elseif` dopo `endif` ipotizzato sulla base dell'estratto terminale troncato nella sezione 7.50. La presente lettura del file completo supera quella precedente: il sospetto di errore sintattico non è confermato.

### 7.52.4 Target nativi `CemuBin` e `CemuAndroid`

Nel file upstream `src/CMakeLists.txt`:

- su Android `CemuBin` viene creato come libreria statica con `main.cpp` e `mainLLE.cpp`;
- viene applicato `cemu_use_precompiled_header(CemuBin)`;
- viene aggiunta la directory `android/app/src/main/cpp`;
- fuori da Android viene mantenuto `add_executable(CemuBin ...)` e il relativo blocco desktop.

Nel nuovo `src/android/app/src/main/cpp/CMakeLists.txt`:

- viene creato `CemuAndroid` come libreria condivisa;
- sono elencati i sorgenti JNI Android, incluso `AndroidFilesystemCallbacks.cpp`;
- viene applicato il precompiled header;
- il target collega `-landroid`, `CemuCommon`, `CemuAudio`, `CemuComponents`, `CemuCafe`, `CemuBin`, `CemuGui`, `ZArchive::zarchive` e `stb`.

Il grafo di linking non è stato configurato né costruito. Non si conclude quindi che il collegamento sia privo di duplicazioni, dipendenze mancanti o problemi di ordine: occorre verificare i target effettivi e il link NDK.

### 7.52.5 Differenza `CEMU_CXX_FLAGS`

In `android-port/src/CMakeLists.txt` è presente:

```cmake
separate_arguments(CEMU_CXX_FLAGS NATIVE_COMMAND ${CEMU_CXX_FLAGS})
```

Questa chiamata non compare nella versione upstream contenuta nell'archivio. Upstream passa direttamente `${CEMU_CXX_FLAGS}` ad `add_compile_options()`. È una differenza reale, già segnalata nell'audit root (sezione 5.1); il comportamento con valori multipli, virgolette e spazi va verificato senza presumere che una delle due forme sia universalmente corretta.

### 7.52.6 `AndroidFilesystemCallbacks.cpp`: controlli statici

Il file completo conferma cinque callback JNI:
- `OpenContentUri()`;
- `ListFiles()`;
- `IsDirectory()`;
- `IsFile()`;
- `Exists()`.

Il costruttore ottiene `JNIEnv*`, risolve la classe `info/cemu/cemu/nativeinterface/NativeFiles` e acquisisce gli ID dei cinque metodi statici. I descrittori JNI attesi sono:
- `openContentUri(String): int`;
- `listFiles(String): String[]`;
- `isDirectory(String): boolean`;
- `isFile(String): boolean`;
- `exists(String): boolean`.

`OpenContentUri()` inizializza il descrittore a `-1`, esegue la chiamata tramite `FiberSafeJNICALL`, converte il percorso in `jstring`, invoca il metodo statico e rilascia il riferimento locale alla stringa.

`ListFiles()` converte l'URI, invoca `listFiles`, ottiene la lunghezza dell'array, converte ciascun elemento in `std::filesystem::path` e rilascia i riferimenti locali degli elementi e dell'array.

`IsDirectory()`, `IsFile()` ed `Exists()` delegano a `CallBooleanFunction()`.

**Rischi statici da verificare nel contesto completo:**
- il costruttore non mostra controlli espliciti su `JNIEnv*`, classe risolta e `jmethodID`;
- `ListFiles()` non controlla se `pathsObjArray` sia nullo prima di `GetArrayLength()`;
- non sono visibili controlli di eccezioni JNI pendenti dopo le chiamate Java;
- il trattamento di elementi null nell'array non è esplicitato;
- il comportamento di `CallBooleanFunction()` non è contenuto in questo file.

Questi sono punti di robustezza da verificare, non errori runtime riprodotti. La verifica del contratto tra C++ e Kotlin resta incompleta perché l'archivio non contiene `AndroidFilesystemCallbacks.h` né `NativeFiles.kt`.

### 7.52.7 Esito del controllo e prossimi file necessari

**Confermato dall'archivio:**
- i file CMake Android upstream mancanti dal branch `android-port` sono presenti nel lato PR dell'archivio;
- il ramo Vulkan Android è strutturato correttamente nel file completo;
- il root upstream contiene la selezione vcpkg/Boost e l'integrazione delle dipendenze Android descritte sopra;
- il target condiviso `CemuAndroid` include e collega i sorgenti nativi Android;
- il sorgente `AndroidFilesystemCallbacks.cpp` è completo nell'archivio.

**Non verificato:**
- configurazione e build Android;
- link effettivo dei target;
- commit e contenuto dei submodule;
- corrispondenza completa dei descrittori JNI con le implementazioni Kotlin;
- gestione runtime di null ed eccezioni JNI.

**Per proseguire il controllo JNI** occorrono almeno `AndroidFilesystemCallbacks.h`, `JNIUtils.h/.cpp` e `NativeFiles.kt` dal riferimento `upstream/pr-1909`. Non sono presenti nell'archivio ricevuto.

**Stato operativo:** è stata eseguita soltanto l'ispezione dei file allegati e l'aggiornamento del documento di audit. Nessuna modifica al codice applicativo, merge, cherry-pick, commit, push o inizializzazione di submodule è stata effettuata.


## 7.53 — Integrazione dei file JNI e filesystem mancanti — 2026-11-15

### 7.53.1 Provenienza e integrità della raccolta

È stato ricevuto `CemuFixer_missing_files.zip`, generato dal repository locale `CemuFixerAPC` estraendo i file dal riferimento Git `upstream/pr-1909`. Il commit dichiarato nel pacchetto è `7c2324eda01a3974ff89aa1e4ee7a594d278662a`. I quattro file sono stati verificati e aggiunti all'archivio di audit sotto `upstream/pr-1909/`, mantenendo i rispettivi percorsi relativi originali. Sono stati conservati anche `SOURCE_BRANCH.txt` e `SOURCE_COMMIT.txt` come metadati di provenienza.

File acquisiti:
- `src/android/app/src/main/cpp/AndroidFilesystemCallbacks.h`
- `src/android/app/src/main/cpp/JNIUtils.h`
- `src/android/app/src/main/cpp/JNIUtils.cpp`
- `src/android/app/src/main/java/info/cemu/cemu/nativeinterface/NativeFiles.kt`

La raccolta proviene esclusivamente dal repository `CemuFixerAPC` e dal suo riferimento `upstream/pr-1909`; non sono stati utilizzati i repository indipendenti `progetto_cemu/Cemu`, `CemuV2` o `Cemu-Android-Ultimate`.

### 7.53.2 Correzione dello stato documentale precedente

Le affermazioni della sezione 7.52.6 e 7.52.7 secondo cui `AndroidFilesystemCallbacks.h` e `NativeFiles.kt` non erano disponibili descrivevano correttamente il contenuto del precedente ZIP v45, ma non sono più lo stato corrente dell'audit. Con questa raccolta sono disponibili anche l'header, l'implementazione JNI comune e il lato Kotlin. Le osservazioni precedenti sul solo `.cpp` vanno quindi considerate preliminari e integrate dalle verifiche seguenti.

### 7.53.3 Contratto JNI C++/Kotlin

L'header `AndroidFilesystemCallbacks.h` dichiara cinque operazioni virtuali e conserva cinque `jmethodID`. Il `.cpp` già presente nell'archivio v45 risolve la classe `info/cemu/cemu/nativeinterface/NativeFiles` e richiede questi descrittori: `openContentUri(String): int`, `listFiles(String): String[]`, `isDirectory(String): boolean`, `isFile(String): boolean`, `exists(String): boolean`.

`NativeFiles.kt` dichiara i cinque metodi come `@JvmStatic` nell'oggetto Kotlin `NativeFiles`, con parametri `String` e tipi di ritorno corrispondenti. `listFiles` è dichiarato `Array<String?>`, che sul piano JVM conserva il descrittore array di stringhe `[Ljava/lang/String;`. Il contratto nominale tra i cinque `GetStaticMethodID` C++ e le dichiarazioni Kotlin risulta quindi coerente. La verifica è statica: non è stata eseguita una build Kotlin/NDK né un test runtime JNI.

### 7.53.4 Comportamento di `NativeFiles.kt`

- `initialize(contentResolver)` assegna il `ContentResolver` usato dagli altri metodi. Nel materiale raccolto non è presente la chiamata di inizializzazione: il punto e il momento in cui viene invocata restano da verificare nel codice Android circostante.
- `openContentUri` apre l'URI in modalità `r`, esegue `detachFd()` e restituisce il file descriptor; in caso di eccezione o descriptor nullo restituisce `-1` e registra un messaggio di log.
- `listFiles` costruisce la URI dei documenti figli tramite `DocumentsContract`, interroga il resolver e restituisce gli URI dei documenti figli convertiti in percorsi nativi. Le eccezioni della query sono intercettate e producono una lista vuota.
- `isDirectory` confronta il MIME type con `MIME_TYPE_DIR`; non contiene un blocco `try/catch` locale.
- `isFile` restituisce semplicemente `!isDirectory(uri)`: pertanto l'assenza di una URI o un MIME type nullo può essere classificato come file. È una semantica da verificare rispetto ai chiamanti, non una conclusione su un malfunzionamento riprodotto.
- `exists` interroga il resolver e restituisce `true` solo se il cursore esiste e `moveToFirst()` ha successo; le eccezioni sono intercettate e restituiscono `false`.

Le conversioni `toNativePath()` e `fromNativePath()` modificano la codifica dei separatori `%2F` e `/` nella parte successiva all'ultimo `%3A`. I casi limite relativi a URI non-DocumentsProvider, encoding non canonico e nomi contenenti caratteri riservati non sono coperti da test nella raccolta.

### 7.53.5 Verifiche statiche su `JNIUtils.h/.cpp`

- `SetJavaVM` memorizza il puntatore globale `JavaVM*`; `GetEnv()` lo usa per ottenere o collegare il thread corrente alla VM. Non è presente un controllo esplicito che `s_jvm` sia stato inizializzato prima della prima chiamata.
- `GetEnv()` gestisce il caso `JNI_EDETACHED` collegando il thread e scollegandolo nel distruttore del dato `thread_local`. Gli altri codici di ritorno di `GetEnv`/`AttachCurrentThread` non sono gestiti esplicitamente.
- `Scopedjobject` e `Scopedjclass` conservano global reference e le rilasciano tramite `DeleteGlobalRef`; i tipi sono move-only di fatto tramite costruttori/operatori di move dichiarati.
- Il costruttore `Scopedjclass(const char*)` non verifica il risultato di `FindClass` né quello di `NewGlobalRef` prima di procedere a `DeleteLocalRef`.
- `FromJString` non verifica il risultato nullo di `GetStringUTFChars` prima di costruire `std::string`; `ToJString` non verifica il risultato di `NewStringUTF`.
- `HandleNativeException` cerca `NativeException` e chiama `ThrowNew`, ma non controlla il risultato di `FindClass` né gestisce esplicitamente eventuali eccezioni JNI già pendenti.
- `FiberSafeJNICALL`/`FiberSafeJNICall` crea un `std::jthread` temporaneo. La distruzione del `jthread` al termine dell'espressione ne attende il completamento: le lambda che catturano variabili locali per riferimento vengono quindi eseguite in modo sincrono rispetto al ritorno della funzione chiamante, nel normale percorso senza eccezioni. La funzione non restituisce uno stato d'errore JNI e non controlla eccezioni Java pendenti.

Questi punti sono rischi/limiti statici da validare; non sono stati dimostrati crash o errori runtime.

### 7.53.6 Interazione con `AndroidFilesystemCallbacks.cpp`

Con l'header ora disponibile si conferma che `CallBooleanFunction` è implementata inline in `AndroidFilesystemCallbacks.h`: crea la stringa URI, invoca `CallStaticBooleanMethod` e rilascia il riferimento locale. Questo chiude la lacuna documentale indicata nella sezione 7.52.6. Restano i controlli di robustezza già segnalati: validità di classe e method ID, gestione delle eccezioni JNI, validità della stringa e controllo del risultato di `listFiles` prima di `GetArrayLength`. Il codice Kotlin `listFiles` restituisce normalmente un array allocato anche in caso di query fallita, ma il bridge C++ deve comunque trattare in modo difensivo un eventuale risultato JNI nullo dovuto a eccezione o errore di allocazione.

### 7.53.7 Esito aggiornato e limiti

**Confermato staticamente:** i quattro file sono stati acquisiti dalla revisione dichiarata; i cinque nomi e descrittori dei metodi statici corrispondono tra C++ e Kotlin; `CallBooleanFunction` è definita nell'header; `JNIUtils` fornisce gestione dei riferimenti globali e collegamento dei thread alla JVM.

**Ancora da verificare:** punto di chiamata di `NativeFiles.initialize`; chiamata a `JNIUtils::SetJavaVM`; compatibilità delle conversioni URI con i provider e i casi d'uso reali; comportamento con eccezioni/null JNI; build completa Kotlin/NDK e test su dispositivo.

**Stato operativo:** i quattro file sono stati aggiunti esclusivamente all'archivio di audit nella directory `upstream/pr-1909/`; il documento `.md` è stato aggiornato. Nessun file applicativo del branch `android-port` è stato modificato. Nessun checkout, merge, cherry-pick, commit, push o inizializzazione di submodule è stato eseguito.


## 7.54 — Punto di inizializzazione di `NativeFiles` — 2026-11-15

### Evidenza aggiuntiva fornita

L'utente ha eseguito una ricerca sul riferimento `upstream/pr-1909` e ha riportato:

```text
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:34:
    NativeFiles.initialize(contentResolver)

upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/nativeinterface/NativeFiles.kt:42:
    fun initialize(contentResolver: ContentResolver) {
```

### Fatti confermati

- `CemuApplication.kt:34` contiene la chiamata `NativeFiles.initialize(contentResolver)`.
- `NativeFiles.kt:42` contiene la dichiarazione della funzione richiamata.
- Nel corpo Kotlin già acquisito nell'archivio, `initialize()` assegna il parametro alla proprietà privata `contentResolver` dell'oggetto singleton `NativeFiles`.
- La chiamata di inizializzazione è quindi presente nel codice applicativo e non è soltanto un metodo dichiarato ma privo di callsite.
- In base all'ordine di `Application.onCreate()` già documentato nella sezione 7.22, questa chiamata precede `initializeCemu()` e quindi l'inizializzazione JNI/nativa che registra e usa le callback SAF.

### Valutazione aggiornata

Il punto aperto della sezione 7.53.4 relativo alla localizzazione della chiamata `NativeFiles.initialize()` è **chiuso a livello statico**. La sequenza documentata è coerente: il `ContentResolver` viene assegnato prima dell'inizializzazione nativa che rende disponibili le callback filesystem.

Questo riduce il rischio che le callback SAF vengano invocate prima dell'assegnazione del resolver nel normale percorso di avvio `Application.onCreate()`.

Non sono invece dimostrati dal solo grep:
- l'assenza di altri percorsi di invocazione anticipata o di uso fuori dal normale ciclo di avvio;
- il comportamento in caso di eccezioni o errori del provider SAF;
- la correttezza runtime delle conversioni URI e del trasferimento dei file descriptor.

### Stato aggiornato

- **Chiamata Kotlin → inizializzazione `ContentResolver`:** confermata staticamente.
- **Ordine rispetto all'inizializzazione nativa:** coerente e già documentato nella sezione 7.22.
- **Test runtime SAF/JNI:** ancora da eseguire.
- **Modifiche al codice applicativo:** nessuna.
- **Attività svolta:** aggiornamento del solo audit documentale e del relativo archivio ZIP.


## 7.55 — Registrazione della `JavaVM` e installazione delle callback filesystem — 2026-11-15

### Evidenza aggiuntiva fornita

L'utente ha eseguito una ricerca sul riferimento `upstream/pr-1909` e ha riportato i seguenti risultati:

```text
upstream/pr-1909:src/android/app/src/main/cpp/JNIUtils.cpp:7:   void SetJavaVM(JavaVM* jvm)
upstream/pr-1909:src/android/app/src/main/cpp/JNIUtils.h:7:     void SetJavaVM(JavaVM* jvm);
upstream/pr-1909:src/android/app/src/main/cpp/NativeLib.cpp:3:extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, [[maybe_unused]] void* reserved)
upstream/pr-1909:src/android/app/src/main/cpp/NativeLib.cpp:5:  JNIUtils::SetJavaVM(vm);
upstream/pr-1909:src/android/app/src/main/cpp/NativeEmulation.cpp:230:  FilesystemAndroid::SetFilesystemCallbacks(std::make_shared<AndroidFilesystemCallbacks>());
```

La stessa ricerca restituisce anche le dichiarazioni e le definizioni dei metodi della classe `AndroidFilesystemCallbacks` e la loro inclusione nel `CMakeLists.txt` Android.

### Fatti confermati staticamente

- `JNIUtils::SetJavaVM(JavaVM*)` è dichiarata in `JNIUtils.h` e definita in `JNIUtils.cpp`.
- `NativeLib.cpp` definisce un entry point JNI `JNI_OnLoad(JavaVM*, void*)`.
- Il corpo di `JNI_OnLoad`, alla riga 5 secondo l'output, chiama `JNIUtils::SetJavaVM(vm)`: il puntatore alla VM viene quindi impostato dal callback JNI di caricamento della libreria.
- `NativeEmulation.cpp:230` registra un'istanza di `AndroidFilesystemCallbacks` tramite `FilesystemAndroid::SetFilesystemCallbacks(...)`.
- La classe registrata è quella che, nelle sezioni 7.53.3 e 7.53.6, delega le operazioni filesystem ai metodi statici Kotlin di `NativeFiles`.

### Valutazione dell'ordine di inizializzazione

La presenza di `SetJavaVM(vm)` dentro `JNI_OnLoad` chiude la lacuna relativa al **callsite** di inizializzazione di `JNIUtils::s_jvm`: non è più corretto descrivere la VM come priva di un punto di assegnazione individuato.

Secondo il normale contratto JNI/Android, `JNI_OnLoad` viene invocata durante il caricamento della libreria nativa e prima che il caricamento della libreria termini. Questo rende coerente l'uso successivo di `GetEnv()` da parte delle callback, purché queste siano invocate solo dopo il caricamento completato.

La ricerca testuale, tuttavia, non mostra il corpo completo di `JNI_OnLoad`, il punto Kotlin/C++ che carica la libreria, né il contesto completo di `NativeEmulation` intorno alla riga 230. Non consente quindi, da sola, di certificare l'ordine totale tra:

1. caricamento della libreria e chiamata a `JNI_OnLoad`;
2. `NativeFiles.initialize(contentResolver)` in `CemuApplication.onCreate()`;
3. chiamata a `initializeCemu()`/inizializzazione nativa;
4. registrazione delle callback in `NativeEmulation.cpp`;
5. prima invocazione effettiva delle callback filesystem.

La coerenza dei primi due punti rispetto all'avvio dell'app è già documentata nella sezione 7.54; il dettaglio completo della sequenza nativa resta da verificare sul codice integrale.

### Rischi e limiti ancora aperti

- `JNIUtils::SetJavaVM` individua il punto di assegnazione, ma non dimostra da solo che `GetEnv()` non possa essere chiamata prima di `JNI_OnLoad` in altri percorsi.
- Non è ancora verificato se `JNI_OnLoad` controlli e restituisca correttamente `JNI_VERSION_*`, né se esegua altre inizializzazioni.
- Non è verificato se `NativeEmulation` possa registrare le callback più di una volta o se la registrazione sia sincronizzata con il loro uso.
- Non sono stati eseguiti test runtime su thread nativi, attach/detach JVM o callback SAF.

### Esito aggiornato

- **Callsite di `SetJavaVM`:** individuato in `NativeLib.cpp::JNI_OnLoad`.
- **Dichiarazione/definizione di `SetJavaVM`:** entrambe individuate.
- **Callsite di registrazione `AndroidFilesystemCallbacks`:** individuato in `NativeEmulation.cpp:230`.
- **Ordine globale di inizializzazione:** coerente con il modello JNI, ma non ancora certificato dal solo output grep.
- **Build e test runtime:** non eseguiti.
- **Modifiche al codice applicativo:** nessuna.

### Prossimo controllo mirato

Per chiudere la sequenza senza inferenze servono i corpi completi di `NativeLib.cpp`, il contesto di `NativeEmulation.cpp` intorno alla riga 230 e i callsite Kotlin/C++ di `System.loadLibrary` e `initializeCemu`. Il controllo successivo dovrà inoltre verificare che il caricamento della libreria preceda qualsiasi chiamata nativa che possa costruire o usare `AndroidFilesystemCallbacks`.


## 7.56 — Caricamento della libreria nativa e inizializzazione JNI — 2026-11-15

### Evidenza aggiuntiva fornita

L'utente ha riportato l'output dei seguenti controlli su `upstream/pr-1909`:

```text
===== NativeLib.cpp =====
#include "JNIUtils.h"

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, [[maybe_unused]] void* reserved)
{
        JNIUtils::SetJavaVM(vm);
        return JNI_VERSION_1_6;
}

===== NativeEmulation.cpp 190-250 =====

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeEmulation([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
        FilesystemAndroid::SetFilesystemCallbacks(std::make_shared<AndroidFilesystemCallbacks>());
        GetConfigHandle().SetFilename(ActiveSettings::GetConfigPath("settings.xml").generic_wstring());
        NativeEmulation::CreateCemuDirectories();
        NetworkConfig::LoadOnce();
        ActiveSettings::Init();
        LatteOverlay_init();
        CemuCommonInit();
}

===== LOAD LIBRARY / INITIALIZE CEMU =====
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:38:
        initializeCemu()
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:131:
    private fun initializeCemu() {
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:155:
            System.loadLibrary("CemuAndroid")
```

### Fatti confermati staticamente

- `NativeLib.cpp::JNI_OnLoad` riceve il puntatore `JavaVM*`, chiama `JNIUtils::SetJavaVM(vm)` e restituisce `JNI_VERSION_1_6`.
- Il corpo mostrato di `JNI_OnLoad` non contiene altre operazioni e non mostra controlli aggiuntivi sul puntatore `vm` o sul risultato dell'assegnazione.
- `CemuApplication.kt` contiene una chiamata a `initializeCemu()` alla riga 38.
- La funzione `initializeCemu()` è dichiarata alla riga 131.
- All'interno della regione individuata dal grep è presente `System.loadLibrary("CemuAndroid")` alla riga 155.
- Il metodo JNI `NativeEmulation_initializeEmulation` registra `AndroidFilesystemCallbacks` come prima operazione mostrata, prima delle successive inizializzazioni di configurazione, directory, rete e componenti comuni.
- Il nome della libreria caricata da Kotlin (`CemuAndroid`) corrisponde al nome usato per il caricamento del modulo nativo indicato dal progetto; la corrispondenza del nome è confermata testualmente, non dalla verifica dell'APK finale.

### Sequenza ricostruibile e limiti

Le evidenze permettono di ricostruire i seguenti collegamenti:
1. `System.loadLibrary("CemuAndroid")` è presente in `CemuApplication.initializeCemu()`.
2. Durante il caricamento della libreria, il callback JNI `JNI_OnLoad` imposta la `JavaVM` tramite `JNIUtils::SetJavaVM(vm)` e restituisce `JNI_VERSION_1_6`.
3. `CemuApplication.onCreate()` richiama `initializeCemu()` (ordine già documentato nella sezione 7.22 e richiamato dall'output alla riga 38).
4. Il metodo nativo `NativeEmulation_initializeEmulation` installa le callback filesystem tramite `FilesystemAndroid::SetFilesystemCallbacks(...)`.

La sequenza è coerente con il modello JNI: il caricamento della libreria e `JNI_OnLoad` avvengono prima che `System.loadLibrary` termini; le chiamate native successive possono quindi utilizzare la `JavaVM` già registrata. La registrazione delle callback avviene nel corpo di `initializeEmulation`, non nel solo `JNI_OnLoad`.

**Non è ancora dimostrato il preciso ordine tra `System.loadLibrary` e la chiamata a `NativeEmulation.initializeEmulation()`**: l'output fornito mostra la posizione di entrambe, ma non il corpo completo di `initializeCemu()` né le righe Kotlin che invocano il metodo nativo. Inoltre il frammento C++ mostra la registrazione come prima istruzione del metodo, ma non prova quando o su quale thread il metodo venga invocato rispetto alle altre operazioni dell'app.

### Valutazione aggiornata

- La lacuna sul callsite di `SetJavaVM` è chiusa: il punto è `JNI_OnLoad`.
- Il valore di ritorno `JNI_VERSION_1_6` è esplicitamente presente.
- Il callsite Kotlin del caricamento della libreria è individuato in `initializeCemu()`.
- Il callsite di registrazione delle callback è individuato in `NativeEmulation_initializeEmulation`.
- L'ordine generale è coerente, ma il collegamento completo tra caricamento e invocazione del metodo nativo richiede il corpo integrale di `CemuApplication.kt` intorno alle righe 125–165 e la ricerca dei callsite di `initializeEmulation`.
- Non sono stati eseguiti build, test JNI o test su dispositivo.

### Stato

- **`JNI_OnLoad` e assegnazione JavaVM:** confermati staticamente.
- **`System.loadLibrary("CemuAndroid")`:** confermato staticamente dentro `initializeCemu()`.
- **Registrazione delle callback:** confermata nel metodo JNI `initializeEmulation`.
- **Ordine completo load → invocazione native initializeEmulation:** da chiudere con il corpo Kotlin e il callsite.
- **Modifiche al codice applicativo:** nessuna; aggiornati soltanto audit e archivio.

### Prossimo controllo mirato

Acquisire il corpo completo di `CemuApplication.kt` tra le righe 125 e 165 e cercare il callsite Kotlin di `NativeEmulation.initializeEmulation`. In parallelo verificare se `initializeEmulation` viene invocata una sola volta o può essere richiamata durante il ciclo di vita dell'app.


## 7.57 — Callsite Kotlin di `initializeEmulation` e possibili percorsi di invocazione — 2026-11-15

### Evidenza aggiuntiva fornita

Output di `git grep -n -E 'initializeEmulation' upstream/pr-1909 -- src/android`:

```text
upstream/pr-1909:src/android/app/src/main/cpp/NativeEmulation.cpp:228:Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeEmulation(...)
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:11:import info.cemu.cemu.nativeinterface.NativeEmulation.initializeEmulation
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/CemuApplication.kt:142:        initializeEmulation()
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/emulation/EmulationScreen.kt:185:            onInitializeEmulation = viewModel::initializeEmulation,
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/emulation/EmulationViewModel.kt:227:    fun initializeEmulation() {
upstream/pr-1909:src/android/app/src/main/java/info/cemu/cemu/nativeinterface/NativeEmulation.kt:7:    external fun initializeEmulation()
```

### Fatti confermati staticamente

- `NativeEmulation.kt` dichiara `external fun initializeEmulation()`: è il ponte Kotlin dichiarato verso il metodo JNI.
- `CemuApplication.kt` importa la funzione `NativeEmulation.initializeEmulation` e la invoca alla riga 142.
- Il simbolo JNI corrispondente è presente in `NativeEmulation.cpp` alla riga 228.
- Esiste inoltre un metodo `EmulationViewModel.initializeEmulation()` alla riga 227.
- `EmulationScreen.kt` passa `viewModel::initializeEmulation` come callback `onInitializeEmulation`.
- Quindi il nome `initializeEmulation` compare in due livelli distinti: la funzione esterna nativa esposta da `NativeEmulation.kt` e il metodo del ViewModel esposto alla schermata.

### Interpretazione e limiti

Il callsite in `CemuApplication.kt:142` dimostra che l'inizializzazione nativa viene invocata esplicitamente dall'applicazione. In combinazione con `System.loadLibrary("CemuAndroid")` a `CemuApplication.kt:155` e con l'ordine già descritto nella sezione 7.56, è necessario leggere il corpo completo di `initializeCemu()` per stabilire senza ambiguità se il caricamento della libreria preceda la chiamata nativa.

La presenza del metodo nel ViewModel e del collegamento callback in `EmulationScreen` dimostra un ulteriore percorso applicativo collegato al flusso di emulazione. Il solo `grep` non prova però che il ViewModel invochi direttamente la funzione esterna, né che questo percorso produca una seconda registrazione delle callback. Occorre leggere il corpo di `EmulationViewModel.initializeEmulation()` e il contesto della callback in `EmulationScreen`.

### Valutazione aggiornata

- **Callsite nativo in `CemuApplication`:** individuato (`CemuApplication.kt:142`).
- **Dichiarazione `external` Kotlin:** individuata (`NativeEmulation.kt:7`).
- **Implementazione JNI:** individuata (`NativeEmulation.cpp:228`).
- **Percorso UI/ViewModel aggiuntivo:** individuato come collegamento callback, ma semantica non ancora accertata.
- **Numero effettivo di invocazioni e possibile doppia registrazione delle callback:** non determinabile dal solo output `grep`.
- **Ordine definitivo rispetto a `System.loadLibrary`:** richiede il corpo completo di `CemuApplication.initializeCemu()`.

### Prossimo controllo mirato

Acquisire i seguenti frammenti:
- `CemuApplication.kt` righe 130–160;
- `EmulationViewModel.kt` righe 215–250;
- `EmulationScreen.kt` righe 170–195;
- se il ViewModel delega a un'altra funzione, acquisire anche il corpo della funzione delegata.

L'obiettivo è distinguere l'inizializzazione globale eseguita all'avvio dell'app dall'eventuale inizializzazione per sessione/schermata e verificare se entrambe arrivano allo stesso metodo JNI.


## 7.58 — Chiusura del flusso `initializeEmulation`: distinzione tra bootstrap globale e avvio del titolo — 2026-11-15

### Evidenza aggiuntiva fornita

Sono stati acquisiti i corpi di `CemuApplication.kt` (righe 130–165), `EmulationViewModel.kt` (righe 215–250) e `EmulationScreen.kt` (righe 170–195). L'output integrale è conservato in `audit_evidence/2026-11-15_initializeEmulation_bodies.txt` nell'archivio.

### Fatti confermati staticamente

- `CemuApplication.initializeCemu()` configura DPI, percorsi e directory native, quindi chiama `initializeEmulation()`, `initializeSwkbd()` e `refreshGraphicPacks()`.
- L'import già acquisito in `CemuApplication.kt:11` identifica `initializeEmulation` come funzione importata da `info.cemu.cemu.nativeinterface.NativeEmulation`; la chiamata nel corpo di `initializeCemu()` (`:142`) è quindi il callsite Kotlin della funzione nativa dichiarata `external` in `NativeEmulation.kt:7`.
- **Correzione puntuale della sezione 7.56:** `System.loadLibrary("CemuAndroid")` non è nel corpo di `initializeCemu()`. È nel blocco `companion object { init { ... } }` di `CemuApplication`. La precedente formulazione che lo collocava “all'interno di `initializeCemu()`” era imprecisa. Il caricamento è un inizializzatore della classe/companion object, separato dal corpo del metodo.
- `EmulationViewModel.initializeEmulation()` è un metodo distinto: applica una guardia tramite `_isEmulationInitialized` e `emulationInitializationJob`, poi avvia una coroutine `viewModelScope` che esegue in sequenza `prepareTitle()`, `initializeSystems()`, `initializeRenderer()` e `launchTitle()`, propagando l'errore in `_emulationError` e impostando infine `_isEmulationInitialized` a `true`.
- `EmulationScreen` passa `viewModel::initializeEmulation` a `EmulationSurfaces` come callback `onInitializeEmulation`.
- Nel corpo acquisito del ViewModel non compare la chiamata `NativeEmulation.initializeEmulation()`. Il callback della schermata non costituisce quindi una seconda invocazione del metodo JNI globale: avvia il flusso di preparazione/avvio del titolo gestito dal ViewModel.

### Flussi distinti ricostruiti

1. **Bootstrap nativo globale:** inizializzazione della classe `CemuApplication` → `companion object init` → `System.loadLibrary("CemuAndroid")` → caricamento della libreria e `JNI_OnLoad` (che registra la `JavaVM`, come documentato nella sezione 7.56) → successiva chiamata da `CemuApplication.initializeCemu()` alla funzione Kotlin `NativeEmulation.initializeEmulation()` → implementazione JNI in `NativeEmulation.cpp`, che installa le callback filesystem e procede con l'inizializzazione globale.
2. **Avvio del titolo dalla UI:** `EmulationScreen` → callback `viewModel::initializeEmulation` → coroutine nel `EmulationViewModel` → preparazione titolo, sistemi, renderer e `launchTitle()`. Questo è un percorso separato e non richiama, nel corpo verificato, il metodo JNI globale `NativeEmulation.initializeEmulation()`.

La sequenza del bootstrap è coerente con la semantica di inizializzazione Kotlin/JVM: l'inizializzatore del companion object viene eseguito quando il companion object viene inizializzato, prima dell'uso della funzione importata nel flusso dell'applicazione. L'evidenza acquisita dimostra la posizione del caricamento e della chiamata, ma non costituisce una prova runtime su dispositivo.

### Valutazione aggiornata

- **Caricamento della libreria:** callsite individuato nel `companion object init` di `CemuApplication`; non è parte del corpo di `initializeCemu()`.
- **Ordine logico load → chiamata JNI globale:** supportato dalla collocazione dell'inizializzatore del companion object e dal successivo callsite nel metodo applicativo; da non confondere con una verifica runtime.
- **Invocazione JNI globale:** una chiamata esplicita individuata in `CemuApplication.initializeCemu()`.
- **Callback UI/ViewModel:** distinta dall'inizializzazione JNI globale; il corpo esegue la pipeline di avvio del titolo.
- **Doppia registrazione delle callback filesystem tramite il percorso ViewModel:** non supportata dalle evidenze; il corpo del ViewModel non richiama il metodo JNI globale.
- **Build, test JNI e test su dispositivo:** non eseguiti.

### Stato

Il controllo mirato richiesto dalla sezione 7.57 è chiuso per i tre frammenti acquisiti. La distinzione tra bootstrap globale e avvio del titolo è ora documentata. Restano fuori da questa verifica l'esecuzione runtime, la gestione di eventuali errori del caricamento della libreria e la verifica end-to-end su dispositivo. Nessun sorgente applicativo è stato modificato.


## 7.59 — Acquisizione del bundle completo e fotografia del repository — 2026-11-15

### Materiale acquisito

È stato esaminato `CemuFixer_full_audit_bundle_20261115_140955.zip`, raccolto il 15 novembre 2026 alle 14:09:55 (+01:00). Il bundle contiene 6.434 voci ZIP e include:

- snapshot dei tree `android-port`, `upstream/main` e `upstream/pr-1909`;
- patch complete e statistiche dei confronti tra i tre riferimenti;
- metadati Git, gitlink dei submodule e ambiente Android;
- sorgenti dei submodule inizializzati nel checkout;
- documentazione e file di audit;
- modifiche locali non committate e stato Git.

Il bundle non contiene la directory `.git`, credenziali, SDK/NDK, cache Gradle o directory di build. Gli snapshot e le patch sono materiale per analisi statica: non modificano il repository.

### Riferimenti Git fotografati

- Branch corrente: `android-port`.
- HEAD corrente: `6bfd5026eb23a118ba5bfa36af02e2ae7cf652d9`.
- `upstream/main`: `b487cb70ed96cf6a3b5385d1aeff6ea41eaa3f63`.
- `upstream/pr-1909`: `7c2324eda01a3974ff89aa1e4ee7a594d278662a`.

Il commit HEAD del branch `android-port` è quindi avanzato rispetto al commit di base `b487cb70` riportato nelle sezioni iniziali. Per le verifiche successive usare i commit fotografati nel bundle, non assumere che il branch sia ancora fermo al commit iniziale.

### Stato locale fotografato

Il report indica:

```text
## android-port
 M docs/TECHNICAL_AUDIT.md
?? "\\android-port.zip"
?? "\\upstream.zip"
```

Risultano quindi una modifica locale a `docs/TECHNICAL_AUDIT.md` e due elementi non tracciati i cui nomi riportati includono una barra inversa iniziale. Non sono stati committati né rimossi. Il bundle conserva inoltre il diff locale di `docs/TECHNICAL_AUDIT.md`; non va confuso con una modifica al codice applicativo.

**Stato:** fotografia Git acquisita. Non è stato eseguito alcun comando sul repository originale e non è stata modificata la working tree dell'utente.

## 7.60 — Dimensione della divergenza e perimetro reale del porting — 2026-11-15

### Confronto `android-port` / `upstream/pr-1909`

La patch completa e il relativo stat confermano:

- 353 file modificati;
- 34.191 inserimenti e 401 rimozioni;
- 246 file sotto `src/android`;
- 107 file fuori da `src/android`.

I 107 file esterni ad `src/android` sono distribuiti in queste aree:

| Area | File |
|---|---:|
| `src/Cafe` | 30 |
| `src/input` | 18 |
| `src/Common` | 17 |
| `src/gui` | 9 |
| `src/util` | 9 |
| `dependencies/*` | 5 |
| `src/config` | 4 |
| `.github` | 3 |
| `src/audio` | 3 |
| Altri file root / `src` | 9 |
| **Totale** | **107** |

Gli altri 9 comprendono `.gitmodules`, `BUILD.md`, `CMakeLists.txt`, `CODING_STYLE.md`, `src/CMakeLists.txt`, `src/Cemu/FileCache/FileCache.h`, `src/imgui/imgui_extension.cpp`, `src/main.cpp` e `vcpkg.json`.

### Conseguenza per la strategia d'integrazione

La PR non è un'aggiunta isolata di una cartella Android. Modifica componenti condivisi del core, tra cui filesystem e stream, configurazione dei percorsi, GUI, input/controller, audio, Vulkan, fiber e dipendenze CMake/vcpkg.

La sola importazione dei 246 file `src/android` non ricostruirebbe il port: lascerebbe fuori una parte sostanziale delle modifiche richieste per compilare e collegare il core Android. Viceversa, importare in blocco tutti i 353 file senza revisione rischierebbe di sovrascrivere o alterare il lavoro già presente nel branch `android-port`.

### Piano di revisione ricavato dal diff

1. Trattare i 107 file esterni ad `src/android` come un insieme di dipendenze del port Android, non come modifiche accessorie.
2. Raggruppare la revisione per filesystem/SAF, CMake e dipendenze, GUI/lifecycle, input, audio, Vulkan e fiber.
3. Per ogni file distinguere: modifica necessaria per Android, modifica condivisa non specifica Android, modifica upstream da preservare e possibile conflitto con `android-port`.
4. Preparare una mappa delle dipendenze tra i file prima di proporre qualsiasi cherry-pick o applicazione di patch.
5. Non eseguire merge, import o modifiche al codice senza approvazione esplicita.

**Stato:** perimetro quantitativo verificato sul diff completo. La revisione semantica file per file dei 107 elementi non è ancora completata; non dichiarare conclusa l'analisi d'integrazione.

## 7.61 — Divergenze dei submodule tra il checkout e la PR — 2026-11-15

### Confronto dei gitlink

Dal report e dai file `*_gitlinks_submodules.txt` emergono queste differenze:

| Submodule | `android-port` fotografato | `upstream/pr-1909` | Esito |
|---|---|---|---|
| `dependencies/Vulkan-Headers` | `01393c3df0e5285b54ee6527466513f9e614be94` | stesso commit | Coincidente |
| `dependencies/ZArchive` | `d2c717730092c7bf8cbb033b12fd4001b7c4d932` | `965b66c8d67b6b7e30fd63b3b75aa91a99ff303b` | **Divergente** |
| `dependencies/cubeb` | `2071354a69aca7ed6df3b4222e305746c2113f60` | stesso commit | Coincidente |
| `dependencies/imgui` | `f65bcf481ab34cd07d3909aab1479f409fa79f2f` | stesso commit | Coincidente |
| `dependencies/libadrenotools` | assente | `8fae8ce254dfc1344527e05301e43f37dea2df80` | **Presente solo nella PR** |
| `dependencies/metal-cpp` | `a63bd172ddcba73a3d87ca32032b66ad41ddb9a6` | stesso commit | Coincidente |
| `dependencies/vcpkg` | `9849e070ad4c3383890fb1c80ae0c54325e2484a` | stesso commit | Coincidente |
| `dependencies/xbyak_aarch64` | `904b8923457f3ec0d6f82ea2d6832a792851194d` | stesso commit | Coincidente |

### Stato di inizializzazione del checkout

Il `git submodule status` fotografato mostra inizializzati al commit atteso del checkout corrente soltanto `ZArchive` e `xbyak_aarch64`; gli altri gitlink riportati con prefisso `-` risultano non inizializzati. `libadrenotools` non è un gitlink del branch `android-port` fotografato.

### Implicazioni

- Il `ZArchive` presente nel checkout non coincide con il commit richiesto dalla PR. Le osservazioni sul CMake del commit `d2c717...` non devono essere automaticamente attribuite al commit `965b66...` della PR.
- L'integrazione Android della PR introduce il submodule `libadrenotools`, assente dal branch corrente fotografato. Il relativo codice non può essere considerato disponibile nel checkout `android-port` sulla sola base del bundle.
- I gitlink coincidenti non implicano che le rispettive directory siano già inizializzate localmente.
- Il fatto che il bundle contenga sorgenti di alcuni submodule inizializzati non equivale a un'inizializzazione completa di tutti i submodule richiesti dalla PR.

**Prossimo controllo:** prima di una build, confrontare il contenuto e il CMake di `ZArchive` ai due commit e pianificare esplicitamente l'introduzione di `libadrenotools`. Non inizializzare o aggiornare submodule senza approvazione.

## 7.62 — Ambiente Android fotografato: discrepanza rispetto alle verifiche precedenti — 2026-11-15

### Dati registrati nel bundle

Il file `metadata/android_build_environment.txt` riporta:

- OpenJDK `21.0.13`;
- Gradle globale `9.9.1` (Kotlin `2.4.21`);
- CMake Termux `4.4.5`;
- `ANDROID_HOME` non impostato;
- `ANDROID_SDK_ROOT` non impostato;
- `ANDROID_NDK_HOME=/data/data/com.termux/files/usr/opt/android-ndk-r29`;
- nessuna piattaforma SDK elencata;
- nessuna versione Build Tools elencata.

### Confronto con le verifiche annotate in precedenza

Le sezioni precedenti riportavano SDK sotto `$PREFIX/opt/android-sdk`, NDK `29.0.14206865`, CMake SDK `3.30.5`, piattaforme 34/35/36 e Build Tools 35.0.0/36.0.0. Il nuovo report fotografa invece una shell nella quale `ANDROID_HOME` e `ANDROID_SDK_ROOT` non sono impostati e non vengono elencate piattaforme o Build Tools; l'NDK è indicato con il percorso `android-ndk-r29`.

Non è possibile concludere dal solo report che l'SDK sia stato disinstallato: potrebbe trattarsi di una diversa configurazione della shell, di variabili non esportate o di un ambiente Termux differente. È però necessario non considerare persistenti le variabili e i percorsi documentati in precedenza senza una nuova verifica.

### Valutazione

- Java e Gradle globale sono rilevati; ciò non verifica l'esecuzione del Gradle Wrapper della PR né la build Android.
- La presenza dell'NDK al percorso indicato non dimostra che CMake/Gradle riescano a usarlo.
- L'assenza delle variabili SDK nel report rende non verificata la configurazione SDK effettivamente disponibile nella sessione di raccolta.
- Il CMake `4.4.5` rilevato è quello globale Termux; non sostituisce la verifica del CMake SDK richiesto/risolto da Gradle.

**Prossimo controllo:** in una singola sessione Termux, verificare esistenza e contenuto di `$PREFIX/opt/android-sdk`, `$PREFIX/opt/android-ndk-r29`, le variabili `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME`, `sdkmanager --list` e le versioni installate di platform/build-tools. Non modificare file di configurazione finché non è chiaro quale installazione sia quella effettivamente usata.

## 7.63 — Stato dell'audit dopo l'acquisizione del bundle — 2026-11-15

### Risultati chiusi

- Bundle completo acquisito e inventariato.
- Commit esatti di `android-port`, `upstream/main` e `upstream/pr-1909` registrati.
- Stato locale e modifiche non committate fotografati.
- Dimensione della divergenza PR/branch confermata: 353 file, di cui 107 fuori da `src/android`.
- Differenze principali dei gitlink submodule individuate.
- Discrepanza tra l'ambiente SDK/NDK documentato in precedenza e quello riportato nell'ultima raccolta registrata.

### Attività ancora aperte

- Revisione semantica dei 107 file fuori da `src/android`.
- Analisi del delta di `ZArchive` tra i due commit.
- Piano d'integrazione per `libadrenotools`.
- Verifica ripetibile di SDK/NDK e variabili d'ambiente nella shell Termux corrente.
- Configurazione CMake/Gradle Android end-to-end e build NDK.
- Packaging APK, installazione e test sul dispositivo.

### Regole preservate

Nessuna modifica al codice applicativo, nessun merge/import della PR, nessun aggiornamento di submodule, nessun commit o push è stato eseguito durante questo aggiornamento. Il lavoro svolto riguarda l'analisi dei file acquisiti e l'aggiornamento dei documenti/archivio di audit.

**Stato complessivo:** audit statico ampliato; integrazione e build non ancora avviate.
