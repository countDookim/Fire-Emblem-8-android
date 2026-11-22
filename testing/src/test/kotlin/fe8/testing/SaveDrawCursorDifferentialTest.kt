package fe8.testing

import fe8.game.RomView
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawCursorProc
import fe8.game.savemenu.SaveDrawParent
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [SaveDrawCursor] (`StartSaveDrawCursor`, `sub_80AB4F4`,
 * `SaveDrawCursor_Init`, `SaveDrawCursor_Loop`, `gProcScr_SaveDrawCursor`) against
 * `ram_trace_prologue_end` (`PC-cap1`, steps `9459..9863`).
 *
 * In `PC-cap1`:
 * - `f9459` runs `SaveDraw_Init` (`StartSaveDrawCursor`) and `SaveDraw_Loop_Main` (`sub_80AB4F4`
 *   for slot 0: `(1, 12, 32)`) before `SaveDrawCursor_Loop` executes; `r09459` holds `unk_2a = 0`
 *   and `cursor_en = 1`.
 * - `r09460` runs the first `SaveDrawCursor_Loop`, half-step smoothing `y` from `0` to `10`
 *   (`gOam` attr0 `0x000A`), then `r09461..r09862` settle at
 *   `y = 20 + yOffsetLut[(unk_2a >> 3) & 7]`
 *   across slot 0 idle (`f9461..9599`), the confirm prompt (`f9600..9801`, `cursor_config = 1`),
 *   and post-write slot scroll / fade-out (`f9802..9862`).
 * - Nine VBlank overruns (`9800..9801`, `9813..9814`, `9839..9843`) skip a main step.
 * - `f9863` ends `ProcScr_savedraw` (`PostSaveMenuHandler`), ending `gProcScr_SaveDrawCursor`.
 *
 * Only `StartSaveDrawCursor` and `sub_80AB4F4` carry `@VerifiesPort`. `SaveDrawCursor_Init` is
 * observed only for the fields the capture does not overwrite (`unk_2a`, `sus_slot`,
 * `main_options`, `unk_34`, `unk_38`, seeded from stale `f9458` slot bytes), and
 * `SaveDrawCursor_Loop` has unexercised branches (`parent.unk_29 == 0`, `susSlot >= 4`,
 * `cursorEn == 0`, `unk3a == 0` dual-sprite cursor, `unk38 != 0` static copy cursor), so both
 * stay `Transcribed`.
 */
class SaveDrawCursorDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("StartSaveDrawCursor", "sub_80AB4F4")
    fun `cursor proc state, sprite layers, and OAM match PC-cap1 f9459 to f9863`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")

        val rom = RomView.ofRom(romFile.readBytes())
        val romSprite = rom.objN(SaveDrawCursor.SPRITE_CURSOR_ADDRESS)
        assertArrayEquals(
            romSprite.data,
            SaveDrawCursor.DEFAULT_CURSOR_SPRITE.data,
            "Sprite_08A20570 halfwords match baserom.gba",
        )

        val procsTrace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procsTrace, step, names)

        val engine = ProcEngine()
        val layers = SpriteLayers()
        val oam = OamBuffer()
        val cursor = SaveDrawCursor(engine, layers, rom)
        var currentSusSlot = 0
        lateinit var cursorProc: Proc

        val parentScript = ProcScript(
            "ProcScr_savedraw",
            listOf(
                name("savedraw"),
                repeat(ProcFunc { proc ->
                    val pState = proc.state as TestSaveDrawParent
                    if (pState.cursorDrawActive != 0) {
                        cursor.sub80AB4F4(1, 12, currentSusSlot * 32 + 32, proc)
                    }
                }),
                end(),
            ),
        ) { TestSaveDrawParent() }

        // f9459: SaveDraw_Init starts gProcScr_SaveDrawCursor (SaveDrawCursor_Init runs), and
        // SaveDraw_Loop_Main calls sub_80AB4F4(1, 12, 32, proc) before r09459 is dumped.
        val parentProc = engine.start(parentScript, 3)
        val parentState = parentProc.state as TestSaveDrawParent
        cursorProc = cursor.startSaveDrawCursor(parentProc)
        parentState.drawCursorProc = cursorProc
        val cursorState = cursorProc.state as SaveDrawCursorProc

        // Seed the stale bytes of f9458 slot 16 (the slot the cursor proc reuses) so the f9459
        // and f9460 comparisons observe saveDrawCursorInit clearing unk2a, susSlot, mainOptions,
        // unk34 and unk38; cursorEn, unk2e, unk32 and unk3a are overwritten by sub_80AB4F4.
        val dirtySlotBytes = procsTrace.step(9458)
        val dirtyBase = 16 * ProcArrayDump.PROC_SIZE
        cursorState.unk2a = u16(dirtySlotBytes, dirtyBase + SC_UNK2A)
        cursorState.susSlot = u8(dirtySlotBytes, dirtyBase + SC_SUS_SLOT)
        cursorState.unk2e = s16(dirtySlotBytes, dirtyBase + SC_UNK2E)
        cursorState.mainOptions = s16(dirtySlotBytes, dirtyBase + SC_MAIN_OPTIONS)
        cursorState.unk32 = s16(dirtySlotBytes, dirtyBase + SC_UNK32)
        cursorState.unk34 = s16(dirtySlotBytes, dirtyBase + SC_UNK34)
        cursorState.cursorEn = u8(dirtySlotBytes, dirtyBase + SC_CURSOR_EN)
        cursorState.unk37 = u8(dirtySlotBytes, dirtyBase + SC_UNK37)
        cursorState.unk38 = u8(dirtySlotBytes, dirtyBase + SC_UNK38)
        cursorState.unk3a = u8(dirtySlotBytes, dirtyBase + SC_UNK3A)
        cursor.saveDrawCursorInit(cursorState)

        currentSusSlot = dump(9459).find("gProcScr_SaveMenuPostChapter")!!.u8(SM_SUS_SLOT)
        cursor.sub80AB4F4(1, 12, currentSusSlot * 32 + 32, parentProc)

        val d9459 = dump(9459)
        val expected9459 = checkNotNull(d9459.find("gProcScr_SaveDrawCursor"))
        assertEquals(parentProc, cursorProc.parent, "parent proc at f9459")
        assertEquals(
            d9459.find("ProcScr_savedraw")!!.address,
            expected9459.u32(PROC_PARENT),
            "parent in capture at f9459",
        )
        assertProcMatches(expected9459, cursorProc, 9459)
        assertTrue(
            readCursorSpritesFromPool(region(run, 9459, "ewram"), region(run, 9459, "iwram"))
                .isEmpty(),
            "r09459 has no Sprite_08A20570 queued yet",
        )
        assertEquals(0, layers.allocIt, "no cursor sprite queued at f9459")

        // f9460: SaveDrawCursor_Loop completes the f9459 cycle (first interpolation step y = 10).
        layers.clear()
        oam.clear(0, OamBuffer.OBJ_COUNT)
        oam.hiPut = 0
        cursor.saveDrawCursorLoop(cursorProc)
        pushLayer4ToOam(layers, oam)
        assertStepMatches(run, dump(9460), cursorProc, layers, oam, 9460)
        assertEquals(10, oam.buffer[0].toInt() and 0xFF, "first glide y at f9460")
        var stepFinished = true

        val observedOverruns = mutableListOf<Int>()
        val observedBobOffsets = mutableSetOf<Int>()
        var prevCapturedUnk2a = cursorState.unk2a

        for (step in 9461..9862) {
            val dStep = dump(step)
            val capturedCursor = checkNotNull(dStep.find("gProcScr_SaveDrawCursor")) {
                "gProcScr_SaveDrawCursor missing at f$step"
            }
            val capturedUnk2a = capturedCursor.u16(SC_UNK2A)
            val ewram = region(run, step, "ewram")
            val iwram = region(run, step, "iwram")

            if (stepFinished) {
                oam.syncHi()
            }

            if (capturedUnk2a == prevCapturedUnk2a) {
                // Frame overrun (WriteGameSave / PutChapterTitleGfx / InitSaveMenuChoice):
                // VBlank fired mid-frame before OnMain ran the cursor procs.
                observedOverruns += step
                assertEquals(0, u8(ewram, G_BM_ST - GbaMem.EWRAM), "bmst main_loop_ended at f$step")
                assertProcMatches(capturedCursor, cursorProc, step)
                assertTrue(
                    readCursorSpritesFromPool(ewram, iwram).isEmpty(),
                    "overrun f$step has empty sSpritePool",
                )
                assertTrue(
                    readCursorFromGOam(iwram).isEmpty(),
                    "overrun f$step has cleared gOam",
                )
                if (step in GRAPHIC_DUMP_STEPS) {
                    assertHwOamMatches(run, oam, step)
                }
                stepFinished = false
                continue
            }

            currentSusSlot = dStep.find("gProcScr_SaveMenuPostChapter")!!.u8(SM_SUS_SLOT)
            layers.clear()
            oam.clear(0, OamBuffer.OBJ_COUNT)
            oam.hiPut = 0
            engine.run(engine.root(3))
            pushLayer4ToOam(layers, oam)
            stepFinished = true

            assertStepMatches(run, dStep, cursorProc, layers, oam, step)
            if (step in GRAPHIC_DUMP_STEPS) {
                assertHwOamMatches(run, oam, step)
            }
            observedBobOffsets += (oam.buffer[0].toInt() and 0xFF) - 20
            prevCapturedUnk2a = capturedUnk2a
        }

        assertEquals(EXPECTED_OVERRUN_STEPS, observedOverruns, "VBlank overrun steps")
        assertEquals(setOf(0, 1, 2, 3), observedBobOffsets, "all bobbing y offsets exercised")

        // f9863: VBlank syncs f9862's cursor to HW OAM, then PostSaveMenuHandler ends savedraw.
        oam.syncHi()
        assertHwOamMatches(run, oam, 9863)
        engine.end(parentProc)
        assertNull(cursorProc.script, "gProcScr_SaveDrawCursor ended with ProcScr_savedraw")
        assertNull(dump(9863).find("gProcScr_SaveDrawCursor"), "cursor proc gone at f9863")
    }

    private fun assertStepMatches(
        run: ReferenceData.Run,
        dump: ProcArrayDump,
        cursorProc: Proc,
        layers: SpriteLayers,
        oam: OamBuffer,
        step: Int,
    ) {
        val capturedCursor = checkNotNull(dump.find("gProcScr_SaveDrawCursor"))
        assertProcMatches(capturedCursor, cursorProc, step)

        val ewram = region(run, step, "ewram")
        val iwram = region(run, step, "iwram")
        val dumpedSprites = readCursorSpritesFromPool(ewram, iwram)
        val ownSprites = readOwnLayer4CursorSprites(layers)
        assertEquals(dumpedSprites, ownSprites, "layer 4 cursor sprite at f$step")

        val dumpedGOam = readCursorFromGOam(iwram)
        assertEquals(1, dumpedGOam.size, "expected 1 gOam cursor entry at f$step")
        assertEquals(1, oam.hiPut / 4, "expected 1 OAM object pushed by cursor at f$step")
        val ownOam = listOf(
            oam.buffer[0].toInt() and 0xFFFF,
            oam.buffer[1].toInt() and 0xFFFF,
            oam.buffer[2].toInt() and 0xFFFF,
        )
        assertEquals(dumpedGOam.single(), ownOam, "gOam cursor object at f$step")
    }

    private fun assertHwOamMatches(run: ReferenceData.Run, oam: OamBuffer, step: Int) {
        val hwOam = run.region("f%05d".format(step), "oam")
        val dumped = readCursorFromHwOam(hwOam)
        assertEquals(1, dumped.size, "expected 1 HW OAM cursor entry at f$step")
        val own = listOf(
            oam.oam[0].toInt() and 0xFFFF,
            oam.oam[1].toInt() and 0xFFFF,
            oam.oam[2].toInt() and 0xFFFF,
        )
        assertEquals(dumped.single(), own, "HW OAM cursor object at f$step")
    }

    private fun assertProcMatches(captured: ProcArrayDump.Proc, cursorProc: Proc, step: Int) {
        assertEquals(captured.scrCur, cursorProc.scrCur, "scrCur at f$step")
        val state = cursorProc.state as SaveDrawCursorProc
        val expectedFields = listOf(
            captured.u16(SC_UNK2A),
            captured.u8(SC_SUS_SLOT),
            s16(captured, SC_UNK2E),
            s16(captured, SC_MAIN_OPTIONS),
            s16(captured, SC_UNK32),
            s16(captured, SC_UNK34),
            captured.u8(SC_CURSOR_EN),
            captured.u8(SC_UNK37),
            captured.u8(SC_UNK38),
            captured.u8(SC_UNK39),
            captured.u8(SC_UNK3A),
        )
        val actualFields = listOf(
            state.unk2a,
            state.susSlot,
            state.unk2e,
            state.mainOptions,
            state.unk32,
            state.unk34,
            state.cursorEn,
            state.unk37,
            state.unk38,
            state.unk39,
            state.unk3a,
        )
        assertEquals(expectedFields, actualFields, "SaveDrawCursorProc fields at f$step")
    }

    private fun pushLayer4ToOam(layers: SpriteLayers, oam: OamBuffer) {
        layers.pushLayerObjects(SaveDrawCursor.CURSOR_SPRITE_LAYER) { entry ->
            oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
        }
    }

    private fun readOwnLayer4CursorSprites(layers: SpriteLayers): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        layers.pushLayerObjects(SaveDrawCursor.CURSOR_SPRITE_LAYER) { entry ->
            if (entry.obj?.id == SaveDrawCursor.SPRITE_CURSOR_ADDRESS) {
                out += listOf(entry.oam1, entry.oam0, entry.oam2)
            }
        }
        return out
    }

    private fun readCursorSpritesFromPool(ewram: ByteArray, iwram: ByteArray): List<List<Int>> {
        val allocIt = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        val count = (allocIt - ADDR_S_SPRITE_POOL) / SPRITE_ENTRY_SIZE
        val out = ArrayList<List<Int>>()
        for (i in 0 until count) {
            val base = (ADDR_S_SPRITE_POOL - GbaMem.EWRAM) + i * SPRITE_ENTRY_SIZE
            val objAddr = u32(ewram, base + 0x0C)
            if (objAddr == SaveDrawCursor.SPRITE_CURSOR_ADDRESS) {
                out += listOf(
                    s16(ewram, base + 0x04),
                    s16(ewram, base + 0x06),
                    u16(ewram, base + 0x08),
                )
            }
        }
        return out
    }

    private fun readCursorFromGOam(iwram: ByteArray): List<List<Int>> {
        val base = ADDR_G_OAM - GbaMem.IWRAM
        val out = ArrayList<List<Int>>()
        for (i in 0 until OamBuffer.OBJ_COUNT) {
            val off = base + i * 8
            val attr0 = u16(iwram, off)
            val attr1 = u16(iwram, off + 2)
            val attr2 = u16(iwram, off + 4)
            if (attr2 == EXPECTED_CURSOR_OAM_ATTR2 && attr0 != OamBuffer.OFFSCREEN_Y) {
                out += listOf(attr0, attr1, attr2)
            }
        }
        return out
    }

    private fun readCursorFromHwOam(hwOam: ByteArray): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (i in 0 until OamBuffer.OBJ_COUNT) {
            val off = i * 8
            val attr0 = u16(hwOam, off)
            val attr1 = u16(hwOam, off + 2)
            val attr2 = u16(hwOam, off + 4)
            if (attr2 == EXPECTED_CURSOR_OAM_ATTR2 && attr0 != OamBuffer.OFFSCREEN_Y) {
                out += listOf(attr0, attr1, attr2)
            }
        }
        return out
    }

    private fun region(run: ReferenceData.Run, step: Int, kind: String): ByteArray {
        val prefix = if (step in GRAPHIC_DUMP_STEPS) "f" else "r"
        return run.region("%s%05d".format(prefix, step), kind)
    }

    private class TestSaveDrawParent(
        override var cursorDrawActive: Int = 1,
        override var drawCursorProc: Proc? = null,
    ) : SaveDrawParent

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val G_BM_ST = 0x0202BCB0
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val ADDR_G_OAM = 0x03003140
        const val SPRITE_ENTRY_SIZE = 16
        const val EXPECTED_CURSOR_OAM_ATTR2 = 0x345C

        const val PROC_PARENT = 0x14
        const val SM_SUS_SLOT = 0x2C

        const val SC_UNK2A = 0x2A
        const val SC_SUS_SLOT = 0x2C
        const val SC_UNK2E = 0x2E
        const val SC_MAIN_OPTIONS = 0x30
        const val SC_UNK32 = 0x32
        const val SC_UNK34 = 0x34
        const val SC_CURSOR_EN = 0x36
        const val SC_UNK37 = 0x37
        const val SC_UNK38 = 0x38
        const val SC_UNK39 = 0x39
        const val SC_UNK3A = 0x3A

        val GRAPHIC_DUMP_STEPS = setOf(9444, 9494, 9500, 9600, 9700, 9799, 9800, 9802, 9863)
        val EXPECTED_OVERRUN_STEPS = listOf(9800, 9801, 9813, 9814, 9839, 9840, 9841, 9842, 9843)

        fun u8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF
        fun u16(buf: ByteArray, off: Int): Int = u8(buf, off) or (u8(buf, off + 1) shl 8)
        fun s16(buf: ByteArray, off: Int): Int = u16(buf, off).toShort().toInt()
        fun u32(buf: ByteArray, off: Int): Int = u16(buf, off) or (u16(buf, off + 2) shl 16)
        fun s16(p: ProcArrayDump.Proc, field: Int): Int = p.u16(field).toShort().toInt()
    }
}
