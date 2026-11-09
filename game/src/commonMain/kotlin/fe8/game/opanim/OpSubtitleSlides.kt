package fe8.game.opanim

import fe8.game.RomView
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * Loads the opening subtitle's ROM graphics and tilemaps into the BG buffers. [rom] must contain
 * `gOpSubtitleGfxLut` and the referenced graphics. [scratch] is reused for decompressed TSA data.
 */
public class OpSubtitleSlides(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val scratch: ByteArray = ByteArray(0x10000),
) {
    /** Fields shared by the slide loaders and subtitle transition callbacks. */
    public class State(
        /** `OpSubtitleProc.index`. */
        public var index: Int = 0,
        /** `OpSubtitleProc.timer_2c`. */
        public var transitionTimer: Int = 0,
        /** `OpSubtitleProc.timer_2a`. */
        public var holdTimer: Int = 0,
    )

    /**
     * Loads [state]'s slide into VRAM at 0x1000 and BG0 with base 0x3080, enables BG0 and its
     * sync, and clears the transition timer.
     *
     * Ported from src/opsubtitle.c: sub_80C4C60
     */
    public fun loadSingle(state: State) {
        load(state.index, 0x1000, 0x3080, false)
        hardware.lcd.dispcnt = hardware.lcd.dispcnt or 0x100
        hardware.enableBgSyncByMask(1)
        state.transitionTimer = 0
    }

    /**
     * Loads [state]'s slide at 0x1000 into BG0 and BG1 with bases 0x3080 and 0xE080; enables BG0,
     * syncs both maps, and clears the transition timer.
     *
     * Ported from src/opsubtitle.c: sub_80C4CD0
     */
    public fun loadDouble(state: State) {
        load(state.index, 0x1000, 0x3080, true)
        hardware.lcd.dispcnt = hardware.lcd.dispcnt or 0x100
        hardware.enableBgSyncByMask(3)
        state.transitionTimer = 0
    }

    /**
     * Loads slide [index] into VRAM at 0x5000 and BG0 with base 0x3280, and syncs BG0.
     *
     * Ported from src/opsubtitle.c: sub_80C4D54
     */
    public fun loadAtAlternateOffset(index: Int) {
        load(index, 0x5000, 0x3280, false)
        hardware.enableBgSyncByMask(1)
    }

    /** Decompresses the selected image and TSA and applies the TSA to the requested maps. */
    private fun load(index: Int, vramOffset: Int, tileBase: Int, includeSecondLayer: Boolean) {
        val entry = LUT + index * 12
        rom.decompressInto(rom.u32(entry), hardware.vram, vramOffset)
        rom.decompressInto(rom.u32(entry + 4), scratch, 0)
        BgTilemap.applyTsa(hardware.tilemaps[0], 0, scratch, 0, tileBase)
        if (includeSecondLayer) BgTilemap.applyTsa(hardware.tilemaps[1], 0, scratch, 0, 0xE080)
    }

    private companion object {
        const val LUT = 0x08206FE4
    }
}
