# CemuFixer Android — stato del pacchetto integrato

## Base sorgente
- Albero sorgente: snapshot esatto `upstream/pr-1909`.
- Commit sorgente: `7c2324eda01a3974ff89aa1e4ee7a594d278662a`.
- Branch di confronto: `android-port`, HEAD fotografato `6bfd5026eb23a118ba5bfa36af02e2ae7cf652d9`.
- La PR modifica 353 file rispetto ad `android-port` (34.191 inserimenti, 401 rimozioni).

## Modifiche locali applicate in questo pacchetto
1. Preservato `docs/TECHNICAL_AUDIT.md` dal tree `android-port` e applicata la modifica locale documentale registrata nel bundle.
2. Corretto il task Gradle `ComputeCemuDataFilesHashTask`: rimosso `javax.xml.bind.DatatypeConverter` (JAXB non disponibile nell'ambiente documentato) e usata una conversione esadecimale con API standard Kotlin/JVM.
3. Aggiunti `SUBMODULES.lock` e `scripts/fetch_submodules.sh` per recuperare gli otto submodule ai commit esatti richiesti da `upstream/pr-1909`.
4. Aggiunto `scripts/build_android_debug.sh` con controlli preliminari SDK/NDK e avvio di `assembleDebug`.

## Dipendenze esterne
Gli snapshot del bundle non contengono i sorgenti completi dei submodule ai commit della PR. I commit sono registrati in `SUBMODULES.lock`; lo script li recupera dalla rete. Il fetch non è stato eseguibile nell'ambiente di preparazione perché non è disponibile risoluzione DNS verso GitHub. Non sono stati sostituiti i commit con versioni differenti.

## Verifiche eseguite
- Controllo statico: nessun riferimento residuo a `javax.xml.bind` o `DatatypeConverter` nel file Gradle.
- Verifica di coerenza: otto URL e commit dei submodule estratti da `.gitmodules` e dai gitlink di `upstream/pr-1909`.
- Verifica ZIP e inventario dei file: da eseguire dopo la creazione dell'archivio finale.
- Build Gradle/CMake/APK: NON eseguita. Il bundle fotografa `ANDROID_HOME` e `ANDROID_SDK_ROOT` non impostate e non elenca SDK platforms/Build Tools; inoltre i submodule richiesti non sono presenti ai commit PR.

## Avvio in Termux dopo aver configurato Android SDK/NDK
1. `bash scripts/fetch_submodules.sh`
2. Configurare `ANDROID_HOME` o `ANDROID_SDK_ROOT` verso l'SDK che contiene `platforms`, `build-tools` e `ndk/29.0.14206865`.
3. `bash scripts/build_android_debug.sh`

Questo pacchetto è una base sorgente integrata e corretta per il problema JAXB noto; non è un APK e non viene dichiarato compilato o testato su dispositivo.
