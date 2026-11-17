# Legacy patch scripts (già applicati)

Questi script `patch_*.py` erano nella root del progetto. Servivano a inserire timer/limiti di debug
nei sorgenti con sostituzioni di testo. **Le modifiche sono già nei sorgenti** (verificato nella Fase 1):
rilanciarli fallirebbe (anchor non trovati) oppure duplicherebbe codice. Non sono usati da CMake né dalla CI.
Sono tenuti solo come storico. `patch_ra_cap_v3.py` (limite 8000 IML nel register allocator) NON risulta applicato.
