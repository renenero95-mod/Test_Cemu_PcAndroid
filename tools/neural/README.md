# Allenare i pesi con dati reali

1. Gioca una sessione con la build neurale. In `<cartella dati Cemu>/neural/` trovi `jit_cost_trace.csv` e `report.txt`.
2. Dal repository:
   ```
   g++ -std=c++20 -O2 -Isrc -Itests/neural tools/neural/train_priors.cpp -o /tmp/train_priors
   /tmp/train_priors src/Cafe/HW/Espresso/Recompiler/Neural/NeuroJitDefaultWeights.h jit_cost_trace.csv
   ```
   Senza il secondo argomento usa i dati sintetici (priori). Con la traccia stampa l'errore finale e lo confronta con l'euristica.
3. Controlla `bash tests/standalone/run_tests.sh`, poi ricompila.
Se sei da telefono: manda i due file a Claude e li allena lui.
