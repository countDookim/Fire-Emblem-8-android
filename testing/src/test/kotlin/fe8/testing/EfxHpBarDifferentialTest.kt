package fe8.testing

import fe8.game.bm.EfxHpBar
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.Anim
import gbafe.anim.AnimPool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `NewEfxHpBar`, the non-death arm of `EfxHpBar_DeclineToDeath`, `CheckEkrHitDone` and
 * `CheckEkrHitNow`, `EfxHpBar_MoveCameraOnEnd`, `EfxHpBar_WaitCameraMove` and the script order (banim-efxhpbar.c) against the three HP bars of `ram_trace_prologue_attack`
 * (created f1577 / f1659 / f1784, `Proc_Break` at f1609 / f1690 / f1816, then
 * `EfxHpBar_MoveCameraOnEnd` (+1), `EfxHpBar_WaitCameraMove` (+2) and the end (+3, proc gone)).
 *
 * Per bar the state before the creation step is loaded from the previous dump (the pool anims'
 * `state2` / `nextRoundId`, `gEkrGaugeHp`, `gEfxHpLutOff`, `gEkrHitNow`, `gEkrHpBarCount`,
 * `gEfxHpLut`); `newEfxHpBar` is called for the anim the dumped proc names as `anim_this` (what
 * `C1a` passes) and then `declineToDeath` runs once per step. After every step the proc's
 * `timer`, `cur`, `diff`, `this`, `next`, `timer2`, `finished`, the gauge, `gEfxHpLutOff`,
 * `gEkrHitNow` and the bar count are compared with the dump.
 *
 * The proc did not advance on f1582 and f1789 (BA-i4, BA-cap3: unexplained), so the run is
 * skipped at those two steps and the dump is only asserted equal to the step before. Stand-in:
 * `GetRoundFlagByAnim` reads `gAnimRoundData` here (flags `0xFF00` of the entry, as
 * `GetBattleAnimRoundTypeFlags`), the death arm is not reached.
 */
class EfxHpBarDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private class Bar(val start: Int, val brk: Int)

    @Test
    @VerifiesPort("NewEfxHpBar", "EfxHpBar_DeclineToDeath", "CheckEkrHitDone", "CheckEkrHitNow",
        "EfxHpBar_MoveCameraOnEnd", "EfxHpBar_WaitCameraMove", "ProcScr_efxHPBar")
    fun `three hp bars of the prologue attack`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp symbols not present")
        val capture = ref.run("ram_trace_prologue_attack")
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        fun at(s: Int) = capture.region(byStep.getValue(s), "ewram")
        fun bar(ram: ByteArray) = ProcArrayDump.ofEwram(ram, names).find("ProcScr_efxHPBar")

        for (b in listOf(Bar(1577, 1609), Bar(1659, 1690), Bar(1784, 1816))) {
            val pre = at(b.start - 1)
            val first = at(b.start)
            val pool = AnimPool()
            for (i in 0 until 4) {
                val a = pool.slots[i]
                val p = POOL + STRIDE * i
                a.state2 = u16(pre, p + 0x0C)
                a.nextRoundId = u8(pre, p + 0x0E)
            }
            val gauge = IntArray(2) { s16(pre, GAUGE_HP + 2 * it) }
            val env = object : EfxHpBar.Env {
                override fun roundFlag(anim: Anim): Int {
                    val side = if (anim.state2 and POS_RIGHT == 0) 0 else 1
                    val w = u16(pre, ROUND_DATA + 2 * ((anim.nextRoundId - 1) * 2 + side))
                    return if (w == 0xFFFF) 0 else w and 0xFF00
                }

                // Stand-in: the capture never takes the CheckRound2 arm (cur stays 1).
                override fun nextRoundIsRound2(proc: EfxHpBar.ProcEfxHpBar) = false
            }
            val hp = EfxHpBar(env, Array(4) { pool.slots[it] }, gauge) {
                u16(pre, EFX_HP_LUT + 2 * it)
            }
            for (side in 0..1) {
                hp.hpLutOff[side] = s16(pre, LUT_OFF + 2 * side)
                hp.hitNow[side] = s16(pre, HIT_NOW + 2 * side)
            }
            hp.hpBarCount = u32(pre, HP_BAR_COUNT)
            hp.spellAnimExists = u32(pre, SPELL_ANIM_EXISTS)
            hp.bgSemaphore = u32(pre, BG_SEMAPHORE)
            assertTrue(hp.checkEkrHitDone())
            assertEquals(0, hp.checkEkrHitNow(0))
            assertEquals(0, hp.checkEkrHitNow(1))

            val dumped = bar(first)!!
            val animThis = pool.slots[(dumped.u32(0x64) - POOL) / STRIDE]
            val proc = hp.newEfxHpBar(animThis)!!
            // A second start while the bar lives returns without a proc (count != 0).
            assertEquals(null, hp.newEfxHpBar(animThis))

            fun compare(step: Int) {
                val ram = at(step)
                val p = bar(ram)!!
                val tag = "f$step"
                assertEquals(p.u16(0x2C), proc.timer, "$tag timer")
                assertEquals(p.u16(0x2E), proc.cur, "$tag cur")
                assertEquals(p.u32(0x48), proc.diff, "$tag diff")
                assertEquals(p.u32(0x4C), proc.thisHp, "$tag this")
                assertEquals(p.u32(0x50), proc.next, "$tag next")
                assertEquals(p.u32(0x54), proc.timer2, "$tag timer2")
                assertEquals(p.u32(0x58), if (proc.finished) 1 else 0, "$tag finished")
                assertEquals(p.scrCur, proc.scrCur, "$tag script index")
                for (side in 0..1) {
                    assertEquals(s16(ram, GAUGE_HP + 2 * side), gauge[side], "$tag gauge $side")
                    assertEquals(s16(ram, LUT_OFF + 2 * side), hp.hpLutOff[side], "$tag off $side")
                    assertEquals(s16(ram, HIT_NOW + 2 * side), hp.checkEkrHitNow(side), "$tag hit")
                }
                assertEquals(u32(ram, HP_BAR_COUNT), hp.hpBarCount, "$tag count")
                assertEquals(u32(ram, HP_BAR_COUNT) == 0 && u32(ram, SPELL_ANIM_EXISTS) == 0,
                    hp.checkEkrHitDone(), "$tag hit done")
            }
            compare(b.start)
            assertFalse(hp.checkEkrHitDone())

            for (step in b.start + 1..b.brk + 3) {
                if (step == b.brk + 3) {
                    // The step after WaitCameraMove broke: the script reaches PROC_END.
                    assertEquals(null, bar(at(step)), "f$step proc gone")
                    assertFalse(hp.step(proc))
                    assertEquals(0, u32(at(step), HP_BAR_COUNT))
                    continue
                }
                if (step in UNADVANCED) {
                    val a = bar(at(step - 1))!!
                    val c = bar(at(step))!!
                    for (off in listOf(0x2C, 0x2E, 0x54, 0x58)) {
                        assertEquals(a.u16(off), c.u16(off), "f$step unadvanced $off")
                    }
                    continue
                }
                assertTrue(hp.step(proc))
                compare(step)
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val POOL = 0x02028F78
        const val STRIDE = 0x48
        const val POS_RIGHT = 1 shl 9
        const val GAUGE_HP = 0x0203E1AC
        const val LUT_OFF = 0x0203E152
        const val EFX_HP_LUT = 0x0203E156
        const val ROUND_DATA = 0x0203E12A
        const val HP_BAR_COUNT = 0x02017728
        const val SPELL_ANIM_EXISTS = 0x0201772C
        const val BG_SEMAPHORE = 0x0201774C
        const val HIT_NOW = 0x02017780
        val UNADVANCED = setOf(1582, 1789)
    }
}
