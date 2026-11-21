package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PLAY_FLAG_COMPLETE
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import gbafe.render.BgHardware

/**
 * The per-slot metadata and slot palettes of the save menu (`src/difficultymenu.c`), as far as the
 * post-chapter save menu runs them.
 *
 * It holds the three `EWRAM_OVERLAY` tables (`gPlayStChapterBits`, `gPlayStChapterMode`,
 * `gPlayStOptionBits`) and fills the slot fields of `struct SaveMenuProc` kept in [Slots].
 *
 * @param lib the save library whose SRAM the slots are read from.
 * @param chapterTitle supplies `GetChapterTitleExtra` and the title palette loader.
 * @param hardware the palette buffer (`gPaletteBuffer`) that is written.
 * @param rom the ROM the difficult-slot palette colours are read from.
 * @param chapterIndexOnWmNode `LoadSavedWMStuff(slot, &gmap)` followed by
 *   `GetChapterIndexOnWmNode(&gmap)`: the chapter index of the world map node a world map save
 *   (`save_menu_type == 4`, not complete) stands on. Not run by the capture; the default throws.
 */
public class SaveSlotData(
    private val lib: BmSaveLib,
    private val chapterTitle: ChapterTitle,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val chapterIndexOnWmNode: (Int) -> Int = {
        TODO("LoadSavedWMStuff / GetChapterIndexOnWmNode: save_menu_type 4 slot")
    },
) {
    /** The slot fields of `struct SaveMenuProc` that [saveMenuInitSaveSlotData] writes. */
    public class Slots {
        /** `chapter_idx[3]` (u8, 0x37): the title image id of each slot, 0xFF for an empty slot. */
        public val chapterIdx: IntArray = IntArray(SLOT_COUNT)

        /** `unk_3a[3]` (u8, 0x3A): bit 0 not first chapter, bit 1 flag 0x8A, bit 2 complete. */
        public val unk3a: IntArray = IntArray(SLOT_COUNT)

        /** `played_time[3]` (0x48): `time_saved` of each slot. */
        public val playedTime: IntArray = IntArray(SLOT_COUNT)

        /** `total_time` (0x54): `time_saved` of the suspend save. */
        public var totalTime: Int = 0

        /** `sus_slot_cur` (u8, 0x3F): the game slot the suspend save was made in (0xFF none). */
        public var susSlotCur: Int = 0xFF

        /** `unk_44` (u16, 0x44): 0x100, or 0xF0 once the suspend save was found invalid. */
        public var unk44: Int = 0x100
    }

    /** `gPlayStChapterBits[4]`: `chapterStateBits` of each slot's saved `PlaySt`. */
    public val playStChapterBits: IntArray = IntArray(TABLE_SIZE)

    /** `gPlayStChapterMode[4]`: `chapterModeIndex` of each slot's saved `PlaySt`. */
    public val playStChapterMode: IntArray = IntArray(TABLE_SIZE)

    /** `gPlayStOptionBits[4]`: the 8 `config` bytes (`PlaySt` 0x40..0x47) of each slot. */
    public val playStOptionBits: Array<ByteArray> = Array(TABLE_SIZE) { ByteArray(CONFIG_SIZE) }

    private val playSt = PlaySt()

    /** Stand-in for the BIOS bytes `IsGameNotFirstChapter` reads: a non-zero `chapterIndex`. */
    private val biosPlaySt = PlaySt().also { it.chapterIndex = 1 }
    private val encoded = ByteArray(PlaySt.SIZE)

    /**
     * Ported from src/difficultymenu.c: SaveMenuInitSaveSlotData
     *
     * Fills the metadata of [slot] in [slots]: for a game slot (0..2) from its saved `PlaySt`,
     * or the empty-slot values (0xFF title, no time, cleared tables) when the slot is not valid;
     * for the suspend slots (3 and 4) only while `unk44` is 0x100, taking `sus_slot_cur` and
     * `total_time` from suspend slot 3, or setting `unk44` to 0xF0 when there is no suspend save.
     *
     * The C passes the slot number itself as the `PlaySt` pointer of `IsGameNotFirstChapter`
     * (marked `BUG?`), which reads BIOS memory; the capture shows it answers true for slots 0..2
     * (including slot 0 holding chapter 0). The port passes [biosPlaySt], a stand-in `PlaySt`
     * that gives that answer, so bit 0 of `unk_3a` is always set for a valid game slot.
     *
     * Verified on three valid game slots, the suspend slots 3 and 4 and the `config` copy. Not
     * run by the capture (transcribed only): the empty-slot branch and the world map branch
     * ([chapterIndexOnWmNode]).
     */
    public fun saveMenuInitSaveSlotData(slot: Int, slots: Slots) {
        if (slot < 3) {
            if (lib.isSaveValid(slot)) {
                lib.readGameSavePlaySt(slot, playSt)
                var chIdx = playSt.chapterIndex
                val complete = playSt.chapterStateBits and PLAY_FLAG_COMPLETE != 0
                if (!complete && playSt.saveMenuType == 4) {
                    chIdx = chapterIndexOnWmNode(slot)
                }
                val saved = playSt.chapterIndex
                playSt.chapterIndex = chIdx
                slots.chapterIdx[slot] = chapterTitle.getChapterTitleExtra(playSt)
                playSt.chapterIndex = saved
                slots.playedTime[slot] = playSt.timeSaved
                slots.unk3a[slot] = 0
                if (lib.isGameNotFirstChapter(biosPlaySt)) {
                    slots.unk3a[slot] = slots.unk3a[slot] or 1
                }
                if (lib.loadSavedEid8A(slot)) slots.unk3a[slot] = slots.unk3a[slot] or 2
                if (playSt.chapterStateBits and PLAY_FLAG_COMPLETE != 0) {
                    slots.unk3a[slot] = slots.unk3a[slot] or 4
                }
                playStChapterBits[slot] = playSt.chapterStateBits
                playStChapterMode[slot] = playSt.chapterModeIndex
                playSt.encode(encoded, 0)
                encoded.copyInto(
                    playStOptionBits[slot], 0, CONFIG_OFFSET, CONFIG_OFFSET + CONFIG_SIZE,
                )
            } else {
                slots.chapterIdx[slot] = 0xFF
                slots.unk3a[slot] = 0
                slots.playedTime[slot] = 0
                playStChapterBits[slot] = 0
                playStChapterMode[slot] = 0
                playStOptionBits[slot].fill(0)
            }
        } else if (slots.unk44 == 0x100) {
            if (lib.isValidSuspendSave(3)) {
                lib.readSuspendSavePlaySt(3, playSt)
                slots.susSlotCur = playSt.gameSaveSlot
                slots.totalTime = playSt.timeSaved
            } else {
                slots.unk44 = 0xF0
            }
        }
    }

    /**
     * Ported from src/difficultymenu.c: SaveMenuInitSlotPalette
     *
     * Loads the two title palette rows of each of the three slots (banks `0x1A + 2 * i` and
     * `0x1B + 2 * i`) with [ChapterTitle.sub80895B4]: the config bits pick the hard row
     * (`PLAY_FLAG_HARD`), the mode rows (`0x10`, `0x20`, `0x40`, by the slot's chapter mode or its
     * `config.controller`) and the dimmed row (2) for every slot except [slot]; the first bank
     * also sets bit 0.
     */
    public fun saveMenuInitSlotPalette(slot: Int) {
        for (i in 0 until 3) {
            var flags = if (playStChapterBits[i] and PLAY_FLAG_HARD != 0) 4 else 0
            val controller = (u32(playStOptionBits[i]) ushr CONTROLLER_BIT) and 1
            val mode = playStChapterMode[i]
            if (controller == 0) {
                if (mode == 1) flags = flags or 0x10
                if (mode == 2) flags = (flags or 0x20) and 0xFF
                if (mode == 3) flags = flags or 0x40
            } else if (mode == 3) {
                flags = flags or 0x40
            } else {
                flags = (flags or 0x20) and 0xFF
            }
            if (i != slot) flags = (flags or 2) and 0xFF
            chapterTitle.sub80895B4(flags or 1, i * 2 + 0x1A)
            chapterTitle.sub80895B4(flags, i * 2 + 0x1B)
        }
        hardware.enablePaletteSync()
    }

    /**
     * Transcribed from src/difficultymenu.c: SaveDrawSetDifficultSlotPalette (not verified: the
     * capture has no `PLAY_FLAG_HARD` slot)
     *
     * Pulses seven colours of the hard slots' title palette: for each slot with
     * `PLAY_FLAG_HARD`, blends two ROM colour sets per channel by `(param1 >> 1) & 0x1F` (folded
     * to 0..0x10), the selected slot [param2] blending the bright pair and the others the dim one.
     */
    public fun saveDrawSetDifficultSlotPalette(param1: Int, param2: Int) {
        var t = (param1 shr 1) and 0x1F
        if (t > 0x10) t = 0x10 - (t and 0xF)
        for (slot in 0 until 3) {
            if (playStChapterBits[slot] and PLAY_FLAG_HARD == 0) continue
            var dst = slot * 0x20 + 0xA0 + 0x109
            val a = rom.pal(if (slot == param2) PAL_SELECTED_A else PAL_OTHER_A, PULSE_COLORS)
            val b = rom.pal(if (slot == param2) PAL_SELECTED_B else PAL_OTHER_B, PULSE_COLORS)
            for (k in 0 until PULSE_COLORS) {
                val x = a[k].toInt() and 0xFFFF
                val y = b[k].toInt() and 0xFFFF
                val r = (((x and 0x1F) * t + (0x10 - t) * (y and 0x1F)) shr 4) and 0x1F
                val g = (((x and 0x3E0) * t + (0x10 - t) * (y and 0x3E0)) shr 4) and 0x3E0
                val bl = (((x and 0x7C00) * t + (0x10 - t) * (y and 0x7C00)) shr 4) and 0x7C00
                hardware.palette[dst++] = (r + g + bl).toShort()
            }
        }
        hardware.enablePaletteSync()
    }

    private fun u32(b: ByteArray): Int =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

    private companion object {
        const val SLOT_COUNT = 3
        const val TABLE_SIZE = 4
        const val CONFIG_OFFSET = 0x40
        const val CONFIG_SIZE = 8
        const val CONTROLLER_BIT = 21
        const val PULSE_COLORS = 7

        /** `gUnknown_08A07AEA` / `gUnknown_08A07BEA`: the selected slot's colour pair. */
        const val PAL_SELECTED_A = 0x08A07AEA
        const val PAL_SELECTED_B = 0x08A07BEA

        /** `gUnknown_08A07B0A` / `gUnknown_08A07C0A`: the other slots' colour pair. */
        const val PAL_OTHER_A = 0x08A07B0A
        const val PAL_OTHER_B = 0x08A07C0A
    }
}
