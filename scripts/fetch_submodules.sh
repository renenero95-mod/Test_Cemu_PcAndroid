#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/SUBMODULES.lock"

if ! command -v git >/dev/null 2>&1; then
  echo "ERRORE: git non trovato." >&2
  exit 1
fi

while IFS=$'\t' read -r path url commit; do
  [[ -z "${path:-}" || "$path" == \#* ]] && continue
  [[ -n "${url:-}" && -n "${commit:-}" ]] || {
    echo "Pin submodule non valido: $path" >&2
    exit 1
  }

  dest="$ROOT/$path"
  mkdir -p "$(dirname "$dest")"
  if [[ -d "$dest/.git" || -f "$dest/.git" ]]; then
    git -C "$dest" fetch --no-tags origin "$commit"
  else
    rm -rf "$dest"
    git clone --no-checkout "$url" "$dest"
    git -C "$dest" fetch --no-tags origin "$commit"
  fi
  git -C "$dest" checkout --detach "$commit"
  git -C "$dest" submodule update --init --recursive
  actual="$(git -C "$dest" rev-parse HEAD)"
  [[ "$actual" == "$commit" ]] || {
    echo "Commit errato per $path: $actual (atteso $commit)" >&2
    exit 1
  }
  echo "[OK] $path @ $commit"
done < "$LOCK"

echo "Submodule allineati ai gitlink di upstream/pr-1909."
