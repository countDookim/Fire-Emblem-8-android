package fe8.game.bm

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * The map tile animations of the `sProc_BMVSync` proc (src/bmio.c): the animated tile graphics
 * copied to BG VRAM and the animated palette colours copied to palette bank 6 onwards, both
 * cycling through a ROM table entry by entry.
 *
 * The state is the proc's own fields (`BMVSyncProc` 0x2C..0x3F); there is one such proc.
 * Nothing is allocated per frame.
 */
public class MapAnimations(
    /** ROM the animation tables are read from. */
    private val rom: RomView,
    /** Chapter table access (`map.objAnimId`, `map.paletteAnimId`). */
    private val chapterData: ChapterData,
    /** BG VRAM and palette buffer. */
    private val hardware: BgHardware,
    /** `gPlaySt` (`chapterIndex`). */
    private val playSt: PlaySt,
) {
    private var tileGfxAnimStart = 0
    private var tileGfxAnimCurrent = 0
    private var tileGfxAnimClock = 0
    private var tilePalAnimStart = 0
    private var tilePalAnimCurrent = 0
    private var tilePalAnimClock = 0

    /**
     * Transcribed from src/bmio.c: BMapVSync_InitMapAnimations (not verified:
     *   the test seeds the animation state, so the init is never run against a trace)
     *
     * Clears both clocks and points both animations at the first entry of the chapter's tables.
     */
    public fun initMapAnimations() {
        tileGfxAnimClock = 0
        tilePalAnimClock = 0
        val chapter = playSt.chapterIndex
        tileGfxAnimStart = asset(chapterData.objAnimId(chapter))
        tileGfxAnimCurrent = tileGfxAnimStart
        tilePalAnimStart = asset(chapterData.paletteAnimId(chapter))
        tilePalAnimCurrent = tilePalAnimStart
    }

    /**
     * Sets the tile animation state (`tileGfxAnimStart`, `tileGfxAnimCurrent`, `tileGfxAnimClock`)
     * directly, as read from a proc dump; for resuming and tests.
     */
    public fun seedTileAnimation(start: Int, current: Int, clock: Int) {
        tileGfxAnimStart = start
        tileGfxAnimCurrent = current
        tileGfxAnimClock = clock
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_UpdateMapImgAnimations (not verified: the reference
     * VRAM is compared only every 10th frame, so an off-by-one entry timer survives)
     *
     * Every `time` frames copies the current `TileGfxAnim` entry (u16 time, u16 size, data
     * pointer) to BG tile 0x140 (VRAM 0xA000) and advances, wrapping at a zero `time`.
     */
    public fun updateMapImgAnimations() {
        if (tileGfxAnimStart == 0) return
        if (tileGfxAnimClock != 0) {
            tileGfxAnimClock--
            return
        }
        val cur = tileGfxAnimCurrent
        tileGfxAnimClock = rom.u16(cur)
        val size = rom.u16(cur + 2)
        val data = rom.u32(cur + 4)
        val vram = hardware.vram
        for (i in 0 until size) vram[TILE_ANIM_VRAM + i] = rom.u8(data + i).toByte()
        tileGfxAnimCurrent += GFX_ENTRY_SIZE
        if (rom.u16(tileGfxAnimCurrent) == 0) tileGfxAnimCurrent = tileGfxAnimStart
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_UpdateMapPalAnimations (not verified:
     *   only the tile animation is compared against a trace)
     *
     * Every `time` frames copies the current `TilePalAnim` entry (u32 data pointer, u8 time, u8
     * colorCount, u8 colorStart) into `gPaletteBuffer` at colour `colorStart + 0x10 * 6`, enables
     * the palette sync and advances, wrapping at a zero `time`.
     */
    public fun updateMapPalAnimations() {
        if (tilePalAnimStart == 0) return
        if (tilePalAnimClock != 0) {
            tilePalAnimClock--
            return
        }
        val cur = tilePalAnimCurrent
        tilePalAnimClock = rom.u8(cur + 4)
        val count = rom.u8(cur + 5)
        val dst = rom.u8(cur + 6) + 0x10 * BM_BGPAL_6
        val data = rom.u32(cur)
        for (i in 0 until count) hardware.palette[dst + i] = rom.u16(data + 2 * i).toShort()
        hardware.enablePaletteSync()
        tilePalAnimCurrent += PAL_ENTRY_SIZE
        if (rom.u8(tilePalAnimCurrent + 4) == 0) tilePalAnimCurrent = tilePalAnimStart
    }

    private fun asset(id: Int): Int = rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * id)

    private companion object {
        /** `BG_TILE_ADDR(0x140)` as a VRAM offset. */
        const val TILE_ANIM_VRAM = 0x80 * 0x140

        /** `sizeof(struct TileGfxAnim)`. */
        const val GFX_ENTRY_SIZE = 8

        /** `sizeof(struct TilePalAnim)` (data pointer plus three bytes, padded). */
        const val PAL_ENTRY_SIZE = 8

        /** `BM_BGPAL_6`. */
        const val BM_BGPAL_6 = 6
    }
}
