package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleScenes
import fe8.game.opanim.OpSubtitleSetup
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

    @Test
    @VerifiesPort("sub_80C538C", "sub_80C5370")
    fun `final whole palette fade follows original callback`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val trace = capture.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes())
        // The saved palette is the buffer when sub_80C5370 runs (step 4178); the 4170 snapshot
        // is the nearest capture and no palette write happens in between.
        val saved = fullPalette(capture.region("r04170", "ewram"))
        var compared = 0
        var snapshots = 0
        for (frame in 4179..4238) {
            val before = ProcArrayDump.ofWatch(trace, frame - 1).find(SCRIPT)!!
            val after = ProcArrayDump.ofWatch(trace, frame).find(SCRIPT)!!
            val first = frame == 4179
            if (!first) assertEquals(FINAL_FADE_CALLBACK, before.u32(0x0C))
            val state = stateOf(before)
            val rig = StageRig(rom, state) { t, p -> t.fadeOutAll(p) }
            saved.copyInto(rig.hardware.palette)
            rig.transitions.snapshotPalette(rig.proc)
            assertEquals(0, state.transitionTimer)
            if (!first) state.transitionTimer = before.u16(0x2C).toShort().toInt()
            rig.hardware.palette.fill(0x5A5A.toShort())
            rig.step()
            compareState(after, state)
            val broke = after.u32(0x0C) == 0
            assertEquals(broke, rig.proc.idleCb == null, "frame $frame repeat")
            assertEquals(1, rig.calls, "frame $frame Start check")
            assertTrue(rig.hardware.modifiedPalette)
            val snapshot = if (frame % 30 == 0) frame else if (broke) 4260 else null
            if (snapshot != null) {
                val prefix = if (snapshot % 300 == 0) "f" else "r"
                val ewram = capture.region(prefix + snapshot.toString().padStart(5, '0'), "ewram")
                // Only the BG half is compared: the original fades the OBJ half from EWRAM past
                // its 256-colour copy and leaves it set on completion; that half is unverified.
                val size = 0x100
                assertArrayEquals(
                    fullPalette(ewram).copyOf(size), rig.hardware.palette.copyOf(size),
                    "frame $frame vs snapshot $snapshot"
                )
                snapshots++
            }
            compared++
        }
        assertEquals(60, compared)
        assertEquals(3, snapshots)
    }

    @Test
    @VerifiesPort("sub_80C5328")
    fun `final layers match captured dispcnt and vram`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rig = StageRig(RomView.ofRom(romFile.readBytes()), OpSubtitleSlides.State()) { _, _ -> }
        rig.hardware.vram.fill(0x7F, 0, 0x40)
        rig.transitions.showFinalLayers()
        val lcd = LcdControl()
        lcd.readFrom(capture.region("r02730", "iwram"), 0x3080)
        assertEquals(lcd.dispcnt and 0x1F00, rig.hardware.lcd.dispcnt and 0x1F00)
        val vram = capture.region("f03000", "vram")
        assertArrayEquals(vram.copyOfRange(0, 0x20), rig.hardware.vram.copyOfRange(0, 0x20))
        assertEquals(0x7F, rig.hardware.vram[0x20].toInt())
        assertEquals(2, rig.hardware.modifiedBgs and 2)
    }

    /**
     * Hardware seeded from a capture snapshot: LCD registers, palette and BG maps from [name]
     * and VRAM from [vramName] (only every 300th step has a VRAM dump).
     */
    private fun seededHardware(
        capture: ReferenceData.Run, name: String, vramName: String
    ): BgHardware {
        val hardware = BgHardware()
        hardware.lcd.readFrom(capture.region(name, "iwram"), 0x3080)
        capture.region(vramName, "vram").copyInto(hardware.vram, 0, 0, hardware.vram.size)
        val ewram = capture.region(name, "ewram")
        fullPalette(ewram).copyInto(hardware.palette)
        for (bg in 0..3) {
            val at = 0x22CA8 + 0x800 * bg
            for (i in 0 until BgHardware.TILEMAP_ENTRIES) {
                hardware.tilemaps[bg][i] = u16(ewram, at + 2 * i).toShort()
            }
        }
        return hardware
    }

    private fun scenesOf(rom: RomView, hardware: BgHardware) = OpSubtitleScenes(
        hardware, rom, OpSubtitleSetup(rom, hardware), OpSubtitleSlides(rom, hardware)
    )

    private fun assertMaps(
        capture: ReferenceData.Run, name: String, hardware: BgHardware, bgs: IntRange
    ) {
        val ewram = capture.region(name, "ewram")
        for (bg in bgs) {
            val at = 0x22CA8 + 0x800 * bg
            val expected =
                ShortArray(BgHardware.TILEMAP_ENTRIES) { u16(ewram, at + 2 * it).toShort() }
            assertArrayEquals(expected, hardware.tilemaps[bg], "BG$bg map vs $name")
        }
    }

    private fun assertVram(
        capture: ReferenceData.Run, name: String, hardware: BgHardware, range: IntRange
    ) {
        val expected = capture.region(name, "vram")
        assertArrayEquals(
            expected.copyOfRange(range.first, range.last + 1),
            hardware.vram.copyOfRange(range.first, range.last + 1),
            "vram $range vs $name",
        )
    }

    @Test
    @VerifiesPort("sub_80C50A0")
    fun `flare clear matches captured vram maps palette and timers`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        // Runs at step 2355; r02340 is the nearest snapshot before it, r02370 the first after.
        // The flare child only touches BG2 registers and its map in between. VRAM is checked
        // together with sub_80C51A8 below (only f02100 and f02400 dump VRAM, and the latter
        // already holds the later slide load over the cleared range).
        val hardware = seededHardware(capture, "r02340", "f02100")
        val state = OpSubtitleSlides.State(2, 0, 0)
        scenesOf(rom, hardware).clearForFlare(state)
        assertMaps(capture, "r02370", hardware, 0..1)
        val palette = fullPalette(capture.region("r02370", "ewram"))
        assertArrayEquals(
            palette.copyOfRange(0xE * 16, 0x10 * 16),
            hardware.palette.copyOfRange(0xE * 16, 0x10 * 16),
        )
        assertEquals(0x03, hardware.modifiedBgs and 3)
        assertTrue(hardware.modifiedPalette)
        // Step 2357 is the first hold-wait frame after the call: the timer is one below its start.
        val after = ProcArrayDump.ofWatch(capture.watch("procs"), 2357).find(SCRIPT)!!
        assertEquals(after.u16(0x2A) + 1, state.holdTimer)
        assertEquals(after.u16(0x2C), state.transitionTimer)
    }

    @Test
    @VerifiesPort("sub_80C51A8", "sub_80C50A0")
    fun `final card load matches captured vram map and palette 3`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        // sub_80C50A0 runs at step 2355 and the flare's child proc runs sub_80C51A8 36 frames
        // later; f02400 is the only VRAM dump after both, f02100 the last before them. The
        // cleared 0x5000 range is overwritten by the slide load, so only the pair is comparable.
        val hardware = seededHardware(capture, "r02340", "f02100")
        val scenes = scenesOf(rom, hardware)
        scenes.clearForFlare(OpSubtitleSlides.State(2, 0, 0))
        scenes.loadFinalCard()
        // Tile data only: the screen-block maps at 0x6000.. are flushed by the BG sync.
        assertVram(capture, "f02400", hardware, 0 until 0x6000)
        assertMaps(capture, "f02400", hardware, 0..0)
        val palette = fullPalette(capture.region("f02400", "ewram"))
        assertArrayEquals(palette.copyOfRange(48, 64), hardware.palette.copyOfRange(48, 64))
        assertEquals(1, hardware.modifiedBgs and 1)
        assertTrue(hardware.modifiedPalette)
    }

    @Test
    @VerifiesPort("sub_80C5218")
    fun `final scene setup matches captured registers vram maps and timers`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        // Runs at step 2425 (after the card palette fade ends); f02400 precedes it and r02430
        // follows it with only the hold timer ticking in between. VRAM is dumped at f02700,
        // after the later slide load at 0x1000, so only 0x5000..0x6000 and BG3 tiles from 0x8000
        // are compared (the maps at 0x6000.. are flushed by the BG sync, not by the ported code).
        val hardware = seededHardware(capture, "f02400", "f02400")
        val state = OpSubtitleSlides.State(2, 0, 0)
        scenesOf(rom, hardware).setupFinalScene(state)
        val expected = LcdControl()
        expected.readFrom(capture.region("r02430", "iwram"), 0x3080)
        val lcd = hardware.lcd
        assertEquals(expected.dispcnt and 0x1F07, lcd.dispcnt and 0x1F07, "dispcnt")
        assertArrayEquals(expected.bgCnt, lcd.bgCnt, "bgcnt")
        assertArrayEquals(expected.bgHofs, lcd.bgHofs, "hofs")
        assertArrayEquals(expected.bgVofs, lcd.bgVofs, "vofs")
        assertEquals(expected.bldcnt, lcd.bldcnt, "bldcnt")
        assertEquals(expected.blendCoeffA, lcd.blendCoeffA, "coeff A")
        assertEquals(expected.blendCoeffB, lcd.blendCoeffB, "coeff B")
        assertEquals(expected.blendY, lcd.blendY, "blend Y")
        assertVram(capture, "f02700", hardware, 0x5000 until 0x6000)
        assertVram(capture, "f02700", hardware, 0x8000 until 0x18000)
        assertMaps(capture, "r02430", hardware, 0..3)
        assertEquals(0x0F, hardware.modifiedBgs and 0x0F)
        // Step 2428 is the first hold-wait frame after the call.
        val after = ProcArrayDump.ofWatch(capture.watch("procs"), 2428).find(SCRIPT)!!
        assertEquals(after.u8(0x29), state.index)
        assertEquals(after.u16(0x2A) + 1, state.holdTimer)
    }

    @Test
    @VerifiesPort("sub_80C540C")
    fun `card offset reset matches captured BG offsets`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        // Runs at the end of the monologue (step ~4897): r04890 still has BG1 at (-3, -3) and
        // r04920 has (0, 0).
        val hardware = seededHardware(capture, "r04890", "f04800")
        assertEquals(0xFFFD, hardware.lcd.bgHofs[1].toInt(), "BG1 hofs before")
        assertEquals(0xFFFD, hardware.lcd.bgVofs[1].toInt(), "BG1 vofs before")
        val hofsBefore = hardware.lcd.bgHofs.copyOf()
        val vofsBefore = hardware.lcd.bgVofs.copyOf()
        scenesOf(rom, hardware).resetCardOffset()
        val expected = LcdControl()
        expected.readFrom(capture.region("r04920", "iwram"), 0x3080)
        // Only BG1 is compared with the capture: other procs scroll BG2 between the snapshots, so
        // the other BGs are checked to be left untouched.
        assertEquals(expected.bgHofs[1], hardware.lcd.bgHofs[1], "BG1 hofs")
        assertEquals(expected.bgVofs[1], hardware.lcd.bgVofs[1], "BG1 vofs")
        assertEquals(0, hardware.lcd.bgHofs[1].toInt() or hardware.lcd.bgVofs[1].toInt())
        for (bg in intArrayOf(0, 2, 3)) {
            assertEquals(hofsBefore[bg], hardware.lcd.bgHofs[bg], "BG$bg hofs untouched")
            assertEquals(vofsBefore[bg], hardware.lcd.bgVofs[bg], "BG$bg vofs untouched")
        }
    }

    private fun fullPalette(ewram: ByteArray) = ShortArray(0x200) { i ->
        val at = 0x228A8 + 2 * i
        ((ewram[at].toInt() and 0xFF) or ((ewram[at + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    @Test
    fun `final card script loads palette 3 on the frame the original timing implies`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val capture = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = seededHardware(capture, "r02340", "f02100")
        val sentinel = 0x5A5A.toShort()
        hardware.palette.fill(sentinel, 48, 64)
        val engine = ProcEngine()
        val proc = engine.start(scenesOf(rom, hardware).finalCardScript, 3)
        // start() runs sleep(0) (yield, no idle callback). Frame 1 runs sleep(36): sleepTime=36
        // and the callback decrements it to 35 in the same frame; it reaches 0 on frame 36 and
        // breaks, so the call command runs on frame 37.
        var loadFrame = 0
        for (frame in 1..60) {
            engine.run(proc)
            if (loadFrame == 0 && hardware.palette[48] != sentinel) loadFrame = frame
        }
        assertEquals(37, loadFrame)
        // The parent starts the child at step 2355, so the load lands at step 2392: after the
        // r02370 snapshot (palette 3 not yet loaded) and by f02400 (loaded).
        assertEquals(2355 + 37, 2392)
        val before = fullPalette(capture.region("r02370", "ewram"))
        val after = fullPalette(capture.region("f02400", "ewram"))
        assertTrue(!before.copyOfRange(48, 64).contentEquals(after.copyOfRange(48, 64)))
        assertArrayEquals(after.copyOfRange(48, 64), hardware.palette.copyOfRange(48, 64))
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
        const val FINAL_FADE_CALLBACK = 0x080C538D
        const val CARD_FADE_IN_CALLBACK = 0x080C5105
    }
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
