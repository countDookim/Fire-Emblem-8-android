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
 * `ekrBattle_80508F0`, `ekrBattle_8050940` (gain[R] arm), `ekrBattleWaitExpBarIdle`,
 * `ekrBattlePostExpBarIdle` and `ekrBattle_8050AB8` against `ram_trace_prologue_attack`
 * f1862 -> f1941 (BA-p5b). Loaded from the f1862 dump (idle `ekrBattle_80508F0`); per step the
 * exp globals come from the dump of the step before, one proc run follows, and the idle callback
 * (mapped through the nm file), the proc timer and, once `ekrBattle_8050940` has set it, `end`
 * are compared with the dump. Window boxes, songs and bar redraws go to recording hooks (not in
 * the dumps); the test asserts only their counts.
 */
class EkrBattleExpBarDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private val byName = mapOf(
        "ekrBattleExecExpGain" to IdleCb.EXEC_EXP_GAIN,
        "ekrBattle_80508F0" to IdleCb.EXP_80508F0,
        "ekrBattle_8050940" to IdleCb.EXP_8050940,
        "ekrBattleWaitExpBarIdle" to IdleCb.WAIT_EXP_BAR_IDLE,
        "ekrBattlePostExpBarIdle" to IdleCb.POST_EXP_BAR_IDLE,
        "ekrBattle_8050AB8" to IdleCb.EXP_8050AB8,
        "ekrBattleLvupHanlder" to IdleCb.LVUP_HANDLER,
    )

    @Test
    @VerifiesPort(
        "ekrBattle_80508F0",
        "ekrBattle_8050940",
        "ekrBattleWaitExpBarIdle",
        "ekrBattlePostExpBarIdle",
        "ekrBattle_8050AB8",
    )
    fun `exp bar chain f1862 to f1941`() {
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
        fun end(ram: ByteArray) = s16(ram, u32(ram, PROC_EKR_BATTLE) + 0x2E)

        val start = at(START)
        assertEquals("ekrBattle_80508F0", idle(start), "start idle")
        var prev = start
        val expGain = IntArray(2)
        val expPrevious = IntArray(2)
        val env = object : EkrBattleRound.Env {
            override val initialHitSide = 0
            override val initPosReal = 1
            override val banimLinkArenaFlag = 0
            override val triangleUnitPresent = false
            override val battleConfigRefresh = false
            override val distanceType = 0
            override val bButtonHeld = false
            override val battleAnimArenaFlag = false
            override val hpBarCount = 0
            override val deadEventExist = 0
            override val namewinAppearUnexist = true
            override val dragonDead = false
            override val banimExpGain get() = expGain
            override var ctrlC01Blocking = 0
            override val banimExpPrevious get() = expPrevious
        }
        var boxes = 0
        var songsStarted = 0
        var songsStopped = 0
        val barValues = ArrayList<Int>()
        val hooks = object : EkrBattleRound.Hooks {
            override fun newEfxStatusUnit(anim: Anim) = Unit
            override fun newEfxWeaponIcon() = Unit
            override fun disableEfxStatusUnits(anim: Anim) = Unit
            override fun newEfxHpBarColorChange(anim: Anim) = Unit
            override fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {
                boxes++
            }
            override fun startExpBarSong() {
                songsStarted++
            }
            override fun stopExpBarSong() {
                songsStopped++
            }
            override fun drawExpBarValue(exp: Int) {
                barValues += exp
            }
        }
        val anims = Array<Anim?>(4) { Anim() }
        val round = EkrBattleRound(anims, booleanArrayOf(true, true), IntArray(2), env, hooks)
        round.idleCb = IdleCb.EXP_80508F0
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
            if (round.idleCb == IdleCb.WAIT_EXP_BAR_IDLE ||
                round.idleCb == IdleCb.POST_EXP_BAR_IDLE
            ) {
                assertEquals(end(ram), round.procEnd, "f$step proc end")
            }
            prev = ram
        }
        assertEquals(IdleCb.LVUP_HANDLER, round.idleCb, "end idle")
        assertEquals(12 + 12, boxes, "window boxes")
        assertEquals(1, songsStarted, "song start")
        assertEquals(1, songsStopped, "song stop")
        assertEquals(
            (expPrevious[1]..expPrevious[1] + expGain[1]).toList(),
            barValues,
            "bar values",
        )
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1862
        const val END = 1941
        const val EWRAM = 0x02000000
        const val PROC_EKR_BATTLE = 0x02000064
        const val EXP_GAIN = 0x0203E1C8
    }
}
