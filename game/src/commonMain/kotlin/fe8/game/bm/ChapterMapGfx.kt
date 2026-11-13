package fe8.game.bm

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * Battle map graphics loading (src/bmmap.c and src/bm.c): the chapter tileset (the terrain
 * tiles the map is drawn with) into BG VRAM and palette banks 6-15, the BG layer priorities, and
 * the OBJ UI graphics (the small map icons and cursor pieces) and their palettes. Palette numbers
 * here are indices into the palette buffer in 16 color banks: 0-15 are BG banks, 16-31 OBJ banks.
 *
 * The `ResetText`, `LoadLegacyUiFrameGraphics`, `ResetFaces`, `ResetIconGraphics_` and
 * `LoadIconPalettes` calls of [sub80156D4] are the hooks [resetText] and friends; `Fe8Boot` wires
 * all but `ResetFaces` (a stand-in until M6-F, the stat screen faces).
 */
public class ChapterMapGfx(
    /** ROM the tileset and UI graphics are read from. */
    private val rom: RomView,
    /** Chapter table access (`map.obj1Id`, `obj2Id`, `paletteId`). */
    private val chapterData: ChapterData,
    /** BG VRAM, palette buffer and `gLCDControlBuffer`. */
    private val hardware: BgHardware,
    /** `gPlaySt` (`chapterIndex`, `chapterWeatherId`). */
    private val playSt: PlaySt,
    /** `ResetText`; set by `Fe8Boot`. */
    public var resetText: () -> Unit = {},
    /** `LoadLegacyUiFrameGraphics`; set by `Fe8Boot`. */
    public var loadLegacyUiFrameGraphics: () -> Unit = {},
    /** Stand-in until M6-F for `ResetFaces` (the stat screen faces). */
    public var resetFaces: () -> Unit = {},
    /** `ResetIconGraphics_`; set by `Fe8Boot`. */
    public var resetIconGraphics: () -> Unit = {},
    /** `LoadIconPalettes(palId)`; set by `Fe8Boot`. */
    public var loadIconPalettes: (Int) -> Unit = {},
) {
    /** `gGenericBuffer` (0x2000 bytes): scratch space [loadObjUiGfx] decompresses into. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /** Looks up the 32-bit ROM pointer at index [assetId] of `gChapterDataAssetTable`. */
    private fun asset(assetId: Int): Int =
        rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * assetId)

    /**
     * `ApplyPalettes` (include/hardware.h macro over `CopyToPaletteBuffer`): copies [bankCount]
     * palettes of 16 colors from ROM address [paletteAddress] into `gPaletteBuffer` from palette
     * [paletteBankId] on (0-15 BG, 16-31 OBJ), then requests the copy to palette RAM at the next
     * VBlank.
     */
    private fun applyPalettes(paletteAddress: Int, paletteBankId: Int, bankCount: Int) {
        val colors = rom.pal(paletteAddress, 16 * bankCount)
        colors.copyInto(hardware.palette, paletteBankId * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Transcribed from src/bmmap.c: UnpackChapterMapGraphics (not verified: both traced chapters
     * have obj2Id 0, so the second tileset branch never runs)
     *
     * Loads the terrain tileset of [chapterId]: decompresses its tile graphics into BG VRAM
     * `+0x8000` (BG tile 0x400, `map.obj1Id`) and, when the chapter has a second part,
     * `+0xC000` (BG tile 0x600, `map.obj2Id`), and applies its 10 palettes to BG banks 6-15
     * (`map.paletteId`). Called when the chapter map is built (`InitChapterMap`, src/bmmap.c)
     * and by the chapter intro (src/chapterintrofx.c).
     */
    public fun unpackChapterMapGraphics(chapterId: Int) {
        rom.decompressInto(asset(chapterData.obj1Id(chapterId)), hardware.vram, TILESET_PART1)
        val obj2 = asset(chapterData.obj2Id(chapterId))
        if (obj2 != 0) rom.decompressInto(obj2, hardware.vram, TILESET_PART2)
        applyPalettes(asset(chapterData.paletteId(chapterId)), TILESET_PAL, TILESET_PAL_COUNT)
    }

    /**
     * Ported from src/bmmap.c: UnpackChapterMapPalette
     *
     * Re-applies the tileset palette of `gPlaySt.chapterIndex` to BG banks 6-15 (10 palettes):
     * restores the map colors after an effect has changed them, for example the map fade-out
     * (`EfxChapterMapFadeOUT`), the flames weather gradient and the event screen setups.
     */
    public fun unpackChapterMapPalette() {
        applyPalettes(
            asset(chapterData.paletteId(playSt.chapterIndex)),
            TILESET_PAL,
            TILESET_PAL_COUNT,
        )
    }

    /**
     * `InitBmBgLayers` (src/bm.c) for this chapter: sets the BG priorities of the map screen to
     * BG0 0, BG1 1, BG2 2, BG3 3, except BG3 is 2 (level with BG2) when the weather is clouds
     * (`WEATHER_CLOUDS`); delegates to the shared port [BgHardware.initBmBgLayers] with the
     * weather from [playSt].
     */
    public fun initBmBgLayers() {
        hardware.initBmBgLayers(playSt.chapterWeatherId == WEATHER_CLOUDS)
    }

    /**
     * Ported from src/bm.c: LoadObjUIGfx
     *
     * Loads the shared OBJ UI graphics: decompresses `gGfx_MiscUiGraphics`, copies its 0x12 x 4
     * tiles to the start of OBJ VRAM (`0x06010000`, tile 0; rows 0x20 tiles apart) and applies
     * `gPal_MiscUiGraphics` to OBJ palettes 0x10-0x11 (`gGenericBuffer` is the scratch buffer).
     * The map icon tiles that `UnitSpritesOam` draws (tiles 3, 0x10, 0x11 and 0x51) lie inside
     * this block. Called when the map or a screen that draws OBJ icons is set up
     * ([sub80156D4], class change, preparations, the item trade screen).
     */
    public fun loadObjUiGfx() {
        rom.decompressInto(GFX_MISC_UI, genericBuffer, 0)
        copy2dChr(genericBuffer, OBJ_VRAM0, MISC_UI_WIDTH, MISC_UI_HEIGHT)
        applyPalettes(PAL_MISC_UI, OBJ_PAL, OBJ_PAL_COUNT)
    }

    /**
     * Ported from src/bm.c: ApplySystemObjectsPalettes
     *
     * Re-applies only the palettes of [loadObjUiGfx] (`gPal_MiscUiGraphics`, OBJ palettes
     * 0x10-0x11), without reloading the graphics.
     */
    public fun applySystemObjectsPalettes() {
        applyPalettes(PAL_MISC_UI, OBJ_PAL, OBJ_PAL_COUNT)
    }

    /**
     * Ported from src/bm.c: sub_80156D4
     *
     * The graphics reset run when the battle map starts (`StartBattleMap`, src/bmio.c) and by the
     * chapter intro (src/chapterintrofx.c) and src/eventscr3.c: resets text, loads the legacy
     * UI frame graphics, resets faces and item icon graphics, loads the item icon palettes into
     * BG palettes 4-5 ([ICON_PAL_ID]), then [loadObjUiGfx]. `sub_80156D4` in the decomp, its
     * purpose is not named there; `ReadGameSaveCoreGfx` is the same sequence with the regular
     * UI frame graphics.
     */
    public fun sub80156D4() {
        resetText()
        loadLegacyUiFrameGraphics()
        resetFaces()
        resetIconGraphics()
        loadIconPalettes(ICON_PAL_ID)
        loadObjUiGfx()
    }

    /**
     * Transcribed from src/bmlib.c: Copy2dChr (not verified: no test checks it against a trace yet)
     *
     * Copies [heightInTiles] rows of [widthInTiles] 4bpp tiles from [source] (packed rows) to VRAM
     * offset [destVramOffset], whose rows are 0x20 tiles (0x400 bytes) apart, so a graphic
     * drawn as a 2D block lands in the 2D tile layout of the OBJ tile sheet.
     */
    private fun copy2dChr(
        source: ByteArray,
        destVramOffset: Int,
        widthInTiles: Int,
        heightInTiles: Int,
    ) {
        val lineSize = widthInTiles * CHR_SIZE
        for (row in 0 until heightInTiles) {
            val destOffset = destVramOffset + row * CHR_SIZE * 0x20
            source.copyInto(hardware.vram, destOffset, row * lineSize, (row + 1) * lineSize)
        }
    }

    /** Layout and asset constants. */
    public companion object {
        /** `WEATHER_CLOUDS` (types.h): the clouds weather, which changes the BG3 priority. */
        public const val WEATHER_CLOUDS: Int = 7

        /** VRAM offset of BG tile 0x400 (0x8000), where primary tileset graphics are loaded. */
        public const val TILESET_PART1: Int = 0x8000

        /** VRAM offset of BG tile 0x600 (0xC000), where secondary tileset graphics are loaded. */
        public const val TILESET_PART2: Int = 0xC000

        /** First palette bank of the tileset palette (bank 6). */
        public const val TILESET_PAL: Int = 6

        /** Number of tileset palette banks (banks 6..15 = 10 banks). */
        public const val TILESET_PAL_COUNT: Int = 10

        /** VRAM offset of OBJ VRAM bank 0 (`0x06010000`). */
        public const val OBJ_VRAM0: Int = 0x10000

        /** First palette of the OBJ UI palettes: 0x10, the first OBJ bank. */
        public const val OBJ_PAL: Int = 0x10

        /** Number of OBJ UI palettes (2: OBJ banks 0 and 1). */
        public const val OBJ_PAL_COUNT: Int = 2

        /** ROM address of `gGfx_MiscUiGraphics` compressed image. */
        public const val GFX_MISC_UI: Int = 0x0859E8E0

        /** ROM address of `gPal_MiscUiGraphics` palette. */
        public const val PAL_MISC_UI: Int = 0x0859ED70

        /** Width in 8x8 tiles of the `gGfx_MiscUiGraphics` block (0x12 = 18 tiles). */
        public const val MISC_UI_WIDTH: Int = 0x12

        /** Height in 8x8 tiles of the `gGfx_MiscUiGraphics` block (4 tile rows). */
        public const val MISC_UI_HEIGHT: Int = 4

        /** `CHR_SIZE`: bytes per 4bpp 8x8 tile (32 bytes). */
        public const val CHR_SIZE: Int = 0x20

        /** Byte size of the `gGenericBuffer` scratch memory (0x2000). */
        public const val GENERIC_BUFFER_SIZE: Int = 0x2000

        /** The `LoadIconPalettes` argument: item icon palettes go to BG palettes 4 and 5. */
        public const val ICON_PAL_ID: Int = 4
    }
}
