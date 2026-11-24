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
 * The idle chain of `ekrBattle` from `ekrBattleMain` to `ekrBattleTriggerNewRoundStart`
 * (`ekrBattleMain`, `ekrBattlePrepareDragonIntro`, `ekrBattleExecDragonIntro`,
 * `ekrBattlePostEkrDragonIntro`, `ekrBattleSetFlashingEffect`, `ekrBattleExecTriangleAtk`)
 * against `ram_trace_prologue_attack` f1498 -> f1506. Driven from the f1498 dump (idle
 * callback `ekrBattleMain`, timer 0x1E); per step one proc run, then the idle callback
 * (mapped to its decomp name through the nm file) and the proc `timer`, `side`, `counter`
 * are compared with the dump. The captured arms: no quote and no pre-event, no dragon
 * status, `gEkrInitialHitSide == gEkrInitPosReal`, no refresh, no triangle attack. The effects
 * `ekrBattleSetFlashingEffect` starts are a recording stand-in (their procs are not ported).
 */
class EkrBattleMainChainDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private val byName = mapOf(
        "ekrBattleMain" to IdleCb.MAIN,
        "ekrBattlePrepareDragonIntro" to IdleCb.PREPARE_DRAGON_INTRO,
        "ekrBattleExecDragonIntro" to IdleCb.EXEC_DRAGON_INTRO,
        "ekrBattlePostEkrDragonIntro" to IdleCb.POST_DRAGON_INTRO,
        "ekrBattleSetFlashingEffect" to IdleCb.SET_FLASHING_EFFECT,
        "ekrBattleExecTriangleAtk" to IdleCb.EXEC_TRIANGLE_ATK,
        "ekrBattleTriggerNewRoundStart" to IdleCb.TRIGGER_NEW_ROUND_START,
    )

    @Test
    @VerifiesPort(
        "ekrBattleMain", "ekrBattlePrepareDragonIntro", "ekrBattleExecDragonIntro",
        "ekrBattlePostEkrDragonIntro", "ekrBattleSetFlashingEffect", "ekrBattleExecTriangleAtk",
    )
    fun `idle chain f1496 to f1506`() {
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

        val start = at(START)
        val anims = Array<Anim?>(4) { Anim() }
        for (i in 0 until 4) {
            val p = u32(start, G_ANIMS + 4 * i)
            anims[i]!!.state2 = u16(start, p + 0x0C)
        }
        val env = object : EkrBattleRound.Env {
            override val initialHitSide = s16(start, INITIAL_HIT_SIDE)
            override val initPosReal = u32(start, INIT_POS_REAL)
            override val banimLinkArenaFlag = u32(start, LINK_ARENA_FLAG)
            override val triangleUnitPresent = u32(start, TRIANGLE_UNITS) != 0
            override val battleConfigRefresh = u16(start, BATTLE_STATS) and CONFIG_REFRESH != 0
            override val distanceType = 0
            override val bButtonHeld = false
            override val battleAnimArenaFlag = false
            override val hpBarCount = 0
            override val deadEventExist = 0
            override val namewinAppearUnexist = true
            override val dragonDead = false
            override val banimExpGain = IntArray(2)
            override var ctrlC01Blocking = 0
        }
        val effects = ArrayList<String>()
        val hooks = object : EkrBattleRound.Hooks {
            override fun newEfxStatusUnit(anim: Anim) {
                effects += "status ${anims.indexOf(anim)}"
            }

            override fun newEfxWeaponIcon() {
                effects += "icon"
            }

            override fun disableEfxStatusUnits(anim: Anim) {
                effects += "disable"
            }

            override fun newEfxHpBarColorChange(anim: Anim) {
                effects += "color ${anims.indexOf(anim)}"
            }
        }
        val dragon = IntArray(2) { u8(start, DRAGON_STATUS + 0x10 * it) }
        val round = EkrBattleRound(anims, booleanArrayOf(true, true), IntArray(2), env, hooks, dragon)

        fun idle(ram: ByteArray) = funcs[u32(ram, u32(ram, PROC_EKR_BATTLE) + 0x0C)]
        val procStart = u32(start, PROC_EKR_BATTLE)
        assertEquals(0x1E, s16(start, procStart + 0x2C), "start timer")
        // f1496: `ekrBattle_Init` is pending; f1497 (it ran, `Proc_Break`) and f1498 (script
        // enters the repeat step of `ekrBattleMain`, which runs from the next step): no idle
        // callback yet, timer 0x1E.
        assertEquals("ekrBattle_Init", idle(at(START - 2)), "f1496 idle")
        for (st in START - 1..START) {
            val r = at(st)
            assertEquals(null, idle(r), "f$st idle")
            assertEquals(0x1E, s16(r, u32(r, PROC_EKR_BATTLE) + 0x2C), "f$st timer")
        }
        round.procTimer = s16(start, procStart + 0x2C)
        round.procIsQuote = u32(start, procStart + 0x54)
        round.procUnk58 = u32(start, procStart + 0x58)

        for (step in START + 1..END) {
            round.runIdle()
            val ram = at(step)
            val proc = u32(ram, PROC_EKR_BATTLE)
            assertEquals(byName[idle(ram)], round.idleCb, "f$step idle callback ${idle(ram)}")
            assertEquals(s16(ram, proc + 0x2C), round.procTimer, "f$step proc timer")
            if (step in 1500..1502) {
                assertEquals(u32(ram, proc + 0x48), round.procCounter, "f$step counter")
                assertEquals(u32(ram, proc + 0x44), round.procSide, "f$step side")
            }
        }
        assertEquals(listOf("status 0", "status 2", "icon", "color 0"), effects)
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1498
        const val END = 1506
        const val EWRAM = 0x02000000
        const val G_ANIMS = 0x02000000
        const val PROC_EKR_BATTLE = 0x02000064
        const val INIT_POS_REAL = 0x02017744
        const val DRAGON_STATUS = 0x02020048
        const val BATTLE_STATS = 0x0203A4D4
        const val LINK_ARENA_FLAG = 0x0203E0F0
        const val INITIAL_HIT_SIDE = 0x0203E100
        const val TRIANGLE_UNITS = 0x0203E194
        const val CONFIG_REFRESH = 1 shl 6
    }
}
