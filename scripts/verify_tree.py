#!/usr/bin/env python3
"""Verifica strutturale dell'albero (non compila nulla). Uso: python3 scripts/verify_tree.py [radice]
Controlli: marker di conflitto, file elencati nei CMakeLists ma inesistenti, include "..." non risolvibili,
simboli COSStaticData/IOSUStaticData usati ma non definiti, YAML dei workflow validi.
Esce con codice 1 se trova errori. Gli include di librerie esterne (boost, fmt, vulkan...) sono ignorati."""
import os, re, sys, glob, collections

root = sys.argv[1] if len(sys.argv) > 1 else '.'
os.chdir(root)
errors = []

def rd(p):
    return open(p, encoding='utf8', errors='ignore').read()

SRC_EXT = ('.cpp', '.h', '.hpp', '.c', '.mm', '.inl')
sources = [f for f in glob.glob('src/**/*', recursive=True) if f.endswith(SRC_EXT)]

# 1) marker di conflitto git
for f in sources + glob.glob('CMakeLists.txt') + glob.glob('src/**/CMakeLists.txt', recursive=True):
    for i, l in enumerate(rd(f).splitlines(), 1):
        if re.match(r'^(<<<<<<< |=======$|>>>>>>> )', l):
            errors.append(f'marker di conflitto: {f}:{i}')

# 2) file elencati nei CMakeLists ma inesistenti (si ignorano i percorsi con variabili e i submodule non scaricati)
for p in glob.glob('**/CMakeLists.txt', recursive=True):
    if p.startswith(('dependencies/', 'audit_evidence/')):
        continue
    d = os.path.dirname(p)
    s = re.sub(r'#.*', '', rd(p))
    for tok in re.findall(r'[\w./\-+]+\.(?:cpp|h|hpp|c|cc|mm|m|inl)\b', s):
        if '${' in tok or tok.startswith('/') or tok.startswith('../../dependencies/'):
            continue
        if not os.path.exists(os.path.normpath(os.path.join(d, tok))):
            errors.append(f'CMake elenca un file inesistente: {p} -> {tok}')

# 3) include "..." non risolvibili
EXT = re.compile(r'^(boost|fmt|glm|rapidjson|zlib|zstd|curl|pugixml|openssl|SDL|wx|libusb|hidapi|nlohmann|gsl|cubeb|imgui|vulkan|'
                 r'spirv|glslang|xbyak|Windows|windows|sys|netinet|arpa|android|jni|ZArchive|zarchive|stb|libadrenotools|adrenotools|'
                 r'zip|Metal|Foundation|QuartzCore|AppKit|SPIRV|vk_|vma|magic_enum|httplib|xxhash|ih264|zconf|tinyxml|pugi|TargetConditionals)')
ROOTS = ['src', 'src/Common', 'src/Cafe', 'src/gui/interface', 'src/android/app/src/main/cpp', 'dependencies', 'dependencies/imgui',
         'dependencies/stb/include', 'dependencies/ZArchive/include', 'dependencies/Vulkan-Headers/include',
         'dependencies/libadrenotools/include', 'dependencies/ih264d', 'dependencies/xbyak_aarch64', 'src/Cafe/HW', 'src/Cafe/OS',
         'src/resource', 'dependencies/glslang', 'src/input']
for f in sources:
    if f.startswith('src/gui/wxgui') or f.startswith('src/Cafe/HW/Latte/Renderer/Metal'):
        continue  # GUI desktop e Metal: non fanno parte della build Android
    d = os.path.dirname(f)
    for i, l in enumerate(rd(f).splitlines(), 1):
        m = re.match(r'\s*#\s*include\s*"([^"]+)"', l)
        if not m:
            continue
        inc = m.group(1)
        if EXT.match(inc):
            continue
        if not (os.path.exists(os.path.join(d, inc)) or any(os.path.exists(os.path.join(r, inc)) for r in ROOTS)):
            errors.append(f'include non risolto: {f}:{i} "{inc}"')

# 4) static data: ogni simbolo usato in CafeStaticVar<...>{X} deve avere una definizione.
#    Lo stesso nome puo comparire in file diversi (namespace diversi, es. nn::save / nn::boss / TCL): si segnala solo il doppione nello stesso file.
defs = collections.defaultdict(int)
defnames = set()
uses = collections.defaultdict(set)
for f in sources:
    t = rd(f)
    local = collections.Counter()
    for m in re.finditer(r'(extern\s+)?(?:constinit\s+)?(?:COS|IOSU)StaticData\s+(\w+(?:::\w+)*)\s*;', t):
        if not m.group(1):
            n = m.group(2).split('::')[-1]
            defnames.add(n)
            local[n] += 1
    for n, c in local.items():
        if c > 1:
            errors.append(f'static data definito piu volte nello stesso file: {n} ({f})')
    for m in re.finditer(r'CafeStaticVar<[^>]*(?:<[^>]*>)?[^>]*>\s+\w+\s*\{\s*([\w:]+)\s*\}', t):
        uses[m.group(1).split('::')[-1]].add(f)
for sym, fs in uses.items():
    if sym not in defnames:
        errors.append(f'static data usato ma non definito: {sym} ({sorted(fs)[0]})')

# 5) YAML dei workflow
try:
    import yaml
    for f in glob.glob('.github/workflows/*.yml') + glob.glob('.github/actions/*/action.yml'):
        try:
            yaml.safe_load(open(f, encoding='utf8'))
        except Exception as e:
            errors.append(f'YAML non valido: {f}: {str(e)[:80]}')
except ImportError:
    print('(pyyaml assente: controllo YAML saltato)')

print(f'file sorgente controllati: {len(sources)}')
if errors:
    print(f'ERRORI: {len(errors)}')
    for e in errors[:60]:
        print('  -', e)
    sys.exit(1)
print('OK: nessun errore strutturale')
