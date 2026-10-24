#!/usr/bin/env python3
"""Generate the committed decomp reference data (function list + known RAM symbols).

Run ONCE per decomp version and commit the output; scheduled sessions never need the decomp checkout
just to compute progress.

    python3 tools/gen_reference.py --decomp /path/to/fireemblem8u

Outputs (all under reference/):
    DECOMP_VERSION          repo URL + pinned commit the data was generated from
    decomp_functions.tsv    name, file, line, kind (c|asm), system  -- one row per decomp function
    iwram_symbols.tsv       name, address  -- explicit IWRAM addresses from sym_iwram.txt

Function detection is a heuristic C parser (top-level `type name(args) {` at brace depth 0, with
comments, strings and preprocessor lines stripped), plus THUMB_FUNC_START/ARM_FUNC_START labels in the
remaining .s files. Functions duplicated across #if NONMATCHING / #else branches are counted once.
The `system` column comes from SYSTEM_RULES below; it drives the per-system table in the README.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reference"

# Ordered (regex on src-relative path, system). First match wins. Keep in sync with ARCHITECTURE.md.
SYSTEM_RULES: list[tuple[str, str]] = [
    (r"^src/(m4a|soundwrapper|soundroom)", "audio"),
    (r"^src/(banim|efx|ekr)", "battle_anim"),
    (r"^src/mapanim", "map_anim"),
    (r"^src/(cp_|cpextra)", "ai"),
    (r"^src/(event|events|eventscr|eventcall|eventinfo|eventfx|eventwarpfx|eventcmd)", "events"),
    (r"^src/worldmap", "worldmap"),
    (r"^src/(bmsave|savedraw|savemenu|agb_sram)", "save"),
    (r"^src/sio", "link"),
    (r"^src/(prep|prepscreen)", "prep_screen"),
    (r"^src/(bmbattle|bmarena|bmtrade|bmitem|bmitemuse|bmusemind|bmmind|bmtrick|bmtrap|bmusailment|bmreliance|bmcontainer|bmshop|bmgold|bmarch)", "battle_logic"),
    (r"^src/(bm\.|bmmap|bmunit|bmphase|bmidoten|bmcamadjust|bmusort|bmudisp|bmpatharrowdisp|bmtarget|bmxfade|bmlib|bmdifficulty|bmguide|bmdebug|bmcommanddbg|bmio|mu\.|muctrl|fogmap|playerphase|unit_|cursor_hand|monstergen|chapterdata)", "battle_map"),
    (r"^src/(ui|menu|bmmenu|statscreen|helpbox|unitlistscreen|unitinfowindow|convoymenu|menuitempanel|player_interface|statbar|difficultymenu|extramenu|bksel|classchg|bonusclaim|gamerankings|uisupport|minimap|popup)", "menus"),
    (r"^src/(msg|fontgrp|classdisplayfont|cgtext|face|icon|portrait)", "text_face"),
    (r"^src/(opanim|opinfo|opsubtitle|titlescreen|ending|chapter_title|chapterintrofx|scene|cg\.|gameoverbgfx|mural|lightrune|whitecircular|emitstar|dancering|phasechange|spinning_arrow|trapfx|minefx|colorfade|koido|ctc|bb|ap|animedrv|sprite|spline|spellassoc)", "presentation"),
    (r"^src/(main|irq|hardware|proc|ramfunc|sysutil|time|rng|gamecontrol|crt0|libagbsyscall|rom_header|code_|fe3_dummy)", "core"),
    (r"^src/data", "data"),
    (r"^asm/", "core"),
]

KEYWORDS = {"if", "for", "while", "switch", "return", "sizeof", "else", "do", "case"}


def system_for(path: str) -> str:
    for rx, sysname in SYSTEM_RULES:
        if re.search(rx, path):
            return sysname
    return "misc"


def strip_c(src: str) -> str:
    """Remove comments, string/char literals and preprocessor lines, preserving newlines."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(c + c)
            out.append("\n" * src.count("\n", i, j))
            i = j + 1
        else:
            out.append(c)
            i += 1
    text = "".join(out)
    # drop preprocessor lines (with continuations)
    lines, cont = [], False
    for line in text.split("\n"):
        if cont or line.lstrip().startswith("#"):
            cont = line.rstrip().endswith("\\")
            lines.append("")
        else:
            lines.append(line)
    return "\n".join(lines)


