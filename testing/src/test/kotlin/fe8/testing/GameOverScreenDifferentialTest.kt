package fe8.testing

import fe8.game.RomView
import fe8.game.bm.GameOverScreen
import fe8.game.bm.ScreenFogEffect
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.ColorFade
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Differential test of the game over screen set-up and scroll (`PC-8a`) on
 * `ram_trace_prologue_gameover` (PC-cap2), `f5953..6293` (the scroll runs to the pass of `f6292`,
 * the screen proc ends in the next, PC-8b). The fades (`LoopFadeIn` from f5954 on,
 * `LoopFadeOut`) and the teardown are PC-8b and `GameOverScreenHBlank` is PC-8h: stand-ins here,
 * so the palette is compared only where no fade step has run yet (the state after the set-up) and
 * the screen proc is never ended.
 *
 * Alignment: the capture's `f5953` is taken inside the long pass that starts the screen
 * (`StartGameOverScreen` runs `GameOverScreen_Init`, which spans a VBlank: `GameOverScreen`
 * exists but not its scroll child, the `BmGameOver` cursor is still on `PROC_YIELD`). The port
 * runs that pass atomically in step 5953, which is compared with the dump of `f5954`, and every
 * later step `f` with the dump of `f + 1` (the same shift as the chapter intro's
 * `ChapterIntroDifferentialTest`); the I/O register watch is one dump later again (the buffer is
 * copied to the registers in the following VBlank). The sim starts from the dump of `f5952`
 * (VRAM, palette, tilemap buffers, `gLCDControlBuffer`, fade state) and starts the screen from a
 * stand-in for `ProcScr_BmGameOver` (a child of a blocking stand-in for the event engine) the
 * frame after, as the real script does.
 */
@Timeout(120)
class GameOverScreenDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private inner class Sim {
        val run = ref.run(RUN)
        val dumps = run.dumps.entries.associate { it.value to it.key }
        val procNames = ProcArrayDump.romNames()
        val procs = run.watch("procs")
        val io = run.watch("displayio")
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        val colorFade = ColorFade(hardware.palette)
        val clock = run.watch("clock")
        val keys = run.watch("keys")
        val pal = run.watch("pal")

        /** The port's step being run; the clock and keys of the capture follow it by one dump. */
        var now = FIRST
        var steps = FIRST
        var suspends = 0
        var fadeOuts = 0
        var bgms = 0
        var endAllMus = 0
        val screen = GameOverScreen(
            engine, hardware, colorFade, rom,
            object : GameOverScreen.Hooks {
                override fun bMapDispSuspend() {
                    suspends++
                }

                override fun startGameOverBgm() {
                    bgms++
                }

                override fun endAllMus() {
                    endAllMus++
                }

                override fun gameClock(): Int = clock.u32(now + CLOCK_SHIFT, 0)

                override fun newKeys(): Int = keys.u16(now + KEYS_SHIFT, 8)

                override fun fadeOutBgm(speed: Int) {
                    assertEquals(4, speed, "Sound_FadeOutBGM speed")
                    fadeOuts++
                }
            },
        )

        fun ewram(frame: Int): ByteArray = run.region(dumps.getValue(frame), "ewram")

        fun seed() {
            val e = ewram(SEED)
            hardware.setupBackgrounds(null) // the tilemap destinations; the rest is overwritten
            hardware.lcd.readFrom(run.region(dumps.getValue(SEED), "iwram"), LCD_BUFFER - IWRAM)
            for (i in 0 until 512) hardware.palette[i] = u16(e, PALETTE - EWRAM + 2 * i).toShort()
            for (bg in 0..3) {
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) hardware.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            e.copyInto(colorFade.step, 0, FADE_STEP - EWRAM, FADE_STEP - EWRAM + 32)
            e.copyInto(colorFade.components, 0, FADE_COMPONENTS - EWRAM,
                FADE_COMPONENTS - EWRAM + 0x600)
            // The capture has no running fades at f5952: poison every step so that only
            // MaybeResetSomePal can clear the ones the set-up does not restart.
            colorFade.step.fill(1)
            run.region(dumps.getValue(SEED), "vram").copyInto(hardware.vram)
            val parent = engine.start(ProcScript("event", listOf(block(), end())), 3)
            // ProcScr_BmGameOver at its StartGameOverScreen call: runs in the next walk.
            engine.startBlocking(
                ProcScript(
                    "BmGameOver",
                    listOf(
                        yield(),
                        call(ProcFunc { screen.startGameOverScreen(it) }),
                        block(),
                        end(),
                    ),
                ),
                parent,
            )
        }

