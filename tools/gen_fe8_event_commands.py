#!/usr/bin/env python3
"""Generates the FE8 event command table from the decomp's include/eventscript.h.

usage: tools/gen_fe8_event_commands.py <fireemblem8u checkout> > engine/extract/src/commonMain/kotlin/gbafe/extract/fe8/events/Fe8EventCommands.kt

Every `#define Evt...(...)` / `WmEvt...(...)` whose body starts with `_EvtArg0` or `_EvtAutoCmdLen*` gives one row
keyed by the encoded header (cmd, len, sub); the first macro per key wins (aliases are listed after it). Names
are the macro names without the `Evt`/`Event` prefix in snake_case (`WmEvt` becomes a `wm_` prefix).

Each row also gets a word layout parsed from the macro body (u16 words, header = word 0):
`_EvtArg0(.., arg0)` fills word 1; every further top-level item is one 32-bit item = two words: `_EvtParams2(x, y)`
(two u16), `_EvtParams4(a, b, c, d)` (four u8), an `(EventListScr)`/`(EventScr)` cast (u32 pointer / u32) or a
literal 0. `_EvtSubParam16u8` / `_EvtSubParam16u4` split a word into bytes / nibbles. Words a macro leaves as
literal zero (including the implicit zero arg0 of `_EvtAutoCmdLen*`) become unnamed zero fields.

A macro parameter that is the whole sub-command nibble (`_EvtArg0(cmd, 2, (speed), ..)`) makes the row a
wildcard (`bySubParam`, keyed by `(cmd shl 8) or (len shl 4)`); a 1-bit parameter inside the sub expression
(`EvtMoveUnit`'s `modify`; `faction` has 3 values) enumerates one row per value.

Each named field also gets a value kind (the `Fe8ScriptConstants` kind its values are drawn from) decided by the
macro parameter's name (PARAM_KINDS, with OVERRIDES per macro): `pid` is a character, `jid` a class, `flag` an
event flag, `bgm`/`songid` a song, `slot`/`s1`/`s2` an event slot... The converter prints such values by name.

Commands 0x1F/0x20/0x23/0x24/0x28/0x35 have no macro in the decomp; their forms are taken from the handlers in
src/eventscr.c (EXTRA below) and appended after the decomp macros.
"""
import math
import re
import sys
from pathlib import Path

# (macro name with params, body) for commands the decomp has handlers for but no macro. Same syntax as eventscript.h.
EXTRA = [
    ("EvtMoveFace(x, y)", "_EvtArg0(EV_CMD_MOVEFACE, 2, 0, _EvtSubParam16u8((x), (y))),"),  # Event1F_: argv[0] low/high byte
    ("EvtClearTextBox", "_EvtAutoCmdLen2(EV_CMD_CLEARTEXTBOX),"),  # Event20_: ClearTalkBubble, no arguments
    ("EvtDisableMapDisp", "_EvtAutoCmdLen2(EV_CMD_23),"),  # Event23_DisaleMapDisp
    ("EvtEnableMapDisp", "_EvtAutoCmdLen2(EV_CMD_24),"),  # Event24_EnableMapDisp
    ("EvtChangeWeather(weather)", "_EvtArg0(EV_CMD_CHANGEWEATHER, 2, 0, (weather)),"),  # Event28_: pEventCurrent[1]
    # Event35_UnitClassChanging: argv[0] unit, argv[1] jid, argv[2] pid; subcmd 0 sets the class, 1 swaps classes.
    ("EvtChangeClass(unit, jid, pid)", "_EvtArg0(EV_CMD_CHANGECLASS, 4, 0, (unit)), _EvtParams2((jid), (pid)),"),
    ("EvtSwapClass(unit, jid, pid)", "_EvtArg0(EV_CMD_CHANGECLASS, 4, 1, (unit)), _EvtParams2((jid), (pid)),"),
]

# Macro names of EXTRA: the generated rows are marked `inferred = true`.
INFERRED = {re.match(r"\w+", head).group(0) for head, _ in EXTRA}