FUNC_RX = re.compile(r"([A-Za-z_]\w*)\s*\(([^;{}()]|\([^()]*\))*\)\s*(__attribute__\s*\(\(.*?\)\)\s*)?$", re.S)


def c_functions(path: Path) -> list[tuple[str, int]]:
    text = strip_c(path.read_text(errors="replace"))
    found, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch == "{":
            if depth == 0:
                head = text[start:i]
                # header = text after the last ';' or '}' at depth 0
                m = FUNC_RX.search(head.rstrip())
                if m and m.group(1) not in KEYWORDS and "=" not in head[: m.start()].split("\n")[-1]:
                    pre = head[: m.start()].strip()
                    # must have a return type / qualifier before the name (excludes struct initialisers)
                    if pre and not pre.endswith(("=", ",", "(")) and not re.search(r"\b(struct|union|enum)\s*$", pre):
                        found.append((m.group(1), text.count("\n", 0, start + m.start()) + 1))
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                start = i + 1
        elif ch == ";" and depth == 0:
            start = i + 1
    return found


ASM_RX = re.compile(r"^\s*(?:THUMB_FUNC_START|ARM_FUNC_START|thumb_func_start|arm_func_start|NON_MATCHING_START)\s+(\w+)", re.M)


def asm_functions(path: Path) -> list[tuple[str, int]]:
    text = path.read_text(errors="replace")
    return [(m.group(1), text.count("\n", 0, m.start()) + 1) for m in ASM_RX.finditer(text)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--decomp", required=True, type=Path, help="fireemblem8u checkout")
    args = ap.parse_args()
    d: Path = args.decomp.resolve()
    if not (d / "src" / "main.c").exists():
        sys.exit(f"{d} does not look like fireemblem8u")
    commit = subprocess.run(["git", "-C", str(d), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    url = subprocess.run(["git", "-C", str(d), "remote", "get-url", "origin"], capture_output=True, text=True).stdout.strip()

    rows: dict[str, tuple[str, int, str, str]] = {}
    for p in sorted((d / "src").glob("*.c")):
        rel = p.relative_to(d).as_posix()
        for name, line in c_functions(p):
            rows.setdefault(name, (rel, line, "c", system_for(rel)))
    for p in sorted(list((d / "asm").rglob("*.s")) + list((d / "src").glob("*.s"))):
        rel = p.relative_to(d).as_posix()
        for name, line in asm_functions(p):
            rows.setdefault(name, (rel, line, "asm", system_for(rel)))

    OUT.mkdir(exist_ok=True)
    (OUT / "DECOMP_VERSION").write_text(f"{url}\n{commit}\n")
    with open(OUT / "decomp_functions.tsv", "w") as f:
        f.write("name\tfile\tline\tkind\tsystem\n")
        for name, (rel, line, kind, sysname) in sorted(rows.items(), key=lambda kv: (kv[1][0], kv[1][1])):
            f.write(f"{name}\t{rel}\t{line}\t{kind}\t{sysname}\n")

    sym = []
    sym_file = d / "sym_iwram.txt"
    if sym_file.exists():
        for m in re.finditer(r"\.\s*=\s*0x([0-9A-Fa-f]+)\s*;\s*(\w+)\s*=\s*\.\s*;", sym_file.read_text()):
            sym.append((m.group(2), 0x03000000 + int(m.group(1), 16)))
    with open(OUT / "iwram_symbols.tsv", "w") as f:
        f.write("name\taddress\n")
        for name, addr in sym:
            f.write(f"{name}\t0x{addr:08X}\n")

    by_sys: dict[str, int] = {}
    for _, (_, _, _, s) in rows.items():
        by_sys[s] = by_sys.get(s, 0) + 1
    print(f"{len(rows)} functions ({sum(1 for r in rows.values() if r[2] == 'asm')} asm), {len(sym)} IWRAM symbols")
    for s, c in sorted(by_sys.items(), key=lambda kv: -kv[1]):
        print(f"  {s:14s} {c}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
