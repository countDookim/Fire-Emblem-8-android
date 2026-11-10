package fe8.game.worldmap

import fe8.game.RomView

/**
 * Read-only access to `gWMNodeData` (ROM 0x082060B0, `struct GMapNodeData`, [ENTRY_SIZE] bytes
 * each, [NODE_COUNT] entries) for the node lookups of `src/worldmap_screen2.c`.
 */
public class WmNodeData(private val rom: RomView) {

    /**
     * Ported from src/worldmap_screen2.c: WMLoc_GetChapterId
     *
     * The chapter id of node [idx]: `chapteridx_ephram` in Ephraim mode, else `chapteridx_eirika`.
     *
     * @param chapterModeIndex `gPlaySt.chapterModeIndex`
     */
    public fun chapterId(idx: Int, chapterModeIndex: Int): Int {
        val field =
            if (chapterModeIndex == CHAPTER_MODE_EPHRAIM) CHAPTER_EPHRAIM else CHAPTER_EIRIKA
        return rom.u8(entry(idx) + field)
    }

    /**
     * Ported from src/worldmap_screen2.c: WMLoc_GetNextLocId
     *
     * The next destination node (signed `s8`) of node [idx]: `unk_08[0 or 1]` by mode, shifted by
     * two when the node's event flag is set.
     *
     * @param chapterModeIndex `gPlaySt.chapterModeIndex`
     * @param checkFlag `CheckFlag`, given the node's `unk_06` flag id
     */
    public fun nextLocId(idx: Int, chapterModeIndex: Int, checkFlag: (Int) -> Boolean): Int {
        val node = entry(idx)
        val flagId = rom.u16(node + FLAG)
        val base = node + NEXT + if (checkFlag(flagId)) 2 else 0
        val mode = if (chapterModeIndex == CHAPTER_MODE_EPHRAIM) 1 else 0
        return rom.u8(base + mode).toByte().toInt()
    }

    /** `gWMNodeData[idx].placementFlag` (`u8`, offset 0x00). */
    public fun placementFlag(idx: Int): Int = rom.u8(entry(idx))

    /** `(u8)gWMNodeData[idx].encounters` (`s8`, offset 0x01). */
    public fun encounters(idx: Int): Int = rom.u8(entry(idx) + 1)

    private fun entry(idx: Int): Int = NODE_DATA + idx * ENTRY_SIZE

    /** `gWMNodeData` layout constants. */
    public companion object {
        /** ROM address of `gWMNodeData`. */
        public const val NODE_DATA: Int = 0x082060B0

        /** `sizeof(struct GMapNodeData)`. */
        public const val ENTRY_SIZE: Int = 0x20

        /** `NODE_MAX`. */
        public const val NODE_COUNT: Int = 0x1D

        private const val CHAPTER_MODE_EPHRAIM = 3
        private const val CHAPTER_EIRIKA = 0x04
        private const val CHAPTER_EPHRAIM = 0x05
        private const val FLAG = 0x06
        private const val NEXT = 0x08
    }
}
