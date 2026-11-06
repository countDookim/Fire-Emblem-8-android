package fe8.testing

import gba.core.VerifiesPort
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [MsgStrings] against `ram_trace_prologue_talk`: every frame where
 * `sMsgString` (0x0202A6AC, 0x1000 bytes) or `sActiveMsg` (0x0202B6AC) changes in the per-frame
 * EWRAM dumps gives an (id, bytes) pair; `getStringFromIndex(id)` on the ROM must reproduce the
 * bytes up to and including the terminator (the dump tail beyond it is stale data from earlier,
 * longer strings).
 *
 * Tagging decisions: GetStringFromIndex is tagged (trace-verified output plus the cache). Message
 * bytes are never printed or stored; only hex of a few bytes appears on failure.
 */
class MsgStringsRomTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    @VerifiesPort("GetStringFromIndex")
    fun `getStringFromIndex reproduces sMsgString and caches the active id`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val changes = traceChanges()
        assertTrue(changes.size >= 2, "expected at least two buffer changes, got ${changes.size}")
        for (c in changes) {
            val ms = makeStrings(rom)
            ms.buffer.fill(0x55) // sentinel: the call must fully produce the string
            val got = ms.getStringFromIndex(c.id)
            assertTrue(got === ms.buffer, "${c.frame} returns the shared buffer")
            assertEquals(c.id, ms.activeMsg, "${c.frame} sActiveMsg")
            assertEquals(
                c.bytes.toList(), got.copyOf(c.bytes.size).toList(),
                "${c.frame} message ${c.id} bytes through terminator",
            )
            // Cache: the same id again must not re-decode (the sentinel survives).
            ms.buffer[0] = 0x66
            ms.getStringFromIndex(c.id)
            assertEquals(0x66, ms.buffer[0].toInt(), "${c.frame} cached call must not re-decode")
            // A different id re-decodes, and coming back decodes again.
            ms.getStringFromIndex(if (c.id == 0x212) 0x213 else 0x212)
            ms.buffer[0] = 0x66
            ms.getStringFromIndex(c.id)
            assertEquals(c.bytes[0], ms.buffer[0], "${c.frame} re-decode after a different id")
        }
    }

    /**
     * The traced message 1636 decodes to a string ending in `[.]` (0x1F) that the game shows one
     * byte shorter in `sMsgString`, so the trace exercises the terminator rewrite directly.
     */
    @Test
    @VerifiesPort("SetMsgTerminator")
    fun `setMsgTerminator turns the trailing dot into the terminator as in the trace`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val root = u32(rom, 0x0815D488 - 0x08000000) - 0x08000000
        val huff = MsgHuffman(rom, 0x0815A72C - 0x08000000, root)
        var exercised = 0
        for (c in traceChanges()) {
            val raw = huff.decode(u32(rom, 0x0815D48C - 0x08000000 + c.id * 4) - 0x08000000)
            val buf = ByteArray(MsgStrings.BUFFER_SIZE) { 0x55 }
            raw.copyInto(buf, 3) // nonzero start offset
            MsgStrings.setMsgTerminator(buf, 3)
            assertEquals(
                c.bytes.toList(), buf.copyOfRange(3, 3 + c.bytes.size).toList(), c.frame,
            )
            if (!raw.contentEquals(c.bytes.copyOf(raw.size))) exercised++
        }
        assertTrue(exercised > 0, "no traced string had its terminator rewritten")
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private class Change(val frame: String, val id: Int, val bytes: ByteArray)

    /** Frames where the buffer or the active id changed, with the string through its terminator. */
    private fun traceChanges(): List<Change> {
        val out = ArrayList<Change>()
        var prev: ByteArray? = null
        var prevId = Int.MIN_VALUE
        for (f in runDir.listFiles { _, n -> n.endsWith(".ewram.bin") }!!.sortedBy { it.name }) {
            val d = f.readBytes()
            val s = d.copyOfRange(BUF, BUF + 0x1000)
            val id = u32(d, ID)
            if (prev == null || !s.contentEquals(prev) || id != prevId) {
                out.add(Change(f.name.substring(0, 6), id, s.copyOf(s.indexOf(0) + 1)))
            }
            prev = s
            prevId = id
        }
        return out
    }

    private fun makeStrings(rom: ByteArray): MsgStrings {
        val root = u32(rom, 0x0815D488 - 0x08000000) - 0x08000000
        val huff = MsgHuffman(rom, 0x0815A72C - 0x08000000, root)
        return MsgStrings(rom, huff, 0x0815D48C - 0x08000000)
    }

    private companion object {
        const val BUF = 0x2A6AC
        const val ID = 0x2B6AC
    }
}
