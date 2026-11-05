#!/usr/bin/env python3
"""Generates the FE8 event-script constant tables from the decomp headers.

usage: tools/gen_fe8_script_constants.py <fireemblem8u checkout> > engine/extract/src/commonMain/kotlin/gbafe/extract/fe8/events/Fe8ScriptConstants.kt

Reads the enums of include/constants/{characters,classes,items,songs,chapters,backgrounds,event-flags,worldmap}.h,
`EventSlotIdx` (include/event.h) and `event3D_menu1override_bitfiles` (include/eventscript.h). One kind per
group; names keep the decomp spelling. Event flags additionally get
  - EVFLAG_EXTRA_UNIT_0..9, expanded from the `EVFLAG_EXTRA_UNIT(i)` macro (origin DERIVED), and
  - EVFLAG_<n> for every id from 0 to the highest named flag that the headers leave unnamed (origin FILLER).
"""
import re
import sys
import textwrap
from pathlib import Path

# (kind, header, enum-name filter or None, name prefixes that belong to the kind)
SOURCES = [
    ("character", "include/constants/characters.h", None, ("CHARACTER_",)),
    ("class", "include/constants/classes.h", None, ("CLASS_",)),
    ("item", "include/constants/items.h", None, ("ITEM_",)),
    ("song", "include/constants/songs.h", None, ("SONG_",)),
    ("chapter", "include/constants/chapters.h", None, ("CHAPTER_",)),
    ("background", "include/constants/backgrounds.h", None, ("BG_",)),
    ("event_flag", "include/constants/event-flags.h", None, ("EVFLAG_",)),
    ("wm_node", "include/constants/worldmap.h", None, ("NODE_", "WM_NODE_")),
    ("wm_path", "include/constants/worldmap.h", None, ("WM_PATH_",)),
    ("wm_nation", "include/constants/worldmap.h", None, ("WM_NATION_",)),
    ("wm_mu", "include/constants/worldmap.h", None, ("WM_MU_",)),
    ("slot", "include/event.h", "EventSlotIdx", ("EVT_SLOT_",)),
    ("menu_override", "include/eventscript.h", "event3D_menu1override_bitfiles", ("EVENT_MENUOVERRIDE_",)),
]

DOC = {
    "character": "Character ids (`CHARACTER_*`), including the event pseudo ids (`CHARACTER_EVT_*`, negative).",
    "class": "Class ids (`CLASS_*`).",
    "item": "Item ids (`ITEM_*`).",
    "song": "Song and sound-effect ids (`SONG_*`).",
    "chapter": "Chapter ids (`CHAPTER_*`).",
    "background": "Text background ids (`BG_*`).",
    "event_flag": "Event flag ids (`EVFLAG_*`), with derived and filler names.",
    "wm_node": "World map node ids (`NODE_*` and `WM_NODE_*`).",
    "wm_path": "World map path ids (`WM_PATH_*`).",
    "wm_nation": "World map highlighted-nation ids (`WM_NATION_*`).",
    "wm_mu": "World map sprite slots (`WM_MU_*`).",
    "slot": "Event slot indices (`EVT_SLOT_*`).",
    "menu_override": "Menu override bit masks (`EVENT_MENUOVERRIDE_*`).",
}


def kdoc(text, indent="    "):
    """A KDoc comment for `text` at `indent`, wrapped to 100 columns."""
    one = "%s/** %s */" % (indent, text)
    if len(one) <= 100:
        return one
    lines = textwrap.wrap(text, 100 - len(indent) - 3)
    return "\n".join([indent + "/**"] + ["%s * %s" % (indent, l) for l in lines] + [indent + " */"])


def strip(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))


def enums(text):
    """Yields (enum name or None, [(NAME, value)]) for every enum in header text."""
    text = strip(text)
    for m in re.finditer(r"\benum\s*(\w*)\s*\{(.*?)\}", text, flags=re.S):
        env, out, nxt = {}, [], 0
        for item in m.group(2).split(","):
            item = item.strip()
            if not item:
                continue
            mm = re.fullmatch(r"(\w+)\s*(?:=\s*(.+))?", item, flags=re.S)
            name, expr = mm.group(1), mm.group(2)
            if expr is not None:
                expr = expr.strip()
                if not re.fullmatch(r"[\w\s<>()+\-|&x]*", expr):
                    raise ValueError("%s: unsupported expression %r" % (name, expr))
                nxt = eval(expr, {"__builtins__": {}}, env)
            env[name] = nxt
            out.append((name, nxt))
            nxt += 1
        yield m.group(1) or None, out


