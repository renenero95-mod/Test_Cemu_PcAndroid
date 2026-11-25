#!/data/data/com.termux/files/usr/bin/bash
# Raccolta completa per audit CemuFixer: snapshot dei tre ref, diff, submodule,
# documentazione, evidenze locali e metadati ambiente. Non modifica i sorgenti.
set -Eeuo pipefail

REPO="${1:-$HOME/CemuFixerAPC}"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUT_DIR:-$REPO}"
OUT="$OUT_DIR/CemuFixer_full_audit_bundle_${STAMP}.zip"

if ! command -v git >/dev/null 2>&1; then
  echo "ERRORE: git non trovato. Installa git in Termux." >&2; exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
  echo "ERRORE: zip non trovato. Esegui: pkg install zip" >&2; exit 1
fi
if [[ ! -d "$REPO/.git" && ! -f "$REPO/.git" ]]; then
  echo "ERRORE: repository Git non trovato in: $REPO" >&2
  echo "Uso: bash $0 [percorso_repository]" >&2; exit 1
fi
mkdir -p "$OUT_DIR"
REPO="$(cd "$REPO" && pwd)"
TMP="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/cemufixer-audit.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
ROOT="$TMP/CemuFixer_full_audit_bundle"
mkdir -p "$ROOT/snapshots" "$ROOT/diffs" "$ROOT/metadata" "$ROOT/submodules" "$ROOT/audit_docs" "$ROOT/local_changes"
cd "$REPO"

# Identità dei ref richiesti dall'audit. Se un ref manca, lo segnala e continua.
REFS=("android-port" "upstream/main" "upstream/pr-1909")
{
  echo "Repository: $REPO"
  echo "Data raccolta: $(date -Iseconds 2>/dev/null || date)"
  echo "Branch corrente: $(git branch --show-current 2>/dev/null || true)"
  echo "Commit HEAD: $(git rev-parse HEAD)"
  echo
  echo "=== STATUS ==="
  git status --short --branch
  echo
  echo "=== REMOTES (URL eventualmente redatti) ==="
  git remote -v | sed -E 's#(https?://)[^/@]+:[^/@]+@#\1[REDACTED]@#g'
  echo
  echo "=== REF / COMMIT ==="
  for ref in "${REFS[@]}"; do
    if git rev-parse --verify "$ref^{commit}" >/dev/null 2>&1; then
      printf '%s %s\n' "$ref" "$(git rev-parse "$ref^{commit}")"
    else
      printf '%s MISSING\n' "$ref"
    fi
  done
  echo
  echo "=== SUBMODULE STATUS CURRENT CHECKOUT ==="
  git submodule status --recursive 2>&1 || true
  echo
  echo "=== GIT VERSION ==="
  git --version
} > "$ROOT/metadata/repository_report.txt"

# Esporta ogni ref completo (tutti i file tracciati, non soltanto quelli Android).
for ref in "${REFS[@]}"; do
  safe="${ref//\//__}"
  if git rev-parse --verify "$ref^{commit}" >/dev/null 2>&1; then
    mkdir -p "$ROOT/snapshots/$safe"
    git archive --format=tar "$ref" | tar -xf - -C "$ROOT/snapshots/$safe"
    git ls-tree -r "$ref" > "$ROOT/metadata/${safe}_tree.txt"
    git ls-tree -r "$ref" | awk '$1=="160000" {print}' > "$ROOT/metadata/${safe}_gitlinks_submodules.txt"
    echo "[OK] Snapshot $ref"
  else
    echo "[ASSENTE] Ref $ref non disponibile" | tee -a "$ROOT/metadata/missing_refs.txt"
  fi
done

# Diff completi e applicabili, inclusi rename/binary dove Git li rappresenta.
if git rev-parse --verify upstream/main^{commit} >/dev/null 2>&1 && git rev-parse --verify upstream/pr-1909^{commit} >/dev/null 2>&1; then
  git diff --binary --full-index --find-renames upstream/main...upstream/pr-1909 > "$ROOT/diffs/upstream_main_vs_pr-1909.patch" || true
  git diff --stat upstream/main...upstream/pr-1909 > "$ROOT/diffs/upstream_main_vs_pr-1909_stat.txt" || true
fi
if git rev-parse --verify android-port^{commit} >/dev/null 2>&1 && git rev-parse --verify upstream/pr-1909^{commit} >/dev/null 2>&1; then
  git diff --binary --full-index --find-renames android-port...upstream/pr-1909 > "$ROOT/diffs/android-port_vs_pr-1909.patch" || true
  git diff --stat android-port...upstream/pr-1909 > "$ROOT/diffs/android-port_vs_pr-1909_stat.txt" || true
fi
if git rev-parse --verify upstream/main^{commit} >/dev/null 2>&1 && git rev-parse --verify android-port^{commit} >/dev/null 2>&1; then
  git diff --binary --full-index --find-renames upstream/main...android-port > "$ROOT/diffs/upstream_main_vs_android-port.patch" || true
  git diff --stat upstream/main...android-port > "$ROOT/diffs/upstream_main_vs_android-port_stat.txt" || true
