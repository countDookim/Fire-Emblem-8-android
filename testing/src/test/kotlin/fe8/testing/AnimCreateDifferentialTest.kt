package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.Anim
import gbafe.anim.AnimPool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `AnimCreate` and `AnimInsert` (animedrv.c) against the two animated battle windows: the four
 * `InitLeftAnim`/`InitRightAnim` creates at f1496 -> f1497 (`ram_trace_prologue_attack`) and
 * f14467 -> f14468 (`ram_trace_prologue_battle`).
 *
 * The callers are not ported, so the script address and display priority of each create are read
 * from the post-state `Anim` (`pScrStart`, `drawLayerPriority`) in the order of `gAnims[0..3]`.
 * The pool starts empty (checked in the pre-state). Compared per slot: `state`, `pScrStart`,
 * `drawLayerPriority`, `pPrev`/`pNext` (as pool addresses) and `sFirstAnim`. Fields the callers
 * set afterwards (position, `oam2Base`, `state2`, round type) and the driver's own per-frame
 * updates (`timer`, `pScrCurrent`) are not compared.
 */
class AnimCreateDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun addr(i: Int) = POOL + STRIDE * i

    private fun check(run: String, step: Int) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val capture = ref.run(run)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        fun at(s: Int) = capture.region(byStep.getValue(s), "ewram")
        val pre = at(step - 1)
        val post = at(step)

        for (i in 0 until POOL_COUNT) assertEquals(0, u16(pre, addr(i)), "pre slot $i free")
        assertEquals(0, u32(pre, FIRST_ANIM))

        val order = List(4) { u32(post, G_ANIMS + 4 * it) }
        assertEquals(List(4) { addr(it) }, order)

        val pool = AnimPool()
        val created = order.map { p ->
            pool.create(u32(post, p + 0x24), u16(post, p + 0x0A))
        }
        for ((i, a) in created.withIndex()) assertNotNull(a, "create $i")

        fun link(a: Anim?) = if (a == null) 0 else addr(pool.slots.indexOf(a))
        for (i in 0 until POOL_COUNT) {
            val a = pool.slots[i]
            val p = addr(i)
            assertEquals(u16(post, p), a.state, "slot $i state")
            assertEquals(u32(post, p + 0x24), a.pScrStart, "slot $i pScrStart")
            assertEquals(u16(post, p + 0x0A), a.drawLayerPriority, "slot $i priority")
            assertEquals(u32(post, p + 0x34), link(a.pPrev), "slot $i pPrev")
            assertEquals(u32(post, p + 0x38), link(a.pNext), "slot $i pNext")
        }
        assertEquals(u32(post, FIRST_ANIM), link(pool.first), "sFirstAnim")
    }

    @Test
    @VerifiesPort("AnimCreate", "AnimInsert")
    fun `attack window f1497`() = check("ram_trace_prologue_attack", 1497)

    @Test
    @VerifiesPort("AnimCreate", "AnimInsert")
    fun `scripted window f14468`() = check("ram_trace_prologue_battle", 14468)

    private companion object {
        const val EWRAM = 0x02000000
        const val G_ANIMS = 0x02000000
        const val POOL = 0x02028F78
        const val STRIDE = 0x48
        const val POOL_COUNT = 50
        const val FIRST_ANIM = 0x02029D88
    }
}
