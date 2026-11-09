package gba.data

/**
 * An image of palette indices: one byte per pixel, row-major, `width * height` bytes.
 *
 * This is the in-memory form GBA tile graphics take between the ROM's tile layout and the extracted
 * (moddable) PNG: indices are kept exact so a round trip ROM → PNG → ROM is byte-identical.
 */
public class IndexedImage(
    /** Width in pixels. */
    public val width: Int,
    /** Height in pixels. */
    public val height: Int,
    /** Palette index of each pixel, row-major (`pixels[y * width + x]`), interpreted as unsigned. */
    public val pixels: ByteArray = ByteArray(width * height),
) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) {
            "pixels has ${pixels.size} bytes, expected ${width}x$height"
        }
    }

    /** Unsigned palette index at ([x], [y]). */
    public operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** Sets the palette index at ([x], [y]) (only the low 8 bits of [index] are kept). */
    public operator fun set(x: Int, y: Int, index: Int) {
        pixels[y * width + x] = index.toByte()
    }
}

/**
 * Conversion between GBA character (tile) data and [IndexedImage]s. Load/extract-time only.
 *
 * GBA tiles are 8×8 pixels stored row by row. In 4bpp mode a tile is 32 bytes and each byte holds two
 * pixels, the **low nibble being the left pixel**; in 8bpp mode a tile is 64 bytes, one byte per pixel.
 * Tiles are laid out left-to-right, top-to-bottom, [tilesPerRow] to a row of the resulting image; this is
 * just a viewing layout (the game addresses tiles by number), so any width round-trips.
 */
public object Tiles {
    /** Tile edge in pixels (GBA hardware tiles are 8×8 pixels). */
    public const val SIZE: Int = 8

    /** Bytes per tile at 4 bits per pixel (64 pixels / 2 pixels per byte = 32 bytes). */
    public const val BYTES_4BPP: Int = 32

    /** Bytes per tile at 8 bits per pixel (64 pixels * 1 byte per pixel = 64 bytes). */
    public const val BYTES_8BPP: Int = 64

    /** Maximum palette index that fits in a 4bpp nibble (0..15). */
    private const val MAX_4BPP_PALETTE_INDEX: Int = 15

    /** Mask for extracting a 4-bit nibble. */
    private const val NIBBLE_MASK: Int = 0x0F

    /**
     * Decodes [tileCount] 4bpp tiles from [src] at [offset] into an image [tilesPerRow] tiles wide.
     * Unused cells of a partial last row are index 0.
     */
    public fun decode4bpp(src: ByteArray, offset: Int, tileCount: Int, tilesPerRow: Int): IndexedImage =
        decode(src, offset, tileCount, tilesPerRow, bpp = 4)

    /** Decodes [tileCount] 8bpp tiles from [src] at [offset] into an image [tilesPerRow] tiles wide. */
    public fun decode8bpp(src: ByteArray, offset: Int, tileCount: Int, tilesPerRow: Int): IndexedImage =
        decode(src, offset, tileCount, tilesPerRow, bpp = 8)

    /**
     * Encodes the first [tileCount] tiles of [image] back to 4bpp tile bytes (inverse of [decode4bpp]).
     *
     * @throws IllegalArgumentException if a pixel index is above 15 or the image is not tile-aligned.
     */
    public fun encode4bpp(image: IndexedImage, tileCount: Int = tileCapacity(image)): ByteArray =
        encode(image, tileCount, bpp = 4)

    /** Encodes the first [tileCount] tiles of [image] to 8bpp tile bytes (inverse of [decode8bpp]). */
    public fun encode8bpp(image: IndexedImage, tileCount: Int = tileCapacity(image)): ByteArray =
        encode(image, tileCount, bpp = 8)

    /** Number of whole tiles [image] holds. */
    public fun tileCapacity(image: IndexedImage): Int = (image.width / SIZE) * (image.height / SIZE)

    /** Decodes [tileCount] tiles from [src] starting at [offset] into an [IndexedImage] arranged in rows. */
    private fun decode(src: ByteArray, offset: Int, tileCount: Int, tilesPerRow: Int, bpp: Int): IndexedImage {
        require(tileCount >= 0 && tilesPerRow > 0) { "tileCount=$tileCount tilesPerRow=$tilesPerRow" }
        val tileBytes = if (bpp == 4) BYTES_4BPP else BYTES_8BPP
        require(offset >= 0 && offset + tileCount * tileBytes <= src.size) { "tile data out of range" }
        val rows = (tileCount + tilesPerRow - 1) / tilesPerRow
        val img = IndexedImage(tilesPerRow * SIZE, rows * SIZE)
        for (tileIndex in 0 until tileCount) {
            val originX = (tileIndex % tilesPerRow) * SIZE
            val originY = (tileIndex / tilesPerRow) * SIZE
            val tileBaseOffset = offset + tileIndex * tileBytes
            for (pixelY in 0 until SIZE) {
                for (pixelX in 0 until SIZE) {
                    val pixelIndexInTile = pixelY * SIZE + pixelX
                    val colorIndex = if (bpp == 4) {
                        val byteVal = src[tileBaseOffset + (pixelIndexInTile shr 1)].toInt()
                        // Low nibble is left pixel (even index), high nibble is right pixel (odd index)
                        if (pixelIndexInTile and 1 == 0) byteVal and NIBBLE_MASK else (byteVal shr 4) and NIBBLE_MASK
                    } else {
                        src[tileBaseOffset + pixelIndexInTile].toInt() and 0xFF
                    }
                    img[originX + pixelX, originY + pixelY] = colorIndex
                }
            }
        }
        return img
    }

