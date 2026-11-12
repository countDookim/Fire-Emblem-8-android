package gbafe.core.flags

/**
 * Backing store for a game's vanilla variables, addressed by index. No FE8 store is plugged in
 * yet (the original's script variables are the `gEventSlots`); only tests use [IntArrayVarStore].
 */
public interface VarStore {
    /** Value of vanilla variable [index]. */
    public fun get(index: Int): Int

    /** Sets vanilla variable [index] to [value]. */
    public fun set(index: Int, value: Int)
}

/**
 * [VarStore] over a plain [IntArray] of [size] zero-initialised variables.
 *
 * @param size number of variables.
 */
public class IntArrayVarStore(size: Int) : VarStore {
    private val values = IntArray(size)

    override fun get(index: Int): Int = values[index]

    override fun set(index: Int, value: Int) {
        values[index] = value
    }
}

/**
 * One row of [VarRegistry.entries], for a debug menu.
 *
 * @property name variable name.
 * @property handle handle to pass to `get`/`set`; `>= MOD_BASE` for mod variables.
 * @property modId owning mod id, or null for a vanilla variable.
 * @property value current value.
 */
public class VarEntry(
    public val name: String,
    public val handle: Int,
    public val modId: String?,
    public val value: Int,
)

/**
 * Named integer variables: a game-supplied [VarStore] of vanilla variables plus growable
 * mod-registered `Int` variables, under the same u16 handle scheme as [FlagRegistry] (vanilla
 * handle = vanilla index `< MOD_BASE`, mod handle `>= MOD_BASE`).
 *
 * `get`/`set` allocate nothing; [register], [resolve], [entries] and serialization are
 * load-time/debug paths. Persist mod variables with [serializeMod]/[deserializeMod], which key by
 * name, never by handle.
 *
 * @property vanilla the vanilla variable storage.
 * @param vanillaNames the game's vanilla name to variable index table; indices must be below
 *     [MOD_BASE].
 */
public class VarRegistry(
    public val vanilla: VarStore,
    vanillaNames: Map<String, Int>,
) {
    private val table = ModNameTable("variable", vanillaNames)
    /** Mod variable values indexed by slot; grows as variables are registered. */
    private var values = IntArray(8)

    /** Number of mod variables registered so far. */
    public val modCount: Int get() = table.count

    /**
     * Registers variable [name] for [modId] (initial value 0 on first registration) and returns
     * its handle (`>= MOD_BASE`). Same-mod re-registration returns the same handle. Errors as in
     * [FlagRegistry.register].
     */
    public fun register(modId: String, name: String): Int {
        val slot = table.register(modId, name)
        if (slot >= values.size) values = values.copyOf(maxOf(values.size * 2, slot + 1))
        return MOD_BASE + slot
    }

    /**
     * Load-time lookup of [name]: vanilla names first, then mod names. Returns [UNRESOLVED] (-1)
     * for an unknown name; see [resolveOrThrow].
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
        if (h == UNRESOLVED) throw NoSuchElementException("unknown variable '$name'")
        return h
    }

    /** Value of variable [handle]. Mod handles must have been registered. */
    public fun get(handle: Int): Int {
        if (handle < MOD_BASE) return vanilla.get(handle)
        return values[modSlot(handle)]
    }

    /** Sets variable [handle] to [value]. */
    public fun set(handle: Int, value: Int) {
        if (handle < MOD_BASE) {
            vanilla.set(handle, value)
            return
        }
        values[modSlot(handle)] = value
    }

    /** All named variables (vanilla in table order, then mod variables in registration order). */
    public fun entries(): List<VarEntry> {
        val out = ArrayList<VarEntry>()
        for ((name, id) in table.vanillaEntries()) {
            out.add(VarEntry(name, id, null, vanilla.get(id)))
        }
        for (slot in 0 until table.count) {
            val h = MOD_BASE + slot
            out.add(VarEntry(table.nameAt(slot), h, table.ownerAt(slot), values[slot]))
        }
        return out
    }

    /**
     * Serializes [modId]'s variables by name: u32 count, then per variable a u16 byte length,
     * the UTF-8 name and the i32 value, all little-endian.
     */
    public fun serializeMod(modId: String): ByteArray {
        val slots = table.slotsOf(modId)
        val out = ByteSink()
        out.i32(slots.size)
        for (slot in slots) {
            out.string(table.nameAt(slot))
            out.i32(values[slot])
        }
        return out.toByteArray()
    }

    /**
     * Restores [modId]'s variables from [serializeMod] output: resets the mod's variables to 0,
     * then assigns by name. Names this mod has not registered are ignored. Returns the number of
     * entries applied. Malformed data throws [IllegalArgumentException] before anything changes.
     */
    public fun deserializeMod(modId: String, data: ByteArray): Int {
        val src = ByteSource(data)
        val n = src.i32()
        require(n >= 0 && n <= data.size) { "bad entry count" }
        val slots = IntArray(n)
        val vals = IntArray(n)
        var applied = 0
        for (i in 0 until n) {
            val name = src.string()
            vals[i] = src.i32()
            val slot = table.slotOf(name)
            slots[i] = if (slot != UNRESOLVED && table.ownerAt(slot) == modId) {
                applied++
                slot
            } else {
                UNRESOLVED
            }
        }
        src.requireEnd()
        for (slot in table.slotsOf(modId)) values[slot] = 0
        for (i in 0 until n) if (slots[i] != UNRESOLVED) values[slots[i]] = vals[i]
        return applied
    }

    /** Slot of mod [handle]; throws if it was never registered. */
    private fun modSlot(handle: Int): Int {
        val slot = handle - MOD_BASE
        require(slot < values.size && slot < table.count) { "unregistered mod variable handle" }
        return slot
    }
}
