# Piano di 200 studi e idee di sviluppo — CemuFixerAPC Android

Data: 2026-11-19 · Legenda rischio: **B** basso, **M** medio, **A** alto · Stato: **FATTO (da provare)** = già nel codice ma non verificato su telefono; **EVIDENZA** = supportato da log o prove tue; **IPOTESI** = plausibile ma non dimostrato; **IDEA** = da studiare.

**Cosa significa «next gen» qui, senza promesse:** nessuna di queste voci è garantita. Ognuna va provata sul telefono. Le voci della parte A e dei difetti osservati vengono prima, perché senza dati ogni modifica è un tentativo alla cieca.

## Priorità consigliata (prime 12)

1. **Mario Kart 8 (aliante):** emulazione del divisore + retry/dump delle pipeline fallite (D01, D02, A13, A14, B08) → serve il log della build con la voce «Sperimentale».
2. **Crash a puntatore nullo (Watch Dogs, NSLU):** prova «Map null page» (C18) e log dettagliato (A05, B05, B10).
3. **Crash multi-core in Watch Dogs:** revisione ordine di memoria e code FS (B09, B18, B19, C03).
4. **Schermo nero di Watch Dogs a inizio gameplay:** log delle pipeline fallite e dei file non trovati (A13, A14, B14).
5. **Firma stabile dell'APK** (I03).
6. **Report diagnostico in app** (A01).
7. **Menu riorganizzato e sezione Sperimentale** (F01, F02).
8. **Upscaling FSR e filtri** (E01, E02).
9. **Limitatore fps e frame pacing** (E11, D13).
10. **Preset grafici e graphic pack consigliati** (E05, E06).
11. **Atomici LSE e affinità dei thread** (C04, C05).
12. **Cache pipeline e pre-compilazione** (D08, D09).


## A. Stabilità e diagnostica

