#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/src/android"

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "ERRORE: Gradle Wrapper non trovato o non eseguibile in src/android." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "ERRORE: ANDROID_HOME/ANDROID_SDK_ROOT non impostata. Configurare l'SDK Android prima della build." >&2
  exit 2
fi
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
if [[ ! -d "$SDK/platforms" ]]; then
  echo "ERRORE: nessuna directory platforms trovata in $SDK." >&2
  exit 2
fi
if [[ ! -d "$SDK/ndk/29.0.14206865" ]]; then
  echo "ERRORE: manca NDK 29.0.14206865 in $SDK/ndk. Installare la versione richiesta dal build.gradle.kts." >&2
  exit 2
fi

cd "$ANDROID_DIR"
./gradlew --no-daemon --stacktrace assembleDebug
