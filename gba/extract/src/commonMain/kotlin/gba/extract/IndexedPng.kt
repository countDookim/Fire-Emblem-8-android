package gba.extract

import gba.data.ByteSink
import gba.data.Crc32
import gba.data.IndexedImage

/**
 * Minimal PNG reader/writer for palette images (colour type 3, bit depth 8, with a `PLTE` chunk).
 *
 * Extracted GBA graphics must keep their **palette indices** exactly, not just their colours (two palette
 * slots can share a colour, and slot 0 is transparent on hardware: a hardware fact, not checked
 * here), so this avoids `javax.imageio`, which may remap palettes (not checked here). zlib comes
 * from the platform (the JDK on the JVM) and CRC-32 from [Crc32].
 *
 * Reading accepts what this writer produces plus what common editors save for 8-bit palette PNGs: any
 * scanline filter, split `IDAT`s, and ancillary chunks (ignored). Interlacing and bit depths other than 8
 * are rejected with a clear error rather than mis-decoded.
 */
public object IndexedPng {
    /** The 8-byte PNG file signature (0x89, "PNG", CR LF, 0x1A, LF). */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        13, 10, 26, 10,
    )

    /** IHDR data length: width 4, height 4, then five 1-byte fields. */
    private const val IHDR_DATA_LENGTH = 13

    /** PNG colour type 3: indexed colour with palette. */
    private const val COLOR_TYPE_PALETTE = 3

    /** PNG bit depth: 8 bits per pixel (palette index). */
    private const val BIT_DEPTH_8 = 8

    /** PNG compression method 0: zlib deflate. */
    private const val COMPRESSION_DEFLATE = 0

    /** PNG filter method 0: adaptive filtering. */
    private const val FILTER_METHOD_ADAPTIVE = 0

    /** PNG interlace method 0: no interlace. */
    private const val INTERLACE_NONE = 0

    /** PNG scanline filter types 0..4: None, Sub, Up, Average, Paeth. */
    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    /** Minimum allowable palette entries in a PNG PLTE chunk. */
    public const val MIN_PALETTE_SIZE: Int = 1

    /** Maximum allowable palette entries in an 8-bit PNG PLTE chunk. */
    public const val MAX_PALETTE_SIZE: Int = 256

    /** A decoded PNG: the index image and its palette as opaque ARGB8888. */
    public class Decoded(
        /** Palette index of every pixel. */
        public val image: IndexedImage,
        /** Palette entries, ARGB8888 with alpha 0xFF (PNG `tRNS` is ignored). */
        public val palette: IntArray,
    )

    /**
     * Encodes [image] with [palette] (ARGB8888, alpha ignored, 1..256 entries) as PNG bytes.
     *
     * @throws IllegalArgumentException if a pixel index is not a valid palette slot.
     */
    public fun write(image: IndexedImage, palette: IntArray): ByteArray {
        require(palette.size in MIN_PALETTE_SIZE..MAX_PALETTE_SIZE) {
            "palette must have $MIN_PALETTE_SIZE..$MAX_PALETTE_SIZE entries, has ${palette.size}"
        }
        for (i in image.pixels.indices) {
            val v = image.pixels[i].toInt() and 0xFF
            require(v < palette.size) { "pixel $i has index $v but palette has ${palette.size} entries" }
        }
        val out = ByteSink()
        out.write(SIGNATURE)

        // IHDR chunk: width, height, bit depth, colour type, compression, filter, interlace
        val ihdr = ByteSink()
        ihdr.writeInt(image.width)
        ihdr.writeInt(image.height)
        ihdr.write(BIT_DEPTH_8)
        ihdr.write(COLOR_TYPE_PALETTE)
        ihdr.write(COMPRESSION_DEFLATE)
        ihdr.write(FILTER_METHOD_ADAPTIVE)
        ihdr.write(INTERLACE_NONE)
        chunk(out, "IHDR", ihdr.toByteArray())

        // PLTE Chunk: RGB triplets
        val plte = ByteArray(palette.size * 3)
        for (i in palette.indices) {
            plte[3 * i] = (palette[i] shr 16).toByte()
            plte[3 * i + 1] = (palette[i] shr 8).toByte()
            plte[3 * i + 2] = palette[i].toByte()
        }
        chunk(out, "PLTE", plte)

        // IDAT Chunk: raw filtered scanlines (filter type 0 None per scanline) compressed with zlib
        val raw = ByteArray(image.height * (image.width + 1))
        for (y in 0 until image.height) {
            image.pixels.copyInto(raw, y * (image.width + 1) + 1, y * image.width, (y + 1) * image.width)
        }
        chunk(out, "IDAT", zlibDeflate(raw))

        // IEND Chunk
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Decodes PNG [bytes] that are 8-bit palette images. The palette may be shorter than 256, and
     * pixel indices are not checked against its size.
     *
     * @throws IllegalArgumentException on a bad signature or CRC, an unsupported format, or truncated data.
     */
    public fun read(bytes: ByteArray): Decoded {
        require(bytes.size >= 8 && bytes.copyOf(8).contentEquals(SIGNATURE)) { "not a PNG" }
        var pos = 8
        var width = -1
        var height = -1
        var palette: IntArray? = null
        val idat = ByteSink()

        while (true) {
            require(pos + 4 <= bytes.size) { "truncated chunk" }
            val len = bigEndianInt(bytes, pos)
            pos += 4
            require(len >= 0 && len <= bytes.size - pos - 8) { "truncated chunk" }
            val typeBytes = bytes.copyOfRange(pos, pos + 4)
            val type = typeBytes.decodeToString()
            val data = bytes.copyOfRange(pos + 4, pos + 4 + len)
            val crc = bigEndianInt(bytes, pos + 4 + len)
            pos += 8 + len
            require(crc == crcOf(typeBytes, data)) { "bad CRC in $type chunk" }

            when (type) {
                "IHDR" -> {
                    require(len >= IHDR_DATA_LENGTH) { "truncated IHDR chunk" }
                    width = bigEndianInt(data, 0)
                    height = bigEndianInt(data, 4)
                    val depth = data[8].toInt() and 0xFF
                    val colorType = data[9].toInt() and 0xFF
                    val interlace = data[12].toInt() and 0xFF
                    require(depth == BIT_DEPTH_8 && colorType == COLOR_TYPE_PALETTE) {
                        "only 8-bit palette PNGs are supported (depth $depth, type $colorType)"
                    }
                    require(interlace == INTERLACE_NONE) { "interlaced PNGs are not supported" }
                }
                "PLTE" -> {
                    require(len % 3 == 0 && len in 3..768) { "bad PLTE length $len" }
                    palette = IntArray(len / 3) { i ->
                        val r = data[3 * i].toInt() and 0xFF
                        val g = data[3 * i + 1].toInt() and 0xFF
                        val b = data[3 * i + 2].toInt() and 0xFF
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }

        require(width >= 0 && palette != null) { "missing IHDR or PLTE" }
        val stride = width + 1
        val raw = ByteArray(height * stride)
        zlibInflate(idat.toByteArray(), raw)
        val img = IndexedImage(width, height)
        for (y in 0 until height) {
            unfilter(raw, y * stride, width, img.pixels, y * width, if (y == 0) -1 else (y - 1) * width)
        }
        return Decoded(img, palette)
    }

    /**
     * Undoes one scanline's PNG filter (for 1 byte per pixel) into [dst], using the previous
     * decoded scanline at [prevOff] (-1 if this is the first scanline).
     */
    private fun unfilter(raw: ByteArray, off: Int, width: Int, dst: ByteArray, dstOff: Int, prevOff: Int) {
        val filter = raw[off].toInt() and 0xFF
        require(filter in FILTER_NONE..FILTER_PAETH) { "bad PNG filter $filter" }
        for (x in 0 until width) {
            val v = raw[off + 1 + x].toInt() and 0xFF
            val a = if (x > 0) dst[dstOff + x - 1].toInt() and 0xFF else 0
            val b = if (prevOff >= 0) dst[prevOff + x].toInt() and 0xFF else 0
            val c = if (x > 0 && prevOff >= 0) dst[prevOff + x - 1].toInt() and 0xFF else 0
            val pred = when (filter) {
                FILTER_NONE -> 0
                FILTER_SUB -> a
                FILTER_UP -> b
                FILTER_AVERAGE -> (a + b) ushr 1
                else -> paeth(a, b, c)
            }
            dst[dstOff + x] = (v + pred).toByte()
        }
    }

    /** PNG Paeth predictor of the neighbours [a] (left), [b] (above), [c] (upper left). */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Reads a big-endian 32-bit integer from [b] at offset [off]. */
    private fun bigEndianInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** Appends one PNG chunk (big-endian length, type, [data], CRC) to [out]. */
    private fun chunk(out: ByteSink, type: String, data: ByteArray) {
        val t = type.encodeToByteArray()
        out.writeInt(data.size)
        out.write(t)
        out.write(data)
        out.writeInt(crcOf(t, data))
    }

    /** CRC-32 stored in a PNG chunk: over the type bytes then [data], not the length. */
    private fun crcOf(type: ByteArray, data: ByteArray): Int {
        val crc = Crc32()
        crc.update(type)
        crc.update(data)
        return crc.value.toInt()
    }
}