    /** Encodes [tileCount] tiles of [image] into a raw byte array formatted for GBA VRAM tile memory. */
    private fun encode(image: IndexedImage, tileCount: Int, bpp: Int): ByteArray {
        require(image.width % SIZE == 0 && image.height % SIZE == 0) { "image is not tile-aligned" }
        require(tileCount in 0..tileCapacity(image)) { "tileCount $tileCount exceeds image" }
        val tilesPerRow = image.width / SIZE
        val tileBytes = if (bpp == 4) BYTES_4BPP else BYTES_8BPP
        val out = ByteArray(tileCount * tileBytes)
        for (tileIndex in 0 until tileCount) {
            val originX = (tileIndex % tilesPerRow) * SIZE
            val originY = (tileIndex / tilesPerRow) * SIZE
            val tileBaseOffset = tileIndex * tileBytes
            for (pixelY in 0 until SIZE) {
                for (pixelX in 0 until SIZE) {
                    val colorIndex = image[originX + pixelX, originY + pixelY]
                    val pixelIndexInTile = pixelY * SIZE + pixelX
                    if (bpp == 4) {
                        require(colorIndex <= MAX_4BPP_PALETTE_INDEX) {
                            "index $colorIndex at (${originX + pixelX}, ${originY + pixelY}) does not fit 4bpp"
                        }
                        val byteOffsetInOutput = tileBaseOffset + (pixelIndexInTile shr 1)
                        val packed = out[byteOffsetInOutput].toInt() or
                            if (pixelIndexInTile and 1 == 0) colorIndex else (colorIndex shl 4)
                        out[byteOffsetInOutput] = packed.toByte()
                    } else {
                        out[tileBaseOffset + pixelIndexInTile] = colorIndex.toByte()
                    }
                }
            }
        }
        return out
    }
}

/**
 * GBA BGR555 palette helpers. A colour is 15 bits `0bbbbbgggggrrrrr`, stored little-endian in 2 bytes.
 *
 * Expansion to 8 bits per channel is `c << 3` with zero low bits, matching mGBA's reference dumps and
 * `FrameBuffer.bgr555ToArgb` in engine:render (duplicated here so engine:data does not depend on
 * rendering). Because the low bits are zero, [argbToBgr555] is an exact inverse on expanded colours.
 */
public object Bgr555 {
    /** Mask for a 5-bit colour channel. */
    private const val CHANNEL_5BIT_MASK: Int = 0x1F

    /** Shift to align red channel in BGR555 (bits 0..4). */
    private const val RED_SHIFT: Int = 0

    /** Shift to align green channel in BGR555 (bits 5..9). */
    private const val GREEN_SHIFT: Int = 5

    /** Shift to align blue channel in BGR555 (bits 10..14). */
    private const val BLUE_SHIFT: Int = 10

    /** Fully opaque alpha channel mask in ARGB8888. */
    private const val ALPHA_OPAQUE: Int = 0xFF shl 24

    /** Converts one BGR555 colour to opaque ARGB8888. Bit 15 is ignored. */
    public fun toArgb(c: Int): Int {
        val r = ((c shr RED_SHIFT) and CHANNEL_5BIT_MASK) shl 3
        val g = ((c shr GREEN_SHIFT) and CHANNEL_5BIT_MASK) shl 3
        val b = ((c shr BLUE_SHIFT) and CHANNEL_5BIT_MASK) shl 3
        return ALPHA_OPAQUE or (r shl 16) or (g shl 8) or b
    }

    /** Converts ARGB8888 to BGR555 by truncating each channel to its top 5 bits (alpha ignored). */
    public fun fromArgb(argb: Int): Int {
        val r = (argb shr 19) and CHANNEL_5BIT_MASK
        val g = (argb shr 11) and CHANNEL_5BIT_MASK
        val b = (argb shr 3) and CHANNEL_5BIT_MASK
        return (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or (b shl BLUE_SHIFT)
    }

    /** Reads [count] little-endian BGR555 colours from [src] at [offset] as ARGB8888. */
    public fun readPalette(src: ByteArray, offset: Int, count: Int): IntArray {
        require(offset >= 0 && count >= 0 && offset + count * 2 <= src.size) { "palette out of range" }
        return IntArray(count) { i ->
            val lo = src[offset + 2 * i].toInt() and 0xFF
            val hi = src[offset + 2 * i + 1].toInt() and 0xFF
            toArgb(lo or (hi shl 8))
        }
    }

    /** Writes ARGB8888 [colors] back as little-endian BGR555 bytes (inverse of [readPalette]). */
    public fun writePalette(colors: IntArray): ByteArray {
        val out = ByteArray(colors.size * 2)
        for (i in colors.indices) {
            val c = fromArgb(colors[i])
            out[2 * i] = c.toByte()
            out[2 * i + 1] = (c shr 8).toByte()
        }
        return out
    }
}
