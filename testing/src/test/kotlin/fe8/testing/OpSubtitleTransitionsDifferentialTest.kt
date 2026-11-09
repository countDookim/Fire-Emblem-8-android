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
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.LcdControl
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
            assertEquals(
                state.holdTimer, rig.callbackHoldTimer, "frame $frame timer before Start check"
            )
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
            assertEquals(
                state.holdTimer, rig.callbackHoldTimer, "frame $frame hold before Start check"
            )
            assertEquals(
                state.transitionTimer,
                rig.callbackTransitionTimer,
                "frame $frame fade before Start check",
            )
            assertTrue(rig.callbackSawPaletteSync, "frame $frame palette sync before Start check")
            assertTrue(rig.hardware.modifiedPalette)
            assertTrue(rig.hardware.palette.take(48).all { it == 0x5A5A.toShort() })
            assertTrue(rig.hardware.palette.drop(64).all { it == 0x5A5A.toShort() })
            if (frame % 30 == 0) {
                val prefix = if (frame % 300 == 0) "f" else "r"
                val ewram = run.region(prefix + frame.toString().padStart(5, '0'), "ewram")
                val expected = ShortArray(16) { i ->
                    val at = 0x228A8 + 2 * (48 + i)
                    ((ewram[at].toInt() and 0xFF) or
                        ((ewram[at + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                assertArrayEquals(
                    expected, rig.hardware.palette.copyOfRange(48, 64), "frame $frame palette"
                )
                paletteSnapshots++
            }
            compared++
            if (broke) breaks++
        }
        assertTrue(compared > 100)
        assertTrue(breaks > 0)
        assertTrue(paletteSnapshots >= 5)
    }

    /** What a stage test checks at snapshot frames besides the proc fields. */
    private enum class Kind { PALETTE_3, PALETTE_15, BLEND }

    private class StageRig(
        rom: RomView, state: OpSubtitleSlides.State, run: (OpSubtitleTransitions, Proc) -> Unit
    ) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        var calls = 0
        val transitions = OpSubtitleTransitions(engine, hardware, rom) { calls++ }
        val script = ProcScript(
            "subtitle stage test",
            listOf(
                repeat(ProcFunc { run(transitions, it) }),
                block(),
                label(1),
                block(),
                label(4),
                block()
            ),
        ) { state }
        val proc = engine.start(script, 3)

        init {
            hardware.palette.fill(0x5A5A.toShort())
            hardware.lcd.dispcnt = 0x1F00
            hardware.lcd.setBlendConfig(0, 0x77, 0x66, 0x55)
        }

        fun step() = engine.run(proc)
    }

    private fun romLabelIndex(rom: ByteArray, id: Int): Int {
        val base = SCRIPT - 0x08000000
        var i = 0
        while (true) {
            val op = u16(rom, base + 8 * i)
            val imm = u16(rom, base + 8 * i + 2)
            if (op == 0x0B && imm == id) return i
            check(op != 0) { "label $id missing" }
            i++
        }
    }

    private fun palette(ewram: ByteArray, slot: Int) = ShortArray(16) { i ->
        val at = 0x228A8 + 2 * (16 * slot + i)
        ((ewram[at].toInt() and 0xFF) or ((ewram[at + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    /**
     * Runs [run] from every captured frame where the subtitle proc's repeat callback is
     * [callback], comparing proc fields, repeat/break/goto outcome and, at 30-frame snapshots,
     * the palette slot and LCD registers. Completion frames (callback cleared) are never on a
     * snapshot, so their palette and DISPCNT result is compared to the next snapshot inside the
     * following hold. Returns the stage's frames as (frame, broke, jumped).
     */
    private fun checkStage(
        callback: Int,
        kind: Kind,
        run: (OpSubtitleTransitions, Proc) -> Unit,
        completionCalls: Int = 1,
        expectedDispcnt: (before: ProcArrayDump.Proc, after: ProcArrayDump.Proc) -> Int?,
    ): List<Triple<Int, Boolean, Boolean>> {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val trace = capture.watch("procs")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val result = mutableListOf<Triple<Int, Boolean, Boolean>>()
        for (frame in 1214..4897) {
            val before = ProcArrayDump.ofWatch(trace, frame - 1).find(SCRIPT) ?: continue
            val after = ProcArrayDump.ofWatch(trace, frame).find(SCRIPT) ?: continue
            val afterCallback = after.u32(0x0C)
            // The stage's first frame: the script reaches the repeat and calls it in one step.
            val first = before.u32(0x0C) == 0 && afterCallback == callback &&
                before.u16(0x2C) == 0 && after.u16(0x2C) == 1
            if (before.u32(0x0C) != callback && !first) continue
            if (afterCallback != callback && afterCallback != 0) continue
            val state = stateOf(before)
            val rig = StageRig(rom, state, run)
            rig.step()
            compareState(after, state)
            val broke = afterCallback == 0
            assertEquals(broke, rig.proc.idleCb == null, "frame $frame repeat")
            val jumped = !first && after.scrCur != before.scrCur
            assertEquals(jumped, rig.proc.scrCur != 1, "frame $frame goto")
            if (jumped) {
                val label = if (rig.proc.scrCur == 2) 1 else 4
                assertEquals(romLabelIndex(romBytes, label), after.scrCur, "frame $frame label")
                assertEquals(if (label == 1) 2 else 4, rig.proc.scrCur, "frame $frame label")
            }
            assertEquals(if (broke) completionCalls else 1, rig.calls, "frame $frame Start check")
            assertTrue(rig.hardware.modifiedPalette, "frame $frame palette sync")
            val dispcnt = expectedDispcnt(before, after)
            if (dispcnt != null) {
                assertEquals(dispcnt, rig.hardware.lcd.dispcnt and 0x1F00, "frame $frame dispcnt")
            } else {
                assertEquals(0x1F00, rig.hardware.lcd.dispcnt, "frame $frame dispcnt untouched")
            }
            if (kind != Kind.BLEND) {
                val slot = if (kind == Kind.PALETTE_3) 3 else 15
                assertTrue(
                    rig.hardware.palette.indices.all {
                        it / 16 == slot || rig.hardware.palette[it] == 0x5A5A.toShort()
                    },
                    "frame $frame other palette slots untouched",
                )
            }
            // Snapshots hold the end-of-step state: this frame's own (every 30th), or the next one
            // while the proc still sits in the same stage or in the hold that follows it.
            val next = (frame / 30 + 1) * 30
            val later = next.takeIf {
                val proc = ProcArrayDump.ofWatch(trace, it).find(SCRIPT)
                proc != null && !jumped && proc.u8(0x29) == after.u8(0x29) &&
                    proc.u32(0x0C) == (if (broke) HOLD_CALLBACK else callback)
            }
            val checks = listOf(
                (if (frame % 30 == 0) frame else null) to true,
                (if (broke) later else null) to true,
                (if (dispcnt != null && !broke) later else null) to false,
            )
            for ((snapshot, checkRegs) in checks) {
                if (snapshot == null) continue
                val prefix = if (snapshot % 300 == 0) "f" else "r"
                val name = prefix + snapshot.toString().padStart(5, '0')
                val ewram = capture.region(name, "ewram")
                val lcd = LcdControl()
                lcd.readFrom(capture.region(name, "iwram"), 0x3080)
                if (checkRegs) when (kind) {
                    Kind.PALETTE_3 -> assertArrayEquals(
                        palette(ewram, 3), rig.hardware.palette.copyOfRange(48, 64),
                        "frame $frame palette 3 vs snapshot $snapshot",
                    )
                    Kind.PALETTE_15 -> assertArrayEquals(
                        palette(ewram, 15), rig.hardware.palette.copyOfRange(240, 256),
                        "frame $frame palette 15 vs snapshot $snapshot",
                    )
                    Kind.BLEND -> {
                        val hw = rig.hardware.lcd
                        assertEquals(
                            lcd.bldcnt and 0xC0, hw.bldcnt and 0xC0, "frame $frame bld effect"
                        )
                        assertEquals(lcd.blendCoeffA, hw.blendCoeffA, "frame $frame coeff A")
                        assertEquals(lcd.blendCoeffB, hw.blendCoeffB, "frame $frame coeff B")
                        assertEquals(lcd.blendY, hw.blendY, "frame $frame blend Y")
                    }
                }
                if (dispcnt != null && (broke || !checkRegs)) {
                    assertEquals(
                        dispcnt, lcd.dispcnt and 0x1F00, "frame $frame snapshot $snapshot dispcnt"
                    )
                }
            }
            result += Triple(frame, broke, jumped)
        }
        return result
    }

    @Test
    @VerifiesPort("sub_80C4E18")
    fun `first card fade out follows original callback`() {
        val frames = checkStage(
            FADE_OUT_FIRST_CALLBACK, Kind.PALETTE_3, { t, p -> t.fadeOutFirstCards(p) },
        ) { before, after ->
            // Index 0 -> 1 breaks (layers kept); index 1 -> 2 turns every layer off and jumps.
            if (after.u32(0x0C) == 0 && after.scrCur != before.scrCur) 0 else null
        }
        assertEquals(160, frames.size, "stage frames")
        assertEquals(2, frames.count { it.second }, "completion frames")
        assertEquals(1, frames.count { it.third }, "goto frames")
    }

    @Test
    @VerifiesPort("sub_80C4EC4")
    fun `card fade out follows original callback`() {
        val frames = checkStage(
            FADE_OUT_CARD_CALLBACK, Kind.PALETTE_3, { t, p -> t.fadeOutToCard(p) },
        ) { _, after -> if (after.u32(0x0C) == 0) 0x0400 else null }
        assertEquals(80, frames.size, "stage frames")
        assertEquals(1, frames.count { it.second })
    }

    @Test
    @VerifiesPort("sub_80C4F60")
    fun `blend in follows original callback`() {
        // Captured completions: index 3 and 4 break; index 5 jumps to label 4.
        val frames =
            checkStage(BLEND_IN_CALLBACK, Kind.BLEND, { t, p -> t.blendIn(p) }) { _, _ -> null }
        assertEquals(240, frames.size, "stage frames")
        assertEquals(3, frames.count { it.second })
        assertEquals(1, frames.count { it.third })
    }

    @Test
    @VerifiesPort("sub_80C501C")
    fun `blend out follows original callback`() {
        val frames = checkStage(
            BLEND_OUT_CALLBACK, Kind.BLEND, { t, p -> t.blendOut(p) }, 2
        ) { _, _ -> null }
        assertEquals(240, frames.size, "stage frames")
        assertEquals(3, frames.count { it.second })
    }

    @Test
    @VerifiesPort("sub_80C5104")
    fun `card palette fade in follows original callback`() {
        val frames = checkStage(
            CARD_FADE_IN_CALLBACK, Kind.PALETTE_15, { t, p -> t.fadeInCardPalette(p) },
        ) { before, _ -> if (before.u16(0x2C) == 0) 0x0700 else null }
        assertEquals(46, frames.size, "stage frames")
        assertEquals(1, frames.count { it.second })
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
            listOf(
                repeat(ProcFunc {
                    if (fade) transitions.fadeIn(it) else transitions.awaitHoldTimer(it)
                }),
                block()
            ),
        ) { state }
        val proc = engine.start(script, 3)
        fun step() = engine.run(proc)
    }

    private companion object {
        const val SCRIPT = 0x08AA21BC
        const val HOLD_CALLBACK = 0x080C4C3D
        const val FADE_CALLBACK = 0x080C4DA1
        const val FADE_OUT_FIRST_CALLBACK = 0x080C4E19
        const val FADE_OUT_CARD_CALLBACK = 0x080C4EC5
        const val BLEND_IN_CALLBACK = 0x080C4F61
        const val BLEND_OUT_CALLBACK = 0x080C501D
        const val CARD_FADE_IN_CALLBACK = 0x080C5105
    }
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
