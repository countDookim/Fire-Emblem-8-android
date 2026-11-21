package fe8.testing

import fe8.game.save.BmSaveGmap
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.Fe8SuspendLayout as S
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential verification of `src/bmsave-gmap.c` (`WriteWorldMapStuff`, `WriteWorldMapNodes`,
 * `WriteWorldMapPaths`, `WriteWorldMapUnits`) and `SramOffsetToAddr` against:
 * - `ram_trace_prologue_clear` (`f03850`, `f03860`, `r04273`, where `SramChecksum32` leaves the
 *   written suspend block in `gGenericBuffer` at `0x02020188`; `f2036` and `f2741` have no dumps
 *   and `ram_trace_prologue_clear` has no `.sram.bin` dumps)
 * - `prologue.sav` (`ram_trace_prologue_resume` `s01001` with `r01150` IWRAM)
 * - `chapter2.sav` (`sram_chapter2_boot` `s00001` with `ram_trace_ch2_scroll` `r01142` IWRAM)
 * - `chapter6.sav` (`sram_chapter6_boot` `s00001` with `ram_trace_ch6_start` `r01142` IWRAM)
 */
class BmSaveGmapReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("WriteWorldMapStuff", "WriteWorldMapUnits")
    fun `writeWorldMapStuff reproduces prologue_clear generic buffer and prologue sav wmStuff`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val clear = ref.run("ram_trace_prologue_clear")
        // r04272 precedes SramChecksum32 (buffer not yet populated); r04273 has the finished
        // suspend block read into gGenericBuffer by SramChecksum32.
        val r4272 = clear.region("r04272", "ewram")
        val r4273 = clear.region("r04273", "ewram")
        val wmOff = GGENERIC_BUF - EWRAM + S.WM_STUFF
        assertNotEquals(
            r4272.copyOfRange(wmOff, wmOff + S.GMAP_SAVE_INFO_SIZE).toList(),
            r4273.copyOfRange(wmOff, wmOff + S.GMAP_SAVE_INFO_SIZE).toList(),
        )
        for (step in listOf("f03850", "f03860", "r04273")) {
            val iwram = clear.region(step, "iwram")
            val ewram = clear.region(step, "ewram")
            assertWmStuffMatches(
                iwram,
                ewram.copyOfRange(wmOff, wmOff + S.GMAP_SAVE_INFO_SIZE),
                isSuspend = true,
                label = "ram_trace_prologue_clear/$step",
            )
        }

        val resume = ref.run("ram_trace_prologue_resume")
        assumeTrue(resume.hasSram, "ram_trace_prologue_resume has no SRAM dumps")
        val prologueSav = resume.sram("s01001")
        val prologueIwram = resume.region("r01150", "iwram")
        assertWmStuffMatches(
            prologueIwram,
            prologueSav.copyOfRange(
                L.SUSPEND0_OFFSET + S.WM_STUFF,
                L.SUSPEND0_OFFSET + S.WM_STUFF + S.GMAP_SAVE_INFO_SIZE,
            ),
            isSuspend = true,
            label = "prologue.sav SUS0",
        )
    }

    @Test
    @VerifiesPort(
        "WriteWorldMapStuff", "WriteWorldMapNodes", "WriteWorldMapPaths", "WriteWorldMapUnits",
    )
    fun `writeWorldMapStuff and helpers match non-zero chapter2 and chapter6 saves`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val ch2Iwram = ref.run("ram_trace_ch2_scroll").region("r01142", "iwram")
        val ch2Sav = ref.run("sram_chapter2_boot").sram("s00001")
        assertWmStuffMatches(
            ch2Iwram,
            ch2Sav.copyOfRange(
                L.SUSPEND0_OFFSET + S.WM_STUFF,
                L.SUSPEND0_OFFSET + S.WM_STUFF + S.GMAP_SAVE_INFO_SIZE,
            ),
            isSuspend = true,
            label = "chapter2.sav SUS0",
        )

        val ch6Iwram = ref.run("ram_trace_ch6_start").region("r01142", "iwram")
        val ch6Sav = ref.run("sram_chapter6_boot").sram("s00001")
        assertWmStuffMatches(
            ch6Iwram,
            ch6Sav.copyOfRange(
                L.GAME1_OFFSET + GAME_WM_STUFF,
                L.GAME1_OFFSET + GAME_WM_STUFF + S.GMAP_SAVE_INFO_SIZE,
            ),
            isSuspend = false,
            label = "chapter6.sav GAME1",
        )
    }

    @Test
    @VerifiesPort("SramOffsetToAddr")
    fun `sramOffsetToAddr resolves save block offsets in prologue chapter2 and chapter6 sav`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val cases = listOf(
            ref.run("ram_trace_prologue_resume").sram("s01001") to L.SAVE_ID_SUSPEND,
            ref.run("sram_chapter2_boot").sram("s00001") to L.SAVE_ID_SUSPEND,
            ref.run("sram_chapter6_boot").sram("s00001") to L.SAVE_ID_GAME1,
        )
        for ((image, slot) in cases) {
            val sram = CartSram().also { it.copyIn(image) }
            val lib = BmSaveLib(sram).also { it.sramInit() }
            assertEquals(lib.getSaveWriteAddr(slot), lib.getSaveReadAddr(slot))
            val info = ByteArray(L.BLOCK_INFO_SIZE)
            assertTrue(lib.readSaveBlockInfo(info, slot))
            val storedOff = (info[L.INFO_OFFSET].toInt() and 0xFF) or
                ((info[L.INFO_OFFSET + 1].toInt() and 0xFF) shl 8)
            assertEquals(storedOff, lib.sramOffsetToAddr(storedOff))
            assertEquals(storedOff, lib.sramOffsetToAddr(storedOff or 0x0E000000))
        }
    }

    private fun assertWmStuffMatches(
        iwram: ByteArray,
        want: ByteArray,
        isSuspend: Boolean,
        label: String,
    ) {
        val gm = GmData(iwram.copyOf(0x8000), RngService())
        val gmap = BmSaveGmap()

        // Exercise each sub-writer directly on a 0x5A-seeded buffer so clearing is proven.
        val nodesBuf = ByteArray(BmSaveGmap.NODES_BYTES) { 0x5A }
        gmap.writeWorldMapNodes(gm, nodesBuf)
        assertArrayEquals(
            want.copyOfRange(BmSaveGmap.INFO_NODES, BmSaveGmap.INFO_NODES + BmSaveGmap.NODES_BYTES),
            nodesBuf,
            "$label nodes",
        )

        val pathsBuf = ByteArray(BmSaveGmap.PATHS_BYTES) { 0x5A }
        gmap.writeWorldMapPaths(gm, pathsBuf)
        assertArrayEquals(
            want.copyOfRange(BmSaveGmap.INFO_PATHS, BmSaveGmap.INFO_PATHS + BmSaveGmap.PATHS_BYTES),
            pathsBuf,
            "$label paths",
        )

        val unitsBuf = ByteArray(BmSaveGmap.UNITS_BYTES) { 0x5A }
        gmap.writeWorldMapUnits(gm, unitsBuf)
        assertArrayEquals(
            want.copyOfRange(BmSaveGmap.INFO_UNITS, BmSaveGmap.INFO_UNITS + BmSaveGmap.UNITS_BYTES),
            unitsBuf,
            "$label units",
        )

        // Unverified (skirmishes are 0 in every capture); still checks the 3-byte target write.
        val skirmishesBuf = ByteArray(GmData.SKIRMISH_COUNT) { 0x5A }
        gmap.writeWorldMapSkirmishes(gm, skirmishesBuf)
        assertArrayEquals(
            want.copyOfRange(
                BmSaveGmap.INFO_SKIRMISHES,
                BmSaveGmap.INFO_SKIRMISHES + GmData.SKIRMISH_COUNT,
            ),
            skirmishesBuf,
            "$label skirmishes",
        )

        // Seed SRAM destination with 0x5A so every byte of the 0x24-byte write is proven.
        val sram = CartSram().also { it.copyIn(ByteArray(L.SRAM_SIZE) { 0x5A }) }
        val dest = 0x100
        gmap.writeWorldMapStuff(sram, dest, gm)
        val got = ByteArray(S.GMAP_SAVE_INFO_SIZE)
        sram.read(dest, got, 0, S.GMAP_SAVE_INFO_SIZE)

        // Verify low nibble of byte 0x1C (unk_1c_1..3); high nibble is C stack garbage in C.
        val bits1c = BmSaveGmap.INFO_BITS_1C
        assertEquals(want[bits1c].toInt() and 0x0F, got[bits1c].toInt() and 0x0F, "$label 0x1C")
        got[bits1c] = ((got[bits1c].toInt() and 0x0F) or (want[bits1c].toInt() and 0xF0)).toByte()
        if (!isSuspend) {
            // GameSaveBlock trailing padding bytes 0x21..0x23 are also C stack garbage in GAME1.
            for (i in 0x21 until S.GMAP_SAVE_INFO_SIZE) got[i] = want[i]
        }
        assertArrayEquals(want, got, "$label wmStuff")
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val GGENERIC_BUF = 0x02020188
        const val GAME_WM_STUFF = 0xD8C
    }
}