fi

# Differenze locali non committate: solo tracciati/untracked non ignorati; non include .git.
git diff --binary HEAD > "$ROOT/local_changes/working_tree.patch" || true
git diff --cached --binary > "$ROOT/local_changes/index.patch" || true
git status --short --untracked-files=all > "$ROOT/local_changes/status_all.txt" || true
# Copia i documenti Markdown presenti nel repository, inclusi audit e note tecniche.
find . -type f \( -iname '*.md' -o -iname '*.markdown' -o -iname '*.txt' \) \
  -not -path './.git/*' -not -path './build/*' -not -path './.gradle/*' \
  -not -path './.cxx/*' -not -path './out/*' -print0 |
while IFS= read -r -d '' f; do
  dest="$ROOT/audit_docs/${f#./}"
  mkdir -p "$(dirname "$dest")"
  cp -p "$f" "$dest"
done

# Include eventuale audit MD fornito tramite variabile AUDIT_MD.
if [[ -n "${AUDIT_MD:-}" && -f "${AUDIT_MD}" ]]; then
  cp -p "$AUDIT_MD" "$ROOT/audit_docs/$(basename "$AUDIT_MD")"
fi

# Sorgenti dei submodule attualmente inizializzati, con commit esatto.
# I gitlink dei tre ref sono riportati separatamente nei metadata.
if [[ -f .gitmodules ]]; then cp -p .gitmodules "$ROOT/metadata/gitmodules_current.txt"; fi
export AUDIT_SUBMODULE_ROOT="$ROOT/submodules"
: > "$ROOT/metadata/submodules_manifest.txt"
git submodule foreach --quiet --recursive '
  name=$(printf "%s" "$displaypath" | tr "/" "_")
  dest="$AUDIT_SUBMODULE_ROOT/$name"
  mkdir -p "$dest"
  git archive --format=tar HEAD | tar -xf - -C "$dest"
  printf "%s %s\n" "$displaypath" "$(git rev-parse HEAD)" >> "$AUDIT_SUBMODULE_ROOT/../metadata/submodules_manifest.txt"
' 2>/dev/null || true

# Informazioni Android/Java/Gradle disponibili: nessun dato personale o credenziale.
{
  echo "=== JAVA ==="; (java -version 2>&1 || true)
  echo; echo "=== GRADLE ==="; (gradle --version 2>&1 | head -25 || true)
  echo; echo "=== CMAKE ==="; (cmake --version 2>&1 | head -2 || true)
  echo; echo "=== NDK / SDK ==="
  echo "ANDROID_HOME=${ANDROID_HOME:-<non impostato>}"
  echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<non impostato>}"
  echo "ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-<non impostato>}"
  echo "PREFIX=${PREFIX:-<non impostato>}"
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null || ls "$ANDROID_HOME/ndk"; fi
  echo; echo "=== SDK PLATFORMS ==="
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platforms" ]]; then ls "$ANDROID_HOME/platforms"; fi
  echo; echo "=== SDK BUILD TOOLS ==="
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then ls "$ANDROID_HOME/build-tools"; fi
} > "$ROOT/metadata/android_build_environment.txt" 2>&1

# Elenco dei contenuti e istruzioni per chi riceve l'archivio.
cat > "$ROOT/LEGGIMI.txt" <<'INFO'
CemuFixer - raccolta completa per audit Android

Contenuto:
- snapshots/android-port: tutti i file tracciati del branch android-port
- snapshots/upstream__main: tutti i file tracciati di upstream/main
- snapshots/upstream__pr-1909: tutti i file tracciati della PR Android
- diffs: patch complete e statistiche tra i tre ref
- submodules: sorgenti dei submodule inizializzati nel checkout corrente
- metadata: commit, gitlink, stato Git, remotes redatti, ambiente Android
- audit_docs: Markdown, note e documentazione presenti nel repository
- local_changes: diff locali e stato dei file non committati

Non contiene .git, credenziali, SDK/NDK, cache Gradle o directory build.
Le patch e gli snapshot sono per analisi statica; non modificano il repository.
I gitlink dei submodule per ogni ref sono in metadata/*_gitlinks_submodules.txt.
INFO

if [[ -f "$0" ]]; then cp -p "$0" "$ROOT/metadata/CemuFixer_collect_audit_bundle_termux.sh"; fi

# Crea archivio dalla directory temporanea. -9 massimizza la compressione.
(cd "$TMP" && zip -qr -9 "$OUT" "$(basename "$ROOT")")

# Verifica integrità e riepilogo.
unzip -t "$OUT" | tail -2
printf '\nZIP CREATO: %s\n' "$OUT"
ls -lh "$OUT"
printf '\nREF INCLUSI:\n'
cat "$ROOT/metadata/repository_report.txt" | sed -n '/=== REF \/ COMMIT ===/,/=== SUBMODULE STATUS/p'
printf '\nNOTA: se un ref risulta MISSING, esegui prima git fetch upstream e rilancia lo script.\n'
