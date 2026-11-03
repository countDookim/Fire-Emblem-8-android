package gbafe.extract

import gbafe.data.IndexedImage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Minimal PNG reader/writer for palette images (colour type 3, bit depth 8, with a `PLTE` chunk).
 *
 * Extracted GBA graphics must keep their **palette indices** exactly, not just their colours (two palette
 * slots can share a colour, and slot 0 is transparent on hardware), so this avoids `javax.imageio`, which
 * may remap or reorder palettes. Written with `Deflater`/`Inflater` and `CRC32` from the JDK only.
 *
 * Reading accepts what this writer produces plus what common editors save for 8-bit palette PNGs: any
 * scanline filter, split `IDAT`s, and ancillary chunks (ignored). Interlacing and bit depths other than 8
 * are rejected with a clear error rather than mis-decoded.
 */
public object IndexedPng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /** A decoded PNG: the index image and its palette as opaque ARGB8888. */
    public class Decoded(
        /** Pixel indices. */
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
        require(palette.size in 1..256) { "palette must have 1..256 entries, has ${palette.size}" }
        for (i in image.pixels.indices) {
            val v = image.pixels[i].toInt() and 0xFF
            require(v < palette.size) { "pixel $i has index $v but palette has ${palette.size} entries" }
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(image.width); writeInt(image.height)
            writeByte(8); writeByte(3); writeByte(0); writeByte(0); writeByte(0)
        }
        chunk(out, "IHDR", ihdr.toByteArray())
        val plte = ByteArray(palette.size * 3)
        for (i in palette.indices) {
            plte[3 * i] = (palette[i] shr 16).toByte()
            plte[3 * i + 1] = (palette[i] shr 8).toByte()
            plte[3 * i + 2] = palette[i].toByte()
        }
        chunk(out, "PLTE", plte)
        val raw = ByteArray(image.height * (image.width + 1))
        for (y in 0 until image.height) {
            // Filter type 0 (None) per row; indices compress well enough and stay trivially inspectable.
            System.arraycopy(image.pixels, y * image.width, raw, y * (image.width + 1) + 1, image.width)
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Decodes PNG [bytes] that are 8-bit palette images.
     *
     * @throws IllegalArgumentException on a bad signature or CRC, an unsupported format, or truncated data.
     */
    public fun read(bytes: ByteArray): Decoded {
        require(bytes.size >= 8 && bytes.copyOf(8).contentEquals(SIGNATURE)) { "not a PNG" }
        val input = DataInputStream(bytes.inputStream(8, bytes.size - 8))
        var width = -1
        var height = -1
        var palette: IntArray? = null
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = input.readInt()
            require(len >= 0 && len <= input.available() - 8) { "truncated chunk" }
            val typeBytes = ByteArray(4).also { input.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(len).also { input.readFully(it) }
            val crc = input.readInt()
            require(crc == crcOf(typeBytes, data)) { "bad CRC in $type chunk" }
            when (type) {
                "IHDR" -> {
                    val d = DataInputStream(data.inputStream())
                    width = d.readInt(); height = d.readInt()
                    val depth = d.readUnsignedByte(); val colorType = d.readUnsignedByte()
                    d.readUnsignedByte(); d.readUnsignedByte()
                    val interlace = d.readUnsignedByte()
                    require(depth == 8 && colorType == 3) { "only 8-bit palette PNGs are supported (depth $depth, type $colorType)" }
                    require(interlace == 0) { "interlaced PNGs are not supported" }
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
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        var n = 0
        while (n < raw.size) {
            val k = inflater.inflate(raw, n, raw.size - n)
            require(k > 0) { "image data truncated" }
            n += k
        }
        inflater.end()
        val img = IndexedImage(width, height)
        for (y in 0 until height) unfilter(raw, y * stride, width, img.pixels, y * width, if (y == 0) -1 else (y - 1) * width)
        return Decoded(img, palette)
    }

    /** Undoes one scanline's filter (bpp = 1) into [dst], using the previous decoded row at [prevOff] (-1 = none). */
    private fun unfilter(raw: ByteArray, off: Int, width: Int, dst: ByteArray, dstOff: Int, prevOff: Int) {
        val filter = raw[off].toInt() and 0xFF
        require(filter <= 4) { "bad PNG filter $filter" }
        for (x in 0 until width) {
            val v = raw[off + 1 + x].toInt() and 0xFF
            val a = if (x > 0) dst[dstOff + x - 1].toInt() and 0xFF else 0
            val b = if (prevOff >= 0) dst[prevOff + x].toInt() and 0xFF else 0
            val c = if (x > 0 && prevOff >= 0) dst[prevOff + x - 1].toInt() and 0xFF else 0
            val pred = when (filter) {
                0 -> 0
                1 -> a
                2 -> b
                3 -> (a + b) ushr 1
                else -> paeth(a, b, c)
            }
            dst[dstOff + x] = (v + pred).toByte()
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        DataOutputStream(out).apply {
            writeInt(data.size); write(t); write(data); writeInt(crcOf(t, data))
        }
    }

    private fun crcOf(type: ByteArray, data: ByteArray): Int {
        val crc = CRC32()
        crc.update(type); crc.update(data)
        return crc.value.toInt()
    }
}
