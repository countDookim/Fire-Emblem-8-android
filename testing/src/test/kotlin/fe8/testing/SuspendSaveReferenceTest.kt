package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmDifficulty
import fe8.game.bm.BmSt
import fe8.game.event.EventState
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveGmap
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.Fe8SuspendLayout as S
import fe8.game.save.SuspendRam
import fe8.game.ui.MenuOverrides
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `WriteSuspendSave` and `EncodeSuspendSavePackedUnit` against `ram_trace_first_phase`.
 *
 * The first suspend of a new game goes to block info 4 (SRAM 0x204C). The SRAM dumps show it
 * written in f19313..f19314 (body) and f19316 (block info 4 and `last_suspend_slot`), and
 * unchanged from s19316 on, so s19316 (= s19317) is the finished state; s19312 is the state
 * before. RAM r19315 is the dump the task names: `gPlaySt` (incl. `time_saved`) is identical in
 * r19313..r19315, and the other inputs do not change between them (the comparison proves it).
 */
class SuspendSaveReferenceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_first_phase"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("EncodeSuspendSavePackedUnit")
    fun `packed unit records equal suspend entry 4 of the reference`() {
        val f = fixture()
        val want = f.want
        val entry = L.SUSPEND1_OFFSET
        val buf = ByteArray(S.PACKED_UNIT_SIZE)
        var encoded = 0
        fun check(name: String, unit: Int, saved: Int) {
            val at = entry + saved
            // An empty slot only gets pid = 0; its other bytes are stale buffer bytes.
            val b = f.ram.units.bytes
            val empty = (0 until 4).all { b[unit + it].toInt() == 0 }
            buf.fill(0)
            f.suspend.encodeSuspendSavePackedUnit(f.ram.units, unit, buf, 0)
            if (empty) {
                assertEquals(0, want[at].toInt(), "$name pid")
                assertEquals(0, buf[0].toInt(), "$name pid")
                return
            }
            encoded++
            for (i in 0 until S.PACKED_UNIT_SIZE) {
                assertEquals(want[at + i], buf[i], "$name byte 0x${i.toString(16)}")
            }
        }
        for (i in 0 until S.UNIT_SAVE_AMOUNT_BLUE) {
            check("blue $i", i * UnitTable.UNIT_SIZE, S.BLUE_UNITS + i * S.PACKED_UNIT_SIZE)
        }
        val redBase = UnitTable.BLUE_COUNT * UnitTable.UNIT_SIZE
        for (i in 0 until S.UNIT_SAVE_AMOUNT_RED) {
            check("red $i", redBase + i * UnitTable.UNIT_SIZE, S.RED_UNITS + i * S.PACKED_UNIT_SIZE)
        }
        val greenBase = redBase + UnitTable.RED_COUNT * UnitTable.UNIT_SIZE
        for (i in 0 until S.UNIT_SAVE_AMOUNT_GREEN) {
            check(
                "green $i", greenBase + i * UnitTable.UNIT_SIZE,
                S.GREEN_UNITS + i * S.PACKED_UNIT_SIZE,
            )
        }
        assertTrue(encoded == 5, "chapter 0 has units: $encoded")
    }

    @Test
    // Not @VerifiesPort: WriteTraps and WriteChapterStats (the reference traps and chapter stats
    // are all zero: target proven, not data), WriteGMMonsterRnState (not in this save),
    // WriteWorldMapNodes / WriteWorldMapPaths / WriteWorldMapSkirmishes (all zero in
    // ram_trace_first_phase; non-zero nodes/paths are verified in BmSaveGmapReferenceTest).
    // Coverage limits: the encode path is checked on non-zero fields only (conBonus,
    // supportBits and the status bits are zero for all 5 units), and for
    // WriteSwappedSuspendSaveId only the 0 -> 1 branch (last_suspend_slot starts 0).
    @VerifiesPort(
        "WriteSuspendSave", "WriteSwappedSuspendSaveId", "WriteGlobalSaveInfoNoChecksum",
        "WritePidStats", "WriteChapterFlags",
        "WritePermanentFlags", "WriteWorldMapStuff", "WriteWorldMapUnits",
    )
    fun `suspend save from r19315 reproduces the sram of s19316`() {
        val f = fixture()
        // Make the vacuous parts observable: r19312..r19315 have zeros in actionData[0..5], so
        // the RN store is only checked if the buffer starts zeroed; time_saved and just_resumed
        // are what the call must change, so they start different from the dump.
        f.ram.actionData.fill(0, 0, 6)
        f.ram.playSt.timeSaved = 0
        f.ram.bmSt.justResumed = 1
        f.suspend.writeSuspendSave(f.ram, L.SAVE_ID_SUSPEND)
        val got = ByteArray(L.SRAM_SIZE).also { f.sram.copyOut(it) }
        val want = f.want
        val entry = L.SUSPEND1_OFFSET
        // Masked, each with its reason:
        // 0x73A0..3: SramInit's probe, re-run by this test on the loaded image; the dump has the
        // bytes as they were before.
        for (i in 0 until 4) got[RESERVED + i] = want[RESERVED + i]
        // Last supply byte: never written by WriteSupplyItems' packing, so uninitialised stack in
        // the C `buf` (0x03 in the dump). The block checksum covers it, so it is patched in and
        // the checksum32 recomputed by the library from the patched image and compared (the
        // checksum is checked for consistency with the dump's byte, not independently).
        got[entry + S.SUPPLY + SUPPLY_SIZE - 1] = want[entry + S.SUPPLY + SUPPLY_SIZE - 1]
        // wmStuff byte 0x1C: WriteWorldMapStuff only writes bits 0..3 (unk_1c_1/2/3); bits 4..7
        // come from C's uninitialised stack `struct GMapSaveInfo info`. Verify the low nibble.
        val wm1c = entry + S.WM_STUFF + BmSaveGmap.INFO_BITS_1C
        assertEquals(want[wm1c].toInt() and 0x0F, got[wm1c].toInt() and 0x0F, "wmStuff bits 0x1C")
        got[wm1c] = ((got[wm1c].toInt() and 0x0F) or (want[wm1c].toInt() and 0xF0)).toByte()
        val check = BmSaveLib(CartSram().also { it.copyIn(got) })
        val sum = check.sramChecksum32(entry, L.SUSPEND_SIZE)
        for (i in 0 until 4) {
            got[L.blockInfoOffset(L.SAVE_ID_SUSPEND_ALT) + L.INFO_CHECKSUM32 + i] =
                (sum shr (8 * i)).toByte()
        }
        val diffs = (0 until L.SRAM_SIZE).filter { got[it] != want[it] }
        assertTrue(diffs.isEmpty(), "diff at " + diffs.take(40).joinToString { "0x%X".format(it) })
        assertEquals(1, got[L.HEADER_LAST_SUSPEND_SLOT].toInt())
        assertEquals(0, f.ram.bmSt.justResumed)
        assertEquals(f.ram.clock.get().toInt(), f.ram.playSt.timeSaved)
        assertEquals(entry + S.PID_STATS, f.lib.pidStatsSaveLoc)
    }

    // Not @VerifiesPort: SaveDungeonRecords, SaveDungeonState, and GetForceDisabledMenuItems are
    // all-zero in every capture, so non-zero records, the post-copy unk_0B_4 = type update, and
    // the MenuAlwaysNotShown filter branch are covered by unit checks below while reference
    // buffers verify the exact 24 / 12 / 16-byte target writes.
    @Test
    fun `dungeon save helpers and getForceDisabledMenuItems match reference captures`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val clear = ref.run("ram_trace_prologue_clear")
        val r4272 = clear.region("r04272", "ewram")
        val r4273 = clear.region("r04273", "ewram")
        val gbBase = GGENERIC_BUF - EWRAM
        val menuStart = gbBase + S.MENU_OVERRIDE
        val menuEnd = menuStart + S.MENU_OVERRIDE_MAX
        assertNotEquals(
            r4272.copyOfRange(menuStart, menuEnd).toList(),
            r4273.copyOfRange(menuStart, menuEnd).toList(),
        )
        assertNotEquals(
            r4272.copyOfRange(gbBase + S.DUNGEON, gbBase + S.DUNGEON + S.DUNGEON_SIZE).toList(),
            r4273.copyOfRange(gbBase + S.DUNGEON, gbBase + S.DUNGEON + S.DUNGEON_SIZE).toList(),
        )
        for (step in listOf("f03850", "f03860", "r04273")) {
            val iwram = clear.region(step, "iwram")
            val ewram = clear.region(step, "ewram")
            assertDungeonAndMenuMatch(
                iwram = iwram,
                wantRecords = ewram.copyOfRange(
                    gbBase + S.WM_MONSTER_UNIT,
                    gbBase + S.WM_MONSTER_UNIT + BmDifficulty.DUNGEON_RECORDS_SIZE,
                ),
                wantDungeon = ewram.copyOfRange(
                    gbBase + S.DUNGEON,
                    gbBase + S.DUNGEON + BmDifficulty.DUNGEON_SIZE,
                ),
                wantMenuOverride = ewram.copyOfRange(
                    gbBase + S.MENU_OVERRIDE,
                    gbBase + S.MENU_OVERRIDE + S.MENU_OVERRIDE_MAX,
                ),
                label = "ram_trace_prologue_clear/$step",
            )
        }

        val ch2Iwram = ref.run("ram_trace_ch2_scroll").region("r01142", "iwram")
        val ch2Sav = ref.run("sram_chapter2_boot").sram("s00001")
        assertDungeonAndMenuMatch(
            iwram = ch2Iwram,
            wantRecords = ch2Sav.copyOfRange(
                L.SUSPEND0_OFFSET + S.WM_MONSTER_UNIT,
                L.SUSPEND0_OFFSET + S.WM_MONSTER_UNIT + BmDifficulty.DUNGEON_RECORDS_SIZE,
            ),
            wantDungeon = ch2Sav.copyOfRange(
                L.SUSPEND0_OFFSET + S.DUNGEON,
                L.SUSPEND0_OFFSET + S.DUNGEON + BmDifficulty.DUNGEON_SIZE,
            ),
            wantMenuOverride = ch2Sav.copyOfRange(
                L.SUSPEND0_OFFSET + S.MENU_OVERRIDE,
                L.SUSPEND0_OFFSET + S.MENU_OVERRIDE + S.MENU_OVERRIDE_MAX,
            ),
            label = "chapter2.sav SUS0",
        )

        val ch6Iwram = ref.run("ram_trace_ch6_start").region("r01142", "iwram")
        val ch6Sav = ref.run("sram_chapter6_boot").sram("s00001")
        val gameRecordsOut = ByteArray(BmDifficulty.DUNGEON_RECORDS_SIZE) { 0x5A }
        val ch6Dng = ch6Iwram.copyOfRange(
            GDUNGEON - IWRAM, GDUNGEON - IWRAM + BmDifficulty.DUNGEON_STATE_SIZE,
        )
        BmDifficulty.saveDungeonRecords(ch6Dng, BmDifficulty.DUNGEON_RECORDS_OFFSET, gameRecordsOut)
        assertArrayEquals(
            ch6Sav.copyOfRange(
                L.GAME1_OFFSET + GAME_DUNGEONS,
                L.GAME1_OFFSET + GAME_DUNGEONS + BmDifficulty.DUNGEON_RECORDS_SIZE,
            ),
            gameRecordsOut,
            "chapter6.sav GAME1 dungeon records",
        )
    }

    @Test
    fun `saveDungeonState preserves saved unk_0B_4 and getForceDisabledMenuItems filters`() {
        val state = ByteArray(BmDifficulty.DUNGEON_STATE_SIZE)
        for (i in 0 until BmDifficulty.DUNGEON_SIZE) state[i] = (i + 1).toByte()
        state[BmDifficulty.DUNGEON_UNK_0B_OFFSET] = 0x25.toByte()
        state[BmDifficulty.DUNGEON_TYPE_OFFSET] = 1

        val saved = ByteArray(BmDifficulty.DUNGEON_SIZE) { 0x5A }
        BmDifficulty.saveDungeonState(state, saved)
        assertEquals(0x25, saved[BmDifficulty.DUNGEON_UNK_0B_OFFSET].toInt() and 0xFF)
        assertEquals(0x15, state[BmDifficulty.DUNGEON_UNK_0B_OFFSET].toInt() and 0xFF)

        val overrides = MenuOverrides()
        overrides.setOverride(
            0, 0x11, MenuOverrides.MENU_OVERRIDE_ISAVAILABLE, MenuOverrides.MENU_ALWAYS_NOT_SHOWN,
        )
        overrides.setOverride(1, 0x22, MenuOverrides.MENU_OVERRIDE_ONSELECT, 0x0804F455)
        overrides.setOverride(
            2, 0x33, MenuOverrides.MENU_OVERRIDE_NONE, MenuOverrides.MENU_ALWAYS_NOT_SHOWN,
        )
        val out = ByteArray(MenuOverrides.MENU_OVERRIDE_MAX) { 0x5A }
        overrides.getForceDisabledMenuItems(out)
        assertEquals(0x11, out[0].toInt() and 0xFF)
        for (i in 1 until MenuOverrides.MENU_OVERRIDE_MAX) {
            assertEquals(MenuOverrides.MENU_ITEM_NONE, out[i].toInt() and 0xFF, "slot $i")
        }
    }

    private fun assertDungeonAndMenuMatch(
        iwram: ByteArray,
        wantRecords: ByteArray,
        wantDungeon: ByteArray,
        wantMenuOverride: ByteArray,
        label: String,
    ) {
        val dungeonState = iwram.copyOfRange(
            GDUNGEON - IWRAM, GDUNGEON - IWRAM + BmDifficulty.DUNGEON_STATE_SIZE,
        )
        val recordsOut = ByteArray(BmDifficulty.DUNGEON_RECORDS_SIZE) { 0x5A }
        BmDifficulty.saveDungeonRecords(
            dungeonState, BmDifficulty.DUNGEON_RECORDS_OFFSET, recordsOut,
        )
        assertArrayEquals(wantRecords, recordsOut, "$label dungeon records")

        val stateOut = ByteArray(BmDifficulty.DUNGEON_SIZE) { 0x5A }
        BmDifficulty.saveDungeonState(dungeonState, stateOut)
        assertArrayEquals(wantDungeon, stateOut, "$label dungeon current")

        val overrides = loadMenuOverrides(iwram)
        val menuOut = ByteArray(MenuOverrides.MENU_OVERRIDE_MAX) { 0x5A }
        overrides.getForceDisabledMenuItems(menuOut)
        assertArrayEquals(wantMenuOverride, menuOut, "$label menuOverride")
    }

    private fun loadMenuOverrides(iwram: ByteArray): MenuOverrides {
        val overrides = MenuOverrides()
        for (i in 0 until MenuOverrides.MENU_OVERRIDE_MAX) {
            val base = SMENUOVERRIDES - IWRAM + i * 8
            val cmdId = u16(iwram, base)
            val kind = u16(iwram, base + 2)
            val func = u16(iwram, base + 4) or (u16(iwram, base + 6) shl 16)
            overrides.setOverride(i, cmdId, kind, func)
        }
        return overrides
    }

    private fun fixture(): Fixture {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val r = ref.run(run)
        assumeTrue(r.hasSram, "$run has no SRAM dumps")
        val rom = RomView.ofRom(romFile.readBytes())
        val sram = CartSram().also { it.copyIn(r.sram("s19312")) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        val ewram = r.region("r19315", "ewram")
        val iwram = r.region("r19315", "iwram")
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(ewram, GBMST - EWRAM) }
        val rng = RngService()
        rng.primary.load(u16(iwram, 0), u16(iwram, 2), u16(iwram, 4))
        val units = UnitTable(rom).also { it.loadFrom(ewram) }
        val flags = Fe8EventFlags.create().also { it.copyIn(iwram, GFLAGS - IWRAM) }
        val clock = GameClock()
        val eventState = EventState()
        val gmData = GmData(iwram.copyOf(0x8000), rng)
        val menuOverrides = loadMenuOverrides(iwram)
        val ram = SuspendRam(
            playSt, bmSt, rng, units, flags, clock, eventState,
            actionData = ewram.copyOfRange(GACTION - EWRAM, GACTION - EWRAM + S.ACTION_SIZE),
            traps = ewram.copyOfRange(
                GTRAPS - EWRAM, GTRAPS - EWRAM + S.TRAP_MAX_COUNT * S.TRAP_SIZE,
            ),
            convoy = ewram.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + SuspendRam.CONVOY_BYTES),
            gmData = gmData,
            menuOverrides = menuOverrides,
        )
        // GetGameClock at the call: r19315's clock is later; gPlaySt.time_saved is what the call
        // stored (r19313..r19315 agree on it).
        clock.set(playSt.timeSaved.toLong() and 0xFFFFFFFFL)
        val slotAt = GEVENTSLOT - IWRAM
        eventState.setEventSlotCounter(u16(iwram, slotAt) or (u16(iwram, slotAt + 2) shl 16))
        iwram.copyInto(
            ram.dungeonState, 0, GDUNGEON - IWRAM, GDUNGEON - IWRAM + S.DUNGEON_STATE_SIZE,
        )
        ewram.copyInto(
            lib.pidStatsData, 0, GPIDSTATS - EWRAM, GPIDSTATS - EWRAM + lib.pidStatsData.size,
        )
        ewram.copyInto(
            lib.chapterStatsData, 0, GCHAPSTATS - EWRAM,
            GCHAPSTATS - EWRAM + lib.chapterStatsData.size,
        )
        val want = r.sram("s19316")
        val suspend = BmSuspendSave(lib, rom)
        // Inputs the port cannot derive (see the class comment):
        // 1. gGenericBuffer is stale before the unit passes and the C code leaves the records of
        //    empty units alone. Positions 0..50 hold what the blue pass leaves (the dump's blue
        //    records; the red pass leaves its empty slots equal to them, which the comparison
        //    checks), positions 51..59 what the red region holds.
        val entry = L.SUSPEND1_OFFSET
        for (k in 0 until S.UNIT_SAVE_AMOUNT_BLUE) {
            want.copyInto(
                suspend.genericBuffer, k * S.PACKED_UNIT_SIZE,
                entry + S.BLUE_UNITS + k * S.PACKED_UNIT_SIZE,
                entry + S.BLUE_UNITS + (k + 1) * S.PACKED_UNIT_SIZE,
            )
        }
        for (k in S.UNIT_SAVE_AMOUNT_BLUE until S.UNIT_SAVE_AMOUNT_RED + S.UNIT_SAVE_AMOUNT_GREEN) {
            want.copyInto(
                suspend.genericBuffer, k * S.PACKED_UNIT_SIZE,
                entry + S.RED_UNITS + k * S.PACKED_UNIT_SIZE,
                entry + S.RED_UNITS + (k + 1) * S.PACKED_UNIT_SIZE,
            )
        }
        return Fixture(ram, suspend, sram, want, lib)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val GBMST = 0x0202BCB0
        const val GACTION = 0x0203A958
        const val GTRAPS = 0x0203A614
        const val GCONVOY = 0x0203A81C
        const val GPIDSTATS = 0x0203E894
        const val GCHAPSTATS = 0x0203ECF4
        const val GFLAGS = 0x03005250
        const val GEVENTSLOT = 0x03000568
        const val GDUNGEON = 0x030017A0
        const val SMENUOVERRIDES = 0x03001870
        const val GGENERIC_BUF = 0x02020188
        const val GAME_DUNGEONS = 0xDB0
        const val RESERVED = 0x73A0
        const val SUPPLY_SIZE = 0xB0
    }
}

/** Everything the tests above need, built from the reference dumps. */
private class Fixture(
    val ram: SuspendRam,
    val suspend: BmSuspendSave,
    val sram: CartSram,
    val want: ByteArray,
    val lib: BmSaveLib,
)

private fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
