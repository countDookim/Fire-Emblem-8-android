/**
 * World map save serialization (`WriteWorldMapStuff` and its helpers) of `src/bmsave-gmap.c`.
 *
 * Encodes `gGMData` ([GmData]) into the 0x24-byte `struct GMapSaveInfo` layout (`nodes`, `paths`,
 * `units`, cursor coordinates, state bits, skirmishes, and `skirmishState`) and writes it to SRAM.
 */

package fe8.game.save

import fe8.game.bm.StructCodec
import fe8.game.worldmap.GmData
import gba.core.sram.CartSram
import gba.core.sram.writeAndVerifySramFast

/**
 * World map save writer of `src/bmsave-gmap.c`.
 *
 * Owns a reusable 0x24-byte `struct GMapSaveInfo` scratch buffer so [writeWorldMapStuff] allocates
 * nothing per call.
 */
public class BmSaveGmap {
    /** Buffer for the `struct GMapSaveInfo info` local of `WriteWorldMapStuff`. */
    private val infoBuf = ByteArray(Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)

    /**
     * Ported from src/bmsave-gmap.c: WriteWorldMapNodes
     *
     * Packs the `GM_NODE_STATE_VALID` (bit 0) and `GM_NODE_STATE_CLEARED` (bit 1) flags of the
     * 0x1D node slots in [gmData] (`nodes[0..0x1B]` and `unk_a0`) into 8 bytes (58 bits, two
     * bits per node, LSB first) at [nodeFlags] + [destOffset], after zeroing those 8 bytes.
     */
    public fun writeWorldMapNodes(
        gmData: GmData,
        nodeFlags: ByteArray,
        destOffset: Int = 0,
    ) {
        for (i in 0 until NODES_BYTES) {
            nodeFlags[destOffset + i] = 0
        }
        var count = 0
        for (i in 0 until GmData.NODE_SAVE_COUNT) {
            val state = gmData.nodeState(i)
            for (j in 0 until 2) {
                val bitSet = when (j) {
                    0 -> (state and 1) != 0
                    else -> (state and 2) != 0
                }
                if (bitSet) {
                    val idx = count ushr 3
                    val bit = count and 7
                    nodeFlags[destOffset + idx] =
                        (nodeFlags[destOffset + idx].toInt() or (1 shl bit)).toByte()
                }
                count++
            }
        }
    }

    /**
     * Ported from src/bmsave-gmap.c: WriteWorldMapPaths
     *
     * Zeroes the 4 bytes at [pathFlags] + [destOffset], then sets bit `path & 7` of byte
     * `path / 8` for each of the `openPathsLength` open paths in [gmData]. Coverage limit: in the
     * chapter 2 / chapter 6 reference saves all open paths are in `0..5`, so bytes `1..3`
     * (`path >= 8`) are zero in those captures.
     */
    public fun writeWorldMapPaths(
        gmData: GmData,
        pathFlags: ByteArray,
        destOffset: Int = 0,
    ) {
        for (i in 0 until PATHS_BYTES) {
            pathFlags[destOffset + i] = 0
        }
        for (i in 0 until gmData.openPathsLength) {
            val path = gmData.openPath(i)
            val idx = path / 8
            val bit = path and 7
            pathFlags[destOffset + idx] =
                (pathFlags[destOffset + idx].toInt() or (1 shl bit)).toByte()
        }
    }

    /**
     * Ported from src/bmsave-gmap.c: WriteWorldMapUnits
     *
     * Packs the 7 world map unit slots of [gmData] into 7 little-endian `u16` records (14 bytes)
     * at [dest] + [destOffset]: bit 0 is `state & 1`, bits 1..6 are `location`, bit 7 is
     * `(state >> 1) & 1`, and bits 8..15 are `id`. Coverage limit: `state & 1` is zero in every
     * reference capture (`prologue.sav`, chapter 2, chapter 6).
     */
    public fun writeWorldMapUnits(
        gmData: GmData,
        dest: ByteArray,
        destOffset: Int = 0,
    ) {
        for (i in 0 until GmData.UNIT_SAVE_COUNT) {
            val state = gmData.unitState(i)
            val location = gmData.unitLocation(i)
            val id = gmData.unitId(i)
            val packed = (state and 1) or
                ((location and 0x3F) shl 1) or
                (((state ushr 1) and 1) shl 7) or
                ((id and 0xFF) shl 8)
            StructCodec.put16(dest, destOffset + i * 2, packed)
        }
    }

