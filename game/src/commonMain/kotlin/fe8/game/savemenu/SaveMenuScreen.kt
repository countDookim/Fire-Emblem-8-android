package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.LcdControl
import gbafe.render.OamBuffer
import gbafe.text.Font
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * Save menu screen initialization (`PC-4a2`, `src/savemenu.c` and `src/savedraw.c`): loads the
 * background tiles, fog overlay, OBJ sprite sheets, palettes, OAM affine identity matrices,
 * initial save slot metadata and palettes, sub-box text state, and chapter title graphics, then
 * starts `ProcScr_savedraw`.
 *
 * @param rom whole-ROM view supplying background/fog/sprite graphics, TSA, palettes, and sine LUT
 * @param hardware display hardware (`vram`, `tilemaps`, `palette`, `lcd`)
 * @param oam OAM shadow buffer whose affine matrices `0..3` are reset to identity
 * @param playSt persistent play state supplying [PlaySt.windowColor] for `LoadUiFrameGraphics`
 * @param text text renderer initialized for [subBoxFont] and [subBoxText]
 * @param chapterTitle chapter title loader (`PutChapterTitleBG` and `PutChapterTitleGfx`)
 * @param slotData slot metadata and palette loader (`SaveMenuInitSaveSlotData` / `SlotPalette`)
 * @param startSaveDraw `StartSaveDraw(proc)` launcher wired from `SaveDrawLifecycle`
 * @param loadObjUiGfx `LoadObjUIGfx()` loader wired from `ChapterMapGfx.loadObjUiGfx`
 * @param subBoxFont `gSaveMenuSubBoxFont` initialized by [saveMenuInitSubBoxText]
 * @param subBoxText `gSaveMenuSubBoxText` initialized by [saveMenuInitSubBoxText]
 * @param endingTmScratchA first two palettes (32 halfwords) of `gEndingTmScratchA`
 */
