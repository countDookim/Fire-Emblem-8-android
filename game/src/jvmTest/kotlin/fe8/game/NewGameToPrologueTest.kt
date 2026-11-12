package fe8.game

import fe8.game.bm.MuProc
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: UnitLoader.closestValidPosition (AiGetUnitClosestValidPosition) is never
// reached here (measured 2026-11-09), so it stays "transcribed".

/**
 * Headless boot, title (A presses) and the New Game stand-in up to the Prologue player phase,
 * plus the START skip of the beginning events driven by the game's real `KeyStatus`.
 *
 * Measured 2026-11-09 (temporary counters, since removed): this flow makes 51 `flags & 1` loads
 * in `AdjustNewUnitPosition` (4 test runs share most of them), none onto an occupied tile, so
 * `AiGetUnitClosestValidPosition` is never reached. The old note that stub-left cutscene units
 * force it is stale: cutscene units are cleared by the real Event34/ENUN/MOVEUNIT.
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
        while (frame < 16000) { // includes the ~3700-frame opening monologue
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
        // The beginning events run for real (no skip: START is never pressed) with the release
        // stand-ins, load the player units and end; the phase then idles. The dialogue is real
        // now, so A keeps being pressed (every 20 frames) to page through it.
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 20000) {
            boot.frame()
            frame++
        }
        frame = 1 // no keys from here: the lambda reads frame % 20 == 1
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
        // MOVEUNIT / ENUN walk the units (MuCtrl); ram_trace_first_phase r19400 has the
        // blue units Seth (char 2) at (4,4) and Eirika (char 1) at (4,5).
        val blue = (1..0x3E).filter { boot.units.isValid(it) }
            .associate { boot.units.charId(it) to (boot.units.xPos(it) to boot.units.yPos(it)) }
        assertEquals(4 to 4, blue[2], "Seth")
        assertEquals(4 to 5, blue[1], "Eirika")
        // No MU of the opening events may be left. The one MU the idle loop may own is the hover MU
        // (UnitSpriteHoverUpdate starts it 5 frames after the cursor rests on a player unit): it
        // belongs to the unit under the cursor, and it is the only MU.
        val first = boot.engine.find(boot.mu.script)
        if (first != null) {
            assertEquals(null, boot.engine.findAfter(boot.mu.script, first), "more than one MU left")
            val bm = boot.loop.bmSt
            val hovered = (1..0x3E).firstOrNull {
                boot.units.isValid(it) && boot.units.xPos(it) == bm.playerCursorX &&
                    boot.units.yPos(it) == bm.playerCursorY
            }
            assertNotNull(hovered, "an event MU is left: no unit under the cursor")
            assertEquals(
                UnitTable.BLUE_ADDRESS + boot.units.unitOffset(hovered!!),
                (first.state as MuProc).unitAddress,
                "an event MU is left: the MU is not the hover MU of the unit under the cursor",
            )
        }
    }

    @Test
    fun `unit sprites are drawn while the beginning events run`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        var frame = 0
        var events = false
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            { if (!events && frame % 20 in 5..8) 0x0001 else 0 },
            RngService(),
        )
        while (frame < 7000 && !boot.eventEngine.eventEngineExists()) {
            boot.frame()
            frame++
        }
        events = true
        var handleFrames = 0
        var objFrames = 0
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) {
            frame++
            boot.frame()
            val bytes = boot.units.bytes
            // ENUN and the MU end rebuild the unit sprite handles (pMapSpriteHandle).
            if ((1..0x3E).any {
                    boot.units.isValid(it) && (0x3C..0x3F).any { d ->
                        bytes[boot.units.unitOffset(it) + d].toInt() != 0
                    }
                }
            ) {
                handleFrames++
            }
            // An OBJ is on screen: attr0 display mode is not "hidden" (bits 8-9 = 0b10).
            val oam = boot.loop.oam.oam
            if ((0 until oam.size / 4).any { (oam[it * 4].toInt() ushr 8) and 3 != 2 }) objFrames++
        }
        assertTrue(handleFrames > 0, "no unit sprite handle while the beginning events ran")
        assertTrue(objFrames > 0, "no OBJ drawn while the beginning events ran")
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
                    // Without START the dialogue is paged with A, as on a device.
                    !pressStart && frame % 20 in 5..8 -> 0x0001
                    else -> 0
                }
            },
            RngService(),
        )
        // Phase 1 (A presses) runs until the beginning events start: with the game locked
        // during events, player phase only starts after they end.
        while (frame < 7000 && !boot.eventEngine.eventEngineExists()) {
            boot.frame()
            frame++
        }
        phase2 = true
        var frames = 0
        var skipped = false
        while (boot.eventEngine.eventEngineExists() && frames < 20000) {
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
