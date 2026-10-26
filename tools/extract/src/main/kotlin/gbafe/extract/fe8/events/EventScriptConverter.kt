package gbafe.extract.fe8.events

/**
 * Event script converter v0 (M2-15): FE8 event bytecode → `.fe8s` text (docs/decisions/0001), one line per
 * original command, named by [Fe8EventCommands] (decomp `include/eventscript.h` macros in snake_case).
 *
 * Encoding (decomp `include/event.h`, `_EvtCmd`): header u16 = `cmd shl 8 | len shl 4 | sub`, `len` counted in
 * u16 words including the header. v0 prints argument words as u16, except that an aligned word pair after the
 * first argument becomes one u32 when it is a ROM address (printed as its symbol name when [symbols] knows it)
 * or the command is `set_slot` (`SVAL(slot, value)` takes a 32-bit value). So a value > 0xFFFF or a name always
 * means two words. A command without parameters omits its (zero) argument word. Unknown headers become
 * `raw 0xHHHH ...` so nothing is lost.
 *
 * Conversion stops at `return`/`end_all` unless a `label` that some `goto`/branch targets is still unseen.
 */
public object EventScriptConverter {
    private const val CMD_LABEL = 0x08
    private val JUMPS = setOf(0x09, 0x0C) // goto, branches: first argument word is a label id

    /** Result: the `.fe8s` lines (no header) and the ROM addresses of every symbol-resolved pointer. */
    public class Result(
        /** One `.fe8s` command per original command, in order. */
        public val lines: List<String>,
        /** GBA addresses printed as symbol names. */
        public val referenced: List<Int>,
        /** Bytes of bytecode consumed. */
        public val byteLength: Int,
    )

    /** Converts the script at ROM offset [offset]; [symbols] maps GBA addresses (0x08xxxxxx) to names. */
    public fun convert(rom: ByteArray, offset: Int, symbols: Map<Int, String> = emptyMap(), maxWords: Int = 0x4000): Result {
        val lines = ArrayList<String>()
        val referenced = ArrayList<Int>()
        val wanted = HashSet<Int>()
        val seen = HashSet<Int>()
        var p = offset
        while ((p - offset) / 2 < maxWords) {
            val h = u16(rom, p)
            val cmd = h shr 8
            val len = (h shr 4) and 0xF
            require(len > 0) { "event at 0x%X: header 0x%04X with length 0".format(p, h) }
            val words = List(len - 1) { u16(rom, p + 2 + 2 * it) }
            val c = Fe8EventCommands.byHeader[h]
            val args = ArrayList<String>()
            if (words.isNotEmpty() && !(c != null && c.params.isEmpty() && words.size == 1 && words[0] == 0)) args += fmt16(words[0])
            var k = 1
            while (k < words.size) {
                val v = if (k + 1 < words.size) words[k] or (words[k + 1] shl 16) else -1
                if (k + 1 < words.size && (v ushr 25 == 4 || c?.name == "set_slot")) {
                    val name = symbols[v]
                    if (name != null) referenced += v
                    args += name ?: fmt32(v)
                    k += 2
                } else args += fmt16(words[k++])
            }
            if (cmd == CMD_LABEL && words.isNotEmpty()) seen += words[0]
            if (cmd in JUMPS && words.isNotEmpty()) wanted += words[0]
            lines += if (c != null) (listOf(c.name) + args).joinToString(" ") else (listOf("raw", "0x%04X".format(h)) + words.map { "0x%04X".format(it) }).joinToString(" ")
            p += 2 * len
            if (c != null && (c.name == "return" || c.name == "end_all") && (wanted - seen).isEmpty()) break
        }
        return Result(lines, referenced, p - offset)
    }

    /** `.fe8s` text for named scripts: `event <name>` blocks with 4-space-indented commands. */
    public fun render(scripts: List<Pair<String, Result>>): String = buildString {
        append("# Converted from the FE8 ROM by gbafe.extract.fe8.events.EventScriptConverter (v0).\n")
        for ((name, r) in scripts) {
            append("\nevent ").append(name).append('\n')
            for (l in r.lines) append("    ").append(l).append('\n')
        }
    }

    private fun fmt16(v: Int) = if (v < 0x100) v.toString() else "0x%X".format(v)
    private fun fmt32(v: Int) = if (v in 0..0xFF) v.toString() else "0x%X".format(v.toLong() and 0xFFFFFFFFL)
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
