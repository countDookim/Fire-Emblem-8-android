package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleSlides
import fe8.game.opanim.OpSubtitleTransitions
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Original opening subtitle proc and palette buffer compared one callback at a time. */
class OpSubtitleTransitionsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun stateOf(proc: ProcArrayDump.Proc) = OpSubtitleSlides.State(
        proc.u8(0x29).toByte().toInt(), proc.u16(0x2C).toShort().toInt(),
        proc.u16(0x2A).toShort().toInt(),
    )

    private fun compareState(expected: ProcArrayDump.Proc, state: OpSubtitleSlides.State) {
        assertEquals(expected.u8(0x29).toByte().toInt(), state.index)
        assertEquals(expected.u16(0x2A).toShort().toInt(), state.holdTimer)
        assertEquals(expected.u16(0x2C).toShort().toInt(), state.transitionTimer)
    }

    @Test
    @VerifiesPort("OpSubtitle_AwaitTimer2a")
    fun `hold timer follows original callback including break frame`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        var compared = 0
        var breaks = 0
        for (frame in 1214..4897) {
            val before = ProcArrayDump.ofWatch(trace, frame - 1).find(SCRIPT) ?: continue
            if (before.u32(0x0C) != HOLD_CALLBACK) continue
            val after = ProcArrayDump.ofWatch(trace, frame).find(SCRIPT) ?: continue
            val afterCallback = after.u32(0x0C)
            if (afterCallback != HOLD_CALLBACK && afterCallback != 0) continue
            val state = stateOf(before)
            val rig = Rig(rom, false, state)
            rig.step()
            compareState(after, state)
            val broke = afterCallback == 0
            assertEquals(broke, rig.proc.idleCb == null, "frame $frame repeat")
            assertEquals(1, rig.calls, "frame $frame Start check")
            assertEquals(broke, rig.callbackSawBreak, "frame $frame Start check order")
            assertEquals(state.holdTimer, rig.callbackHoldTimer, "frame $frame timer before Start check")
            compared++
            if (broke) breaks++
        }
        assertTrue(compared > 100)
        assertTrue(breaks > 0)
    }

    @Test
    @VerifiesPort("sub_80C4DA0", "sub_80C4BB4")
    fun `fade and palette follow original callback and snapshots`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        var compared = 0
        var breaks = 0
        var paletteSnapshots = 0
        for (frame in 1214..4897) {
            val before = ProcArrayDump.ofWatch(trace, frame - 1).find(SCRIPT) ?: continue
            if (before.u32(0x0C) != FADE_CALLBACK) continue
            val after = ProcArrayDump.ofWatch(trace, frame).find(SCRIPT) ?: continue
            val afterCallback = after.u32(0x0C)
            if (afterCallback != FADE_CALLBACK && afterCallback != 0) continue
            val state = stateOf(before)
            val rig = Rig(rom, true, state)
            rig.hardware.palette.fill(0x5A5A.toShort())
            rig.step()
            compareState(after, state)
            val broke = afterCallback == 0
            assertEquals(broke, rig.proc.idleCb == null, "frame $frame repeat")
            assertEquals(1, rig.calls, "frame $frame Start check")
            assertEquals(broke, rig.callbackSawBreak, "frame $frame Start check order")
            assertEquals(state.holdTimer, rig.callbackHoldTimer, "frame $frame hold before Start check")
            assertEquals(state.transitionTimer, rig.callbackTransitionTimer, "frame $frame fade before Start check")
            assertTrue(rig.callbackSawPaletteSync, "frame $frame palette sync before Start check")
            assertTrue(rig.hardware.modifiedPalette)
            assertTrue(rig.hardware.palette.take(48).all { it == 0x5A5A.toShort() })
            assertTrue(rig.hardware.palette.drop(64).all { it == 0x5A5A.toShort() })
            if (frame % 30 == 0) {
                val prefix = if (frame % 300 == 0) "f" else "r"
                val ewram = run.region(prefix + frame.toString().padStart(5, '0'), "ewram")
                val expected = ShortArray(16) { i ->
                    val at = 0x228A8 + 2 * (48 + i)
                    ((ewram[at].toInt() and 0xFF) or ((ewram[at + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                assertArrayEquals(expected, rig.hardware.palette.copyOfRange(48, 64), "frame $frame palette")
                paletteSnapshots++
            }
            compared++
            if (broke) breaks++
        }
        assertTrue(compared > 100)
        assertTrue(breaks > 0)
        assertTrue(paletteSnapshots >= 5)
    }

    private class Rig(rom: RomView, fade: Boolean, state: OpSubtitleSlides.State) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        var calls = 0
        var callbackSawBreak = false
        var callbackHoldTimer = 0
        var callbackTransitionTimer = 0
        var callbackSawPaletteSync = false
        val transitions = OpSubtitleTransitions(engine, hardware, rom) {
            calls++
            callbackSawBreak = it.idleCb == null
            val callbackState = it.state as OpSubtitleSlides.State
            callbackHoldTimer = callbackState.holdTimer
            callbackTransitionTimer = callbackState.transitionTimer
            callbackSawPaletteSync = hardware.modifiedPalette
        }
        val script = ProcScript(
            "subtitle callback test",
            listOf(repeat(ProcFunc { if (fade) transitions.fadeIn(it) else transitions.awaitHoldTimer(it) }), block()),
        ) { state }
        val proc = engine.start(script, 3)
        fun step() = engine.run(proc)
    }

    private companion object {
        const val SCRIPT = 0x08AA21BC
        const val HOLD_CALLBACK = 0x080C4C3D
        const val FADE_CALLBACK = 0x080C4DA1
    }
}
