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
import fe8.game.bm.MuUnit
import fe8.game.bm.PlaySt
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-6l-e: `Event2F_MoveUnit` / `Event30_ENUN` per frame and the real `KILL` death-fade path
 * against `ram_trace_prologue_end` (`PC-cap1`).
 *
 * W3a `f15049..15084` (Breguet MOVE then ENUN) and W4 `f16019..16153` (seven MOVEs, ENUN): every
 * completed main-loop pass runs Tree 3 then Tree 5 once; the red-unit watch (x, y, `US_HIDDEN`
 * of all seven red units), live `ProcScr_MuCtrl` / `ProcScr_Mu` fields and the engine cursor are
 * compared every pass; `gBmMapUnit` / `gBmMapOther` and the unit records of the moving units are
 * compared at every frame that has an ewram dump (the capture has no per-frame map watch).
 *
 * W3b `f15525..15558`: `Event34` `KILL` with the real `StartMu`, `MU_StartDeathFade`,
 * `StartMuHitFlash` and `MuDeathFade_OnLoop` (only the palette, blend and sound hooks are
 * stand-ins), then `DISA_IF` once the fade proc is gone. Per pass: `ProcScr_Mu` (state, Q4
 * position, jid, facing), `ProcScr_MuDeathFade` (script position, `timeLeft`),
 * `ProcScr_MuHitFlash` (script position, sleep timer) and the engine cursor; at dumps the unit
 * records and maps.
 *
 * VBlank frames with `gBmSt.mainLoopEnded == 0` are mid-pass interrupts: their pass completes on
 * the next frame with `mainLoopEnded == 1`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1MoveUnitKillDifferentialTest {
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
    @VerifiesPort("Event2F_MoveUnit", "Event30_ENUN")
    fun `W3a Breguet MOVE and ENUN match PC-cap1 per frame f15049 to f15084`() {
        rig().verifyMoveWindow(15048, 15040, 15049, 15084, null, listOf(0x81))
    }

    @Test
    @VerifiesPort("Event2F_MoveUnit", "Event30_ENUN")
    fun `W4 seven MOVE commands and ENUN match PC-cap1 per frame f16019 to f16153`() {
        rig().verifyMoveWindow(16018, 16010, 16019, 16153, 16160, (0x81..0x87).toList())
    }

    @Test
    @VerifiesPort(
        "Event34_MessWithUnitState",
        "MU_StartDeathFade",
        "MuDeathFade_OnLoop",
        "StartMuHitFlash",
    )
    fun `W3b KILL death fade and DISA_IF match PC-cap1 per frame f15526 to f15558`() {
        rig().verifyKillWindow()
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

                override fun startUnitDeathFade(unitId: Int) {
                    val proc = mu.startMu(
                        MuUnit(
                            address = unitAddress(unitId),
                            xPos = table.xPos(unitId),
                            yPos = table.yPos(unitId),
                            classId = table.classId(unitId),
                            state = table.state(unitId),
                            spritePalette = 0,
                        ),
                    )
                    mu.setAutoMuDefaultFacing()
                    mu.muStartDeathFade(proc!!.state as MuProc)
                }

                override fun muDeathFadeExists(): Boolean = mu.muDeathFadeExists()
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
            val core = EventScrCore(
                state, mem, object : EventInput {}, flags, rng, playSt, keys,
            )
            for (code in 0..0xFF) engine.register(code, EventHandler { EVC_STOP_YIELD })
            core.registerAll(engine)
            // The command after DISA_IF (f15558, cursor 0x089EF914, the cursor display) is not
            // under test here: it continues in the capture, so the stand-in continues too.
            engine.register(0x3B, EventHandler { EVC_ADVANCE_CONTINUE })
            moveUnit.registerOn(engine)
            unitState.registerOn(engine)
        }

        private fun unitAddress(id: Int): Int = when {
            id >= 0x81 -> ADDR_RED + (id - 0x81) * UNIT_SIZE
            id >= 0x41 -> ADDR_GREEN + (id - 0x41) * UNIT_SIZE
            else -> error("blue unit")
        }

        /** MOVE / ENUN windows: seed at [seed] from [dump], compare passes [first]..[last]. */
        fun verifyMoveWindow(
            seed: Int,
            dump: Int,
            first: Int,
            last: Int,
            after: Int?,
            redIds: List<Int>,
        ) {
            val evProc = seedWindow(seed, dump)
            assertRedWatch(seed)
            var completed = 0
            for (step in first..last) {
                if (gbmst.u8(step, 0) == 0) continue
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++
                assertMuProcs(step)
                assertRedWatch(step)
                assertRngEquals(step)
                assertEventCursor(step, evProc)
                if (step in dumpByStep) {
                    assertDumpMaps(step)
                    assertDumpUnits(step, redIds)
                }
            }
            assertTrue(completed > 0)
            if (after != null) {
                assertDumpMaps(after)
                assertDumpUnits(after, redIds)
            }
            assertEquals(0, procs.procs.count { it.script === muCtrl.script }, "all walks ended")
            assertEquals(0, procs.procs.count { it.script === mu.script }, "all MUs ended")
        }

        fun verifyKillWindow() {
            val evProc = seedWindow(15525, 15520)
            var sawFade = false
            var completed = 0
            for (step in 15526..15558) {
                if (gbmst.u8(step, 0) == 0) continue
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++
                assertMuProcs(step)
                assertEffectProcs(step)
                if (mu.muDeathFadeExists()) sawFade = true
                assertEventCursor(step, evProc)
                if (step in dumpByStep) {
                    assertDumpMaps(step)
                    assertDumpUnits(step, listOf(0x41, 0x42))
                }
            }
            assertTrue(sawFade, "death fade ran")
            assertTrue(completed > 0)
            // r15560: the unit slot is cleared and the maps are refreshed.
            assertEquals(false, table.isValid(0x42), "slot 0x42 cleared")
            assertDumpMaps(15560)
            assertDumpUnits(15560, listOf(0x41, 0x42))
        }

        private fun seedWindow(step: Int, dumpStep: Int): EventEngineProc {
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
            evProc.pEventCurrent = dumpEv.u32(EV_CURRENT)
            evProc.evStateBits = dumpEv.u16(EV_STATE_BITS)
            evProc.evStallTimer = dumpEv.u16(EV_STALL_TIMER)
            evProc.proc!!.scrCur = 4 // repeat(onUpdateFn)
            return evProc
        }

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
                val host = gotMcs.singleOrNull {
                    (it.state as MuCtrl.MuCtrlProc).muProc?.unitAddress == unitAddr
                }
                assertNotNull(host, "missing MuCtrl for 0x${unitAddr.toString(16)} at f$step")
                val got = host!!.state as MuCtrl.MuCtrlProc
                val ctx = "f$step unit=0x${unitAddr.toString(16)}"
                assertEquals(want.scrCur, host.scrCur, "$ctx scrCur")
                assertEquals(want.u16(MC_TIMER), got.timer, "$ctx timer")
                assertEquals(want.u16(MC_FLAGS), got.flags, "$ctx flags")
                assertEquals(want.u8(MC_X).toByte().toInt(), got.x, "$ctx x")
                assertEquals(want.u8(MC_Y).toByte().toInt(), got.y, "$ctx y")
            }
            for (want in wantMus) {
                val unitAddr = want.u32(MU_UNIT)
                val m = gotMus.map { it.state as MuProc }
                    .singleOrNull { it.unitAddress == unitAddr }
                assertNotNull(m, "missing Mu for 0x${unitAddr.toString(16)} at f$step")
                val ctx = "f$step mu=0x${unitAddr.toString(16)}"
                assertEquals(want.u8(MU_STATE), m!!.state, "$ctx state")
                assertEquals(want.u8(MU_HIDDEN), m.hiddenB, "$ctx hiddenB")
                assertEquals(want.u8(MU_JID), m.jid, "$ctx jid")
                assertEquals(want.u8(MU_FACING).toByte().toInt(), m.facing, "$ctx facing")
                assertEquals(want.u16(MU_MOVE_CLK), m.moveClockQ4, "$ctx moveClockQ4")
                assertEquals(want.u16(MU_XQ4).toShort().toInt(), m.xQ4, "$ctx xQ4")
                assertEquals(want.u16(MU_YQ4).toShort().toInt(), m.yQ4, "$ctx yQ4")
            }
        }

        private fun assertEffectProcs(step: Int) {
            val dump = ProcArrayDump.ofWatch(procsWatch, step, names)
            val fades = dump.findAll(PROC_SCR_MU_DEATH_FADE)
            val myFade = procs.find(mu.deathFadeScript)
            assertEquals(fades.isNotEmpty(), myFade != null, "MuDeathFade exists f$step")
            if (fades.isNotEmpty() && myFade != null) {
                val s = myFade.state as Mu.MuEffectState
                assertEquals(fades.single().scrCur, myFade.scrCur, "fade scrCur f$step")
                assertEquals(fades.single().u16(0x64), s.timeLeft, "fade timeLeft f$step")
                assertEquals(fades.single().u32(0x54) != 0, s.mu != null, "fade mu f$step")
            }
            val flashes = dump.findAll(PROC_SCR_MU_HIT_FLASH)
            val myFlash = procs.find(mu.hitFlashScript)
            assertEquals(flashes.isNotEmpty(), myFlash != null, "MuHitFlash exists f$step")
            if (flashes.isNotEmpty() && myFlash != null) {
                assertEquals(flashes.single().scrCur, myFlash.scrCur, "flash scrCur f$step")
                assertEquals(flashes.single().u8(0x24), myFlash.sleepTime, "flash sleep f$step")
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
                assertEquals(watch[wOff + UNIT_X].toInt(), table.xPos(id), "red[$slot] x f$step")
                assertEquals(watch[wOff + UNIT_Y].toInt(), table.yPos(id), "red[$slot] y f$step")
                assertEquals(
                    u32(watch, wOff + UNIT_STATE) and US_HIDDEN,
                    table.state(id) and US_HIDDEN,
                    "red[$slot] US_HIDDEN f$step",
                )
            }
        }

        private fun assertDumpUnits(step: Int, ids: List<Int>) {
            val ew = run.region(dumpByStep.getValue(step), "ewram")
            for (id in ids) {
                val off = unitAddress(id) - EWRAM_BASE
                val valid = u32(ew, off) != 0
                assertEquals(valid, table.isValid(id), "unit $id valid f$step")
                if (!valid) continue
                assertEquals(ew[off + UNIT_X].toInt(), table.xPos(id), "unit $id x f$step")
                assertEquals(ew[off + UNIT_Y].toInt(), table.yPos(id), "unit $id y f$step")
                assertEquals(
                    u32(ew, off + UNIT_STATE) and (US_HIDDEN or US_DEAD),
                    table.state(id) and (US_HIDDEN or US_DEAD),
                    "unit $id hidden/dead f$step",
                )
            }
        }

        private fun assertDumpMaps(step: Int) {
            val ew = run.region(dumpByStep.getValue(step), "ewram")
            assertMapEquals(ew, ADDR_MAP_UNIT, map.unit, "gBmMapUnit f$step")
            assertMapEquals(ew, ADDR_MAP_OTHER, map.other, "gBmMapOther f$step")
        }

        private fun assertMapEquals(ew: ByteArray, ptrAddr: Int, pool: BmMap.Pool, label: String) {
            val ptr = u32(ew, ptrAddr - EWRAM_BASE)
            for (y in 0 until map.sizeY) {
                val rowOff = u32(ew, ptr + 4 * y - EWRAM_BASE) - EWRAM_BASE
                for (x in 0 until map.sizeX) {
                    assertEquals(ew[rowOff + x].toInt() and 0xFF, pool[y, x], "$label ($x,$y)")
                }
            }
        }

        private fun assertRngEquals(step: Int) {
            for (i in 0 until 3) {
                assertEquals(rngWatch.u16(step, 2 * i), rng.primary.word(i), "gRNState[$i] f$step")
            }
        }

        private fun assertEventCursor(step: Int, evProc: EventEngineProc) {
            val dumpEv = ProcArrayDump.ofWatch(procsWatch, step, names)
                .find("ProcScr_StdEventEngine")!!
            assertEquals(dumpEv.u32(EV_CURRENT), evProc.pEventCurrent, "pEventCurrent f$step")
        }

        private fun copyMapLayer(ew: ByteArray, ptrAddr: Int, pool: BmMap.Pool) {
            val ptr = u32(ew, ptrAddr - EWRAM_BASE)
            for (y in 0 until map.sizeY) {
                val rowOff = u32(ew, ptr + 4 * y - EWRAM_BASE) - EWRAM_BASE
                for (x in 0 until map.sizeX) pool[y, x] = ew[rowOff + x].toInt() and 0xFF
            }
        }

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, off: Int): Int = u16(b, off) or (u16(b, off + 2) shl 16)
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val ADDR_GREEN = 0x0202DDCC
        const val ADDR_RED = 0x0202CFBC
        const val UNIT_SIZE = 0x48
        const val UNIT_STATE = 0x0C
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val PLAY_FLAG_HARD = 1 shl 6

        const val PROC_SCR_MU = 0x089A2C48
        const val PROC_SCR_MUCTRL = 0x089A2DB0
        const val PROC_SCR_MU_DEATH_FADE = 0x089A2C80
        const val PROC_SCR_MU_HIT_FLASH = 0x089A2D98
        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_STALL_TIMER = 0x3E
        const val EV_EXEC_TYPE = 0x41

        const val MC_UNIT = 0x2C
        const val MC_TIMER = 0x3A
        const val MC_FLAGS = 0x3E
        const val MC_X = 0x40
        const val MC_Y = 0x41

        const val MU_UNIT = 0x2C
        const val MU_STATE = 0x3F
        const val MU_HIDDEN = 0x40
        const val MU_JID = 0x41
        const val MU_FACING = 0x42
        const val MU_MOVE_CLK = 0x48
        const val MU_XQ4 = 0x4C
        const val MU_YQ4 = 0x4E
    }
}
