package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventState
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.Fe8SuspendLayout as S
import fe8.game.save.SuspendRam
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
 * `ReadSuspendSave` and its helpers against `ram_trace_ch2_scroll`, whose SRAM is the
 * `chapter2.sav` fixture (the run's SRAM dumps are not kept; `sram_chapter2_boot` s00001 is the
 * same preloaded file, byte for byte). `scan_trace` puts the resume load between the per-frame
 * dumps r01139 (title / save menu) and r01142: the call straddles a frame boundary (`gPlaySt`
 * and `gActionData` change in r01140, the unit arrays in r01140 and r01141), and nothing of
 * these changes in r01142..r01148 (r01149 on is the map start). The RAM of r01142 is the
 * expected result, and the targets are seeded with sentinels so that a skipped write fails.
 *
 * Not modelled (no owner yet, so not compared): the menu override list (`sMenuOverrides` not
 * ported), `gGMData` (`ReadWorldMapStuff` not ported) and `sub_80A71F8`'s target. They are kept
 * raw in [SuspendRam] and only the round trip checks them.
 */
class ReadSuspendSaveReferenceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_ch2_scroll"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: LoadDungeonState (unk_0B_4 / type are zero in the reference, so the
    // type derivation is unobserved), ReadSupplyItems (the convoy of chapter 2 is empty: zeros only prove the
    // target, not the unpacking), ReadChapterStats and LoadSavedBonusClaimFlags (all zero in the
    // reference: target proven, not data), ReadTraps (every trap is zero), ReadGMMonsterRnState
    // (not in a suspend save), InitUnits' ClearUnit is part of InitUnits (tagged).
    // Coverage limits: gameSaveSlot 0 (LoadSavedBonusClaimFlags reads game block 0 here),
    // and the saved dungeon / menu override / world map stand-ins are not compared with RAM.
    @Test
    @VerifiesPort(
        "ReadSuspendSave", "ReadSuspendSavePackedUnit", "InitUnits", "ReadPidStats",
        "ReadPermanentFlags", "ReadChapterFlags", "SetEventSlotCounter",
    )
    fun `reading chapter2 sav reproduces the RAM of r01142`() {
        val f = fixture()
        val after = f.after
        val iwAfter = f.iwAfter
        f.suspend.readSuspendSave(f.ram, L.SAVE_ID_SUSPEND)
        val ram = f.ram

        // gPlaySt: all 0x4C bytes.
        val playSt = ByteArray(PlaySt.SIZE).also { ram.playSt.encode(it, 0) }
        assertArrayEquals(after.copyOfRange(GPLAYST - EWRAM, GPLAYST - EWRAM + PlaySt.SIZE), playSt)
        assertEquals(2, ram.playSt.chapterIndex, "chapter 2")
        // SetGameTime(time_saved) -> gGameClock.
        // The dump's clock has kept counting since the call (IncrementGameClock, once a frame
        // after the load): it is time_saved plus the frames since, 0..6 here.
        val dumped = rdU32(iwAfter, GGAMECLOCK - IWRAM).toLong() and 0xFFFFFFFFL
        assertTrue(dumped - ram.clock.get() in 1L..6L, "clock ${ram.clock.get()} vs dump $dumped")
        assertEquals(ram.playSt.timeSaved.toLong() and 0xFFFFFFFFL, ram.clock.get())
        // gActionData, all 0x38 bytes (the first 6 are the RN state).
        assertArrayEquals(
            after.copyOfRange(GACTION - EWRAM, GACTION - EWRAM + S.ACTION_SIZE), ram.actionData,
        )
        // LoadRNStateFromActionStruct -> gRNSeeds.
        val rng = IntArray(3)
        ram.rng.primary.store(rng)
        for (i in 0 until 3) assertEquals(rdU16(iwAfter, i * 2), rng[i], "rn $i")
        // InitUnits + the unit records: every byte of all four arrays. Masked: +0x3C..0x3F
        // (pMapSpriteHandle) of every unit, set by RefreshUnitSprites, never by the load.
        val want = after.copyOfRange(UNITS - EWRAM, UNITS - EWRAM + UnitTable.TOTAL_SIZE)
        val got = ram.units.bytes.copyOf()
        for (u in 0 until UnitTable.TOTAL_SIZE / UnitTable.UNIT_SIZE) {
            val o = u * UnitTable.UNIT_SIZE
            for (i in 0x3C until 0x40) {
                assertEquals(0, got[o + i].toInt(), "unit slot $u handle is not written by the load")
                want[o + i] = 0
            }
        }
        val bad = (0 until UnitTable.TOTAL_SIZE).filter { want[it] != got[it] }
        assertTrue(bad.isEmpty(), "unit diff at " + bad.take(30).joinToString { "0x%X".format(it) })
        assertTrue(
            (0 until UnitTable.BLUE_COUNT).count { ram.units.bytes[it * UnitTable.UNIT_SIZE].toInt() != 0 } >= 5,
            "units were loaded",
        )
        // Flags: the saved parts only (the rest of the mirror is not loaded by the call).
        for (i in 0 until Fe8EventFlags.PERMANENT_SAVE_BYTES) {
            assertEquals(iwAfter[GFLAGS - IWRAM + i].toInt() and 0xFF, ram.flags.byteAt(i), "perm $i")
        }
        for (i in 0 until Fe8EventFlags.CHAPTER_SAVE_BYTES) {
            val at = Fe8EventFlags.PERMANENT_STORAGE_BYTES + i
            assertEquals(iwAfter[GFLAGS - IWRAM + at].toInt() and 0xFF, ram.flags.byteAt(at), "chap $i")
        }
        // The flag readers themselves, into fresh sentinel buffers (the call above reads into
        // an internal buffer whose stale zeros would hide a short read).
        val perm = ByteArray(Fe8EventFlags.PERMANENT_SAVE_BYTES) { 0x5A }
        f.lib.readPermanentFlags(f.srcEntry + S.PERMANENT_FLAGS, perm)
        for (i in perm.indices) assertEquals(iwAfter[GFLAGS - IWRAM + i], perm[i], "perm buf $i")
        val chap = ByteArray(Fe8EventFlags.CHAPTER_SAVE_BYTES) { 0x5A }
        f.lib.readChapterFlags(f.srcEntry + S.CHAPTER_FLAGS, chap)
        for (i in chap.indices) {
            val at = GFLAGS - IWRAM + Fe8EventFlags.PERMANENT_STORAGE_BYTES + i
            assertEquals(iwAfter[at], chap[i], "chapter buf $i")
        }
        // Convoy (gConvoyItemArray), pid / chapter stats, traps, bonus claim flags.
        assertArrayEquals(
            after.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + SuspendRam.CONVOY_BYTES),
            ram.convoy,
        )
        assertArrayEquals(
            after.copyOfRange(GPIDSTATS - EWRAM, GPIDSTATS - EWRAM + f.lib.pidStatsData.size),
            f.lib.pidStatsData,
        )
        assertArrayEquals(
            after.copyOfRange(GCHAPSTATS - EWRAM, GCHAPSTATS - EWRAM + f.lib.chapterStatsData.size),
            f.lib.chapterStatsData,
        )
        assertTrue(f.lib.pidStatsData.any { it.toInt() != 0 }, "pid stats are not empty")
        assertTrue((0 until 0x19).any { ram.flags.byteAt(it) != 0 }, "permanent flags are set")
        assertEquals(f.srcEntry + S.PID_STATS, f.lib.pidStatsSaveLoc)
        assertArrayEquals(
            after.copyOfRange(GTRAPS - EWRAM, GTRAPS - EWRAM + ram.traps.size), ram.traps,
        )
        assertEquals(rdU32(after, GBONUS - EWRAM), f.lib.bonusContentClaimFlags)
        // gEventSlotCounter and gDungeonState (current, records, type).
        assertEquals(rdU32(iwAfter, GEVENTSLOT - IWRAM), ram.eventState.getEventSlotCounter())
        assertArrayEquals(
            iwAfter.copyOfRange(GDUNGEON - IWRAM, GDUNGEON - IWRAM + S.DUNGEON_STATE_SIZE),
            ram.dungeonState,
        )
    }

    // Not @VerifiesPort: the unit-array and flag readers were verified above; this one tags
    // nothing new (WriteSuspendSave is already tagged by SuspendSaveReferenceTest).
    @Test
    fun `write after read reproduces the suspend entry of chapter2 sav`() {
        val f = fixture()
        f.suspend.readSuspendSave(f.ram, L.SAVE_ID_SUSPEND)
        val orig = f.sramImage
        val src = f.srcEntry
        val dest = f.lib.getSaveWriteAddr(L.SAVE_ID_SUSPEND + f.lib.getNextSuspendSaveId())
        assertTrue(dest != src, "the writer targets the other block")
        // The records of empty units are left alone by the C writer (stale gGenericBuffer): what
        // the buffer holds is not derivable, so it is seeded from the original record bytes.
        for (k in 0 until S.UNIT_SAVE_AMOUNT_BLUE) {
            orig.copyInto(
                f.suspend.genericBuffer, k * S.PACKED_UNIT_SIZE,
                src + S.BLUE_UNITS + k * S.PACKED_UNIT_SIZE,
                src + S.BLUE_UNITS + (k + 1) * S.PACKED_UNIT_SIZE,
            )
        }
        for (k in S.UNIT_SAVE_AMOUNT_BLUE until S.UNIT_SAVE_AMOUNT_RED + S.UNIT_SAVE_AMOUNT_GREEN) {
            orig.copyInto(
                f.suspend.genericBuffer, k * S.PACKED_UNIT_SIZE,
                src + S.RED_UNITS + k * S.PACKED_UNIT_SIZE,
                src + S.RED_UNITS + (k + 1) * S.PACKED_UNIT_SIZE,
            )
        }
        f.suspend.writeSuspendSave(f.ram, L.SAVE_ID_SUSPEND)
        val out = ByteArray(L.SRAM_SIZE).also { f.sram.copyOut(it) }
        val got = out.copyOfRange(dest, dest + S.SIZE)
        val want = orig.copyOfRange(src, src + S.SIZE)
        // Masked, each with its reason: the last supply byte is uninitialised stack in C
        // (WriteSupplyItems' `buf` is not cleared), and bytes 5..6 of chapterFlags, the 0x1C
        // padding of wmMonsterUnit and the end padding are never written by the C code.
        got[S.SUPPLY + 0xB0 - 1] = want[S.SUPPLY + 0xB0 - 1]
        for (i in 5 until 7) got[S.CHAPTER_FLAGS + i] = want[S.CHAPTER_FLAGS + i]
        for (i in 0x18 until S.PACKED_UNIT_SIZE) got[S.WM_MONSTER_UNIT + i] = want[S.WM_MONSTER_UNIT + i]
        val bad = (0 until S.SIZE).filter { got[it] != want[it] }
        assertTrue(bad.isEmpty(), "diff at " + bad.take(30).joinToString { "0x%X".format(it) })
    }

    private fun fixture(): ResumeFixture {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val r = ref.run(run)
        val pre = ref.run("sram_chapter2_boot")
        assumeTrue(pre.hasSram, "sram_chapter2_boot has no SRAM dumps")
        val rom = RomView.ofRom(romFile.readBytes())
        val image = pre.sram("s00001")
        val sram = CartSram().also { it.copyIn(image) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        assertTrue(lib.isValidSuspendSave(L.SAVE_ID_SUSPEND), "chapter2.sav has a suspend save")
        val srcEntry = lib.getSaveReadAddr(L.SAVE_ID_SUSPEND + lib.suspendSaveIdOffset)

        val playSt = PlaySt().also { it.decode(ByteArray(PlaySt.SIZE) { 0x5A }, 0) }
        val rng = RngService().also { it.primary.load(0x1111, 0x2222, 0x3333) }
        val units = UnitTable(rom)
        units.bytes.fill(0x5A)
        val flags = Fe8EventFlags.create()
        flags.copyIn(ByteArray(flags.size) { 0x5A })
        val clock = GameClock().also { it.set(12345L) }
        val eventState = EventState().also { it.setEventSlotCounter(0x5A5A) }
        val ram = SuspendRam(
            playSt, BmSt(), rng, units, flags, clock, eventState,
            actionData = ByteArray(S.ACTION_SIZE) { 0x5A },
            traps = ByteArray(S.TRAP_MAX_COUNT * S.TRAP_SIZE) { 0x5A },
            convoy = ByteArray(SuspendRam.CONVOY_BYTES) { 0x5A },
        )
        lib.pidStatsData.fill(0x5A)
        lib.chapterStatsData.fill(0x5A)
        lib.setBonusContentClaimFlags(0x5A5A5A5A)
        ram.dungeonState.fill(0x5A)
        return ResumeFixture(
            ram, BmSuspendSave(lib, rom), sram, lib, image, srcEntry,
            r.region("r01142", "ewram"), r.region("r01142", "iwram"),
        )
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val UNITS = 0x0202BE4C
        const val GACTION = 0x0203A958
        const val GTRAPS = 0x0203A614
        const val GCONVOY = 0x0203A81C
        const val GPIDSTATS = 0x0203E894
        const val GCHAPSTATS = 0x0203ECF4
        const val GBONUS = 0x0203EDB4
        const val GGAMECLOCK = 0x03000014
        const val GFLAGS = 0x03005250
        const val GEVENTSLOT = 0x03000568
        const val GDUNGEON = 0x030017A0
    }
}

/** Everything the tests above need. */
private class ResumeFixture(
    val ram: SuspendRam,
    val suspend: BmSuspendSave,
    val sram: CartSram,
    val lib: BmSaveLib,
    val sramImage: ByteArray,
    val srcEntry: Int,
    val after: ByteArray,
    val iwAfter: ByteArray,
)

private fun rdU16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun rdU32(b: ByteArray, o: Int): Int = rdU16(b, o) or (rdU16(b, o + 2) shl 16)
