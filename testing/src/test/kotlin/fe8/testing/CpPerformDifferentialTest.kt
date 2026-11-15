package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.MuProc
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The movement half of the AI perform proc (EP-8, src/cp_perform.c) against the per-frame RAM of
 * two captures: `ram_trace_prologue_enemy_phase` (Soldier 1 f1614..1695 and Soldier 2
 * f2039..2128: walk to Eirika, camera, target cursor) and `ram_trace_prologue_clear` (the boss's
 * move-only walk f3804..3831: no target cursor).
 *
 * `CpDecide` runs for one unit with `aiDecideMainFunc` replaced by the captured `gAiDecision` of
 * the decision frame (the decision code is EP-3a..EP-7; for the boss it is EP-7d, which is not
 * ported, so its decision is input here), then `gProcScr_CpPerform` and `gProcScr_AiTargetCursor`
 * run for real: `CpDecide` blocks on the perform proc as in the C. The state at the frame before
 * (units, `gPlaySt`, `gBmSt`) and the RNG state after the decision's draw are the capture's. The
 * action half (EP-9) is a stand-in, so the comparisons stop at the last walk frame (f1695, f2128
 * and f3831 show `CpPerform` just before its action). Engine frame k is compared with capture
 * step `decision frame + 1 + k` (see [walk]).
 *
 * Compared per frame: `gBmSt` camera, the `CpPerform`, `ProcScr_Mu` and `gProcScr_AiTargetCursor`
 * procs (existence, script position, MU position/state/facing/clock fields, cursor x, y, kind and
 * timer), the `sMuConfig[0]` position, `ProcScr_CamMove` existence, `gAiDecision` move tile,
 * `gActionData` move tile, subject and type, the walking unit's state word and the map cursor's
 * `sLastCoordMapCursorDrawn` / `sLastTimeMapCursorDrawn`. Inputs from outside the port: the
 * sprite palette of the MU (the sprite handle table is not part of this test) and the game clock
 * of the dump that `PutMapCursor` reads.
 */
class CpPerformDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun sim(run: String, seed: Int, unit: Int): CpPerformSim =
        CpPerformSim.create(ref, romFile, run, seed, unit)

    /** Runs the frames [first]..[last] and compares each with its capture frame. */
    private fun walk(s: CpPerformSim, first: Int, last: Int, cursorFrames: IntRange) {
        val names = ProcArrayDump.romNames()
        var sawCursor = 0
        // Engine frame k shows capture step first + 1 + k. The dump of the decision step [first]
        // is a snapshot inside the frame that decides and starts the walk (Soldier 1 and the boss
        // show the new MU before its first loop, Soldier 2 after it), so it is not compared; every
        // later step is one engine frame.
        for (f in first + 1..last) {
            s.cursor.gameClock = {
                GbaMem.u32(s.iwram(f), GbaMem.IWRAM, CpPerformSim.GAME_CLOCK) + CLOCK_AT_PUT
            }
            s.frame()
            compare(s, f, names)
            val cursor = s.engine.find(s.perform.targetCursorScript)
            if (cursor != null) sawCursor++
        }
        assertEquals(cursorFrames.count(), sawCursor, "frames with a target cursor proc")
    }

    private fun compare(s: CpPerformSim, f: Int, names: Map<Int, String>) {
        val at = "f$f"
        val want = BmSt().also { it.decode(s.data.watch("gbmst").step(f), 0) }
        assertEquals(want.cameraX, s.bm.cameraX, "gBmSt camera.x $at")
        assertEquals(want.cameraY, s.bm.cameraY, "gBmSt camera.y $at")
        val dump = ProcArrayDump.ofWatch(s.data.watch("procs"), f, names)

        val perform = dump.find(PROC_SCR_CP_PERFORM)
        val ours = s.engine.find(s.perform.script)
        assertEquals(perform != null, ours != null, "gProcScr_CpPerform exists $at")
        if (perform != null) {
            assertEquals(perform.scrCur, ours!!.scrCur, "gProcScr_CpPerform script position $at")
        }

        assertEquals(
            dump.find(PROC_SCR_CAM_MOVE) != null, s.engine.find(s.camMove.script) != null,
            "ProcScr_CamMove exists $at",
        )

        val cursor = dump.find(PROC_SCR_AI_TARGET_CURSOR)
        val cursorOurs = s.engine.find(s.perform.targetCursorScript)
        assertEquals(cursor != null, cursorOurs != null, "gProcScr_AiTargetCursor exists $at")
        if (cursor != null) {
            val c = cursorOurs!!.state as fe8.game.bm.AiTargetCursorState
            assertEquals(cursor.u32(0x2C), c.x, "AiTargetCursor x $at")
            assertEquals(cursor.u32(0x30), c.y, "AiTargetCursor y $at")
            assertEquals(cursor.u32(0x58), c.kind, "AiTargetCursor kind $at")
            assertEquals(cursor.u16(0x64).toShort().toInt(), c.timer, "AiTargetCursor timer $at")
            assertEquals(cursor.scrCur, cursorOurs.scrCur, "AiTargetCursor script position $at")
        }

        val mu = dump.find(PROC_SCR_MU)
        val muOurs = s.engine.find(s.mu.script)
        assertEquals(mu != null, muOurs != null, "ProcScr_Mu exists $at")
        if (mu != null) {
            val m = muOurs!!.state as MuProc
            assertEquals(mu.u8(0x3F), m.state, "MU state $at")
            assertEquals(mu.u8(0x42), m.facing, "MU facing $at")
            assertEquals(mu.u8(0x43), m.stepSoundClock, "MU step_sound_clock $at")
            assertEquals(mu.u16(0x48), m.moveClockQ4, "MU move_clock_q4 $at")
            assertEquals(mu.u16(0x4A), m.moveConfig and 0xFFFF, "MU moveConfig $at")
            assertEquals(mu.u16(0x4C), m.xQ4 and 0xFFFF, "MU x_q4 $at")
            assertEquals(mu.u16(0x4E), m.yQ4 and 0xFFFF, "MU y_q4 $at")
            assertEquals(mu.u8(0x3E), if (m.camB) 1 else 0, "MU cam_b $at")
            assertEquals(
                s.iwram(f)[CpPerformSim.CFG - GbaMem.IWRAM + 4].toInt() and 0xFF,
                s.mu.configs[0].pc, "sMuConfig[0].pc $at",
            )
        }

        val decision = s.data.watch("aidecision")
        assertEquals(decision.u8(f, 2), s.decide.decision.xMove, "gAiDecision.xMove $at")
        assertEquals(decision.u8(f, 3), s.decide.decision.yMove, "gAiDecision.yMove $at")
        val action = s.data.watch("actiondata")
        val a = s.active.action
        assertEquals(action.u8(f, 0x0C), a.subjectIndex, "gActionData.subjectIndex $at")
        assertEquals(action.u8(f, 0x0E), a.xMove, "gActionData.xMove $at")
        assertEquals(action.u8(f, 0x0F), a.yMove, "gActionData.yMove $at")
        assertEquals(action.u8(f, 0x10), a.moveCount, "gActionData.moveCount $at")
        assertEquals(action.u8(f, 0x11), a.unitActionType, "gActionData.unitActionType $at")

        val red = s.data.watch("red")
        val o = (s.unitId - 0x81) * 0x48
        assertEquals(red.u32(f, o + 0x0C), s.units.state(s.unitId), "unit state $at")

        val e = s.ewram(f)
        assertEquals(
            GbaMem.u16(e, GbaMem.EWRAM, CpPerformSim.LAST_COORD).toShort().toInt(),
            s.cursor.lastCoordX, "sLastCoordMapCursorDrawn.x $at",
        )
        assertEquals(
            GbaMem.u16(e, GbaMem.EWRAM, CpPerformSim.LAST_COORD + 2).toShort().toInt(),
            s.cursor.lastCoordY, "sLastCoordMapCursorDrawn.y $at",
        )
        assertEquals(
            GbaMem.u32(e, GbaMem.EWRAM, CpPerformSim.LAST_TIME), s.cursor.lastTimeDrawn,
            "sLastTimeMapCursorDrawn $at",
        )
    }

    @Test
    @VerifiesPort(
        "CpPerform_BeginUnitMovement",
        "CpPerform_MoveCameraOntoTarget", "StartAiTargetCursor", "AiTargetCursor_Main",
        "HaltMu", "Mu_OnStateDoNothing",
    )
    fun `Soldier 1 walks and the target cursor shows as the capture f1614 to f1695`() {
        val s = sim(ENEMY_PHASE, 1613, 0x82)
        walk(s, 1614, 1695, 1648..1694)
        assertNotNull(s.engine.find(s.perform.script), "perform proc waits for the action")
    }

    @Test
    @VerifiesPort(
        "CpPerform_BeginUnitMovement",
        "CpPerform_MoveCameraOntoTarget", "StartAiTargetCursor", "AiTargetCursor_Main",
        "HaltMu", "Mu_OnStateDoNothing",
    )
    fun `Soldier 2 walks and the target cursor shows as the capture f2039 to f2128`() {
        val s = sim(ENEMY_PHASE, 2038, 0x83)
        walk(s, 2039, 2128, 2081..2127)
    }

    @Test
    @VerifiesPort(
        "CpPerform_BeginUnitMovement",
        "CpPerform_MoveCameraOntoTarget", "HaltMu", "Mu_OnStateDoNothing",
    )
    fun `the boss moves without a target cursor as the capture f3804 to f3831`() {
        val s = sim(CLEAR, 3803, 0x81)
        walk(s, 3804, 3831, IntRange.EMPTY)
    }

    private companion object {
        const val ENEMY_PHASE = "ram_trace_prologue_enemy_phase"
        const val CLEAR = "ram_trace_prologue_clear"
        const val PROC_SCR_CP_PERFORM = 0x085A8024
        const val PROC_SCR_AI_TARGET_CURSOR = 0x085A8004
        const val PROC_SCR_CAM_MOVE = 0x0859A548
        const val PROC_SCR_MU = 0x089A2C48

        /** `sLastTimeMapCursorDrawn` minus the `gGameClock` of the same dump (calibrated). */
        const val CLOCK_AT_PUT = 0
    }
}
