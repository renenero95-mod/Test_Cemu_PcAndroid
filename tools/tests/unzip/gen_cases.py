#!/usr/bin/env python3
"""Genera gli zip di prova in ./cases (validi, corrotti, malevoli). Poi: java UnzipTest.java"""
import zipfile, io, os
os.makedirs('cases', exist_ok=True)
def z(name, entries, raw=None, mut=None):
    b = io.BytesIO()
    with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED) as f:
        for n, d in entries:
            zi = zipfile.ZipInfo(n); zi.compress_type = zipfile.ZIP_DEFLATED; f.writestr(zi, d)
    data = b.getvalue()
    if mut: data = mut(data)
    open(f'cases/{name}.zip', 'wb').write(raw if raw is not None else data)
z('01_valido_con_cartelle', [('d/', b''), ('d/a.txt', b'A'), ('d/s/', b''), ('d/s/b.txt', b'B')])
z('02_senza_entry_cartelle', [('d/s/b.txt', b'B'), ('x.txt', b'X')])
z('03_traversal_semplice', [('../evil.txt', b'EVIL')])
z('04_traversal_profondo', [('a/../../evil.txt', b'EVIL')])
z('05_percorso_assoluto', [('/abs.txt', b'ABS')])
z('06_backslash', [('..\\evil.txt', b'BS')])
z('07_troncato', [('big.bin', os.urandom(200000))], mut=lambda d: d[:len(d) // 2])
z('08_garbage', [], raw=os.urandom(5000))
z('09_vuoto', [])
z('10_nome_lunghissimo', [('n' * 400 + '.txt', b'L')])
z('11_bomba_64MB', [('zeros.bin', b'\0' * (64 * 1024 * 1024))])
z('12_duplicati', [('a.txt', b'1'), ('a.txt', b'2')])
z('13_traversal_dopo_validi', [('ok/a.txt', b'A'), ('ok/', b''), ('../../x/evil.txt', b'E')])
print('generati', len(os.listdir('cases')), 'zip')
