package gbafe.core.flags

/**
 * One row of [FlagRegistry.entries], for a debug menu.
 *
 * @property name flag name.
 * @property handle handle to pass to `check`/`set`/`clear`; `>= MOD_BASE` for mod flags.
 * @property modId owning mod id, or null for a vanilla flag.
 * @property value current value.
 */
public class FlagEntry(
    public val name: String,
    public val handle: Int,
    public val modId: String?,
    public val value: Boolean,
)

/**
 * Named event flags: the game's vanilla [EventFlags] plus growable mod flags, addressed by one
 * integer handle that fits a u16 script argument.
 *
 * Vanilla names come from a game-supplied table and their handle is the vanilla flag id (below
 * [MOD_BASE]). Mods [register] names at load time and get handles from [MOD_BASE] up; the mod
 * bits live in a separate bitset, so mod operations never touch the vanilla bytes (and are not
 * part of the vanilla save image). Handles are only stable within one process: persist mod flags
 * with [serializeMod]/[deserializeMod], which key by name.
 *
 * `check`/`set`/`clear` allocate nothing; [register], [resolve], [entries] and the
 * serialization calls are load-time/debug paths and may allocate.
 *
 * @property vanilla the vanilla flag memory; vanilla handles are flag ids routed to its
 *     `checkFlag`/`setFlag`/`clearFlag`.
 * @param vanillaNames the game's vanilla name to flag id table; ids must be below [MOD_BASE].
 */
public class FlagRegistry(
    public val vanilla: EventFlags,
    vanillaNames: Map<String, Int>,
) {
    private val table = ModNameTable("flag", vanillaNames)
    private var bits = ByteArray(16)

    /** Number of mod flags registered so far. */
    public val modCount: Int get() = table.count

    /**
     * Registers flag [name] for [modId] and returns its handle (`>= MOD_BASE`). Registering the
     * same name again for the same mod returns the same handle. Throws
     * [IllegalArgumentException] for an empty/over-long name, a vanilla name, or a name owned by
     * another mod, and [IllegalStateException] once the u16 handle space is exhausted.
     */
    public fun register(modId: String, name: String): Int {
        val slot = table.register(modId, name)
        val need = (slot shr 3) + 1
        if (need > bits.size) bits = bits.copyOf(maxOf(bits.size * 2, need))
        return MOD_BASE + slot
    }

    /**
     * Load-time lookup of [name]: vanilla names first (handle = flag id), then mod names.
     * Returns [UNRESOLVED] (-1) for an unknown name; use [resolveOrThrow] to fail instead.
     */
    public fun resolve(name: String): Int {
        val v = table.vanillaId(name)
        if (v != UNRESOLVED) return v
        val slot = table.slotOf(name)
        return if (slot == UNRESOLVED) UNRESOLVED else MOD_BASE + slot
    }

    /** Like [resolve] but throws [NoSuchElementException] for an unknown name. */
    public fun resolveOrThrow(name: String): Int {
        val h = resolve(name)
        if (h == UNRESOLVED) throw NoSuchElementException("unknown flag '$name'")
        return h
    }

    /** True if flag [handle] is set. Mod handles must have been registered. */
    public fun check(handle: Int): Boolean {
        if (handle < MOD_BASE) return vanilla.checkFlag(handle)
        val slot = modSlot(handle)
        return (bits[slot shr 3].toInt() and (1 shl (slot and 7))) != 0
    }

    /** Sets flag [handle]. */
    public fun set(handle: Int) {
        if (handle < MOD_BASE) {
            vanilla.setFlag(handle)
            return
        }
        val slot = modSlot(handle)
        val i = slot shr 3
        bits[i] = (bits[i].toInt() or (1 shl (slot and 7))).toByte()
    }

    /** Clears flag [handle]. */
    public fun clear(handle: Int) {
        if (handle < MOD_BASE) {
            vanilla.clearFlag(handle)
            return
        }
        val slot = modSlot(handle)
        val i = slot shr 3
        bits[i] = (bits[i].toInt() and (1 shl (slot and 7)).inv()).toByte()
    }

    /** All named flags (vanilla in table order, then mod flags in registration order). */
    public fun entries(): List<FlagEntry> {
        val out = ArrayList<FlagEntry>()
        for ((name, id) in table.vanillaEntries()) {
            out.add(FlagEntry(name, id, null, vanilla.checkFlag(id)))
        }
        for (slot in 0 until table.count) {
            val h = MOD_BASE + slot
            out.add(FlagEntry(table.nameAt(slot), h, table.ownerAt(slot), check(h)))
        }
        return out
    }

    /**
     * Serializes [modId]'s flags by name: u32 count, then per flag a u16 byte length, the UTF-8
     * name and one byte (0 or 1), all little-endian. Registration order does not matter to the
     * reader.
     */
    public fun serializeMod(modId: String): ByteArray {
        val slots = table.slotsOf(modId)
        val out = ByteSink()
        out.i32(slots.size)
        for (slot in slots) {
            out.string(table.nameAt(slot))
            out.u8(if (check(MOD_BASE + slot)) 1 else 0)
        }
        return out.toByteArray()
    }

    /**
     * Restores [modId]'s flags from [serializeMod] output: first clears all of the mod's flags,
     * then sets by name. Entries whose name this mod has not registered (in this run) are
     * ignored. Returns the number of entries applied. Malformed data throws
     * [IllegalArgumentException] before any flag is changed.
     */
    public fun deserializeMod(modId: String, data: ByteArray): Int {
        val src = ByteSource(data)
        val n = src.i32()
        require(n >= 0 && n <= data.size) { "bad entry count" }
        val handles = IntArray(n)
        val values = BooleanArray(n)
        var applied = 0
        for (i in 0 until n) {
            val name = src.string()
            values[i] = src.u8() != 0
            val slot = table.slotOf(name)
            handles[i] = if (slot != UNRESOLVED && table.ownerAt(slot) == modId) {
                applied++
                MOD_BASE + slot
            } else {
                UNRESOLVED
            }
        }
        src.requireEnd()
        for (slot in table.slotsOf(modId)) clear(MOD_BASE + slot)
        for (i in 0 until n) if (handles[i] != UNRESOLVED && values[i]) set(handles[i])
        return applied
    }

    private fun modSlot(handle: Int): Int {
        val slot = handle - MOD_BASE
        require(slot < table.count) { "unregistered mod flag handle" }
        return slot
    }
}
