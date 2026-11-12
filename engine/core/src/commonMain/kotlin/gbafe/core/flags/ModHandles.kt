package gbafe.core.flags

/**
 * First handle given to mod-registered flags and variables. Handles below it are the game's own
 * ids (vanilla event-flag ids, vanilla variable indices); handles from here up address the mod
 * storage of [FlagRegistry] and [VarRegistry]. Chosen so every handle fits an unsigned 16-bit
 * script argument (ADR 0001, M5 amendment).
 */
public const val MOD_BASE: Int = 0x8000

/** Largest valid handle (u16 maximum); at most `MAX_HANDLE - MOD_BASE + 1` mod slots exist. */
public const val MAX_HANDLE: Int = 0xFFFF

/** Result of `resolve` for a name that is neither a vanilla nor a registered mod name. */
public const val UNRESOLVED: Int = -1

/** Longest accepted mod id or flag/variable name, in UTF-16 chars. */
internal const val MAX_NAME_CHARS: Int = 255

/**
 * Name bookkeeping shared by [FlagRegistry] and [VarRegistry]: the vanilla name table plus the
 * mod names in registration order. A mod name's slot is its position in that order; the handle
 * is `MOD_BASE + slot`. Slots are per-process only: persistence goes by name.
 */
internal class ModNameTable(private val kind: String, vanillaNames: Map<String, Int>) {
    private val vanilla = HashMap<String, Int>(vanillaNames)
    private val slotByName = HashMap<String, Int>()
    private val names = ArrayList<String>()
    private val owners = ArrayList<String>()

    init {
        for ((name, id) in vanillaNames) {
            require(id in 0 until MOD_BASE) { "vanilla $kind '$name' id $id is not below MOD_BASE" }
        }
    }

    /** Number of registered mod slots. */
    val count: Int get() = names.size

    /** The vanilla id of [name], or [UNRESOLVED]. */
    fun vanillaId(name: String): Int = vanilla[name] ?: UNRESOLVED

    /** Slot of the mod name [name], or [UNRESOLVED]. */
    fun slotOf(name: String): Int = slotByName[name] ?: UNRESOLVED

    /** Name registered in [slot]. */
    fun nameAt(slot: Int): String = names[slot]

    /** Id of the mod that registered [slot]. */
    fun ownerAt(slot: Int): String = owners[slot]

    /** The vanilla name to id pairs, in the order the game supplied them. */
    fun vanillaEntries(): Set<Map.Entry<String, Int>> = vanilla.entries

    /**
     * Allocates or returns the slot of [name] for [modId]. Re-registering the same name by the
     * same mod is idempotent; a vanilla name or another mod's name is rejected.
     */
    fun register(modId: String, name: String): Int {
        require(modId.isNotEmpty() && modId.length <= MAX_NAME_CHARS) { "bad mod id" }
        require(name.isNotEmpty() && name.length <= MAX_NAME_CHARS) { "bad $kind name" }
        require(name !in vanilla) { "$kind '$name' is a vanilla name" }
        val existing = slotByName[name]
        if (existing != null) {
            require(owners[existing] == modId) {
                "$kind '$name' already registered by mod '${owners[existing]}'"
            }
            return existing
        }
        val slot = names.size
        check(MOD_BASE + slot <= MAX_HANDLE) { "out of mod $kind handles (u16 limit)" }
        names.add(name)
        owners.add(modId)
        slotByName[name] = slot
        return slot
    }

    /** Slots owned by [modId], in registration order. */
    fun slotsOf(modId: String): List<Int> {
        val out = ArrayList<Int>()
        for (i in owners.indices) if (owners[i] == modId) out.add(i)
        return out
    }
}

/**
 * Minimal growable byte writer for the per-mod serialization formats (little-endian). Mod data
 * is not part of the original game; the format is this port's own.
 */
internal class ByteSink {
    private var buf = ByteArray(64)
    private var len = 0

    fun u8(v: Int) {
        ensure(1)
        buf[len++] = v.toByte()
    }

    fun u16(v: Int) {
        u8(v)
        u8(v shr 8)
    }

    fun i32(v: Int) {
        u16(v)
        u16(v shr 16)
    }

    fun string(s: String) {
        val b = s.encodeToByteArray()
        u16(b.size)
        ensure(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }
}

/** Bounds-checked little-endian reader matching [ByteSink]; failures are argument errors. */
internal class ByteSource(private val buf: ByteArray) {
    private var pos = 0

    fun u8(): Int {
        require(pos < buf.size) { "truncated mod data" }
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun i32(): Int = u16() or (u16() shl 16)

    fun string(): String {
        val n = u16()
        require(pos + n <= buf.size) { "truncated mod data" }
        val s = buf.decodeToString(pos, pos + n)
        pos += n
        return s
    }

    fun requireEnd() {
        require(pos == buf.size) { "trailing bytes in mod data" }
    }
}
