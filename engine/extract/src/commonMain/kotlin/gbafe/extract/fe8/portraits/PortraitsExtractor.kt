package gbafe.extract.fe8.portraits

import gba.data.Bgr555
import gba.data.IndexedImage
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json
import gbafe.extract.hexUpper
import gbafe.extract.padDec

/**
 * Extracts FE8 portraits (`portrait_data[]`, `struct FaceData` in decomp `include/types.h`: `img`,
 * `imgChibi`, `pal`, `imgMouth`, `imgCard` pointers, then `xMouth, yMouth, xEyes, yEyes,
 * blinkKind`; 0x19 bytes of fields padded to 28 per table row). Portrait id `fid` is entry
 * `fid - 1` (`src/face.c: GetPortraitData`).
 *
 * Images are written as raw 4bpp tile sheets with the portrait's 16-color palette (index 0 =
 * transparent), unassembled: arranging the sheet into the 96×80 face is the face renderer's job
 * (M3).
 * - `img`: `Decompress` stream (type 0x00 raw in FE8) of 128 tiles → 32×4-tile sheet;
 * - `imgChibi` (LZ77, 16 tiles) → 4×4 tiles; `imgMouth` (raw, 48 tiles: 6 frames of 4×2 tiles)
 *   → 4×12;
 * - `imgCard` (LZ77, 90 tiles; generic NPC cards) → 10×9 tiles.
 * Entries sharing the same images (e.g. eyes-closed variants) reference the same files.
 */
public object PortraitsExtractor {
    /** Size of `struct FaceData`. */
    public const val ENTRY_SIZE: Int = 28

    /** Result: image files (relative path → PNG bytes) and the metadata rows. */
    public class Output(
        /** PNG files keyed by path relative to the JSON's directory. */
        public val files: Map<String, ByteArray>,
        /** One row per portrait id, as written to [json]. */
        public val entries: List<Map<String, Any?>>,
    ) {
        /** The `portraits.json` document (format `gbafe.portraits`). */
        public val json: String
            get() = Json.write(
                linkedMapOf(
                    "format" to "gbafe.portraits", "game" to "fe8", "version" to 1,
                    "entries" to entries,
                ),
            )
    }

    /** Extracts [count] entries of the table at ROM offset [tableOffset]. */
    public fun extract(rom: ByteArray, tableOffset: Int, count: Int): Output {
        val files = LinkedHashMap<String, ByteArray>()
        val names = HashMap<Long, String>()
        val entries = ArrayList<Map<String, Any?>>()
        for (i in 0 until count) {
            val e = tableOffset + i * ENTRY_SIZE
            // Pointers: +0 img, +4 chibi, +8 palette, +12 mouth, +16 card; +20..+24 are
            // xMouth, yMouth, xEyes, yEyes, blinkKind.
            val img = u32(rom, e)
            val chibi = u32(rom, e + 4)
            val pal = u32(rom, e + 8)
            val mouth = u32(rom, e + 12)
            val card = u32(rom, e + 16)
            val fid = i + 1
            fun sheet(
                kind: String,
                ptr: Int,
                tiles: (ByteArray) -> Pair<ByteArray, Int>,
                perRow: Int,
            ): String? {
                if (ptr == 0) return null
                // Dedup key: same image pointer + same palette => reuse the already written file.
                val key = (ptr.toLong() shl 32) or (pal.toLong() and 0xFFFFFFFFL)
                names[key]?.let { return it }
                val (data, tileCount) = tiles(rom)
                val image: IndexedImage = Tiles.decode4bpp(data, 0, tileCount, perRow)
                val path = "$kind/${padDec(fid, 3)}.png"
                files[path] = IndexedPng.write(image, Bgr555.readPalette(rom, rel(pal), 16))
                names[key] = path
                return path
            }
            entries += linkedMapOf(
                "fid" to fid,
                "sheet" to sheet("sheet", img, { r ->
                    decompress(r, rel(img)).let { it to it.size / Tiles.BYTES_4BPP }
                }, 32),
                "chibi" to sheet("chibi", chibi, { r ->
                    Lz77.decode(r, rel(chibi)).data.let { it to it.size / Tiles.BYTES_4BPP }
                }, 4),
                "mouth" to sheet("mouth", mouth, { r ->
                    r.copyOfRange(rel(mouth), rel(mouth) + MOUTH_TILES * Tiles.BYTES_4BPP) to
                        MOUTH_TILES
                }, 4),
                "card" to sheet("card", card, { r ->
                    Lz77.decode(r, rel(card)).data.let { it to it.size / Tiles.BYTES_4BPP }
                }, 10),
                "mouthPos" to listOf(rom[e + 20].u8, rom[e + 21].u8),
                "eyesPos" to listOf(rom[e + 22].u8, rom[e + 23].u8),
                "blink" to rom[e + 24].u8,
            )
        }
        return Output(files, entries)
    }

    /** Mouth frames: 6 frames of 4×2 tiles. */
    public const val MOUTH_TILES: Int = 48

    /** `Decompress` (`src/bmlib.c`): type 0x00 = raw copy of `size - 4` bytes, 0x10 = LZ77. */
    public fun decompress(rom: ByteArray, offset: Int): ByteArray = when (rom[offset].u8 and 0xF0) {
        0x00 -> {
            val size =
                rom[offset + 1].u8 or (rom[offset + 2].u8 shl 8) or (rom[offset + 3].u8 shl 16)
            rom.copyOfRange(offset + 4, offset + size)
        }
        Lz77.TYPE -> Lz77.decode(rom, offset).data
        else -> throw IllegalArgumentException(
            "unsupported compression 0x${hexUpper(rom[offset].u8, 2)} at 0x${hexUpper(offset)}",
        )
    }

    private val Byte.u8 get() = toInt() and 0xFF

    /** Converts a GBA ROM pointer (0x08xxxxxx) to an offset into the ROM image. */
    private fun rel(ptr: Int): Int {
        require(ptr ushr 25 == 4) { "not a ROM pointer: 0x${ptr.toUInt().toString(16)}" }
        return ptr - 0x08000000
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].u8) or (b[o + 1].u8 shl 8) or (b[o + 2].u8 shl 16) or (b[o + 3].u8 shl 24)
}
