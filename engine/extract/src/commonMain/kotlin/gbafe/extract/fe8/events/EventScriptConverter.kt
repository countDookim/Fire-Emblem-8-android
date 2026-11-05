package gbafe.extract.fe8.events

import gbafe.extract.hexUpper
import gbafe.script.ScriptConstants

/**
 * Event script converter v1: FE8 event bytecode to `.fe8s` text (docs/decisions/0001), one line
 * per original command, named and laid out by [Fe8EventCommands] (decomp `include/eventscript.h`
 * macros in snake_case).
 *
 * Encoding (decomp `include/event.h`, `_EvtCmd`): header u16 = `cmd shl 8 | len shl 4 | sub`, `len`
 * counted in u16 words including the header.
 *
 * Conversion covers exactly the bytes it is given (the script's size in the decomp symbols), with
 * no `return`/`end_all` heuristic. A command is printed as its name and one argument per named
 * [Fe8EventCommands.Field], in layout order (word order, so the sub-command nibble of a
 * [Fe8EventCommands.bySubParam] command comes first). Implicit zero words are omitted; bits no
 * field covers must be zero or the command is not representable and is printed as
 * `raw 0xHHHH ...` (also for unknown headers), so the text is always lossless.
 *
 * Argument forms:
 * - A field with a value kind ([Fe8EventCommands.Field.valueKind]) prints the constant name of its
 *   value (`CHARACTER_SETH`, `SONG_RAID`, `EVT_SLOT_2`, `EVFLAG_...`, menu bits as `A|B`) when
 *   [constants] has one; 16-bit values are also tried sign-extended (`CHARACTER_EVT_SLOT2` is -3).
 *   Unknown values stay numbers. Labels, text ids and the rest are always numbers.
 * - A 32-bit value that is the address of a ROM symbol prints as the symbol name. For an odd
 *   value (a Thumb function pointer, as `asm_call` takes) the symbol at the even address below
 *   prints instead: the name stands for the pointer with bit 0 set for `asm_call` arguments.
 * - The flag of `set_flag`/`check_flag`... being `0xFFFF` means "the flag number is in slot 2"
 *   (`src/eventscr.c`: negative argument). `clear_flag` prints as the decomp macro
 *   `EvtClearFlagAtSlot2` (`clear_flag_at_slot2`), the other flag commands, which have no macro,
 *   take the argument name [FLAG_AT_SLOT2].
 *
 * Numbers print in decimal below 0x100 and as `0x` + uppercase hex above.
 */
public object EventScriptConverter {
    /** Argument printed for a flag number of `0xFFFF` (the flag is read from event slot 2). */
    public const val FLAG_AT_SLOT2: String = "EVFLAG_AT_SLOT2"

    /** Name of `clear_flag` with the flag taken from slot 2 (decomp `EvtClearFlagAtSlot2`). */
    public const val CLEAR_FLAG_AT_SLOT2: String = "clear_flag_at_slot2"

    private const val ROM_BASE = 0x08000000
    private const val ROM_END = 0x0A000000
    private const val SLOT2_FLAG = 0xFFFF
    private const val CLEAR_FLAG_MACRO = "EvtClearFlag"

    /** Result: the `.fe8s` lines (no header) and the addresses of symbol-resolved pointers. */
    public class Result(
        /** One `.fe8s` command per original command, in order. */
        public val lines: List<String>,
        /** Addresses of the symbols printed as names (even for Thumb pointers). */
        public val referenced: List<Int>,
        /** Bytes of bytecode consumed: the length asked for. */
        public val byteLength: Int,
    )

    /**
     * Converts the [byteLength] bytes of script at ROM offset [offset]. [symbols] maps GBA
     * addresses (0x08xxxxxx) to names (only `get` is used), [constants] names values by kind.
     */
    public fun convert(
        rom: ByteArray,
        offset: Int,
        byteLength: Int,
        symbols: Map<Int, String> = emptyMap(),
        constants: ScriptConstants = Fe8ScriptConstants,
    ): Result {
        require(byteLength >= 0 && byteLength % 2 == 0) { "odd script length $byteLength" }
        val out = Printer(symbols, constants)
        var p = offset
        val end = offset + byteLength
        while (p < end) {
            val h = u16(rom, p)
            val len = (h shr 4) and 0xF
            require(len > 0) { "event at 0x${hexUpper(p)}: header 0x${hexUpper(h, 4)} length 0" }
            require(p + 2 * len <= end) {
                "event at 0x${hexUpper(p)}: command of $len words overruns the script"
            }
            out.lines += out.command(rom, p, h, len)
            p += 2 * len
        }
        return Result(out.lines, out.referenced, p - offset)
    }

