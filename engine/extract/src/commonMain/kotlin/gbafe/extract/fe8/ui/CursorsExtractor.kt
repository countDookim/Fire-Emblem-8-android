package gbafe.extract.fe8.ui

import gba.data.Bgr555
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json
import gbafe.extract.hexUpper

/**
 * FE8 map cursor, misc OBJ UI graphics and the menu hand cursor.
 *
 * - `LoadObjUIGfx` (decomp `src/bm.c`): `gGfx_MiscUiGraphics` (LZ77 4bpp) is copied with `Copy2dChr(.., 0x12, 4)`
 *   to OBJ VRAM 0x06010000, i.e. 18 tiles × 4 rows of the 32-tile-wide 2D OBJ layout; palettes
 *   `gPal_MiscUiGraphics` (2 × 16) go to OBJ palettes 0..1 (`ApplyPalettes(.., 0x10, 2)`). Written as an
 *   18×4-tile sheet in palette 0 (`cursors.png`); both palettes are in the JSON.
 * - `PutMapCursor` (`src/bm.c`): `sMapCursorSpriteLut[16]` = sprite (OAM data list) per animation frame; each
 *   list is `u16 n; n × {attr0, attr1, attr2}`, exported raw. `oam2` base is 2 (cursor) or 0x24 (alt state).
 * - Hand cursor (`src/cursor_hand.c` `SetUiCursorHandConfig`): `gImg_UiCursorHandTop`/`Bottom` (LZ77) are
 *   decompressed to OBJ VRAM 0x060100C0 / 0x060104C0 (tile 6 of rows 0 and 1), written as `cursor_hand.png`
 *   with the top half above the bottom half. `UiCursorHand_Loop` draws it with `oam2` 0 (OBJ palette 0, chr 0 or
 *   6), so its colors come from whatever the calling screen loaded into OBJ palette 0; the PNG uses
 *   `gPal_MiscUiGraphics` palette 0 only as a stand-in (colors are not authoritative).
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
     * Extracts from ROM offsets: [gfx] `gGfx_MiscUiGraphics`, [pal] `gPal_MiscUiGraphics`, [cursorLut]
     * `sMapCursorSpriteLut` (16 pointers), [stretched] `sSprite_MapCursorStretched`, [handTop]/[handBottom]
     * `gImg_UiCursorHandTop`/`Bottom`, [handSprites] the hand's OAM lists (`gSprite_UiCursorHand_*`).
     */
    public fun extract(
        rom: ByteArray, gfx: Int, pal: Int, cursorLut: Int, stretched: Int, handTop: Int, handBottom: Int,
        handSprites: List<Int>,
    ): Output {
        val files = LinkedHashMap<String, ByteArray>()
        val palettes = List(2) { Bgr555.readPalette(rom, pal + 32 * it, 16) }
        val sheet = Lz77.decode(rom, gfx).data
        val sheetTiles = SHEET_TILES_WIDE * SHEET_TILE_ROWS
        require(sheet.size >= sheetTiles * Tiles.BYTES_4BPP) { "misc UI gfx: ${sheet.size} bytes < $sheetTiles tiles" }
        files["cursors.png"] = IndexedPng.write(Tiles.decode4bpp(sheet, 0, sheetTiles, SHEET_TILES_WIDE), palettes[0])

        val top = Lz77.decode(rom, handTop).data
        val bottom = Lz77.decode(rom, handBottom).data
        require(top.size == bottom.size && top.size % Tiles.BYTES_4BPP == 0) { "hand halves: ${top.size} / ${bottom.size} bytes" }
        val handWide = top.size / Tiles.BYTES_4BPP
        files["cursor_hand.png"] = IndexedPng.write(Tiles.decode4bpp(top + bottom, 0, 2 * handWide, handWide), palettes[0])

        val lut = List(16) { u32(rom, cursorLut + 4 * it) - ROM }
        val distinct = (lut + stretched).distinct()
        val sprites = distinct.map { readSprite(rom, it) }
        val json = Json.write(
            linkedMapOf(
                "format" to "gbafe.cursors", "game" to "fe8", "version" to 1,
                "palettes" to palettes.map { p -> p.map { Bgr555.fromArgb(it) } },
                "sheet" to linkedMapOf("image" to "cursors.png", "tilesWide" to SHEET_TILES_WIDE, "tileRows" to SHEET_TILE_ROWS,
                    "objVram" to 0x06010000, "objPalette" to 0),
                "mapCursor" to linkedMapOf(
                    "sprites" to sprites,
                    "frames" to lut.map { distinct.indexOf(it) },
                    "stretched" to distinct.indexOf(stretched),
                    "oam2" to linkedMapOf("normal" to 2, "alt" to 0x24),
                ),
                "hand" to linkedMapOf("image" to "cursor_hand.png", "tilesWide" to handWide, "tileRows" to 2,
                    "objVram" to listOf(0x060100C0, 0x060104C0), "objPalette" to 0, "paletteSource" to "caller",
                    "sprites" to handSprites.map { readSprite(rom, it) }),
            ),
        )
        return Output(files, json)
    }

    private fun readSprite(rom: ByteArray, o: Int): List<Map<String, Int>> {
        val n = u16(rom, o)
        require(n in 1..32) { "sprite at 0x${hexUpper(o)}: $n objects" }
        return List(n) { k -> linkedMapOf("attr0" to u16(rom, o + 2 + 6 * k), "attr1" to u16(rom, o + 4 + 6 * k), "attr2" to u16(rom, o + 6 + 6 * k)) }
    }

    private const val ROM = 0x08000000
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