    /**
     * Transcribed from src/bmsave-gmap.c: WriteWorldMapSkirmishes (not verified: `gGMData.unk_c9`
     * is all zeros in every reference capture, which proves the 3-byte target write when seeded
     * with sentinel, not non-zero skirmish data)
     *
     * Copies the 3 active world map skirmish bytes (`unk_c9[0..2]`) from [gmData] to
     * [skirmishFlags] + [destOffset].
     */
    public fun writeWorldMapSkirmishes(
        gmData: GmData,
        skirmishFlags: ByteArray,
        destOffset: Int = 0,
    ) {
        for (i in 0 until GmData.SKIRMISH_COUNT) {
            skirmishFlags[destOffset + i] = gmData.skirmish(i).toByte()
        }
    }

    /**
     * Ported from src/bmsave-gmap.c: WriteWorldMapStuff
     *
     * Packs [gmData] into the 0x24-byte `struct GMapSaveInfo` (`nodes`, `paths`, `units`,
     * `skirmishes`, `skirmishState`, `xCursor`, `yCursor`, and the `unk_1c_*` bits) and writes it
     * to [sram] at [destOffset] with verification (`WriteAndVerifySramFast`). Unlike the C
     * stack-allocated `struct GMapSaveInfo info`, [infoBuf] starts zeroed, so the unwritten top 4
     * bits of byte 0x1C and trailing padding bytes 0x21..0x23 are 0 rather than stack garbage.
     * Coverage limit: `unk_c9` (`skirmishes`), `unk_cc` (`skirmishState`), and `monster_merged`
     * (`unk_1c_1`) are zero in all reference captures.
     */
    public fun writeWorldMapStuff(
        sram: CartSram,
        destOffset: Int,
        gmData: GmData,
    ) {
        infoBuf.fill(0)
        writeWorldMapNodes(gmData, infoBuf, INFO_NODES)
        writeWorldMapPaths(gmData, infoBuf, INFO_PATHS)
        writeWorldMapUnits(gmData, infoBuf, INFO_UNITS)
        writeWorldMapSkirmishes(gmData, infoBuf, INFO_SKIRMISHES)
        infoBuf[INFO_SKIRMISH_STATE] = gmData.skirmishState.toByte()
        infoBuf[INFO_X_CURSOR] = gmData.xCamera.toByte()
        infoBuf[INFO_Y_CURSOR] = gmData.yCamera.toByte()
        val monsterMerged = if (gmData.monsterMerged()) 1 else 0
        val state2 = if (gmData.state2) 1 else 0
        val state45 = gmData.state45 and 3
        infoBuf[INFO_BITS_1C] = (monsterMerged or (state2 shl 1) or (state45 shl 2)).toByte()
        writeAndVerifySramFast(infoBuf, 0, sram, destOffset, Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)
    }

    /** Field offsets and sub-array sizes inside `struct GMapSaveInfo` (0x24 bytes). */
    public companion object {
        /** Byte size of `GMapSaveInfo.nodes` (`u8 nodes[8]`). */
        public const val NODES_BYTES: Int = 8

        /** Byte size of `GMapSaveInfo.paths` (`u8 paths[4]`). */
        public const val PATHS_BYTES: Int = 4

        /** Byte size of `GMapSaveInfo.units` (`u16 units[7]`). */
        public const val UNITS_BYTES: Int = GmData.UNIT_SAVE_COUNT * 2

        /** Offset of `nodes[8]` in `struct GMapSaveInfo`. */
        public const val INFO_NODES: Int = 0x00

        /** Offset of `paths[4]` in `struct GMapSaveInfo`. */
        public const val INFO_PATHS: Int = 0x08

        /** Offset of `units[7]` in `struct GMapSaveInfo`. */
        public const val INFO_UNITS: Int = 0x0C

        /** Offset of `xCursor` (`u8`) in `struct GMapSaveInfo`. */
        public const val INFO_X_CURSOR: Int = 0x1A

        /** Offset of `yCursor` (`u8`) in `struct GMapSaveInfo`. */
        public const val INFO_Y_CURSOR: Int = 0x1B

        /** Offset of the `unk_1c_1` / `unk_1c_2` / `unk_1c_3` bit-field byte. */
        public const val INFO_BITS_1C: Int = 0x1C

        /** Offset of `skirmishes[3]` in `struct GMapSaveInfo`. */
        public const val INFO_SKIRMISHES: Int = 0x1D

        /** Offset of `skirmishState` (`u8`) in `struct GMapSaveInfo`. */
        public const val INFO_SKIRMISH_STATE: Int = 0x20
    }
}
