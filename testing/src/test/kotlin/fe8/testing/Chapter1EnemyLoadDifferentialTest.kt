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
import fe8.game.bm.PlaySt
import fe8.game.bm.Reda
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventMoveUnit
import fe8.game.event.EventMoveUnitHost
import fe8.game.event.EventState
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventUnitLoadsHost
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-6l-a: Chapter 1 W1 enemy load path (`f10298..f10378` in `ram_trace_prologue_end` / `PC-cap1`).
 *
 * Seeds [EventEngine], [EventUnitLoads], [UnitLoader], [UnitTable], [BmMap] (`gBmMapUnit`,
 * `gBmMapOther`), [PlaySt], [RngService], and real [MuCtrl]/[Mu] (throttling at `MU_MAX_COUNT = 4`
 * slots, releasing on `f10346`, `f10351`, `f10357`) from `PC-cap1` `f10298` and runs the real ROM
 * `Event2C_LoadUnits` -> `EventLoadUnitWithMovement` -> `Event30_ENUN` sequence through `f10378`.
 *
 * Stand-in: `Event0E_STAL` is stubbed at `0x089EF858` (the command after `ENUN`) and
 * `EventUnitState` / `UnitSpriteHandles` are omitted (`pMapSpriteHandle` at unit `+0x3C..0x3F` is
 * masked because sprite handles come from `RefreshUnitSprites`). `PC-6l-b` (`MuCtrl` internals) is
 * out of scope of `@VerifiesPort` tags here; real `MuCtrl` + `Mu` runs only to provide authentic
 * MU slot capacity and `gBmMapUnit` / `gBmMapOther` updates.
 *
 * Frame timing: mGBA dumps at VBlank, so frames whose GBA main loop overran VBlank (`mainLoopEnded`
 * `== 0`: `10299`, `10300`, `10347`, `10352`, `10358`) are mid-step snapshots (`10299`/`10300`
 * mid-loop inside `EventLoadUnitWithMovement` before `10301`; `10347`/`10352`/`10358` after tree
 * 3 before tree 5). Every completed main-loop pass (`mainLoopEnded == 1`) and every mid-step
 * overrun point is compared across `10298..10378`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1EnemyLoadDifferentialTest {
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
        assumeTrue(r != null, "reference data or ROM not present")
        return r!!
    }

    private fun loadRig(): Rig? {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val romFile = File(assets, "baserom.gba")
        if (!ref.available || !romFile.isFile) return null
        val run = ref.run(RUN)
        if (!run.hasWatch("procs") || !run.hasWatch("red") || !run.hasWatch("rng")) return null
        return Rig(romFile.readBytes(), run)
    }

    @Test
    @VerifiesPort(
        "EventLoadUnitWithMovement",
        "ModifyMoveUnitFlag",
        "TryPrepareEventUnitMovement",
        "ShouldUNITBeLoaded",
        "IsUnitSlotAvailable",
        "LoadUnit_800F704",
        "sub_800F8A8",
        "ClearUnit",
    )
    // Not @VerifiesPort: GenUnitDefinitionFinalPosition (LoadUnit_800F704 overwrites xPos/yPos
    // immediately after LoadUnit and no Ch1 tile is TERRAIN_ROOF; writing (0, 0) survives).
    fun `Chapter 1 W1 enemy load matches PC-cap1 across f10298 to f10378`() {
        rig().verifyWindow1()
    }

    private class Rig(
        romBytes: ByteArray,
        private val run: ReferenceData.Run,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val gbmst = run.watch("gbmst")
        private val gplayst = run.watch("gplayst")
        private val rngWatch = run.watch("rng")
        private val procsWatch = run.watch("procs")
        private val redWatch = run.watch("red")
        private val names = ProcArrayDump.romNames()

        private val mem = ScriptMemory { addr -> rom.u16(addr) }
        private val procs = ProcEngine()
        private val state = EventState()
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
                override fun canStartMu(): Boolean {
                    val configs = mu.configs
                    for (i in 0 until configs.size) if (configs[i].slot == 0) return true
                    return false
                }
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

        private var onBeforeStartDefinedMove: (() -> Unit)? = null

        init {
            loader.startDefinedMove = { id, redas, count, flags ->
                onBeforeStartDefinedMove?.invoke()
                val list = List(count) {
                    Reda.fromWords(rom.u32(redas + 8 * it), rom.u32(redas + 8 * it + 4))
                }
                muCtrl.startDefinedMove(id, list, count, flags)
            }
            for (code in 0..0xFF) engine.register(code, EventHandler { EVC_STOP_YIELD })
            loads.registerOn(engine)
            moveUnit.registerOn(engine)
        }

        fun verifyWindow1() {
            val ew10298 = ewram(10298)
            procs.init()
            mu.initMus()
            bmSt.decode(gbmst.step(10298), 0)
            playSt.decode(gplayst.step(10298), 0)
            loadRng(10298)
            table.loadFrom(ew10298)
            map.initChapterMap(playSt.chapterIndex)
            copyMapLayer(ew10298, ADDR_MAP_UNIT, map.unit)
            copyMapLayer(ew10298, ADDR_MAP_OTHER, map.other)

            val evProc = seedEventEngine(10298)
            val observedCounts = HashMap<Int, Int>()
            val simulatedRngState = HashMap<Int, List<Int>>()
            fun recordStepMetrics(step: Int) {
                observedCounts[step] = activeRedCount()
                simulatedRngState[step] = listOf(
                    rng.primary.word(0),
                    rng.primary.word(1),
                    rng.primary.word(2),
                )
            }

            // Verify f10298 seed state.
            recordStepMetrics(10298)
            assertStepState(
                10298,
                evProc,
                checkMapOther = true,
                checkMapUnit = true,
                maskAllRedState = false,
            )

            // First main-loop pass: f10298 -> f10301, overrunning VBlanks f10299 and f10300.
            val midSteps = intArrayOf(10299, 10300)
            var midIdx = 0
            onBeforeStartDefinedMove = {
                val count = activeRedCount()
                if (midIdx < midSteps.size && (count == 2 || count == 4)) {
                    val step = midSteps[midIdx++]
                    recordStepMetrics(step)
                    assertMidOverrunStep(step, evProc, count)
                }
            }
            procs.run(procs.root(3))
            procs.run(procs.root(5))
            onBeforeStartDefinedMove = null
            assertEquals(2, midIdx, "both mid-load overrun snapshots observed")
            recordStepMetrics(10301)
            assertStepState(
                10301,
                evProc,
                checkMapOther = true,
                checkMapUnit = true,
                maskAllRedState = false,
            )

            // Run f10302..f10378 frame by frame.
            var step = 10302
            while (step <= 10378) {
                val mle = gbmst.u8(step, 0)
                if (mle == 0) {
                    // Overrun frames 10347, 10352, 10358: VBlank fired after tree 3 before tree 5.
                    procs.run(procs.root(3))
                    recordStepMetrics(step)
                    assertStepState(
                        step,
                        evProc,
                        checkMapOther = true,
                        checkMapUnit = (step != 10358),
                        maskAllRedState = (step == 10358),
                    )
                    procs.run(procs.root(5))
                    val nextMle = gbmst.u8(step + 1, 0)
                    assertEquals(1, nextMle, "expected completed pass at f${step + 1}")
                    recordStepMetrics(step + 1)
                    assertStepState(
                        step + 1,
                        evProc,
                        checkMapOther = true,
                        checkMapUnit = true,
                        maskAllRedState = false,
                    )
                    step += 2
                } else {
                    procs.run(procs.root(3))
                    procs.run(procs.root(5))
                    recordStepMetrics(step)
                    assertStepState(
                        step,
                        evProc,
                        checkMapOther = true,
                        checkMapUnit = true,
                        maskAllRedState = false,
                    )
                    step += 1
                }
            }

            // Explicit checks on exact slot counts, throttle boundaries, and RNG draw frames.
            val expectedSlotCounts = mapOf(
                10298 to 0, 10299 to 2, 10300 to 4, 10346 to 4, 10347 to 5,
                10351 to 5, 10352 to 6, 10357 to 6, 10358 to 7,
            )
            for ((f, wantCount) in expectedSlotCounts) {
                assertEquals(wantCount, observedCounts[f], "simulated red count f$f")
            }
            for (f in listOf(10299, 10300, 10347, 10352, 10358)) {
                assertRngDrew(f, true, simulatedRngState)
            }
            for (f in listOf(10301, 10346, 10351, 10357)) {
                assertRngDrew(f, false, simulatedRngState)
            }
        }

        private fun assertMidOverrunStep(step: Int, evProc: EventEngineProc, expectedCount: Int) {
            assertEquals(expectedCount, activeRedCount(), "mid-load red count at f$step")
            assertRngEquals(step)
            val ew = ewram(step)
            assertMapEquals(ew, ADDR_MAP_OTHER, map.other, "gBmMapOther f$step")
            assertMapEquals(ew, ADDR_MAP_UNIT, map.unit, "gBmMapUnit f$step")
            assertRedUnits(step, ew, maskLastLoadedState = true)
            val dumpEv = dumpEventEngine(step)
            assertEquals(dumpEv.u32(EV_CURRENT), evProc.pEventCurrent, "pEventCurrent f$step")
            assertEquals(dumpEv.u32(EV_CALLBACK) != 0, evProc.callback != null, "pCallback f$step")
        }

        private fun assertStepState(
            step: Int,
            evProc: EventEngineProc,
            checkMapOther: Boolean,
            checkMapUnit: Boolean,
            maskAllRedState: Boolean,
        ) {
            val ew = ewram(step)
            val expectedCount = (0 until 50).count {
                u32(ew, RED_ADDR - EWRAM_BASE + it * UNIT_SIZE) != 0
            }
            assertEquals(expectedCount, activeRedCount(), "active red count f$step")
            assertRedUnits(step, ew, maskLastLoadedState = maskAllRedState)
            if (checkMapUnit) assertMapEquals(ew, ADDR_MAP_UNIT, map.unit, "gBmMapUnit f$step")
            if (checkMapOther) assertMapEquals(ew, ADDR_MAP_OTHER, map.other, "gBmMapOther f$step")
            assertRngEquals(step)

            val dumpEv = dumpEventEngine(step)
            assertEquals(dumpEv.u32(EV_CURRENT), evProc.pEventCurrent, "pEventCurrent f$step")
            assertEquals(dumpEv.u32(EV_CALLBACK) != 0, evProc.callback != null, "pCallback f$step")
            assertEquals(
                dumpEv.u16(EV_UNIT_LOAD_COUNT).toShort().toInt(),
                evProc.unitLoadCount,
                "unitLoadCount f$step",
            )

            // Verify MuCtrl.flags on all live controllers matches ModifyMoveUnitFlag output.
            val wantMcs = ProcArrayDump.ofWatch(procsWatch, step, names).findAll(PROC_SCR_MUCTRL)
            val gotMcs = procs.procs.filter { it.script === muCtrl.script }
                .map { it.state as MuCtrl.MuCtrlProc }
            if (gbmst.u8(step, 0) == 1) {
                assertEquals(wantMcs.size, gotMcs.size, "MuCtrl count f$step")
                for (want in wantMcs) {
                    val unitAddr = want.u32(0x2C)
                    val got = gotMcs.singleOrNull { it.muProc?.unitAddress == unitAddr }
                    assertNotNull(got, "missing MuCtrl for 0x${unitAddr.toString(16)} at f$step")
                    assertEquals(want.u16(0x3E), got!!.flags, "MuCtrl flags f$step")
                }
            }
        }

        private fun assertRedUnits(step: Int, ew: ByteArray, maskLastLoadedState: Boolean) {
            val redWatchStep = redWatch.step(step)
            val lastValid = (0 until 50).lastOrNull { table.isValid(0x81 + it) } ?: -1
            for (slot in 0 until 50) {
                val id = 0x81 + slot
                val o = table.unitOffset(id)
                val ewOff = RED_ADDR - EWRAM_BASE + slot * UNIT_SIZE
                val watchOff = slot * UNIT_SIZE
                if (!table.isValid(id)) {
                    assertEquals(0, u32(ew, ewOff), "slot $slot empty in ewram f$step")
                    assertEquals(0, u32(redWatchStep, watchOff), "slot $slot empty in watch f$step")
                    continue
                }
                val maskState = maskLastLoadedState && slot == lastValid
                for (b in 0 until UNIT_SIZE) {
                    if (b in 0x3C..0x3F) continue // pMapSpriteHandle (RefreshUnitSprites)
                    if (maskState && b in 0x0C..0x0F) continue
                    val got = table.bytes[o + b].toInt() and 0xFF
                    val wantEw = ew[ewOff + b].toInt() and 0xFF
                    val wantWatch = redWatchStep[watchOff + b].toInt() and 0xFF
                    assertEquals(
                        wantWatch,
                        wantEw,
                        "watch vs ewram slot=$slot +0x${b.toString(16)} f$step",
                    )
                    assertEquals(wantEw, got, "red[$slot]+0x${b.toString(16)} f$step")
                }
            }
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
                    val got = pool[y, x]
                    assertEquals(want, got, "$label ($x,$y)")
                }
            }
        }

        private fun assertRngEquals(step: Int) {
            val want = IntArray(3) { rngWatch.u16(step, 2 * it) }
            val got = IntArray(3) { rng.primary.word(it) }
            assertArrayEquals(want, got, "gRNState f$step")
        }

        private fun assertRngDrew(
            step: Int,
            expectedDrew: Boolean,
            simulatedRngState: Map<Int, List<Int>>,
        ) {
            val prev = List(3) { rngWatch.u16(step - 1, 2 * it) }
            val curr = List(3) { rngWatch.u16(step, 2 * it) }
            val simPrev = simulatedRngState.getValue(step - 1)
            val simCurr = simulatedRngState.getValue(step)
            if (expectedDrew) {
                assertTrue(curr != prev, "expected capture RNG draw at f$step")
                assertTrue(simCurr != simPrev, "expected simulated RNG draw at f$step")
            } else {
                assertFalse(curr != prev, "expected no capture RNG draw at f$step")
                assertFalse(simCurr != simPrev, "expected no simulated RNG draw at f$step")
            }
        }

        private fun activeRedCount(): Int = (0 until 50).count { table.isValid(0x81 + it) }

        private fun dumpEventEngine(step: Int): ProcArrayDump.Proc = checkNotNull(
            ProcArrayDump.ofWatch(procsWatch, step, names).find("ProcScr_StdEventEngine"),
        ) {
            "missing ProcScr_StdEventEngine at f$step"
        }

        private fun seedEventEngine(step: Int): EventEngineProc {
            val dumpEv = dumpEventEngine(step)
            val proc = engine.create(dumpEv.u32(EV_START), dumpEv.u8(EV_EXEC_TYPE))
            proc.pEventIdk = dumpEv.u32(EV_IDK)
            proc.pEventCurrent = dumpEv.u32(EV_CURRENT)
            proc.evStateBits = dumpEv.u16(EV_STATE_BITS)
            proc.evStallTimer = dumpEv.u16(EV_STALL_TIMER)
            proc.proc!!.scrCur = 4 // repeat(onUpdateFn)
            return proc
        }

        private fun loadRng(step: Int) {
            rng.primary.load(
                rngWatch.u16(step, 0),
                rngWatch.u16(step, 2),
                rngWatch.u16(step, 4),
            )
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

        private fun ewram(step: Int): ByteArray {
            val tag = if (step == 10300) "f%05d".format(step) else "r%05d".format(step)
            return run.region(tag, "ewram")
        }

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, off: Int): Int =
            u16(b, off) or (u16(b, off + 2) shl 16)
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val RED_ADDR = 0x0202CFBC
        const val UNIT_SIZE = 0x48
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val PLAY_FLAG_HARD = 1 shl 6
        const val PROC_SCR_MUCTRL = 0x089A2DB0
        const val EV_CALLBACK = 0x2C
        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_STALL_TIMER = 0x3E
        const val EV_EXEC_TYPE = 0x41
        const val EV_UNIT_LOAD_COUNT = 0x4C
    }
}
