# Peso neurale — Fasi N1, N2 e N3: consulente neurale per l'ordine di compilazione JIT, come lo abbiamo verificato e come scegliere la finestra

## Cosa fa
Il JIT ha migliaia di funzioni PPC in coda all'avvio e le compila in ordine di arrivo; finché una non è compilata gira nell'interprete (molto più lento).
Una piccola rete neurale (353 pesi) **prevede il tempo di compilazione** di ogni funzione dal suo codice (dimensione, salti, cicli, float, salti indiretti...).
Compilare prima le più economiche riduce il tempo medio di attesa. Impara **sul tuo telefono** dai tempi reali.

## Perché non può "allucinare" danni
Decide **solo l'ordine** di lavori indipendenti: cosa e come si compila non dipende da lei. Una previsione sbagliata costa velocità, mai correttezza. In più:
1. **Shadow prima:** si confronta con la realtà (ordine normale vs previsto vs perfetto, sui tempi veri di ogni finestra) e prende il controllo solo con confidenza statistica (media − 3 errori standard ≥ +3% sulle ultime 32 finestre); lo perde quando non può più dimostrare di aiutare.
2. **Finestra di 512 funzioni:** nessuna funzione aspetta più della propria finestra; priorità alta/bassa non si mescolano; l'ordine è sempre una permutazione verificata.
3. **Numeri sempre sani:** ingressi limitati, tanh, pesi ±40, controlli sui bit, uscita entro ±6 unità logaritmiche (~400×) dall'euristica calibrata.
4. **Apprendimento prudente:** perdita di Huber, gradiente tagliato, campioni assurdi scartati, ritorno bit-esatto all'ultimo stato buono se diverge, apprendimento a fine finestra in ordine rimescolato.
5. **File a prova di errore:** pesi con versione, forma, CRC32 e controllo dei valori; file rovinato = ignorato. Scrittura atomica.
6. **Si spegne da solo** se analizzare costa troppo (>0,25 ms per funzione); si spegne a mano con `mode.txt`.
7. **Lettura memoria sicura:** solo dentro la sezione eseguibile registrata dal loader.

## Cosa vedi sul telefono (cartella dati di Cemu → `neural/`)
`report.txt` (modo, guadagno misurato, errore, rollback, **tabella delle finestre**; ogni 30 s) · `mode.txt` (scrivi `off`, `shadow`, `active` o `auto`, con un numero opzionale per la finestra, es. `active 2048`; riletto senza riavvio) · `jit_cost.weights` (pesi imparati) · `jit_cost_trace.csv` (dati reali per riaddestrare, max 2 MB).
Confronto: workflow con `neural` spuntato o no (`-PcemuNeural=false`, CMake `-DCEMU_ENABLE_NEURAL=OFF`).

## Cosa abbiamo scoperto verificando (e corretto)
Su tua richiesta ho costruito test che falliscono se una garanzia viene meno. Risultati che cambiano ciò che ti avevo detto:
1. **Bug vero, corretto:** in modalità attiva le funzioni si compilano in ordine di costo previsto, quindi la rete imparava da dati *ordinati* e dimenticava il resto: il guadagno calava da +22% a +1% nel tempo. Ora impara a fine finestra, in ordine rimescolato: resta a +38…41%.
2. **Mutation testing:** inseriti errori uno alla volta. Con i test iniziali, su 59 ne passavano inosservati 24 (scoperti solo il 59%): non era vero che i test coprissero tutto. Dopo i test mirati, su 67 errori (compresi quelli del codice per la finestra) **66 su 66 scoperti**, 1 equivalente documentato.
3. **Correzione di una mia affermazione:** nel regime "dispositivo diverso" l'ordine dell'euristica da sola dà già lo stesso guadagno della rete (+75,9% nelle finestre); lì la rete migliora solo la *calibrazione* (errore 0,09 contro 0,29). La rete **batte l'euristica solo quando il costo non dipende dalla dimensione** (regime "Spiky"): precisione d'ordinamento 99,0% contro 87,9%, guadagno nelle finestre +69% contro +46%.
4. **Velocità di apprendimento:** misurata con `tools/neural/tune.cpp`. Tasso 0,004 → 0,02: nel regime Spiky il guadagno pieno arriva dopo ~5000 compilazioni invece di 60000, senza instabilità.
5. **Limite troppo stretto:** il vincolo di ±3 sull'uscita limitava il guadagno al 28% quando l'euristica è molto sbagliata; con ±6 sale al 41%.
6. **Guadagno nelle finestre ≠ guadagno complessivo, ora misurato:** il riordino è limitato alla finestra, quindi su code lunghe il tempo totale di attesa scende poco (1–1,4% su 27000 funzioni con finestra 512). Per scegliere la finestra con dati veri, il consulente **calcola da solo cosa avrebbero ottenuto finestre 2×, 4×, 8× più grandi** sulle compilazioni realmente avvenute (stima controfattuale dai tempi misurati) e lo scrive in `report.txt`, insieme al **ritardo peggiore di una singola funzione**. Convalida: la stima di una sola esecuzione con finestra 512 coincide con il risultato di eseguire davvero le finestre più grandi (coda sintetica di 4096 funzioni; anche con 12 finestre in una raffica, quindi con riporto dei tempi tra blocchi):

