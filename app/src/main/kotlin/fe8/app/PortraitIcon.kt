/**
 * The runtime recents icon built from the player's own ROM (decision 0011, CLAUDE.md rule 3): the
 * portrait of `CHARACTER_EIRIKA` cropped to a square and scaled up. The pixels exist only in
 * memory (and in the system's recents task switcher); nothing of them is in the APK or the repo.
 */

package fe8.app

import gba.data.IndexedImage
import gbafe.extract.fe8.characters.CharactersExtractor
import gbafe.extract.fe8.portraits.PortraitsExtractor

/** Pure ROM-bytes to icon-pixels builder; no Android types, so it runs in JVM unit tests. */
internal object PortraitIcon {
    /** ROM offset of `gCharacterData` (0x08803D64 in `fireemblem8.nm.txt`). */
    const val CHARACTER_TABLE: Int = 0x803D64

    /** ROM offset of `portrait_data` (0x088ACBE0 in `fireemblem8.nm.txt`). */
    const val PORTRAIT_TABLE: Int = 0x8ACBE0

    /** `CHARACTER_EIRIKA` (decomp `include/constants/characters.h`); row `id - 1` of the table. */
    const val CHARACTER_EIRIKA: Int = 0x01

    /** Offset of `portraitId` (u16) in `struct CharacterData` (decomp `include/bmunit.h`). */
    private const val PORTRAIT_ID_OFFSET = 6

    /** Left edge of the square crop in face pixels (the face is 64 wide: full width). */
    private const val CROP_X = 0

    /** Top edge of the square crop in face pixels; skips the top 4 rows of the 80-row face. */
    private const val CROP_Y = 4

    /** Edge of the square crop in face pixels: rows 4 to 67 of the 64 x 80 face. */
    private const val CROP_SIZE = 64

    /** Output edge in pixels: the crop scaled 3x with nearest-neighbour. */
    const val SIZE: Int = CROP_SIZE * 3

    /**
     * Portrait id of `CHARACTER_EIRIKA`: `gCharacterData[CHARACTER_EIRIKA - 1].portraitId`,
     * read straight from the ROM table (little-endian u16).
     *
     * @param rom The whole FE8 (U) ROM image.
     * @return The portrait id; the face is entry `id - 1` of `portrait_data`.
     */
    fun portraitId(rom: ByteArray): Int {
        val o = CHARACTER_TABLE + (CHARACTER_EIRIKA - 1) * CharactersExtractor.ENTRY_SIZE +
            PORTRAIT_ID_OFFSET
        return (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Builds the icon: decodes the lord's face with [PortraitsExtractor.face], crops the square
     * and scales it up with nearest-neighbour. Palette index 0 becomes fully transparent.
     *
     * @param rom The whole FE8 (U) ROM image.
     * @return [SIZE] x [SIZE] ARGB pixels, row-major (suits `Bitmap.setPixels`).
     */
    fun build(rom: ByteArray): IntArray {
        val face = PortraitsExtractor.face(rom, PORTRAIT_TABLE, portraitId(rom))
        return scale(face.image, face.palette)
    }

    /**
     * Crops the [CROP_SIZE] square out of [image] and enlarges it to [SIZE] by repeating each
     * source pixel; palette index 0 (the GBA's transparent colour) becomes ARGB 0.
     */
    private fun scale(image: IndexedImage, palette: IntArray): IntArray {
        val scale = SIZE / CROP_SIZE
        val out = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val index = image[CROP_X + x / scale, CROP_Y + y / scale]
                out[y * SIZE + x] = if (index == 0) 0 else palette[index]
            }
        }
        return out
    }
}
