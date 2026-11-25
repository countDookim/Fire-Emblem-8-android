package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventState
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmGameSave
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.GameSaveRam
import fe8.game.save.SuspendRam
import fe8.game.savemenu.PostSaveMenu
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * The two title-menu loads of the PC-11cap runs through `PostSaveMenuHandler`, with the real
 * selection bitfiles and the real `ReadGameSave` / `ReadSuspendSave` as its effects:
 * - `ram_trace_title_continue` (Restart, bitfile 2): from the SRAM of `s01213` the handler's
 *   `& 0x82` arm runs `ReadGameSave(0)`, whose `InvalidateSuspendSave` rewrites the two suspend
 *   block descriptors; the whole SRAM must equal `s01214` (29 bytes differ from `s01213`, at
 *   `0x94..0xB3`; the stack-residue `magic32` of both descriptors is masked). `gPlaySt` equals the
 *   dump of `f1215`, and `nextAction` is 1.
 * - `ram_trace_title_resume` (Resume, bitfile 1): the `& 1` arm runs `ReadSuspendSave(3)`, which
 *   writes no SRAM (`s01099` is unchanged); `gPlaySt` equals the dump of `f1101` and
 *   `nextAction` is 4.
 * Targets are seeded with sentinels, so a skipped load fails. `WriteLastGameSaveId` writes the
 * slot already stored in both runs (a no-op here), so it stays transcribed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class TitleMenuLoadDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private var rom: RomView? = null

    @AfterAll
    fun release() {
        rom = null
    }

    private class Loaded(
        val sram: CartSram,
        val playSt: PlaySt,
        val units: UnitTable,
        val nextAction: Int,
        val effects: List<String>,
    )

    private fun load(run: String, sramDump: String, bitfile: Int, bmStStep: Int): Loaded {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val r = ref.run(run)
        assumeTrue(r.hasSram, "$run has no SRAM dumps")
        val rom = rom ?: RomView.ofRom(romFile.readBytes()).also { rom = it }
        val sram = CartSram().also { it.copyIn(r.sram(sramDump)) }
        val lib = BmSaveLib(sram).also { it.sramInit() }

        val playSt = PlaySt().also { it.decode(ByteArray(PlaySt.SIZE) { 0x5A }, 0) }
        val bmSt = BmSt().also { it.decode(r.watch("gbmst").step(bmStStep), 0) }
        val units = UnitTable(rom)
        units.bytes.fill(0x5A)
        val flags = Fe8EventFlags.create()
        flags.copyIn(ByteArray(flags.size) { 0x5A })
        val clock = GameClock().also { it.set(12345L) }
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        val gameRam = GameSaveRam(playSt, bmSt, units, flags, clock, ByteArray(200) { 0x5A })
        val suspendRam = SuspendRam(
            playSt, bmSt, RngService(), units, flags, clock, EventState(),
            actionData = ByteArray(0x38) { 0x5A },
        )
        var nextAction = 0
        val effects = ArrayList<String>()
        val menu = PostSaveMenu(
            playSt, lib, { nextAction = it },
            PostSaveMenu.Effects(
                readSuspendSave = {
                    effects += "ReadSuspendSave($it)"
                    assertTrue(lib.isValidSuspendSave(it), "suspend save valid")
                    BmSuspendSave(lib, rom).readSuspendSave(suspendRam, it)
                },
                readGameSave = {
                    effects += "ReadGameSave($it)"
                    BmGameSave(lib, loader, rom).readGameSave(gameRam, it)
                },
            ),
        )
        menu.postSaveMenuHandler(
            PostSaveMenu.Selection(mainSelBitfile = bitfile, susSlot = 0),
        )
        return Loaded(sram, playSt, units, nextAction, effects)
    }

    private fun sramBytes(sram: CartSram) = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }

    private fun assertPlaySt(run: String, step: Int, got: PlaySt) {
        val want = ref.run(run).watch("gplayst").step(step)
        val have = ByteArray(PlaySt.SIZE).also { got.encode(it, 0) }
        assertArrayEquals(want, have, "$run gPlaySt at f$step")
    }

    @Test
    @VerifiesPort("PostSaveMenuHandler", "ReadGameSave", "InvalidateSuspendSave")
    fun `continue restarts slot 0 and invalidates the suspend descriptors like s01214`() {
        val got = load(CONTINUE, "s01213", PostSaveMenu.MAIN_MENU_OPTION_RESTART, 1213)
        assertEquals(listOf("ReadGameSave(0)"), got.effects)
        val r = ref.run(CONTINUE)
        val before = r.sram("s01213")
        val after = r.sram("s01214")
        val diff = before.indices.filter { before[it] != after[it] }
        assertEquals(29, diff.size, "reference diff size")
        assertEquals(0x94..0xB3, diff.min()..diff.max())
        val have = sramBytes(got.sram)
        // Masked: `magic32` (4 bytes) of the two descriptors. The C `chunk` local is not cleared
        // (stack residue in the dump), and the port writes 0 there; the readers ignore it.
        for (entry in listOf(SUSPEND_INFO, SUSPEND_INFO + L.BLOCK_INFO_SIZE)) {
            for (i in 0 until 4) {
                have[entry + L.INFO_MAGIC32 + i] = after[entry + L.INFO_MAGIC32 + i]
            }
        }
        val bad = have.indices.filter { have[it] != after[it] }
        assertTrue(bad.isEmpty(), "SRAM differs from s01214 at " + bad.take(20).joinToString {
            "0x%X".format(it)
        })
        assertPlaySt(CONTINUE, 1215, got.playSt)
        assertEquals(0, got.playSt.gameSaveSlot)
        assertEquals(1, got.nextAction)
        // The load wrote the blue units (sentinels gone).
        assertTrue(got.units.bytes.any { it.toInt() != 0x5A })
    }

    @Test
    @VerifiesPort("PostSaveMenuHandler", "ReadSuspendSave")
    fun `resume reads the suspend save without writing SRAM like s01099`() {
        val got = load(RESUME, "s01099", PostSaveMenu.MAIN_MENU_OPTION_RESUME, 1099)
        assertEquals(listOf("ReadSuspendSave(3)"), got.effects)
        assertArrayEquals(ref.run(RESUME).sram("s01099"), sramBytes(got.sram), "SRAM unchanged")
        assertPlaySt(RESUME, 1101, got.playSt)
        assertEquals(4, got.nextAction)
    }

    private companion object {
        const val CONTINUE = "ram_trace_title_continue"
        const val RESUME = "ram_trace_title_resume"
        const val SUSPEND_INFO = L.BLOCK_INFO_OFFSET + L.SAVE_ID_SUSPEND * L.BLOCK_INFO_SIZE
    }
}