Obiettivo: ogni build deve spiegare da sola perché fallisce, così non servono giri di prova a vuoto.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| A01 | Report diagnostico in app (log, crash log, impostazioni, info GPU/driver) da condividere con un tocco: `log_inspector_point.txt` con voce «Share inspector report» | Log completi senza cercare file | B | FATTO (da provare) |
| A02 | Al riavvio dopo un crash mostrare un avviso con il pulsante «Condividi report» | Nessun crash senza dati | B | IDEA |
| A03 | Allegare in CI la libreria non strippata (`libCemuAndroid.so`) per risolvere gli offset del crash log con addr2line | Offset del crash decifrabili | B | IDEA |
| A04 | Registro «breadcrumb» degli ultimi 64 eventi HLE (file aperti, errori FS, thread creati) stampato nel crash log | Si vede cosa è successo prima del crash | M | IDEA |
| A05 | Log dettagliato di API non supportate e file non trovati, attivo di default | Individua chiamate mancanti | B | FATTO (da provare) |
| A06 | Crash log con indirizzo del guasto, PC/LR con modulo+offset, mappe di memoria e registri | Crash JIT diagnosticabili | B | FATTO (da provare) |
| A07 | Gestore delle eccezioni C++ non gestite che scrive il motivo nel log | Niente più chiusure silenziose | B | FATTO (da provare) |
| A08 | Verifica di integrità di zip/WUA all'import (dimensione, entry, hash opzionale) | Evita import corrotti | B | IDEA |
| A09 | Scansione del codice del gioco prima dell'avvio (istruzioni non tradotte dal ricompilatore, funzioni coinvolte) con barra di avanzamento, salvata in `inspector/` | Nessun peso durante il gioco | B | FATTO (da provare) |
| A10 | Avviso se «Load shared libraries» è attivo e `cafeLibs` contiene file reali | Spiega crash e finestre «system update» | B | EVIDENZA |
| A11 | Watchdog del thread GPU: se non avanza per N secondi salva diagnostica invece di congelare | Blocchi diagnosticabili | M | IDEA |
| A12 | Gestione di `VK_ERROR_DEVICE_LOST`: messaggio in UI e log invece di chiusura | Errori GPU comprensibili | M | IDEA |
| A13 | Secondo tentativo di creazione pipeline senza pipeline cache su `VK_ERROR_UNKNOWN` | Recupera pipeline fallite (-13) | B | FATTO (da provare) |
| A14 | Salvataggio del GLSL degli shader delle pipeline fallite in `shaderDump/failedPipelines` | Permette di capire cosa rifiuta Adreno | B | FATTO (da provare) |
| A15 | Dump SPIR-V e ricompilazione con opzioni «sicure» (senza ottimizzazioni) quando una pipeline fallisce | Possibile recupero su driver difettosi | M | IDEA |
| A16 | Contatori per gioco (pipeline fallite, API non supportate, file non trovati) mostrati nel dettaglio del titolo | Stato di compatibilità visibile | B | IDEA |
| A17 | Modalità «bisect JIT»: disattiva il ricompilatore per intervalli di indirizzi indicati nel profilo del gioco | Isola l'istruzione che manda in crash | M | IDEA |
| A18 | Nel crash log: disassemblato attorno all'istruzione guasta, indirizzo effettivo, origine del registro base, ultime chiamate `bl` con nome della funzione e catena dei chiamanti | Capire quale funzione restituisce zero | M | FATTO (da provare) |
| A19 | Test automatici (unit test) su ZipUtils, ImportTitles, parsing config, in CI | Meno regressioni | B | IDEA |
| A20 | Eseguire `scripts/verify_tree.py` come primo passo della CI | Errori strutturali bloccati subito | B | FATTO (script, da inserire in CI) |
| A21 | Reazione a `onTrimMemory`: ridurre cache shader/texture invece di essere terminati | Meno chiusure per memoria | M | IDEA |
| A22 | Monitor termico (Thermal API) con avviso e log | Spiega cali di fps | B | IDEA |
| A23 | Flush periodico del log per non perdere righe in caso di crash | Log più completi | B | IDEA |
| A24 | Avviso per driver Vulkan noti problematici con suggerimento di un driver custom | Meno bug da driver | B | IDEA |
| A25 | Conservare i log delle ultime 5 sessioni con data e gioco | Confronto fra prove | B | IDEA |

## B. Compatibilità giochi e HLE

