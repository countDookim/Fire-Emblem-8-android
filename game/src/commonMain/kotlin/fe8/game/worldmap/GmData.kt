package fe8.game.worldmap

import fe8.game.bm.StructCodec
import gbafe.core.rng.RngService

/**
 * The world map state block `gGMData` (`struct GMapData`, include/worldmap.h:~470) in emulated
 * IWRAM: the camera, the party leader and other map units' slots (`units`), which nodes are
 * valid or cleared (`nodes`), the open paths between nodes and the current node. It is part of
 * the save, so the byte layout is kept exactly.
 *
 * @param iwram the 32 KiB IWRAM image (address 0x03000000 is index 0)
 * @param rng the generator whose primary state `StoreRNState` copies into `unk_ce`
 */
public class GmData(private val iwram: ByteArray, private val rng: RngService) {
    /**
     * Ported from src/worldmap_path.c: GmDataInit
     *
     * Resets the world map state for a new game: zeroes `gGMData` (0xD4 bytes), marks all open
     * paths unused (`GmPathsInit`: 0xFF entries, length 0), clears the unit slots' ids and bit 1 of
     * their state, places unit 0 (id 1, location 0), marks node 0 cleared, sets `state_2` and
     * `state_4_5` (the meaning of those bits is not documented in the decomp) and copies the
     * RN state into `unk_ce` (`StoreRNState`).
     */
    public fun init() {
        iwram.fill(0, BASE, BASE + CLEAR_SIZE)
        // GmPathsInit (src/worldmap_path.c:1382)
        iwram.fill(0xFF.toByte(), BASE + OPEN_PATHS, BASE + OPEN_PATHS + OPEN_PATHS_SIZE)
        StructCodec.put8(iwram, BASE + OPEN_PATHS_LENGTH, 0)
        for (i in 0 until UNIT_COUNT) {
            val unit = BASE + UNITS + i * UNIT_SIZE
            StructCodec.put16(iwram, unit + 2, 0)
            iwram[unit] = (iwram[unit].toInt() and 2.inv()).toByte()
        }
        val unit0 = BASE + UNITS
        StructCodec.put16(iwram, unit0 + 2, 1)
        iwram[unit0] = (iwram[unit0].toInt() and 2.inv()).toByte()
        iwram[unit0 + 1] = 0
        iwram[BASE + NODES] = (iwram[BASE + NODES].toInt() or 2).toByte()
        // state_2 = 1 (bit 2), state_4_5 = 3 (bits 4-5)
        var state = iwram[BASE].toInt() and 0xFF
        state = (state and 0x04.inv()) or 0x04
        state = (state and 0x30.inv()) or 0x30
        iwram[BASE] = state.toByte()
        iwram[BASE + UNK_CD] = 0
        val words = IntArray(3)
        rng.primary.store(words)
        for (i in 0 until 3) StructCodec.put16(iwram, BASE + UNK_CE + i * 2, words[i])
    }

    /**
     * True if `gGMData.nodes[node].state` has `GM_NODE_STATE_CLEARED` (bit 1): the node's chapter
     * was won.
     */
    public fun isNodeCleared(node: Int): Boolean =
        iwram[BASE + NODES + node].toInt() and NODE_STATE_CLEARED != 0

    /**
     * Reads `gGMData.state.bits.monster_merged` (`GM_STATE_MONSGEN`, bit 1 of the first byte):
     * set once the random monsters have been merged into the map
     * (`WorldMap_SetMonsterMergedState`).
     */
    public fun monsterMerged(): Boolean = (iwram[BASE].toInt() and 0x02) != 0

    /** `gGMData.state.raw` (`u8` at offset 0): the `GM_STATE_*` bits (see `union GMStateBits`). */
    public var stateRaw: Int
        get() = iwram[BASE].toInt() and 0xFF
        set(value) {
            iwram[BASE] = value.toByte()
        }

    /** `gGMData.sprite_disp` (`u8`, offset 1): the world map cursor is drawn while above 0. */
    public var spriteDisp: Int
        get() = iwram[BASE + 1].toInt() and 0xFF
        set(value) {
            iwram[BASE + 1] = value.toByte()
        }

    /** `gGMData.units[unit].location` (`u8`, offset 1 of the unit slot): its node. */
    public fun unitLocation(unit: Int): Int =
        iwram[BASE + UNITS + unit * UNIT_SIZE + 1].toInt() and 0xFF

    /**
     * `gGMData.units[unit].state` (`u8`, offset 0 of the unit slot), `GM_UNIT_STATE_B*` bits. Bit 1
     * selects another spawn check in `EventC3_SpawnLord`, which the port reads as "a class slot"
     * (the decomp only says `id` is a character or class id).
     */
    public fun unitState(unit: Int): Int = iwram[BASE + UNITS + unit * UNIT_SIZE].toInt() and 0xFF

    /** `gGMData.units[unit].id` (`u16`, offset 2 of the slot): character or class id, 0 = none. */
    public fun unitId(unit: Int): Int = u16(UNITS + unit * UNIT_SIZE + 2)

    /** Sets `gGMData.units[unit].location` (`u8`). */
    public fun setUnitLocation(unit: Int, value: Int) {
        iwram[BASE + UNITS + unit * UNIT_SIZE + 1] = value.toByte()
    }

