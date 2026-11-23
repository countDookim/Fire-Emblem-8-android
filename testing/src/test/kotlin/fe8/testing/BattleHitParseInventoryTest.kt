package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, BA-i2) of the hit-array parse and the actor setup that start
 * a battle animation: `ParseBattleHitToBanimCmd` (banim-battleparse.c, run inside
 * `PrepareBattleGraphicsMaybe`) and `ekrBattle_Init` -> `InitMainAnims` ->
 * `InitBattleAnimFrame`/`InitLeftAnim`/`InitRightAnim` (banim-ekrbattle.c, banim-ekrmain.c).
 * Windows: `ram_trace_prologue_attack` (parse f1464, ekrBattle f1496, anims f1497) and
 * `ram_trace_prologue_battle` (parse f14431, ekrBattle f14467, anims f14468). The captures
 * log no function entries, so the facts are state before/after, read from the per-step EWRAM
 * dumps at runtime; nothing from the ROM is embedded and the hit array is decoded with the
 * decomp's bit layout. See docs/plans/M6C-J-breakdown.md ("BA-i2 result").
 */
class BattleHitParseInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Window(
        val run: String,
        val parse: Int,
        val ekrBattle: Int,
        val end: Int,
        val hits: List<Triple<Int, Int, Int>>, // attributes, info, hpChange
        val attackerIsRight: List<Boolean>,
        val expectedRounds: List<Pair<Int, Int>>, // (left, right) ANIM_ROUND type
    )

    private inner class Ram(run: String) {
        val capture = ref.run(run)
        private val byStep = capture.dumps.entries.associate { it.value to it.key }

        fun at(step: Int): ByteArray = capture.region(byStep.getValue(step), "ewram")
    }

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)
    private fun words(e: ByteArray, a: Int, n: Int) = List(n) { u32(e, a + 4 * it) }
    private fun halves(e: ByteArray, a: Int, n: Int) = List(n) { u16(e, a + 2 * it) }

    private fun decode(word: Int) =
        Triple(word and 0x7FFFF, (word ushr 19) and 0x1F, word ushr 24)

    private fun check(w: Window) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val ram = Ram(w.run)
        val before = ram.at(w.parse - 1)
        val e = ram.at(w.parse)

        // The hit array is final at the parse step and decodes to the expected hits plus END.
        val hitWords = words(e, HIT_ARRAY, 7)
        val decoded = hitWords.map(::decode)
        assertEquals(w.hits, decoded.take(w.hits.size))
        assertEquals(BATTLE_HIT_INFO_END, decoded[w.hits.size].second and BATTLE_HIT_INFO_END)
        assertEquals(BATTLE_HIT_INFO_BEGIN, decoded[0].second and BATTLE_HIT_INFO_BEGIN)

        // Before the parse the three output blocks are zero (never initialised in this run).
        assertEquals(List(20) { 0 }, halves(before, ANIM_ROUND_DATA, 20))
        assertEquals(List(22) { 0 }, halves(before, EFX_HP_LUT, 22))
        assertEquals(0, u32(before, GAUGE_HP))

        // Parse results: 20 slots, filled for each hit then 0xFFFF; no triangle attack.
        val rounds = halves(e, ANIM_ROUND_DATA, 20)
        val n = w.hits.size
        assertEquals(w.expectedRounds.flatMap { listOf(it.first, it.second) }, rounds.take(2 * n))
        assertEquals(List(20 - 2 * n) { 0xFFFF }, rounds.drop(2 * n))
        assertEquals(listOf(0, 0), words(e, EKR_TRIANGLE_UNITS, 2))
        assertEquals(0, u16(e, EKR_DISTANCE_TYPE)) // EKR_DISTANCE_CLOSE: no far remap branch
        assertEquals(1, u16(e, EKR_INITIAL_HIT_SIDE)) // POS_R: the first hit is the right side's
        // Side of the attacker follows the retaliation bit and gBanimPositionIsEnemy.
        val posEnemy = halves(e, BANIM_POSITION_IS_ENEMY, 2)
        for (i in 0 until n) {
            val retaliation = (w.hits[i].second and BATTLE_HIT_INFO_RETALIATION) != 0
            val leftAttacks = (posEnemy[0] == 1) == retaliation
            assertEquals(!leftAttacks, w.attackerIsRight[i], "hit $i")
        }

        // gEfxHpLut: [0..1] = gEkrGaugeHp at parse; each landed hit appends the new HP of its
        // victim side (previous value of that side minus hpChange), others stay 0xFFFF.
        val lut = halves(e, EFX_HP_LUT, 22)
        val gauge = halves(e, GAUGE_HP, 2)
        assertEquals(gauge, lut.take(2))
        val last = gauge.toMutableList()
        val expectedLut = MutableList(22) { 0xFFFF }
        expectedLut[0] = gauge[0]
        expectedLut[1] = gauge[1]
        val slot = intArrayOf(0, 0)
        for (i in 0 until n) {
            val (attr, _, hp) = w.hits[i]
            if (attr and BATTLE_HIT_ATTR_MISS != 0) continue
            val victim = if (w.attackerIsRight[i]) 0 else 1
            slot[victim]++
            last[victim] = maxOf(0, last[victim] - hp)
            expectedLut[2 * slot[victim] + victim] = last[victim]
        }
        assertEquals(expectedLut, lut)
        assertEquals(0, u16(e, EFX_HP_LUT_OFF))
        assertEquals(0, u16(e, EFX_HP_LUT_OFF + 2))

        // Outputs are unchanged to the end of the window (read-only afterwards).
        val endRam = ram.at(w.end)
        assertEquals(rounds, halves(endRam, ANIM_ROUND_DATA, 20))
        assertEquals(lut.drop(2), halves(endRam, EFX_HP_LUT, 22).drop(2))

        // Actor setup: ekrBattle proc exists one step before the anims do (ekrBattle_Init ->
        // InitMainAnims), and the four Anim structs come out of a pool with stride 0x48.
        val preProc = ram.at(w.ekrBattle - 1)
        val initFrame = ram.at(w.ekrBattle)
        val anims = ram.at(w.ekrBattle + 1)
        assertEquals(0, u32(preProc, PROC_EKR_BATTLE))
        assertNotEquals(0, u32(initFrame, PROC_EKR_BATTLE))
        assertEquals(List(4) { 0 }, words(initFrame, G_ANIMS, 4))
        val ptrs = words(anims, G_ANIMS, 4)
        assertEquals(List(4) { ANIM_POOL + ANIM_STRIDE * it }, ptrs)
        for ((i, p) in ptrs.withIndex()) {
            assertEquals(1, u16(anims, p), "anim $i state ENABLED") // state
            assertEquals(0x58, u16(anims, p + 4), "anim $i y")
            assertEquals(1, u16(anims, p + 6), "anim $i timer")
            assertEquals(if (i < 2) 0x5C else 0x94, u16(anims, p + 2), "anim $i x")
            // currentRoundType: ANIM_ROUND_TAKING_HIT_CLOSE for the distance-CLOSE branch.
            assertEquals(ANIM_ROUND_TAKING_HIT_CLOSE, u8(anims, p + 0x12), "anim $i round")
        }
        // ekrBattle_Init's background branch: gEkrBgPosition stays 0 (its InitPosReal != 0 arm).
        assertEquals(0, u32(anims, EKR_BG_POSITION))
        assertNotEquals(0, u32(anims, EKR_INIT_POS_REAL))
    }

    @Test
    fun `attack window parse f1464 and actor setup f1496 to f1497`() = check(
        Window(
            ATTACK, 1464, 1496, 1988,
            // Eirika hit (BEGIN, 7), Soldier retaliation (RETALIATE, 9), Eirika follow-up (7).
            hits = listOf(
                Triple(0, BATTLE_HIT_INFO_BEGIN, 7),
                Triple(BATTLE_HIT_ATTR_RETALIATE, BATTLE_HIT_INFO_RETALIATION, 9),
                Triple(BATTLE_HIT_ATTR_FOLLOWUP, 0, 7),
            ),
            attackerIsRight = listOf(true, false, true),
            expectedRounds = listOf(
                ANIM_ROUND_TAKING_HIT_CLOSE to ANIM_ROUND_HIT_CLOSE,
                ANIM_ROUND_HIT_CLOSE to ANIM_ROUND_TAKING_HIT_CLOSE,
                ANIM_ROUND_TAKING_HIT_CLOSE to ANIM_ROUND_HIT_CLOSE,
            ),
        ),
    )

    @Test
    fun `scripted window parse f14431 and actor setup f14467 to f14468`() = check(
        Window(
            BATTLE, 14431, 14467, 15034,
            // Right side misses (BEGIN, MISS), left retaliates for 17.
            hits = listOf(
                Triple(BATTLE_HIT_ATTR_MISS, BATTLE_HIT_INFO_BEGIN, 0),
                Triple(0, BATTLE_HIT_INFO_RETALIATION, 17),
            ),
            attackerIsRight = listOf(true, false),
            expectedRounds = listOf(
                ANIM_ROUND_TAKING_MISS_CLOSE to ANIM_ROUND_MISS_CLOSE,
                ANIM_ROUND_HIT_CLOSE to ANIM_ROUND_TAKING_HIT_CLOSE,
            ),
        ),
    )

    @Test
    fun `hit array is rebuilt just before the attack parse and final at it`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val ram = Ram(ATTACK)
        val early = words(ram.at(1430), HIT_ARRAY, 7)
        val mid = words(ram.at(1463), HIT_ARRAY, 7)
        val at = words(ram.at(1464), HIT_ARRAY, 7)
        // f1430 holds the final array already; f1463 is mid-rewrite (only hit 0 and a stub);
        // f1464 (the parse) holds it again. The iterator pointer is not reset by the parse.
        assertEquals(at, early)
        assertEquals(early[0], mid[0])
        assertNotEquals(early, mid)
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val BATTLE = "ram_trace_prologue_battle"
        const val EWRAM = 0x02000000
        const val HIT_ARRAY = 0x0203A5EC // gBattleHitArray
        const val EKR_INITIAL_HIT_SIDE = 0x0203E100
        const val BANIM_POSITION_IS_ENEMY = 0x0203E108
        const val EKR_DISTANCE_TYPE = 0x0203E120
        const val ANIM_ROUND_DATA = 0x0203E12A
        const val EFX_HP_LUT_OFF = 0x0203E152
        const val EFX_HP_LUT = 0x0203E156
        const val EKR_TRIANGLE_UNITS = 0x0203E194
        const val GAUGE_HP = 0x0203E1AC // gEkrGaugeHp
        const val G_ANIMS = 0x02000000
        const val PROC_EKR_BATTLE = 0x02000064 // gpProcEkrBattle
        const val EKR_BG_POSITION = 0x0201FB0C
        const val EKR_INIT_POS_REAL = 0x02017744
        const val ANIM_POOL = 0x02028F78
        const val ANIM_STRIDE = 0x48
        const val BATTLE_HIT_ATTR_MISS = 1 shl 1
        const val BATTLE_HIT_ATTR_FOLLOWUP = 1 shl 2
        const val BATTLE_HIT_ATTR_RETALIATE = 1 shl 3
        const val BATTLE_HIT_INFO_BEGIN = 1 shl 0
        const val BATTLE_HIT_INFO_RETALIATION = 1 shl 3
        const val BATTLE_HIT_INFO_END = 1 shl 4
        const val ANIM_ROUND_HIT_CLOSE = 0
        const val ANIM_ROUND_TAKING_MISS_CLOSE = 4
        const val ANIM_ROUND_TAKING_HIT_CLOSE = 6
        const val ANIM_ROUND_MISS_CLOSE = 9
    }
}
