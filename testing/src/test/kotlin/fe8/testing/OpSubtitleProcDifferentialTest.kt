package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.opanim.OpSubtitle
import fe8.game.opanim.OpSubtitleSlides
import gba.core.VerifiesPort
import gba.extract.Symbols
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.PaletteFade
import gbafe.render.ScreenFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Parent script structure and uninterrupted monologue state against the original run. The replay
 * ends with the real [ScreenFade] slow fade to black (speed 4, `StartSlowFadeToBlack` then
 * `WaitForFade`): steps 4799..4863 compare the fade proc's existence, cursor, `unk64`/`unk66`, and
 * (steps 4800, 4830, 4860) BLDCNT, blend coefficients, BLDY and the four blend-enable WINCNT bits
 * (other WINCNT bits are game code this replay omits). Not covered: the fast fade, fade to white.
 */
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
    @VerifiesPort(
        "StartSlowFadeToBlack", "StartFadeToBlack", "FadeToBlack_OnInit", "FadeToCommon_OnLoop",
        "WaitForFade",
    )
    fun `continuous playback follows the original parent through the first cards`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val trace = capture.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        var frame = 1213
        var fadeStarted = false
        var fadeSteps = 0
        val names = ProcArrayDump.romNames()
        val sym = RamSymbols.fromEnvironment()
        val screenFade = ScreenFade(engine, hardware.lcd)
        val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val host = OpSubtitle(
            engine, hardware, rom, SoundWrapper(engine, null) { 0 },
            OpSubtitle.Hooks(
                newFadeIn = { speed, p ->
                    assertEquals(2, speed)
                    fadeStarted = true
                    fade.newFadeIn(speed, p)
                },
                // The real generic fade proc (tree 4) renders the palette; the run loop below
                // runs every proc tree each step, in index order.
                fadeInExists = { fade.exists() },
                startSlowFadeToBlack = { screenFade.startSlowFadeToBlack() },
                startFastFadeToBlack = { screenFade.startFastFadeToBlack() },
                waitForFade = { screenFade.waitForFade(it) },
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
        // Long callbacks. A call command (opcode 0x02) that is the parent's cursor target at a
        // captured step is a scene-setup callback spanning video frames: VBlank keeps ticking
        // (and runs tree 0) inside it, so steps in the span are not completed updates. This
        // engine runs the callback atomically in one update: it runs at the span's first step
        // and is compared at the step after the span, where the original's update has completed
        // (cursor, timers and RAM then match). No engine runs happen in between.
        fun cursorAt(step: Int): Int? =
            ProcArrayDump.ofWatch(trace, step).find(0x08AA21BC)?.scrCur
        var spanEnd = 0
        val spans = mutableListOf<IntRange>()
        for (step in 1214..4896) {
            frame = step
            val at = cursorAt(step)!!
            if (step > spanEnd) {
                if (host.script.cmds[at - 1].opcode == 0x02) {
                    var last = step
                    while (cursorAt(last + 1) == at) last++
                    spans += step..last
                    spanEnd = last + 1
                }
                for (tree in 0..7) engine.run(engine.root(tree))
            }
            if (step <= spanEnd - 1) continue
            if (step in 4795..4870) {
                val orig = ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_FadeToBlack")
                val ours = engine.find(screenFade.toBlack)
                assertEquals(orig != null, ours != null, "step $step fade proc exists")
                assertEquals(orig != null, screenFade.fadeExists(), "step $step FadeExists")
                if (orig != null && ours != null) {
                    val st = ours.state as ScreenFade.State
                    assertEquals(orig.scrCur, ours.scrCur, "step $step fade cursor")
                    assertEquals(orig.u16(0x64).toShort().toInt(), st.speed, "step $step unk64")
                    assertEquals(orig.u16(0x66).toShort().toInt(), st.level, "step $step unk66")
                    fadeSteps++
                }
                if (step % 30 == 0) {
                    val prefix = if (step % 300 == 0) "f" else "r"
                    val iwram = capture.region("$prefix%05d".format(step), "iwram")
                    val want = LcdControl().apply {
                        readFrom(iwram, sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
                    }
                    val lcd = hardware.lcd
                    assertEquals(want.bldcnt, lcd.bldcnt, "step $step bldcnt")
                    assertEquals(want.blendCoeffA, lcd.blendCoeffA, "step $step blendCoeffA")
                    assertEquals(want.blendCoeffB, lcd.blendCoeffB, "step $step blendCoeffB")
                    assertEquals(want.blendY, lcd.blendY, "step $step blendY")
                    // Only the four blend-enable bits: other WININ/WOUT bits are set by game code
                    // this replay does not model.
                    val blendBits = (1 shl LcdControl.WIN0_ENABLE_BLEND) or
                        (1 shl LcdControl.WIN1_ENABLE_BLEND) or
                        (1 shl LcdControl.WOUT_ENABLE_BLEND) or (1 shl LcdControl.WOBJ_ENABLE_BLEND)
                    assertEquals(
                        want.wincnt and blendBits, lcd.wincnt and blendBits, "step $step wincnt",
                    )
                }
            }
            val original = ProcArrayDump.ofWatch(trace, step).find(0x08AA21BC)!!
            val state = child.state as OpSubtitleSlides.State
            assertEquals(original.scrCur, child.scrCur, "step $step cursor")
            assertEquals(original.u8(0x29).toByte().toInt(), state.index, "step $step index")
            assertEquals(original.u16(0x2A).toShort().toInt(), state.holdTimer, "step $step hold")
            assertEquals(
                original.u16(0x2C).toShort().toInt(), state.transitionTimer, "step $step timer"
            )
            if (step == 1230 || step == 1260 || (step >= 2370 && step % 30 == 0)) {
                val prefix = if (step % 300 == 0) "f" else "r"
                val ewram = capture.region("$prefix%05d".format(step), "ewram")
                fun half(at: Int): Int = (ewram[at].toInt() and 0xFF) or
                    ((ewram[at + 1].toInt() and 0xFF) shl 8)
                val expected = ShortArray(512) { half(0x228A8 + 2 * it).toShort() }
                // Entries 273 and 417 (banks 17 and 26, color 1) hold 0x0842 in the original, a
                // value other game code writes to the live buffer and this replay does not
                // model; the fade of an archive of zero there yields 0. All other 510 entries
                // are produced by the fade (not a phase offset: both frames match elsewhere).
                for (at in listOf(273, 417)) expected[at] = hardware.palette[at]
                assertArrayEquals(expected, hardware.palette, "step $step palette")
                if (step >= 2370) {
                    for (bank in 0..1) {
                        val map = ShortArray(1024) {
                            half(0x22CA8 + bank * 0x800 + 2 * it).toShort()
                        }
                        assertArrayEquals(map, hardware.tilemaps[bank], "step $step BG$bank map")
                    }
                }
            }
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
        assertEquals(listOf(2355..2356, 2425..2427, 3228..3228), spans)
        // The original proc is gone at step 4897; this engine's final update ends it too.
        assertEquals(null, cursorAt(4897))
        frame = 4897
        for (tree in 0..7) engine.run(engine.root(tree))
        assertEquals(null, child.script)
        assertEquals(0, parent.lockCnt)
        assertTrue(fadeStarted)
        assertEquals(65, fadeSteps)
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
