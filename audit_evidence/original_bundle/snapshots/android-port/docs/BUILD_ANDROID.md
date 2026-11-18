# CemuFixerAPC — Android Build Guide

## Ambiente previsto

Configurazione di sviluppo:
- Termux su Android.
- OpenJDK rilevato: 21.0.13.
- Android NDK configurato nel progetto: 29.0.14206865.
- CMake richiesto dal progetto: 3.25.0 o superiore.
- ABI iniziale: `arm64-v8a`.
- Gradle Wrapper del progetto Android: 9.4.1.
- Android compile SDK: 36.
- Android min SDK: 30.
- Android target SDK: 35.

Questi valori descrivono la configurazione individuata nel codice della PR #1909; non costituiscono ancora una build verificata.

## Configurazione Android individuata

Il progetto Android si trova in `src/android`.

La configurazione esaminata nella PR #1909 prevede:
- `src/android/app/build.gradle.kts`
- `src/android/settings.gradle.kts`
- `src/android/gradle/wrapper/gradle-wrapper.properties`
- CMake root in `CMakeLists.txt`
- NDK e toolchain Android.

ABI configurata: `arm64-v8a`.

## Prima compilazione

La procedura definitiva sarà scritta dopo aver verificato:
1. Percorsi SDK e NDK disponibili nell'ambiente.
2. Compatibilità Gradle, Android Gradle Plugin e Kotlin.
3. Dipendenze native e submodule.
4. Configurazione CMake per Android.
5. Generazione e installazione dell'APK.

Non considerare ancora disponibile una procedura di build funzionante.

## Diagnostica

Per ogni operazione di compilazione:
- salvare l'output in un log;
- conservare il codice di uscita del comando;
- fermarsi al primo errore;
- annotare comando, errore e soluzione in `TECHNICAL_AUDIT.md`.

## GitHub Actions

Obiettivo iniziale:
- build Debug per `arm64-v8a`;
- trigger sui push a `android-port`;
- trigger manuale;
- APK pubblicata come artifact.

La firma delle APK Release verrà configurata separatamente tramite GitHub Secrets. Non inserire keystore o password nel repository.
