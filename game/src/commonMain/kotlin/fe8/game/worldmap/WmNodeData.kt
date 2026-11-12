package fe8.game.worldmap

import fe8.game.RomView

/**
 * Read-only access to `gWMNodeData` (ROM 0x082060B0, `struct GMapNodeData`, [ENTRY_SIZE] bytes
 * each, [NODE_COUNT] entries): the per-node table of the world map (the map locations the player
 * can stand on) for the node lookups of `src/worldmap_screen2.c`. Layout: include/worldmap.h:334.
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
     * The next destination node (signed `s8`, `unk_08[4]` "next destination id" in the decomp) of
     * node [idx]: entry 0 (Eirika) or 1 (Ephraim) of `unk_08`, or entries 2 / 3 when the node's
     * event condition flag (`unk_06`) is set. The beginning event of the world map uses it to
     * find the chapter that follows.
     *
     * @param chapterModeIndex `gPlaySt.chapterModeIndex`
     * @param checkFlag `CheckFlag`, given the node's `unk_06` event condition flag id
     */
    public fun nextLocId(idx: Int, chapterModeIndex: Int, checkFlag: (Int) -> Boolean): Int {
        val node = entry(idx)
        val flagId = rom.u16(node + FLAG)
        val base = node + NEXT + if (checkFlag(flagId)) 2 else 0
        val mode = if (chapterModeIndex == CHAPTER_MODE_EPHRAIM) 1 else 0
        return rom.u8(base + mode).toByte().toInt()
    }

    /**
     * `gWMNodeData[idx].placementFlag` (`u8`, offset 0x00): `GMAP_NODE_PLACEMENT_PROGRESSION` 0,
     * `_FRELIA` 1, `_MONSTERSPAWN` 2 or `_DUNGEON` 3.
     */
    public fun placementFlag(idx: Int): Int = rom.u8(entry(idx))

    /** `gWMNodeData[idx].encounters` (`s8`, offset 0x01; read unsigned), `GMAP_ENCOUNTERS_*`. */
    public fun encounters(idx: Int): Int = rom.u8(entry(idx) + 1)

    /** `gWMNodeData[idx].x` (`s16`, offset 0x18): the node's x on the world map. */
    public fun x(idx: Int): Int = rom.u16(entry(idx) + X).toShort().toInt()

    /** `gWMNodeData[idx].y` (`s16`, offset 0x1A): the node's y on the world map. */
    public fun y(idx: Int): Int = rom.u16(entry(idx) + Y).toShort().toInt()

    /** `gWMNodeData[idx].shipTravelFlag` (`u8`, offset 0x1E). */
    public fun shipTravelFlag(idx: Int): Int = rom.u8(entry(idx) + SHIP_TRAVEL_FLAG)

    /** ROM address of node [idx]'s `struct GMapNodeData`. */
    private fun entry(idx: Int): Int = NODE_DATA + idx * ENTRY_SIZE

    /** `gWMNodeData` layout constants. */
    public companion object {
        /** ROM address of `gWMNodeData`. */
        public const val NODE_DATA: Int = 0x082060B0

        /** `sizeof(struct GMapNodeData)`. */
        public const val ENTRY_SIZE: Int = 0x20

        /** `NODE_MAX` (include/constants/worldmap.h:36). */
        public const val NODE_COUNT: Int = 0x1D

        /** `CHAPTER_MODE_EPHRAIM` (include/types.h); any other mode counts as Eirika. */
        private const val CHAPTER_MODE_EPHRAIM = 3

        /** Offsets in `struct GMapNodeData`: `chapteridx_eirika`, `chapteridx_ephram`. */
        private const val CHAPTER_EIRIKA = 0x04
        private const val CHAPTER_EPHRAIM = 0x05

        /** `unk_06` (event condition flag), `unk_08` (next destination ids). */
        private const val FLAG = 0x06
        private const val NEXT = 0x08

        /** `x`, `y`, `shipTravelFlag`. */
        private const val X = 0x18
        private const val Y = 0x1A
        private const val SHIP_TRAVEL_FLAG = 0x1E
    }
}