def main(decomp):
    root = Path(decomp)
    tables = {}
    for kind, header, enum_name, prefixes in SOURCES:
        entries = []
        for name, items in enums((root / header).read_text()):
            if enum_name is not None and name != enum_name:
                continue
            entries += [(n, v) for n, v in items if n.startswith(prefixes)]
        if not entries:
            raise ValueError("no constants for %s in %s" % (kind, header))
        tables[kind] = [(n, v, "DECOMP") for n, v in entries]

    flags = tables["event_flag"]
    flag_src = (root / "include/constants/event-flags.h").read_text()
    base = dict((n, v) for n, v, _ in flags)
    m = re.search(r"#define\s+EVFLAG_EXTRA_UNIT\(i\)\s*\(EVFLAG_EXTRA_UNIT_BASE\s*\+\s*\(i\)\)", flag_src)
    count = re.search(r"EVFLAG_EXTRA_UNIT_BASE\s*=\s*\d+,\s*/\*\s*(\d+) extra unit", flag_src)
    if not (m and count):
        raise ValueError("EVFLAG_EXTRA_UNIT macro not found")
    for i in range(int(count.group(1))):
        flags.append(("EVFLAG_EXTRA_UNIT_%d" % i, base["EVFLAG_EXTRA_UNIT_BASE"] + i, "DERIVED"))
    used = {v for _, v, _ in flags}
    top = max(v for n, v, _ in flags if not n.startswith("EVFLAG_EXTRA_UNIT_"))
    names = {n for n, _, _ in flags}
    for n in range(top + 1):
        if n not in used and "EVFLAG_%d" % n not in names:
            flags.append(("EVFLAG_%d" % n, n, "FILLER"))

    out = []
    out.append("package gbafe.extract.fe8.events\n")
    out.append("import gbafe.script.ConstantOrigin")
    out.append("import gbafe.script.ConstantTable")
    out.append("import gbafe.script.ScriptConstants")
    out.append("import gbafe.script.TableScriptConstants\n")
    out.append("// GENERATED by tools/gen_fe8_script_constants.py from fireemblem8u include/constants/*.h,")
    out.append("// include/event.h and include/eventscript.h. Do not edit.\n")
    out.append("""/**
 * FE8 event-script constants by kind (see the `KIND_*` names); names are the decomp's, ids the
 * game's. Unnamed event flag ids are `EVFLAG_<n>` ([ConstantOrigin.FILLER]);
 * `EVFLAG_EXTRA_UNIT_<i>` is expanded from a macro ([ConstantOrigin.DERIVED]). Mods layer their own
 * constants with [gbafe.script.MutableScriptConstants].
 */""")
    out.append("public object Fe8ScriptConstants : ScriptConstants {")
    for kind in tables:
        out.append(kdoc(DOC[kind]))
        out.append('    public const val KIND_%s: String = "%s"\n' % (kind.upper(), kind))
    out.append("    private val set = TableScriptConstants(")
    out.append("        mapOf(")
    for kind in tables:
        out.append("            KIND_%s to %s()," % (kind.upper(), "table" + "".join(p.title() for p in kind.split("_"))))
    out.append("        ),")
    out.append("    )\n")
    out.append("    /** The value of [name] in [kind], or `NONE` if unknown. */")
    out.append("    override fun valueOf(kind: String, name: String): Int = set.valueOf(kind, name)\n")
    out.append("    /** The primary name of [value] in [kind], or null if none. */")
    out.append("    override fun nameOf(kind: String, value: Int): String? = set.nameOf(kind, value)\n")
    out.append("    /** The table of [kind] (names, values and origins), or null for an unknown kind. */")
    out.append("    public fun table(kind: String): ConstantTable? = set.table(kind)\n")
    out.append(kdoc("Where [name] of [kind] comes from; [ConstantOrigin.DECOMP] for a plain header name."))
    out.append("    public fun originOf(kind: String, name: String): ConstantOrigin =")
    out.append("        set.table(kind)?.originOf(name) ?: ConstantOrigin.DECOMP\n")
    for kind, entries in tables.items():
        fn = "table" + "".join(p.title() for p in kind.split("_"))
        out.append("    private fun %s(): ConstantTable = ConstantTable(" % fn)
        out.append("        arrayOf(")
        for n, _, _ in entries:
            out.append('            "%s",' % n)
        out.append("        ),")
        out.append("        intArrayOf(")
        for i in range(0, len(entries), 8):
            out.append("            " + ", ".join(str(v) for _, v, _ in entries[i:i + 8]) + ",")
        out.append("        ),")
        special = [(n, o) for n, _, o in entries if o != "DECOMP"]
        if special:
            out.append("        mapOf(")
            for n, o in special:
                out.append('            "%s" to ConstantOrigin.%s,' % (n, o))
            out.append("        ),")
        out.append("    )\n")
    out[-1] = out[-1].rstrip("\n")
    out.append("}")
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
