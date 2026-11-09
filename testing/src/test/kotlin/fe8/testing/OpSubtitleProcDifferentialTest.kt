package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.opanim.OpSubtitle
import fe8.game.opanim.OpSubtitleSlides
import gba.extract.Symbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Parent script structure and uninterrupted monologue state against the original run. */
class OpSubtitleProcDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")

    @Test
    fun `opcodes immediates and child targets match the ROM`() {
        val nm = ProcArrayDump.nmFile()
        assumeTrue(ref.available && romFile.isFile && nm != null, "ROM or symbols unavailable")
        val rom = RomView.ofRom(romFile.readBytes())
        val symbols = Symbols(nm!!.readLines())
        val engine = ProcEngine()
        val host = OpSubtitle(engine, BgHardware(), rom, SoundWrapper(engine, null) { 0 }, hooks())
        val names = listOf(
            null, "OpSubtitle_Init", null, "NewFadeIn", "FadeInExists",
            "OpSubtitle_AwaitTimer2a", null, "sub_80C4C60", "sub_80C4DA0",
            "OpSubtitle_AwaitTimer2a", "sub_80C4E18", "OpSubtitle_AwaitTimer2a", null,
            null, "gProcScr_OpSubtitle_LightFlareFx", "gProcScr_08AA2184", "sub_80C50A0",
            "OpSubtitle_AwaitTimer2a", "sub_80C5104", "sub_80C5218",
            "OpSubtitle_AwaitTimer2a", "sub_80C501C", "OpSubtitle_AwaitTimer2a",
            "sub_80C5328", null, "sub_80C4CD0", "sub_80C4F60", "OpSubtitle_AwaitTimer2a",
            "sub_80C501C", "OpSubtitle_AwaitTimer2a", null, null, "OpSubtitle_AwaitTimer2a",
            "sub_80C5370", "sub_80C538C", "OpSubtitle_AwaitTimer2a", "sub_80C4C60",
            "sub_80C4DA0", "OpSubtitle_AwaitTimer2a", "sub_80C4EC4",
            "OpSubtitle_AwaitTimer2a", null, "gProcScr_OpSubtitle_LightFlareFx",
            "gProcScr_08AA2184", "sub_80C5400", "StartSlowFadeToBlack", "WaitForFade",
            null, null, null, "gProcScr_OpSubtitle_LightFlareFx", "gProcScr_08AA2184",
            "StartFastFadeToBlack", "WaitForFade", null, null, null, "sub_80C540C", null, null,
        )
        val flareNames = listOf(
            null, "Subtitle_LightFlareFx_Init", "Subtitle_LightFlareFx_Loop", null,
            "Subtitle_LightFlareFx_End", null,
        )
        checkScript(rom, symbols, host.script, "gProcScr_OpSubtitle", names)
        checkScript(rom, symbols, host.flareScript, "gProcScr_OpSubtitle_LightFlareFx", flareNames)
    }

    @Test
    fun `continuous playback follows the original parent through the first cards`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val trace = capture.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        var frame = 1213
        var fadeStarted = false
        val host = OpSubtitle(
            engine, hardware, rom, SoundWrapper(engine, null) { 0 },
            OpSubtitle.Hooks(
                newFadeIn = { speed, _ -> assertEquals(2, speed); fadeStarted = true },
                // The fade begins during step 1215; thereafter its original proc lifetime
                // supplies this external hook. Generic fade rendering is not exercised here.
                fadeInExists = {
                    fadeStarted && (frame == 1215 ||
                        ProcArrayDump.ofWatch(trace, frame - 1).find(0x08A20DA4) != null)
                },
                startSlowFadeToBlack = {}, startFastFadeToBlack = {},
                waitForFade = {},
                newKeys = { 0 },
            ),
        )
        val parent = engine.start(ProcScript("parent", listOf(block(), end())), 3)
        val child = host.startBlocking(parent)!!
        assertEquals(1, parent.lockCnt)
        // OpSubtitle_Init leaves timer_2c untouched; the original slot has reused memory.
        val initial = ProcArrayDump.ofWatch(trace, 1213).find(0x08AA21BC)!!
        (child.state as OpSubtitleSlides.State).transitionTimer =
            initial.u16(0x2C).toShort().toInt()
        // At creation step 1213 the original has not initialized the proc fields yet.
        // Begin after the next frame; its creation-frame cursor differs from this engine.
        // The original capture crosses the long flare setup at 2355–2356; this engine
        // executes that callback atomically, so the comparison stops before that boundary.
        for (step in 1214..2354) {
            frame = step
            engine.run(parent)
            val original = ProcArrayDump.ofWatch(trace, step).find(0x08AA21BC)!!
            val state = child.state as OpSubtitleSlides.State
            assertEquals(original.scrCur, child.scrCur, "step $step cursor")
            assertEquals(original.u8(0x29).toByte().toInt(), state.index, "step $step index")
            assertEquals(original.u16(0x2A).toShort().toInt(), state.holdTimer, "step $step hold")
            assertEquals(
                original.u16(0x2C).toShort().toInt(), state.transitionTimer, "step $step timer"
            )
            if (step in setOf(1500, 2100)) {
                val prefix = if (step % 300 == 0) "f" else "r"
                val ewram = capture.region("$prefix%05d".format(step), "ewram")
                fun half(at: Int): Short = ((ewram[at].toInt() and 0xFF) or
                    ((ewram[at + 1].toInt() and 0xFF) shl 8)).toShort()
                val expected = ShortArray(16) { half(0x228A8 + (3 * 16 + it) * 2) }
                assertArrayEquals(
                    expected, hardware.palette.copyOfRange(48, 64), "step $step palette 3",
                )
            }
        }
        assertTrue(fadeStarted)
        assertEquals(1, parent.lockCnt)
    }

    @Test
    fun `normal and Start exits clean up children and release the blocking parent`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val rom = RomView.ofRom(romFile.readBytes())
        for (skip in listOf(false, true)) {
            val engine = ProcEngine()
            var keys = 0
            var slow = 0
            var fast = 0
            val host = OpSubtitle(
                engine, BgHardware(), rom, SoundWrapper(engine, null) { 0 },
                OpSubtitle.Hooks(
                    newFadeIn = { _, _ -> }, fadeInExists = { false },
                    startSlowFadeToBlack = { slow++ }, startFastFadeToBlack = { fast++ },
                    waitForFade = { engine.breakProc(it) }, newKeys = { keys },
                ),
            )
            val parent = engine.start(ProcScript("parent", listOf(block(), end())), 3)
            val child = host.startBlocking(parent)!!
            engine.run(parent)
            engine.goto(child, 1)
            engine.run(parent)
            @Suppress("UNCHECKED_CAST")
            val finalCard = (host.script.cmds[15].ptr as () -> ProcScript)()
            assertEquals(1, engine.count(host.flareScript))
            assertEquals(1, engine.count(finalCard))
            if (skip) {
                keys = 8
                engine.run(parent)
                keys = 0
            } else {
                engine.goto(child, 5)
            }
            engine.run(parent)
            assertEquals(0, engine.count(host.flareScript))
            assertEquals(0, engine.count(finalCard))
            assertEquals(if (skip) 0 else 1, slow)
            assertEquals(if (skip) 1 else 0, fast)
            repeat(40) { engine.run(parent) }
            assertEquals(0, parent.lockCnt)
            assertEquals(null, child.script)
        }
    }

    private fun checkScript(
        rom: RomView, symbols: Symbols, script: ProcScript, symbol: String, names: List<String?>,
    ) {
        val base = symbols[symbol].address
        assertEquals(symbols[symbol].size / 8, script.cmds.size, symbol)
        assertEquals(script.cmds.size, names.size, symbol)
        for ((index, cmd) in script.cmds.withIndex()) {
            val at = base + index * 8
            assertEquals(rom.u16(at), cmd.opcode, "$symbol $index opcode")
            assertEquals(rom.u16(at + 2), cmd.imm, "$symbol $index immediate")
            val name = names[index]
            if (name == null) {
                assertEquals(0, rom.u32(at + 4), "$symbol $index pointer")
            } else {
                val expected = symbols[name].address or if (cmd.opcode in 0x02..0x04 ||
                    cmd.opcode == 0x14 || cmd.opcode == 0x18) 1 else 0
                assertEquals(expected, rom.u32(at + 4), "$symbol $index $name")
                if (cmd.opcode == 0x05 || cmd.opcode == 0x09) {
                    @Suppress("UNCHECKED_CAST")
                    val child = (cmd.ptr as () -> ProcScript)()
                    assertEquals(name, child.name, "$symbol $index child")
                }
            }
        }
    }

    private fun hooks() = OpSubtitle.Hooks(
        newFadeIn = { _, _ -> }, fadeInExists = { false }, startSlowFadeToBlack = {},
        startFastFadeToBlack = {}, waitForFade = {}, newKeys = { 0 },
    )
}
