#!/bin/sh
# Compila il VERO src/Cafe/OS/RPL/GameInspector.cpp contro interfacce che replicano quelle reali di Cemu (mock) ed esegue uno scenario:
# crash per puntatore nullo dopo una chiamata + scansione del codice. Serve g++ 13 (std::format). Uso: sh run.sh  (da qualsiasi cartella)
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../../../src"
cp "$SRC/Cafe/OS/RPL/GameInspector.h" "$SRC/Cafe/OS/RPL/GameInspector.cpp" "$HERE/Cafe/OS/RPL/"
grep -v 'boost/container' "$SRC/Cemu/PPCAssembler/ppcAssembler.h" > "$HERE/Cemu/PPCAssembler/ppcAssembler.h"
cd "$HERE"
g++ -std=c++20 -Wall -Wextra -I. -include Common/precompiled.h -fsyntax-only Cafe/OS/RPL/GameInspector.cpp
g++ -std=c++20 -I. -include Common/precompiled.h -o /tmp/inspector_test mock_impl.cpp Cafe/OS/RPL/GameInspector.cpp 2>/dev/null
/tmp/inspector_test
