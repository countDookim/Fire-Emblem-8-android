package fe8.game.event

import gbafe.extract.fe8.events.Fe8EventCommands
import gbafe.extract.fe8.events.Fe8EventCommands.FieldKind
import gbafe.extract.fe8.events.Fe8ScriptConstants
import gbafe.script.Arg
import gbafe.script.CommandEncoder
import gbafe.script.CommandSet
import gbafe.script.CommandSpec
import gbafe.script.SymbolResolver
import gbafe.script.resolveMask

/** One FE8 command form with the values the `.fe8s` command set and the encoder derive from it. */
private class Form(
    val command: Fe8EventCommands.Command,
    /** Header word; the sub-command nibble is 0 when [Fe8EventCommands.Command.subParam] is set. */
    val header: Int,
    val spec: CommandSpec,
    /** True for `clear_flag_at_slot2`: the flag field is implied to be 0xFFFF. */
    val flagAtSlot2: Boolean,
) {
    val words: Int get() = (header shr 4) and 0xF
    val fields = command.layout.filter { it.name != null }
}

/** The FE8 command forms by `.fe8s` name and decomp macro name. */
private object Fe8Forms {
    const val CLEAR_FLAG_MACRO = "EvtClearFlag"
    val byName = HashMap<String, Form>()
    private val byMacro = HashMap<String, Form>()

    init {
        val rows = ArrayList<Pair<Int, Fe8EventCommands.Command>>()
        for ((h, c) in Fe8EventCommands.byHeader) rows += h to c
        for ((h, c) in Fe8EventCommands.bySubParam) rows += h to c
        for ((h, c) in rows) {
            val header = if (c.subParam != null) h and 0xFFF0 else h
            val n = c.layout.count { it.name != null } + (if (c.subParam != null) 1 else 0)
            val form = Form(c, header, CommandSpec(c.name, n), false)
            byName[c.name] = form
            byMacro[c.macro] = form
            for (a in c.aliases) byMacro[a] = form
            if (c.macro == CLEAR_FLAG_MACRO) {
                val name = FLAG_AT_SLOT2_COMMAND
                byName[name] = Form(c, header, CommandSpec(name, n - 1), true)
            }
        }
    }

    fun lookup(name: String): Form? =
        byName[name] ?: byMacro[name]?.let { byName[it.command.name] }
}

/** Name of `clear_flag` whose flag is read from event slot 2 (decomp `EvtClearFlagAtSlot2`). */
public const val FLAG_AT_SLOT2_COMMAND: String = "clear_flag_at_slot2"

/**
 * The FE8 event commands as a `.fe8s` [CommandSet], built from [Fe8EventCommands]. A command takes
 * one argument per named layout field in word order, the sub-command nibble (if any) first, as
 * printed by `EventScriptConverter`; decomp macro names and aliases are accepted as well.
 */
public object Fe8CommandSet : CommandSet {
    /** Resolves [name] (canonical, macro name or alias) to its command spec, or null. */
    override fun lookup(name: String): CommandSpec? = Fe8Forms.lookup(name)?.spec

    /** Indices into the arguments of [spec] of the fields that take an event flag id. */
    public fun flagArgIndices(spec: CommandSpec): List<Int> {
        val f = Fe8Forms.byName[spec.name] ?: return emptyList()
        if (f.flagAtSlot2) return emptyList()
        val first = if (f.command.subParam != null) 1 else 0
        return f.fields.mapIndexedNotNull { i, fl ->
            if (fl.valueKind == Fe8ScriptConstants.KIND_EVENT_FLAG) first + i else null
        }
    }
}

/** Encodes FE8 commands to the original event words per the [Fe8EventCommands] layouts. */
public object Fe8CommandEncoder : CommandEncoder {
    /** Commands whose pointer names stand for a Thumb function (address with bit 0 set). */
    private val THUMB_MACROS = setOf("EvtAsmCall", "EvtAsmCallEnableSkip")
    private const val FLAG_ALL_BITS = 0xFFFF
    private const val LABEL_HEADER = 0x0820

    /** Number of u16 words a `label` command takes. */
    override val labelWordCount: Int = 2

    /** Number of u16 words the command [spec] encodes to. */
    override fun wordCount(spec: CommandSpec): Int = form(spec).words

    /** Writes `label` [number] at [pos] in [out]; returns the next position. */
    override fun encodeLabel(number: Int, out: IntArray, pos: Int) {
        out[pos] = LABEL_HEADER
        out[pos + 1] = number and 0xFFFF
    }

    /** Encodes one command at [pos] in [out] per its layout; returns the next position. */
    override fun encode(
        spec: CommandSpec,
        args: List<Arg>,
        symbols: SymbolResolver,
        out: IntArray,
        pos: Int,
    ) {
        val f = form(spec)
        var header = f.header
        var k = 0
        if (f.command.subParam != null) {
            header = header or (number(args[k++], null, false, symbols, 4).toInt() and 0xF)
        }
        out[pos] = header
        val thumb = f.command.macro in THUMB_MACROS
        for (field in f.fields) {
            val v = if (f.flagAtSlot2 && field.valueKind == Fe8ScriptConstants.KIND_EVENT_FLAG) {
                FLAG_ALL_BITS.toLong()
            } else {
                val asThumb = thumb && field.kind == FieldKind.POINTER
                number(args[k++], field.valueKind, asThumb, symbols, field.kind.bits)
            }
            put(out, pos + field.word, field, v)
        }
    }

    private fun form(spec: CommandSpec): Form =
        Fe8Forms.byName[spec.name]
            ?: throw IllegalArgumentException("unknown command '${spec.name}'")

    private fun number(
        arg: Arg,
        kind: String?,
        thumb: Boolean,
        symbols: SymbolResolver,
        bits: Int,
    ): Long {
        val v = when (arg) {
            is Arg.IntArg -> arg.value.toLong()
            is Arg.IdentArg -> {
                val r = symbols.resolveMask(arg.name, kind)
                require(r != SymbolResolver.UNRESOLVED) { "unresolved identifier '${arg.name}'" }
                if (thumb) r or 1L else r
            }
        }
        // Negative constants (CHARACTER_EVT_SLOT2 = -3) truncate to the field.
        require(v >= -(1L shl (bits - 1)) && v < (1L shl bits)) {
            "value $v does not fit $bits bits"
        }
        return v
    }

    private fun put(out: IntArray, w: Int, f: Fe8EventCommands.Field, v: Long) {
        when (f.kind) {
            FieldKind.U4 -> out[w] = out[w] or ((v.toInt() and 0xF) shl f.shift)
            FieldKind.U8 -> out[w] = out[w] or ((v.toInt() and 0xFF) shl f.shift)
            FieldKind.U16 -> out[w] = v.toInt() and 0xFFFF
            FieldKind.U32, FieldKind.POINTER -> {
                out[w] = v.toInt() and 0xFFFF
                out[w + 1] = (v.toInt() ushr 16) and 0xFFFF
            }
        }
    }
}
