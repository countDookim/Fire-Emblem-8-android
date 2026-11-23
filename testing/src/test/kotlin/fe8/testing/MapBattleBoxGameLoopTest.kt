package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.text.Font
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The map battle info box as the game loop wires it (D-19, D-22), against the Prologue captures
 * `ram_trace_prologue_enemy_phase` and `ram_trace_prologue_clear`.
 */
class MapBattleBoxGameLoopTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private fun available() = ref.available && File(assets, "baserom.gba").isFile &&
        ProcArrayDump.romNames().isNotEmpty()

    /** A boot resumed from the Prologue save, fed the keys of [runName] from the first idle frame. */
    private fun boot(runName: String, frameOf: () -> Int, idleAt: () -> Int, idleFrame: Int): Fe8Boot {
        val run = ref.run(runName)
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        return Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt() < 0) pulse[if (frameOf() % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frameOf() - idleAt() + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = storage,
        )
    }

    private fun colorOf(rgb: ByteArray, p: Int) =
        (((rgb[p * 3].toInt() and 255) shl 16) or ((rgb[p * 3 + 1].toInt() and 255) shl 8) or
            (rgb[p * 3 + 2].toInt() and 255)) and 0xF8F8F8

    @Test
    fun `enemy initiated map battle draws the info box frame like the capture`() {
        assumeTrue(available(), "reference data / ROM")
        val run = ref.run("ram_trace_prologue_enemy_phase")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        // The capture's first enemy battle: the info box exists from step 1706, drawn by 1760.
        val capStart = (1100 until 2000).first {
            ProcArrayDump.ofWatch(trace, it, names).find("ProcScr_MapBattleInfoBox") != null
        }
        val sample = capStart + 54
        val rgb = run.frameRgb("f%05d".format(sample))
        val bldcnt = run.region("f%05d".format(sample), "io").let {
            (it[0x50].toInt() and 255) or ((it[0x51].toInt() and 255) shl 8)
        }
        var frame = 0
        var idleAt = -1
        val boot = boot("ram_trace_prologue_enemy_phase", { frame }, { idleAt }, idleFrame)
        var start = -1
        var checked = false
        while (frame < 3000 && !checked) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
            val box = boot.engine.procs.any { it.script?.name == "ProcScr_MapBattleInfoBox" }
            if (box && start < 0) start = frame
            if (box && frame - start == 54) {
                val lcd = boot.ui.env.hardware.lcd
                assertEquals(bldcnt shr 6 and 3, lcd.bldcnt shr 6 and 3, "BLDCNT effect")
                boot.render()
                var diff = 0
                for (y in 112..140) for (x in 0 until 240) {
                    val p = y * 240 + x
                    if ((boot.screen.pixels[y * boot.screen.width + x] and 0xF8F8F8) !=
                        colorOf(rgb, p)
                    ) diff++
                }
                assertEquals(0, diff, "info box rows differ from the capture")
                checked = true
            }
        }
        assertTrue(checked, "no enemy-initiated info box reached")
    }

    @Test
    fun `battle quote end restores the system text font and UI frame palette`() {
        assumeTrue(available(), "reference data / ROM")
        val clear = ref.run("ram_trace_prologue_clear")
        val names = ProcArrayDump.romNames()
        val trace = clear.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        var frame = 0
        var idleAt = -1
        val boot = boot("ram_trace_prologue_clear", { frame }, { idleAt }, idleFrame)
        while (frame < 1500 && (idleAt < 0 || frame < idleAt + 5)) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
        }
        val text = boot.talkScene.text
        // A battle quote leaves the talk font (palette bank 2) active.
        text.initTextFont(Font(), 0x06000000, 0x100, 2)
        text.initTalkTextFont()
        assertEquals(2, text.activeFont.palid)
        val proc = EventEngineProc().also { it.execType = EventConst.EV_EXEC_QUIET }
        boot.eventEngine.onEnd(proc)
        assertSame(text.defaultFont, text.activeFont, "SetTextFont(NULL)")
        // Bank 0 as the capture holds it right after the battle quote (f04460).
        val pal = clear.region("f04460", "pal")
        val hw = boot.ui.env.hardware
        for (i in 0 until 16) {
            val want = (pal[2 * i].toInt() and 255) or ((pal[2 * i + 1].toInt() and 255) shl 8)
            assertEquals(want, hw.palette[i].toInt() and 0xFFFF, "BG palette bank 0 colour $i")
        }
    }
}
