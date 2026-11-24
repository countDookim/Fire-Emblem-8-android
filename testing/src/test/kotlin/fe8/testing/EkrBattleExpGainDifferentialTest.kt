package fe8.testing

import fe8.game.bm.EkrBattleRound
import fe8.game.bm.EkrBattleRound.IdleCb
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.Anim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `ekrBattleOnBattleEnd`, `ekrBattle_8050600`, `ekrBattle_WaitForPostBattleAct` and the control
 * state of `ekrBattleExecExpGain` against `ram_trace_prologue_attack` f1829 -> f1862 (BA-p5a).
 * The port is loaded from the f1829 dump; per step the globals the callbacks read come from the
 * dump of the step before, one proc run follows, and the idle callback (mapped through the nm
 * file), the proc timer and `speedup` are compared with the dump. The exp-bar draw goes to a
 * recording hook (it is not in the dumps); the test only asserts it ran once, with the value of
 * `gBanimExpPrevious[R]`.
 *
 * Stand-ins: `GetBanimLinkArenaFlag` and `GetBanimDragonStatusType` (0),
 * `CheckEkrNamewinAppearUnexist` (true) and `CheckEkrDragonDead` (false).
 */
class EkrBattleExpGainDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private val byName = mapOf(
        "ekrBattleOnBattleEnd" to IdleCb.ON_BATTLE_END,
        "ekrBattle_8050600" to IdleCb.BATTLE_END_8050600,
        "ekrBattle_WaitForPostBattleAct" to IdleCb.WAIT_FOR_POST_BATTLE_ACT,
        "ekrBattleExecExpGain" to IdleCb.EXEC_EXP_GAIN,
        "ekrBattle_80508F0" to IdleCb.EXP_80508F0,
    )

    @Test
    @VerifiesPort("ekrBattle_WaitForPostBattleAct", "ekrBattleExecExpGain")
    fun `post battle wait and exp gain f1829 to f1862`() {
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
        assertEquals("ekrBattleOnBattleEnd", idle(start), "start idle")
        var prev = start
        val expGain = IntArray(2)
        val expPrevious = IntArray(2)
        val env = object : EkrBattleRound.Env {
            override val initialHitSide = 0
            override val initPosReal get() = u32(prev, INIT_POS_REAL)
            override val banimLinkArenaFlag = 0
            override val triangleUnitPresent = false
            override val battleConfigRefresh = false
            override val distanceType = 0
            override val bButtonHeld = false
            override val battleAnimArenaFlag = false
            override val hpBarCount get() = u32(prev, HP_BAR_COUNT)
            override val deadEventExist get() = u32(prev, DEAD_EVENT_EXIST)
            override val namewinAppearUnexist = true
            override val dragonDead = false
            override val banimExpGain get() = expGain
            override var ctrlC01Blocking = 0
            override val banimExpPrevious get() = expPrevious
        }
        val drawn = ArrayList<Pair<Int, Boolean>>()
        val hooks = object : EkrBattleRound.Hooks {
            override fun newEfxStatusUnit(anim: Anim) = Unit
            override fun newEfxWeaponIcon() = Unit
            override fun disableEfxStatusUnits(anim: Anim) = Unit
            override fun newEfxHpBarColorChange(anim: Anim) = Unit
            override fun drawExpBar(expValue: Int, dragonPriority: Boolean) {
                drawn += expValue to dragonPriority
            }
        }
        val anims = Array<Anim?>(4) { Anim() }
        val round = EkrBattleRound(anims, booleanArrayOf(true, true), IntArray(2), env, hooks)
        round.idleCb = IdleCb.ON_BATTLE_END
        round.procTimer = timer(start)

        for (step in START + 1..END) {
            val ram = at(step)
            expGain[0] = s16(prev, EXP_GAIN)
            expGain[1] = s16(prev, EXP_GAIN + 2)
            expPrevious[0] = s16(prev, EXP_GAIN - 4)
            expPrevious[1] = s16(prev, EXP_GAIN - 2)
            round.runIdle()
            assertEquals(byName[idle(ram)], round.idleCb, "f$step idle ${idle(ram)}")
            assertEquals(timer(ram), round.procTimer, "f$step proc timer")
            assertEquals(
                u8(ram, u32(ram, PROC_EKR_BATTLE) + 0x29) != 0,
                round.procSpeedup,
                "f$step speedup",
            )
            prev = ram
        }
        assertEquals(IdleCb.EXP_80508F0, round.idleCb, "end idle")
        assertEquals(listOf(expPrevious[1] to false), drawn, "exp bar drawn once from prev[R]")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1829
        const val END = 1862
        const val EWRAM = 0x02000000
        const val PROC_EKR_BATTLE = 0x02000064
        const val HP_BAR_COUNT = 0x02017728
        const val DEAD_EVENT_EXIST = 0x02017738
        const val INIT_POS_REAL = 0x02017744
        const val EXP_GAIN = 0x0203E1C8
    }
}
