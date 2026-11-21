#!/usr/bin/env python3
"""Does every Dear ImGui name that Cemu's sources use exist in the imgui you installed? (functions ImGui::X, enum values ImGuiXxx_Yyy,
io.Member, font->Member, ImXxx types.) A name check, not a full compile, but it catches exactly the kind of break that a wrong imgui
version causes. Usage: python3 tools/deps/check_imgui_symbols.py [src_dir] [imgui_dir]"""
import os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from deps_common import *

ENUM = re.compile(r'\b(ImGui(?:Col|Cond|Key|NavInput|StyleVar|WindowFlags|ConfigFlags|BackendFlags|MouseButton|MouseCursor|InputTextFlags|TabBarFlags|TabItemFlags|TableFlags|TreeNodeFlags|ComboFlags|SelectableFlags|HoveredFlags|FocusedFlags|Dir|DataType|SliderFlags|ColorEditFlags|PopupFlags)_\w+)')
FUNC = re.compile(r'\bImGui::(\w+)')
IOM = re.compile(r'\bio\s*\.\s*(\w+)')
FONTM = re.compile(r'\b\w*[Ff]ont\w*\s*->\s*(\w+)')
TYPES = re.compile(r'\b(Im(?:Vec2|Vec4|Font|FontAtlas|FontConfig|DrawList|DrawData|DrawCmd|DrawVert|Color|U32|U8|U16|U64|S32|Wchar|Wchar16|Wchar32|TextureID|GuiIO|GuiStyle|GuiContext|GuiViewport|FontGlyph|FontGlyphRangesBuilder|DrawChannel|DrawListSplitter))\b')


def sources_using_imgui(src):
    for dp, dn, fn in os.walk(src):
        for f in fn:
            if f.endswith((".cpp", ".h", ".hpp", ".mm")):
                p = os.path.join(dp, f)
                t = read_text(p)
                if re.search(r'#\s*include\s*[<"][^>"]*imgui', t, re.I) or "ImGui::" in t:
                    yield p, t


def scan(src, imgui_dir):
    hdr = read_text(os.path.join(imgui_dir, "imgui.h")) + read_text(os.path.join(imgui_dir, "imgui_internal.h"))
    words = set(re.findall(r'\w+', strip_comments(hdr)))
    # members are looked up in the right struct, not just anywhere in the header
    io_members = set(re.findall(r'\w+', struct_body(hdr, "ImGuiIO")))
    font_members = set()
    for s in ("ImFont", "ImFontConfig", "ImFontAtlas", "ImFontGlyph"):
        font_members |= set(re.findall(r'\w+', struct_body(hdr, s)))
    missing = {}
    for p, t in sources_using_imgui(src):
        if os.sep + "imgui_impl_" in p:
            continue  # the backends are Cemu's own copies and use imgui internals; they are checked by compiling in CI
        for ln, line in enumerate(t.splitlines(), 1):
            s = line.split("//")[0]
            for pat, kind in ((FUNC, "ImGui::"), (ENUM, ""), (IOM, "io."), (FONTM, "font->"), (TYPES, "")):
                for m in pat.finditer(s):
                    name = m.group(1)
                    known = io_members if kind == "io." else (font_members if kind == "font->" else words)
                    if name not in known:
                        missing.setdefault((kind + name), []).append("%s:%d" % (os.path.relpath(p, src), ln))
    return missing


def main(argv):
    src = argv[0] if len(argv) > 0 else os.path.join(ROOT, "src")
    imgui = argv[1] if len(argv) > 1 else os.path.join(ROOT, "dependencies", "imgui")
    if not os.path.isfile(os.path.join(imgui, "imgui.h")):
        print("imgui non trovato in", imgui)
        return 1
    m = scan(src, imgui)
    if not m:
        print("imgui: tutti i nomi usati da Cemu esistono (%s)" % ".".join(map(str, imgui_version(read_text(os.path.join(imgui, 'imgui.h'))) or ("?",))))
        return 0
    print("Nomi usati da Cemu che NON esistono in questo imgui:")
    for name, where in sorted(m.items()):
        print("  %-34s %s" % (name, ", ".join(where[:3])))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
