package fe8.testing

import fe8.game.bm.EkrBattleRound
import fe8.game.bm.EkrBattleRound.IdleCb
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.Anim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The round part of the `ekrBattle` idle chain, `ekrBattleTriggerNewRoundStart` (tail),
 * `ekrBattle_80503EC`, `ekrBattle_StartPromotion`, `ekrBattleInRoundIdle`,
 * `ekrBattleOnBattleEnd` and `ekrBattle_8050600`, against `ram_trace_prologue_attack`
 * f1506 -> f1831 (BA-p4d). The port is loaded from the f1506 dump (idle callback
 * `ekrBattleTriggerNewRoundStart`, proc timer); per step the state the callback reads
 * (`gBanimDoneFlag`, `gEkrHpBarCount`, `gEkrDeadEventExist`, `gBanimExpGain`, distance type,
 * `gCtrlC01Blocking`) comes from the dump of the step before, one proc run follows, and the
 * idle callback (mapped to its decomp name through the nm file), the proc timer, `speedup`
 * and, at the step of `ekrBattle_80503EC`, `gCtrlC01Blocking` are compared with the dump. The
 * anim scripts' writes to `gBanimDoneFlag` (f1819, f1828) are not ported; the dump supplies
 * them.
 *
 * Stand-ins: B button (not held), `GetBattleAnimArenaFlag` (0), `CheckEkrNamewinAppearUnexist`
 * (true) and `CheckEkrDragonDead` (false). The two steps where the HP-bar proc did not advance
 * (f1582, f1789, BA-cap3) are skipped explicitly: the port is not run and the idle callback is
 * only asserted equal to the step before.
 */
class EkrBattleRoundIdleDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private val byName = mapOf(
        "ekrBattleTriggerNewRoundStart" to IdleCb.TRIGGER_NEW_ROUND_START,
        "ekrBattle_80503EC" to IdleCb.ROUND_START_80503EC,
        "ekrBattle_StartPromotion" to IdleCb.START_PROMOTION,
        "ekrBattleInRoundIdle" to IdleCb.IN_ROUND_IDLE,
        "ekrBattleOnBattleEnd" to IdleCb.ON_BATTLE_END,
        "ekrBattle_8050600" to IdleCb.BATTLE_END_8050600,
        "ekrBattle_WaitForPostBattleAct" to IdleCb.WAIT_FOR_POST_BATTLE_ACT,
    )

    @Test
    @VerifiesPort(
        "ekrBattleTriggerNewRoundStart", "ekrBattle_80503EC", "ekrBattle_StartPromotion",
        "ekrBattleInRoundIdle", "ekrBattleOnBattleEnd", "ekrBattle_8050600",
    )
    fun `idle chain f1506 to f1831`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val nm = ProcArrayDump.nmFile()
        assumeTrue(nm != null, "decomp nm file not present")
        val funcs = HashMap<Int, String>()
        nm!!.forEachLine {
            val p = it.split(" ")
            if (p.size >= 4 && p[2] == "T") funcs[p[0].toLong(16).toInt() or 1] = p[3]
        }
        val capture = ref.run(RUN)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        fun at(step: Int) = capture.region(byStep.getValue(step), "ewram")
        fun idle(ram: ByteArray) = funcs[u32(ram, u32(ram, PROC_EKR_BATTLE) + 0x0C)]
        fun timer(ram: ByteArray) = s16(ram, u32(ram, PROC_EKR_BATTLE) + 0x2C)

        val start = at(START)
        assertEquals("ekrBattleTriggerNewRoundStart", idle(start), "start idle")
        val anims = Array<Anim?>(4) { Anim() }
        for (i in 0 until 4) {
            anims[i]!!.state2 = u16(start, u32(start, G_ANIMS + 4 * i) + 0x0C)
        }
        var prev = start
        val expGain = IntArray(2)
        val env = object : EkrBattleRound.Env {
            override val initialHitSide = 0
            override val initPosReal get() = u32(prev, INIT_POS_REAL)
            override val banimLinkArenaFlag = 0
            override val triangleUnitPresent = false
            override val battleConfigRefresh = false
            override val distanceType get() = u16(prev, DISTANCE_TYPE)
            override val bButtonHeld = false
            override val battleAnimArenaFlag = false
            override val hpBarCount get() = u32(prev, HP_BAR_COUNT)
            override val deadEventExist get() = u32(prev, DEAD_EVENT_EXIST)
            override val namewinAppearUnexist = true
            override val dragonDead = false
            override val banimExpGain get() = expGain
            override var ctrlC01Blocking = 0
        }
        val done = IntArray(2)
        val round = EkrBattleRound(anims, booleanArrayOf(true, true), done, env)
        round.idleCb = IdleCb.TRIGGER_NEW_ROUND_START
        round.procTimer = timer(start)
        assertNotEquals(0, u32(at(ROUND_START_STEP), CTRL_C01_BLOCKING), "ctrl not vacuous")

        for (step in START + 1..END) {
            val ram = at(step)
            if (step in UNADVANCED) {
                // Not run (BA-cap3); the idle callback must not move either.
                assertEquals(idle(prev), idle(ram), "f$step idle unmoved")
                prev = ram
                continue
            }
            done[0] = u32(prev, DONE_FLAG)
            done[1] = u32(prev, DONE_FLAG + 4)
            expGain[0] = u16(prev, EXP_GAIN)
            expGain[1] = u16(prev, EXP_GAIN + 2)
            env.ctrlC01Blocking = u32(prev, CTRL_C01_BLOCKING)
            round.runIdle()
            assertEquals(byName[idle(ram)], round.idleCb, "f$step idle ${idle(ram)}")
            assertEquals(timer(ram), round.procTimer, "f$step proc timer")
            assertEquals(
                u8(ram, u32(ram, PROC_EKR_BATTLE) + 0x29) != 0,
                round.procSpeedup,
                "f$step speedup",
            )
            if (step == ROUND_START_STEP + 1) {
                assertEquals(u32(ram, CTRL_C01_BLOCKING), env.ctrlC01Blocking, "f$step ctrl")
            }
            if (step == ROUND_START_STEP) assertEquals(0, done[0] + done[1], "f$step done reset")
            prev = ram
        }
        assertEquals(IdleCb.WAIT_FOR_POST_BATTLE_ACT, round.idleCb, "end idle")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1506
        const val ROUND_START_STEP = 1537
        const val END = 1831
        val UNADVANCED = setOf(1582, 1789)
        const val EWRAM = 0x02000000
        const val G_ANIMS = 0x02000000
        const val CTRL_C01_BLOCKING = 0x02000024
        const val PROC_EKR_BATTLE = 0x02000064
        const val HP_BAR_COUNT = 0x02017728
        const val DEAD_EVENT_EXIST = 0x02017738
        const val INIT_POS_REAL = 0x02017744
        const val DONE_FLAG = 0x0201FB04
        const val DISTANCE_TYPE = 0x0203E120
        const val EXP_GAIN = 0x0203E1C8
    }
}
