# Test eseguibili senza Android (servono solo Java 21, Python 3, g++)
- `unzip/` : `cd tools/tests/unzip && python3 gen_cases.py && java UnzipTest.java` — confronta lo `unzip()` Kotlin PRIMA/DOPO il fix F4 su 13 zip. Atteso: colonna DOPO senza file "FUORI" e con `02_senza_entry_cartelle` = OK.
- `fspath_test.cpp` : `g++ -std=c++20 fspath_test.cpp -o t && ./t` — controllo di `libraryName` (fix F3); atteso `falliti: 0`.
- `../../scripts/verify_tree.py` : controllo strutturale dell'albero (CMake/include/static data/YAML/conflitti).
Nota: la logica Kotlin è tradotta riga per riga in Java; non è il Kotlin reale.
- `inspector/opcode_test.py` : compila ed esegue un test con opcode PowerPC reali sulle funzioni di decodifica dell'inspector (serve g++); atteso `falliti: 0`.
- `inspector/report_sim.py <log...>` : porta in Python la logica di `InspectorReport.kt` e la prova sui log reali (titolo, crash, pipeline fallite, chiamate non supportate).
- `inspector/mock/run.sh` : compila il **vero** `GameInspector.cpp` contro interfacce che replicano quelle di Cemu e lo esegue su uno scenario simulato (puntatore nullo dopo una chiamata + scansione); atteso: nessun errore o avviso di compilazione e righe «NULL POINTER ACCESS», «returned 0», «NOT translated».
- `../scripts/check_kotlin_imports.py` : controllo statico degli import di tutti i file Kotlin (segnala tipi non importati/dichiarati).
