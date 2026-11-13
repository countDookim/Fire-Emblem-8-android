package fe8.game.opanim

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * Opening monologue initialization and the shared screen-building helpers. [rom] must contain the
 * monologue's BG configuration tables and graphics; [startBgm] stands in for
 * `StartBgm(song, player)`.
 */
public class OpSubtitleSetup(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val startBgm: (song: Int, player: Int) -> Unit = { _, _ -> },
) {
    /**
     * Sets up the monologue screen: the `gBgConfig_OpSubtitle` backgrounds, mode 1 with BG0 and
     * OBJ shown and BG2 (priority 0, 256x256 affine, no wrap) holding the lens-flare graphics,
     * all windows off, alpha blend with BG2 as first target and BG0, BG1 and OBJ as second, the
     * lens-flare palettes in BG palette banks 0..2, `SONG_PROLOGUE`, slide index 0 and a first
     * hold of 60 frames. The sound request is only forwarded to [startBgm]; its audio is not
     * verified.
     *
     * Ported from src/opsubtitle.c: OpSubtitle_Init
     */
    public fun init(state: OpSubtitleSlides.State) {
        val lcd = hardware.lcd
        hardware.setupBackgrounds(IntArray(12) { rom.u16(BG_CONFIG_SUBTITLE + 2 * it) })
        lcd.dispcnt = (lcd.dispcnt and 7.inv()) or 1
        for (bg in 0..3) {
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or (if (bg == 2) 0 else 3)
        }
        lcd.setDispEnable(1, 0, 0, 0, 1)
        // BG2: screenSize = 1 (256x256 affine), areaOverflowMode = 0 (no wraparound).
        lcd.bgCnt[2] = (lcd.bgCnt[2] and 0xE000.inv()) or 0x4000
        lcd.setWinEnable(0, 0, 0)
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.setBlendTargetA(0, 0, 1, 0, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 1)
        rom.decompressInto(LENS_FLARE_IMG, hardware.vram, hardware.tileDataOffset(2))
        rom.pal(LENS_FLARE_PAL, 3 * 16).copyInto(hardware.palette, 0)
        hardware.enablePaletteSync()
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask(4)
        startBgm(SONG_PROLOGUE, 0)
        state.index = 0
        state.holdTimer = 60
    }

    /**
     * Resets the four BG controls and applies the table `gUnknown_08AA21A4` (tile base, map base
     * and screen size per BG), used by the final scene; it differs from the first table only in
     * BG2's tile base (0 instead of 0x8000). All BG scrolls are reset to 0.
     *
     * Ported from src/opsubtitle.c: OpSubtitle_SetupBackgrounds
     */
    public fun setupBackgrounds() {
        val lcd = hardware.lcd
        for (bg in 0..3) lcd.bgCnt[bg] = 0
        for (bg in 0..3) {
            hardware.setTileDataOffset(bg, rom.u16(BG_CONFIG_SCENE + 6 * bg))
            hardware.setMapDataOffset(bg, rom.u16(BG_CONFIG_SCENE + 6 * bg + 2))
            hardware.setScreenSize(bg, rom.u16(BG_CONFIG_SCENE + 6 * bg + 4))
            lcd.setBgPosition(bg, 0, 0)
        }
    }

    /**
     * Loads the full-screen background image `Img_CommGameBgScreen` (0x280 tiles, one 32x20
     * screen when drawn with [fillIndexedMap]) into [bg]'s tile data, clears the one tile at
     * +0x5000 (that BG's tile 0x280, the blank tile used to empty a map), syncs [bg] and
     * fills palette bank 0xE with the single colour 0x08A7 (a dark brown in BGR555). What the
     * image shows: meaning unknown.
     *
     * Ported from src/opsubtitle.c: sub_80C488C
     */
    public fun loadCommonScreen(bg: Int) {
        val offset = hardware.tileDataOffset(bg)
        rom.decompressInto(COMM_GAME_BG_SCREEN, hardware.vram, offset)
        hardware.vram.fill(0, offset + 0x5000, offset + 0x5020)
        hardware.enableBgSyncByMask(1 shl bg)
        hardware.palette.fill(0x08A7.toShort(), 0xE * 16, 0xF * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Fills the first 0x280 entries of [bg]'s tilemap buffer with `0xF000 + i`: tile i in palette
     * bank 15, so the 32x20 screen shows tiles 0..0x27F in order (the image of
     * [loadCommonScreen]).
     *
     * Ported from src/opsubtitle.c: sub_80C48F0
     */
    public fun fillIndexedMap(bg: Int) {
        val map = hardware.mapBuffer(bg)
        for (i in 0 until 0x280) map[i] = (0xF000 + i).toShort()
    }

    /** Constants of the subtitle data. */
    public companion object {
        /** `SONG_PROLOGUE` (include/constants/songs.h): the monologue's music. */
        public const val SONG_PROLOGUE: Int = 3
        /** `gBgConfig_OpSubtitle`: 4 x (tile offset, map offset, screen size) u16. */
        private const val BG_CONFIG_SUBTITLE = 0x08AA213C

        /** `gUnknown_08AA21A4`: same layout, used by [setupBackgrounds]. */
        private const val BG_CONFIG_SCENE = 0x08AA21A4

        /** `Img_ChapterIntro_LensFlare`. */
        private const val LENS_FLARE_IMG = 0x08B17B64

        /** `Pal_ChapterIntro_LensFlare` (3 palettes). */
        private const val LENS_FLARE_PAL = 0x08B18ED4

        /** `Img_CommGameBgScreen`. */
        private const val COMM_GAME_BG_SCREEN = 0x08B12DB4
    }
}
