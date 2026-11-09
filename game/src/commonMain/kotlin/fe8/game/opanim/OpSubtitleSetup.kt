package fe8.game.opanim

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * Opening subtitle initialization and background setup. [rom] must contain the subtitle's BG
 * configuration tables and graphics; [startBgm] stands in for `StartBgm(song, player)`.
 */
public class OpSubtitleSetup(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val startBgm: (song: Int, player: Int) -> Unit = { _, _ -> },
) {
    /**
     * Sets up the subtitle backgrounds (mode 1; BG2 holds the lens-flare graphics), blending,
     * palette banks 0..2 and the prologue BGM, then starts the first hold at 60 frames. The sound
     * request is only forwarded to [startBgm]; its audio is not verified.
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
        // screenSize = 1, areaOverflowMode = 0
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
     * Resets the four BG controls and applies the second subtitle BG table (tile base, map base,
     * screen size, zero scroll).
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
     * Decompresses the common game background screen into [bg]'s tile data, clears 0x20 bytes
     * at +0x5000, syncs [bg] and fills palette bank 0xE with 0x08A7.
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
     * Fills the first 0x280 entries of [bg]'s tilemap buffer with `0xF000 + i`.
     *
     * Ported from src/opsubtitle.c: sub_80C48F0
     */
    public fun fillIndexedMap(bg: Int) {
        val map = hardware.mapBuffer(bg)
        for (i in 0 until 0x280) map[i] = (0xF000 + i).toShort()
    }

    /** Constants of the subtitle data. */
    public companion object {
        /** `SONG_PROLOGUE`. */
        public const val SONG_PROLOGUE: Int = 3
        private const val BG_CONFIG_SUBTITLE = 0x08AA213C
        private const val BG_CONFIG_SCENE = 0x08AA21A4
        private const val LENS_FLARE_IMG = 0x08B17B64
        private const val LENS_FLARE_PAL = 0x08B18ED4
        private const val COMM_GAME_BG_SCREEN = 0x08B12DB4
    }
}
