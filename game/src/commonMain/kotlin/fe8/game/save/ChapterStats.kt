package fe8.game.save

import fe8.game.bm.PlaySt
import fe8.game.bm.StructCodec

/**
 * Per-chapter completion records (`gChapterStats`, `src/bmsave-bwl.c`, `include/bmsave.h`).
 *
 * FE8 stores up to [ARRAY_NUM] (48) packed 4-byte `struct ChapterStats` entries in EWRAM at
 * `0x0203ECF4` ([SIZE] = 192 bytes total): `chapter_index` (7 bits), `chapter_turn` (9 bits), and
 * `chapter_time` (16 bits, in units of 3 seconds = `FRAMES_PER_SECOND * 3` = 180 frames).
 *
 * @property data the 192-byte EWRAM backing array (`gChapterStats`).
 */
public class ChapterStats(
    public val data: ByteArray = ByteArray(SIZE),
) {
    /**
     * Transcribed from src/bmsave-bwl.c: GetChapterStats (not verified: only slot 0 is accessed at
     * f9443 -> f9444 in `ram_trace_prologue_end`, so a constant-0 offset or wrong stride survives)
     *
     * Returns the byte offset into [data] for chapter stats slot [index] (`&gChapterStats[index]`).
     */
    public fun getChapterStats(index: Int): Int = index * ENTRY_SIZE

    /**
     * Transcribed from src/bmsave-bwl.c: GetNextChapterStatsSlot (not verified: `gChapterStats` is
     * all zeros at `r09443` in `ram_trace_prologue_end`, so the `chapter_turn != 0` traversal loop
     * never runs)
     *
     * Returns the index of the first slot in [data] whose `chapter_turn` is 0, bounded by
     * [ARRAY_NUM] to avoid reading past [data].
     */
    public fun getNextChapterStatsSlot(): Int {
        var ret = 0
        while (ret < ARRAY_NUM && chapterTurn(ret) != 0) {
            ret++
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: RegisterChapterTimeAndTurnCount (not verified: f9443 ->
     * f9444 in `ram_trace_prologue_end` exercises slot 0 time division by 180 and turn count
     * encoding only; unexercised branches: `chapter_index != 0`, `time > 50 * 60 * 20` clamp,
     * `turn > 500` clamp, and `slot > 0`)
     *
     * Records the cleared chapter's index, clamped turn count, and clamped elapsed time (in
     * 3-second ticks since [PlaySt.timeChapterStarted] using unsigned 32-bit difference from
     * [gameClock]) into the next free slot of [data]. Guards against `slot >= ARRAY_NUM` so a full
     * table cannot overrun [data].
     */
    public fun registerChapterTimeAndTurnCount(playSt: PlaySt, gameClock: Int) {
        val slot = getNextChapterStatsSlot()
        if (slot >= ARRAY_NUM) return

        val off = getChapterStats(slot)
        var time = ((gameClock - playSt.timeChapterStarted).toUInt() / TIME_UNIT_FRAMES.toUInt())
            .toInt()
        if (time > MAX_CHAPTER_TIME) time = MAX_CHAPTER_TIME

        var turn = playSt.chapterTurnNumber
        if (turn > MAX_CHAPTER_TURN) turn = MAX_CHAPTER_TURN

        val header = StructCodec.field(playSt.chapterIndex, 0, 7) or StructCodec.field(turn, 7, 9)
        StructCodec.put16(data, off, header)
        StructCodec.put16(data, off + 2, time)
    }

    /** Returns `chapter_index` (7 bits) for slot [index]. */
    public fun chapterIndex(index: Int): Int =
        StructCodec.bits(StructCodec.u16(data, getChapterStats(index)), 0, 7)

    /** Returns `chapter_turn` (9 bits) for slot [index]. */
    public fun chapterTurn(index: Int): Int =
        StructCodec.bits(StructCodec.u16(data, getChapterStats(index)), 7, 9)

    /** Returns `chapter_time` (16 bits, in 3-second units) for slot [index]. */
    public fun chapterTime(index: Int): Int =
        StructCodec.u16(data, getChapterStats(index) + 2)

    /** Constants for `gChapterStats` sizing and caps (`include/bmsave.h`, `include/hardware.h`). */
    public companion object {
        /** Byte size of one `struct ChapterStats` entry. */
        public const val ENTRY_SIZE: Int = 4

        /** Maximum number of entries in `gChapterStats` (`WIN_ARRAY_NUM`). */
        public const val ARRAY_NUM: Int = 0x30

        /** Total byte size of `gChapterStats` (`0x30 * 4 = 192`). */
        public const val SIZE: Int = ARRAY_NUM * ENTRY_SIZE

        /** `FRAMES_PER_SECOND` (`include/hardware.h`). */
        public const val FRAMES_PER_SECOND: Int = 60

        /** Frames per chapter time unit (`FRAMES_PER_SECOND * 3` = 180). */
        public const val TIME_UNIT_FRAMES: Int = FRAMES_PER_SECOND * 3

        /** Maximum recorded chapter time in 3-second units (`50 * 60 * 20` = 60000 = 50 hours). */
        public const val MAX_CHAPTER_TIME: Int = 50 * 60 * 20

        /** Maximum recorded chapter turn count (`500`). */
        public const val MAX_CHAPTER_TURN: Int = 500
    }
}
