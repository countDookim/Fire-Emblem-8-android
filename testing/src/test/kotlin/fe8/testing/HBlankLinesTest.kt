package fe8.testing

import gbafe.render.BgVerticalScroll
import gbafe.render.HBlankLines
import gbafe.render.LineTable
import gbafe.render.RenderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Unit tests of the HBlank replay ([HBlankLines]) and the menu scroll table ([BgVerticalScroll]). */
class HBlankLinesTest {
    private fun scrollState(): Pair<BgVerticalScroll, RenderState> {
        val s = BgVerticalScroll()
        for (line in 0 until 160) {
            s.buf[line] = line.toShort() // half 0
            s.buf[480 + line] = (300 + line).toShort() // half 1
        }
        s.src[0] = 0x18 // BG2HOFS
        s.scrollEnabled = true
        val state = RenderState()
        state.layers[2].enabled = true
        return s to state
    }

    @Test
    fun `line N gets table entry N from the half latched at the previous line 159`() {
        val (s, state) = scrollState()
        s.frameFlip = 1
        HBlankLines.capture(ByteArray(0x400), state) { v, io -> s.onHBlank(v, io) }
        val l = state.lines
        for (line in 0 until 160) {
            assertEquals(LineTable.BG_SCROLL shl 2, l.mask[line] and (LineTable.BG_SCROLL shl 2), "line $line")
            assertEquals((300 + line) and 0x1FF, l.scrollX[2 * RenderState.LINES + line], "line $line")
        }
        assertEquals(1, s.frame)
    }

    @Test
    fun `nothing is written while scrolling is disabled`() {
        val (s, state) = scrollState()
        s.scrollEnabled = false
        val io = ByteArray(0x400)
        HBlankLines.capture(io, state) { v, regs -> s.onHBlank(v, regs) }
        assertFalse(state.lines.active)
        assertFalse(s.updating)
    }

    @Test
    fun `vcount 227 wraps to line 0`() {
        val (s, _) = scrollState()
        s.updating = true
        s.dst[0] = 0x18
        val io = ByteArray(0x400)
        s.buf[0] = 0x123
        s.onHBlank(227, io)
        assertEquals(0x23, io[0x18].toInt() and 0xFF)
        assertEquals(0x01, io[0x19].toInt())
    }
}
