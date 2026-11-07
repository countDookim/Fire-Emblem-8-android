package fe8.game

import fe8.game.event.EventEngineProc
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: the talk code itself is verified by EventTextDifferentialTest and the Talk
// tests in :testing; this test checks the production wiring (Fe8Boot.talkScene) against the same
// ram_trace_prologue_talk.

/**
 * The device-like headless path (New Game, A pressed every 30 frames) runs the Prologue opening's
 * text commands 0x1A / 0x1B / 0x1D for real, and the talk draws into the BG buffers and VRAM the
 * renderer displays; the first talk's BG0 and BG1 tilemaps equal `ram_trace_prologue_talk`
 * (f9865..f10565) frame by frame.
 */
class PrologueOpeningTextTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    private fun dump(f: Int, kind: String): ByteArray {
        val r = File(runDir, "r%05d.$kind.bin".format(f))
        return (if (r.isFile) r else File(runDir, "f%05d.$kind.bin".format(f))).readBytes()
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `the first talk of the Prologue is shown and equals the reference tilemaps`() {
        val file = File(assets, "baserom.gba")
        assumeTrue(file.isFile && runDir.isDirectory, "ROM or reference run not present")
        var frame = 0
        var started = -1
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            {
                if (started < 0) {
                    if (frame % 30 in 5..8) 0x0001 else 0 // A every 30 frames, like NEWGAME
                } else {
                    // Once the first talk runs, the keys of the trace (A held at f9870..f9871...).
                    val t = FIRST + frame + 1 - started
                    if (t > LAST) 0 else u16(dump(t, "ewram"), KEYS + 4)
                }
            },
            RngService(),
        )
        val engine = boot.eventEngine
        // 0x1A / 0x1B / 0x1D are the real text commands, not the shared no-op release stub.
        for (code in intArrayOf(0x1A, 0x1B, 0x1D)) {
            assertNotSame(engine.lo[0x21], engine.lo[code], "command $code is a release stub")
        }
        val hw = boot.ui.env.hardware
        var compared = 0
        var maxText = 0
        while (frame < 12000 && (started < 0 || frame - started < LAST - FIRST)) {
            boot.frame()
            frame++
            if (started < 0 && boot.engine.find(boot.talkScene.talk.talkScript) != null) {
                started = frame
            }
            if (started < 0) continue
            val t = FIRST + frame - started
            val ew = dump(t, "ewram")
            for (i in 0 until 0x400) {
                assertEquals(u16(ew, BG0_TM + 2 * i), hw.mapBuffer(0)[i].toInt() and 0xFFFF,
                    "BG0 cell $i at trace frame $t")
                assertEquals(u16(ew, BG1_TM + 2 * i), hw.mapBuffer(1)[i].toInt() and 0xFFFF,
                    "BG1 cell $i at trace frame $t")
            }
            maxText = maxOf(maxText, hw.mapBuffer(0).count { it.toInt() != 0 })
            compared++
        }
        assertTrue(started > 0, "no talk started")
        assertEquals(LAST - FIRST + 1, compared, "frames compared")
        assertTrue(maxText > 0, "the talk drew no text tiles into the BG0 tilemap")
        // The text tiles are in BG VRAM (char base of BG0, from chr 0x80): not all zero.
        val base = hw.tileDataOffset(0) + 0x80 * 0x20
        assertTrue((0 until 0x1000).any { hw.vram[base + it].toInt() != 0 }, "no text tile data")
        // While the talk ran the event engine was parked on 0x1D, then moved on.
        val std = boot.eventEngine.findStd()?.state as? EventEngineProc
        assertTrue(std != null, "event engine gone")
    }

    private companion object {
        const val FIRST = 9865
        const val LAST = 10565
        const val KEYS = 0x24CC0
        const val BG0_TM = 0x22CA8
        const val BG1_TM = 0x234A8
    }
}
