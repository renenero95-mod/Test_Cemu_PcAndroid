# Porta fedele della logica di InspectorReport.generate (Kotlin) per provarla sui log reali
import sys,re
def gen(path):
    lines=open(path,encoding='utf8',errors='ignore').read().splitlines()
    unsupported={}; notfound={}; pipe=[]; vk=[]; title=None; run=-1; crash=-1
    for i,l in enumerate(lines):
        if title is None and 'Mounting title ' in l: title=l.split('Mounting title ',1)[1].strip()[:16].lower()
        if run<0 and 'Run title' in l: run=i
        if crash<0 and (l.startswith('Error: signal') or 'Unhandled C++ exception' in l): crash=i
        if 'Unsupported lib call: ' in l:
            n=l.split('Unsupported lib call: ',1)[1].strip(); unsupported[n]=unsupported.get(n,0)+1
        elif 'FS: not found: ' in l:
            n=l.split('FS: not found: ',1)[1].strip(); notfound[n]=notfound.get(n,0)+1
        elif 'Failed to create graphics pipeline' in l: pipe.append(l.strip())
        elif 'Vulkan:' in l and len(vk)<40: vk.append(l.strip())
    return dict(lines=len(lines),title=title,run=run,crash=crash,unsupported=unsupported,notfound=notfound,pipe=len(pipe),vk=len(vk),text=('NULL POINTER ACCESS' in '\n'.join(lines)))
for f in sys.argv[1:]:
    r=gen(f); print(f.split('/')[-1]); print('  righe',r['lines'],'titolo',r['title'],'runTitle@',r['run'],'crash@',r['crash'])
    print('  unsupported:',dict(list(r['unsupported'].items())[:4]),' notfound:',len(r['notfound']),' pipeline fail:',r['pipe'],' vulkan lines:',r['vk'])