        /** One pass: the proc trees, then the VBlank sync. */
        fun step() {
            now = steps++
            for (tree in 0 until 8) engine.run(engine.root(tree))
            hardware.flushBackgrounds()
        }

        fun dumpOrNull(f: Int) = dumps[f]

        fun dump(f: Int) = ProcArrayDump.ofWatch(procs, f, procNames)
    }

    @Test
    @VerifiesPort(
        "StartGameOverScreen", "GameOverScreen_RandomScroll_Init",
        "GameOverScreen_RandomScroll_Loop",
    )
    fun `screen start and scroll match PC-cap2 procs and BG2 BG3 scroll every frame`() {
        val sim = begin() ?: return
        for (f in FIRST..LAST) {
            sim.step()
            val d = sim.dump(f + 1)
            val at = "f$f"
            assertNotNull(sim.engine.find(sim.screen.script), "$at screen proc")
            val scrollProc = d.find("ProcScr_GameOverScreen_RandomScroll")
            assertNotNull(scrollProc, "$at captured scroll proc (dump ${f + 1})")
            val ours = sim.engine.find(sim.screen.scrollScript)
            assertNotNull(ours, "$at scroll proc")
            val state = ours!!.state as GameOverScreen.ScrollState
            val orig = scrollProc!!
            assertEquals(orig.u32(0x34), state.bg2Xdiff, "$at bg2_xdiff")
            assertEquals(orig.u32(0x38), state.bg2Ydiff, "$at bg2_ydiff")
            assertEquals(orig.u32(0x3C), state.bg3Xdiff, "$at bg3_xdiff")
            assertEquals(orig.u32(0x40), state.bg3Ydiff, "$at bg3_ydiff")
            assertEquals(s16(orig.u16(0x64)), state.bg2Xpos, "$at bg2_xpos")
            assertEquals(s16(orig.u16(0x66)), state.bg2Ypos, "$at bg2_ypos")
            assertEquals(s16(orig.u16(0x68)), state.bg3Xpos, "$at bg3_xpos")
            assertEquals(s16(orig.u16(0x6A)), state.bg3Ypos, "$at bg3_ypos")
            // The registers follow the buffer one dump later.
            val lcd = sim.hardware.lcd
            val regs = f + 2
            assertEquals(sim.io.u16(regs, IO_BG2HOFS), lcd.bgHofs[2] and 0x1FF, "$at BG2HOFS")
            assertEquals(sim.io.u16(regs, IO_BG2HOFS + 2), lcd.bgVofs[2] and 0x1FF, "$at BG2VOFS")
            assertEquals(sim.io.u16(regs, IO_BG3HOFS), lcd.bgHofs[3] and 0x1FF, "$at BG3HOFS")
            assertEquals(sim.io.u16(regs, IO_BG3HOFS + 2), lcd.bgVofs[3] and 0x1FF, "$at BG3VOFS")
            // Start 1234,5678 / 6346,8536, steps 46,-90 / -16,-53, one step per frame.
            val k = f - FIRST + 1
            assertEquals(1234 + 46 * k, state.bg2Xpos, "$at formula")
            assertEquals(8536 - 53 * k, state.bg3Ypos, "$at formula")
        }
        assertEquals(1, sim.suspends, "BMapDispSuspend once")
        assertEquals(1, sim.bgms, "StartBgm once")
        assertEquals(1, sim.endAllMus, "EndAllMus once")
    }

    @Test
    @VerifiesPort(
        "GameOverScreen_LoopFadeIn", "GameOverScreen_BeginIdle", "GameOverScreen_LoopIdle",
        "GameOverScreen_BeginFadeOut", "GameOverScreen_LoopFadeOut", "GameOverScreen_End",
    )
    fun `fades idle and teardown match PC-cap2 counters palette fade state and procs`() {
        val sim = begin() ?: return
        var fadeSteps = 0
        var palettesCompared = 0
        var fadeStateCompared = 0
        var lastCounter1 = Int.MIN_VALUE
        var endedAt = -1
        for (f in FIRST..LAST_TEARDOWN) {
            sim.step()
            val at = "f$f"
            val d = sim.dump(f + 1)
            val want = d.find("ProcScr_GameOverScreen")
            val ours = sim.engine.find(sim.screen.script)
            assertEquals(want != null, ours != null, "$at screen proc presence (dump ${f + 1})")
            assertEquals(
                d.find("ProcScr_GameOverScreen_RandomScroll") != null,
                sim.engine.find(sim.screen.scrollScript) != null, "$at scroll proc presence",
            )
            if (want != null) {
                val state = ours!!.state as GameOverScreen.State
                assertEquals(s16(want.u16(0x4C)), state.counter1, "$at counter1")
                if (state.counter2 != 0) {
                    assertEquals(s16(want.u16(0x4E)), state.counter2, "$at counter2")
                }
                if (state.counter1 != lastCounter1) fadeSteps++
                lastCounter1 = state.counter1
            } else if (endedAt < 0) {
                endedAt = f + 1
            }
            // The palette RAM follows the buffer one dump later.
            // Past f6293 the capture's palette belongs to the next scene (not this screen's).
            if (f <= LAST_PALETTE) {
                val palRam = sim.pal.step(f + 2)
                for (i in 0 until 512) {
                    assertEquals(u16(palRam, 2 * i), sim.hardware.paletteRam[i].toInt() and 0xFFFF,
                        "$at palette RAM[$i]")
                }
                palettesCompared++
            }
            val lcd = sim.hardware.lcd
            assertEquals(sim.io.u16(f + 2, IO_DISPCNT), lcd.dispcnt, "$at DISPCNT")
            assertEquals(sim.io.u16(f + 2, IO_BLDY), lcd.blendY, "$at BLDY")
            sim.dumpOrNull(f + 1)?.takeIf { f <= LAST_PALETTE }?.let { key ->
                val e = sim.run.region(key, "ewram")
                for (i in 0 until 512) {
                    assertEquals(u16(e, PALETTE - EWRAM + 2 * i),
                        sim.hardware.palette[i].toInt() and 0xFFFF, "$at palette buffer[$i]")
                }
                for (i in 0 until 32) {
                    assertEquals(e[FADE_STEP - EWRAM + i], sim.colorFade.step[i], "$at fade step $i")
                }
                for (i in 0 until 0x600) {
                    assertEquals(e[FADE_COMPONENTS - EWRAM + i], sim.colorFade.components[i],
                        "$at fade component $i")
                }
                fadeStateCompared++
            }
        }
        assertNull(sim.hardware.hblank.primary, "End removes the primary HBlank handler")
        assertNull(sim.hardware.hblank.secondary, "secondary HBlank handler")
        assertEquals(6294, endedAt, "the screen proc is gone at the dump of f6294")
        assertEquals(1, sim.fadeOuts, "Sound_FadeOutBGM once")
        assertTrue(fadeSteps >= 22 + 33, "counter1 changes: $fadeSteps")
        assertTrue(palettesCompared > 300 && fadeStateCompared > 50,
            "compared $palettesCompared palettes, $fadeStateCompared fade states")
    }

    @Test
    @VerifiesPort(
        "GameOverScreen_Init", "PutScreenFogEffect", "PutScreenFogEffectOverlayed",
        "MaybeResetSomePal", "MaybeSmoothChangeSomePal",
    )
    fun `init matches PC-cap2 tilemaps VRAM palette fade state LCD and procs`() {
        val sim = begin() ?: return
        sim.step() // f5953
        val key = sim.dumps.getValue(FIRST + 1)
        val e = sim.run.region(key, "ewram")
        val lcdWant = LcdControl().also {
            it.readFrom(sim.run.region(key, "iwram"), LCD_BUFFER - IWRAM)
        }
        val lcd = sim.hardware.lcd
        assertEquals(lcdWant.dispcnt, lcd.dispcnt, "dispcnt")
        assertEquals(lcdWant.dispstat, lcd.dispstat, "dispstat (HBlank IRQ enable)")
        for (bg in 0..3) {
            assertEquals(lcdWant.bgCnt[bg], lcd.bgCnt[bg], "bgcnt$bg")
            assertEquals(lcdWant.bgHofs[bg], lcd.bgHofs[bg], "hofs$bg")
            assertEquals(lcdWant.bgVofs[bg], lcd.bgVofs[bg], "vofs$bg")
        }
        assertEquals(lcdWant.wincnt, lcd.wincnt, "wincnt")
        assertEquals(lcdWant.bldcnt, lcd.bldcnt, "bldcnt")
        assertEquals(lcdWant.blendCoeffA, lcd.blendCoeffA, "blendCoeffA")
        assertEquals(lcdWant.blendCoeffB, lcd.blendCoeffB, "blendCoeffB")
        assertEquals(lcdWant.blendY, lcd.blendY, "blendY")
        for (bg in 0..3) {
            val at = BG_BUFFERS[bg] - EWRAM
            for (i in 0 until 0x400) {
                assertEquals(u16(e, at + 2 * i), sim.hardware.mapBuffer(bg)[i].toInt() and 0xFFFF,
                    "BG$bg tilemap[$i]")
            }
        }
        for (i in 0 until 512) {
            assertEquals(u16(e, PALETTE - EWRAM + 2 * i),
                sim.hardware.palette[i].toInt() and 0xFFFF, "palette buffer[$i]")
        }
        for (i in 0 until 32) {
            assertEquals(e[FADE_STEP - EWRAM + i], sim.colorFade.step[i], "fade step $i")
        }
        for (i in 0 until 0x600) {
            assertEquals(e[FADE_COMPONENTS - EWRAM + i], sim.colorFade.components[i],
                "fade component $i")
        }
        // The palette RAM and VRAM (tilemaps) are copied in the following VBlank: one dump later.
        val syncKey = sim.dumps.getValue(FIRST + 2)
        val palRam = sim.run.region(syncKey, "pal")
        for (i in 0 until 512) {
            assertEquals(u16(palRam, 2 * i), sim.hardware.paletteRam[i].toInt() and 0xFFFF,
                "palette RAM[$i]")
        }
        val vram = sim.run.region(syncKey, "vram")
        for (i in 0 until BgHardware.VRAM_SIZE) {
            assertEquals(vram[i], sim.hardware.vram[i], "VRAM +${Integer.toHexString(i)}")
        }
        val screen = sim.dump(FIRST + 1).find("ProcScr_GameOverScreen")!!
        assertEquals(21, s16(screen.u16(0x4C)), "counter1 after the set-up")
        val ourScreen = sim.engine.find(sim.screen.script)!!.state as GameOverScreen.State
        assertEquals(21, ourScreen.counter1)
    }

    @Test
    @VerifiesPort("PutScreenFogEffect", "PutScreenFogEffectOverlayed")
    fun `fog tilemaps match the BG2 and BG3 buffers of PC-cap2`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        val dumps = run.dumps.entries.associate { it.value to it.key }
        val e = run.region(dumps.getValue(FIRST + 1), "ewram")
        val bg2 = ShortArray(0x400)
        val bg3 = ShortArray(0x400)
        ScreenFogEffect.putScreenFogEffectOverlayed(bg2)
        ScreenFogEffect.putScreenFogEffect(bg3)
        for (i in 0 until 0x400) {
            val at2 = BG_BUFFERS[2] - EWRAM + 2 * i
            val at3 = BG_BUFFERS[3] - EWRAM + 2 * i
            assertEquals(u16(e, at2), bg2[i].toInt() and 0xFFFF, "BG2[$i]")
            assertEquals(u16(e, at3), bg3[i].toInt() and 0xFFFF, "BG3[$i]")
        }
    }

    @Test
    fun `lcd buffer matches the dumps in the scroll window`() {
        val sim = begin() ?: return
        var compared = 0
        for (f in FIRST..LAST) {
            sim.step()
            val key = sim.dumps[f + 1] ?: continue
            val want = LcdControl().also {
                it.readFrom(sim.run.region(key, "iwram"), LCD_BUFFER - IWRAM)
            }
            val lcd = sim.hardware.lcd
            for (bg in 2..3) {
                assertEquals(want.bgHofs[bg], lcd.bgHofs[bg], "f$f hofs$bg")
                assertEquals(want.bgVofs[bg], lcd.bgVofs[bg], "f$f vofs$bg")
            }
            assertEquals(want.bldcnt, lcd.bldcnt, "f$f bldcnt")
            assertEquals(want.blendCoeffA, lcd.blendCoeffA, "f$f blendCoeffA")
            assertEquals(want.blendCoeffB, lcd.blendCoeffB, "f$f blendCoeffB")
            assertEquals(want.dispcnt, lcd.dispcnt, "f$f dispcnt")
            compared++
        }
        assertTrue(compared > 60, "dumps compared: $compared")
    }

    private fun begin(): Sim? {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("displayio"), "$RUN watches missing")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        return Sim().also { it.seed() }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
        const val SEED = 5952
        const val FIRST = 5953
        const val LAST = 6292
        const val LAST_TEARDOWN = 6295
        const val LAST_PALETTE = 6293
        // Evidence (mutation runs): 0 fails at f5954 counter1 (21 instead of 20), 2 fails at
        // f5953 counter1; the capture's clock and keys watches are one dump ahead of the step.
        const val CLOCK_SHIFT = 1

        // 0 ends the idle one step early (f6260 counter1 -1 instead of 0), 2 one step late.
        const val KEYS_SHIFT = 1
        const val IO_DISPCNT = 0
        const val IO_BLDY = 0x54
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val LCD_BUFFER = 0x03003080
        const val PALETTE = 0x020228A8
        const val FADE_STEP = 0x02022288
        const val FADE_COMPONENTS = 0x020222A8
        val BG_BUFFERS = intArrayOf(0x02022CA8, 0x020234A8, 0x02023CA8, 0x020244A8)
        const val IO_BG2HOFS = 0x18
        const val IO_BG3HOFS = 0x1C

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(v: Int): Int = v.toShort().toInt()
    }
}
