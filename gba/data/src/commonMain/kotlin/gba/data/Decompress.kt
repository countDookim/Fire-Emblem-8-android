package gba.data

/**
 * `Decompress` (src/bmlib.c): unpacks a ROM graphics blob by the type in its header's high nibble
 * (`struct TileMapArr` in include/bmlib.h: type in bits 0..7, size in bits 8..31):
 * 0x00 raw (`UnpackRaw`: the size field includes the 4-byte header) or 0x10 LZ77.
 * The decomp's table also dispatches 0x20 to `HuffUnComp` and 0x30 to `RLUnCompVram` or
 * `RLUnCompWram`; those two types are not implemented here (they throw), and it is not checked
 * whether any FE8 blob uses them. The decomp picks the VRAM or WRAM variant from the
 * destination address; the BIOS VRAM variants write 16 bits at a time (hardware/BIOS fact, not
 * checked here), which does not change the output bytes, so this port has one path for either
 * destination.
 *
 * Partially ported from src/bmlib.c: Decompress. The raw path into VRAM is checked against the
 * Prologue boss turn talk's face tiles (f2980); the LZ77 path by other tests; types 0x20 and 0x30
 * are missing.
 */
public object Decompress {
    /** Header type value (high nibble, `0x00`) of an uncompressed blob. */
    public const val TYPE_RAW: Int = 0x00

    /** Header type value (high nibble, `0x10`) of an LZ77 blob; same as [Lz77.TYPE]. */
    public const val TYPE_LZ77: Int = Lz77.TYPE

    /** High-nibble mask for extracting the compression format type from the first header byte. */
    private const val HEADER_TYPE_MASK: Int = 0xF0

    /** Size of the type+size header in front of a raw blob's payload (`src + 4` in `UnpackRaw`). */
    private const val RAW_HEADER_SIZE: Int = 4

    /**
     * Decodes the blob at [offset] of [src] into [dst] at [dstOffset]; returns the bytes written
     * (the payload size, without the 4-byte header). Does not allocate.
     *
     * @throws UnsupportedOperationException for header types other than raw and LZ77.
     */
    public fun into(src: ByteArray, offset: Int, dst: ByteArray, dstOffset: Int): Int {
        val type = src[offset].toInt() and HEADER_TYPE_MASK
        return when (type) {
            TYPE_RAW -> unpackRaw(src, offset, dst, dstOffset)
            TYPE_LZ77 -> {
                Lz77.decodeInto(src, offset, dst, dstOffset)
                Lz77.headerSize(src, offset)
            }
            else -> throw UnsupportedOperationException(
                "Decompress type 0x${type.toString(16)} not supported yet",
            )
        }
    }

    /** Decodes the blob at [offset] of [src] (raw or LZ77) into a newly allocated array. */
    public fun decode(src: ByteArray, offset: Int): ByteArray {
        val type = src[offset].toInt() and HEADER_TYPE_MASK
        if (type == TYPE_LZ77) return Lz77.decode(src, offset).data
        val out = ByteArray(maxOf(rawSize(src, offset), 0))
        into(src, offset, out, 0)
        return out
    }

    /**
     * `GetDataSize(data)`: the 24-bit little-endian size in header bytes 1..3 (`TileMapArr.size`).
     *
     * Ported from src/bmlib.c: GetDataSize
     */
    public fun getDataSize(src: ByteArray, offset: Int): Int {
        val b1 = src[offset + 1].toInt() and 0xFF
        val b2 = src[offset + 2].toInt() and 0xFF
        val b3 = src[offset + 3].toInt() and 0xFF
        return b1 or (b2 shl 8) or (b3 shl 16)
    }

    /**
     * `UnpackRaw(src, dst)`: copies the payload of a raw blob (the size field includes the 4
     * header bytes); returns the bytes copied. The C picks `CpuCopy16` or `CpuFastCopy` by the
     * size; both copy the same bytes.
     *
     * Ported from src/bmlib.c: UnpackRaw
     */
    public fun unpackRaw(src: ByteArray, offset: Int, dst: ByteArray, dstOffset: Int): Int {
        val size = rawSize(src, offset)
        val payloadStart = offset + RAW_HEADER_SIZE
        src.copyInto(dst, dstOffset, payloadStart, payloadStart + size)
        return size
    }

    /** Payload size of a raw blob: [getDataSize] minus the 4 header bytes, as `UnpackRaw` does. */
    private fun rawSize(src: ByteArray, offset: Int): Int =
        getDataSize(src, offset) - RAW_HEADER_SIZE
}