    /** Sets `gGMData.units[unit].state` (`u8`). */
    public fun setUnitState(unit: Int, value: Int) {
        iwram[BASE + UNITS + unit * UNIT_SIZE] = value.toByte()
    }

    /** Sets `gGMData.units[unit].id` (`u16`). */
    public fun setUnitId(unit: Int, value: Int) {
        StructCodec.put16(iwram, BASE + UNITS + unit * UNIT_SIZE + 2, value)
    }

    /**
     * `gGMData.state.bits.state_3` (bit 3 of the first byte): set while the full-screen big map
     * shows; the world map face and effect code then use BG2 (`worldmap_face.c:59`).
     */
    public var state3: Boolean
        get() = stateRaw and 0x08 != 0
        set(value) {
            stateRaw = if (value) stateRaw or 0x08 else stateRaw and 0x08.inv()
        }

    /** `gGMData.nodes[node].state` (`u8`): `GM_NODE_STATE_VALID` (bit 0), `_CLEARED` (bit 1). */
    public fun nodeState(node: Int): Int = iwram[BASE + NODES + node].toInt() and 0xFF

    /** Sets `gGMData.nodes[node].state` to [value] (low 8 bits). */
    public fun setNodeState(node: Int, value: Int) {
        iwram[BASE + NODES + node] = value.toByte()
    }

    /** `gGMData.openPaths.openPathsLength` (`s8`): how many entries of `openPaths` are in use. */
    public val openPathsLength: Int
        get() = iwram[BASE + OPEN_PATHS_LENGTH].toInt()

    /** `gGMData.openPaths.openPaths[index]` (`s8`): an open path id, 0xFF (-1) when unused. */
    public fun openPath(index: Int): Int = iwram[BASE + OPEN_PATHS + index].toInt()

    /** `gGMData.current_node` (`u8`, offset 0xC8): "entry node id?" (decomp comment). */
    public var currentNode: Int
        get() = iwram[BASE + CURRENT_NODE].toInt() and 0xFF
        set(value) {
            iwram[BASE + CURRENT_NODE] = value.toByte()
        }

    /** `gGMData.xCamera` (`s16`, offset 2): the world map scroll x. */
    public var xCamera: Int
        get() = s16(CAMERA_X)
        set(value) = StructCodec.put16(iwram, BASE + CAMERA_X, value)

    /** `gGMData.yCamera` (`s16`, offset 4): the world map scroll y. */
    public var yCamera: Int
        get() = s16(CAMERA_Y)
        set(value) = StructCodec.put16(iwram, BASE + CAMERA_Y, value)

    /** `gGMData.ix` (`int`, offset 8): the cursor's world x; `>> 8` is pixels. */
    public var ix: Int
        get() = u16(IX) or (s16(IX + 2) shl 16)
        set(value) {
            StructCodec.put16(iwram, BASE + IX, value)
            StructCodec.put16(iwram, BASE + IX + 2, value shr 16)
        }

    /** `gGMData.iy` (`int`, offset 0xC): the cursor's world y; `>> 8` is pixels. */
    public var iy: Int
        get() = u16(IY) or (s16(IY + 2) shl 16)
        set(value) {
            StructCodec.put16(iwram, BASE + IY, value)
            StructCodec.put16(iwram, BASE + IY + 2, value shr 16)
        }

    /** The unsigned halfword at [offset] bytes into `gGMData`. */
    private fun u16(offset: Int): Int =
        (iwram[BASE + offset].toInt() and 0xFF) or
            ((iwram[BASE + offset + 1].toInt() and 0xFF) shl 8)

    /** The signed halfword at [offset] bytes into `gGMData`. */
    private fun s16(offset: Int): Int = u16(offset).toShort().toInt()

    /** `gGMData` layout constants. */
    public companion object {
        /** Offsets of `xCamera`, `yCamera`, `ix`, `iy`, `current_node` in `struct GMapData`. */
        private const val CAMERA_X = 2
        private const val CAMERA_Y = 4
        private const val IX = 8
        private const val IY = 0xC
        private const val CURRENT_NODE = 0xC8

        /** `GM_NODE_STATE_CLEARED`. */
        private const val NODE_STATE_CLEARED = 2

        /**
         * IWRAM offset of `gGMData` (0x03005280; the decomp lists no address, `GmDataTest` checks
         * it against a trace).
         */
        public const val BASE: Int = 0x5280

        /** Bytes `CpuFill32` clears. */
        public const val CLEAR_SIZE: Int = 0xD4
        /** Offset of `units`, `sizeof(struct GMUnit)`, slots `GmDataInit` clears (`WM_MU_MAX`). */
        private const val UNITS = 0x10
        private const val UNIT_SIZE = 4
        private const val UNIT_COUNT = 7

        /** Offset of `nodes` (one `GMNode` state byte each). */
        private const val NODES = 0x30

        /** Offset of `openPaths` (`s8[0x20]`) and of its `openPathsLength` right after it. */
        private const val OPEN_PATHS = 0xA4
        private const val OPEN_PATHS_SIZE = 0x20
        private const val OPEN_PATHS_LENGTH = 0xC4

        /** Offsets of `unk_cd` and `unk_ce` (the saved RN state, 3 halfwords). */
        private const val UNK_CD = 0xCD
        private const val UNK_CE = 0xCE
    }
}