Problemi osservati: Mario Kart 8 (aliante), Watch Dogs (crash all'avvio, nella coda FS, schermo nero), New Super Luigi U (crash a un pulsante), Zelda (richiede update).

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| B01 | «Load shared libraries» spento di default su Android (la schermata lo mostrava spento ma il motore lo accendeva) | Coerenza e meno crash | B | FATTO (da provare) |
| B02 | Implementare `sysapp.SYSLaunchMenu` come «torna all'elenco giochi» (oggi non supportata, vista nel log di Zelda) | Uscita pulita dai giochi | B | EVIDENZA |
| B03 | Implementare `UCOpen/UCClose/UCReadSysConfig` minimali (non supportate nei log) | Meno rami di errore nei giochi | M | EVIDENZA |
| B04 | Implementare `OSGetShutdownReason` | Completezza HLE | B | EVIDENZA |
| B05 | `nn_boss` (IOSU-Boss): errori puliti al posto di puntatori nulli nei task (`plyrepo` prima del crash di NSLU) | Possibile causa del puntatore nullo | M | IPOTESI |
| B06 | Tabella di compatibilità per titolo con impostazioni consigliate (CPU mode, librerie, shader asincroni, driver) | Meno prove a tentativi | M | IDEA |
| B07 | Preset «Adreno» per i profili (barriere accurate, shader asincroni, ecc.) applicabili con un tocco | Configurazione rapida | B | IDEA |
| B08 | Mario Kart 8: verificare divisore attributi + pipeline fallite (-13) in corrispondenza dell'aliante | Aliante visibile | M | EVIDENZA |
| B09 | Watch Dogs: studiare la condizione di gara in `__FSUpdateQueue` con più core | Fine dei crash in multi-core | M | EVIDENZA |
| B10 | New Super Luigi U: individuare quale chiamata restituisce zero prima di `lwz 0x18(r3)` | Fine del crash al pulsante | M | EVIDENZA |
| B11 | Dialog nell'app quando il titolo base richiede un update non trovato | Spiega il messaggio del gioco | B | EVIDENZA |
| B12 | Rilevare il mismatch di regione fra base e update (EU/US) e avvisare | Evita update non validi | B | EVIDENZA |
| B13 | Mostrare update e DLC contenuti in un `.wua` nel dettaglio del gioco | Chiarezza | B | FATTO (nel log) |
| B14 | Mappare gli errori FS agli errori Wii U corretti | Comportamento fedele | M | IDEA |
| B15 | Elenco di giochi «smoke test» con esito registrato per ogni build | Regressioni visibili | M | IDEA |
| B16 | Supporto amiibo via NFC Android (nn_nfp) | Funzione richiesta in molti giochi | A | IDEA |
| B17 | Profili di controller (Pro Controller, Joy-Con, DualSense) preconfigurati | Input pronto all'uso | B | IDEA |
| B18 | Rivedere `FSCmdQueue` e simili per ordinamento acquire/release su ARM64 | Possibile causa dei crash multi-core | M | IPOTESI |
| B19 | Cercare nel codice le assunzioni x86 (TSO) in coreinit (spinlock, mutex veloci, code) | Stabilità multi-core su ARM | M | IPOTESI |
| B20 | Passare automaticamente a «single-core» per un gioco che chiude due volte in multi-core | Meno crash senza intervento | M | IDEA |
| B21 | Importare cache shader condivise dalla community | Meno scatti iniziali | M | IDEA |
| B22 | Ricerca più profonda delle cartelle titolo (opzione) | Trova più titoli | B | IDEA |
| B23 | Verificare il supporto di `.wux/.wud` su SAF | Più formati | M | IDEA |
| B24 | Elenco delle funzioni GX2 non implementate richiamate dal gioco | Priorità di sviluppo chiare | B | IDEA |
| B25 | Archivio JSON locale con esiti per gioco e versione build | Confronto fra build | B | IDEA |

## C. CPU e ricompilatore ARM64

Il ricompilatore ARM64 è giovane: serve correttezza prima delle prestazioni.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| C01 | Flush della cache istruzioni dopo ogni funzione JIT | Elimina una classe di crash casuali | B | FATTO (da provare) |
| C02 | Barriera `release` prima di pubblicare i puntatori JIT nella jump table | Ordine di memoria corretto | B | FATTO (da provare) |
| C03 | `membarrier(SYNC_CORE)` per sincronizzare gli altri core dopo la generazione di codice | Sicurezza su big.LITTLE | M | IDEA |
| C04 | Usare gli atomici ARMv8.1 (LSE: CAS, LDADD) per `lwarx/stwcx` e atomici HLE | Più veloce e corretto | M | IDEA |
| C05 | Affinità dei thread: PPC sui core grandi, GPU/audio sui medi | Frame più regolari | M | IDEA |
| C06 | ADPF `PerformanceHintManager` sui thread di emulazione | Frequenze più reattive | M | IDEA |
| C07 | Priorità dei thread emulazione (`setpriority`) e sospensione di quelli inattivi | Meno interferenze | B | IDEA |
| C08 | Ridurre il costo del cambio fiber (`__OSFiberThreadEntry`) | Meno overhead di schedulazione | A | IDEA |
| C09 | Profilatore dei punti caldi PPC con output su file | Ottimizzazioni guidate dai dati | M | IDEA |
| C10 | Paired-singles nativi con NEON (`psq_l/st`, `ps_*`) | Giochi che li usano molto più veloci | A | IDEA |
| C11 | Percorsi rapidi NEON per `memcpy/memset/memcmp` HLE | Piccolo guadagno | B | IDEA |
| C12 | Cache su disco del codice ricompilato (JIT persistente) | Avvii e scatti molto ridotti | A | IDEA |
| C13 | Compilazione JIT asincrona con priorità alle funzioni richieste | Meno stutter | M | IDEA |
| C14 | Ridurre l'overhead del contatore di cicli con controlli a blocchi | Più fps in CPU-bound | M | IDEA |
| C15 | Supporto a pagine da 16 KB (Android 15) in MemMapper | Compatibilità futura | M | IDEA |
| C16 | Build con LTO thin e PGO di `libCemuAndroid.so` | Guadagno generale di prestazioni | M | IDEA |
| C17 | Opzioni di compilazione per Cortex-X3/A715 con selezione a runtime | Guadagno sul tuo SoC | M | IDEA |
| C18 | Interruttore sperimentale «Map null page»: gli accessi a 0x0–0xFFFF restituiscono zeri | Test diretto dei crash a puntatore nullo (WD, NSLU) | B | FATTO (da provare) |
| C19 | Gestire in modo configurabile gli accessi guest a pagine non mappate (log + zeri invece di chiusura) | Meno chiusure improvvise | M | IDEA |
| C20 | Modalità «accurata» del ricompilatore (meno ottimizzazioni) per i giochi problematici | Isola bug dell'ottimizzatore IML | M | IDEA |

## D. GPU e Vulkan avanzato

Su Adreno 740 (driver Qualcomm del 2023) mancano: divisore attributi, feedback loop, pipeline robustness, R4G4.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| D01 | Emulare il divisore degli attributi vertice quando il driver non lo supporta (stride 0 + draw spezzati) | Oggetti per-istanza corretti (aliante?) | M | FATTO (da provare) |
| D02 | Interruttori di test per ogni emulazione (divisore, retry) nel menu Sperimentale | Più ipotesi per ogni build | B | FATTO (da provare) |
| D03 | Alternativa al divisore: copia GPU con compute che replica gli elementi | Funziona anche con molte istanze | A | IDEA |
| D04 | Fallback del feedback loop con copia della texture (se l'estensione manca) | Meno artefatti su GPU a tile | M | IPOTESI |
| D05 | Strumento per trovare costrutti SPIR-V rifiutati dal driver Adreno e riscriverli | Meno pipeline fallite | A | IDEA |
| D06 | Dynamic rendering (`VK_KHR_dynamic_rendering`) per eliminare i render pass espliciti | Meno oggetti, meno stati | A | IDEA |
| D07 | `VK_EXT_graphics_pipeline_library` dove disponibile | Meno stutter da compilazione | A | IDEA |
| D08 | Pipeline cache separata per versione driver e UUID (driver di sistema/custom) | Evita cache incompatibili | M | IDEA |
| D09 | Pre-compilare le pipeline note al primo avvio del gioco (con barra di avanzamento) | Meno scatti in partita | M | IDEA |
| D10 | Cache pipeline condivisibile fra utenti con lo stesso driver | Avvii senza scatti | M | IDEA |
| D11 | Pre-rotazione della swapchain (`currentTransform`) | Meno lavoro del compositore | A | IDEA |
| D12 | Più immagini di swapchain (triple buffering) con presentazione a latenza minima | Frame più regolari | B | IDEA |
| D13 | `VK_GOOGLE_display_timing` / present timing per il frame pacing | Ritmo costante dei frame | A | IDEA |
| D14 | `VK_QCOM_render_pass_transform` e `fragment_density_map` (VRS) dove presenti | Meno carico GPU | A | IDEA |
| D15 | Specializzazione degli shader con costanti Vulkan al posto di varianti | Meno shader da compilare | A | IDEA |
| D16 | Shader compilati in thread a bassa priorità con coda ordinata per visibilità | Meno stutter | M | IDEA |
| D17 | Cache dei descriptor set e riduzione degli aggiornamenti per draw | Meno CPU nel driver | M | IDEA |
| D18 | Suballocazione di memoria con VMA e buffer più grandi | Meno allocazioni | M | IDEA |
| D19 | Barriere più precise (sincronizzazione2) invece di barriere globali | Meno stalli GPU | A | IDEA |
| D20 | Opzione «barriere accurate» spenta per titoli pesanti (profilo) | Test di prestazioni | B | IDEA |
| D21 | Streamout emulato via compute dove `transform_feedback` manca | Giochi che lo usano | A | IDEA |
| D22 | Gestione dei formati non supportati (R4G4, D24S8) con tabella di fallback verificata | Texture corrette | M | IDEA |
| D23 | Elenco completo di estensioni e limiti Vulkan nel log di avvio | Diagnosi rapida | B | IDEA |
| D24 | Selezione guidata del driver custom (Turnip/Mesa) con prova automatica | Risolve bug del driver di serie | M | IDEA |
| D25 | Validazione automatica dei driver custom (caricamento e prova di una pipeline) | Meno crash all'avvio | M | IDEA |
| D26 | Debug marker (`VK_EXT_debug_utils`) per RenderDoc/AGI | Analisi grafica professionale | B | IDEA |
| D27 | Supporto a Vulkan 1.3 core invece di estensioni singole | Codice più semplice | M | IDEA |
| D28 | Limitare i render pass spezzati per le GPU a tile (accorpare) | Meno traffico di memoria | A | IDEA |
| D29 | Compressione ASTC/ETC2 delle texture decodificate per ridurre RAM e banda | Meno memoria | A | IDEA |
| D30 | Streaming delle texture con eliminazione per uso recente | Meno RAM | M | IDEA |

## E. Grafica, risoluzione e fluidità

Funzioni chieste: risoluzione, ombre, doppio fps, frame skip, qualità.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| E01 | Upscaling finale con FSR 1.0 (EASU+RCAS) oppure CAS | Immagine più nitida sullo schermo 3200×1440 | M | IDEA |
| E02 | Filtro di upscaling selezionabile (bilineare, bicubico, hermite, nearest) nel menu | Qualità a costo quasi nullo | B | IDEA |
| E03 | Sharpening regolabile | Immagine più definita | B | IDEA |
| E04 | Scala di risoluzione interna per titolo tramite i graphic pack di risoluzione, con interfaccia dedicata | Più nitidezza o più fps | M | IDEA |
| E05 | Elenco dei graphic pack «risoluzione/FPS/ombre» consigliati per ogni gioco, con attivazione da menu | Funzioni di qualità senza cercare file | B | IDEA |
| E06 | Selezione preset «Risparmio / Bilanciato / Qualità» che combina scala, ombre e filtri | Un tocco per regolare il peso | M | IDEA |
| E07 | Riduzione ombre tramite graphic pack (risoluzione ombre, distanza) per titoli supportati | Meno carico GPU | M | IDEA |
| E08 | Doppio frame rate tramite graphic pack «FPS++» dove esistono (es. 60 fps) | Più fluidità reale | M | IDEA |
| E09 | Generazione di frame intermedi (interpolazione) in compute sul frame finale | Più fluidità percepita, latenza maggiore | A | IDEA |
| E10 | Frame skip adattivo: salta la presentazione (non l'emulazione) quando il frame è in ritardo | Mantiene la velocità di gioco | M | IDEA |
| E11 | Limitatore di fps per gioco (30/60) con pacing stabile | Meno consumo e rumore di frame | B | IDEA |
| E12 | Frame rate fisso per la finestra (`ANativeWindow_setFrameRate`) | Cadenza regolare sul display 120 Hz | B | FATTO (da provare) |
| E13 | Modalità «risparmio batteria» che limita fps, risoluzione e core | Più autonomia | M | IDEA |
| E14 | Dimensione della pad view e dello schermo TV regolabili con anteprima | Uso dello schermo ottimale | B | IDEA |
| E15 | Opzione per disattivare overlay e HUD dell'emulatore durante il gioco | Schermo pulito | B | IDEA |
| E16 | Correzione colore/gamma/luminosità del display | Resa visiva migliore | B | IDEA |
| E17 | Anti-aliasing post-processo (SMAA o FXAA) opzionale | Bordi più puliti | M | IDEA |
| E18 | Filtro anisotropico configurabile | Texture più nitide a distanza | B | IDEA |
| E19 | Opzione per precisione degli shader (moltiplicazione accurata) con consiglio per gioco | Correttezza grafica o velocità | B | IDEA |
| E20 | Misuratore di frame time in tempo reale (grafico) con esportazione | Vedere stutter e colli di bottiglia | B | IDEA |
| E21 | Rapporto d'aspetto: originale, riempi, estendi con bordo sfocato | Uso dello schermo | B | IDEA |
| E22 | Supporto HDR e Ultra HDR dove il display lo consente | Colori più ricchi | A | IDEA |
| E23 | Texture pack ad alta risoluzione abilitabili da menu | Qualità visiva | M | IDEA |
| E24 | Riduzione dei costi di post-processo: bloom/profondità di campo a metà risoluzione per titolo | Meno carico GPU | A | IDEA |
| E25 | Variable rate shading dove il driver lo consente | Meno carico sui bordi dello schermo | A | IDEA |
| E26 | Ridurre la distanza di disegno con graphic pack dove supportato | Prestazioni nei giochi open world | M | IDEA |
| E27 | Cattura schermo e registrazione video integrati | Condivisione e segnalazione bug | M | IDEA |
| E28 | Modalità «prestazioni stabili» che evita il throttling (limita fps in modo costante) | Fps costanti nelle sessioni lunghe | M | IDEA |
| E29 | Anteprima dell'impatto previsto (leggero/pesante) accanto a ogni opzione grafica | Scelte consapevoli | B | IDEA |
| E30 | Profilo grafico salvato per gioco con ripristino rapido | Meno configurazioni ripetute | B | IDEA |

## F. Interfaccia e menu

Rendere l'app più chiara: meno schermate sparse, più indicazioni.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| F01 | Riorganizzare le impostazioni: Generale, Grafica, Audio, Input, Sperimentale, Diagnostica | Menu comprensibile | M | IDEA |
| F02 | Sezione «Sperimentale» con interruttori per le ipotesi in corso | Prove senza nuove build | B | FATTO (da provare) |
| F03 | Ricerca nelle impostazioni | Trova subito l'opzione | B | IDEA |
| F04 | Descrizioni brevi e consigli accanto a ogni opzione | Meno tentativi | B | IDEA |
| F05 | Wizard di prima configurazione (cartelle giochi, file di sistema, driver, controller) | Avvio senza guide esterne | M | IDEA |
| F06 | Schermata dettaglio del gioco con titolo, versione, update/DLC, regione, profilo, esito dell'ultima sessione | Tutto in un posto | M | IDEA |
| F07 | Voce «Aggiorna elenco giochi» nel menu | Rilettura senza riaprire l'app | B | FATTO (da provare) |
| F08 | Importazione di update/DLC da ZIP o da cartella | Gestione semplice dei contenuti | M | FATTO (da provare) |
| F09 | Supporto `.7z` per l'import (libreria di decompressione) | Nessun passaggio manuale | M | IDEA |
| F10 | Tema Material 3 dinamico con modalità chiara/scura e icone coerenti | Aspetto moderno | B | IDEA |
| F11 | Elenco giochi con copertine, ordinamento, filtri e preferiti | Navigazione migliore | M | IDEA |
| F12 | Modalità griglia/lista con dimensioni regolabili | Uso su schermi diversi | B | IDEA |
| F13 | Overlay in gioco con pannello rapido (fps, scala, ombre, limitatore) richiamabile con un gesto | Regolazione al volo | M | IDEA |
| F14 | Editor del layout dei controlli a schermo con salvataggio di profili | Controlli personalizzati | M | IDEA |
| F15 | Controlli a schermo con feedback aptico e trasparenza regolabile | Comfort | B | IDEA |
| F16 | Test dei controlli (schermata di prova con mappatura visibile) | Configurazione facile | B | IDEA |
| F17 | Notifiche di stato per download/import in corso con possibilità di annullare | Controllo sulle operazioni lunghe | M | IDEA |
| F18 | Barra di avanzamento per import grandi (byte copiati, tempo residuo) | Feedback chiaro | B | IDEA |
| F19 | Messaggi di errore con causa e soluzione (non solo «errore») | Meno confusione | B | IDEA |
| F20 | Localizzazione completa (italiano compreso) con revisione delle stringhe | Interfaccia in italiano | M | IDEA |
| F21 | Accessibilità: dimensioni testo, contrasto, descrizioni dei controlli | Uso per tutti | B | IDEA |
| F22 | Supporto schermi pieghevoli e tablet (layout adattivo) | Più dispositivi | M | IDEA |
| F23 | Modalità immersiva automatica e gestione del notch | Schermo intero corretto | B | IDEA |
| F24 | Scorciatoie e menu contestuale sul gioco (tieni premuto) | Azioni rapide | B | IDEA |
| F25 | Pagina «Informazioni e diagnostica» con versione, commit, GPU, driver, funzionalità Vulkan | Supporto più semplice | B | IDEA |

## G. Titoli, update, DLC e archivi

Il metodo con la cartella estratta funziona: rendere tutto più automatico.

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| G01 | Cartella «Update e DLC» dedicata riconosciuta automaticamente | Un solo posto per i contenuti | B | IDEA |
| G02 | Dopo l'import, aggiornare subito l'elenco senza riavvio | Il gioco vede l'update subito | B | FATTO (da provare) |
| G03 | Rilevare update/DLC non compatibili (titolo, regione, versione) e spiegare il motivo | Evita errori misteriosi | B | IDEA |
| G04 | Eliminare l'update/DLC dal Title manager con spazio recuperato mostrato | Gestione dello spazio | B | IDEA |
| G05 | Convertire cartelle in `.wua` in background con coda e notifica | Meno spazio e file singolo | M | IDEA |
| G06 | Riparare la funzione «convert to wua» (non funziona nel gioco) | Conversione affidabile | M | EVIDENZA |
| G07 | Verifica della conversione (apertura di prova del `.wua` creato) | Niente `.wua` inutilizzabili | M | IDEA |
| G08 | Importare update/DLC direttamente dentro un `.wua` esistente | File unico completo | A | IDEA |
| G09 | Ricerca dei titoli duplicati e delle versioni vecchie | Pulizia | B | IDEA |
| G10 | Pulizia di cache e file temporanei con mostra dello spazio | Più spazio libero | B | IDEA |
| G11 | Gestione di più cartelle giochi con stato (accessibile/scaduta) | Meno elenchi vuoti | B | IDEA |
| G12 | Rinnovo dei permessi SAF scaduti con un tocco | Meno confusione | B | IDEA |
| G13 | Backup e ripristino dei salvataggi (mlc01/usr/save) in zip | Nessuna perdita dati | M | IDEA |
| G14 | Condivisione/esportazione dei profili di gioco | Riutilizzo delle impostazioni | B | IDEA |
| G15 | Import di file di sistema (online files) con verifica della struttura | Configurazione guidata | M | IDEA |

## H. Audio, input e sensori

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| H01 | Audio AAudio a bassa latenza con dimensione buffer adattiva agli underrun | Meno crepitii e latenza | A | IDEA |
| H02 | Regolazione della latenza audio per gioco | Sincronia migliore | B | IDEA |
| H03 | Equalizzatore e volume per gioco | Comfort | B | IDEA |
| H04 | Giroscopio/accelerometro a 100–200 Hz invece di ~50 Hz | Mira con giroscopio più precisa | M | IDEA |
| H05 | Calibrazione del giroscopio con indicatore | Meno deriva | B | IDEA |
| H06 | Touch non batchato (`requestUnbufferedDispatch`) | Latenza touch minore | B | FATTO (da provare) |
| H07 | Correzione degli stati bloccati dei controlli dopo `ACTION_CANCEL` | Niente joystick bloccato | B | FATTO (da provare) |
| H08 | Vibrazione: intensità regolabile e mappatura HD rumble approssimata | Più immersione | M | IDEA |
| H09 | Supporto a più controller contemporanei con assegnazione ai giocatori | Multigiocatore locale | M | IDEA |
| H10 | Microfono del GamePad (se richiesto dal gioco) | Più giochi | A | IDEA |

## I. Build, CI e test

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| I01 | Workflow unico «Android» con verifica struttura, compilazione e test prima del push | Errori scoperti subito | B | IDEA |
| I02 | Cache di vcpkg e Gradle in CI per dimezzare i tempi | Build più veloci | B | IDEA |
| I03 | Firma stabile con keystore nei secret (aggiornare l'APK senza disinstallare) | Niente perdita dati fra build | B | EVIDENZA |
| I04 | Pubblicare l'APK con nome che include il commit e la data | Sapere sempre quale build è installata | B | IDEA |
| I05 | Mostrare il commit e la data della build nella schermata Informazioni | Diagnostica più chiara | B | IDEA |
| I06 | Compilazione con analisi statica (clang-tidy, cppcheck) come passo opzionale | Bug trovati prima | M | IDEA |
| I07 | Sanitizer (ASan/HWASan) per build di diagnostica | Trova memoria corrotta (es. coda FS) | M | IDEA |
| I08 | Build «debug diagnostica» con simboli e asserzioni attive su richiesta | Crash molto più chiari | B | IDEA |
| I09 | Test di avvio su emulatore/dispositivo cloud (se disponibile) | Regressioni evidenti | A | IDEA |
| I10 | Controllo automatico dei merge con upstream: report dei file cambiati e dei conflitti | Aggiornamenti dal PC sicuri | M | IDEA |
| I11 | Script per applicare periodicamente le novità di Cemu PC (merge a tre vie) | Progetto sempre aggiornato | M | IDEA |
| I12 | Changelog generato dai commit e incluso nell'app | Sapere cosa cambia | B | IDEA |

## J. Pulizia e semplificazione

| ID | Idea / studio | Beneficio atteso | Rischio | Stato |
|---|---|---|---|---|
| J01 | Rimuovere il bypass «system update» (non serve: l'update si carica) | Meno opzioni inutili | B | FATTO |
| J02 | Rimuovere i file orfani (es. `FiberUnix.cpp`) | Albero più chiaro | B | IDEA |
| J03 | Togliere le opzioni di desktop non pertinenti (wx, Metal, OpenGL) dalla build Android | Compilazione più veloce | M | IDEA |
| J04 | Eliminare dipendenze non usate da Android (libzip, wxWidgets, ecc.) | Meno tempo di build | M | IDEA |
| J05 | Unificare i messaggi di errore e i testi duplicati | Coerenza | B | IDEA |
| J06 | Spostare la documentazione tecnica in `docs/` con indice aggiornato | Più ordine | B | IDEA |
| J07 | Revisionare le impostazioni esistenti e nascondere quelle che non hanno effetto su Android | Menu più semplice | M | IDEA |
| J08 | Rimuovere codice morto segnalato dalla compilazione (warning di funzioni non usate) | Progetto più piccolo | M | IDEA |

**Totale voci: 200**
