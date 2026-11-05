package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-47: the cursor handling of [PlayerPhase] against the per-frame RAM dumps (`r<frame>`) of the
 * cursor holds. Each frame is seeded from dump f-1 (`gBmSt`, `gPlaySt`) and the key status of
 * dump f, runs `HandlePlayerCursorMovement` once, and `playerCursor`, `playerCursorDisplay` and
 * `camera` (plus `cursorTarget` and `gameStateBits`) are compared with dump f. Frames whose key
 * status equals the next dump's (a lag frame dumped mid-frame) are skipped.
 */
class PlayerPhaseCursorDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "HandlePlayerCursorMovement", "HandleMapCursorInput", "HandleMoveMapCursor",
        "HandleMoveCameraWithMapCursor",
    )
    fun `first phase cursor holds match the game (map fits the screen)`() {
        val c = run("ram_trace_first_phase", 19421, 19520)
        assertTrue(c.compared >= 60 && c.cursorMoves >= 30 && c.cameraMoves == 0, "$c")
    }

    @Test
    @VerifiesPort(
        "HandlePlayerCursorMovement", "HandleMapCursorInput", "HandleMoveMapCursor",
        "HandleMoveCameraWithMapCursor",
    )
    fun `chapter 2 cursor holds scroll the camera like the game`() {
        val c = run("ram_trace_ch2_scroll", 1301, 1570)
        assertTrue(c.compared >= 150 && c.cursorMoves >= 20 && c.cameraMoves >= 10, "$c")
    }

    @Test
    @VerifiesPort(
        "HandlePlayerCursorMovement", "HandleMapCursorInput", "HandleMoveMapCursor",
        "HandleMoveCameraWithMapCursor",
    )
    fun `chapter 6 fog cursor holds scroll both axes like the game`() {
        val c = run("ram_trace_ch6_fog", 2004, 2250)
        assertTrue(c.compared >= 150 && c.cursorMoves >= 100 && c.cameraMoves >= 50, "$c")
    }

    private fun frameName(f: Int) = "r%05d".format(f)

    private fun run(name: String, first: Int, last: Int): Checks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(name)
        fun has(f: Int) = File(ref.root, "$name/${frameName(f)}.ewram.bin").isFile
        fun ew(f: Int) = run.region(frameName(f), "ewram")
        fun keyStruct(f: Int) =
            ew(f).copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)
        val rom = RomView.ofRom(romFile.readBytes())
        val playSt = PlaySt().also { it.decode(ew(first), PLAY_ST - EWRAM) }
        val bm = BmSt()
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom)
        val engine = ProcEngine()
        val keys = KeyStatus()
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, CamMove(engine, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys, { false },
        )
        val c = Checks()
        for (f in first..last) {
            if (!has(f) || !has(f - 1) || !has(f + 1)) continue
            if (keyStruct(f + 1).contentEquals(keyStruct(f))) {
                c.skipped++
                continue
            }
            bm.decode(ew(f - 1), BM_ST - EWRAM)
            playSt.decode(ew(f - 1), PLAY_ST - EWRAM)
            keys.readFrom(keyStruct(f))
            val displayBefore = bm.playerCursorDisplayX + bm.playerCursorDisplayY
            val camXBefore = bm.cameraX
            val camYBefore = bm.cameraY
            phase.handlePlayerCursorMovement()
            val want = BmSt().also { it.decode(ew(f), BM_ST - EWRAM) }
            val at = "$name f$f"
            assertEquals(want.playerCursorX, bm.playerCursorX, "$at cursor x")
            assertEquals(want.playerCursorY, bm.playerCursorY, "$at cursor y")
            assertEquals(want.playerCursorDisplayX, bm.playerCursorDisplayX, "$at display x")
            assertEquals(want.playerCursorDisplayY, bm.playerCursorDisplayY, "$at display y")
            assertEquals(want.cameraX, bm.cameraX, "$at camera x")
            assertEquals(want.cameraY, bm.cameraY, "$at camera y")
            assertEquals(want.gameStateBits, bm.gameStateBits, "$at gameStateBits")
            assertEquals(want.cursorTargetX, bm.cursorTargetX, "$at target x")
            assertEquals(want.cursorTargetY, bm.cursorTargetY, "$at target y")
            c.compared++
            if (displayBefore != bm.playerCursorDisplayX + bm.playerCursorDisplayY) c.cursorMoves++
            if (camXBefore != bm.cameraX || camYBefore != bm.cameraY) c.cameraMoves++
        }
        println("PlayerPhaseCursor $name: $c")
        return c
    }

    private class Checks {
        var compared = 0
        var skipped = 0
        var cursorMoves = 0
        var cameraMoves = 0

        override fun toString() =
            "compared=$compared skipped=$skipped cursorMoves=$cursorMoves cameraMoves=$cameraMoves"
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
    }
}
