package fe8.game.worldmap

import fe8.game.RomView

/**
 * Read-only access to `gWMPathData` (ROM 0x08206674, `struct GMapPathData`, [ENTRY_SIZE] bytes
 * each): the two end nodes of every world map path.
 */
public class WmPathData(private val rom: RomView) {
    /** `gWMPathData[path].node[end]` (`s8`, offset 4 + [end]). */
    public fun node(path: Int, end: Int): Int =
        rom.u8(PATH_DATA + path * ENTRY_SIZE + NODE + end).toByte().toInt()

    /** `gWMPathData` layout constants. */
    public companion object {
        /** ROM address of `gWMPathData`. */
        public const val PATH_DATA: Int = 0x08206674

        /** `sizeof(struct GMapPathData)`. */
        public const val ENTRY_SIZE: Int = 0xC

        private const val NODE = 4
    }
}

/**
 * The `struct GMapNodeLink[0x1d]` table `gUnknown_0201AFF0`, kept as its raw bytes: per node one
 * `s8 numConnections` followed by `s8 connections[7]` ([ENTRY_SIZE] bytes).
 *
 * @param bytes backing storage, [SIZE] bytes
 */
public class GmNodeLinks(public val bytes: ByteArray = ByteArray(SIZE)) {
    /** `table[node].numConnections` (`s8`). */
    public fun numConnections(node: Int): Int = bytes[node * ENTRY_SIZE].toInt()

    /** `table[node].connections[index]` (`s8`). */
    public fun connection(node: Int, index: Int): Int = bytes[node * ENTRY_SIZE + 1 + index].toInt()

    /** Table layout constants. */
    public companion object {
        /** `sizeof(struct GMapNodeLink)`. */
        public const val ENTRY_SIZE: Int = 8

        /** `NODE_MAX`. */
        public const val NODE_COUNT: Int = 0x1D

        /** Bytes the original clears: `sizeof(struct GMapNodeLink) * 0x1d`. */
        public const val SIZE: Int = ENTRY_SIZE * NODE_COUNT
    }
}

/** The pure `gGMData` node and path helpers of `src/worldmap_path.c`. */
public class WorldMapPath(private val gmData: GmData, private val pathData: WmPathData) {
    /**
     * Transcribed from src/worldmap_path.c: ResetGmStoryNode (not verified: no reference run yet).
     *
     * Clears `GM_NODE_STATE_CLEARED` on all [GmNodeLinks.NODE_COUNT] nodes.
     */
    public fun resetGmStoryNode() {
        for (i in 0 until GmNodeLinks.NODE_COUNT) {
            gmData.setNodeState(i, gmData.nodeState(i) and CLEARED.inv())
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: GetNextUnclearedNode (not verified: no reference run
     * yet).
     *
     * The first node that has both `GM_NODE_STATE_VALID` and `GM_NODE_STATE_CLEARED` set (as the
     * original is written, despite its name), or -1.
     */
    public fun getNextUnclearedNode(): Int {
        for (i in 0 until GmNodeLinks.NODE_COUNT) {
            val state = gmData.nodeState(i)
            if (state and VALID == 0) continue
            if (state and CLEARED == 0) continue
            return i
        }
        return -1
    }

    /**
     * Ported from src/worldmap_path.c: RefreshGmNodeLinksExt
     *
     * Rebuilds [links] from `gGMData.openPaths`: each open path adds its two end nodes to each
     * other's connection list. Like the original, a write for node 0 is repeated into the slot
     * after the incremented count, and slot writes address the flat table bytes (a full list
     * spills into the next entry); writes past the table are dropped.
     */
    public fun refreshGmNodeLinksExt(links: GmNodeLinks) {
        val table = links.bytes
        table.fill(0, 0, GmNodeLinks.SIZE)
        for (i in 0 until gmData.openPathsLength) {
            val pathId = gmData.openPath(i)
            for (j in 0 until 2) {
                val node = pathData.node(pathId, j)
                val entry = node * GmNodeLinks.ENTRY_SIZE
                val other = pathData.node(pathId, 1 - j).toByte()
                putSlot(table, entry, other)
                table[entry]++
                if (node == 0) putSlot(table, entry, other)
            }
        }
    }

    /**
     * Ported from src/worldmap_path.c: RefreshGmNodeLinks
     *
     * [refreshGmNodeLinksExt] on the table [links] standing for `gUnknown_0201AFF0`.
     */
    public fun refreshGmNodeLinks(links: GmNodeLinks) {
        refreshGmNodeLinksExt(links)
    }

    /** `GMapNodeLinkNextConnSlot(entry) = value`: byte `1 + numConnections` of the entry. */
    private fun putSlot(table: ByteArray, entry: Int, value: Byte) {
        val at = entry + 1 + table[entry]
        if (at in table.indices) table[at] = value
    }

    private companion object {
        const val VALID = 1
        const val CLEARED = 2
    }
}