public class SaveMenuScreen(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val oam: OamBuffer,
    private val playSt: PlaySt,
    private val text: TextSystem,
    private val chapterTitle: ChapterTitle,
    private val slotData: SaveSlotData,
    private val startSaveDraw: (Proc) -> Proc?,
    private val loadObjUiGfx: () -> Unit,
    public val subBoxFont: Font = Font(),
    public val subBoxText: Text = Text(),
    public val endingTmScratchA: ShortArray = ShortArray(COPY_PALETTE_BANKS * PALETTE_BANK_COLORS),
) {
    /** Reusable `gGenericBuffer` (0x2000 bytes) for TSA decompression/staging. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /** Reusable 2-palette staging buffer for ROM-to-EWRAM `SaveMenuCopyPalette`. */
    private val scratchCopySrc = ShortArray(COPY_PALETTE_BANKS * PALETTE_BANK_COLORS)

    /**
     * Loads the save menu backgrounds, fog, OBJ sprites, palettes, OAM affine identity matrices,
     * initial slot metadata and palettes, sub-box text font, and chapter titles, then starts
     * `ProcScr_savedraw`.
     *
     * Transcribed from src/savemenu.c: SaveMenu_InitScreen (not verified: individual setup calls
     * masked by identical `f09444` pre-state are `resetTextFont`, `loadObjUiGfx`,
     * `gEndingTmScratchA` copy, `scrollCnt = 0`, `win0/win1_enableBlend = 1`, `hbEn = false`,
     * `setWinEnable(0, 0, 0)`, `palette[0] = 0`, and the second `enableBgSyncByMask(2)`; graphics,
     * tilemaps, palettes, LCD, OAM affine matrices, and VRAM are differential-checked at `r09458`/
     * `r09459`/`f09494` in `SaveMenuInitScreenDifferentialTest`).
     */
    public fun saveMenuInitScreen(proc: Proc) {
        val state = proc.state as SaveMenuState

        text.resetTextFont()

        UiFrame.loadUiFrameGraphics(hardware, rom, playSt.windowColor)
        loadObjUiGfx()

        val bg3Tiles = hardware.tileDataOffset(3)
        applyPalettes(PAL_SAVE_MENU_BG, OBJPAL_SAVEMENU_TITLEBG, SAVE_MENU_BG_PAL_COUNT)
        rom.decompressInto(IMG_SAVE_MENU_BG, hardware.vram, bg3Tiles)
        applyRomTsa(hardware.mapBuffer(3), TSA_SAVE_MENU_BG, BG3_TILE_BASE)

        applyPalettes(PAL_MAIN_MENU_BG_FOG, BGPAL_SAVEMENU_BGFOG, 1)
        rom.decompressInto(
            IMG_MAIN_MENU_BG_FOG,
            hardware.vram,
            bg3Tiles + BGCHR_SAVEMENU_BGFOG * TILE_SIZE_4BPP,
        )
        rom.decompressInto(TSA_MAIN_MENU_BG_FOG, genericBuffer, 0)
        BgTilemap.applyTsa(hardware.mapBuffer(2), 0, genericBuffer, 0, BG2_TILE_BASE)

        rom.decompressInto(
            IMG_SAVE_SCREEN_SPRITES,
            hardware.vram,
            OBJ_VRAM_OFFSET + OBJCHR_SAVEMENU_SPRITES * TILE_SIZE_4BPP,
        )
        applyPalettes(PAL_SAVE_SCREEN_SPRITES, OBJPAL_SAVEMENU_SPRITES + 0x10, SPRITE_PAL_COUNT)

        applyPalettes(PAL_08A295B4, BG_PAL_2, 1)
        saveMenuCopyPalette(
            hardware.palette,
            PAL_OBJ_2_BANK * PALETTE_BANK_COLORS,
            hardware.palette,
            PAL_OBJ_1_BANK * PALETTE_BANK_COLORS,
            1,
        )
        loadPaletteWords(PAL_08A2C23C, scratchCopySrc, COPY_PALETTE_BANKS)
        saveMenuCopyPalette(scratchCopySrc, 0, endingTmScratchA, 0, COPY_PALETTE_BANKS)

        hardware.enableBgSyncByMask(BG_ALL_SYNC_MASK)

        state.scrollCnt = 0

        hardware.lcd.setWincntBit(LcdControl.WIN0_ENABLE_BLEND, true)
        hardware.lcd.setWincntBit(LcdControl.WIN1_ENABLE_BLEND, true)

        state.cursorConfig = 0
        state.cursorSlot = EMPTY_SLOT_IDX
        state.unk3d = 0

        val cos0 = rom.u16(SIN_LOOKUP_ADDRESS + COS_OFFSET * 2).toShort().toInt()
        val sin0 = rom.u16(SIN_LOOKUP_ADDRESS).toShort().toInt()
        val pa = (cos0 * 16) / AFFINE_UNIT
        val pb = (-sin0 * 16) / AFFINE_UNIT
        val pc = (sin0 * 16) / AFFINE_UNIT
        val pd = (cos0 * 16) / AFFINE_UNIT
        for (i in 0 until AFFINE_MATRIX_COUNT) {
            oam.setObjAffine(i, pa, pb, pc, pd)
        }

        state.slots.unk44 = AFFINE_UNIT
        state.susSlotCur = EMPTY_SLOT_IDX
        state.hbEn = false
        state.ctrlTimer = 0

        for (i in 0 until INIT_SLOT_DATA_COUNT) {
            slotData.saveMenuInitSaveSlotData(i, state.slots)
        }

        slotData.saveMenuInitSlotPalette(state.susSlot)
        saveMenuInitSubBoxText()

        hardware.enableBgSyncByMask(BG1_SYNC_BIT)
        hardware.lcd.setWinEnable(0, 0, 0)
        hardware.palette[0] = 0
        hardware.enablePaletteSync()
        saveMenuPutChapterTitle(state)
        state.savedraw = startSaveDraw(proc)
    }

    /**
     * Loads the chapter title background graphic (`OBJCHR_SAVEMENU_TITLEBG`) and decompresses each
     * slot's chapter title image into OBJ VRAM (`OBJCHR_SAVEMENU_TITLEGFX`, `0x800` bytes apart).
     *
     * Transcribed from src/savemenu.c: SaveMenuPutChapterTitle (not verified: empty-slot
     * `chapterIdx == 0xFF` branch passing `-1` is unexercised in `ram_trace_prologue_end`, where
     * all three slots are valid `[0, 7, 6]`; valid-slot path verified at `r09459`/`f09494`).
     */
    public fun saveMenuPutChapterTitle(state: SaveMenuState) {
        chapterTitle.putChapterTitleBg(OBJ_PRIORITY_2 + OBJCHR_SAVEMENU_TITLEBG)
        for (i in 0 until SLOT_COUNT) {
            val baseBytes = (OBJ_PRIORITY_2 + OBJCHR_SAVEMENU_TITLEGFX) * TILE_SIZE_4BPP
            val chr = ((baseBytes + i * TITLE_SLOT_BYTES) and VRAM_OFFSET_MASK) / TILE_SIZE_4BPP
            val chapterIdx = state.slots.chapterIdx[i]
            val titleId = if (chapterIdx != EMPTY_SLOT_IDX) chapterIdx else -1
            chapterTitle.putChapterTitleGfx(chr, titleId)
        }
    }

    /**
     * Copies [count] 16-colour palette banks (`count * 16` halfwords) from [src] at [srcOffset]
     * into [dst] at [dstOffset].
     *
     * Transcribed from src/savemenu.c: SaveMenuCopyPalette (not verified: `count <= 0` early-return
     * guard is unexercised in `ram_trace_prologue_end`, where calls pass `count = 1` and
     * `count = 2`; `PAL_OBJ(2) -> PAL_OBJ(1)` copy verified at `r09450`/`r09458`).
     */
    public fun saveMenuCopyPalette(
        src: ShortArray,
        srcOffset: Int,
        dst: ShortArray,
        dstOffset: Int,
        count: Int,
    ) {
        val total = count * PALETTE_BANK_COLORS
        if (total <= 0) return
        for (i in 0 until total) {
            dst[dstOffset + i] = src[srcOffset + i]
        }
    }

    /**
     * Initializes [subBoxFont] (`gSaveMenuSubBoxFont`) at BG tile `0x80` (`VRAM 0x06001000`, BG
     * palette bank `4`) and [subBoxText] (`gSaveMenuSubBoxText`) with a 10-tile strip, making
     * [subBoxFont] the active font.
     *
     * Ported from src/savedraw.c: SaveMenuInitSubBoxText
     */
    public fun saveMenuInitSubBoxText() {
        text.initTextFont(
            subBoxFont,
            VRAM_BASE + BGCHR_SAVEMENU_SUBBOX_TEXT * TILE_SIZE_4BPP,
            BGCHR_SAVEMENU_SUBBOX_TEXT,
            BGPAL_SAVEMENU_SUBBOX_TEXT,
        )
        text.initText(subBoxText, SUBBOX_TEXT_TILES)
    }

    private fun applyPalettes(romAddr: Int, bankId: Int, bankCount: Int) {
        loadPaletteWords(romAddr, hardware.palette, bankCount, bankId * PALETTE_BANK_COLORS)
        hardware.enablePaletteSync()
    }

    private fun loadPaletteWords(
        romAddr: Int,
        dst: ShortArray,
        bankCount: Int,
        dstOffset: Int = 0,
    ) {
        val count = bankCount * PALETTE_BANK_COLORS
        for (i in 0 until count) {
            dst[dstOffset + i] = rom.u16(romAddr + 2 * i).toShort()
        }
    }

    private fun applyRomTsa(tilemap: ShortArray, romAddr: Int, tileBase: Int) {
        val wMinus1 = rom.u8(romAddr)
        val hMinus1 = rom.u8(romAddr + 1)
        val size = 2 + 2 * (wMinus1 + 1) * (hMinus1 + 1)
        for (i in 0 until size) {
            genericBuffer[i] = rom.u8(romAddr + i).toByte()
        }
        BgTilemap.applyTsa(tilemap, 0, genericBuffer, 0, tileBase)
    }

    /** ROM asset addresses and hardware layout constants from `savemenu.c` and `savemenu.h`. */
    public companion object {
        /** `BGCHR_SAVEMENU_SUBBOX_TEXT` (`include/savemenu.h`). */
        public const val BGCHR_SAVEMENU_SUBBOX_TEXT: Int = 0x80

        /** `BGPAL_SAVEMENU_SUBBOX_TEXT` (`include/savemenu.h`). */
        public const val BGPAL_SAVEMENU_SUBBOX_TEXT: Int = 4

        /** `BGCHR_SAVEMENU_BGFOG` (`include/savemenu.h`). */
        public const val BGCHR_SAVEMENU_BGFOG: Int = 0x260

        /** `BGPAL_SAVEMENU_BGFOG` (`include/savemenu.h`). */
        public const val BGPAL_SAVEMENU_BGFOG: Int = 7

        /** `OBJCHR_SAVEMENU_SPRITES` (`include/savemenu.h`). */
        public const val OBJCHR_SAVEMENU_SPRITES: Int = 0x40

        /** `OBJPAL_SAVEMENU_SPRITES` (`include/savemenu.h`). */
        public const val OBJPAL_SAVEMENU_SPRITES: Int = 2

        /** `OBJCHR_SAVEMENU_TITLEBG` (`include/savemenu.h`). */
        public const val OBJCHR_SAVEMENU_TITLEBG: Int = 0x2C0

        /** `OBJPAL_SAVEMENU_TITLEBG` (`include/savemenu.h`). */
        public const val OBJPAL_SAVEMENU_TITLEBG: Int = 8

        /** `OBJCHR_SAVEMENU_TITLEGFX` (`include/savemenu.h`). */
        public const val OBJCHR_SAVEMENU_TITLEGFX: Int = 0x340

        /** `Img_SaveMenuBG` (`data/data_A21658.s`). */
        public const val IMG_SAVE_MENU_BG: Int = 0x08A21658

        /** `Pal_SaveMenuBG` (`data/data_A21658.s`). */
        public const val PAL_SAVE_MENU_BG: Int = 0x08A25DCC

        /** `Tsa_SaveMenuBG` (`data/data_A21658.s`). */
        public const val TSA_SAVE_MENU_BG: Int = 0x08A25ECC

        /** `Img_MainMenuBgFog` (`data/data_A21658.s`). */
        public const val IMG_MAIN_MENU_BG_FOG: Int = 0x08A26380

        /** `Pal_MainMenuBgFog` (`data/data_A21658.s`). */
        public const val PAL_MAIN_MENU_BG_FOG: Int = 0x08A268D8

        /** `Tsa_MainMenuBgFog` (`data/data_A21658.s`). */
        public const val TSA_MAIN_MENU_BG_FOG: Int = 0x08A268F8

        /** `Img_SaveScreenSprits` (`data/data_A21658.s`). */
        public const val IMG_SAVE_SCREEN_SPRITES: Int = 0x08A26A74

        /** `Pal_SaveScreenSprits` (`data/data_A21658.s`). */
        public const val PAL_SAVE_SCREEN_SPRITES: Int = 0x08A27F68

        /** `Pal_08A295B4` (`data/data_A21658.s`). */
        public const val PAL_08A295B4: Int = 0x08A295B4

        /** `gUnknown_08A2C23C` (`data/data_A21658.s`). */
        public const val PAL_08A2C23C: Int = 0x08A2C23C

        /** `gSinLookup` (`data/trig_rodata.s`). */
        public const val SIN_LOOKUP_ADDRESS: Int = 0x080D751C

        private const val GENERIC_BUFFER_SIZE = 0x2000
        private const val PALETTE_BANK_COLORS = 16
        private const val COPY_PALETTE_BANKS = 2
        private const val SAVE_MENU_BG_PAL_COUNT = 8
        private const val SPRITE_PAL_COUNT = 8
        private const val BG_PAL_2 = 2
        private const val PAL_OBJ_1_BANK = 0x11
        private const val PAL_OBJ_2_BANK = 0x12
        private const val BG3_TILE_BASE = 0x8000
        private const val BG2_TILE_BASE = (BGPAL_SAVEMENU_BGFOG shl 12) + BGCHR_SAVEMENU_BGFOG
        private const val TILE_SIZE_4BPP = 0x20
        private const val VRAM_BASE = 0x06000000
        private const val OBJ_VRAM_OFFSET = 0x10000
        private const val VRAM_OFFSET_MASK = 0x1FFFF
        private const val OBJ_PRIORITY_2 = 2 shl 10
        private const val TITLE_SLOT_BYTES = 0x800
        private const val SLOT_COUNT = 3
        private const val INIT_SLOT_DATA_COUNT = 4
        private const val EMPTY_SLOT_IDX = 0xFF
        private const val AFFINE_MATRIX_COUNT = 4
        private const val AFFINE_UNIT = 0x100
        private const val COS_OFFSET = 0x40
        private const val SUBBOX_TEXT_TILES = 10
        private const val BG_ALL_SYNC_MASK = 0x0F
        private const val BG1_SYNC_BIT = 0x02
    }
}
