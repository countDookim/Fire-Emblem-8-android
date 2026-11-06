package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmGameSave
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.GameSaveRam
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `ReadGameSave` and its helpers against `ram_trace_ch6_start`, whose SRAM is the `chapter6.sav`
 * fixture (the run's SRAM dumps are not kept; `sram_chapter6_boot` s00001 is the same preloaded
 * file). The game save of slot 1 is loaded between r01139 and r01141 (the call straddles a frame
 * boundary: `gPlaySt` changes in r01139, the unit arrays in r01139 and r01141); nothing of these
 * changes in r01142..r01148, so r01142 is the expected result. The targets are seeded with
 * sentinels so that a skipped write fails.
 *
 * Not compared (no owner yet): `gGMData` (`ReadWorldMapStuff` not ported), the monster RN state
 * and `gDungeonState.dungeon`.
 */
class ReadGameSaveReferenceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_ch6_start"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: ReadBonusContentClaimFlags (zero in the reference: the sentinel proves
    // the read happens, not its offset), ReadGMMonsterRnState, ReadWorldMapStuff,
    // LoadSavedWMStuff (world map not modelled), LoadDungeonRecords (not compared).
    // Coverage limits of the ReadGameSave tag: the invalidation of the suspend save (invalid in
    // chapter6.sav already) and gameSaveSlot = slot (the file stores 1 already) are unobserved.
    @Test
    @VerifiesPort(
        "ReadGameSave", "LoadSavedUnit", "ReadPidStats", "ReadPermanentFlags", "InitUnits",
        "ReadSupplyItems", "ReadChapterStats",
    )
    fun `reading chapter6 sav slot 1 reproduces the RAM of r01142`() {
        val f = fixture()
        val after = f.after
        val iwAfter = f.iwAfter
        f.lib.writeLastGameSaveId(2)
        f.game.readGameSave(f.ram, SLOT)
        val ram = f.ram

        // gPlaySt: all 0x4C bytes (gameSaveSlot = slot is part of them).
        val playSt = ByteArray(PlaySt.SIZE).also { ram.playSt.encode(it, 0) }
        assertArrayEquals(after.copyOfRange(GPLAYST - EWRAM, GPLAYST - EWRAM + PlaySt.SIZE), playSt)
        assertEquals(SLOT, ram.playSt.gameSaveSlot)
        assertEquals(7, ram.playSt.chapterIndex, "chapter index 7")
        assertEquals(ram.playSt.timeSaved.toLong() and 0xFFFFFFFFL, ram.clock.get())
        val dumped = rdU32(iwAfter, GGAMECLOCK - IWRAM).toLong() and 0xFFFFFFFFL
        assertTrue(dumped - ram.clock.get() in 1L..6L, "clock ${ram.clock.get()} vs dump $dumped")

        // InitUnits + LoadSavedUnit: every byte of all four arrays. Masked: +0x3C..0x3F
        // (pMapSpriteHandle), set by RefreshUnitSprites, never by the load.
        val want = after.copyOfRange(UNITS - EWRAM, UNITS - EWRAM + UnitTable.TOTAL_SIZE)
        val got = ram.units.bytes.copyOf()
        for (u in 0 until UnitTable.TOTAL_SIZE / UnitTable.UNIT_SIZE) {
            val o = u * UnitTable.UNIT_SIZE
            for (i in 0x3C until 0x40) {
                assertEquals(0, got[o + i].toInt(), "unit slot $u handle is not written")
                want[o + i] = 0
            }
        }
        val bad = (0 until UnitTable.TOTAL_SIZE).filter { want[it] != got[it] }
        assertTrue(bad.isEmpty(), "unit diff at " + bad.take(30).joinToString { "0x%X".format(it) })
        assertTrue(
            (0 until UnitTable.BLUE_COUNT).count { got[it * UnitTable.UNIT_SIZE].toInt() != 0 } >=
                5,
            "units were loaded",
        )

        // Permanent flags: the saved bytes only.
        for (i in 0 until Fe8EventFlags.PERMANENT_SAVE_BYTES) {
            val want = iwAfter[GFLAGS - IWRAM + i].toInt() and 0xFF
            assertEquals(want, ram.flags.byteAt(i), "perm $i")
        }
        assertTrue((0 until 0x19).any { ram.flags.byteAt(it) != 0 }, "permanent flags are set")
        // Convoy, pid stats, chapter stats, bonus claim flags.
        assertArrayEquals(
            after.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + 200), ram.convoy,
        )
        assertArrayEquals(
            after.copyOfRange(GPIDSTATS - EWRAM, GPIDSTATS - EWRAM + f.lib.pidStatsData.size),
            f.lib.pidStatsData,
        )
        assertTrue(f.lib.pidStatsData.any { it.toInt() != 0 }, "pid stats are not empty")
        assertArrayEquals(
            after.copyOfRange(GCHAPSTATS - EWRAM, GCHAPSTATS - EWRAM + f.lib.chapterStatsData.size),
            f.lib.chapterStatsData,
        )
        assertEquals(rdU32(after, GBONUS - EWRAM), f.lib.bonusContentClaimFlags)
        assertEquals(f.srcBlock + GAME_PID_STATS, f.lib.pidStatsSaveLoc)
        // WriteLastGameSaveId(slot).
        assertEquals(SLOT, f.lib.readLastGameSaveId())
        // gBmSt.gameStateBits has no PLAY_FLAG_HARD: the suspend save was invalidated.
        assertTrue(!f.lib.isSaveValid(L.SAVE_ID_SUSPEND), "suspend save invalidated")
    }

    // Not @VerifiesPort: WriteGMMonsterRnState (stand-in state, 2 bytes masked),
    // WriteBonusContentClaimFlags (zero in the reference: its offset is unobserved), the
    // SGM_SetCharacterKnown / WriteGlobalSaveInfo part (the fresh image keeps the known flags;
    // break-check passes), InvalidateSuspendSave (already invalid).
    @Test
    // Not @VerifiesPort: WriteLastGameSaveId (the image already holds slot 1 as the last game
    // save, so a no-op survives; audit 2026-11-06).
    @VerifiesPort(
        "WriteGameSave", "WriteGameSavePackedUnit", "WriteSupplyItems", "WritePidStats",
        "WriteChapterStats", "WritePermanentFlags",
    )
    fun `write after read reproduces slot 1 of chapter6 sav`() {
        val f = fixture()
        val orig = f.image
        f.game.readGameSave(f.ram, SLOT)
        val dest = f.lib.getSaveWriteAddr(SLOT)
        val info = L.blockInfoOffset(SLOT)
        // A fresh image: the original without the slot 1 block, its info entry and the last id.
        val fresh = orig.copyOf()
        fresh.fill(0xFF.toByte(), dest, dest + L.GAME_SIZE)
        fresh.fill(0xFF.toByte(), info, info + L.BLOCK_INFO_SIZE)
        val sram = CartSram().also { it.copyIn(fresh) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        val loader = f.loader
        val game = BmGameSave(lib, loader, f.rom)
        // Stand-ins load does not restore from their own state: the saved ones are kept raw.
        lib.pidStatsData.let { f.lib.pidStatsData.copyInto(it) }
        lib.setBonusContentClaimFlags(f.lib.bonusContentClaimFlags)
        f.lib.chapterStatsData.copyInto(lib.chapterStatsData)
        lib.writeLastGameSaveId(2)
        game.writeGameSave(f.ram, SLOT)
        val out = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        val got = out.copyOfRange(dest, dest + L.GAME_SIZE)
        val want = orig.copyOfRange(dest, dest + L.GAME_SIZE)
        // Masked: the last 2 bytes of the 8-byte monster RN state (0x77E..0x77F, uninitialised
        // stack in C) and the last supply byte (0x84B, the uninitialised `buf` of
        // WriteSupplyItems).
        for (i in intArrayOf(0x77E, 0x77F, 0x84B)) got[i] = want[i]
        val bad = (0 until L.GAME_SIZE).filter { got[it] != want[it] }
        val where = bad.take(30).joinToString { "0x%X".format(it) }
        assertTrue(bad.isEmpty(), "block diff at $where")
        // Block info: all but checksum32 equal (the checksum covers the 3 masked bytes, which
        // differ); the written checksum is the one of the written block, as it is in the original.
        assertArrayEquals(
            orig.copyOfRange(info, info + L.INFO_CHECKSUM32),
            out.copyOfRange(info, info + L.INFO_CHECKSUM32), "block info",
        )
        assertTrue(lib.isSaveValid(SLOT), "written block verifies against its checksum")
        assertEquals(SLOT, lib.readLastGameSaveId())
    }

    private fun fixture(): GameFixture {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val r = ref.run(run)
        val pre = ref.run("sram_chapter6_boot")
        assumeTrue(pre.hasSram, "sram_chapter6_boot has no SRAM dumps")
        val rom = RomView.ofRom(romFile.readBytes())
        val image = pre.sram("s00001")
        val sram = CartSram().also { it.copyIn(image) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        assertTrue(lib.isSaveValid(SLOT), "chapter6.sav has a valid game save in slot 1")
        val srcBlock = lib.getSaveReadAddr(SLOT)

        val playSt = PlaySt().also { it.decode(ByteArray(PlaySt.SIZE) { 0x5A }, 0) }
        val units = UnitTable(rom)
        units.bytes.fill(0x5A)
        val flags = Fe8EventFlags.create()
        flags.copyIn(ByteArray(flags.size) { 0x5A })
        val clock = GameClock().also { it.set(12345L) }
        val ram = GameSaveRam(
            playSt, BmSt(), units, flags, clock, ByteArray(200) { 0x5A },
        )
        lib.pidStatsData.fill(0x5A)
        lib.chapterStatsData.fill(0x5A)
        lib.setBonusContentClaimFlags(0x5A5A5A5A)
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        return GameFixture(
            ram, BmGameSave(lib, loader, rom), lib, srcBlock, image, loader, rom,
            r.region("r01142", "ewram"), r.region("r01142", "iwram"),
        )
    }

    private companion object {
        const val SLOT = 1
        const val GAME_PID_STATS = 0x84C
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val UNITS = 0x0202BE4C
        const val GCONVOY = 0x0203A81C
        const val GPIDSTATS = 0x0203E894
        const val GCHAPSTATS = 0x0203ECF4
        const val GBONUS = 0x0203EDB4
        const val GGAMECLOCK = 0x03000014
        const val GFLAGS = 0x03005250
    }
}

/** Everything the test above needs. */
private class GameFixture(
    val ram: GameSaveRam,
    val game: BmGameSave,
    val lib: BmSaveLib,
    val srcBlock: Int,
    val image: ByteArray,
    val loader: UnitLoader,
    val rom: RomView,
    val after: ByteArray,
    val iwAfter: ByteArray,
)

private fun rdU16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun rdU32(b: ByteArray, o: Int): Int = rdU16(b, o) or (rdU16(b, o + 2) shl 16)
