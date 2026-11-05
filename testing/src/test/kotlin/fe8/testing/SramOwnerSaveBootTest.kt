package fe8.testing

import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Boots [BmSaveLib] on the real mGBA saves of `sram_chapter2_boot` / `sram_chapter6_boot` (the
 * owner's save preloaded as SRAM; the first dump `s00001` equals the preloaded image) and compares
 * the SRAM and the boot's RAM with the settled dumps. Expectations about which blocks are valid
 * are derived here from the dump's block info table, not from the port.
 */
class SramOwnerSaveBootTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun info(sram: ByteArray, index: Int): Info {
        val o = 0x64 + index * 0x10
        return Info(
            u32(sram, o), u16(sram, o + 4), u16(sram, o + 8), u16(sram, o + 10), u32(sram, o + 12),
        )
    }

    /** `(sum & 0xFFFF) + (xor << 16)` over the halfwords, written out independently. */
    private fun sum32(sram: ByteArray, off: Int, size: Int): Int {
        var add = 0
        var xor = 0
        for (i in 0 until size / 2) {
            val v = u16(sram, off + i * 2)
            add += v
            xor = xor xor v
        }
        return (add and 0xFFFF) + (xor shl 16)
    }

    private fun expectValid(sram: ByteArray, index: Int): Boolean {
        val i = info(sram, index)
        if (i.magic16 != 0x200A || i.magic32 != 0x40624) return false
        if (i.offset + i.size > L.SRAM_SIZE) return false
        return i.checksum == sum32(sram, i.offset, i.size)
    }

    private fun sum16Ok(sram: ByteArray, off: Int, size: Int, sumAt: Int, sumSize: Int): Boolean {
        var add = 0
        var xor = 0
        for (i in 0 until sumSize / 2) {
            val v = u16(sram, off + i * 2)
            add += v
            xor = xor xor v
        }
        assertTrue(off + size <= L.SRAM_SIZE)
        return u16(sram, off + sumAt) == ((add + xor) and 0xFFFF)
    }

    private fun check(run: String, suspendIndex: Int?, expectedValid: List<Boolean>): ByteArray {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        assertTrue(r.hasSram, "$run has no SRAM dumps")
        val preload = r.sram("s00001")
        assertEquals(L.SRAM_SIZE, preload.size)

        val sram = CartSram()
        sram.copyIn(preload)
        val lib = BmSaveLib(sram)
        lib.boot()
        val image = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        assertArrayEquals(r.sram("s00900"), image, "$run: SRAM after boot vs s00900")

        val valid = (0 until 7).map { expectValid(preload, it) }
        assertEquals(expectedValid, valid, "$run: blocks valid in the save")
        for (index in 0 until 7) {
            assertEquals(valid[index], lib.isSaveValid(index), "$run: block $index")
        }

        // RAM the boot sets: gSuspendSaveIdOffset (0x0203EDB8) and gBoolSramWorking (0x0203E88E).
        val expectOffset = suspendIndex?.minus(L.SAVE_ID_SUSPEND) ?: 0x7F
        for (dump in listOf("r00005", "r00060", "r00900")) {
            val ewram = r.region(dump, "ewram")
            assertEquals(expectOffset, ewram[0x3EDB8].toInt() and 0xFF, "$run $dump suspend id")
            assertEquals(1, ewram[0x3E88E].toInt() and 0xFF, "$run $dump gBoolSramWorking")
        }
        assertEquals(expectOffset, lib.suspendSaveIdOffset)
        assertTrue(lib.isSramWorking())
        assertEquals(suspendIndex != null, lib.isValidSuspendSave(L.SAVE_ID_SUSPEND))
        assertEquals(expectOffset, lib.suspendSaveIdOffset)

        // The aux blocks carry real, correctly checksummed data in the save: a wrong check would
        // make boot erase (change) them, and the image comparison above would fail.
        assertTrue(sum16Ok(preload, L.RANK_OFFSET, L.RANK_SIZE, L.RANK_CHECKSUM, L.RANK_SUM_SIZE))
        assertTrue(lib.loadAndVerifyRankData())
        assertTrue(
            sum16Ok(preload, L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_SIZE, L.SOUND_ROOM_CHECKSUM,
                L.SOUND_ROOM_CHECKSUM),
        )
        assertTrue(lib.loadAndVerifySoundRoomData())
        assertTrue(lib.loadAndVerifyLinkArenaStruct2())
        return preload
    }

    @Test
    @VerifiesPort(
        "IsValidSuspendSave", "GetLastSuspendSaveId", "VerifySaveBlockChecksum",
        "SramChecksum32", "IsSaveValid",
    )
    fun `chapter 2 save with a valid suspend boots unchanged`() {
        // Game slots 0..2 and both suspend entries are valid; last_suspend_slot is 1, so the
        // suspend save is entry 4 (offset 1). Arena and extra map infos are blank (0xFF).
        val preload =
            check("sram_chapter2_boot", 4, listOf(true, true, true, true, true, false, false))
        assertEquals(1, preload[L.HEADER_LAST_SUSPEND_SLOT].toInt())
    }

    @Test
    @VerifiesPort(
        "IsValidSuspendSave", "GetLastSuspendSaveId", "VerifySaveBlockChecksum",
        "SramChecksum32", "IsSaveValid",
    )
    fun `chapter 6 save without a suspend boots unchanged and selects 0x7F`() {
        check("sram_chapter6_boot", null, listOf(true, true, true, false, false, false, false))
    }

    @Test
    @VerifiesPort("VerifySaveBlockChecksum", "SramChecksum32", "IsSaveValid")
    fun `a flipped data byte invalidates exactly its block`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val preload = ref.run("sram_chapter2_boot").sram("s00001")
        for (index in 0..4) {
            val i = info(preload, index)
            val bad = preload.copyOf()
            bad[i.offset + i.size - 1] = (bad[i.offset + i.size - 1].toInt() xor 1).toByte()
            assertTrue(!expectValid(bad, index))
            val lib = BmSaveLib(CartSram().also { it.copyIn(bad) }).also { it.sramInit() }
            for (other in 0..4) {
                assertEquals(other != index, lib.isSaveValid(other), "$index/$other")
            }
        }
    }

    /** One decoded block-info entry (below the tests: tools/progress.py credits the last class). */
    private class Info(
        val magic32: Int,
        val magic16: Int,
        val offset: Int,
        val size: Int,
        val checksum: Int,
    )
}