| finestra | tempo di attesa risparmiato (stimato) | misurato eseguendola davvero | ritardo peggiore di una funzione |
|---|---|---|---|
| 512 | 9,4% | 9,4% | 3,8 s |
| 1024 | 19,4% | 19,4% | 7,7 s |
| 2048 | 37,3% | 37,3% | 14,6 s |
| 4096 | 72,6% | 72,6% | 30,0 s |

   Il compromesso è chiaro: finestre più grandi risparmiano molto di più ma ritardano di più le funzioni grosse (che potrebbero essere importanti: la rete non sa quali sono "calde"). **Nessun valore nasconde il rischio:** la scelta si fa guardando il tuo `report.txt`, poi `mode.txt` (`active 2048`, ecc.), senza ricompilare. Valori sintetici: quelli veri dipendono dalla tua coda.

## Prove (tutte offline; la compilazione dell'intero Cemu resta solo nella tua GitHub Action)
- **Matematica:** gradiente analitico = gradiente numerico su 8560 pesi (errore max 0,6%); un'implementazione numpy indipendente dà gli stessi pesi dopo un passo (differenza 6e-8).
- **Sanitizer:** tutto passa con ASan, UBSan e TSan; **copertura** 97,8% delle righe (le restanti 17 sono difese non raggiungibili, es. perdita non finita con pesi limitati).
- **Ciclo dati completo:** traccia nel formato esatto del telefono → addestramento offline → nuovi pesi: errore su dati mai visti 0,83 → 0,085, ordinamento 87,7% → 99,8%.
- **Lunga durata (SOAK):** un consulente, 160000 funzioni, carico cambiato 6 volte: 1 cambio di modalità, 1 rollback, mai peggio dell'ordine normale (0,986–0,993×).
- **Controlli negativi:** costi casuali → non prende il controllo; prior invertito → non fa danni e poi recupera; 10% di etichette avvelenate → errore 0,03; rumore puro sul gate: ≤2 cambi di modalità in 2000 finestre.
- **Collegamento al motore:** il vero codice di `PPCRecompiler.cpp` eseguito contro un motore finto e l'analizzatore vero: funzioni invalidate durante l'attesa saltate, mai compilato un indirizzo non più "visitato", nessuna lettura fuori sezione.
Dettaglio in `tests/neural/README.md`. Esegui: `bash tests/standalone/run_tests.sh`, `python3 tests/static/ifdef_brace_check.py`, opzionali `python3 tests/neural/mutation_test.py` (~12 min) e `bash tests/neural/coverage.sh`.

## Onestà sullo stato
- I pesi iniziali sono **priori sintetici** (si comportano come l'euristica). Non esistono ancora dati reali di Wii U: tutti i numeri sopra vengono da carichi *sintetici*, che provano il meccanismo, non il guadagno sul tuo telefono.
- Non so quanto la compilazione reale dipenda dalle caratteristiche misurate. L'anello di miglioramento esiste e funziona: **dopo una sessione mandami `neural/report.txt` e `neural/jit_cost_trace.csv`** e riaddestro pesi veri (`tools/neural/README.md`).
- Prestazioni (x86): previsione 0,5 µs, passo di apprendimento ~2 µs, scansione di 4096 parole ~3 µs; tutto sul thread JIT.

## Prossimi passi possibili
Scegliere la finestra con il tuo `report.txt` · ordinare per rapporto beneficio/costo usando contatori di visita (serve strumentare l'interprete: da misurare) · altri consulenti neurali (rilevamento blocchi, politica cache texture) che useranno lo stesso nucleo e gli stessi test.