    /** `.fe8s` text for named scripts: `event <name>` blocks with 4-space-indented commands. */
    public fun render(scripts: List<Pair<String, Result>>): String = buildString {
        append("# Converted from the FE8 ROM by gbafe.extract.fe8.events.EventScriptConverter ")
        append("(v1).\n")
        for ((name, r) in scripts) {
            append("\nevent ").append(name).append('\n')
            for (l in r.lines) append("    ").append(l).append('\n')
        }
    }

    private class Printer(
        private val symbols: Map<Int, String>,
        private val constants: ScriptConstants,
    ) {
        val lines = ArrayList<String>()
        val referenced = ArrayList<Int>()

        fun command(rom: ByteArray, p: Int, h: Int, len: Int): String {
            val c = Fe8EventCommands.find(h)
            if (c == null || !representable(rom, p, len, c)) return raw(rom, p, h, len)
            val args = ArrayList<String>()
            var name = c.name
            if (c.subParam != null) args += number(h and 0xF)
            for (f in c.layout) {
                if (f.name == null) continue
                val v = f.read(rom, p)
                if (f.valueKind == Fe8ScriptConstants.KIND_EVENT_FLAG && v == SLOT2_FLAG) {
                    if (c.macro == CLEAR_FLAG_MACRO) {
                        name = CLEAR_FLAG_AT_SLOT2
                    } else {
                        args += FLAG_AT_SLOT2
                    }
                } else {
                    args += field(f, v)
                }
            }
            return (listOf(name) + args).joinToString(" ")
        }

        private fun raw(rom: ByteArray, p: Int, h: Int, len: Int): String {
            val words = List(len) { "0x${hexUpper(u16(rom, p + 2 * it), 4)}" }
            return (listOf("raw") + words).joinToString(" ")
        }

        /**
         * True when every bit of the command that no named field covers is zero (and the sub
         * nibble of an exact-header row is fixed by the header), so printing the fields loses
         * nothing.
         */
        private fun representable(
            rom: ByteArray,
            p: Int,
            len: Int,
            c: Fe8EventCommands.Command,
        ): Boolean {
            val covered = IntArray(len)
            for (f in c.layout) {
                if (f.name == null) continue
                val words = if (f.kind.bits == 32) 2 else 1
                if (f.word + words > len) return false
                val mask = when (f.kind) {
                    Fe8EventCommands.FieldKind.U4 -> 0xF shl f.shift
                    Fe8EventCommands.FieldKind.U8 -> 0xFF shl f.shift
                    else -> 0xFFFF
                }
                covered[f.word] = covered[f.word] or mask
                if (words == 2) covered[f.word + 1] = 0xFFFF
            }
            for (w in 1 until len) if (u16(rom, p + 2 * w) and covered[w].inv() != 0) return false
            return true
        }

        private fun field(f: Fe8EventCommands.Field, v: Int): String {
            if (f.kind == Fe8EventCommands.FieldKind.POINTER) return pointer(v)
            val k = f.valueKind
            if (k == Fe8ScriptConstants.KIND_MENU_OVERRIDE) return menuBits(v)
            if (k != null) {
                val wide = f.kind == Fe8EventCommands.FieldKind.U16 && v >= 0x8000
                val named = constants.nameOf(k, v)
                    ?: if (wide) constants.nameOf(k, v - 0x10000) else null
                if (named != null) return named
            }
            return if (f.kind == Fe8EventCommands.FieldKind.U32) pointer(v) else number(v)
        }

        /** The names of the set bits of an unit-menu override mask joined by `|`, or the number. */
        private fun menuBits(v: Int): String {
            if (v == 0) return "0"
            val parts = ArrayList<String>()
            var rest = v
            var bit = 1
            while (rest != 0 && bit <= 0x8000) {
                if (rest and bit != 0) {
                    val n = constants.nameOf(Fe8ScriptConstants.KIND_MENU_OVERRIDE, bit)
                        ?: return number(v)
                    parts += n
                    rest = rest and bit.inv()
                }
                bit = bit shl 1
            }
            return if (rest == 0) parts.joinToString("|") else number(v)
        }

        /** A 32-bit value: a ROM symbol name (Thumb: the symbol below), else a number. */
        private fun pointer(v: Int): String {
            if (v < ROM_BASE || v >= ROM_END) return number(v)
            val exact = symbols[v]
            if (exact != null) {
                referenced += v
                return exact
            }
            val thumb = if (v and 1 != 0) symbols[v - 1] else null
            if (thumb != null) {
                referenced += v - 1
                return thumb
            }
            return number(v)
        }

        private fun number(v: Int) = if (v in 0..0xFF) v.toString() else "0x${hexUpper(v)}"
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
