package gba.core.sram

/**
 * The cartridge's 32 KiB battery-backed SRAM. Blank (never written) SRAM reads as 0xFF.
 *
 * Offsets are relative to the start of SRAM (GBA address 0x0E000000, [BASE_ADDRESS]). Every access
 * is bounds-checked and throws [IllegalArgumentException] instead of touching memory outside the
 * chip. [dirty] is raised by any write so a host can persist the image lazily.
 */
public class CartSram {
    private val data = ByteArray(SIZE).also { it.fill(0xFF.toByte()) }

    /** True once any write happened since construction or the last [clearDirty]. */
    public var dirty: Boolean = false
        private set

    /** Marks the current contents as persisted. */
    public fun clearDirty() {
        dirty = false
    }

    /** Resets every byte to 0xFF (blank SRAM) and sets [dirty]. */
    public fun erase() {
        data.fill(0xFF.toByte())
        dirty = true
    }

    /** Byte at [offset] as 0..255. */
    public fun readByte(offset: Int): Int {
        require(offset in 0 until SIZE) { "SRAM offset $offset out of range" }
        return data[offset].toInt() and 0xFF
    }

    /** Stores the low 8 bits of [value] at [offset]. */
    public fun writeByte(offset: Int, value: Int) {
        require(offset in 0 until SIZE) { "SRAM offset $offset out of range" }
        data[offset] = value.toByte()
        dirty = true
    }

    /** Copies [size] bytes from SRAM at [offset] into [dest] at [destOffset]. */
    public fun read(offset: Int, dest: ByteArray, destOffset: Int, size: Int) {
        checkRange(offset, size)
        data.copyInto(dest, destOffset, offset, offset + size)
    }

    /**
     * Copies [size] bytes from [src] at [srcOffset] into SRAM at [offset]; does not mark dirty when
     * [size] is 0.
     */
    public fun write(offset: Int, src: ByteArray, srcOffset: Int, size: Int) {
        checkRange(offset, size)
        src.copyInto(data, offset, srcOffset, srcOffset + size)
        if (size > 0) dirty = true
    }

    /**
     * Compares [size] bytes at SRAM [offset] with [src] from [srcOffset]; returns the index
     * (relative to the range) of the first differing byte, or -1 if equal.
     */
    public fun firstMismatch(offset: Int, src: ByteArray, srcOffset: Int, size: Int): Int {
        checkRange(offset, size)
        require(srcOffset >= 0 && srcOffset <= src.size - size) { "source range out of bounds" }
        for (i in 0 until size) {
            if (data[offset + i] != src[srcOffset + i]) return i
        }
        return -1
    }

    /** Copies the whole image to [dest] (for persisting to a `.sav` file). */
    public fun copyOut(dest: ByteArray) {
        data.copyInto(dest, 0, 0, SIZE)
    }

    /**
     * Replaces the whole image from [src] (a loaded `.sav`; its first [SIZE] bytes) and clears
     * [dirty].
     */
    public fun copyIn(src: ByteArray) {
        require(src.size >= SIZE) { "SRAM image needs $SIZE bytes" }
        src.copyInto(data, 0, 0, SIZE)
        dirty = false
    }

    /** Rejects a [size]-byte range at [offset] that is negative or runs past the chip's end. */
    private fun checkRange(offset: Int, size: Int) {
        require(size >= 0 && offset >= 0 && offset <= SIZE - size) {
            "SRAM range $offset+$size out of bounds"
        }
    }

    /** SRAM geometry. */
    public companion object {
        /** SRAM size in bytes (32 KiB). */
        public const val SIZE: Int = 0x8000

        /** GBA bus address of SRAM offset 0. */
        public const val BASE_ADDRESS: Int = 0x0E000000
    }
}
