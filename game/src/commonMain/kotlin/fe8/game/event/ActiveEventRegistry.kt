package fe8.game.event

/**
 * `struct ActiveEventRegistry` (`gActiveEventRegistry`, eventinfo.h): the script and flag of every
 * list event started since the last `ClearActiveEventRegistry`, so that a running script can ask
 * which list flag started it (`GetEventTriggerId`). Layout: ten script words, ten `s16` flags and
 * the `s16` count [idx] at +0x3C; the C object is cleared with `memset` over 0x40 bytes.
 */
public class ActiveEventRegistry {
    /** `scripts[10]`: ROM addresses of the started scripts. */
    public val scripts: IntArray = IntArray(CAPACITY)

    /** `flags[10]`: the list flag of each script, as a u16 (the C field is `s16`). */
    public val flags: IntArray = IntArray(CAPACITY)

    /** `idx`: the number of registered scripts (the next write position). */
    public var idx: Int = 0

    /** Zeroes every field (`memset(&gActiveEventRegistry, 0, sizeof(...))`). */
    internal fun clear() {
        for (i in 0 until CAPACITY) {
            scripts[i] = 0
            flags[i] = 0
        }
        idx = 0
    }

    /**
     * `scripts[idx] = script; flags[idx] = flag; idx++`. The C writes past the array when ten are
     * registered; here that write is dropped (the count still grows).
     */
    internal fun register(script: Int, flag: Int) {
        if (idx in 0 until CAPACITY) {
            scripts[idx] = script
            flags[idx] = flag and 0xFFFF
        }
        idx++
    }

    /** Writes the 0x40 bytes of the C object, little endian, to [out] at [offset]. */
    public fun store(out: ByteArray, offset: Int) {
        for (i in 0 until BYTES) out[offset + i] = 0
        for (i in 0 until CAPACITY) {
            put32(out, offset + 4 * i, scripts[i])
            put16(out, offset + FLAGS_OFFSET + 2 * i, flags[i])
        }
        put16(out, offset + IDX_OFFSET, idx)
    }

    /** Reads the 0x40 bytes of the C object from [src] at [offset]. */
    public fun load(src: ByteArray, offset: Int) {
        for (i in 0 until CAPACITY) {
            scripts[i] = get16(src, offset + 4 * i) or (get16(src, offset + 4 * i + 2) shl 16)
            flags[i] = get16(src, offset + FLAGS_OFFSET + 2 * i)
        }
        idx = get16(src, offset + IDX_OFFSET).toShort().toInt()
    }

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v shr 8).toByte()
    }

    private fun put32(out: ByteArray, at: Int, v: Int) {
        put16(out, at, v)
        put16(out, at + 2, v ushr 16)
    }

    private fun get16(src: ByteArray, at: Int): Int =
        (src[at].toInt() and 0xFF) or ((src[at + 1].toInt() and 0xFF) shl 8)

    /** The registry's size and layout constants. */
    public companion object {
        /** Entries of `scripts` and `flags`. */
        public const val CAPACITY: Int = 10

        /** Size of the object in memory (the `memset` length). */
        public const val BYTES: Int = 0x40

        private const val FLAGS_OFFSET = 0x28
        private const val IDX_OFFSET = 0x3C
    }
}
