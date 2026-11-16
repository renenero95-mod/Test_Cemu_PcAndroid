# Test eseguibili senza Android (servono solo Java 21, Python 3, g++)
- `unzip/` : `cd tools/tests/unzip && python3 gen_cases.py && java UnzipTest.java` — confronta lo `unzip()` Kotlin PRIMA/DOPO il fix F4 su 13 zip. Atteso: colonna DOPO senza file "FUORI" e con `02_senza_entry_cartelle` = OK.
- `fspath_test.cpp` : `g++ -std=c++20 fspath_test.cpp -o t && ./t` — controllo di `libraryName` (fix F3); atteso `falliti: 0`.
- `../../scripts/verify_tree.py` : controllo strutturale dell'albero (CMake/include/static data/YAML/conflitti).
Nota: la logica Kotlin è tradotta riga per riga in Java; non è il Kotlin reale.
