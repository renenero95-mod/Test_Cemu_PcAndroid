# CemuFixerAPC — Android Port Plan

## Stato del progetto
- Repository: `renenero95-mod/CemuFixerAPC`
- Engine di riferimento: `cemu-project/Cemu`
- Branch di lavoro: `android-port`
- Branch principale: `main`
- Dispositivo di test previsto: POCO F6 Pro
- Architettura Android iniziale: `arm64-v8a`

## Obiettivo
Realizzare un porting Android di Cemu mantenendo il codice upstream come base e integrando selettivamente le modifiche Android disponibili nella PR upstream #1909.

Non utilizzare un fork Android di terze parti come engine di base.

## Strategia Git
- `main`: base Cemu upstream, da mantenere pulita.
- `android-port`: sviluppo e integrazione Android.
- Non importare automaticamente modifiche sperimentali da altri repository.
- Ogni integrazione deve essere valutata e verificata.

## Fasi di sviluppo

### Fase 1 — Documentazione e audit
- [x] Verifica repository e remote.
- [x] Verifica working tree pulito.
- [x] Creazione branch `android-port`.
- [x] Avvio documentazione tecnica.
- [ ] Completare audit delle modifiche Android della PR #1909.

### Fase 2 — Integrazione Android
- [ ] Verificare CMake e configurazione Gradle.
- [ ] Integrare selettivamente GUI e lifecycle Android.
- [ ] Verificare JNI e callback Kotlin/C++.
- [ ] Verificare Storage Access Framework.
- [ ] Verificare Vulkan e Adrenotools.
- [ ] Verificare input touch e controller.

### Fase 3 — Prima compilazione
- [ ] Configurare build ARM64.
- [ ] Risolvere incompatibilità Gradle, Kotlin, Java e NDK.
- [ ] Ottenere una prima APK Debug.
- [ ] Verificare installazione e avvio.

### Fase 4 — GitHub Actions
- [ ] Workflow Android Debug.
- [ ] Avvio automatico sui push al branch `android-port`.
- [ ] Avvio manuale con `workflow_dispatch`.
- [ ] Pubblicazione APK come artifact.
- [ ] Release firmate solo in una fase successiva.

### Fase 5 — Test dispositivo
- [ ] Installazione su POCO F6 Pro.
- [ ] Test Vulkan.
- [ ] Test accesso ai giochi tramite SAF.
- [ ] Test input e stabilità.
- [ ] Test compatibilità dei giochi.

## Regole
- Non modificare `main` per lo sviluppo Android.
- Non sovrascrivere codice sperimentale senza revisione.
- Non dichiarare una funzione completata senza test.
- Registrare errori e risultati nei documenti.
