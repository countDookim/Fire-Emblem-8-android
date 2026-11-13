package gbafe.extract.fe8.ui

import gba.data.Bgr555
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json
import gbafe.extract.hexUpper

/**
 * FE8 map cursor, misc OBJ UI graphics and the menu hand cursor, from three places in the decomp.
 *
 * - `LoadObjUIGfx` (`src/bm.c`): `gGfx_MiscUiGraphics` (LZ77 4bpp) is copied with
 *   `Copy2dChr(.., 0x12, 4)` to OBJ VRAM 0x06010000, i.e. 18 tiles x 4 rows of the 32-tile-wide 2D
 *   OBJ layout; `gPal_MiscUiGraphics` (2 x 16 colors) goes to OBJ palettes 0..1
 *   (`ApplyPalettes(.., 0x10, 2)`; BG palettes come first, so 0x10 is OBJ palette 0). Written as
 *   an 18x4-tile sheet in palette 0 (`cursors.png`); both palettes are in the JSON.
 * - `PutMapCursor` (`src/bm.c`): `sMapCursorSpriteLut[16]` gives the sprite (OAM data list) per
 *   animation frame, picked as `(GetGameClock() / 2) % 16`; `sSprite_MapCursorStretched` is the
 *   extra sprite for cursor kind 3. Each list is `u16 n; n x {attr0, attr1, attr2}`, exported raw.
 *   The `oam2` base is 2 for kinds 0, 1, 3 and 0x24 for kinds 2 and 4 (exported as `normal` and
 *   `alt`; the decomp gives no name for what kinds 2 and 4 mean).
 * - Hand cursor (`src/cursor_hand.c`): `SetUiCursorHandConfig` decompresses
 *   `gImg_UiCursorHandTop`/`Bottom` (LZ77) to OBJ VRAM 0x060100C0 / 0x060104C0 (tile 6 of rows 0
 *   and 1) only when its `flags` has bit 1 set; those are written as `cursor_hand.png` with the
 *   top half above the bottom half. `UiCursorHand_Loop` draws the hand with `oam2` 0 (OBJ palette
 *   0), using chr 6 (these images) when flag bit 1 is set and chr 0 (the misc UI sheet) otherwise,
 *   so its colors come from whatever the calling screen loaded into OBJ palette 0. The PNG uses
 *   `gPal_MiscUiGraphics` palette 0 only as a preview (colors are not authoritative).
 */
public object CursorsExtractor {
    /** Result: PNG files by name and the `gbafe.cursors` document. */
    public class Output(
        /** PNG bytes by file name. */
        public val files: Map<String, ByteArray>,
        /** The `gbafe.cursors` document. */
        public val json: String,
    )

    /** Sheet width in tiles (`Copy2dChr` width). */
    public const val SHEET_TILES_WIDE: Int = 0x12

    /** Sheet height in tile rows (`Copy2dChr` height). */
    public const val SHEET_TILE_ROWS: Int = 4

    /**
     * Extracts from ROM file offsets: [gfx] `gGfx_MiscUiGraphics`, [pal] `gPal_MiscUiGraphics`,
     * [cursorLut] `sMapCursorSpriteLut` (16 pointers), [stretched] `sSprite_MapCursorStretched`,
     * [handTop]/[handBottom] `gImg_UiCursorHandTop`/`Bottom`, [handSprites] the hand's OAM lists
     * (`gSprite_UiCursorHand_*`).
     */
    public fun extract(
        rom: ByteArray, gfx: Int, pal: Int, cursorLut: Int, stretched: Int,
        handTop: Int, handBottom: Int,
        handSprites: List<Int>,
    ): Output {
        val files = LinkedHashMap<String, ByteArray>()
        val palettes = List(2) { Bgr555.readPalette(rom, pal + 32 * it, 16) }
        val sheet = Lz77.decode(rom, gfx).data
        val sheetTiles = SHEET_TILES_WIDE * SHEET_TILE_ROWS
        require(sheet.size >= sheetTiles * Tiles.BYTES_4BPP) {
            "misc UI gfx: ${sheet.size} bytes < $sheetTiles tiles"
        }
        files["cursors.png"] = IndexedPng.write(
            Tiles.decode4bpp(sheet, 0, sheetTiles, SHEET_TILES_WIDE), palettes[0],
        )

        val top = Lz77.decode(rom, handTop).data
        val bottom = Lz77.decode(rom, handBottom).data
        require(top.size == bottom.size && top.size % Tiles.BYTES_4BPP == 0) {
            "hand halves: ${top.size} / ${bottom.size} bytes"
        }
        val handWide = top.size / Tiles.BYTES_4BPP
        files["cursor_hand.png"] = IndexedPng.write(
            Tiles.decode4bpp(top + bottom, 0, 2 * handWide, handWide), palettes[0],
        )

        val lut = List(16) { u32(rom, cursorLut + 4 * it) - ROM }
        val distinct = (lut + stretched).distinct()
        val sprites = distinct.map { readSprite(rom, it) }
        val json = Json.write(
            linkedMapOf(
                "format" to "gbafe.cursors", "game" to "fe8", "version" to 1,
                "palettes" to palettes.map { p -> p.map { Bgr555.fromArgb(it) } },
                "sheet" to linkedMapOf(
                    "image" to "cursors.png", "tilesWide" to SHEET_TILES_WIDE,
                    "tileRows" to SHEET_TILE_ROWS, "objVram" to 0x06010000, "objPalette" to 0,
                ),
                "mapCursor" to linkedMapOf(
                    "sprites" to sprites,
                    "frames" to lut.map { distinct.indexOf(it) },
                    "stretched" to distinct.indexOf(stretched),
                    "oam2" to linkedMapOf("normal" to 2, "alt" to 0x24),
                ),
                "hand" to linkedMapOf(
                    "image" to "cursor_hand.png", "tilesWide" to handWide, "tileRows" to 2,
                    "objVram" to listOf(0x060100C0, 0x060104C0), "objPalette" to 0,
                    "paletteSource" to "caller",
                    "sprites" to handSprites.map { readSprite(rom, it) },
                ),
            ),
        )
        return Output(files, json)
    }

    /**
     * Reads one OAM data list (`u16 count; count x 3 u16`) at [offset]; the 1..32 object limit is
     * a sanity check on the ROM data, not a hardware limit.
     */
    private fun readSprite(rom: ByteArray, offset: Int): List<Map<String, Int>> {
        val objectCount = u16(rom, offset)
        require(objectCount in 1..32) { "sprite at 0x${hexUpper(offset)}: $objectCount objects" }
        return List(objectCount) { index ->
            linkedMapOf(
                "attr0" to u16(rom, offset + 2 + 6 * index),
                "attr1" to u16(rom, offset + 4 + 6 * index),
                "attr2" to u16(rom, offset + 6 + 6 * index),
            )
        }
    }

    /** Base address of the GBA ROM; subtracted from pointers to get file offsets. */
    private const val ROM = 0x08000000

    /** Reads a little-endian 16-bit value from the ROM. */
    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads a little-endian 32-bit value (a ROM pointer here) from the ROM. */
    private fun u32(bytes: ByteArray, offset: Int) =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
