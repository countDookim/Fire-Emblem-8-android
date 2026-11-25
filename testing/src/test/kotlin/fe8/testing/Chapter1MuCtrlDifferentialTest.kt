package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.Mu
import fe8.game.bm.MuCtrl
import fe8.game.bm.MuCtrlHooks
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.Reda
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventMoveUnit
import fe8.game.event.EventMoveUnitHost
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventUnitLoadsHost
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-6l-b: [MuCtrl] (`ProcScr_MuCtrl`) and `IsMuActive` verified against `ram_trace_prologue_end`
 * (`PC-cap1`) across Chapter 1 opening windows W1 (`f10298..f10378`), W2 (`f13048..f13103`), and
 * W4 (`f16018..f16153`).
 *
 * Frame timing: mGBA snapshots at VBlank; frames with `gBmSt.mainLoopEnded == 0` (`10299`,
 * `10300`, `10347`, `10352`, `10358` in W1; `13049` in W2; `16019`, `16020`, `16021`, `16057` in
 * W4) are mid-pass interrupts whose step completes on the following `mainLoopEnded == 1` frame.
 * Every completed main-loop pass runs Tree 3 then Tree 5 once and asserts all live `ProcScr_MuCtrl`
 * and `ProcScr_Mu` fields, unit coordinates and `US_HIDDEN` bits, `gBmMapUnit` / `gBmMapOther`,
 * `gRNState`, and `ProcScr_StdEventEngine` cursors.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1MuCtrlDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    @AfterAll
    fun releaseRig() {
        shared = null
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data, nm file, or ROM not present")
        return r!!
    }

    private fun loadRig(): Rig? {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val romFile = File(assets, "baserom.gba")
        val names = ProcArrayDump.romNames()
        if (!ref.available || !romFile.isFile || names.isEmpty()) return null
        val run = ref.run(RUN)
        if (!run.hasWatch("procs") || !run.hasWatch("red") || !run.hasWatch("rng")) return null
        return Rig(romFile.readBytes(), run, names)
    }

    @Test
    @VerifiesPort(
        "MuCtr_StartDefinedMove",
        "MuCtr_InitDefinedMove",
        "sub_807A0E4",
        "sub_807A194",
        "sub_807A294",
        "sub_807A300",
        "MuCtr_ExecREDA_807A358",
        "MuCtr_OnEnd",
        "IsMuActive",
    )
    fun `W1 red unit REDA walks delays multi-leg and no-op match PC-cap1 f10298 to f10378`() {
        rig().verifyWindow1()
    }

    @Test
    @VerifiesPort(
        "MuCtr_StartDefinedMove",
        "MuCtr_InitDefinedMove",
        "sub_807A0E4",
        "sub_807A194",
        "sub_807A294",
        "sub_807A300",
        "sub_807A324",
        "MuCtr_ExecREDA_807A358",
        "MuCtr_OnEnd",
        "IsMuActive",
    )
    fun `W2 NPC load dependency wait and 3-leg walk match PC-cap1 f13048 to f13103`() {
        rig().verifyWindow2()
    }

    @Test
    @VerifiesPort(
        "MuCtr_InitDefinedMove",
        "sub_807A0E4",
        "sub_807A194",
        "sub_807A294",
        "MuCtr_ExecREDA_807A358",
        "MuCtr_OnEnd",
        "IsMuActive",
    )
    fun `W4 seven enemy MOVE commands throttle and speeds match PC-cap1 f16018 to f16153`() {
        rig().verifyWindow4()
    }

    @Test
    @VerifiesPort(
        "MuCtr_InitDefinedMove",
        "sub_807A644",
        "MuCtr_ExecREDA_807A358",
        "MuCtr_OnEnd",
        "MuCtrExists",
    )
    fun `W5 ally load two blue units path tie-break and settle tiles match PC-cap1 f20248 to f20268`() {
        rig().verifyWindow5()
    }

    private class Rig(
        romBytes: ByteArray,
        private val run: ReferenceData.Run,
        private val names: Map<Int, String>,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val gbmst = run.watch("gbmst")
        private val gplayst = run.watch("gplayst")
        private val rngWatch = run.watch("rng")
        private val procsWatch = run.watch("procs")
        private val redWatch = run.watch("red")
        private val blueWatch by lazy { run.watch("blue") }
        private val evGlobalsWatch = run.watch("evglobals")
        private val dumpByStep = run.dumps.entries.associate { it.value to it.key }

        private val mem = ScriptMemory { addr -> rom.u16(addr) }
        private val procs = ProcEngine()
        private val state = EventState()
        private val flags = Fe8EventFlags.create()
        private val keys = KeyStatus()
        private val bmSt = BmSt()
        private val playSt = PlaySt()
        private val rng = RngService()
        private val table = UnitTable(rom)
        private val map = BmMap(rom, chapters, bmSt)
        private val entityMaps = EntityBmMaps(map, table, playSt)
        private val camMove = CamMove(procs, bmSt)
        private val mu = Mu(
            procs, bmSt, playSt, keys, camMove.script,
            object : MuHooks {
                override fun classAttributes(jid: Int): Int =
                    rom.u32(CLASS_DATA + (jid - 1) * CLASS_DATA_SIZE + 0x28)

                override fun classSlowWalking(jid: Int): Int =
                    rom.u8(CLASS_DATA + (jid - 1) * CLASS_DATA_SIZE + 0x07)
            },
            rom,
        )
        private val loader = UnitLoader(
            table, rom,
            { type -> if (type == 0) playSt.unk1C0 else if (type == 1) playSt.unk1C1 else 0 },
            rng,
            { playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
        ).apply {
            chapterLevelBonus = {
                val levels = rom.u16(chapters.getROMChapterStruct(playSt.chapterIndex) + 0x14)
                val hard = playSt.chapterStateBits and PLAY_FLAG_HARD != 0
                when {
                    playSt.controller == 0 && !hard -> -(levels and 0xF)
                    hard -> (levels ushr 4) and 0xF
                    else -> -((levels ushr 8) and 0xF)
                }
            }
            bmMap = map
            weatherId = { playSt.chapterWeatherId }
        }
        private val muCtrl = MuCtrl(
            procs, mu, table, map, loader, rng, keys,
            object : MuCtrlHooks {
                override fun refreshEntityBmMaps() {
                    entityMaps.refreshEntityBmMaps()
                }
            },
        )
        private val engine = EventEngine(procs, state, mem, object : EventEngineHooks {})
        private val unitState = EventUnitState(
            state, mem, table, loader,
            object : EventUnitStateHooks {
                override fun unitAt(x: Int, y: Int): Int = map.unit[y, x]
                override fun refreshMapAndSprites() {
                    entityMaps.refreshEntityBmMaps()
                }
            },
        )
        private val loads = EventUnitLoads(
            state, mem, rom, table, loader, rng,
            { x, y -> map.terrain[y, x] },
            { x, y -> map.unit[y, x] },
            { map.bmMapFill(map.other, 0) },
            object : EventUnitLoadsHost {
                override fun camMoveExists(): Boolean = procs.find(camMove.script) != null
                override fun ensureCameraOntoPosition(
                    proc: EventEngineProc,
                    x: Int,
                    y: Int,
                ): Boolean = camMove.ensureCameraOntoPosition(proc.proc, x, y)
                override fun canStartMu(): Boolean = mu.configs.any { it.slot == 0 }
            },
        )
        private val moveUnit = EventMoveUnit(
            state, mem, table, map, mu, muCtrl,
            { pid -> unitState.getUnitStructFromEventParameter(pid) },
            object : EventMoveUnitHost {
                override fun camMoveExists(): Boolean = procs.find(camMove.script) != null
                override fun ensureCameraOntoPosition(
                    proc: EventEngineProc,
                    x: Int,
                    y: Int,
                ): Boolean = camMove.ensureCameraOntoPosition(proc.proc, x, y)
                override fun refreshEntityBmMaps() {
                    entityMaps.refreshEntityBmMaps()
                }
            },
        )

        init {
            loader.startDefinedMove = { id, redas, count, flags ->
                val list = List(count) {
                    Reda.fromWords(rom.u32(redas + 8 * it), rom.u32(redas + 8 * it + 4))
                }
                muCtrl.startDefinedMove(id, list, count, flags)
            }
            val core = EventScrCore(
                state, mem, object : EventInput {}, flags, rng, playSt, keys,
            )
            for (code in 0..0xFF) engine.register(code, EventHandler { EVC_STOP_YIELD })
            core.registerAll(engine)
            // Cursor display command before STAL in W2 advances without starting a cursor proc.
            engine.register(0x3B, EventHandler { EVC_ADVANCE_CONTINUE })
            loads.registerOn(engine)
            moveUnit.registerOn(engine)
            unitState.registerOn(engine)
        }

        fun verifyWindow1() {
            val evProc = seedWindow(10298, 10298, null)
            assertRedWatch(10298)
            assertDumpMaps(10298)
            assertMuProcs(10298)

            // Key frame milestones in W1:
            // f10301: r0 walking, r1/r2 delay 15, r3 delay 31
            // f10316: r1/r2 start leg 0 (r2 to (3,8))
            // f10339: r2 finishes leg 0 at (3,8) and starts leg 1 to (3,6)
            // f10346: r0 ends -> r0 unhidden at (2,5)
            // f10348: r4 starts
            // f10351: r1 ends -> r1 unhidden at (1,6)
            // f10353: r5 starts
            // f10357: r3 ends -> r3 unhidden at (2,7)
            // f10359: r6 same-tile no-op completes (after f10358 overrun)
            // f10362: r4 ends
            // f10365: r2 ends -> r2 unhidden at (3,6)
            // f10377: r5 ends -> last walk done
            // f10378: ENUN advances to 0x089EF858
            var completed = 0
            for (step in 10299..10378) {
                if (gbmst.u8(step, 0) == 0) continue
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuProcs(step)
                assertRedWatch(step)
                assertRngEquals(step)
                assertEventCursor(step, evProc)
                if (step in dumpByStep) assertDumpMaps(step)
            }
            assertEquals(75, completed, "W1 completed passes")

            // Explicit lifecycle milestones across W1.
            assertEquals(0, procs.procs.count { it.script === muCtrl.script }, "W1 all ended")
            assertEquals(0, procs.procs.count { it.script === mu.script }, "W1 all MUs ended")
            for (slot in 0 until 7) {
                assertEquals(0, table.state(0x81 + slot) and US_HIDDEN, "W1 red[$slot] visible")
            }
        }

        fun verifyWindow2() {
            // Seed from r13040 (units, maps, RNG, playSt, evglobals unchanged 13040..13048);
            // step 13049 enters Event2C_LoadUnits at 0x089EF880 after TextShowWithFadeIn RETURN.
            val evProc = seedWindow(13048, 13040, 0x089EF880)
            assertMuProcs(13048)

            var green1WaitFrames = 0
            var completed = 0
            for (step in 13049..13103) {
                if (gbmst.u8(step, 0) == 0) continue
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuProcs(step)
                assertRngEquals(step)
                assertEventCursor(step, evProc)

                val g0 = findController(ADDR_GREEN)
                val g1 = findController(ADDR_GREEN + UNIT_SIZE)
                if (step in 13050..13058) {
                    assertNotNull(g0, "green[0] active at f$step")
                    assertEquals(0, g0!!.timer, "green[0] on leg 0 at f$step")
                    assertNotNull(g1, "green[1] waiting on green[0] at f$step")
                    assertEquals(
                        3,
                        procOf(g1!!).scrCur,
                        "green[1] waiting in waitToStartLeg f$step",
                    )
                    assertEquals(
                        0,
                        g1.delayFrames,
                        "green[1] delayFrames paused while waiting f$step",
                    )
                    assertEquals(
                        US_HIDDEN,
                        table.state(0x42) and US_HIDDEN,
                        "green[1] hidden f$step",
                    )
                    green1WaitFrames++
                }
                if (step == 13059) {
                    assertNotNull(g0, "green[0] active at leg transition f13059")
                    assertEquals(1, g0!!.timer, "green[0] transitioned to leg 1 at f13059")
                    assertEquals(
                        2,
                        table.xPos(0x41),
                        "green[0] unit x updated to leg 0 end at f13059",
                    )
                    assertEquals(
                        3,
                        table.yPos(0x41),
                        "green[0] unit y updated to leg 0 end at f13059",
                    )
                    assertNull(g1, "green[1] unblocked and finished same-tile walk at f13059")
                    assertEquals(0, table.state(0x42) and US_HIDDEN, "green[1] unhidden at f13059")
                }
                if (step == 13076) {
                    assertNotNull(g0, "green[0] active at leg 2 transition f13076")
                    assertEquals(2, g0!!.timer, "green[0] transitioned to leg 2 at f13076")
                    assertEquals(
                        0,
                        table.xPos(0x41),
                        "green[0] unit x updated to leg 1 end at f13076",
                    )
                    assertEquals(
                        3,
                        table.yPos(0x41),
                        "green[0] unit y updated to leg 1 end at f13076",
                    )
                }
                if (step == 13101) {
                    assertNull(g0, "green[0] ended at f13101")
                    assertEquals(0, table.state(0x41) and US_HIDDEN, "green[0] unhidden at f13101")
                    assertEquals(
                        0x41,
                        map.unit[0, 0],
                        "green[0] placed on gBmMapUnit(0,0) at f13101",
                    )
                }
                if (step in dumpByStep) {
                    assertDumpMaps(step)
                    assertGreenSlotsInDump(step)
                }
            }
            assertEquals(9, green1WaitFrames, "green[1] waited 9 frames (13050..13058)")
            assertEquals(54, completed, "W2 completed passes")

            // r13110 matches f13103 (13104..13110 is only STAL(60) counting down).
            assertDumpMaps(13110)
            assertGreenSlotsInDump(13110)
        }

        fun verifyWindow4() {
            // Seed from r16010 (units, maps, RNG, playSt, evglobals unchanged 16010..16018);
            // step 16019 enters MOVE(Breguet) at 0x089EF93C after CallOnTutorialMode.
            val evProc = seedWindow(16018, 16010, 0x089EF93C)
            assertMuProcs(16018)
            assertRedWatch(16018)

            var completed = 0
            for (step in 16019..16153) {
                if (gbmst.u8(step, 0) == 0) continue
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuProcs(step)
                assertRedWatch(step)
                assertRngEquals(step)
                assertEventCursor(step, evProc)
                if (step in dumpByStep) assertDumpMaps(step)
            }
            assertEquals(131, completed, "W4 completed passes")

            // r16160 matches f16153 (16154..16160 is only STAL2(60) counting down).
            assertDumpMaps(16160)
            assertEquals(0, procs.procs.count { it.script === muCtrl.script }, "W4 all ended")
            assertEquals(0, procs.procs.count { it.script === mu.script }, "W4 all MUs ended")
        }

        fun verifyWindow5() {
            // Seed from r20240 (units, maps, RNG, playSt, evglobals unchanged 20240..20248);
            // step 20249 enters Event2C_LoadUnits for the two blue allies.
            val evProc = seedWindow(20248, 20240, null)
            assertMuProcs(20248)
            assertBlueWatch(20248)

            var completed = 0
            var drewRng = false
            for (step in 20249..20268) {
                if (gbmst.u8(step, 0) == 0) continue
                val before = IntArray(3) { rng.primary.word(it) }
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuProcs(step)
                assertBlueWatch(step)
                assertRngEquals(step)
                assertEventCursor(step, evProc)
                if (!before.contentEquals(IntArray(3) { rng.primary.word(it) })) {
                    assertEquals(20250, step, "W5 RNG draw frame")
                    drewRng = true
                }
                if (step in dumpByStep) assertDumpMaps(step)
            }
            assertTrue(drewRng, "W5 BestMove draw happened")
            assertEquals(0, procs.procs.count { it.script === muCtrl.script }, "W5 all ended")
            assertEquals(0, procs.procs.count { it.script === mu.script }, "W5 all MUs ended")
            assertEquals(13, table.xPos(1), "W5 Seth x")
            assertEquals(8, table.yPos(1), "W5 Seth y")
            assertEquals(12, table.xPos(2), "W5 Eirika x")
            assertEquals(9, table.yPos(2), "W5 Eirika y")
            assertDumpMaps(20270)
        }

        private fun assertBlueWatch(step: Int) {
            val watch = blueWatch.step(step)
            for (slot in 0 until 2) {
                val id = 1 + slot
                val wOff = slot * UNIT_SIZE
                val valid = u32(watch, wOff) != 0
                assertEquals(valid, table.isValid(id), "blue[$slot] valid f$step")
                if (!valid) continue
                assertEquals(s8(watch, wOff + UNIT_X), table.xPos(id), "blue[$slot] xPos f$step")
                assertEquals(s8(watch, wOff + UNIT_Y), table.yPos(id), "blue[$slot] yPos f$step")
                assertEquals(
                    u32(watch, wOff + UNIT_STATE) and US_HIDDEN,
                    table.state(id) and US_HIDDEN,
                    "blue[$slot] US_HIDDEN f$step",
                )
            }
        }

        private fun seedWindow(step: Int, dumpStep: Int, cursorOverride: Int?): EventEngineProc {
            val ew = run.region(dumpByStep.getValue(dumpStep), "ewram")
            procs.init()
            mu.initMus()
            bmSt.decode(gbmst.step(step), 0)
            playSt.decode(gplayst.step(step), 0)
            rng.primary.load(
                rngWatch.u16(step, 0),
                rngWatch.u16(step, 2),
                rngWatch.u16(step, 4),
            )
            state.load(evGlobalsWatch.step(step), 0)
            table.loadFrom(ew)
            map.initChapterMap(playSt.chapterIndex)
            copyMapLayer(ew, ADDR_MAP_UNIT, map.unit)
            copyMapLayer(ew, ADDR_MAP_OTHER, map.other)
            entityMaps.traps = EntityBmMaps.Traps.NONE

            val dumpEv = ProcArrayDump.ofWatch(procsWatch, step, names)
                .find("ProcScr_StdEventEngine")!!
            val evProc = engine.create(dumpEv.u32(EV_START), dumpEv.u8(EV_EXEC_TYPE))
            evProc.pEventIdk = dumpEv.u32(EV_IDK)
            evProc.pEventCurrent = cursorOverride ?: dumpEv.u32(EV_CURRENT)
            evProc.evStateBits = dumpEv.u16(EV_STATE_BITS)
            evProc.evStallTimer = dumpEv.u16(EV_STALL_TIMER)
            evProc.proc!!.scrCur = 4 // repeat(onUpdateFn)
            return evProc
        }

        private fun procOf(ctrl: MuCtrl.MuCtrlProc) =
            procs.procs.first { it.script === muCtrl.script && it.state === ctrl }

        private fun findController(unitAddr: Int): MuCtrl.MuCtrlProc? =
            procs.procs.filter { it.script === muCtrl.script }
                .map { it.state as MuCtrl.MuCtrlProc }
                .singleOrNull { it.muProc?.unitAddress == unitAddr }

        private fun assertMuProcs(step: Int) {
            val dump = ProcArrayDump.ofWatch(procsWatch, step, names)
            val wantMcs = dump.findAll(PROC_SCR_MUCTRL)
            val wantMus = dump.findAll(PROC_SCR_MU)
            val gotMcs = procs.procs.filter { it.script === muCtrl.script }
            val gotMus = procs.procs.filter { it.script === mu.script }

            assertEquals(wantMcs.size, gotMcs.size, "MuCtrl count f$step")
            assertEquals(wantMus.size, gotMus.size, "Mu count f$step")

            for (want in wantMcs) {
                val unitAddr = want.u32(MC_UNIT)
                val hostProc = gotMcs.singleOrNull {
                    (it.state as MuCtrl.MuCtrlProc).muProc?.unitAddress == unitAddr
                }
                assertNotNull(hostProc, "missing MuCtrl for 0x${unitAddr.toString(16)} at f$step")
                val got = hostProc!!.state as MuCtrl.MuCtrlProc
                val ctx = "f$step unit=0x${unitAddr.toString(16)}"

                assertEquals(want.scrCur, hostProc.scrCur, "$ctx scrCur")
                assertEquals(want.u16(MC_REDA_CNT), got.redaCount, "$ctx redaCount")
                assertEquals(want.u16(MC_TIMER), got.timer, "$ctx timer")
                assertEquals(want.s16(MC_DELAY), got.delayFrames, "$ctx delayFrames")
                assertEquals(want.u16(MC_FLAGS), got.flags, "$ctx flags")
                assertEquals(want.s8(MC_X), got.x, "$ctx x")
                assertEquals(want.s8(MC_Y), got.y, "$ctx y")
                assertEquals(want.s8(MC_UNK42), got.unk42, "$ctx unk42")
                assertEquals(want.s8(MC_UNK43), got.unk43, "$ctx unk43")

                val wantMu = wantMus.single { it.address == want.u32(MC_MUPROC) }
                val m = got.muProc!!
                assertEquals(wantMu.u32(MU_UNIT), m.unitAddress, "$ctx mu.unitAddress")
                assertEquals(wantMu.u8(MU_CAM) != 0, m.camB, "$ctx mu.camB")
                assertEquals(wantMu.u8(MU_STATE), m.state, "$ctx mu.state")
                assertEquals(wantMu.u8(MU_HIDDEN), m.hiddenB, "$ctx mu.hiddenB")
                assertEquals(wantMu.u8(MU_JID), m.jid, "$ctx mu.jid")
                assertEquals(wantMu.s8(MU_FACING), m.facing, "$ctx mu.facing")
                assertEquals(wantMu.u8(MU_FAST_WALK) != 0, m.fastWalkB, "$ctx mu.fastWalkB")
                assertEquals(wantMu.u16(MU_MOVE_CLK), m.moveClockQ4, "$ctx mu.moveClockQ4")
                assertEquals(wantMu.s16(MU_CONFIG), m.moveConfig, "$ctx mu.moveConfig")
                assertEquals(wantMu.s16(MU_XQ4), m.xQ4, "$ctx mu.xQ4")
                assertEquals(wantMu.s16(MU_YQ4), m.yQ4, "$ctx mu.yQ4")
                assertTrue((m.config?.slot ?: 0) != 0, "$ctx mu.config.slot non-zero")
            }
        }

        private fun assertRedWatch(step: Int) {
            val watch = redWatch.step(step)
            for (slot in 0 until 7) {
                val id = 0x81 + slot
                val wOff = slot * UNIT_SIZE
                val valid = u32(watch, wOff) != 0
                assertEquals(valid, table.isValid(id), "red[$slot] valid f$step")
                if (!valid) continue
                assertEquals(s8(watch, wOff + UNIT_X), table.xPos(id), "red[$slot] xPos f$step")
                assertEquals(s8(watch, wOff + UNIT_Y), table.yPos(id), "red[$slot] yPos f$step")
                assertEquals(
                    u32(watch, wOff + UNIT_STATE) and US_HIDDEN,
                    table.state(id) and US_HIDDEN,
                    "red[$slot] US_HIDDEN f$step",
                )
            }
        }

        private fun assertGreenSlotsInDump(step: Int) {
            val ew = run.region(dumpByStep.getValue(step), "ewram")
            for (slot in 0 until 2) {
                val id = 0x41 + slot
                val ewOff = ADDR_GREEN - EWRAM_BASE + slot * UNIT_SIZE
                val valid = u32(ew, ewOff) != 0
                assertEquals(valid, table.isValid(id), "green[$slot] valid f$step")
                if (!valid) continue
                assertEquals(s8(ew, ewOff + UNIT_X), table.xPos(id), "green[$slot] xPos f$step")
                assertEquals(s8(ew, ewOff + UNIT_Y), table.yPos(id), "green[$slot] yPos f$step")
                assertEquals(
                    u32(ew, ewOff + UNIT_STATE) and US_HIDDEN,
                    table.state(id) and US_HIDDEN,
                    "green[$slot] US_HIDDEN f$step",
                )
            }
        }

        private fun assertDumpMaps(step: Int) {
            val ew = run.region(dumpByStep.getValue(step), "ewram")
            assertMapEquals(ew, ADDR_MAP_UNIT, map.unit, "gBmMapUnit f$step")
            assertMapEquals(ew, ADDR_MAP_OTHER, map.other, "gBmMapOther f$step")
        }

        private fun assertMapEquals(
            ew: ByteArray,
            ptrAddr: Int,
            pool: BmMap.Pool,
            label: String,
        ) {
            val ptr = u32(ew, ptrAddr - EWRAM_BASE)
            for (y in 0 until map.sizeY) {
                val rowOff = u32(ew, ptr + 4 * y - EWRAM_BASE) - EWRAM_BASE
                for (x in 0 until map.sizeX) {
                    val want = ew[rowOff + x].toInt() and 0xFF
                    assertEquals(want, pool[y, x], "$label ($x,$y)")
                }
            }
        }

        private fun assertRngEquals(step: Int) {
            val want = IntArray(3) { rngWatch.u16(step, 2 * it) }
            val got = IntArray(3) { rng.primary.word(it) }
            assertArrayEquals(want, got, "gRNState f$step")
        }

        private fun assertEventCursor(step: Int, evProc: EventEngineProc) {
            val dumpEv = ProcArrayDump.ofWatch(procsWatch, step, names)
                .find("ProcScr_StdEventEngine")!!
            assertEquals(dumpEv.u32(EV_CURRENT), evProc.pEventCurrent, "pEventCurrent f$step")
            assertEquals(dumpEv.u32(EV_CALLBACK) != 0, evProc.callback != null, "pCallback f$step")
        }

        private fun copyMapLayer(ew: ByteArray, ptrAddr: Int, pool: BmMap.Pool) {
            val ptr = u32(ew, ptrAddr - EWRAM_BASE)
            for (y in 0 until map.sizeY) {
                val rowOff = u32(ew, ptr + 4 * y - EWRAM_BASE) - EWRAM_BASE
                for (x in 0 until map.sizeX) {
                    pool[y, x] = ew[rowOff + x].toInt() and 0xFF
                }
            }
        }

        private fun ProcArrayDump.Proc.s8(field: Int): Int = u8(field).toByte().toInt()
        private fun ProcArrayDump.Proc.s16(field: Int): Int = u16(field).toShort().toInt()

        private fun s8(b: ByteArray, off: Int): Int = b[off].toInt()
        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, off: Int): Int =
            u16(b, off) or (u16(b, off + 2) shl 16)
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val ADDR_GREEN = 0x0202DDCC
        const val UNIT_SIZE = 0x48
        const val UNIT_STATE = 0x0C
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val US_HIDDEN = 1 shl 0
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val PLAY_FLAG_HARD = 1 shl 6

        const val PROC_SCR_MU = 0x089A2C48
        const val PROC_SCR_MUCTRL = 0x089A2DB0
        const val EV_CALLBACK = 0x2C
        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_STALL_TIMER = 0x3E
        const val EV_EXEC_TYPE = 0x41

        const val MC_UNIT = 0x2C
        const val MC_MUPROC = 0x30
        const val MC_REDA_CNT = 0x38
        const val MC_TIMER = 0x3A
        const val MC_DELAY = 0x3C
        const val MC_FLAGS = 0x3E
        const val MC_X = 0x40
        const val MC_Y = 0x41
        const val MC_UNK42 = 0x42
        const val MC_UNK43 = 0x43

        const val MU_UNIT = 0x2C
        const val MU_CAM = 0x3E
        const val MU_STATE = 0x3F
        const val MU_HIDDEN = 0x40
        const val MU_JID = 0x41
        const val MU_FACING = 0x42
        const val MU_FAST_WALK = 0x44
        const val MU_MOVE_CLK = 0x48
        const val MU_CONFIG = 0x4A
        const val MU_XQ4 = 0x4C
        const val MU_YQ4 = 0x4E
    }
}
