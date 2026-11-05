package fe8.game

import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Headless boot, title (A presses) and the New Game stand-in up to the Prologue player phase. */
class NewGameToPrologueTest {
    @Test
    fun `boot and New Game reach the Prologue player phase`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        var frame = 0
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            { if (frame % 20 in 5..8) 0x0001 else 0 }, // A pressed a few frames out of 20
            RngService(),
        )
        var reached = -1
        val trail = StringBuilder()
        while (frame < 3000) {
            boot.frame()
            frame++
            if (frame % 100 == 0) {
                trail.append(frame).append(':').append(boot.control.playSt.chapterIndex).append(' ')
            }
            if (boot.engine.find(boot.playerPhaseScript) != null) {
                reached = frame
                break
            }
        }
        assertNotNull(boot.sram)
        assert(reached >= 0) { "PlayerPhase not reached; $trail" }
        frame = 1 // no keys from here: the lambda reads frame % 20 == 1
        // The beginning events (stubbed, skip mode) must run to their end. The unit load commands
        // are stubs, so no unit exists and the phase auto-ends (label 3) instead of idling; the
        // playerPhaseMainIdle assertion returns with the unit load port.
        repeat(120) { boot.frame() }
        assertEquals(false, boot.eventEngine.eventEngineExists(), "event engine still running")
    }
}
