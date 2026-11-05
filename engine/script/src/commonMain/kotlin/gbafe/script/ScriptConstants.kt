package gbafe.script

/**
 * Named constants of the event script language, grouped by kind (`"character"`, `"song"`,
 * `"event_flag"`...): the `.fe8s` parser resolves a name to its value, the converter and tools
 * print a value back as its name. Kinds are open strings so mods can add their own. A base game
 * supplies one implementation (for FE8, `gbafe.extract.fe8.events.Fe8ScriptConstants`);
 * [MutableScriptConstants] layers mod entries over it.
 *
 * Lookups never allocate, so they are safe on the interpreter's hot path.
 */
public interface ScriptConstants {
    /** The value of [name] in [kind], or [NONE] when there is no such constant. */
    public fun valueOf(kind: String, name: String): Int

    /**
     * The primary name of [value] in [kind] (the first one declared when several share it), or
     * null.
     */
    public fun nameOf(kind: String, value: Int): String?

    /** Shared constants. */
    public companion object {
        /** Returned by [valueOf] for an unknown name; no real constant has this value. */
        public const val NONE: Int = Int.MIN_VALUE
    }
}

/**
 * The value of [name] in [kind], or null when unknown (boxes large values; use
 * [ScriptConstants.valueOf] on hot paths).
 */
public fun ScriptConstants.findValue(kind: String, name: String): Int? =
    valueOf(kind, name).let { if (it == ScriptConstants.NONE) null else it }

/** Where a constant comes from. */
public enum class ConstantOrigin {
    /** Named by the original game's headers. */
    DECOMP,

    /** Expanded from a parameterised macro in the headers (such as `EVFLAG_EXTRA_UNIT(i)`). */
    DERIVED,

    /** Placeholder for an id the headers leave unnamed (such as `EVFLAG_41`). */
    FILLER,
}

/**
 * Immutable name/value table of one kind. [names] and [values] are parallel; when several names
 * share a value the first listed is the primary name returned by [nameOf]. [origins] lists the
 * entries that are not [ConstantOrigin.DECOMP].
 */
public class ConstantTable(
    /** Names in declaration order. */
    public val names: Array<String>,
    /** Value of each name. */
    public val values: IntArray,
    private val origins: Map<String, ConstantOrigin> = emptyMap(),
) {
    private val byName: HashMap<String, Int> = HashMap<String, Int>(names.size * 2).also { m ->
        for (i in names.indices) if (!m.containsKey(names[i])) m[names[i]] = values[i]
    }
    private val sortedValues: IntArray
    private val sortedNames: Array<String>

    init {
        require(names.size == values.size) { "names and values differ in size" }
        // Stable sort by value so the first declared name of a value stays first.
        val order = names.indices.sortedBy { values[it] }
        sortedValues = IntArray(order.size) { values[order[it]] }
        sortedNames = Array(order.size) { names[order[it]] }
    }

    /** Number of entries. */
    public val size: Int get() = names.size

    /** The value of [name], or [ScriptConstants.NONE]. */
    public fun valueOf(name: String): Int = byName[name] ?: ScriptConstants.NONE

    /** The primary name of [value], or null. */
    public fun nameOf(value: Int): String? {
        var lo = 0
        var hi = sortedValues.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedValues[mid] < value) lo = mid + 1 else hi = mid
        }
        return if (lo < sortedValues.size && sortedValues[lo] == value) sortedNames[lo] else null
    }

    /** How [name] came about; [ConstantOrigin.DECOMP] for plain header names and unknown names. */
    public fun originOf(name: String): ConstantOrigin = origins[name] ?: ConstantOrigin.DECOMP
}

/** A [ScriptConstants] backed by one immutable [ConstantTable] per kind. */
public open class TableScriptConstants(
    private val tables: Map<String, ConstantTable>,
) : ScriptConstants {
    /** The table of [kind], or null. */
    public fun table(kind: String): ConstantTable? = tables[kind]

    /** The kinds this set defines. */
    public val kinds: Set<String> get() = tables.keys

    override fun valueOf(kind: String, name: String): Int =
        tables[kind]?.valueOf(name) ?: ScriptConstants.NONE

    override fun nameOf(kind: String, value: Int): String? = tables[kind]?.nameOf(value)
}

/**
 * Constants added by mods on top of an optional [base]: its own entries win over the base's. Define
 * entries while loading; lookups afterwards do not allocate.
 */
public class MutableScriptConstants(private val base: ScriptConstants? = null) : ScriptConstants {
    private class Kind {
        val byName = HashMap<String, Int>()
        var values = IntArray(4)
        var names = arrayOfNulls<String>(4)
        var count = 0
    }

    private val kinds = HashMap<String, Kind>()

    /** Defines [name] = [value] in [kind]; redefining a name replaces its value. */
    public fun define(kind: String, name: String, value: Int) {
        val k = kinds.getOrPut(kind) { Kind() }
        val old = k.byName.put(name, value)
        if (old != null) {
            for (i in 0 until k.count) {
                if (k.names[i] == name) {
                    k.values[i] = value
                    return
                }
            }
        }
        if (k.count == k.values.size) {
            k.values = k.values.copyOf(k.count * 2)
            k.names = k.names.copyOf(k.count * 2)
        }
        k.values[k.count] = value
        k.names[k.count++] = name
    }

    override fun valueOf(kind: String, name: String): Int {
        val own = kinds[kind]?.byName?.get(name)
        return own ?: base?.valueOf(kind, name) ?: ScriptConstants.NONE
    }

    override fun nameOf(kind: String, value: Int): String? {
        val k = kinds[kind]
        if (k != null) {
            // Newest definition first, so a mod's name for a value beats an older one.
            for (i in k.count - 1 downTo 0) if (k.values[i] == value) return k.names[i]
        }
        return base?.nameOf(kind, value)
    }
}
