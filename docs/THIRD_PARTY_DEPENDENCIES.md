# Dipendenze esterne (submodule) e riferimenti

Il progetto è un fork di **Cemu** (https://github.com/cemu-project/Cemu), licenza **MPL-2.0** (vedi `LICENSE.txt`).
Le librerie sotto sono registrate in `.gitmodules`. Nello zip scaricato da GitHub ("Download ZIP") le cartelle sono VUOTE:
nel repository GitHub reale sono submodule agganciati a un commit preciso. Licenze indicate "da conferma": verificare il file LICENSE di ciascun repository.

| Cartella | Repository | Licenza (da conferma) | Usata su Android |
|---|---|---|---|
| dependencies/ZArchive | https://github.com/Exzap/ZArchive | MIT | sì |
| dependencies/cubeb | https://github.com/mozilla/cubeb | ISC | sì (audio) |
| dependencies/vcpkg | https://github.com/microsoft/vcpkg | MIT | sì (serve la cronologia git completa) |
| dependencies/Vulkan-Headers | https://github.com/KhronosGroup/Vulkan-Headers | Apache-2.0 / MIT | sì |
| dependencies/imgui | https://github.com/ocornut/imgui | MIT | sì |
| dependencies/libadrenotools | https://github.com/bylaws/libadrenotools | BSD-2-Clause | sì (driver GPU custom) |
| dependencies/xbyak_aarch64 | https://github.com/fujitsu/xbyak_aarch64 | Apache-2.0 | sì (JIT ARM64) |
| dependencies/metal-cpp | https://github.com/bkaradzic/metal-cpp | Apache-2.0 | no (solo macOS) |

Già presenti nello zip (cartelle piene): DirectX_2010, gamemode, ih264d, stb, vcpkg_overlay_ports*.
Pacchetti C++ (boost, fmt, zstd, libzip, pugixml, rapidjson...) li scarica vcpkg durante la build (`vcpkg.json`).

**Regola per l'upload su GitHub:** NON cancellare né sostituire le cartelle `dependencies/*` del repository: carica i file
modificati sopra quelli esistenti. Se le cartelle risultassero vuote durante la build, il workflow ora le clona da solo
(fallback, ultimo commit: meno preciso del submodule originale).
