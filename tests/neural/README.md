# Come sono testate le parti neurali (e cosa prova ogni test)

Obiettivo: non "provare a caso", ma verificare ciascuna garanzia con un test che fallirebbe se la garanzia venisse a mancare.

| Livello | File | Cosa prova |
|---|---|---|
| Matematica | `math_test.cpp`, `numpy_reference.py` | il gradiente analitico coincide con quello numerico (8560 pesi, errore max 0,6%); clipping e decay fanno ciò che dicono; un'implementazione numpy indipendente produce gli stessi pesi dopo un passo (differenza 6e-8) |
| Unità | `neural_test.cpp` (SIMPLE, WRONG INPUT, GAPS) | calcolo a mano, caratteristiche PPC, 20000 blob corrotti rifiutati, NaN/inf, lettura mai fuori sezione, file e modalità |
| Apprendimento | `neural_test.cpp` (HARD, STRENGTH, VALUE) | cambio di dispositivo, etichette avvelenate, raffica assurda con ritorno bit-esatto all'ultimo stato buono, dispositivo 100× più lento, la rete batte l'euristica *calibrata* dove deve (regime Spiky) |
| Controllo | `neural_test.cpp` (GATE, SCHEDULER) | il gate non promuove con il rumore, non oscilla, perde il controllo quando fa danni; nessuna funzione compilata due volte o saltata; priorità e stop rispettati; controlli negativo (costi casuali) e avverso (costi invertiti) |
| Lunga durata | `neural_test.cpp` (SOAK) | un solo consulente su 160000 funzioni con 6 cambi di carico: 1 cambio di modalità, 1 rollback, mai peggio dell'ordine normale |
| Collegamento al motore | `engine_hooks_test.cpp` | il vero codice di `PPCRecompiler.cpp` contro un motore finto e l'analizzatore vero |
| Ciclo dati | `retrain_loop.sh` | traccia nel formato reale → addestramento offline → nuovi pesi: errore su dati mai visti 0,83 → 0,085, ordinamento 87,7% → 99,8% |
| Finestra | `neural_test.cpp` (WINDOW SIZE) | la stima "cosa avrebbe dato una finestra più grande" coincide con l'esecuzione vera (anche con più blocchi), formula verificata contro una versione O(n²) indipendente, raffiche separate, mode.txt con numero |
| Forza dei test | `mutation_test.py` | inserisce 67 errori voluti (uno alla volta) nel codice neurale e nel collegamento: 66 su 66 scoperti, 1 equivalente documentato |
| Copertura | `coverage.sh` | righe eseguite dai test (gcov) |
Senza sanitizer: `bash tests/standalone/run_tests.sh` li esegue anche con ASan, UBSan e TSan.
Mutation testing (~10 min): `python3 tests/neural/mutation_test.py`. Copertura: `bash tests/neural/coverage.sh`.
