package fe8.testing

import fe8.game.opanim.OpAnimHs
import fe8.game.opanim.OpAnimHs.StepResult
import gba.core.VerifiesPort
import gbafe.input.KeyStatus
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests of the Nintendo / Intelligent Systems logo steps and the delays of `ProcScr_GameEarlyStartUI`
 * (0x08AA7680) against the mGBA boot RAM trace (`ram_trace_boot`, RAM every 10 frames). Each dump where the intro
 * proc sits on a step is rewound by one call (the dump is taken after this frame's step ran) and the step re-run
 * with the dump's keys and `gSoftwareResetFlag`; the proc fields, palette and LCD must come back to the dump.
 * Step functions are read from the script in the ROM (8-byte commands, function at +4).
 */
class OpAnimHsLogoDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun hits(rom: ByteArray): List<Hit> {
        val run = ref.run("ram_trace_boot")
        val procArray = sym["sProcArray"]
        val out = ArrayList<Hit>()
        for (dump in run.dumps.keys) {
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            for (p in 0 until procArray.size / PROC_SIZE) {
                val o = procArray.address - GbaMem.EWRAM + p * PROC_SIZE
                if (u32(ew, o) != PROC_SCRIPT) continue
                // proc_scrCur points past the running PROC_REPEAT.
                out += Hit(dump, (u32(ew, o + 4) - PROC_SCRIPT) / 8 - 1, o, ew, iw)
            }
        }
        return out
    }

    private fun keys(h: Hit) = KeyStatus().apply { readFrom(h.ew, sym["sKeyStatusBuffer"].address - GbaMem.EWRAM) }
    private fun softReset(h: Hit) = h.iw[sym["gSoftwareResetFlag"].address - GbaMem.IWRAM].toInt() != 0
    private fun palette(h: Hit) = ShortArray(512) { u16(h.ew, sym["gPaletteBuffer"].address - GbaMem.EWRAM + 2 * it).toShort() }
    private fun romPal(rom: ByteArray, at: Int, n: Int) = ShortArray(n) { u16(rom, at - ROM + 2 * it).toShort() }

    @Test
    @VerifiesPort("GameIntroNintendoFadeIN", "GameIntroNintendoFadeOUT", "GameIntroIntelligentSystemsFadeIN", "GameIntroIntelligentSystemsFadeOUT", "TryTickHSScreenExit")
    fun `logo fade steps match the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val nPal = romPal(rom, 0x08B10ABC, 16) // Pal_GameIntroNintendo
        val isPal = romPal(rom, 0x08B11864, 48) // Pal_IntelligentSystems
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val seen = HashSet<Int>()
        for (h in hits(rom)) {
            if (h.index !in listOf(N_IN, N_OUT, IS_IN, IS_OUT)) continue
            val timer = u32(h.ew, h.proc + 0x30)
            val proc = OpAnimHs.Proc().apply { status = u32(h.ew, h.proc + 0x2C); paletteTimer = timer - 1; delayTimer = 0x7777 }
            val pal = palette(h)
            val banks = if (h.index == N_IN || h.index == N_OUT) 0 until 16 else 16 until 64
            for (i in banks) pal[i] = 0x7C1F
            val lcd = LcdControl().apply { readFrom(h.iw, lcdAt) }
            val dispcnt = lcd.dispcnt
            val k = keys(h)
            val sr = softReset(h)
            val r = when (h.index) {
                N_IN -> OpAnimHs.nintendoFadeIn(proc, nPal, pal, lcd, k, sr)
                N_OUT -> OpAnimHs.nintendoFadeOut(proc, nPal, pal, k, sr)
                IS_IN -> OpAnimHs.intelligentSystemsFadeIn(proc, isPal, pal, lcd, k, sr)
                else -> OpAnimHs.intelligentSystemsFadeOut(proc, isPal, pal, k, sr)
            }
            assertEquals(StepResult.CONTINUE, r, h.dump) // the proc is still on the step after this frame
            assertEquals(timer, proc.paletteTimer, "${h.dump} palette_timer")
            assertEquals(0x7777, proc.delayTimer, "${h.dump} delay_timer")
            assertEquals(dispcnt, lcd.dispcnt, "${h.dump} DISPCNT")
            val dumped = palette(h)
            for (i in 0 until 512) assertEquals(dumped[i], pal[i], "${h.dump} colour $i")
            seen += h.index
        }
        assertEquals(setOf(N_IN, N_OUT, IS_IN, IS_OUT), seen)
        // Breaking frames (never on the trace): delays from the source; a skip (A/B on the IS logo) wins over a break.
        val k = KeyStatus()
        val p = OpAnimHs.Proc().apply { paletteTimer = 29 }
        assertEquals(StepResult.BREAK, OpAnimHs.nintendoFadeIn(p, nPal, ShortArray(512), LcdControl(), k, false))
        assertEquals(0x3C, p.delayTimer)
        p.paletteTimer = 29
        assertEquals(StepResult.BREAK, OpAnimHs.nintendoFadeOut(p, nPal, ShortArray(512), k, false))
        assertEquals(0x28, p.delayTimer)
        p.paletteTimer = 39; p.delayTimer = 0
        assertEquals(StepResult.BREAK, OpAnimHs.intelligentSystemsFadeOut(p, isPal, ShortArray(512), k, false))
        assertEquals(0x3C, p.delayTimer)
        p.status = OpAnimHs.STATUS_INTELLIGENT_SYSTEMS; p.paletteTimer = 29
        k.newKeys = 0x2 // B
        assertEquals(StepResult.SKIP, OpAnimHs.intelligentSystemsFadeIn(p, isPal, ShortArray(512), LcdControl(), k, false))
        p.status = OpAnimHs.STATUS_NINTENDO; p.paletteTimer = 0
        assertEquals(StepResult.CONTINUE, OpAnimHs.nintendoFadeIn(p, nPal, ShortArray(512), LcdControl(), k, false))
        assertEquals(StepResult.SKIP, OpAnimHs.nintendoFadeIn(p, nPal, ShortArray(512), LcdControl(), k, true))
    }

    @Test
    @VerifiesPort("GameEarlyStartDelay")
    fun `delay steps match the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val delayFunc = u32(rom, PROC_SCRIPT - ROM + 8 * 4 + 4)
        var checked = 0
        for (h in hits(rom)) {
            if (u32(rom, PROC_SCRIPT - ROM + 8 * h.index + 4) != delayFunc) continue
            val delay = u32(h.ew, h.proc + 0x34)
            val timer = u32(h.ew, h.proc + 0x30)
            val proc = OpAnimHs.Proc().apply { status = u32(h.ew, h.proc + 0x2C); paletteTimer = timer; delayTimer = delay + 1 }
            assertEquals(StepResult.CONTINUE, OpAnimHs.earlyStartDelay(proc, keys(h), softReset(h)), h.dump)
            assertEquals(delay, proc.delayTimer, "${h.dump} delay_timer")
            assertEquals(timer, proc.paletteTimer, "${h.dump} palette_timer")
            checked++
        }
        assertTrue(checked >= 15, "only $checked delay dumps checked")
        val p = OpAnimHs.Proc().apply { paletteTimer = 30; delayTimer = 1 }
        assertEquals(StepResult.BREAK, OpAnimHs.earlyStartDelay(p, KeyStatus(), false))
        assertEquals(0, p.paletteTimer)
    }

    @Test
    @VerifiesPort("SetOpAnimHsStatus0", "SetOpAnimHsStatus1", "SetOpAnimHsStatus2")
    fun `status per script section matches the boot RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val setters = listOf(OpAnimHs::setStatus0, OpAnimHs::setStatus1, OpAnimHs::setStatus2)
        // Script commands 1, 10, 16 are PROC_CALL(SetOpAnimHsStatus0/1/2): each step runs after the last setter before it.
        val callAt = listOf(1, 10, 16)
        val seen = HashSet<Int>()
        for (h in hits(rom)) {
            val section = callAt.indexOfLast { it < h.index }
            if (section < 0) continue
            val proc = OpAnimHs.Proc().apply { status = 0x55 }
            setters[section](proc)
            assertEquals(u32(h.ew, h.proc + 0x2C), proc.status, "${h.dump} status at command ${h.index}")
            seen += section
        }
        assertEquals(setOf(0, 1, 2), seen)
    }

    private companion object {
        const val ROM = 0x08000000
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val PROC_SIZE = 0x6C
        const val N_IN = 11 // script command indices (see the script in src/opanim-healthsafetyscreen.c)
        const val N_OUT = 13
        const val IS_IN = 17
        const val IS_OUT = 19

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}

/** One dump where the intro proc runs a step: script command index, proc offset in EWRAM, dump regions. */
private class Hit(val dump: String, val index: Int, val proc: Int, val ew: ByteArray, val iw: ByteArray)
