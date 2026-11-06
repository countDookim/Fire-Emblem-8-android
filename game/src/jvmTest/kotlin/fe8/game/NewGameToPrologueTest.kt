package fe8.game

import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: UnitLoader.closestValidPosition (AiGetUnitClosestValidPosition) is only
// exercised here; no RAM trace has a load onto an occupied tile, so it stays "transcribed".

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
        // The beginning events run for real (no skip: START is never pressed) with the release
        // stand-ins, load the player units (including the closest-free-tile search for a walk-in
        // onto an occupied tile) and end; the phase then idles.
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) boot.frame()
        assertEquals(false, boot.eventEngine.eventEngineExists(), "event engine still running")
        assert(boot.units.getPhaseAbleUnitCount(0) > 0) { "no blue unit loaded" }
        val proc = boot.engine.find(boot.playerPhaseScript)
        assertNotNull(proc, "player phase auto-ended")
        // Acceptance: the proc sits on its PlayerPhase_MainIdle `repeat` and stays there.
        val cmds = boot.playerPhaseScript.cmds
        val idleIndex = cmds.indexOfFirst { it.opcode == 0x0B && it.imm == 9 } + 3
        repeat(30) {
            boot.frame()
            assertEquals(idleIndex + 1, proc!!.scrCur, "not on the PlayerPhase_MainIdle repeat")
        }
        // The repeat command has run (scrCur is past it) and installed its callback.
        assertEquals(0x03, cmds[idleIndex].opcode)
        assertNotNull(proc!!.idleCb)
    }
}
