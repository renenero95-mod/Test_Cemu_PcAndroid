# CemuAndroidUltimatePesoNeurale

Questo progetto è una **copia esatta** di "Cemu Android Ultimate" alla **fase 2c** (vedi `CEMU_FASE1_REPORT.md`).
Da qui in poi si sviluppa in parallelo a quello senza peso neurale (`Cemu-Android-Ultimate-main`):

| Progetto | Contenuto |
|---|---|
| Senza peso neurale | si continua a migliorare come prima |
| **Questo (con peso neurale)** | stessa base 2c + un sistema a pesi neurali che lavora *dentro* l'emulatore (non grafica/upscaling tipo DLSS) |

Stato: **Fasi N1 e N2 completate** (consulente neurale per l'ordine di compilazione JIT e sua verifica, vedi `PESO_NEURALE_FASE_N1.md`). Regole di lavoro: stesse verifiche del progetto base
(`bash tests/standalone/run_tests.sh`, `python3 tests/static/ifdef_brace_check.py`), ogni parte neurale spenta di default
finché non è provata, e la build senza di essa deve restare identica alla 2c.
