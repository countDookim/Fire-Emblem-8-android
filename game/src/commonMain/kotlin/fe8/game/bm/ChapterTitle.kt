package fe8.game.bm

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * The chapter title card graphics of `src/chapter_title.c`, as far as the chapter intro uses them
 * (`ChapterIntro_DrawChapterTitle`): the title palette, the title image (a 4bpp graphic per
 * `chap_title_data` entry, decompressed into BG VRAM) and the tilemap row of its tiles. Only the
 * functions the `ram_trace_newgame_intro` window runs are here (not `GetChapterTitleExtra`,
 * `PutChapterTitleBG` or the other tilemap helpers).
 *
 * @param getPlayChapterId `GetPlayChapterId(chapterIndex)` (worldmap_path.c): the world map node of
 *   a chapter, or -1. Stand-in supplied by the caller until M6-I (the rest of the world map).
 * @param getNextUnclearedNode `GetNextUnclearedNode(&gGMData)` (worldmap_path.c): the world map
 *   node the story reaches next.
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
     * Loads one 16-colour row of the title palettes into palette bank [palId] and marks the
     * palette for sync. [config] picks the palette set (`gPal_08A07AD8` when bit 0 is set, else
     * `gPal_08A07C58`) and the row, as a halfword offset into it: unless bit 0x80 is set, bit 8
     * selects +0xA0, otherwise unless bit 0x10 is set bit 0x20 adds +0x20 and bit 0x40 and bit 4
     * add +0x40 each; then bit 2 adds one more row (+0x10).
     *
     * The capture only runs `config = 8` (the +0xA0 row of `gPal_08A07C58`); the other branches
     * are transcribed (not verified: not run by `ram_trace_newgame_intro`).
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
     * Decompresses the title image of [titleId] (the first pointer of its 12-byte
     * `chap_title_data` entry) to tile [chr] of BG VRAM and remembers [chr] (10 bits) as
     * [chrStr]. An id above 0x108 (as unsigned) falls back to 0x54, the entry
     * `GetChapterTitleExtra` returns for no data (not verified: not run).
     */
    public fun putChapterTitleGfx(chr: Int, titleId: Int) {
        var id = titleId
        if (id.toUInt() > 0x108u) id = 0x54
        chrStr = chr and 0x3FF
        val image = rom.u32(CHAP_TITLE_DATA + id * CHAP_TITLE_SIZE)
        rom.decompressInto(image, hardware.vram, chr * 0x20)
    }

    /**
     * Ported from src/chapter_title.c: _PutChapterTitleGfx
     *
     * A plain wrapper of [putChapterTitleGfx], the one `ChapterIntro_DrawChapterTitle` calls.
     */
    public fun putChapterTitleGfxWrapper(chr: Int, titleId: Int) {
        putChapterTitleGfx(chr, titleId)
    }

    /**
     * Ported from src/chapter_title.c: sub_80896FC
     *
     * Writes the 0x40 consecutive tile references of the title image, starting at tile [chrStr]
     * with BG palette [pal], into the tilemap [tm] from entry [offset] on (one run of 0x40
     * entries, wrapping at 32 per row). The third parameter of the original is unused.
     */
    public fun sub80896FC(tm: ShortArray, offset: Int, pal: Int) {
        var tile = chrStr + (pal shl 12)
        for (i in 0 until 0x40) tm[offset + i] = tile++.toShort()
    }

    /**
     * Ported from src/chapter_title.c: GetChapterTitleWM
     *
     * The title image id for the chapter in [playSt]: 0x46 plus the slot in
     * `gWMMonsterSpawnLocations` when the chapter's world map node (`GetPlayChapterId`) is one of
     * the monster spawn nodes and either `PLAY_FLAG_POSTGAME` is set or the node is not the next
     * uncleared one; else the chapter's `chapTitleId`. The C also returns 0x54 for a null
     * `PlaySt`; the port takes a non-null one.
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
        /** `PLAY_FLAG_POSTGAME` (include/types.h). */
        const val PLAY_FLAG_POSTGAME = 1 shl 2

        /** `gPal_08A07AD8`. */
        const val PAL_A = 0x08A07AD8

        /** `gPal_08A07C58`. */
        const val PAL_B = 0x08A07C58

        /** `chap_title_data`: `ChapTitle` entries of three pointers (`save` and two unused). */
        const val CHAP_TITLE_DATA = 0x08A01208

        /** `sizeof(ChapTitle)`. */
        const val CHAP_TITLE_SIZE = 12

        /** `gWMMonsterSpawnLocations`: world map node ids, one byte per monster spawn. */
        const val MONSTER_SPAWN_LOCATIONS = 0x08206948

        /** `gWMMonsterSpawnsSize` (a byte holding the length of the table above). */
        const val MONSTER_SPAWNS_SIZE = 0x08206951
    }
}
