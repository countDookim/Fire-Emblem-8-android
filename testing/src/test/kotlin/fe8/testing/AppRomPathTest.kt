package fe8.testing

import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The app's game object ([Fe8Game] with sound, [Fe8Game.present] every frame) on the two ROM
 * sources the APK uses (M5-61): the whole imported ROM, or the extracted boot/sound windows of a
 * debug APK without an import. Regression for the 2026-11-06 device crash: START on the title
 * with windows only threw "ROM address ... not in this view" once New Game read chapter data.
 * Not a port test (no @VerifiesPort).
 */
class AppRomPathTest {
    private val romFile = File(System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    fun `windows only - START and A on the title neither crash nor start New Game`() {
        assumeTrue(romFile.isFile)
        val rom = romFile.readBytes()
        val windows = RomView(
            (RomView.BOOT_WINDOWS + RomView.SOUND_WINDOWS).map { (start, end) ->
                start to rom.copyOfRange(start - RomView.ROM_BASE, end - RomView.ROM_BASE)
            },
        )
        val game = Fe8Game(rom = windows, sound = Fe8Sound.create(windows))
        val boot = game.boot!!
        var frame = 0
        game.actionSource = gba.input.ActionSource { pressFor(frame) }
        while (frame < FRAMES) {
            game.tick()
            game.present(0f)
            frame++
        }
        assertFalse(boot.engine.find(boot.playerPhaseScript) != null, "reached the battle map")
    }

    @Test
    fun `whole ROM - New Game reaches the Prologue player phase through Fe8Game`() {
        assumeTrue(romFile.isFile)
        val full = RomView.ofRom(romFile.readBytes())
        val game = Fe8Game(rom = full, sound = Fe8Sound.create(full))
        val boot = game.boot!!
        var frame = 0
        game.actionSource = gba.input.ActionSource { pressFor(frame) }
        while (frame < FRAMES && boot.engine.find(boot.playerPhaseScript) == null) {
            game.tick()
            game.present(0f)
            frame++
        }
        assertTrue(boot.engine.find(boot.playerPhaseScript) != null, "no player phase by f$frame")
        repeat(600) {
            game.tick()
            game.present(0f)
            frame++
        }
    }

    /** Issue #4: one START on the title reached a player phase that rendered all black. */
    @Test
    fun `whole ROM - one START on the title renders the Prologue map, not a black screen`() {
        assumeTrue(romFile.isFile)
        val full = RomView.ofRom(romFile.readBytes())
        val game = Fe8Game(rom = full, sound = Fe8Sound.create(full))
        val boot = game.boot!!
        var frame = 0
        var pressAt = Int.MAX_VALUE
        game.actionSource = gba.input.ActionSource {
            if (frame - pressAt in 0..3) 0x8L else 0L
        }
        var reached = -1
        while (frame < 12000 && (reached < 0 || frame < reached + 300)) {
            if (pressAt == Int.MAX_VALUE &&
                boot.engine.procs.any { it.script?.name == "gProcScr_DrawTitleSprites" }
            ) {
                pressAt = frame + 30
            }
            game.tick()
            game.present(0f)
            frame++
            if (reached < 0 && boot.engine.find(boot.playerPhaseScript) != null) reached = frame
        }
        assertTrue(reached >= 0, "no player phase by f$frame")
        val colours = game.frame.pixels.toSet().size
        assertTrue(colours > 8, "rendered frame has only $colours colours (blendY ${boot.ui.env.hardware.lcd.blendY})")
    }

    private companion object {
        const val FRAMES = 6000

        /** A for frames 5..8 of every 20, START for frames 15..16 of every 40. */
        fun pressFor(frame: Int): Long =
            (if (frame % 20 in 5..8) 0x1L else 0L) or (if (frame % 40 in 15..16) 0x8L else 0L)
    }
}
