package gbafe.core.flags

import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EventFlagsTest {
    /**
     * FE8U values from src/eventinfo.c (GetPermanentFlagBitsSize, GetChapterFlagBitsSize, IWRAM
     * symbol sizes).
     */
    private val layout = EventFlagLayout(
        permanentSaveBytes = 0x19,
        chapterSaveBytes = 5,
        permanentStorageBytes = 32,
        chapterStorageBytes = 16,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )

    private val chapterBase = 32

    @Test
    fun `fe8 layout constant matches the c values`() {
        val l = EventFlagLayout.FE8
        assertEquals(layout.permanentSaveBytes, l.permanentSaveBytes)
        assertEquals(layout.chapterSaveBytes, l.chapterSaveBytes)
        assertEquals(layout.permanentStorageBytes, l.permanentStorageBytes)
        assertEquals(layout.chapterStorageBytes, l.chapterStorageBytes)
        assertEquals(layout.chapterFlagLimit, l.chapterFlagLimit)
        assertEquals(layout.permanentBaseId, l.permanentBaseId)
        assertTrue(layout.bitMask.contentEquals(l.bitMask))
    }

    @Test
    fun `flag 0 and flag 100 are no-ops that read false`() {
        val f = EventFlags(layout)
        for (id in intArrayOf(0, 100)) {
            f.setFlag(id)
            assertFalse(f.checkFlag(id))
            f.clearFlag(id)
        }
        for (i in 0 until f.size) assertEquals(0, f.byteAt(i))
        f.setChapterFlag(0)
        f.setPermanentFlag(100)
        f.setPermanentFlag(5)
        f.setPermanentFlag(-3)
        for (i in 0 until f.size) assertEquals(0, f.byteAt(i))
    }

    @Test
    fun `chapter flag n is bit n minus 1 of the chapter array`() {
        val f = EventFlags(layout)
        f.setFlag(1)
        assertEquals(0x01, f.byteAt(chapterBase))
        f.setFlag(8)
        assertEquals(0x81, f.byteAt(chapterBase))
        f.setFlag(9)
        assertEquals(0x01, f.byteAt(chapterBase + 1))
        f.setFlag(40)
        assertEquals(0x80, f.byteAt(chapterBase + 4))
        assertTrue(f.checkFlag(40))
        f.clearFlag(8)
        assertEquals(0x01, f.byteAt(chapterBase))
        assertFalse(f.checkChapterFlag(8))
        for (i in 0 until chapterBase) assertEquals(0, f.byteAt(i))
    }

    @Test
    fun `permanent flag n is bit n minus 101 of the permanent array`() {
        val f = EventFlags(layout)
        f.setFlag(101)
        assertEquals(0x01, f.byteAt(0))
        f.setFlag(108)
        assertEquals(0x81, f.byteAt(0))
        f.setFlag(109)
        assertEquals(0x01, f.byteAt(1))
        f.setFlag(300)
        assertEquals(0x80, f.byteAt(24))
        assertTrue(f.checkPermanentFlag(300))
        f.clearFlag(108)
        assertEquals(0x01, f.byteAt(0))
        for (i in chapterBase until f.size) assertEquals(0, f.byteAt(i))
    }

    @Test
    fun `resets clear only the saved byte range`() {
        val f = EventFlags(layout)
        f.setFlag(40) // chapter byte 4: reset
        f.setFlag(41) // chapter byte 5: beyond the 5 reset bytes, survives
        f.setFlag(99) // chapter byte 12
        f.setFlag(300) // permanent byte 24: reset
        f.setFlag(301) // permanent byte 25: survives
        f.resetChapterFlags()
        assertFalse(f.checkFlag(40))
        assertTrue(f.checkFlag(41))
        assertTrue(f.checkFlag(99))
        assertTrue(f.checkFlag(300))
        f.resetPermanentFlags()
        assertFalse(f.checkFlag(300))
        assertTrue(f.checkFlag(301))
        assertEquals(0x01, f.byteAt(25))
    }

    @Test
    fun `a permanent flag past the permanent array lands in the chapter array`() {
        val f = EventFlags(layout)
        f.setFlag(100 + 256 + 1) // bit 256 of the mirror: first chapter byte
        assertEquals(0x01, f.byteAt(chapterBase))
        assertTrue(f.checkChapterFlag(1))
    }

    @Test
    fun `check permanent flag from reads an arbitrary buffer`() {
        val buf = ByteArray(0x19)
        buf[1] = 0x04
        val f = EventFlags(layout)
        assertTrue(f.checkPermanentFlagFrom(100 + 8 + 2 + 1, buf))
        assertFalse(f.checkPermanentFlagFrom(100, buf))
        assertFalse(f.checkPermanentFlagFrom(3, buf))
        assertThrows(IllegalArgumentException::class.java) { f.checkPermanentFlagFrom(400, buf) }
    }

    @Test
    fun `ids outside the mirror throw`() {
        val f = EventFlags(layout)
        assertThrows(IllegalArgumentException::class.java) { f.setFlag(-1) }
        assertThrows(IllegalArgumentException::class.java) { f.checkFlag(-5) }
        assertThrows(IllegalArgumentException::class.java) { f.setPermanentFlag(100 + 48 * 8 + 1) }
        assertThrows(IllegalArgumentException::class.java) { f.checkFlag(100 + 48 * 8 + 1) }
        assertThrows(IllegalArgumentException::class.java) { f.setChapterFlag(16 * 8 + 1) }
        f.setPermanentFlag(100 + 48 * 8) // last bit of the mirror
        assertEquals(0x80, f.byteAt(47))
    }

    @Test
    fun `raw copy in and out round trip the saved ranges`() {
        val f = EventFlags(layout)
        f.setFlag(101)
        f.setFlag(300)
        f.setFlag(2)
        f.setFlag(40)
        val perm = ByteArray(0x19)
        val chap = ByteArray(5)
        f.copyOutPermanent(perm)
        f.copyOutChapter(chap)
        assertEquals(0x01, perm[0].toInt())
        assertEquals(0x80, perm[24].toInt() and 0xFF)
        assertEquals(0x02, chap[0].toInt())
        assertEquals(0x80, chap[4].toInt() and 0xFF)

        val g = EventFlags(layout)
        g.copyInPermanent(perm)
        g.copyInChapter(chap)
        assertTrue(g.checkFlag(101) && g.checkFlag(300) && g.checkFlag(2) && g.checkFlag(40))

        val all = ByteArray(g.size)
        g.copyOut(all)
        val h = EventFlags(layout)
        h.copyIn(all)
        for (i in 0 until h.size) assertEquals(g.byteAt(i), h.byteAt(i))
    }

    @Test
    fun `set and check allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val f = EventFlags(layout)
        var hits = 0
        var n = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            val id = 1 + n % 299
            n++
            f.setFlag(id)
            if (f.checkFlag(id)) hits++
            f.clearFlag(id)
            f.setChapterFlag(id % 99 + 1)
            f.resetChapterFlags()
        }
        assertEquals(0L, bytes, "bytes allocated over 10k calls")
        assertTrue(hits > 0)
    }
}
