package fe8.testing

import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import fe8.game.bm.Mu
import gba.audio.PcmStream
import gba.input.ActionSource
import gba.testkit.AllocationMeter
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Checks repeated, sustained opening walks through the production tick and GPU pack path. */
class WalkingAllocationTest {
    @Test
    fun `steady walking contains allocation-free windows`() {
        val rom = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(AllocationMeter.supported && rom.isFile, "ROM / allocation meter unavailable")
        val view = RomView.ofRom(rom.readBytes())
        val sound = Fe8Sound.create(view)
        val pcm = PcmStream(sound.info.pcmFreq, 48000, 2048)
        val output = ShortArray(804 * 2)
        val game = Fe8Game(
            rom = view,
            sound = sound,
            pcm = pcm,
        )
        game.gpuPresent = true
        val boot = game.boot!!
        var frame = 0
        game.actionSource = ActionSource { if (frame % 20 in 5..8) 1L else 0L }
        var consecutive = 0
        var windowFrames = 0
        var windowBytes = 0L
        var windows = 0
        var zeroWindows = 0
        while (frame < 16000 &&
            (frame < 1000 || boot.engine.find(boot.playerPhaseScript) == null ||
                boot.eventEngine.eventEngineExists())
        ) {
            val before = AllocationMeter.current()
            game.tick()
            game.present(0f)
            val bytes = AllocationMeter.current() - before
            // Approximate a 48 kHz single consumer; this does not reproduce audio thread timing.
            pcm.render(output, 804)
            val walking = boot.mu.configs.any {
                it.slot != 0 && it.mu?.state == Mu.MU_STATE_MOVEMENT && it.mu?.hiddenB == 0
            }
            var visibleMu = false
            val oam = boot.loop.oam.oam
            for (i in 0 until oam.size / 4) {
                val a0 = oam[i * 4].toInt() and 0xFFFF
                val tile = oam[i * 4 + 2].toInt() and 0x3FF
                if (a0 and 0x300 != 0x200 && a0 and 0xFF < 160 && tile in 0x380..0x3FF) {
                    visibleMu = true
                    break
                }
            }
            consecutive = if (walking && visibleMu) consecutive + 1 else 0
            if (consecutive > 25) {
                windowFrames++
                windowBytes += bytes
                if (windowFrames == 10) {
                    windows++
                    if (windowBytes == 0L) zeroWindows++
                    windowFrames = 0
                    windowBytes = 0
                }
            } else {
                windowFrames = 0
                windowBytes = 0
            }
            frame++
        }
        assertTrue(
            boot.engine.find(boot.playerPhaseScript) != null &&
                !boot.eventEngine.eventEngineExists(),
            "Prologue idle not reached within $frame frames",
        )
        println("Sustained visible walking: $windows windows, $zeroWindows allocation-free")
        // Other game systems can allocate at animation and event transitions. Each counted window
        // contains ten visible walking frames after the first 25, so idle cannot hide cache boxing.
        assertTrue(windows >= 10, "too few sustained walking windows: $windows")
        assertTrue(zeroWindows >= 3, "allocation-free walking windows: $zeroWindows of $windows")
    }
}
