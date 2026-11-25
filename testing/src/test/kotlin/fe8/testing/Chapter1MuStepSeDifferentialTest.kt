package fe8.testing

import fe8.game.Fe8Sound
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.SpacialSe
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
import gba.audio.M4aSound
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
 * PC-6l-d: [Mu] step sound and start state (`UpdateMuStepSounds`, `PlayMuStepSe`,
 * `MuStepSe_Init`, `StartPlayMuStepSe`, `MuStepSe_PlaySeA`, `MuStepSe_PlaySeB`, `StartMuInternal`,
 * `Mu_OnStateMovement`, `GetMuDisplayPosition`, and [SpacialSe] `PlaySeSpacial`/`Screen2Pan`)
 * verified against `ram_trace_prologue_end` (`PC-cap1`) across Chapter 1 opening windows W1
 * (`f10299..f10376`, stepped `f10298..f10378`) and W4 (`f16019..f16153`).
 *
 * Frame timing: mGBA snapshots at VBlank; frames with `gBmSt.mainLoopEnded == 0` (`10299`,
 * `10300`, `10347`, `10352`, `10358` in W1; `16019`, `16020`, `16021`, `16057` in W4) are
 * mid-pass interrupts whose step completes on the following `mainLoopEnded == 1` frame.
 *
 * GBA dirty `sProcArray` `+0x60` (`unk60`): `Proc_Start` does not zero `+0x29..0x6B` and
 * `MuStepSe_Init` does not clear `unk60`, so reused proc slots keep stale non-zero `+0x60` values
 * (`0x05000003` at `f10363..10364`, `0x080DADB6` at `f16039..16067`, `0x05050506` at
 * `f16072..16139`). When `unk60 != 0`, GBA's `StartPlayMuStepSe` skips `song2`/`x2` (`f16059`,
 * `f16067`, `f16075` in W4), while Kotlin's [Mu.StepSeState] starts with `unk60 = 0`; `song2`/`x2`
 * are compared against the capture whenever `want.u32(0x60) == 0`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1MuStepSeDifferentialTest {
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
        if (!run.hasWatch("procs") || !run.hasWatch("gbmst") || !run.hasWatch("gplayst")) {
            return null
        }
        return Rig(ref, romFile.readBytes(), run, names)
    }

    @Test
    @VerifiesPort(
        "StartMuExt",
        "UpdateMuStepSounds",
        "PlayMuStepSe",
        "MuStepSe_Init",
        "StartPlayMuStepSe",
        "MuStepSe_PlaySeA",
        "MuStepSe_PlaySeB",
        "StartMuInternal",
        "Mu_OnStateMovement",
        "GetMuDisplayPosition",
        "PlaySeSpacial",
        "Screen2Pan",
    )
    fun `W1 MU positions speeds stepSoundClock ProcScr_MuStepSe and M4A match PC-cap1 f10299 to f10376`() {
        rig().verifyWindow1()
    }

    @Test
    @VerifiesPort(
        "UpdateMuStepSounds",
        "PlayMuStepSe",
        "MuStepSe_Init",
        "StartPlayMuStepSe",
        "MuStepSe_PlaySeA",
        "MuStepSe_PlaySeB",
        "StartMuInternal",
        "Mu_OnStateMovement",
        "GetMuDisplayPosition",
        "PlaySeSpacial",
        "Screen2Pan",
    )
    fun `W4 MU positions speeds stepSoundClock ProcScr_MuStepSe and M4A match PC-cap1 f16019 to f16153`() {
        rig().verifyWindow4()
    }

    private class Rig(
        private val ref: ReferenceData,
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
        private val evGlobalsWatch = run.watch("evglobals")
        private val dumpByStep = run.dumps.entries.associate { it.value to it.key }
        private val players = M4aRam.PLAYERS.values.toList()

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
        private var m4a: M4aSound = Fe8Sound.create(rom)
        private var sound = SoundWrapper(procs, m4a) {
            (playSt.disableBgm shl 8) or (playSt.disableSoundEffects shl 9)
        }
        private var spacial = SpacialSe(sound)
        private val seLog = ArrayList<SeCall>()
        private var currentStep = 0

        private val mu = Mu(
            procs, bmSt, playSt, keys, camMove.script,
            object : MuHooks {
                override fun classAttributes(jid: Int): Int =
                    rom.u32(CLASS_DATA + (jid - 1) * CLASS_DATA_SIZE + 0x28)

                override fun classSlowWalking(jid: Int): Int =
                    rom.u8(CLASS_DATA + (jid - 1) * CLASS_DATA_SIZE + 0x07)

                override fun playSeSpacial(song: Int, x: Int) {
                    seLog += SeCall(currentStep, song, x)
                    spacial.playSeSpacial(song, x)
                }
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
            loads.registerOn(engine)
            moveUnit.registerOn(engine)
            unitState.registerOn(engine)
        }

        fun verifyWindow1() {
            seedWindow(10298, 10298, null)
            assertMuAndStepSe(10298, verifyM4aOnEveryPlay = false)

            var completed = 0
            var sawHeavyBreguet = false
            var sawFootSoldier = false
            var sawFootFighter = false
            var sawClockZero = false
            var sawClockFe = false
            var sawDualSound = false
            var sawCleanSlotResetAfterDual = false

            for (step in 10299..10378) {
                if (gbmst.u8(step, 0) == 0) continue
                currentStep = step
                val beforeSe = seLog.size
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuAndStepSe(step, verifyM4aOnEveryPlay = true, beforeSe = beforeSe)

                for (proc in procs.procs.filter { it.script === mu.script }) {
                    val m = proc.state as MuProc
                    if (m.state == Mu.MU_STATE_MOVEMENT) {
                        assertEquals(24, m.moveConfig, "W1 f$step moveConfig")
                    }
                    when (m.unitAddress) {
                        ADDR_RED -> {
                            assertEquals(CLASS_ARMOR_KNIGHT, m.jid, "W1 Breguet class")
                            sawHeavyBreguet = true
                            if (step == 10301) {
                                assertEquals(2, m.stepSoundClock, "W1 Breguet clock started at 0")
                                sawClockZero = true
                            }
                        }
                        ADDR_RED + UNIT_SIZE, ADDR_RED + 2 * UNIT_SIZE,
                        ADDR_RED + 3 * UNIT_SIZE,
                        -> {
                            assertEquals(CLASS_SOLDIER, m.jid, "W1 Soldier class")
                            sawFootSoldier = true
                            if (step == 10301 && m.state == Mu.MU_STATE_INACTIVE) {
                                assertEquals(
                                    0xFE,
                                    m.stepSoundClock,
                                    "W1 co-started Soldier clock 0xFE",
                                )
                                sawClockFe = true
                            }
                        }
                        else -> {
                            assertEquals(CLASS_FIGHTER, m.jid, "W1 Fighter class")
                            sawFootFighter = true
                        }
                    }
                }
                val stepSe = procs.find(mu.stepSeScript)?.state as Mu.StepSeState?
                if (stepSe != null) {
                    if (stepSe.song2 != 0) sawDualSound = true
                    if (step == 10332) {
                        assertEquals(SONG_A4, stepSe.song1, "W1 f10332 song1")
                        assertEquals(0, stepSe.song2, "W1 f10332 song2 cleared")
                        assertEquals(0, stepSe.x2, "W1 f10332 x2 cleared")
                        sawCleanSlotResetAfterDual = true
                    }
                }
            }

            assertEquals(75, completed, "W1 completed passes")
            assertEquals(16, seLog.size, "W1 PlaySeSpacial calls")
            assertTrue(seLog.any { it.song == SONG_A4 } && seLog.any { it.song == SONG_A5 },
                "W1 Breguet FootHeavy songs A4/A5")
            assertTrue(
                seLog.any { it.song == SONG_96 } && seLog.any { it.song == SONG_97 } &&
                    seLog.any { it.song == SONG_98 } && seLog.any { it.song == SONG_99 },
                "W1 Foot songs 96/97 and alt 98/99",
            )
            assertTrue(sawHeavyBreguet && sawFootSoldier && sawFootFighter, "W1 classes exercised")
            assertTrue(sawClockZero && sawClockFe, "W1 clock 0 and 0xFE exercised")
            assertTrue(sawDualSound && sawCleanSlotResetAfterDual, "W1 dual sound and reset")
        }

        fun verifyWindow4() {
            seedWindow(16018, 16010, 0x089EF93C)
            assertMuAndStepSe(16018, verifyM4aOnEveryPlay = false)

            var completed = 0
            var sawHeavyBreguet = false
            var sawFootSoldier = false
            var sawFootFighter = false
            var sawDualSound = false

            for (step in 16019..16153) {
                if (gbmst.u8(step, 0) == 0) continue
                currentStep = step
                val beforeSe = seLog.size
                keys.updateWith(run.inputs[step - 1])
                procs.run(procs.root(3))
                procs.run(procs.root(5))
                completed++

                assertMuAndStepSe(step, verifyM4aOnEveryPlay = false, beforeSe = beforeSe)
                if (step == 16040 || step == 16050) {
                    assertTrue(seLog.size > beforeSe, "W4 f$step has PlaySeSpacial dump")
                    assertM4aDumpMatches(step, beforeSe)
                }

                for (proc in procs.procs.filter { it.script === mu.script }) {
                    val m = proc.state as MuProc
                    when (m.unitAddress) {
                        ADDR_RED -> {
                            assertEquals(CLASS_ARMOR_KNIGHT, m.jid, "W4 Breguet class")
                            assertEquals(0, m.moveConfig, "W4 Breguet speed 16 Q4/f")
                            sawHeavyBreguet = true
                            if (step == 16022) {
                                assertEquals(2, m.stepSoundClock, "W4 Breguet clock after start")
                            }
                        }
                        ADDR_RED + UNIT_SIZE, ADDR_RED + 2 * UNIT_SIZE,
                        ADDR_RED + 3 * UNIT_SIZE,
                        -> {
                            assertEquals(CLASS_SOLDIER, m.jid, "W4 Soldier class")
                            assertEquals(24, m.moveConfig, "W4 Soldier speed 24 Q4/f")
                            sawFootSoldier = true
                        }
                        else -> {
                            assertEquals(CLASS_FIGHTER, m.jid, "W4 Fighter class")
                            assertEquals(24, m.moveConfig, "W4 Fighter speed 24 Q4/f")
                            sawFootFighter = true
                        }
                    }
                }
                val stepSe = procs.find(mu.stepSeScript)?.state as Mu.StepSeState?
                if (stepSe != null && stepSe.song2 != 0) sawDualSound = true
            }

            assertEquals(131, completed, "W4 completed passes")
            assertTrue(seLog.any { it.song == SONG_A4 } && seLog.any { it.song == SONG_A5 },
                "W4 Breguet FootHeavy songs A4/A5")
            assertTrue(
                seLog.any { it.song == SONG_96 } && seLog.any { it.song == SONG_97 } &&
                    seLog.any { it.song == SONG_98 } && seLog.any { it.song == SONG_99 },
                "W4 Foot songs 96/97 and alt 98/99",
            )
            assertTrue(sawHeavyBreguet && sawFootSoldier && sawFootFighter, "W4 classes exercised")
            assertTrue(sawDualSound, "W4 dual sound exercised")
        }

        private fun seedWindow(step: Int, dumpStep: Int, cursorOverride: Int?) {
            val ew = run.region(dumpByStep.getValue(dumpStep), "ewram")
            procs.init()
            mu.initMus()
            seLog.clear()
            m4a = Fe8Sound.create(rom)
            sound = SoundWrapper(procs, m4a) {
                (playSt.disableBgm shl 8) or (playSt.disableSoundEffects shl 9)
            }
            spacial = SpacialSe(sound)
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
        }

        private fun assertMuAndStepSe(
            step: Int,
            verifyM4aOnEveryPlay: Boolean,
            beforeSe: Int = seLog.size,
        ) {
            val dump = ProcArrayDump.ofWatch(procsWatch, step, names)
            val wantMus = dump.findAll(PROC_SCR_MU)
            val gotMus = procs.procs.filter { it.script === mu.script }
                .map { it.state as MuProc }
            assertEquals(wantMus.size, gotMus.size, "MU count f$step")

            for (wantMu in wantMus) {
                val unitAddr = wantMu.u32(MU_UNIT)
                val m = gotMus.singleOrNull { it.unitAddress == unitAddr }
                assertNotNull(m, "missing MU for 0x${unitAddr.toString(16)} at f$step")
                val ctx = "f$step unit=0x${unitAddr.toString(16)}"
                assertEquals(wantMu.u8(MU_CAM) != 0, m!!.camB, "$ctx camB")
                assertEquals(wantMu.u8(MU_STATE), m.state, "$ctx state")
                assertEquals(wantMu.u8(MU_HIDDEN), m.hiddenB, "$ctx hiddenB")
                assertEquals(wantMu.u8(MU_JID), m.jid, "$ctx jid")
                assertEquals(wantMu.s8(MU_FACING), m.facing, "$ctx facing")
                assertEquals(wantMu.u8(MU_STEP_CLK), m.stepSoundClock, "$ctx stepSoundClock")
                assertEquals(wantMu.u8(MU_FAST_WALK) != 0, m.fastWalkB, "$ctx fastWalkB")
                assertEquals(wantMu.u16(MU_MOVE_CLK), m.moveClockQ4, "$ctx moveClockQ4")
                assertEquals(wantMu.s16(MU_CONFIG), m.moveConfig, "$ctx moveConfig")
                assertEquals(wantMu.s16(MU_XQ4), m.xQ4, "$ctx xQ4")
                assertEquals(wantMu.s16(MU_YQ4), m.yQ4, "$ctx yQ4")
            }

            val wantSe = dump.find(PROC_SCR_MU_STEP_SE)
            val gotSe = procs.find(mu.stepSeScript)
            assertEquals(wantSe != null, gotSe != null, "ProcScr_MuStepSe exists f$step")
            if (wantSe != null) {
                val st = gotSe!!.state as Mu.StepSeState
                assertEquals(wantSe.scrCur, gotSe.scrCur, "MuStepSe scrCur f$step")
                assertEquals(wantSe.u32(SE_SONG1), st.song1, "MuStepSe song1 f$step")
                assertEquals(wantSe.s16(SE_X1), st.x1, "MuStepSe x1 f$step")
                if (wantSe.u32(SE_UNK60) == 0) {
                    assertEquals(wantSe.u32(SE_SONG2), st.song2, "MuStepSe song2 f$step")
                    assertEquals(wantSe.s16(SE_X2), st.x2, "MuStepSe x2 f$step")
                } else {
                    assertEquals(0, wantSe.u32(SE_SONG2), "dirty unk60 suppresses GBA song2 f$step")
                }
            }

            if (verifyM4aOnEveryPlay && seLog.size > beforeSe) {
                assertM4aDumpMatches(step, beforeSe)
            }
        }

        private fun assertM4aDumpMatches(step: Int, fromIndex: Int) {
            val iwram = iwramRegion(step)
            for (k in fromIndex until seLog.size) {
                val call = seLog[k]
                val index = m4a.songs.player(call.song)
                val (address, slots) = players[index]
                val captured = M4aRam.player(iwram, address, slots)
                val ours = m4a.players[index]
                assertEquals(
                    captured.songHeader,
                    ours.songHeader,
                    "M4A player $index songHeader f$step (song=0x${call.song.toString(16)})",
                )
                for (t in 0 until captured.trackCount) {
                    assertEquals(
                        captured.tracks[t].panX,
                        ours.tracks[t].panX,
                        "M4A player $index track $t panX f$step (song=0x${call.song.toString(16)}, " +
                            "x=${call.x})",
                    )
                }
            }
        }

        private fun iwramRegion(step: Int): ByteArray {
            val r = "r%05d".format(step)
            val per = File(ref.root, "${run.name}/$r.iwram.bin")
            val tag = if (per.isFile) r else "f%05d".format(step)
            return run.region(tag, "iwram")
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

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, off: Int): Int =
            u16(b, off) or (u16(b, off + 2) shl 16)
    }

    private data class SeCall(val step: Int, val song: Int, val x: Int)

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val ADDR_RED = 0x0202CFBC
        const val UNIT_SIZE = 0x48
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val PLAY_FLAG_HARD = 1 shl 6

        const val CLASS_ARMOR_KNIGHT = 0x09
        const val CLASS_FIGHTER = 0x3F
        const val CLASS_SOLDIER = 0x4E

        const val SONG_96 = 0x96
        const val SONG_97 = 0x97
        const val SONG_98 = 0x98
        const val SONG_99 = 0x99
        const val SONG_A4 = 0xA4
        const val SONG_A5 = 0xA5

        const val PROC_SCR_MU = 0x089A2C48
        const val PROC_SCR_MU_STEP_SE = 0x089A2938

        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_STALL_TIMER = 0x3E
        const val EV_EXEC_TYPE = 0x41

        const val MU_UNIT = 0x2C
        const val MU_CAM = 0x3E
        const val MU_STATE = 0x3F
        const val MU_HIDDEN = 0x40
        const val MU_JID = 0x41
        const val MU_FACING = 0x42
        const val MU_STEP_CLK = 0x43
        const val MU_FAST_WALK = 0x44
        const val MU_MOVE_CLK = 0x48
        const val MU_CONFIG = 0x4A
        const val MU_XQ4 = 0x4C
        const val MU_YQ4 = 0x4E

        const val SE_SONG1 = 0x58
        const val SE_SONG2 = 0x5C
        const val SE_UNK60 = 0x60
        const val SE_X1 = 0x64
        const val SE_X2 = 0x66
    }
}
