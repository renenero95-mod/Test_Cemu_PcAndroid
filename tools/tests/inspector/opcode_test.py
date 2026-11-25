#!/usr/bin/env python3
"""Compila ed esegue un test con opcode PowerPC reali sulle funzioni IsCall e WrittenRegister di
src/Cafe/OS/RPL/GameInspector.cpp (estratte dal sorgente). Serve g++. Uso: python3 opcode_test.py"""
import os, subprocess, tempfile
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..')
src = open(os.path.join(root, 'src/Cafe/OS/RPL/GameInspector.cpp'), encoding='utf8').read()
def grab(sig):
    i = src.index(sig); j = src.index('{', i); d = 0
    for k in range(j, len(src)):
        if src[k] == '{': d += 1
        elif src[k] == '}':
            d -= 1
            if d == 0: return src[i:k + 1]
test = '''#include <cstdint>
#include <cstdio>
typedef uint32_t uint32; typedef int32_t sint32; typedef int64_t sint64;
''' + grab('bool IsCall(uint32 opcode') + '\n' + grab('sint32 WrittenRegister(uint32 opcode') + '''
int main(){
  int fail=0; auto chk=[&](bool c,const char* m){ printf("%s %s\\n", c?"ok":"KO", m); if(!c) fail++; };
  uint32 t;
  chk(IsCall(0x48000101,0x02000000,t)&&t==0x02000100,"bl avanti");
  chk(IsCall(0x4BFFFFF1,0x02000100,t)&&t==0x020000F0,"bl indietro");
  chk(!IsCall(0x48000100,0x02000000,t),"b senza link non e' una chiamata");
  chk(IsCall(0x480000E3,0x02000000,t)&&t==0xE0,"bla assoluto");
  chk(WrittenRegister(0x80030018)==0,"lwz r0,0x18(r3) scrive r0");
  chk(WrittenRegister(0x38640008)==3,"addi r3,r4,8 scrive r3");
  chk(WrittenRegister(0x60C50001)==5,"ori r5,r6,1 scrive r5");
  chk(WrittenRegister(0x7C832378)==3,"mr r3,r4 scrive r3");
  chk(WrittenRegister(0x7C642A14)==3,"add r3,r4,r5 scrive r3");
  chk(WrittenRegister(0x90010000)==-1,"stw non scrive registri");
  printf("falliti: %d\\n",fail); return fail;
}
'''
with tempfile.TemporaryDirectory() as d:
    open(os.path.join(d, 't.cpp'), 'w').write(test)
    subprocess.check_call(['g++', '-std=c++20', '-Wall', '-o', os.path.join(d, 't'), os.path.join(d, 't.cpp')])
    raise SystemExit(subprocess.call([os.path.join(d, 't')]))
