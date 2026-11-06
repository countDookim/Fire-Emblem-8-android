package fe8.game.bm

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * Battle map graphics loading (src/bmmap.c and src/bm.c): the chapter tileset into BG VRAM and
 * palette banks 6-15, the BG layer priorities, and the OBJ UI graphics and palettes.
 *
 * Stand-ins: the `ResetText`, `LoadLegacyUiFrameGraphics`, `ResetFaces`, `ResetIconGraphics_` and
 * `LoadIconPalettes` calls of [sub80156D4] are the hooks [resetText] and friends (they do nothing
 * until those modules are ported).
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
    /** Stand-in for `ResetText`. */
    public var resetText: () -> Unit = {},
    /** Stand-in for `LoadLegacyUiFrameGraphics`. */
    public var loadLegacyUiFrameGraphics: () -> Unit = {},
    /** Stand-in for `ResetFaces`. */
    public var resetFaces: () -> Unit = {},
    /** Stand-in for `ResetIconGraphics_`. */
    public var resetIconGraphics: () -> Unit = {},
    /** Stand-in for `LoadIconPalettes(palId)`. */
    public var loadIconPalettes: (Int) -> Unit = {},
) {
    /** `gGenericBuffer` (0x2000 bytes): scratch space [loadObjUiGfx] decompresses into. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    private fun asset(id: Int): Int = rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * id)

    /** `ApplyPalettes(src, palId, count)` with a ROM [src]: [count] banks into `gPaletteBuffer`. */
    private fun applyPalettes(src: Int, palId: Int, count: Int) {
        val colors = rom.pal(src, 16 * count)
        colors.copyInto(hardware.palette, palId * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Transcribed from src/bmmap.c: UnpackChapterMapGraphics (not verified: both traced chapters
     * have obj2Id 0, so the second tileset branch never runs)
     *
     * Decompresses the tileset graphics of [chapterId] into BG VRAM `+0x8000` (obj1) and, when
     * present, `+0xC000` (obj2), and applies its palette to banks 6-15.
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
     * Applies the palette of `gPlaySt.chapterIndex` to banks 6-15.
     */
    public fun unpackChapterMapPalette() {
        applyPalettes(
            asset(chapterData.paletteId(playSt.chapterIndex)),
            TILESET_PAL,
            TILESET_PAL_COUNT,
        )
    }

    /**
     * `InitBmBgLayers` (src/bm.c) for this chapter: delegates to the shared port
     * [BgHardware.initBmBgLayers] with the weather from [playSt].
     */
    public fun initBmBgLayers() {
        hardware.initBmBgLayers(playSt.chapterWeatherId == WEATHER_CLOUDS)
    }

    /**
     * Ported from src/bm.c: LoadObjUIGfx
     *
     * Decompresses `gGfx_MiscUiGraphics`, copies 0x12 x 4 tiles into OBJ VRAM bank 0 and applies
     * `gPal_MiscUiGraphics` to palette banks 0x10-0x11 (`gGenericBuffer` is used as scratch).
     */
    public fun loadObjUiGfx() {
        rom.decompressInto(GFX_MISC_UI, genericBuffer, 0)
        copy2dChr(genericBuffer, OBJ_VRAM0, MISC_UI_WIDTH, MISC_UI_HEIGHT)
        applyPalettes(PAL_MISC_UI, OBJ_PAL, OBJ_PAL_COUNT)
    }

    /**
     * Ported from src/bm.c: ApplySystemObjectsPalettes
     *
     * Applies `gPal_MiscUiGraphics` to palette banks 0x10-0x11.
     */
    public fun applySystemObjectsPalettes() {
        applyPalettes(PAL_MISC_UI, OBJ_PAL, OBJ_PAL_COUNT)
    }

    /**
     * Ported from src/bm.c: sub_80156D4
     *
     * Text, UI frame, face and icon resets (stand-in hooks), then [loadObjUiGfx].
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
     * Copies [height] rows of [width] 4bpp tiles from [src] (packed rows) to VRAM offset [dst],
     * whose rows are 0x20 tiles (0x400 bytes) apart.
     */
    private fun copy2dChr(src: ByteArray, dst: Int, width: Int, height: Int) {
        val lineSize = width * CHR_SIZE
        for (row in 0 until height) {
            val to = dst + row * CHR_SIZE * 0x20
            src.copyInto(hardware.vram, to, row * lineSize, (row + 1) * lineSize)
        }
    }

    /** Layout constants. */
    public companion object {
        /** `WEATHER_CLOUDS` (types.h). */
        public const val WEATHER_CLOUDS: Int = 7

        /** VRAM offset of BG tile 0x400, where tileset part 1 goes. */
        public const val TILESET_PART1: Int = 0x8000

        /** VRAM offset of BG tile 0x600, where tileset part 2 goes. */
        public const val TILESET_PART2: Int = 0xC000

        /** First palette bank of the tileset palette. */
        public const val TILESET_PAL: Int = 6

        /** Number of tileset palette banks. */
        public const val TILESET_PAL_COUNT: Int = 10

        /** VRAM offset of OBJ VRAM bank 0 (`0x06010000`). */
        public const val OBJ_VRAM0: Int = 0x10000

        /** First palette bank of the OBJ UI palettes. */
        public const val OBJ_PAL: Int = 0x10

        /** Number of OBJ UI palette banks. */
        public const val OBJ_PAL_COUNT: Int = 2

        /** `gGfx_MiscUiGraphics` ROM address. */
        public const val GFX_MISC_UI: Int = 0x0859E8E0

        /** `gPal_MiscUiGraphics` ROM address. */
        public const val PAL_MISC_UI: Int = 0x0859ED70

        /** Width in tiles of the OBJ UI graphics copy. */
        public const val MISC_UI_WIDTH: Int = 0x12

        /** Height in tiles of the OBJ UI graphics copy. */
        public const val MISC_UI_HEIGHT: Int = 4

        /** `CHR_SIZE`: bytes per 4bpp tile. */
        public const val CHR_SIZE: Int = 0x20

        /** `gGenericBuffer` size. */
        public const val GENERIC_BUFFER_SIZE: Int = 0x2000

        /** Argument of `LoadIconPalettes` in `sub_80156D4`. */
        public const val ICON_PAL_ID: Int = 4
    }
}
