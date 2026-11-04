package fe8.testing

import gba.render.LineTable
import gba.render.RenderState
import gba.render.SpriteBatch
import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** M3-02: render state types (ADR 0008) behave and filling a frame allocates nothing. */
class RenderStateTest {
    private fun fill(s: RenderState, frame: Int) {
        s.sprites.clear()
        s.lines.clear()
        for (bg in 0 until RenderState.LAYERS) {
            val l = s.layers[bg]
            l.enabled = true; l.priority = bg; l.scrollX = frame; l.scrollY = -frame
            for (y in 0 until 32) for (x in 0 until 32) l.setEntry(x, y, (x + y + frame) and 0x3FF)
        }
        while (s.sprites.add(frame and 0xFF, 10, 16, 16, 1, 6, 0, 1, SpriteBatch.HFLIP) >= 0) Unit
        for (line in 0 until RenderState.LINES) {
            s.lines.setScroll(line, 2, line + frame, 0)
            s.lines.setWindowX(line, 0, 120 - line / 2, 120 + line / 2)
            s.lines.writePalette(line, 1, line)
        }
        s.palettes.bg[0] = frame.toShort()
        s.effects.eva = frame and 15
    }

    @Test
    fun `filling a frame allocates nothing`() {
        assumeTrue(AllocationMeter.supported)
        val s = RenderState()
        var f = 0
        val bytes = AllocationMeter.measureMin(warmup = 5_000, iterations = 1_000) { fill(s, f++) }
        assertEquals(0L, bytes, "bytes allocated over 1000 frames")
    }

    @Test
    fun `sprite batch drops sprites past capacity like the hardware`() {
        val b = SpriteBatch()
        repeat(128) { assertEquals(it, b.add(0, 0, 8, 8, 0, it, 0, 0)) }
        assertEquals(-1, b.add(0, 0, 8, 8, 0, 0, 0, 0))
        assertEquals(128, b.count)
        assertEquals(64, SpriteBatch().apply { add(0, 0, 64, 64, 0, 0, 0, 0) }.width[0].toInt())
    }

    @Test
    fun `line table records overrides and clears`() {
        val t = LineTable(paletteWriteCapacity = 2)
        assertFalse(t.active)
        t.setScroll(5, 3, 7, 9)
        assertTrue(t.active)
        assertEquals(LineTable.BG_SCROLL shl 3, t.mask[5])
        assertEquals(7, t.scrollX[3 * RenderState.LINES + 5])
        assertTrue(t.writePalette(1, 300, 0x7FFF))
        assertTrue(t.writePalette(4, 2, 1))
        assertFalse(t.writePalette(9, 2, 1))
        t.clear()
        assertFalse(t.active)
        assertEquals(0, t.mask[5])
        assertEquals(0, t.paletteWrites)
    }

    @Test
    fun `tile entries round-trip all 16 bits`() {
        val s = RenderState()
        s.layers[1].setEntry(127, 127, 0xFFFF)
        assertEquals(0xFFFF, s.layers[1].entry(127, 127))
        s.reset()
        assertEquals(0, s.layers[1].entry(127, 127))
    }
}
