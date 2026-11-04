package fe8.testing

import fe8.game.opanim.OpAnimHs
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [OpAnimHs.brightenPalette] against the mGBA boot RAM trace (`ram_trace_boot`, RAM every
 * 10 frames): in every dump where the intro proc (`ProcScr_GameEarlyStartUI` 0x08AA7680) sits on one of the six
 * fade steps, our fade of the ROM palette with the proc's `palette_timer` (+0x30) must equal the palette banks in
 * the dumped `gPaletteBuffer`. Both come from the same game step, so the dump's one-step lead does not matter.
 * Addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb); fade arguments from the step functions' source.
 */
class OpAnimHsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private val steps = listOf(
        Step(0x080CC2F5, 0x08B125F4, 0, 1, 30, false), // GameIntroHealthSafetyFadeIN
        Step(0x080CC431, 0x08B125F4, 0, 1, 30, true), // GameIntroHealthSafetyFadeOUT
        Step(0x080CC099, 0x08B10ABC, 0, 1, 30, false), // GameIntroNintendoFadeIN
        Step(0x080CC0FD, 0x08B10ABC, 0, 1, 30, true), // GameIntroNintendoFadeOUT
        Step(0x080CC13D, 0x08B11864, 1, 3, 30, false), // GameIntroIntelligentSystemsFadeIN
        Step(0x080CC1A1, 0x08B11864, 1, 3, 40, true), // GameIntroIntelligentSystemsFadeOUT (0x28 - t, 0x28)
    )

    @Test
    @VerifiesPort("OpAnimHS_BrightenPalette")
    fun `intro fades match gPaletteBuffer in the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_boot")
        val procArray = sym["sProcArray"]
        val palBuf = sym["gPaletteBuffer"].address - GbaMem.EWRAM
        val checkedSteps = HashSet<Int>()
        var checked = 0
        for (dump in run.dumps.keys) {
            val ew = run.region(dump, "ewram")
            for (p in 0 until procArray.size / PROC_SIZE) {
                val o = procArray.address - GbaMem.EWRAM + p * PROC_SIZE
                if (u32(ew, o) != PROC_SCRIPT) continue
                // proc_scrCur already points past the running PROC_REPEAT (8-byte commands, function at +4).
                val cmd = u32(ew, o + 4) - ROM - 8
                val step = steps.firstOrNull { it.func == u32(rom, cmd + 4) } ?: continue
                val timer = u32(ew, o + 0x30)
                val cur = if (step.out) step.max - timer else timer
                val src = ShortArray(step.lines * 16) { u16(rom, step.pal - ROM + 2 * it).toShort() }
                val ours = ShortArray(512)
                OpAnimHs.brightenPalette(src, ours, step.palId, step.lines, cur, step.max)
                for (i in step.palId * 16 until (step.palId + step.lines) * 16) {
                    assertEquals(u16(ew, palBuf + 2 * i), ours[i].toInt() and 0xFFFF, "$dump colour $i (timer $timer)")
                }
                checkedSteps += step.func
                checked++
            }
        }
        assertTrue(checked >= 18, "only $checked fade dumps checked")
        assertEquals(steps.size, checkedSteps.size, "not every fade step appears in the trace")
    }

    @Test
    @VerifiesPort("GameIntroHealthSafetyWaitButton", "SetBlendConfig")
    fun `health and safety brightness pulse matches the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_boot")
        val procArray = sym["sProcArray"]
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val keyAt = sym["sKeyStatusBuffer"].address - GbaMem.EWRAM
        var checked = 0
        for (dump in run.dumps.keys) {
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            for (p in 0 until procArray.size / PROC_SIZE) {
                val o = procArray.address - GbaMem.EWRAM + p * PROC_SIZE
                if (u32(ew, o) != PROC_SCRIPT || u32(rom, u32(ew, o + 4) - ROM - 4) != WAIT_BUTTON) continue
                val timer = u32(ew, o + 0x30)
                // Rewind one call: timer - 1 and the fields the step writes scrambled; keys are this frame's.
                val proc = OpAnimHs.Proc().apply { paletteTimer = timer - 1 }
                val lcd = LcdControl().apply { readFrom(iw, lcdAt) }
                val expected = listOf(lcd.dispcnt, lcd.bldcnt, lcd.blendCoeffA, lcd.blendCoeffB, lcd.blendY)
                lcd.bldcnt = lcd.bldcnt xor 0xC0; lcd.blendCoeffA = 0x55; lcd.blendCoeffB = 0x55; lcd.blendY = 0x55
                val keys = gbafe.input.KeyStatus().apply { readFrom(ew, keyAt) }
                val broke = OpAnimHs.healthSafetyWaitButton(proc, lcd, keys)
                assertEquals(false, broke, dump)
                assertEquals(timer, proc.paletteTimer, "$dump palette_timer")
                assertEquals(expected, listOf(lcd.dispcnt, lcd.bldcnt, lcd.blendCoeffA, lcd.blendCoeffB, lcd.blendY), "$dump LCD")
                checked++
            }
        }
        assertTrue(checked >= 3, "only $checked wait-button dumps checked")
    }

    @Test
    @VerifiesPort("GameIntroHealthSafetyFadeIN", "GameIntroHealthSafetyFadeOUT")
    fun `health and safety fade steps match the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val hsPal = ShortArray(16) { u16(rom, 0xB125F4 + 2 * it).toShort() } // Pal_OpAnimHealthSafetyScreen
        val run = ref.run("ram_trace_boot")
        val procArray = sym["sProcArray"]
        val palBuf = sym["gPaletteBuffer"].address - GbaMem.EWRAM
        val seen = HashSet<Int>()
        for (dump in run.dumps.keys) {
            val ew = run.region(dump, "ewram")
            for (p in 0 until procArray.size / PROC_SIZE) {
                val o = procArray.address - GbaMem.EWRAM + p * PROC_SIZE
                if (u32(ew, o) != PROC_SCRIPT) continue
                val func = u32(rom, u32(ew, o + 4) - ROM - 4)
                if (func != HS_FADE_IN && func != HS_FADE_OUT) continue
                // Rewind one call: timer - 1, delay scrambled, bank 0 cleared.
                val proc = OpAnimHs.Proc().apply { paletteTimer = u32(ew, o + 0x30) - 1; delayTimer = 0x7777 }
                val pal = ShortArray(512) { u16(ew, palBuf + 2 * it).toShort() }
                for (i in 0 until 16) pal[i] = 0x7C1F
                val broke = if (func == HS_FADE_IN) OpAnimHs.healthSafetyFadeIn(proc, hsPal, pal) else OpAnimHs.healthSafetyFadeOut(proc, hsPal, pal)
                assertEquals(false, broke, dump) // a breaking frame is never on the trace: the proc moved on
                assertEquals(u32(ew, o + 0x30), proc.paletteTimer, "$dump palette_timer")
                assertEquals(0x7777, proc.delayTimer, "$dump delay_timer")
                for (i in 0 until 512) assertEquals(u16(ew, palBuf + 2 * i), pal[i].toInt() and 0xFFFF, "$dump colour $i")
                seen += func
            }
        }
        assertEquals(setOf(HS_FADE_IN, HS_FADE_OUT), seen)
        // The breaking frame: delay timers as in the source (0x5A after the fade-in, 0x28 after the fade-out).
        val p = OpAnimHs.Proc().apply { paletteTimer = 29 }
        assertTrue(OpAnimHs.healthSafetyFadeIn(p, hsPal, ShortArray(512)) && p.delayTimer == 0x5A)
        p.paletteTimer = 29
        assertTrue(OpAnimHs.healthSafetyFadeOut(p, hsPal, ShortArray(512)) && p.delayTimer == 0x28)
    }

    private companion object {
        const val HS_FADE_IN = 0x080CC2F5 // GameIntroHealthSafetyFadeIN
        const val HS_FADE_OUT = 0x080CC431 // GameIntroHealthSafetyFadeOUT
        const val WAIT_BUTTON = 0x080CC32D // GameIntroHealthSafetyWaitButton
        const val ROM = 0x08000000
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val PROC_SIZE = 0x6C

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}

/** One intro fade step: its function (PROC_REPEAT target, Thumb bit set) and its BrightenPalette arguments. */
private class Step(val func: Int, val pal: Int, val palId: Int, val lines: Int, val max: Int, val out: Boolean)