# Value kind (Fe8ScriptConstants.KIND_*) of a macro parameter by its name.
PARAM_KINDS = {
    "pid": "character", "pid_target": "character", "unit": "character",
    "jid": "class", "item": "item", "songid": "song", "bgm": "song", "chapter": "chapter",
    "bg": "background", "flag": "event_flag", "slot": "slot", "s1": "slot", "s2": "slot",
    "bitfile": "menu_override", "node": "wm_node", "node1": "wm_node", "node2": "wm_node",
    "path": "wm_path", "nation": "wm_nation", "wm_uid": "wm_mu",
}
# Per-macro exceptions to PARAM_KINDS: (macro, parameter) -> kind or None. Slot operations name their slots
# `to`/`a`/`b`; the "flag" of the ev-bit commands is an event-state bit index, not an event flag; EvtSwapClass
# passes a character in its `jid` (src/eventscr.c Event35_UnitClassChanging subcommand 1).
OVERRIDES = {
    ("EvtClearEvBits", "flag"): None,
    ("EvtSetEvBits", "flag"): None,
    ("EvtSwapClass", "jid"): "character",
}
for _op in ("ADD", "SUB", "MUL", "DIV", "MOD", "AND", "ORR", "XOR", "LSL", "LSR"):
    for _p in ("to", "a", "b"):
        OVERRIDES[("EvtSlot" + _op, _p)] = "slot"

# Name of the Kotlin constant (K_<name> in the generated object) for each kind.
KIND_CONSTS = {
    "character": "CHARACTER", "class": "CLASS", "item": "ITEM", "song": "SONG", "chapter": "CHAPTER",
    "background": "BACKGROUND", "event_flag": "EVENT_FLAG", "slot": "SLOT",
    "menu_override": "MENU_OVERRIDE", "wm_node": "WM_NODE", "wm_path": "WM_PATH",
    "wm_nation": "WM_NATION", "wm_mu": "WM_MU",
}


def kind_of(macro, param):
    """Value kind of a macro parameter, or None."""
    if (macro, param) in OVERRIDES:
        return OVERRIDES[(macro, param)]
    if param == "flag" and macro.startswith("WmEvt"):
        return None  # WmEvtMoveUnit's `flag` is not an event flag
    return PARAM_KINDS.get(param)


# Casts to (EventListScr) that are really plain 32-bit values (SVAL's value, a worldmap node id).
VALUE32 = {("EvtSetSlot", "value"), ("WmEvtLoadUnit", "node")}


def snake(name):
    name = re.sub(r"^WmEvt", "Wm_", name)
    name = re.sub(r"^(Evt|Event)", "", name)
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    name = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    return name.lower().replace("__", "_")


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", " ", s)
    return re.sub(r"//.*", "", s).strip()


def split_top(s):
    """Splits on top-level commas, trimming; drops empty items (the trailing comma)."""
    out, depth, cur = [], 0, []
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur).strip())
    return [x for x in out if x]


def unparen(e):
    e = e.strip()
    while e.startswith("(") and e.endswith(")"):
        depth = 0
        for i, ch in enumerate(e):
            depth += (ch == "(") - (ch == ")")
            if depth == 0 and i < len(e) - 1:
                return e
        e = e[1:-1].strip()
    return e


def call_args(e, fn):
    """Arguments of `fn(...)` when expression e is exactly that call, else None."""
    e = unparen(e)
    if e.startswith(fn + "(") and e.endswith(")"):
        return split_top(e[len(fn) + 1:-1])
    return None


def is_ident(e):
    return re.fullmatch(r"[A-Za-z_]\w*", e) is not None


