package fe8.testing

import gba.extract.Symbols
import gba.testkit.WatchTrace
import java.io.File

/**
 * The GBA `sProcArray` (64 procs of [PROC_SIZE] bytes at EWRAM [ARRAY_ADDRESS]) decoded from a RAM dump, so
 * differential tests need not each carry their own copy of the layout. Slot `+0` is the script pointer (0 =
 * free slot), `+4` the script cursor (`proc_scrCur`, already past the running command), `+0x14` the parent.
 *
 * Build one from a full EWRAM dump ([ofEwram]), from a raw array slice ([ofSlice], e.g. one record of the
 * `procs` watch of `ram_trace_prologue`) or from a watch step ([ofWatch]). [names] maps ROM addresses to
 * decomp symbols ([romNames]); without it [Proc.name] is null and [find] by name fails.
 */
class ProcArrayDump private constructor(
    private val mem: ByteArray,
    private val base: Int,
    private val names: Map<Int, String>,
) {
    /** One slot. [offset] is the byte offset of the slot inside the dump this was built from. */
    inner class Proc(
        /** Index in the array, 0 until [SLOTS]. */
        val slot: Int,
    ) {
        /** Offset of the slot inside the underlying dump (EWRAM offset for [ofEwram]). */
        val offset: Int = base + slot * PROC_SIZE

        /** GBA address of the slot (what `proc_parent` of a child holds). */
        val address: Int = ARRAY_ADDRESS + slot * PROC_SIZE

        /** The script pointer at `+0`; 0 for a free slot. */
        val script: Int get() = u32(0)

        /** Decomp symbol of [script], or null if unknown. */
        val name: String? get() = names[script]

        /** Script position in commands: (`proc_scrCur` - script) / 8, the first command being 0. */
        val scrCur: Int get() = (u32(4) - script) / 8

        /** Unsigned byte at [field] bytes into the proc. */
        fun u8(field: Int): Int = mem[offset + field].toInt() and 0xFF

        /** Little-endian unsigned 16-bit value at [field] bytes into the proc. */
        fun u16(field: Int): Int = u8(field) or (u8(field + 1) shl 8)

        /** Little-endian 32-bit value at [field] bytes into the proc. */
        fun u32(field: Int): Int = u16(field) or (u16(field + 2) shl 16)
    }

    /** The slots in use (script pointer non-zero), in array order. */
    val live: List<Proc> = (0 until SLOTS).map { Proc(it) }.filter { it.script != 0 }

    /** Live procs running the script at ROM address [script]. */
    fun findAll(script: Int): List<Proc> = live.filter { it.script == script }

    /** The first live proc running [script], or null. */
    fun find(script: Int): Proc? = live.firstOrNull { it.script == script }

    /** The first live proc whose script is the decomp symbol [name]; needs the name table. */
    fun find(name: String): Proc? {
        val address = names.entries.firstOrNull { it.value == name }?.key
            ?: error("symbol '$name' not in the ROM name table")
        return find(address)
    }

    /** Factories and the layout. */
    companion object {
        /** `sProcArray` address (EWRAM). */
        const val ARRAY_ADDRESS = 0x02024E68

        /** Bytes per `struct Proc`. */
        const val PROC_SIZE = 0x6C

        /** Slots in the array. */
        const val SLOTS = 64

        /** Bytes of the whole array (the `procs` watch length, 0x1B00). */
        const val ARRAY_SIZE = SLOTS * PROC_SIZE

        private const val EWRAM = 0x02000000

        /** Decodes the array inside a full EWRAM dump (slot offsets are EWRAM offsets). */
        fun ofEwram(ewram: ByteArray, names: Map<Int, String> = emptyMap()) =
            ProcArrayDump(ewram, ARRAY_ADDRESS - EWRAM, names)

        /** Decodes a raw [ARRAY_SIZE]-byte slice starting at `sProcArray`. */
        fun ofSlice(bytes: ByteArray, names: Map<Int, String> = emptyMap()): ProcArrayDump {
            require(bytes.size >= ARRAY_SIZE) { "proc array slice is ${bytes.size} bytes, need $ARRAY_SIZE" }
            return ProcArrayDump(bytes, 0, names)
        }

        /** Decodes the record of the `procs` watch after emulated step [step]. */
        fun ofWatch(trace: WatchTrace, step: Int, names: Map<Int, String> = emptyMap()): ProcArrayDump {
            require(trace.address == ARRAY_ADDRESS) { "watch ${trace.name} is not the proc array" }
            return ofSlice(trace.step(step), names)
        }

        /**
         * ROM address to decomp symbol from the nm file named by the `fe8.nm` property or `FE8_NM_FILE`
         * environment variable (else `$FE8_ASSETS/fireemblem8.nm.txt`); empty if none is present.
         */
        fun romNames(): Map<Int, String> {
            val file = nmFile() ?: return emptyMap()
            return Symbols(file.readLines()).romNamesByAddress()
        }

        /**
         * The decomp nm file: the `fe8.nm` property or `FE8_NM_FILE`, else
         * `$FE8_ASSETS/fireemblem8.nm.txt` (CI, setup.sh), else the ROM repo checkout next to
         * this one (owner PC); null if none exists.
         */
        fun nmFile(): File? {
            val assets =
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
            val path = System.getProperty("fe8.nm") ?: System.getenv("FE8_NM_FILE")
            val rel = "Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt"
            return listOfNotNull(
                path?.let(::File),
                File(assets, "fireemblem8.nm.txt"),
                File("../$rel"),
                File("../../$rel"),
            ).firstOrNull { it.isFile }
        }
    }
}
