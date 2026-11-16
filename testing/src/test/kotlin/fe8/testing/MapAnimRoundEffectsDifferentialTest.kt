package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.CallDelayedArg
import fe8.game.bm.CamMove
import fe8.game.bm.MapAnimApi
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.mapanim.ApProcs
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for EP-24:
 * - Round effect proc B stepping away (`MapAnimMoveUnitAwayFromTargetExt`,
 *   `MapAnim_MoveSubjectsAwayFromTarget` in `mapanim_spellassoc.c`)
 * - Delayed-call procs (`CallDelayedArg`, `CallDelayedArg_OnLoop` in `bmlib.c`) verified
 *   against `f4485..4611` of `ram_trace_prologue_clear`
 * - Miss special animation (`MapAnim_BeginMISSAnim` in `mapanim_specialeffect.c`) and AP proc
 *   (`APProc_Create`, `APProc_OnUpdate`, `APProc_OnEnd` in `ap.c`) verified against
 *   `f4550..4577` and OAM dumps in `ram_trace_prologue_clear`.
 */
class MapAnimRoundEffectsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `MapAnimMoveUnitAwayFromTargetExt and MapAnim_MoveSubjectsAwayFromTarget step subjects back`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val rng = RngService()
        val table = UnitTable(rom)
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val mu = Mu(engine, bm, playSt, KeyStatus(), CamMove(engine, bm).script, object : MuHooks {})
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom)

        // Verify MapAnimMoveUnitAwayFromTargetExt covering all coordinate relationship branches:
        // dx < 0, dx > 0, dx == 0, dy < 0, dy > 0, dy == 0
        val m1 = MuProc()
        val m2 = MuProc()

        // Case 1: m1 is below and right of m2 (dx > 0, dy > 0)
        m1.xQ4 = 0x80
        m1.yQ4 = 0x60
        m2.xQ4 = 0x70
        m2.yQ4 = 0x50
        api.mapAnimMoveUnitAwayFromTargetExt(m1, m2)
        assertEquals(0x90, m1.xQ4, "m1 steps right (away from m2)")
        assertEquals(0x70, m1.yQ4, "m1 steps down (away from m2)")

        // Case 2: m1 is above and left of m2 (dx < 0, dy < 0)
        m1.xQ4 = 0x60
        m1.yQ4 = 0x40
        m2.xQ4 = 0x70
        m2.yQ4 = 0x50
        api.mapAnimMoveUnitAwayFromTargetExt(m1, m2)
        assertEquals(0x50, m1.xQ4, "m1 steps left (away from m2)")
        assertEquals(0x30, m1.yQ4, "m1 steps up (away from m2)")

        // Case 3: m1 has same coordinates as m2 (dx == 0, dy == 0)
        m1.xQ4 = 0x70
        m1.yQ4 = 0x50
        m2.xQ4 = 0x70
        m2.yQ4 = 0x50
        api.mapAnimMoveUnitAwayFromTargetExt(m1, m2)
        assertEquals(0x70, m1.xQ4, "m1 x unchanged when equal")
        assertEquals(0x50, m1.yQ4, "m1 y unchanged when equal")

        // Verify MapAnim_MoveSubjectsAwayFromTarget:
        val muScript = ProcScript("mu", listOf(repeat(ProcFunc {})))
        val muProcs = Array(4) {
            engine.start(muScript, 0).also { p -> p.state = MuProc() }
        }
        for (i in 0 until 4) api.state.mu[i] = muProcs[i]
        api.state.subjectActorId = 0
        api.state.targetActorId = 1
        api.state.actorCount = 4

        val mu0 = muProcs[0].state as MuProc
        val muTarget = muProcs[1].state as MuProc
        val mu2 = muProcs[2].state as MuProc
        val mu3 = muProcs[3].state as MuProc

        mu0.xQ4 = 0x50; mu0.yQ4 = 0x50
        muTarget.xQ4 = 0x60; muTarget.yQ4 = 0x60
        mu2.xQ4 = 0x70; mu2.yQ4 = 0x50
        mu3.xQ4 = 0x50; mu3.yQ4 = 0x70

        // Without triangle attack attribute
        api.state.hitAttributes = 0
        api.mapAnimMoveSubjectsAwayFromTarget()
        assertEquals(0x40, mu0.xQ4, "subject x moves away")
        assertEquals(0x40, mu0.yQ4, "subject y moves away")
        assertEquals(0x70, mu2.xQ4, "partner 2 x unaffected without tattack")
        assertEquals(0x50, mu2.yQ4, "partner 2 y unaffected without tattack")
        assertEquals(0x50, mu3.xQ4, "partner 3 x unaffected without tattack")
        assertEquals(0x70, mu3.yQ4, "partner 3 y unaffected without tattack")

        // With triangle attack attribute (ATTR_TATTACK = 1 shl 10 = 0x400)
        api.state.hitAttributes = 1 shl 10
        api.mapAnimMoveSubjectsAwayFromTarget()
        assertEquals(0x30, mu0.xQ4, "subject x moves away again")
        assertEquals(0x30, mu0.yQ4, "subject y moves away again")
        assertEquals(0x80, mu2.xQ4, "partner 2 moves away from target")
        assertEquals(0x40, mu2.yQ4, "partner 2 moves away from target")
        assertEquals(0x40, mu3.xQ4, "partner 3 moves away from target")
        assertEquals(0x80, mu3.yQ4, "partner 3 moves away from target")
    }

    @Test
    @VerifiesPort(
        "CallDelayedArg",
        "CallDelayedArg_OnLoop",
    )
    fun `CallDelayedArg and CallDelayedArg_OnLoop reproduce delayed-call procs f4485 to f4611`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run(RUN)
        val procs = run.watch("procs")

        val engine = ProcEngine()
        val delayedCall = CallDelayedArg(engine)

        // Three speed-up delayed calls in ram_trace_prologue_clear:
        // Round 1: step 4485..4506, arg = 0x03000108, delay = 20
        // Round 2: step 4544..4565, arg = 0x03000130, delay = 20
        // Round 3: step 4590..4611, arg = 0x03000108, delay = 20
        val calls = listOf(
            Triple(4485, 0x03000108, 20),
            Triple(4544, 0x03000130, 20),
            Triple(4590, 0x03000108, 20),
        )

        val invokedCalls = ArrayList<Pair<Int, Int>>()
        var currentStep = 0

        // In the game, CallDelayedArg is invoked from the round proc on tree 3.
        // Starting it from a proc on tree 3 ensures CallDelayedArg is added to tree 3
        // during tree 3's execution and does not tick until the subsequent frame.
        val callerScript = ProcScript(
            "DelayedCaller",
            listOf(
                repeat(
                    ProcFunc {
                        for ((startStep, arg, delay) in calls) {
                            if (currentStep == startStep) {
                                delayedCall.callDelayedArg(
                                    { invokedCalls.add(currentStep to it) },
                                    arg,
                                    delay,
                                )
                            }
                        }
                    },
                ),
                end(),
            ),
        )
        engine.start(callerScript, 3)

        for (step in 4480..4620) {
            currentStep = step

            // Run proc trees (CallDelayedArg runs on tree 3)
            for (tree in 0 until 8) engine.run(engine.root(tree))

            val dump = ProcArrayDump.ofWatch(procs, step, names)
            val delayedDumps = dump.findAll(PROC_SCR_CALL_DELAYED_ARG)
            val mine = engine.find(delayedCall.script)

            val at = "$RUN step $step"
            assertEquals(delayedDumps.isNotEmpty(), mine != null, "$at: ProcScr_CallDelayedArg presence")
            if (delayedDumps.isNotEmpty() && mine != null) {
                val original = delayedDumps.single()
                val state = mine.state as CallDelayedArg.State
                assertEquals(original.scrCur, mine.scrCur, "$at: script position")
                assertEquals(original.u32(0x30), state.arg, "$at: arg")
                assertEquals(original.u32(0x34), state.clock, "$at: countdown clock")
            }
        }

        // Verify callback was invoked at exact frame (21 frames after start: 4506, 4565, 4611)
        assertEquals(
            listOf(
                4506 to 0x03000108,
                4565 to 0x03000130,
                4611 to 0x03000108,
            ),
            invokedCalls,
            "callback execution frames and arguments",
        )
    }

    @Test
    @VerifiesPort(
        "MapAnim_BeginMISSAnim",
        "APProc_Create",
        "APProc_OnUpdate",
        "APProc_OnEnd",
    )
    fun `MapAnim_BeginMISSAnim decompresses MISS graphic and runs APProc f4550 to f4577 matching OAM`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        val run = ref.run(RUN)
        val procs = run.watch("procs")

        fun name(f: Int) = "f%05d".format(f)
        val ewram4550 = run.region(name(4550), "ewram")

        val engine = ProcEngine()
        val hw = BgHardware()
        val layers = SpriteLayers()
        val oam = OamBuffer()
        val apHost = WorldMapApHost(rom, hw, oam)
        val apPlayer = SpriteAnimPlayer({ rom.u16(it) }, layers, apHost).also { it.clearAll() }
        val apProcs = ApProcs(engine, apPlayer)

        val bm = BmSt().also { it.decode(ewram4550, GBMST - EWRAM) }
        val table = UnitTable(rom).also { it.loadFrom(ewram4550) }
        val playSt = PlaySt().also { it.decode(ewram4550, GPLAYST - EWRAM) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val mu = Mu(engine, bm, playSt, KeyStatus(), CamMove(engine, bm).script, object : MuHooks {})
        val api = MapAnimApi(
            engine, bm, battle, gen, table, loader, mu, rom, hw,
            apProcs = apProcs,
        )

        // Seed actor state from the f4550 dump
        // In f4550, Seth is actor 0 at GBATTLE_ACTOR (x=8, y=6), camera=(0, 0)
        api.state.actorCount = 2
        api.state.unitAddress[0] = GBATTLE_ACTOR
        api.state.unitAddress[1] = GBATTLE_TARGET
        api.state.unitBytes[0] = ewram4550
        api.state.unitOffset[0] = GBATTLE_ACTOR - EWRAM
        api.state.unitBytes[1] = ewram4550
        api.state.unitOffset[1] = GBATTLE_TARGET - EWRAM

        // In the game, MapAnim_BeginMISSAnim is called from the round proc on tree 3 at step 4550.
        // Starting it from a proc on tree 3 ensures ProcScr_ApProc is created during tree 3's
        // run and begins updating on step 4551.
        var currentStep = 0
        val callerScript = ProcScript(
            "MissCaller",
            listOf(
                repeat(
                    ProcFunc {
                        if (currentStep == 4550) {
                            api.mapAnimBeginMissAnim(GBATTLE_ACTOR)
                        }
                    },
                ),
                end(),
            ),
        )
        engine.start(callerScript, 3)

        // Step forward from 4550 to 4580, verifying decompression, APProc presence and OAM
        for (step in 4550..4580) {
            currentStep = step
            // VBlank: sync secondary OAM to hardware OAM (reflecting previous frame's render)
            oam.syncHi()

            // Main loop: clear sprite layers, run proc trees, push to secondary OAM
            layers.clear()
            for (tree in 0 until 8) engine.run(engine.root(tree))
            layers.pushLayerObjects(0) { entry ->
                oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
            }

            if (step == 4550) {
                // 1. Check OBJ VRAM decompression of Img_MapAnimMISS:
                // Decompressed size is 384 (0x180) bytes at OBJ_VRAM0 + 384 * 32 = 0x13000
                val vram4560 = run.region(name(4560), "vram")
                val vramOffset = 0x13000
                for (i in 0 until 0x180) {
                    assertEquals(
                        vram4560[vramOffset + i], hw.vram[vramOffset + i],
                        "decompressed MISS VRAM byte $i",
                    )
                }

                // 2. Check APProc creation at step 4550
                val initialProc = engine.find(apProcs.script)
                assertNotNull(initialProc, "APProc created on tree 3 at step 4550")
                val initialApState = initialProc!!.state as ApProcs.State
                assertEquals(136, initialApState.x, "initial APProc x position")
                assertEquals(112, initialApState.y, "initial APProc y position")
                val initialHandle = initialApState.handle
                assertNotNull(initialHandle, "AP handle allocated")
                assertEquals(OBJ_MAP_ANIM_MISS, initialHandle!!.pDefinition, "handle definition")
                assertEquals(384, initialHandle.tileBase, "handle tile base")
                assertEquals(2, initialHandle.objLayer, "handle obj layer")
            }

            val at = "$RUN step $step"
            val dump = ProcArrayDump.ofWatch(procs, step, names)
            val apDump = dump.find("ProcScr_ApProc")
            val myAp = engine.find(apProcs.script)

            if (step in 4550..4577) {
                assertNotNull(apDump, "$at: captured ProcScr_ApProc alive")
                assertNotNull(myAp, "$at: my ProcScr_ApProc alive")
                assertEquals(apDump!!.scrCur, myAp!!.scrCur, "$at: APProc script cursor")
            } else {
                assertNull(apDump, "$at: captured ProcScr_ApProc ended")
                assertNull(myAp, "$at: my ProcScr_ApProc ended")
            }

            // Verify OAM at dump frames
            if (step in setOf(4560, 4570, 4580)) {
                val origOam = run.region(name(step), "oam")
                if (step == 4560) {
                    // MISS sprite is at Obj 0
                    val a0 = GbaMem.u16(origOam, 0, 0)
                    val a1 = GbaMem.u16(origOam, 0, 2)
                    val a2 = GbaMem.u16(origOam, 0, 4)
                    assertEquals(a0, oam.oam[0].toInt() and 0xFFFF, "$at: MISS OAM attr0")
                    assertEquals(a1, oam.oam[1].toInt() and 0xFFFF, "$at: MISS OAM attr1")
                    assertEquals(a2, oam.oam[2].toInt() and 0xFFFF, "$at: MISS OAM attr2")
                    // Check affine matrix 0
                    val pa = GbaMem.u16(origOam, 0, 6)
                    val pb = GbaMem.u16(origOam, 0, 14)
                    val pc = GbaMem.u16(origOam, 0, 22)
                    val pd = GbaMem.u16(origOam, 0, 30)
                    assertEquals(pa, oam.oam[3].toInt() and 0xFFFF, "$at: Affine PA")
                    assertEquals(pb, oam.oam[7].toInt() and 0xFFFF, "$at: Affine PB")
                    assertEquals(pc, oam.oam[11].toInt() and 0xFFFF, "$at: Affine PC")
                    assertEquals(pd, oam.oam[15].toInt() and 0xFFFF, "$at: Affine PD")
                } else if (step == 4570) {
                    val a0 = GbaMem.u16(origOam, 0, 0)
                    val a1 = GbaMem.u16(origOam, 0, 2)
                    val a2 = GbaMem.u16(origOam, 0, 4)
                    assertEquals(a0, oam.oam[0].toInt() and 0xFFFF, "$at: MISS OAM attr0")
                    assertEquals(a1, oam.oam[1].toInt() and 0xFFFF, "$at: MISS OAM attr1")
                    assertEquals(a2, oam.oam[2].toInt() and 0xFFFF, "$at: MISS OAM attr2")
                } else if (step == 4580) {
                    // At step 4580, miss proc is gone; obj 0 is no longer the MISS sprite
                    assertTrue(
                        oam.oam[2].toInt() and 0x3FF != 384 || oam.oam[0].toInt() and 0xFF >= 160,
                        "$at: MISS sprite cleared from active OAM",
                    )
                }
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val PROC_SCR_CALL_DELAYED_ARG = 0x0859A110
        const val OBJ_MAP_ANIM_MISS = 0x089AC194
    }
}