class Layout:
    """Collects the fields of one macro as (name or None, storage kind, word, shift, value kind)."""

    def __init__(self, macro, params):
        self.macro, self.params, self.fields = macro, params, []

    def name(self, e):
        e = unparen(e)
        if is_ident(e) and e in self.params:
            return e
        if re.fullmatch(r"0|0x0+", e):
            return None
        raise ValueError("%s: unsupported field expression %r" % (self.macro, e))

    def sub(self, e, word, kind, shift):
        n = self.name(e)
        if n is not None:
            self.fields.append((n, kind, word, shift, kind_of(self.macro, n)))

    def word16(self, e, word):
        for fn, kind, width, count in (("_EvtSubParam16u8", "U8", 8, 2), ("_EvtSubParam16u4", "U4", 4, 4)):
            a = call_args(e, fn)
            if a is not None:
                assert len(a) == count, (self.macro, e)
                for i, x in enumerate(a):
                    self.sub(x, word, kind, i * width)
                return
        self.sub(e, word, "U16", 0)

    def item32(self, e, word):
        a = call_args(e, "_EvtParams2")
        if a is not None:
            self.word16(a[0], word)
            self.word16(a[1], word + 1)
            return
        a = call_args(e, "_EvtParams4")
        if a is not None:
            for i, x in enumerate(a):
                self.sub(x, word + i // 2, "U8", (i % 2) * 8)
            return
        m = re.fullmatch(r"\((EventListScr|EventScr)\)\s*(.+)", e.strip())
        if m:
            n = self.name(m.group(2))
            if n is not None:
                kind = "PTR" if m.group(1) == "EventListScr" and (self.macro, n) not in VALUE32 else "U32"
                self.fields.append((n, kind, word, 0, kind_of(self.macro, n)))
            elif m.group(1) == "EventListScr":
                self.fields.append((None, "PTR", word, 0, None))
            return
        if re.fullmatch(r"0|0x0+", e.strip()):
            return
        raise ValueError("%s: unsupported 32-bit item %r" % (self.macro, e))

    def finish(self, length):
        """Adds an unnamed zero U16 for every word no field covers."""
        covered = set()
        for _, kind, word, _, _ in self.fields:
            covered.add(word)
            if kind in ("U32", "PTR"):
                covered.add(word + 1)
        for w in range(1, length):
            if w not in covered:
                self.fields.append((None, "U16", w, 0, None))
        self.fields.sort(key=lambda f: (f[2], f[3]))
        return self.fields


def main(decomp):
    src = (Path(decomp) / "include/eventscript.h").read_text()
    joined = re.sub(r"\\\n", " ", src)
    consts = {}
    for m in re.finditer(r"\b(EV_CMD_\w+|EVT?SUBCMD_\w+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)", src):
        consts[m.group(1)] = int(m.group(2), 0)

    def val(tok):
        tok = tok.strip()
        if tok in consts:
            return consts[tok]
        m = re.fullmatch(r"(\w+)\s*-\s*(0x[0-9A-Fa-f]+|\d+)", tok)
        if m and m.group(1) in consts:
            return consts[m.group(1)] - int(m.group(2), 0)
        return int(tok, 0)

    macros = []
    for line in joined.splitlines():
        m = re.match(r"#define\s+((?:Wm)?Evt\w+|Event\w+)(\([^)]*\))?\s+(.*)", line)
        if m:
            macros.append((m.group(1), (m.group(2) or "()")[1:-1], m.group(3)))
    for head, body in EXTRA:
        m = re.fullmatch(r"(\w+)(?:\(([^)]*)\))?", head)
        macros.append((m.group(1), m.group(2) or "", body))

    rows = {}  # (cmd, len, sub) -> [(name, params, suffix, layout)]
    wild = {}  # (cmd, len) -> [(name, params, subParam, layout)]
    for name, params, body in macros:
        items = split_top(strip_comments(body))
        if not items:
            continue
        a = call_args(items[0], "_EvtArg0")
        auto = re.fullmatch(r"_EvtAutoCmdLen(\d)\(\s*([^)]+)\)", items[0])
        if a is not None and len(a) == 4:
            cmd, ln, sub, arg0 = a
        elif auto:
            cmd, ln, sub, arg0 = auto.group(2), auto.group(1), "0", "0"
        else:
            continue
        args = [p.strip() for p in params.split(",") if p.strip()]
        length = val(ln)
        lay = Layout(name, args)
        lay.word16(arg0, 1)
        for i, it in enumerate(items[1:]):
            lay.item32(it, 2 + 2 * i)
        if 2 + 2 * (len(items) - 1) != length:
            raise ValueError("%s: layout covers %d words, header says %d" % (name, 2 + 2 * (len(items) - 1), length))
        # A sub-command may embed a 1-bit macro parameter (EvtMoveUnit's `modify`): one row per value, the
        # parameter's name appended to the command name for value 1. A bare parameter is a wildcard row.
        bare = unparen(sub) if unparen(sub) in args else None
        inner = [x for x in args if re.search(r"\b%s\b" % re.escape(x), sub) and x != bare]
        if bare is not None:
            wild.setdefault((val(cmd), length), []).append(
                (name, [x for x in args if x != bare], bare, lay.finish(length)))
            continue
        # `faction` takes 0..2 (EVSUBCMD_CUSA + faction: blue, green, red); other parameters are 1-bit.
        sizes = [3 if x == "faction" else 2 for x in inner]
        for combo in range(math.prod(sizes)):
            vals, rest = [], combo
            for sz in sizes:
                vals.append(rest % sz)
                rest //= sz
            env = dict(consts, true=1, false=0)
            env.update({x: vals[i] for i, x in enumerate(inner)})
            try:
                key = (val(cmd), length, eval(sub, {}, env) & 0xF)
            except (ValueError, NameError, SyntaxError):
                break
            suffix = "".join(
                "_" + x + ("" if vals[i] == 1 else str(vals[i])) for i, x in enumerate(inner) if vals[i])
            rows.setdefault(key, []).append((name, [x for x in args if x not in inner], suffix, lay.finish(length)))

    def kstr(s):
        return "null" if s is None else '"%s"' % s

    def fields(fs):
        out = []
        for n, kind, word, shift, vk in fs:
            k = {"U4": "u4", "U8": "u8", "U16": "u16", "U32": "u32", "PTR": "ptr"}[kind]
            extra = ", valueKind = K_%s" % KIND_CONSTS[vk] if vk is not None else ""
            out.append("%s(%s, %d%s%s)" % (k, kstr(n), word, ", %d" % shift if shift else "", extra))
        return "listOf(%s)" % ", ".join(out)

    entries = []  # (header, kotlin Command(...) expression)
    for (cmd, ln, sub), forms in sorted(rows.items()):
        name, args, suffix, lay = forms[0]
        al = ", ".join('"%s"' % x[0] for x in forms[1:])
        ps = ", ".join('"%s"' % x for x in args)
        inferred = ", inferred = true" if name in INFERRED else ""
        entries.append(("0x%04X" % ((cmd << 8) | (ln << 4) | sub),
                        'Command("%s", "%s", listOf(%s), listOf(%s), %s%s)'
                        % (snake(name) + suffix, name, ps, al, fields(lay), inferred)))
    wentries = []
    for (cmd, ln), forms in sorted(wild.items()):
        name, args, bare, lay = forms[0]
        al = ", ".join('"%s"' % x[0] for x in forms[1:])
        ps = ", ".join('"%s"' % x for x in args)
        wentries.append(("0x%04X" % ((cmd << 8) | (ln << 4)),
                         'Command("%s", "%s", listOf(%s), listOf(%s), %s, "%s")' % (snake(name), name, ps, al, fields(lay), bare)))

    chunk = 48
    out = []
    out.append("package gbafe.extract.fe8.events\n")
    out.append("// GENERATED by tools/gen_fe8_event_commands.py from fireemblem8u include/eventscript.h.\n// Do not edit.\n")
    out.append("""/**
 * FE8 event commands keyed by header `(cmd shl 8) or (len shl 4) or sub`; `len` counts u16 words
 * including the header. [byHeader] has one row per exact header, [bySubParam] the commands whose
 * sub-command nibble is a macro parameter (keyed by header with sub 0); [find] looks in both.
 */""")
    out.append("public object Fe8EventCommands {")
    out.append("""    /** Storage kind of a [Field]. */
    public enum class FieldKind(
        /** Width in bits. */
        public val bits: Int,
    ) {
        /** Nibble of a u16 word (`_EvtSubParam16u4`). */
        U4(4),

        /** Byte of a u16 word (`_EvtSubParam16u8`, `_EvtParams4`). */
        U8(8),

        /** A whole u16 word. */
        U16(16),

        /** Two words, little-endian (`(EventScr)` cast). */
        U32(32),

        /** Two words holding a ROM address (`(EventListScr)` cast). */
        POINTER(32),
    }

    /**
     * One field of a command: [name] is the macro parameter it carries, or null for an implicit
     * zero word the macro fills with a literal. It sits in u16 word [word] (0 is the header) at bit
     * [shift] (sub-word kinds only); 32-bit kinds span [word] (low half) and `word + 1`.
     */
    public class Field(
        /** Macro parameter name; null for an implicit zero. */
        public val name: String?,
        /** Storage kind. */
        public val kind: FieldKind,
        /** u16 word index from the start of the command; 0 is the header. */
        public val word: Int,
        /** Bit position inside the word for sub-word kinds, else 0. */
        public val shift: Int,
        /**
         * The [Fe8ScriptConstants] kind (`Fe8ScriptConstants.KIND_*`) this field's values are
         * drawn from, or null for plain numbers (also for ids and text ids).
         */
        public val valueKind: String? = null,
    ) {
        /**
         * Reads this field from [rom] for the command starting at byte [commandOffset] (no
         * allocation).
         */
        public fun read(rom: ByteArray, commandOffset: Int): Int {
            val o = commandOffset + 2 * word
            val lo = (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
            return when (kind) {
                FieldKind.U16 -> lo
                FieldKind.U8 -> (lo shr shift) and 0xFF
                FieldKind.U4 -> (lo shr shift) and 0xF
                FieldKind.U32, FieldKind.POINTER -> {
                    val hi = (rom[o + 2].toInt() and 0xFF) or ((rom[o + 3].toInt() and 0xFF) shl 8)
                    lo or (hi shl 16)
                }
            }
        }
    }

    /**
     * One command form: [name] (snake_case macro name), decomp [macro], its [params], [aliases] and
     * word [layout]. [inferred] marks the few commands the decomp has handlers but no macro for;
     * their name and layout are ours, derived from the handler in `src/eventscr.c`.
     */
    public class Command(
        /** `.fe8s` command name. */
        public val name: String,
        /** Decomp macro it comes from. */
        public val macro: String,
        /** The macro's parameter names (without [subParam]). */
        public val params: List<String>,
        /** Other macros encoding the same header. */
        public val aliases: List<String>,
        /** Fields in word order; every word after the header is covered by at least one field. */
        public val layout: List<Field>,
        /** Macro parameter that is the header's sub-command nibble, or null if the sub is fixed. */
        public val subParam: String? = null,
        /** True when [name] and [layout] are inferred from the handler, not from a decomp macro. */
        public val inferred: Boolean = false,
    )
""")
    for kk, cc in KIND_CONSTS.items():
        out.append("    private const val K_%s = Fe8ScriptConstants.KIND_%s" % (cc, cc))
    out.append("")
    out.append("    private fun u4(name: String?, word: Int, shift: Int = 0, valueKind: String? = null) =")
    out.append("        Field(name, FieldKind.U4, word, shift, valueKind)")
    out.append("    private fun u8(name: String?, word: Int, shift: Int = 0, valueKind: String? = null) =")
    out.append("        Field(name, FieldKind.U8, word, shift, valueKind)")
    out.append("    private fun u16(name: String?, word: Int, valueKind: String? = null) =")
    out.append("        Field(name, FieldKind.U16, word, 0, valueKind)")
    out.append("    private fun u32(name: String?, word: Int, valueKind: String? = null) =")
    out.append("        Field(name, FieldKind.U32, word, 0, valueKind)")
    out.append("    private fun ptr(name: String?, word: Int, valueKind: String? = null) =")
    out.append("        Field(name, FieldKind.POINTER, word, 0, valueKind)\n")
    parts = [entries[i:i + chunk] for i in range(0, len(entries), chunk)]
    for i, part in enumerate(parts):
        out.append("    private fun exact%d(m: MutableMap<Int, Command>) {" % i)
        for h, c in part:
            out.append("        m[%s] = %s" % (h, c))
        out.append("    }\n")
    out.append("    private fun wildcards(m: MutableMap<Int, Command>) {")
    for h, c in wentries:
        out.append("        m[%s] = %s" % (h, c))
    out.append("    }\n")
    out.append("    /** Header word → command. */")
    out.append("    public val byHeader: Map<Int, Command> = HashMap<Int, Command>().also { m ->")
    for i in range(len(parts)):
        out.append("        exact%d(m)" % i)
    out.append("    }\n")
    out.append("    /** Commands whose sub-command nibble is a parameter: `(cmd shl 8) or (len shl 4)` → command. */")
    out.append("    public val bySubParam: Map<Int, Command> = HashMap<Int, Command>().also { wildcards(it) }\n")
    out.append("    /**")
    out.append("     * The command for [header]: an exact [byHeader] row, else the [bySubParam] row of its cmd and")
    out.append("     * len.")
    out.append("     */")
    out.append("    public fun find(header: Int): Command? = byHeader[header] ?: bySubParam[header and 0xFFF0]")
    out.append("}")
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
