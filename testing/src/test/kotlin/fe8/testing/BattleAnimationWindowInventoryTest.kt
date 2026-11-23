package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, BA-0) of the two melee battle-animation windows: the
 * Eirika/Soldier attack `ram_trace_prologue_attack` (animations on, `sProcScr_CombatAction`
 * f1464..2010) and the scripted Seth/Valter fight `ram_trace_prologue_battle`
 * (f14401..15035). Boundaries come from the `procs` watch (spans of one proc script symbol:
 * first and last step it is alive, steps are 1-based like the dumps) and from the EWRAM dumps
 * (`gEkrGaugeHp`, `gEkrBattleEndFlag`, `gBanimDoneFlag`, `gBattleActor`/`gBattleTarget`
 * current HP). Which proc groups map to which BA-i row is in docs/plans/M6C-J-breakdown.md
 * ("BA-0 result"). No per-step LCD/palette/OAM exists in these runs; only every 10th step has a
 * full dump (`fNNNNN`), the rest are `rNNNNN` (EWRAM + IWRAM).
 */
class BattleAnimationWindowInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    /** Contiguous live spans per proc script symbol over [first]..[last], as `name first-last`. */
    private fun spans(run: String, first: Int, last: Int): Map<String, List<IntRange>> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val capture = ref.run(run)
        assumeTrue(capture.hasWatch("procs"), "$run has no procs watch")
        val trace = capture.watch("procs")
        val open = HashMap<String, IntArray>()
        val result = LinkedHashMap<String, MutableList<IntRange>>()
        for (step in first..last + 1) {
            val alive = if (step > last) {
                emptySet()
            } else {
                ProcArrayDump.ofWatch(trace, step, names).live
                    .map { it.name ?: "?%08x".format(it.script) }.toSet()
            }
            for ((name, seg) in open.entries.toList()) {
                if (name !in alive) {
                    result.getOrPut(name) { ArrayList() } += seg[0]..seg[1]
                    open.remove(name)
                }
            }
            for (name in alive) {
                val seg = open[name]
                if (seg == null) open[name] = intArrayOf(step, step) else seg[1] = step
            }
        }
        return result
    }

    /** `gEkrGaugeHp` (two HP halfwords: low = battle target side, high = actor side) per step. */
    private fun gaugeChanges(run: String, first: Int, last: Int): List<Pair<Int, Int>> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val capture = ref.run(run)
        val stepToDump = capture.dumps.entries.associate { it.value to it.key }
        val changes = ArrayList<Pair<Int, Int>>()
        var previous = -1
        for (step in first..last) {
            val ewram = capture.region(stepToDump.getValue(step), "ewram")
            val offset = GAUGE_HP - EWRAM
            val value = (ewram[offset].toInt() and 0xFF) or
                ((ewram[offset + 1].toInt() and 0xFF) shl 8) or
                ((ewram[offset + 2].toInt() and 0xFF) shl 16) or
                ((ewram[offset + 3].toInt() and 0xFF) shl 24)
            if (value != previous) {
                changes += step to value
                previous = value
            }
        }
        return changes
    }

    private fun flagAt(run: String, step: Int, address: Int): Int {
        val capture = ref.run(run)
        val dump = capture.dumps.entries.first { it.value == step }.key
        val ewram = capture.region(dump, "ewram")
        val offset = address - EWRAM
        return (ewram[offset].toInt() and 0xFF) or ((ewram[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun hp(run: String, step: Int, unit: Int): Int {
        val capture = ref.run(run)
        val dump = capture.dumps.entries.first { it.value == step }.key
        return capture.region(dump, "ewram")[unit + UNIT_CUR_HP - EWRAM].toInt() and 0xFF
    }

    private fun check(
        spans: Map<String, List<IntRange>>,
        name: String,
        vararg expected: IntRange,
    ) {
        assertEquals(expected.toList(), spans[name], name)
    }

    @Test
    fun `attack window ekr boundaries f1464 to f2010`() {
        val s = spans(ATTACK, 1464, 2010)
        // Map -> battle: ekrBattleStarting + deamon alive from the window start.
        check(s, "ProcScr_ekrBattleStarting", 1464..1495)
        check(s, "sProcScr_CombatAction", 1464..2010)
        check(s, "gProc_ekrBattleDeamon", 1464..2010)
        // Starting phase furniture: gauge/dispUP appear f1480, window/base/kakudai f1482.
        check(s, "ProcScr_ekrGauge", 1480..2008)
        check(s, "gProc_ekrDispUP", 1480..2000)
        check(s, "ProcScr_ekrUnitKakudai", 1482..1499, 1993..2010)
        // First round starts when ekrBattle takes over; ends at the ending proc.
        check(s, "gProc_ekrBattle", 1496..1988)
        check(s, "ProcScr_efxHPBarColorChange", 1505..1984)
        check(s, "ProcScr_efxWeaponIcon", 1505..1984)
        check(s, "ProcScr_efxStatusUnit", 1505..1994)
        // Three rounds, each one efxHPBar group (hit effects + HP bar).
        check(s, "ProcScr_efxHPBar", 1577..1611, 1659..1692, 1784..1818)
        check(s, "ProcScr_EfxHitQuake", 1577..1596, 1659..1677, 1784..1803)
        check(s, "ProcScr_efxNormalEffect", 1577..1602, 1659..1683, 1784..1809)
        check(s, "ProcScr_efxNormalEffectBG", 1581..1599, 1663..1680, 1788..1806)
        check(s, "ProcScr_efxFlashUnit", 1577..1586, 1659..1667, 1784..1793)
        // Battle -> map: ekrBattleEnding f1989, back to map f2010.
        check(s, "ProcScr_ekrBattleEnding", 1989..2010)
        check(s, "sProc_DelayedBMapDispResume", 2010..2010)
    }

    @Test
    fun `attack window gauge HP changes per round and end flags`() {
        val changes = gaugeChanges(ATTACK, 1464, 2010)
        // Three rounds: target half 0x16 -> 0xf, actor half 0x10 -> 7, target half 0xf -> 8.
        assertEquals(0x00100016, changes.first().second)
        assertEquals(1464, changes.first().first)
        val round1 = changes.filter { it.first in 1577..1611 }
        val round2 = changes.filter { it.first in 1659..1692 }
        val round3 = changes.filter { it.first in 1784..1818 }
        assertEquals(1579 to 0x00100015, round1.first())
        assertEquals(1592 to 0x0010000f, round1.last())
        assertEquals(1661 to 0x000f000f, round2.first())
        assertEquals(1677 to 0x0007000f, round2.last())
        assertEquals(1786 to 0x0007000e, round3.first())
        assertEquals(1799 to 0x00070008, round3.last())
        assertEquals(changes.size, round1.size + round2.size + round3.size + 1)
        // gBanimDoneFlag at f1819, gEkrBattleEndFlag at f1989.
        assertEquals(0, flagAt(ATTACK, 1818, DONE_FLAG))
        assertEquals(1, flagAt(ATTACK, 1819, DONE_FLAG))
        assertEquals(0, flagAt(ATTACK, 1988, END_FLAG))
        assertEquals(1, flagAt(ATTACK, 1989, END_FLAG))
        // gBattleActor/gBattleTarget hold the precomputed final HP for the whole window.
        assertEquals(7, hp(ATTACK, 1464, BATTLE_ACTOR))
        assertEquals(8, hp(ATTACK, 1464, BATTLE_TARGET))
    }

    @Test
    fun `scripted battle window ekr boundaries f14401 to f15035`() {
        val s = spans(BATTLE, 14401, 15035)
        // Scripted: ScriptBattleDeamon at f14430, then the ekr start phase.
        check(s, "ProcScr_ScriptBattleDeamon", 14430..15035)
        check(s, "ProcScr_ekrBattleStarting", 14431..14466)
        check(s, "gProc_ekrBattleDeamon", 14431..15035)
        check(s, "ProcScr_ekrGauge", 14448..15035)
        check(s, "gProc_ekrDispUP", 14448..15035)
        check(s, "gProc_ekrBattle", 14467..15034)
        // Battle quote talk inside the animation (BattleEventEngine owns the Talk procs).
        check(s, "ProcScr_BattleEventEngine", 14479..14630)
        check(s, "gProcScr_Talk", 14480..14613)
        check(s, "ProcScr_efxWeaponIcon", 14648..15030)
        check(s, "ProcScr_efxHPBarColorChange", 14648..15030)
        check(s, "ProcScr_efxStatusUnit", 14648..15035)
        // Round 1 is a miss: EfxAvoid and the damage-number procs, no efxHPBar.
        check(s, "ProcScr_EfxAvoid", 14730..14763)
        check(s, "ProcScr_efxDamageMojiEffect", 14730..14741)
        check(s, "ProcScr_ekrsubAnimeEmulator", 14731..14783)
        // Round 2 hits: efxHPBar group.
        check(s, "ProcScr_efxHPBar", 14890..14926)
        check(s, "ProcScr_EfxHitQuake", 14890..14908)
        check(s, "ProcScr_efxNormalEffect", 14890..14914)
        // Battle -> map: ekrBattleEnding at f15035 (window end; trace goes to f15059).
        check(s, "ProcScr_ekrBattleEnding", 15035..15035)
    }

    @Test
    fun `scripted battle gauge HP change and end flags`() {
        val changes = gaugeChanges(BATTLE, 14401, 15035).filter { it.first >= 14430 }
        // gEkrGaugeHp = target half 0x22, actor half 0x1e -> 0xd (damage 17), in round 2 only.
        assertEquals(14431 to 0x001e0022, changes.first())
        assertEquals(14892 to 0x001d0022, changes[1])
        assertEquals(14924 to 0x000d0022, changes.last())
        assertEquals(34, hp(BATTLE, 14430, BATTLE_TARGET))
        assertEquals(13, hp(BATTLE, 14430, BATTLE_ACTOR))
        assertEquals(0, flagAt(BATTLE, 14994, DONE_FLAG))
        assertEquals(1, flagAt(BATTLE, 14995, DONE_FLAG))
        assertEquals(0, flagAt(BATTLE, 15034, END_FLAG))
        assertEquals(1, flagAt(BATTLE, 15035, END_FLAG))
    }

    @Test
    fun `observed ekr and efx proc groups`() {
        val attack = spans(ATTACK, 1464, 2010).keys
        val battle = spans(BATTLE, 14401, 15035).keys
        val ekr = { n: String -> n.contains("ekr", true) || n.contains("efx", true) ||
            n.contains("Efx") }
        // Procs seen in both windows (core of BA-i1..i8).
        val common = (attack intersect battle).filter(ekr).toSortedSet()
        assertEquals(COMMON_EKR_EFX, common.toList())
        // Only the attack window: hit-flash/normal-effect BG layers; only the scripted one: miss.
        assertTrue("ProcScr_EfxAvoid" in battle && "ProcScr_EfxAvoid" !in attack)
        assertTrue("ProcScr_efxFlashBG" in attack && "ProcScr_efxFlashBG" in battle)
        // No death or level-up proc runs in either window (BA-death-*/BA-level-* need new CAPs).
        for (n in attack + battle) {
            assertTrue(!n.contains("Death", true) && !n.contains("lvup", true), n)
        }
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val BATTLE = "ram_trace_prologue_battle"
        const val EWRAM = 0x02000000
        const val GAUGE_HP = 0x0203e1ac // gEkrGaugeHp
        const val DONE_FLAG = 0x0201fb04 // gBanimDoneFlag
        const val END_FLAG = 0x02017724 // gEkrBattleEndFlag
        const val BATTLE_ACTOR = 0x0203a4ec // gBattleActor
        const val BATTLE_TARGET = 0x0203a56c // gBattleTarget
        const val UNIT_CUR_HP = 0x13 // struct Unit.curHP

        val COMMON_EKR_EFX = listOf(
            "ProcScr_EfxHitQuake",
            "ProcScr_EkrBaseKaiten",
            "ProcScr_efxFlashBG",
            "ProcScr_efxFlashHPBar",
            "ProcScr_efxFlashUnit",
            "ProcScr_efxHPBar",
            "ProcScr_efxHPBarColorChange",
            "ProcScr_efxNormalEffect",
            "ProcScr_efxNormalEffectBG",
            "ProcScr_efxStatusUnit",
            "ProcScr_efxWeaponIcon",
            "ProcScr_ekrBaseAppear",
            "ProcScr_ekrBattleEnding",
            "ProcScr_ekrBattleStarting",
            "ProcScr_ekrGauge",
            "ProcScr_ekrNamewinAppear",
            "ProcScr_ekrUnitKakudai",
            "ProcScr_ekrWindowAppear",
            "gProc_ekrBattle",
            "gProc_ekrBattleDeamon",
            "gProc_ekrChienCHR",
            "gProc_ekrDispUP",
        )
    }
}
