package gbafe.core

import gbafe.core.mechanics.BattleRolls
import gbafe.core.mechanics.Mechanics
import gbafe.core.mechanics.VanillaBattleRolls
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleRollsTest {
    private fun rng() = RngService().apply { seedLikeBoot() }

    @Test
    fun `vanilla hit is the two-RN roll and consumes two draws`() {
        val a = rng(); val b = rng()
        repeat(200) { assertEquals(b.roll2(70), VanillaBattleRolls.hit(a, 70)) }
        assertEquals(b.next(), a.next()) // same RNG position afterwards
    }

    /** What a "1-RN hit" mod would ship. Lives in this test only: the game itself has no such option. */
    private object OneRnHit : BattleRolls by VanillaBattleRolls {
        override fun hit(rng: RngService, hitRate: Int): Boolean = rng.roll1(hitRate)
    }

    @Test
    fun `a mod can switch hit to one RN without touching battle code`() {
        val m = Mechanics()
        assertTrue(m.isVanilla)
        m.battleRolls = OneRnHit
        assertFalse(m.isVanilla)
        val a = rng(); val b = rng()
        repeat(200) { assertEquals(b.roll1(70), m.battleRolls.hit(a, 70)) }
        assertEquals(b.next(), a.next()) // one draw per hit, not two
    }
}
