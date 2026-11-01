package gbafe.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TouchButtonsTest {
    private val t = TouchButtons().apply { resize(1600, 800) }

    private fun centre(b: TouchButtons.Button): FloatArray {
        val i = b.ordinal * 4
        return floatArrayOf(
            (t.layout[i] + t.layout[i + 2]) / 2,
            (t.layout[i + 1] + t.layout[i + 3]) / 2,
        )
    }

    @Test
    fun `each button emits exactly its GBA key`() {
        for (b in TouchButtons.Button.entries) {
            if (b == TouchButtons.Button.DPAD) continue
            val c = centre(b)
            assertEquals(1L shl b.gbaBit, t.keysAt(c[0], c[1]), b.name)
        }
    }

    @Test
    fun `buttons do not overlap`() {
        val l = t.layout
        val n = TouchButtons.Button.entries.size
        for (a in 0 until n) for (b in a + 1 until n) {
            val overlap = l[a * 4] < l[b * 4 + 2] && l[b * 4] < l[a * 4 + 2] &&
                l[a * 4 + 1] < l[b * 4 + 3] && l[b * 4 + 1] < l[a * 4 + 3]
            assertEquals(false, overlap, "$a vs $b")
        }
    }

    @Test
    fun `dpad cells give directions and diagonals`() {
        val i = TouchButtons.Button.DPAD.ordinal * 4
        val cell = (t.layout[i + 2] - t.layout[i]) / 3
        fun at(col: Int, row: Int) =
            t.keysAt(t.layout[i] + cell * (col + 0.5f), t.layout[i + 1] + cell * (row + 0.5f))
        assertEquals(0L, at(1, 1))
        assertEquals(1L shl 6, at(1, 0)) // up
        assertEquals(1L shl 7, at(1, 2)) // down
        assertEquals(1L shl 5, at(0, 1)) // left
        assertEquals((1L shl 4) or (1L shl 6), at(2, 0)) // right + up
    }

    @Test
    fun `multi-touch ORs pointers, slide and lift update`() {
        val a = centre(TouchButtons.Button.A)
        val s = centre(TouchButtons.Button.START)
        val b = centre(TouchButtons.Button.B)
        t.pointerAt(0, a[0], a[1])
        t.pointerAt(1, s[0], s[1])
        assertEquals((1L shl 0) or (1L shl 3), t.poll())
        t.pointerAt(0, b[0], b[1])
        assertEquals((1L shl 1) or (1L shl 3), t.poll())
        t.pointerUp(1)
        assertEquals(1L shl 1, t.poll())
        t.pointerAt(0, 800f, 300f) // empty middle of the screen
        assertEquals(0L, t.poll())
        t.pointerAt(99, a[0], a[1]) // out of range id: ignored
        assertEquals(0L, t.poll())
    }

    @Test
    fun `buttons stay inside the safe area with a margin (issue 3)`() {
        // Landscape 16:9..21:9 phones; insets = cutout left/right, status bar top, gesture bar.
        val cases = listOf(
            intArrayOf(1920, 1080, 0, 0, 0, 0),
            intArrayOf(2160, 1080, 80, 0, 0, 63),
            intArrayOf(2400, 1080, 0, 72, 120, 48),
            intArrayOf(2520, 1080, 140, 0, 140, 0),
            intArrayOf(800, 360, 30, 24, 0, 24),
        )
        for (c in cases) {
            val b = TouchButtons().apply { resize(c[0], c[1], c[2], c[3], c[4], c[5]) }
            val l = b.layout
            for (e in TouchButtons.Button.entries) {
                val i = e.ordinal * 4
                val msg = "${e.name} in ${c.toList()}"
                assertTrue(l[i] > c[2] && l[i + 1] > c[3], msg)
                assertTrue(l[i + 2] < c[0] - c[4] && l[i + 3] < c[1] - c[5], msg)
                assertTrue(l[i] < l[i + 2] && l[i + 1] < l[i + 3], msg)
            }
            val n = TouchButtons.Button.entries.size
            for (x in 0 until n) for (y in x + 1 until n) {
                val overlap = l[x * 4] < l[y * 4 + 2] && l[y * 4] < l[x * 4 + 2] &&
                    l[x * 4 + 1] < l[y * 4 + 3] && l[y * 4 + 1] < l[x * 4 + 3]
                assertFalse(overlap, "$x vs $y in ${c.toList()}")
            }
        }
    }
}
