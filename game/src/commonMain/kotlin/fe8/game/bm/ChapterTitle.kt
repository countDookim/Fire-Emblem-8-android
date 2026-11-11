package fe8.game.bm

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * The chapter title card graphics of `src/chapter_title.c`, as far as the chapter intro uses them
 * (`ChapterIntro_DrawChapterTitle`): the title palette, the title image and its tilemap rows.
 * Only the functions the `ram_trace_newgame_intro` window runs are here.
 *
 * @param getPlayChapterId `GetPlayChapterId(chapterIndex)` (worldmap_path.c): the world map node of
 *   a chapter, or -1. Stand-in supplied by the caller until M6-I.
 * @param getNextUnclearedNode `GetNextUnclearedNode(&gGMData)` (worldmap_path.c).
 */
public class ChapterTitle(
    private val rom: RomView,
    private val hardware: BgHardware,
    private val chapterData: ChapterData,
    private val getPlayChapterId: (Int) -> Int,
    private val getNextUnclearedNode: () -> Int,
) {
    /** `gChapterTitleFxSt.chr_str`: first tile of the title image, set by [putChapterTitleGfx]. */
    public var chrStr: Int = 0
        private set

    /**
     * Ported from src/chapter_title.c: sub_80895B4
     *
     * Loads the 16-colour title palette selected by [config] (bit 0: palette set; bits 0x80, 8,
     * 0x10, 0x20, 0x40, 4: which 16-colour row of it; bit 2: one row on) into palette bank [palId]
     * and marks the palette for sync.
     *
     * The capture only runs `config = 8` (the row at +0xA0 halfwords of the second palette set);
     * the other branches are transcribed (not verified: not run by `ram_trace_newgame_intro`).
     */
    public fun sub80895B4(config: Int, palId: Int) {
        var pal = if (config and 1 != 0) PAL_A else PAL_B
        if (config and 0x80 == 0) {
            if (config and 8 != 0) {
                pal += 0xA0 * 2
            } else if (config and 0x10 == 0) {
                if (config and 0x20 != 0) pal += 0x20 * 2
                if (config and 0x40 != 0) pal += 0x40 * 2
                if (config and 4 != 0) pal += 0x40 * 2
            }
        }
        if (config and 2 != 0) pal += 0x10 * 2
        rom.pal(pal, 16).copyInto(hardware.palette, palId * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Ported from src/chapter_title.c: PutChapterTitleGfx
     *
     * Decompresses the title image of [titleId] to tile [chr] of BG VRAM; an id above 0x108 (as
     * unsigned) falls back to 0x54 (not verified: not run).
     */
    public fun putChapterTitleGfx(chr: Int, titleId: Int) {
        var id = titleId
        if (id.toUInt() > 0x108u) id = 0x54
        chrStr = chr and 0x3FF
        val image = rom.u32(CHAP_TITLE_DATA + id * CHAP_TITLE_SIZE)
        rom.decompressInto(image, hardware.vram, chr * 0x20)
    }

    /** Ported from src/chapter_title.c: _PutChapterTitleGfx */
    public fun putChapterTitleGfxWrapper(chr: Int, titleId: Int) {
        putChapterTitleGfx(chr, titleId)
    }

    /**
     * Ported from src/chapter_title.c: sub_80896FC
     *
     * Writes 0x40 consecutive tile references starting at [chrStr] with palette [pal] into [tm] at
     * [offset]. The third parameter of the original is unused.
     */
    public fun sub80896FC(tm: ShortArray, offset: Int, pal: Int) {
        var tile = chrStr + (pal shl 12)
        for (i in 0 until 0x40) tm[offset + i] = tile++.toShort()
    }

    /**
     * Ported from src/chapter_title.c: GetChapterTitleWM
     *
     * The title image id for the chapter in [playSt]: the world map monster node id (0x46 + slot)
     * when the postgame flag is set or the chapter's node is not the next uncleared one and is a
     * monster spawn node, else the chapter's `chapTitleId`.
     *
     * The monster-spawn branch is transcribed (not verified: not run by the capture).
     */
    public fun getChapterTitleWM(playSt: PlaySt): Int {
        val node = getPlayChapterId(playSt.chapterIndex)
        if (playSt.chapterStateBits and PLAY_FLAG_POSTGAME != 0 || getNextUnclearedNode() != node) {
            val count = rom.u8(MONSTER_SPAWNS_SIZE)
            for (i in 0 until count) {
                if (node == rom.u8(MONSTER_SPAWN_LOCATIONS + i)) return 0x46 + i
            }
        }
        return chapterData.chapTitleId(playSt.chapterIndex)
    }

    private companion object {
        const val PLAY_FLAG_POSTGAME = 1 shl 2
        const val PAL_A = 0x08A07AD8
        const val PAL_B = 0x08A07C58
        const val CHAP_TITLE_DATA = 0x08A01208
        const val CHAP_TITLE_SIZE = 12
        const val MONSTER_SPAWN_LOCATIONS = 0x08206948
        const val MONSTER_SPAWNS_SIZE = 0x08206951
    }
}
