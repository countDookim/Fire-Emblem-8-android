package fe8.game.opanim

import fe8.game.RomView
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * Loads the opening monologue's story slides into VRAM and the BG map buffers. Each slide is one
 * 12-byte entry of `gOpSubtitleGfxLut` (compressed graphics, compressed map, hold time in
 * frames); [rom] must contain it and the referenced data. [scratch] stands in for
 * `gGenericBuffer`, which holds the decompressed map.
 */
public class OpSubtitleSlides(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val scratch: ByteArray = ByteArray(0x10000),
) {
    /** The `struct OpSubtitleProc` fields shared by the slide loaders and the transitions. */
    public class State(
        /** `index` (s8): the current slide, an index into `gOpSubtitleGfxLut` (0..6). */
        public var index: Int = 0,
        /** `timer_2c` (s16): frame counter of the running fade or blend (80 frames for most). */
        public var transitionTimer: Int = 0,
        /** `timer_2a` (s16): frames left in the current hold (`OpSubtitle_AwaitTimer2a`). */
        public var holdTimer: Int = 0,
    )

    /**
     * Loads [state]'s slide: graphics into VRAM at 0x1000 (tile 0x80) and its map into BG0 with
     * base 0x3080 (tile 0x80, palette 3, which holds the slide palette), shows BG0, syncs it and
     * clears the transition timer.
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
     * Like [loadSingle], but also draws the same map into BG1 with base 0xE080 (palette 14,
     * the single dark colour of `loadCommonScreen`; that and the (-3, -3) offset set on BG1 in the
     * final scene suggest a drop shadow, inferred and not stated in the decomp). Syncs both
     * maps and clears the transition timer.
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
     * Loads slide [index] with its graphics at VRAM 0x5000 (tile 0x280) and its map into BG0 with
     * base 0x3280 (tile 0x280, palette 3), and syncs BG0. Used for the title card, which is
     * shown while the background image owns the tiles from VRAM 0.
     *
     * Ported from src/opsubtitle.c: sub_80C4D54
     */
    public fun loadAtAlternateOffset(index: Int) {
        load(index, 0x5000, 0x3280, false)
        hardware.enableBgSyncByMask(1)
    }

    /**
     * Decompresses slide [index]'s graphics to [vramOffset] and its map to [scratch], then fills
     * BG0's map (and BG1's if [includeSecondLayer]) from it with tile base [tileBase]
     * (`CallARM_FillTileRect`).
     */
    private fun load(index: Int, vramOffset: Int, tileBase: Int, includeSecondLayer: Boolean) {
        val entry = LUT + index * 12
        rom.decompressInto(rom.u32(entry), hardware.vram, vramOffset)
        rom.decompressInto(rom.u32(entry + 4), scratch, 0)
        BgTilemap.applyTsa(hardware.tilemaps[0], 0, scratch, 0, tileBase)
        if (includeSecondLayer) BgTilemap.applyTsa(hardware.tilemaps[1], 0, scratch, 0, 0xE080)
    }

    private companion object {
        /** `gOpSubtitleGfxLut`. */
        const val LUT = 0x08206FE4
    }
}
