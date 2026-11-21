"""Shared code for the dependency tools: manifest, marker checks, imgui compatibility checks, safe zip handling."""
import json, os, re, stat, sys, zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
IMGUI_MIN, IMGUI_MAX = (1, 87), (1, 90, 99)


def load_manifest():
    with open(os.path.join(os.path.dirname(__file__), "deps_manifest.json"), encoding="utf-8") as f:
        return json.load(f)


def missing_markers(entry, root=ROOT):
    """Marker files that are absent, or present but without the expected text (entry["content"]: file -> text that must appear in it)."""
    base = os.path.join(root, entry["path"])
    miss = [m for m in entry["markers"] if not os.path.isfile(os.path.join(base, m))]
    for f, text in entry.get("content", {}).items():
        if f not in miss and text not in read_text(os.path.join(base, f)):
            miss.append(f + " (contenuto non corrispondente)")
    return miss


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def imgui_version(imgui_h_text):
    m = re.search(r'#define\s+IMGUI_VERSION\s+"(\d+)\.(\d+)(?:\.(\d+))?', imgui_h_text)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3) or 0))


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def struct_body(text, name):
    """Text between the braces of 'struct [IMGUI_API] name {...}' (comments removed), or ''. Needed because a member can still exist in
    the header but moved to another struct (ImFont::FallbackAdvanceX became ImFontBaked::FallbackAdvanceX in imgui 1.92)."""
    t = strip_comments(text)
    m = re.search(r"\bstruct\s+(?:IMGUI_API\s+)?" + re.escape(name) + r"\b[^;{]*\{", t)
    if not m:
        return ""
    depth, i = 1, m.end()
    while i < len(t) and depth:
        depth += (t[i] == "{") - (t[i] == "}")
        i += 1
    return t[m.end():i - 1]


def check_imgui_dir(imgui_dir):
    """Problems (Italian strings) that make this imgui unusable for Cemu. Empty list = compatible as far as we can check."""
    h = read_text(os.path.join(imgui_dir, "imgui.h"))
    problems = []
    if not h:
        return ["imgui.h non trovato"]
    v = imgui_version(h)
    if v is None:
        problems.append("versione di imgui non riconosciuta in imgui.h")
    if not re.search(r'typedef\s+void\s*\*\s*ImTextureID\s*;', h):
        problems.append("ImTextureID non è 'void*' come in Cemu (succede da imgui 1.92 in poi: 'ridefinizione incompatibile di ImTextureID')")
    font, io = struct_body(h, "ImFont"), struct_body(h, "ImGuiIO")
    if not re.search(r'\bFallbackAdvanceX\b', font):
        problems.append("ImFont::FallbackAdvanceX non esiste (spostato in ImFontBaked da imgui 1.92): usato da LatteShaderCache.cpp")
    if not re.search(r'\bNavInputs\s*\[', io):
        problems.append("io.NavInputs[] non esiste: usato da swkbd.cpp e imgui_extension.cpp")
    if not re.search(r'\bImeWindowHandle\b', io):
        problems.append("io.ImeWindowHandle non esiste: usato da imgui_extension.cpp")
    if not re.search(r'\bAddKeyEvent\s*\(', io):
        problems.append("io.AddKeyEvent() non esiste (imgui precedente alla 1.87)")
    if problems and v is not None and (v[:2] < IMGUI_MIN[:2] or v[:2] > IMGUI_MAX[:2]):
        problems.insert(0, "imgui %d.%d.%d non è la versione giusta: Cemu richiede una 1.87-1.90 (consigliata 1.89.9)" % v)
    return problems


def imgui_version_warning(imgui_dir):
    """A version outside 1.87-1.90 whose members all pass the checks is allowed (the pinned version may be a bit different): warn only."""
    v = imgui_version(read_text(os.path.join(imgui_dir, "imgui.h")))
    if v is not None and (v[:2] < IMGUI_MIN[:2] or v[:2] > IMGUI_MAX[:2]):
        return "imgui %d.%d.%d è fuori dall'intervallo provato (1.87-1.90), ma tutti i nomi usati da Cemu esistono: controlla il log di compilazione" % v
    return None


# ---- zips -----------------------------------------------------------------------------------------------------------------
def zip_names(zf):
    return [n for n in zf.namelist() if not n.endswith("/")]


def common_root(names):
    """GitHub zips contain a single top level folder 'repo-ref/': return it (with trailing slash) or ''."""
    tops = {n.split("/", 1)[0] for n in names if "/" in n}
    if len(tops) == 1 and all("/" in n for n in names):
        return tops.pop() + "/"
    return ""


def classify_zip(path, manifest):
    """Which dependency is this zip? Decided by the files inside (not by the file name). Returns (entry, root) or (None, '')."""
    with zipfile.ZipFile(path) as zf:
        names = zip_names(zf)
    root = common_root(names)
    rel = {n[len(root):] for n in names}
    best, bestScore = None, 0
    with zipfile.ZipFile(path) as zf:
        for e in manifest:
            if not all(m in rel for m in e["markers"]):
                continue
            if any(text not in zf.read(root + f).decode("utf-8", "replace") for f, text in e.get("content", {}).items() if f in rel):
                continue
            score = len(e["markers"]) + len(e.get("content", {}))
            if score > bestScore:
                best, bestScore = e, score
    return best, root


def safe_extract(path, root, dest):
    """Extract path (stripping 'root') into dest. Refuses entries that would land outside dest (zip-slip) and skips symlinks."""
    dest = os.path.abspath(dest)
    count = 0
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            if info.filename.endswith("/") or not info.filename.startswith(root):
                continue
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode and stat.S_ISLNK(mode):
                continue
            rel = info.filename[len(root):]
            target = os.path.abspath(os.path.join(dest, rel))
            if os.path.commonpath([dest, target]) != dest:
                raise ValueError("percorso pericoloso nello zip: " + info.filename)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                out.write(src.read())
            count += 1
    return count
