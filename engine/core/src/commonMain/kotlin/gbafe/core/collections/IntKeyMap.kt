package gbafe.core.collections

/**
 * A map from `Int` keys to non-null values that never boxes a key: open addressing with linear
 * probing over a power-of-two table, a slot being free while its value is null. A lookup
 * allocates nothing, so it is safe in per-frame code. Entries are never removed. Not thread
 * safe.
 */
public class IntKeyMap<V : Any> {
    private var keys = IntArray(INITIAL_CAPACITY)
    private var values = arrayOfNulls<Any>(INITIAL_CAPACITY)

    /** The number of entries. */
    public var size: Int = 0
        private set

    /** The value stored under [key], or null if there is none. */
    @Suppress("UNCHECKED_CAST")
    public operator fun get(key: Int): V? {
        val mask = keys.size - 1
        var slot = hash(key) and mask
        while (true) {
            val value = values[slot] ?: return null
            if (keys[slot] == key) return value as V
            slot = (slot + 1) and mask
        }
    }

    /** The value stored under [key]; throws [NoSuchElementException] if there is none. */
    public fun getValue(key: Int): V =
        get(key) ?: throw NoSuchElementException("no entry for key $key")

    /** Stores [value] under [key], replacing any earlier value. */
    public operator fun set(key: Int, value: V) {
        if ((size + 1) * 4 > keys.size * 3) grow()
        put(key, value)
    }

    /** Whether a value is stored under [key]. */
    public fun containsKey(key: Int): Boolean = get(key) != null

    /** Same as [containsKey], so `key in map` works. */
    public operator fun contains(key: Int): Boolean = containsKey(key)

    private fun put(key: Int, value: V) {
        val mask = keys.size - 1
        var slot = hash(key) and mask
        while (values[slot] != null) {
            if (keys[slot] == key) {
                values[slot] = value
                return
            }
            slot = (slot + 1) and mask
        }
        keys[slot] = key
        values[slot] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2)
        values = arrayOfNulls(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) {
            @Suppress("UNCHECKED_CAST")
            val value = oldValues[i] as V?
            if (value != null) put(oldKeys[i], value)
        }
    }

    private fun hash(key: Int): Int {
        val mixed = key * -0x61c88647 // the golden-ratio multiplier spreads low-bit patterns
        return mixed xor (mixed ushr 15)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
