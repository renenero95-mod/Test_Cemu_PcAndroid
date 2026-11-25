#!/usr/bin/env python3
"""Controllo statico dei file Kotlin (senza compilare): ogni identificatore che inizia con una maiuscola
(tipo, composable, oggetto) deve essere importato, dichiarato nello stesso pacchetto/file o far parte della libreria
standard. Uso: python3 scripts/check_kotlin_imports.py [file.kt ...]   (senza argomenti controlla tutto src/android)"""
import os, re, sys, glob

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
KT = [os.path.abspath(p) for p in glob.glob(os.path.join(ROOT, 'src/android/app/src/main/java/**/*.kt'), recursive=True)]
STD = set('''String Int Long Float Double Boolean Char Byte Short Unit Any Nothing Array List MutableList Map MutableMap Set MutableSet
ArrayList LinkedHashMap HashMap HashSet LinkedHashSet StringBuilder Exception RuntimeException Throwable Error Pair Triple Regex Result
Comparable Iterable Collection Sequence IntArray ByteArray LongArray FloatArray DoubleArray BooleanArray CharArray Math System Thread Runnable Class
CharSequence Number Enum Annotation Deprecated Suppress JvmStatic JvmOverloads JvmField JvmName Volatile Synchronized Throws Lazy Function Iterator
IllegalStateException IllegalArgumentException NullPointerException UnsupportedOperationException IndexOutOfBoundsException NoSuchElementException
OutOfMemoryError AssertionError ClassCastException ArithmeticException NumberFormatException CancellationException ConcurrentModificationException
UInt UByte UShort ULong LazyThreadSafetyMode Process ProcessBuilder Appendable Cloneable Object Void Override Target Retention Repeatable MustBeDocumented
ExperimentalStdlibApi OptIn RequiresOptIn DslMarker Strictfp Transient Inline Nullable'''.split())

def strip(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'"""(.*?)"""', '""', src, flags=re.S)
    # stringhe: tengo le espressioni ${...} e $name (possono contenere chiamate)
    def repl(m):
        body = m.group(1)
        exprs = re.findall(r'\$\{([^}]*)\}', body)
        return '""' + ' '.join(re.sub(r'"[^"]*"', '""', e) for e in exprs)
    # una stringa con un'espressione ${tr("...")} contiene virgolette annidate: prima le espressioni, poi le stringhe semplici
    src = re.sub(r'"((?:\\.|\$\{[^}]*\}|[^"\\])*)"', repl, src)
    src = re.sub(r"'(?:\\.|[^'\\])'", "''", src)
    return src

decl_re = re.compile(r'\b(?:class|object|interface|typealias|fun|val|var|enum class)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_<>?,\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)')
pkg_names = {}
file_info = {}
for f in KT:
    raw = open(f, encoding='utf8').read()
    pkg = (re.search(r'^package\s+([\w.]+)', raw, re.M) or [None, ''])[1]
    code = strip(raw)
    names = set(decl_re.findall(code))
    # entry di enum e parametri del costruttore principale
    names |= set(re.findall(r'\b([A-Z][A-Z0-9_]+)\b\s*(?:,|\(|;|\n)', code))
    pkg_names.setdefault(pkg, set()).update(names)
    imports = set()
    wild = []
    for m in re.finditer(r'^import\s+([\w.]+?)(?:\s+as\s+(\w+))?\s*$', raw, re.M):
        path, alias = m.group(1), m.group(2)
        if path.endswith('.*'): wild.append(path[:-2])
        imports.add(alias or path.split('.')[-1])
    for m in re.finditer(r'^import\s+([\w.]+)\.\*', raw, re.M): wild.append(m.group(1))
    file_info[f] = (pkg, code, names, imports, wild)

targets = [os.path.abspath(a) for a in sys.argv[1:]] or KT
bad = 0
for f in targets:
    f = os.path.abspath(f)
    if f not in file_info: continue
    pkg, code, own, imports, wild = file_info[f]
    # identificatori maiuscoli non preceduti da punto
    used = set()
    for m in re.finditer(r'(?<![\w.])([A-Z][A-Za-z0-9_]*)\b', code):
        name = m.group(1)
        if len(name) == 1: continue
        if name.isupper() and len(name) > 1:  # costanti: ok se dichiarate
            if name in own or name in pkg_names.get(pkg, set()) or name in imports: continue
        used.add(name)
    missing = sorted(n for n in used if n not in own and n not in imports and n not in STD and n not in pkg_names.get(pkg, set()))
    # un nome importato con carattere jolly non si puo' verificare: lo segnalo solo se non c'e' alcun jolly
    if missing:
        bad += 1
        print('%s: %s' % (os.path.relpath(f, ROOT), ', '.join(missing)))
print('file controllati: %d, con identificatori non risolti: %d' % (len([t for t in targets if os.path.abspath(t) in file_info]), bad))
sys.exit(1 if bad else 0)
