package fe8.game.worldmap

import fe8.game.bm.StructCodec
import gbafe.core.rng.RngService

/**
 * The world map state block `gGMData` (`struct GMapData`) in emulated IWRAM.
 *
 * @param iwram the 32 KiB IWRAM image (address 0x03000000 is index 0)
 * @param rng the generator whose primary state `StoreRNState` copies into `unk_ce`
 */
public class GmData(private val iwram: ByteArray, private val rng: RngService) {
    /**
     * Ported from src/worldmap_path.c: GmDataInit
     *
     * Zeroes `gGMData` (0xD4 bytes), marks all open paths unused, clears the unit slots, places
     * unit 0 (id 1, location 0), marks node 0 cleared, sets the state bits and saves the RN state.
     */
    public fun init() {
        iwram.fill(0, BASE, BASE + CLEAR_SIZE)
        // GmPathsInit
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

    public companion object {
        /** IWRAM offset of `gGMData` (0x03005280). */
        public const val BASE: Int = 0x5280

        /** Bytes `CpuFill32` clears. */
        public const val CLEAR_SIZE: Int = 0xD4
        private const val UNITS = 0x10
        private const val UNIT_SIZE = 4
        private const val UNIT_COUNT = 7
        private const val NODES = 0x30
        private const val OPEN_PATHS = 0xA4
        private const val OPEN_PATHS_SIZE = 0x20
        private const val OPEN_PATHS_LENGTH = 0xC4
        private const val UNK_CD = 0xCD
        private const val UNK_CE = 0xCE
    }
}
