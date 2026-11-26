# CHECK — Session 4

## Modifiche
- `:core`: PowerPC subset (decoder, interpreter, assembler), contenitore sintetico C2A-FX1 + sample, C2A-IR (traduttore AOT, esecutore con fallback interprete contato, testo canonico), pacchetti content-addressed + cache, `PackageValidator` (FCV), `JobRunner` a checkpoint riprendibile (6 stage).
- App: "Add sample", job Run/Pause/Resume/Cancel/Report, lista "Converted" con Run. Versione 0.4.0.
- Sorgenti XEX: restano importabili/analizzabili; la conversione è rifiutata con `FORMAT-UNSUPPORTED` (non implementata, nessuna protezione aggirata).
- Bug corretto (trovato dai test): il traduttore considerava raggiungibile la parola dopo `blr` incondizionato.
- Doc: `docs/FX1_PIPELINE.md`; preflight esteso. Funzionalità esistenti: nessuna rimossa.

## Controlli eseguiti
- `:core`: 142 test JUnit **PASS** (1 saltato: root ignora i permessi). Test differenziali interprete vs IR, mutation spot-check del runner.
- `:app`: typecheck contro stub API **PASS**.
- `update_manifest.py` + `preflight.py` **PASS**; ZIP riestratto e ritestato.

## Non verificato / incompleto
- Build Gradle/APK e API Compose/AndroidX reali: **non eseguiti** (no rete/SDK) → il primo run CI è la verifica.
- Nessuna conversione di giochi Xbox 360 reali: solo fixture sintetica C2A-FX1. Niente loader XEX, kernel/API, grafica, audio, input, ARM64 nativo, misure di performance.
- Lo status del validatore vale per la fixture (`scope=synthetic-fixture-checks`), non è un claim di compatibilità.
- D4 (significato di XEX1/XEX2) ancora aperta: identificatori opachi. GitHub Actions non fissate a SHA.
