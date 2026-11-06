package fe8.game

import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: UnitLoader.closestValidPosition (AiGetUnitClosestValidPosition) may run here
// but no RAM trace has a load onto an occupied tile, so it stays "transcribed".

/**
 * Headless boot, title (A presses) and the New Game stand-in up to the Prologue player phase,
 * plus the START skip of the beginning events driven by the game's real `KeyStatus`.
 *
 * Known gap: the closest-position search (`AiGetUnitClosestValidPosition`) is reached in this
 * flow only because the release no-op stubs leave the cutscene units on the map. The reference
 * `ram_trace_first_phase` (f19430) has 2 blue units (Seth 4,4, Eirika 4,5) and 3 red ones; this
 * flow ends with 9 blue and 15 red, some off the 15x10 map. To be closed by M6-22, M6-28 and
 * M6-44.
 */
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
        assertTrue(reached >= 0) { "PlayerPhase not reached; $trail" }
        frame = 1 // no keys from here: the lambda reads frame % 20 == 1
        // The beginning events run for real (no skip: START is never pressed) with the release
        // stand-ins, load the player units and end; the phase then idles. Known gap: the
        // closest-free-tile search runs only for red units 139 (13,6)->(13,7) and 142
        // (11,4)->(11,3), because stub-left cutscene units occupy those tiles.
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) boot.frame()
        assertEquals(false, boot.eventEngine.eventEngineExists(), "event engine still running")
        assertTrue(boot.units.getPhaseAbleUnitCount(0) > 0, "no blue unit loaded")
        // A failed closest-tile search leaves x = -1 (0xFF): no valid unit may end that way.
        for (id in 1..0xC5) {
            if (!boot.units.isValid(id)) continue
            assertTrue(boot.units.xPos(id) != -1, "unit $id ended with x == 0xFF")
        }
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

    @Test
    fun `START skips the beginning events only when the real KeyStatus sees it`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        val withStart = runBeginningEvents(file, pressStart = true)
        val without = runBeginningEvents(file, pressStart = false)
        assertTrue(withStart.skipped, "START did not make the engine skip")
        assertEquals(false, without.skipped, "the engine skipped without START")
        assertTrue(without.ended && withStart.ended, "events did not end")
        assertTrue(
            withStart.frames < without.frames,
            "skip not faster: ${withStart.frames} vs ${without.frames} frames",
        )
    }

    private fun runBeginningEvents(file: File, pressStart: Boolean): Run {
        var frame = 0
        var phase2 = false
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            {
                when {
                    !phase2 -> if (frame % 20 in 5..8) 0x0001 else 0
                    pressStart && frame % 4 == 0 -> 0x0008
                    else -> 0
                }
            },
            RngService(),
        )
        while (frame < 3000 && boot.engine.find(boot.playerPhaseScript) == null) {
            boot.frame()
            frame++
        }
        phase2 = true
        var frames = 0
        var skipped = false
        while (boot.eventEngine.eventEngineExists() && frames < 6000) {
            frame++
            boot.frame()
            frames++
            val state = boot.eventEngine.findStd()?.state as? EventEngineProc
            if (state != null && state.evStateBits and EventConst.EV_STATE_SKIPPING != 0) {
                skipped = true
            }
        }
        return Run(skipped, !boot.eventEngine.eventEngineExists(), frames)
    }

    private class Run(val skipped: Boolean, val ended: Boolean, val frames: Int)
}
