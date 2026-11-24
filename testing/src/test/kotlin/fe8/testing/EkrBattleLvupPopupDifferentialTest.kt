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
 * `ekrBattleLvupHanlder` (`c < 100` arm), `ekrNewEkrPopup`, `ekrBattle_WaitForPopup` (ended arm),
 * `ekrBattle_PostPopup`, `ekrBattle_TriggerDragonStatusFinished` (default arms) and
 * `ekrBattle_PostDragonStatusEffect` (debug mode 0) against `ram_trace_prologue_attack`
 * f1941 -> f1989 (BA-p5c). Dump N holds the state the callback of step N starts from, so per
 * step the globals come from the dump of the step before, one proc run follows, and the idle
 * callback (mapped through the nm file), the proc timer and, once `ekrBattle_PostPopup` has set
 * them, `side` and `counter` are compared with dump N. Popup, namewin and BGM effects go to
 * recording hooks; the test asserts their counts and the flag/exist changes in the dumps.
 */
class EkrBattleLvupPopupDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private val byName = mapOf(
        "ekrBattleLvupHanlder" to IdleCb.LVUP_HANDLER,
        "ekrNewEkrPopup" to IdleCb.NEW_EKR_POPUP,
        "ekrBattle_WaitForPopup" to IdleCb.WAIT_FOR_POPUP,
        "ekrBattle_PostPopup" to IdleCb.POST_POPUP,
        "ekrBattle_TriggerDragonStatusFinished" to IdleCb.TRIGGER_DRAGON_STATUS_FINISHED,
        "ekrBattle_PostDragonStatusEffect" to IdleCb.POST_DRAGON_STATUS_EFFECT,
    )

    @Test
    @VerifiesPort(
        "ekrBattleLvupHanlder",
        "ekrNewEkrPopup",
        "ekrBattle_WaitForPopup",
        "ekrBattle_PostPopup",
        "ekrBattle_TriggerDragonStatusFinished",
        "ekrBattle_PostDragonStatusEffect",
    )
    fun `lvup handler to post dragon status f1941 to f1989`() {
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
        fun side(ram: ByteArray) = u32(ram, u32(ram, PROC_EKR_BATTLE) + 0x44)
        fun counter(ram: ByteArray) = u32(ram, u32(ram, PROC_EKR_BATTLE) + 0x48)

        val start = at(START)
        assertEquals("ekrBattleLvupHanlder", idle(start), "start idle")
        var prev = start
        val expGain = IntArray(2)
        val expPrevious = IntArray(2)
        var popupEnded = false
        var initialHitSide = 0
        val env = object : EkrBattleRound.Env {
            override val initialHitSide get() = initialHitSide
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
            override val popupEnded get() = popupEnded
            override val debugMode = 0
        }
        val calls = ArrayList<String>()
        val hooks = object : EkrBattleRound.Hooks {
            override fun newEfxStatusUnit(anim: Anim) = Unit
            override fun newEfxWeaponIcon() = Unit
            override fun disableEfxStatusUnits(anim: Anim) = Unit
            override fun newEfxHpBarColorChange(anim: Anim) = Unit
            override fun clearLvupBg(dragonPriority: Boolean) {
                calls += "clearBg($dragonPriority)"
            }
            override fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {
                calls += "box($left,$top,$right,$bottom)"
            }
            override fun newEkrPopup() {
                calls += "popup"
            }
            override fun deleteAnimsOnPopup() {
                calls += "deleteAnims"
            }
            override fun endProcEfxWeaponIcon() {
                calls += "endWeaponIcon"
            }
            override fun endEfxHpBarColorChange() {
                calls += "endHpBarColor"
            }
            override fun newEkrNamewinAppear(a: Int, b: Int, c: Int) {
                calls += "namewin($a,$b,$c)"
            }
            override fun ekrRestoreBgm() {
                calls += "restoreBgm"
            }
        }
        val anims = Array<Anim?>(4) { Anim() }
        val round = EkrBattleRound(anims, booleanArrayOf(true, true), IntArray(2), env, hooks)
        round.idleCb = IdleCb.LVUP_HANDLER
        round.procTimer = timer(start)

        for (step in START + 1..END + 1) {
            expGain[0] = s16(prev, EXP_GAIN)
            expGain[1] = s16(prev, EXP_GAIN + 2)
            expPrevious[0] = s16(prev, EXP_GAIN - 4)
            expPrevious[1] = s16(prev, EXP_GAIN - 2)
            popupEnded = u32(prev, POPUP_ENDED) != 0
            initialHitSide = u16(prev, INITIAL_HIT_SIDE)
            round.runIdle()
            val ram = at(step)
            if (step <= END) {
                assertEquals(byName[idle(ram)], round.idleCb, "f$step idle ${idle(ram)}")
                assertEquals(timer(ram), round.procTimer, "f$step proc timer")
                if (round.idleCb == IdleCb.TRIGGER_DRAGON_STATUS_FINISHED ||
                    round.idleCb == IdleCb.POST_DRAGON_STATUS_EFFECT
                ) {
                    assertEquals(side(ram), round.procSide, "f$step proc side")
                    assertEquals(counter(ram), round.procCounter, "f$step proc counter")
                }
            } else {
                // ekrBattle is gone; nullsub_69 is the callback it leaves.
                assertEquals(IdleCb.NULLSUB_69, round.idleCb, "end idle")
                assertEquals(u32(ram, BATTLE_END_FLAG), round.battleEndFlag, "end flag")
                assertEquals(0, u32(prev, BATTLE_END_FLAG), "end flag before")
                assertEquals(0, u32(prev, NAMEWIN_EXIST), "namewin exist before")
                assertEquals(1, u32(ram, NAMEWIN_EXIST), "namewin exist after")
            }
            prev = ram
        }
        assertEquals(
            listOf(
                "clearBg(false)",
                "box(0,0,240,160)",
                "popup",
                "deleteAnims",
                "endWeaponIcon",
                "endHpBarColor",
                "namewin(2,7,0)",
                "restoreBgm",
            ),
            calls,
        )
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1941
        const val END = 1988
        const val EWRAM = 0x02000000
        const val PROC_EKR_BATTLE = 0x02000064
        const val EXP_GAIN = 0x0203E1C8
        const val BATTLE_END_FLAG = 0x02017724
        const val NAMEWIN_EXIST = 0x0201FAD0
        const val POPUP_ENDED = 0x02020144
        const val INITIAL_HIT_SIDE = 0x0203E100
    }
}
