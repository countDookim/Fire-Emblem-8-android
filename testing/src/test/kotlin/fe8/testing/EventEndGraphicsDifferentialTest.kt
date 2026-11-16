package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import fe8.game.ui.ItemIcon
import fe8.game.ui.UiFrame
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.TemporaryLock
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The graphics and fade calls around the talk of the boss turn event in
 * `ram_trace_prologue_clear` (EP-11f): the UI frame tiles and palette that `LoadUiFrameGraphics`
 * of the event end (step 3721) writes, `ReadGameSaveCoreGfx` of the same call, the black palette
 * the talk face starts from (`SetBlackPal`, step 2972), the lock of the face fade-in
 * (`StartTemporaryLock`, steps 2972..2980) and the face fade-out (`StartPalFadeToBlack`, steps
 * 3706..3714), compared with the per-step `gPaletteBuffer` of the EWRAM dumps and the `procs`
 * watch.
 *
 * The UI frame palette (bank 1) and tiles are the same before and after the event end, so the
 * tests start from cleared graphics and check what the calls write.
 */
class EventEndGraphicsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() = assumeTrue(ref.available && romFile.isFile, "reference data absent")

    @Test
    @VerifiesPort("UnpackUiFrameImage")
    fun `the frame image of the window colour is the BG tile data of f3730`() {
        available()
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()

        UiFrame.unpackUiFrameImage(hardware, rom, windowColor(3720))

        val written = hardware.vram.indexOfLast { it != 0.toByte() } + 1
        assertTrue(written > 0x100, "image written")
        // Tiles 16..25 of the frame are overwritten before the event end and restored by it.
        val before = run.region(dumpName(3720), "vram")
        val want = run.region(dumpName(3730), "vram")
        assertNotEquals(
            before.copyOfRange(0, written).toList(), want.copyOfRange(0, written).toList(), "seed",
        )
        assertTrue(want.copyOfRange(0, written).contentEquals(hardware.vram.copyOf(written)))
    }

    @Test
    @VerifiesPort("UnpackUiFramePalette")
    fun `the frame palette of the window colour is palette bank 1 of r3721`() {
        available()
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()

        UiFrame.unpackUiFramePalette(hardware, rom, windowColor(3721))

        val want = run.region(dumpName(3721), "ewram")
        assertNotEquals(List(16) { 0 }, bank(hardware, 1), "seed")
        assertEquals(captured(want, 1), bank(hardware, 1))
        assertEquals(List(16) { 0 }, bank(hardware, 0))
        assertEquals(List(16) { 0 }, bank(hardware, 2))
    }

    @Test
    @VerifiesPort("ReadGameSaveCoreGfx")
    fun `the core graphics reset writes the frame, icon and OBJ UI graphics of the capture`() {
        available()
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        val playSt = PlaySt().also { it.windowColor = windowColor(3721) }
        val calls = ArrayList<String>()
        val gfx = ChapterMapGfx(rom, ChapterData(rom), hardware, playSt)
        gfx.resetText = { calls += "text" }
        gfx.loadUiFrameGraphics = {
            calls += "frame"
            UiFrame.loadUiFrameGraphics(hardware, rom, playSt.windowColor)
        }
        gfx.resetFaces = { calls += "faces" }
        gfx.resetIconGraphics = { calls += "icons" }
        gfx.loadIconPalettes = {
            calls += "iconPalettes"
            ItemIcon.loadIconPalettes(hardware, rom, it)
        }

        gfx.readGameSaveCoreGfx()

        assertEquals(listOf("text", "frame", "faces", "icons", "iconPalettes"), calls)
        val want = run.region(dumpName(3721), "ewram")
        for (b in listOf(1, 4, 5, OBJ_UI_BANK, OBJ_UI_BANK + 1)) {
            assertNotEquals(List(16) { 0 }, bank(hardware, b), "bank $b written")
            assertEquals(captured(want, b), bank(hardware, b), "bank $b")
        }
        val vram = run.region(dumpName(3730), "vram")
        val frameBytes = hardware.vram.copyOf(0x1000).indexOfLast { it != 0.toByte() } + 1
        assertTrue(vram.copyOfRange(0, frameBytes).contentEquals(hardware.vram.copyOf(frameBytes)))
        for (row in 0 until 4) {
            val at = ChapterMapGfx.OBJ_VRAM0 + row * 0x400
            assertTrue(
                vram.copyOfRange(at, at + 0x12 * 32)
                    .contentEquals(hardware.vram.copyOfRange(at, at + 0x12 * 32)),
                "OBJ UI tile row $row",
            )
        }
    }

    @Test
    @VerifiesPort("SetBlackPal")
    fun `the face palette of the talk start is black at step 2972`() {
        available()
        val run = ref.run(RUN)
        val hardware = hardwareAt(run.region(dumpName(2971), "ewram"))
        val want = run.region(dumpName(2972), "ewram")
        assertNotEquals(List(16) { 0 }, bank(hardware, FACE_BANK), "seed")
        val fade = PaletteFade(hardware, ProcEngine().also { it.init() })

        fade.setBlackPal(FACE_BANK)

        assertEquals(captured(want, FACE_BANK), bank(hardware, FACE_BANK))
        assertEquals(List(16) { 0 }, bank(hardware, FACE_BANK))
    }

    @Test
    @VerifiesPort("StartPalFadeToBlack")
    fun `the face fade out follows the captured palette bank per step`() {
        available()
        val run = ref.run(RUN)
        val hardware = hardwareAt(run.region(dumpName(3705), "ewram"))
        val engine = ProcEngine().also { it.init() }
        val fade = PaletteFade(hardware, engine)
        val parent = engine.start(idleScript, 3)

        fade.startPalFadeToBlack(FACE_BANK, 12, parent)
        for (step in 3706..3714) {
            engine.run(engine.root(3))
            val want = captured(run.region(dumpName(step), "ewram"), FACE_BANK)
            assertEquals(want, bank(hardware, FACE_BANK), "bank $FACE_BANK at step $step")
        }
        assertNotEquals(List(16) { 0 }, bank(hardware, FACE_BANK), "ended before black")
    }

    @Test
    @VerifiesPort("StartTemporaryLock", "TemporaryLock_OnLoop")
    fun `the face fade in lock counts down as the captured lock proc`() {
        available()
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp symbol table not present")
        val procs = run.watch("procs")
        val engine = ProcEngine().also { it.init() }
        val lock = TemporaryLock(engine)
        val parent = engine.start(idleScript, 3)

        assertNull(ProcArrayDump.ofWatch(procs, 2971, names).find("ProcScr_TemporaryLock"))
        lock.start(parent, FADE_IN_LOCK_FRAMES)
        for (step in 2972..2981) {
            engine.run(engine.root(3))
            val captured = ProcArrayDump.ofWatch(procs, step, names).find("ProcScr_TemporaryLock")
            val ours = engine.find(lock.script)
            assertEquals(captured != null, ours != null, "lock exists at step $step")
            if (captured != null && ours != null) {
                assertEquals(captured.u16(LOCK_COUNT), lock.remainingFrames(ours), "step $step")
            }
        }
        assertNotNull(parent)
        assertNull(engine.find(lock.script))
        assertEquals(0, parent.lockCnt, "the parent is released")
    }

    private val idleScript = ProcScript("idle", listOf(repeat(ProcFunc { _: Proc -> }), end()))

    private fun windowColor(step: Int): Int {
        val config = ref.run(RUN).watch("gplayst").u32(step, 0x40)
        return (config shr 10) and 3
    }

    private fun dumpName(step: Int): String =
        ref.run(RUN).dumps.entries.first { it.value == step }.key

    private fun hardwareAt(ewram: ByteArray): BgHardware = BgHardware().also {
        for (i in 0 until 32 * 16) it.palette[i] = u16(ewram, PALETTE + 2 * i).toShort()
    }

    private fun bank(hardware: BgHardware, b: Int): List<Int> =
        (0 until 16).map { hardware.palette[b * 16 + it].toInt() and 0xFFFF }

    private fun captured(ewram: ByteArray, b: Int): List<Int> =
        (0 until 16).map { u16(ewram, PALETTE + 2 * (b * 16 + it)) }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val RUN = "ram_trace_prologue_clear"

        /** `gPaletteBuffer` as an offset into the EWRAM dump (0x020228A8). */
        const val PALETTE = 0x228A8

        /** The first talk face's palette bank (OBJ 6, `0x10 + 6`). */
        const val FACE_BANK = 22

        /** The first OBJ bank `LoadObjUIGfx` fills (`0x10`). */
        const val OBJ_UI_BANK = 16

        /** The lock proc's `unk58` and the frames the face fade-in locks for. */
        const val LOCK_COUNT = 0x58
        const val FADE_IN_LOCK_FRAMES = 8
    }
}
