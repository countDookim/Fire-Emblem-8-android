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
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventMoveUnit
import fe8.game.event.EventScrCore
import fe8.game.event.EventScrHooks
import fe8.game.flags.Fe8EventFlags
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
import gbafe.proc.ProcFunc
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class EasyTutorialEnemySpawnDifferentialTest {
    private var shared: Pair<ByteArray, ReferenceData.Run>? = null
    private var loaded = false

    @AfterAll
    fun releaseRig() {
        shared = null
    }

    private fun rig(): Rig {
        if (!loaded) {
            val ref = ReferenceData.fromEnvironment()
            val assets = File(
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            )
            val romFile = File(assets, "baserom.gba")
            shared = if (ref.available && romFile.isFile) {
                romFile.readBytes() to ref.run(RUN)
            } else {
                null
            }
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data or ROM not present")
        return Rig(r!!.first, r.second)
    }

    @Test
    fun `Turn1 spawn script loads and walks three red units f19901 to f19970`() {
        rig().verify()
    }

    @VerifiesPort("Event02_EvBitAndIdMod")
    @Test
    fun `End-turn events set and clear their flags per frame f19883 to f19892`() {
        rig().verifyEndTurnFlags()
    }

    @VerifiesPort("Event02_EvBitAndIdMod")
    @Test
    fun `Turn1 message event flags 0x103 and 0x4F 0x50 per frame f20478 to f20725`() {
        rig().verifyTurn1MessageFlags()
    }

    private class Rig(
        romBytes: ByteArray,
        private val run: ReferenceData.Run,
    ) {
        private val flags = Fe8EventFlags.create()
        private val flagsWatch = run.watch("flags")
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val evGlobalsWatch = run.watch("evglobals")
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
            EventScrCore(
                state, mem, flags = flags, rng = rng, playSt = playSt, keys = keys,
                hooks = object : EventScrHooks {
                    override fun countRedUnits(): Int = table.countRedUnits()

                    // The active-event registry is not ported to this window: the id is the
                    // capture's (slot 2 after `EvtGetTriggeredEid`).
                    override fun eventTriggerId(script: Int): Int =
                        if (script == END_TURN_EVENT) END_TURN_EVENT_FLAG else 0
                },
            )
                .registerAll(engine)
            unitState.registerOn(engine)
            loads.registerOn(engine)
            moveUnit.registerOn(engine)
        }

        fun verify() {
            val ew = run.region("f%05d".format(START - 1), "ewram")
            procs.init()
            mu.initMus()
            bmSt.decode(gbmst.step(START - 1), 0)
            playSt.decode(gplayst.step(START - 1), 0)
            loadRng(START - 1)
            flags.copyIn(flagsWatch.step(START - 1))
            table.loadFrom(ew)
            map.initChapterMap(playSt.chapterIndex)
            copyMapLayer(ew, ADDR_MAP_UNIT, map.unit)
            copyMapLayer(ew, ADDR_MAP_OTHER, map.other)
            val dumpEv = checkNotNull(
                ProcArrayDump.ofWatch(procsWatch, START, names).find("ProcScr_StdEventEngine"),
            )
            engine.create(dumpEv.u32(EV_START), dumpEv.u8(EV_EXEC_TYPE))
            // The capture's first two frames are mid-logic: the unit loads and walk set-up overran
            // their vblanks (f19901 holds a MuCtrl proc whose fields are not set yet, and every MU
            // is still INACTIVE through f19902). The walks then run LAG frames behind the port's
            // logic frames, so port frame f is compared with capture frame f + LAG.
            for (f in START..START + LAG - 1) {
                for (m in capMus(f)) {
                    assertEquals(MU_STATE_INACTIVE_CAP, m.u8(MU_STATE), "cap MU f$f")
                }
            }
            for (step in START..END - LAG) {
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                val cap = step + LAG
                if (gbmst.u8(cap, 0) != 1) continue
                assertRedWatch(cap)
                assertMus(cap)
                assertFlags(cap)
            }
            for ((f, pos) in mapOf(
                19902 to listOf(14 to 8, 14 to 7, 14 to 7),
                19936 to listOf(10 to 8, 14 to 7, 14 to 7),
                19960 to listOf(10 to 8, 8 to 6, 14 to 7),
                19967 to listOf(10 to 8, 8 to 6, 9 to 6),
            )) {
                val w = redWatch.step(f)
                for (i in 0..2) {
                    assertEquals(pos[i].first, w[i * UNIT_SIZE + 0x10].toInt(), "cap red$i x f$f")
                    assertEquals(pos[i].second, w[i * UNIT_SIZE + 0x11].toInt(), "cap red$i y f$f")
                }
            }
            for (i in 0..2) {
                val hp = intArrayOf(23, 22, 20)[i]
                assertEquals(hp, table.bytes[table.unitOffset(0x81 + i) + 0x13].toInt(), "hp $i")
            }
        }

        /**
         * Runs the Easy-tutorial box event's tail (f19884..f19888: its END pops the call stack,
         * `SetFlag` 0xC9 and 0xB7, then `Event01_End` hands over to the cutscene tail script) and
         * the end-turn event (queued, runs f19890: `ClearFlag` of the slot-2 flag 0x07), seeded
         * from the capture at f19883. The capture's event flags are compared every frame.
         */
        fun verifyEndTurnFlags() {
            val seed = 19883
            val ew = run.region("r19880", "ewram")
            procs.init()
            mu.initMus()
            bmSt.decode(gbmst.step(seed), 0)
            playSt.decode(gplayst.step(seed), 0)
            loadRng(seed)
            flags.copyIn(flagsWatch.step(seed))
            table.loadFrom(ew)
            map.initChapterMap(playSt.chapterIndex)
            // The box's text commands are stubs (their procs are PC-10e); the capture's box is
            // gone at the seed, so each one passes in one frame.
            for (code in intArrayOf(0x1A, 0x1B, 0x1D)) {
                engine.register(code, EventHandler { EVC_ADVANCE_YIELD })
            }
            state.load(evGlobalsWatch.step(seed), 0)
            val ev2 = checkNotNull(
                ProcArrayDump.ofWatch(procsWatch, 19889, names).find("ProcScr_StdEventEngine"),
            )
            assertEquals(END_TURN_EVENT, ev2.u32(EV_START), "end-turn event")
            // The queued end-turn event is the one the capture starts at f19889 (the port hands
            // over by CallNextQueuedEvent when the box engine ends).
            assertEquals(END_TURN_EVENT, state.callQueue[0], "queued end-turn event")
            seedEventEngine(seed)
            val unchanged = flagsWatch.step(seed)
            var changed = 0
            // The capture's new engine only finishes its set-up in f19889 (scr 3, nothing run
            // yet) and runs its script in f19890; the port runs it in its f19889: from there on
            // port frame f is compared with capture frame f + 1.
            for (step in seed + 1..19891) {
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                val cap = if (step >= 19889) step + 1 else step
                assertFlags(cap)
                if (!flagsWatch.step(cap).contentEquals(unchanged)) changed++
            }
            // 0x52 and 0x64 set f19886, 0x106 cleared f19890 (raw bit numbers of the watch)
            assertEquals(1, flagBit(flagsWatch.step(19886), 0x52))
            assertEquals(1, flagBit(flagsWatch.step(19886), 0x64))
            assertEquals(1, flagBit(flagsWatch.step(19889), 0x106))
            assertEquals(0, flagBit(flagsWatch.step(19890), 0x106))
            assertTrue(changed >= 4, "flags change in the window")
        }

        /**
         * Seeds the box-less event engine at [seed] and compares the capture's flags every frame
         * up to [last]: the Turn1 message event clears chapter flag 4 (raw 0x103) f20479 and
         * sets 0xB4/0xB5 (raw 0x4F/0x50) f20725; no other flag changes in f20479..20731 (the
         * capture compare covers every flag bit).
         */
        fun verifyTurn1MessageFlags() {
            for (code in intArrayOf(0x1A, 0x1B, 0x1D)) {
                engine.register(code, EventHandler { EVC_ADVANCE_YIELD })
            }
            for ((seed, last) in listOf(20478 to 20480, 20723 to 20727)) {
                procs.init()
                playSt.decode(gplayst.step(seed), 0)
                bmSt.decode(gbmst.step(seed), 0)
                flags.copyIn(flagsWatch.step(seed))
                state.load(evGlobalsWatch.step(seed), 0)
                val proc = seedEventEngine(seed)
                for (step in seed + 1..last) {
                    procs.run(procs.root(3))
                    procs.run(procs.root(5))
                    assertFlags(step)
                    val dump = dumpEventEngine(step)
                    if (step < last - 1) {
                        assertEquals(dump.u32(EV_CURRENT), proc.pEventCurrent, "cur f$step")
                    }
                }
            }
            assertEquals(1, flagBit(flagsWatch.step(20478), 0x103))
            assertEquals(0, flagBit(flagsWatch.step(20479), 0x103))
            assertEquals(0, flagBit(flagsWatch.step(20724), 0x4F))
            assertEquals(1, flagBit(flagsWatch.step(20725), 0x4F))
            assertEquals(1, flagBit(flagsWatch.step(20725), 0x50))
        }

        private fun flagBit(bits: ByteArray, raw: Int): Int =
            (bits[raw shr 3].toInt() shr (raw and 7)) and 1

        private fun assertFlags(cap: Int) {
            val want = flagsWatch.step(cap)
            val got = ByteArray(want.size)
            flags.copyOut(got)
            assertArrayEquals(want, got, "event flags f$cap")
        }

        private fun capMus(step: Int): List<ProcArrayDump.Proc> {
            val d = ProcArrayDump.ofWatch(procsWatch, step, names)
            val script = names.entries.first { it.value == "ProcScr_Mu" }.key
            return d.findAll(script)
        }

        private fun assertMus(cap: Int) {
            val want = capMus(cap)
            val got = ArrayList<fe8.game.bm.MuProc>()
            procs.forEach(mu.script, ProcFunc { got += it.state as fe8.game.bm.MuProc })
            assertEquals(want.size, got.size, "MU count f$cap")
            for (w in want) {
                val addr = w.u32(MU_UNIT)
                val m = got.singleOrNull { it.unitAddress == addr }
                assertNotNull(m, "MU for 0x${addr.toString(16)} f$cap")
                m!!
                val ctx = "MU 0x${addr.toString(16)} f$cap"
                assertEquals(w.u8(MU_STATE), m.state, "$ctx state")
                assertEquals(w.u8(MU_FACING).toByte().toInt(), m.facing, "$ctx facing")
                assertEquals(w.u16(MU_MOVE_CLOCK), m.moveClockQ4, "$ctx moveClock")
                assertEquals(w.u16(MU_MOVE_CONFIG).toShort().toInt(), m.moveConfig, "$ctx config")
                assertEquals(w.u16(MU_X).toShort().toInt(), m.xQ4, "$ctx x")
                assertEquals(w.u16(MU_Y).toShort().toInt(), m.yQ4, "$ctx y")
                assertEquals(w.u8(MU_HIDDEN), m.hiddenB, "$ctx hidden")
                assertEquals(w.u8(MU_CAM) != 0, m.camB, "$ctx camB")
            }
        }

        private fun assertRedWatch(step: Int) {
            val w = redWatch.step(step)
            for (slot in 0 until 50) {
                val o = table.unitOffset(0x81 + slot)
                val valid = table.isValid(0x81 + slot)
                assertEquals(u32(w, slot * UNIT_SIZE) != 0, valid, "slot $slot valid f$step")
                if (!valid) continue
                for (b in 0 until UNIT_SIZE) {
                    if (b in 0x3C..0x3F) continue
                    assertEquals(
                        w[slot * UNIT_SIZE + b].toInt() and 0xFF,
                        table.bytes[o + b].toInt() and 0xFF,
                        "red[$slot]+0x${b.toString(16)} f$step",
                    )
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
        const val RUN = "ram_trace_newgame_easy"
        const val START = 19901
        const val END = 19970
        const val EWRAM_BASE = 0x02000000
        const val RED_ADDR = 0x0202CFBC
        const val UNIT_SIZE = 0x48
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val PLAY_FLAG_HARD = 1 shl 6
        const val PROC_SCR_MUCTRL = 0x089A2DB0
        const val LAG = 2
        const val END_TURN_EVENT = 0x089EF0D8
        const val END_TURN_EVENT_FLAG = 7
        const val MU_STATE_INACTIVE_CAP = 1
        const val MU_UNIT = 0x2C
        const val MU_CAM = 0x3E
        const val MU_STATE = 0x3F
        const val MU_HIDDEN = 0x40
        const val MU_FACING = 0x42
        const val MU_MOVE_CLOCK = 0x48
        const val MU_MOVE_CONFIG = 0x4A
        const val MU_X = 0x4C
        const val MU_Y = 0x4E
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
