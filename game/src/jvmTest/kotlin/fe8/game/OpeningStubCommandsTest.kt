package fe8.game

import gbafe.core.rng.RngService
import java.io.File
import java.util.TreeMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Progress gauge for M6-44 (not a port verification): runs the Prologue opening from New Game
 * (A pressed a few frames out of 20) to the player phase and pins the event command codes that
 * still reach the no-op release stubs. When a command gets a real handler, remove its code from
 * [EXPECTED]; when this fails with a new code, a handler regressed.
 */
class OpeningStubCommandsTest {
    @Test
    fun `the Prologue opening sends exactly the known commands to the release stubs`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        var frame = 0
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            { if (frame % 20 in 5..8) 0x0001 else 0 },
            RngService(),
        )
        val counts = TreeMap<Int, Int>()
        boot.onEventStub = { counts.merge(it, 1, Int::plus) }
        var reached = false
        while (frame < 12000 && !reached) {
            boot.frame()
            frame++
            reached = boot.engine.find(boot.playerPhaseScript) != null
        }
        assertTrue(reached, "PlayerPhase not reached")
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 20000) {
            boot.frame()
            frame++
        }
        println("stub codes (code=count): " +
            counts.entries.joinToString { "0x%02X=%d".format(it.key, it.value) })
        assertEquals(EXPECTED, counts.keys.toList())
    }

    private companion object {
        // 0x0D (x1), 0x21 (x4), 0x37 (x1), 0x3A (x1)
        val EXPECTED = listOf(13, 33, 55, 58)
    }
}
