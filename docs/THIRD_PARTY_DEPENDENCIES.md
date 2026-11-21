# Dipendenze esterne (cartella `dependencies/`)

Il progetto è un fork di **Cemu** (https://github.com/cemu-project/Cemu), licenza **MPL-2.0** (`LICENSE.txt`).
Lo zip scaricato da GitHub ("Download ZIP") ha le cartelle delle dipendenze **vuote**: i sottomoduli non vengono inclusi, nemmeno quelli
annidati. Per questo le build fallivano.

## Cosa era successo (errore mio)
La workflow, se trovava le cartelle vuote, clonava le dipendenze **all'ultimo commit e senza i sottomoduli annidati**:
- `cubeb` senza `sanitizers-cmake` → `Could not find sanitizers-cmake`;
- `imgui` all'ultima versione (1.92) → `ridefinizione incompatibile di ImTextureID`, `ImFont::FallbackAdvanceX` inesistente
  (Cemu usa anche `io.NavInputs` e `io.ImeWindowHandle`, spariti nella 1.92).
Ora **non clona più niente a caso**: la workflow controlla che le dipendenze siano nel repository e, se mancano o sono incompatibili,
si ferma in pochi secondi dicendo quali e con quali link (`tools/deps/check_dependencies.py`). Solo `vcpkg` viene clonato dalla CI.

## Cosa scaricare (link dei repository.zip)
| Cartella | Link da scaricare | Note |
|---|---|---|
| dependencies/imgui | https://github.com/ocornut/imgui/archive/refs/tags/v1.89.9.zip | **versione 1.87-1.90** (consigliata 1.89.9). NON l'ultima |
| dependencies/cubeb | https://github.com/mozilla/cubeb/archive/HEAD.zip | audio |
| dependencies/cubeb/cmake/sanitizers-cmake | https://github.com/arsenm/sanitizers-cmake/archive/HEAD.zip | sottomodulo di cubeb |
| dependencies/ZArchive | https://github.com/Exzap/ZArchive/archive/HEAD.zip | archivi .wua |
| dependencies/Vulkan-Headers | https://github.com/KhronosGroup/Vulkan-Headers/archive/HEAD.zip | header Vulkan |
| dependencies/libadrenotools | https://github.com/bylaws/libadrenotools/archive/HEAD.zip | driver GPU (Android) |
| dependencies/libadrenotools/lib/linkernsbypass | https://github.com/bylaws/liblinkernsbypass/archive/HEAD.zip | sottomodulo di libadrenotools (il repository si chiama **liblinkernsbypass**, non linkernsbypass) |
| dependencies/xbyak_aarch64 | https://github.com/fujitsu/xbyak_aarch64/archive/HEAD.zip | JIT ARM64 |
| dependencies/vcpkg | (non serve scaricarlo) | lo clona la CI con la cronologia completa |
(`HEAD.zip` = ultima versione del ramo principale. `metal-cpp` serve solo su macOS.)

Pacchetti C++ (boost, fmt, zstd...) li scarica vcpkg durante la build (`vcpkg.json`).

## Come si installano
- **Da telefono:** scarica i zip, mandali a Claude: li riconosce dal contenuto (il nome non conta), li mette nei posti giusti, controlla
  versione di imgui e nomi usati, e ti restituisce un unico zip del progetto con le dipendenze già dentro.
- **Da computer:** `python3 tools/deps/install_dependency_zips.py cartella_con_gli_zip/` poi `python3 tools/deps/check_dependencies.py`.
- **Su GitHub:** carica le cartelle `dependencies/*` **piene** (se nel repository sono sottomoduli lascia stare: la CI li usa così come sono).
  Non cancellare mai `dependencies/vcpkg_overlay_ports*`.
- Dubbi sulla versione di imgui del repository originale? Su GitHub apri la sua cartella `dependencies`: accanto a `imgui` c'è il commit
  esatto (es. `imgui @ a1b2c3d`); scaricalo con `https://github.com/ocornut/imgui/archive/<commit>.zip`.

## Licenze (da conferma nel LICENSE di ciascun repository)
imgui MIT · cubeb ISC · sanitizers-cmake MIT · ZArchive MIT · Vulkan-Headers Apache-2.0/MIT · libadrenotools BSD-2-Clause · linkernsbypass BSD-2-Clause · xbyak_aarch64 Apache-2.0 · vcpkg MIT.

## Versioni incluse in questo zip (commit scaricati da te)
imgui v1.89.9 · cubeb dcd7cdd · sanitizers-cmake bcb1fc6 · ZArchive 965b66c · Vulkan-Headers b26454f · libadrenotools 8fae8ce · xbyak_aarch64 aa81336 · liblinkernsbypass aa39758.
Se `liblinkernsbypass` mancasse, la workflow lo clona da sola (`https://github.com/bylaws/liblinkernsbypass`, con avviso nel log).
`vcpkg` lo clona sempre la CI. Inoltre cubeb ora si configura con `USE_SANITIZERS=OFF`, quindi non dipende più da sanitizers-cmake.

## Controlli automatici prima della build (falliscono in pochi secondi)
`tools/deps/check_dependencies.py` (cartelle e file presenti, imgui compatibile) · `check_imgui_symbols.py` (ogni nome di imgui usato da Cemu esiste)
· `check_cmake_paths.py` (ogni percorso `dependencies/...` citato da CMake esiste).
