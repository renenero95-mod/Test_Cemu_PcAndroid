#!/usr/bin/env python3
"""Lightweight static check (no compiler needed): evaluates #if/#ifdef blocks for several macro
configurations, strips comments/strings and verifies that braces/parentheses stay balanced and that
#if/#endif are matched. Catches the most common mistakes of text-based patches.
Usage: python3 tests/static/ifdef_brace_check.py [file ...]   (defaults to the files changed in Phase 1)"""
import re, sys, itertools

DEFAULT_FILES = [
 "src/Cafe/HW/Espresso/Recompiler/PPCRecompiler.cpp",
 "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp",
 "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRendererCore.cpp",
 "src/Cafe/OS/RPL/elf.cpp",
 "src/util/CemuDiagV2.h", "src/util/CemuProfiling.h", "src/util/CemuThreadWatchdog.h",
 "src/Cafe/HW/Espresso/Recompiler/IML/IMLRegisterAllocator.cpp", "src/Cafe/HW/Espresso/Recompiler/PPCRecompilerTrace.h",
 "src/Cafe/OS/libs/snd_user/snd_user.cpp",
 "src/Cafe/HW/Espresso/Recompiler/Neural/NeuralMLP.h", "src/Cafe/HW/Espresso/Recompiler/Neural/NeuralGuard.h", "src/Cafe/HW/Espresso/Recompiler/Neural/NeuroJitFeatures.h",
 "src/Cafe/HW/Espresso/Recompiler/Neural/NeuroJitAdvisor.h", "src/Cafe/HW/Espresso/Recompiler/Neural/NeuroJitScheduler.h", "src/Cafe/HW/Espresso/Recompiler/Neural/NeuroJitSession.h", "src/Cafe/OS/libs/coreinit/coreinit_MEM.cpp",
 "src/Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.cpp", "src/Cafe/HW/Espresso/Analyzer/PPCPreExecutionAnalyzer.h",
]
BASE = {"PPCREC_FORCE_SYNCHRONOUS_COMPILATION":0,"PPCREC_LOG_RECOMPILATION_RESULTS":0,"__aarch64__":1,"__linux__":1,
        "BOOST_PLAT_ANDROID":1,"BOOST_OS_WINDOWS":0,"BOOST_OS_LINUX":1}

def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        c=src[i]
        if src.startswith("//",i):
            while i<n and src[i]!="\n": i+=1
        elif src.startswith("/*",i):
            j=src.find("*/",i+2); j = n if j<0 else j+2
            out.append("\n"*src[i:j].count("\n")); i=j
        elif c=="'" and i>0 and (src[i-1].isalnum()):
            i+=1  # C++14 digit separator (10'000), not a char literal
        elif c in "\"'":
            q=c;i+=1
            while i<n and src[i]!=q:
                if src[i]=="\\": i+=1
                i+=1
            i+=1; out.append('""')
        else: out.append(c); i+=1
    return "".join(out)

def evalcond(expr, macros):
    e=expr.strip()
    e=re.sub(r"defined\s*\(\s*(\w+)\s*\)", lambda m: "1" if m.group(1) in macros else "0", e)
    e=re.sub(r"defined\s+(\w+)", lambda m: "1" if m.group(1) in macros else "0", e)
    e=re.sub(r"\b[A-Za-z_]\w*\b", lambda m: str(macros.get(m.group(0),0)), e)
    e=e.replace("&&"," and ").replace("||"," or ").replace("!"," not ")
    e=re.sub(r"not\s*=","!=",e)
    try: return bool(eval(e,{},{}))
    except Exception: return False

def preprocess(text, macros):
    macros=dict(macros); stack=[]  # (active, taken, parent_active)
    active=True; out=[]
    for line in text.split("\n"):
        s=line.strip()
        if s.startswith("#"):
            d=re.match(r"#\s*(\w+)\s*(.*)",s)
            if d:
                k,rest=d.group(1),d.group(2)
                if k in("if","ifdef","ifndef"):
                    if k=="if": cond=evalcond(rest,macros)
                    elif k=="ifdef": cond=rest.split()[0] in macros
                    else: cond=rest.split()[0] not in macros
                    stack.append([active and cond, cond, active]); active=stack[-1][0]
                elif k=="elif":
                    top=stack[-1]
                    cond = (not top[1]) and evalcond(rest,macros)
                    top[0]=top[2] and cond; top[1]=top[1] or cond; active=top[0]
                elif k=="else":
                    top=stack[-1]; top[0]=top[2] and not top[1]; top[1]=True; active=top[0]
                elif k=="endif":
                    if not stack: raise ValueError("unmatched #endif")
                    stack.pop(); active=stack[-1][0] if stack else True
                elif k=="define" and active:
                    m=re.match(r"(\w+)(?:\([^)]*\))?\s*(.*)",rest)
                    if m:
                        v=m.group(2).strip()
                        macros[m.group(1)] = int(v) if re.fullmatch(r"-?\d+",v) else 1
                elif k=="undef" and active: macros.pop(rest.strip(),None)
            out.append("")
        else: out.append(line if active else "")
    if stack: raise ValueError("unterminated #if")
    return "\n".join(out)

def balance(code):
    st=[];pairs={")":"(","]":"[","}":"{"}
    line=1
    for ch in code:
        if ch=="\n": line+=1
        if ch in "([{": st.append((ch,line))
        elif ch in ")]}":
            if not st or st[-1][0]!=pairs[ch]: return f"unbalanced '{ch}' at line {line}"
            st.pop()
    return None if not st else f"unclosed '{st[-1][0]}' from line {st[-1][1]}"

def check(path):
    text=open(path,errors="ignore").read(); ok=True
    for prof,wd,tr in itertools.product((0,1),(0,1),(0,1)):
        for neural in (0,1):
            macros=dict(BASE, CEMU_PROFILING=prof, CEMU_THREAD_WATCHDOG=wd, CEMU_JIT_TRACE=tr, CEMU_NEURAL=neural)
            try:
                err=balance(strip(preprocess(text,macros)))
            except ValueError as e: err=str(e)
            if err: ok=False; print(f"  FAIL {path} [prof={prof} wd={wd} trace={tr} neural={neural}]: {err}")
    return ok

if __name__=="__main__":
    files=sys.argv[1:] or DEFAULT_FILES
    good=all([check(f) for f in files])
    print("STATIC CHECK:", "OK (all 16 macro combinations balanced)" if good else "PROBLEMS FOUND")
    sys.exit(0 if good else 1)
