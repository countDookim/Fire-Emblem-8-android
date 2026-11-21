package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.PostSaveMenu
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuRTextData
import fe8.game.savemenu.SaveMenuState
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.PaletteFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for `PC-4a1` ([SaveMenuPostChapter] and [BmSaveLib.readLastGameSaveId])
 * against `ram_trace_prologue_end` (`PC-cap1`):
 * - Entry at `r09443 -> f09444` (`s09350` SRAM): `Make6C_SaveMenuPostChapter`, `SaveMenuInit`,
 *   `ReadLastGameSaveId`, `SaveMenu_SetLcdChapterIdx`, `InitSaveMenuHelpTextSt`.
 * - Direct slot-select branch at `r09460 -> r09461 -> r09492 -> r09493 -> f09494`:
 *   `SaveMenuDirectlySelectSlotOnPrepScreen` -> `NewFadeIn(4)` (`ProcScr_BmFadeIN`) ->
 *   `PL_SAVEMENU_SAVE_SLOT_SEL` (`scrCur = 16`).
 *
 * Unexercised branches (`SaveMenu_SetLcdChapterIdx` world-map/Frelia overrides,
 * `SaveMenuDirectlySelectSlotOnPrepScreen` prep-screen `NewFadeIn(8)` path, and
 * `ReadLastGameSaveId` invalid-header/out-of-range fallbacks) remain `Transcribed from ...` and
 * are excluded from `@VerifiesPort`.
 */
class PostChapterSaveMenuEntryDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("Make6C_SaveMenuPostChapter", "SaveMenuInit", "InitSaveMenuHelpTextSt")
    fun `entry initializes SaveMenuProc, RTextData, and LCD state like f09444`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasSram, "$RUN has no SRAM dumps")

        val ew9443 = run.region("r09443", "ewram")
        val iw9443 = run.region("r09443", "iwram")
        val ew9444 = run.region("f09444", "ewram")
        val iw9444 = run.region("f09444", "iwram")
        val s9350 = run.sram("s09350")

        val gc9444 = ProcArrayDump.ofEwram(ew9444).find(PROC_GAME_CONTROL)!!
        val sm9444 = ProcArrayDump.ofEwram(ew9444).find(PROC_SAVE_MENU_POST_CHAPTER)!!

        val sram = CartSram().also { it.copyIn(s9350) }
        val saveLib = BmSaveLib(sram).also { it.sramInit() }
        assertEquals(0, saveLib.readLastGameSaveId(), "ReadLastGameSaveId(s09350)")

        val engine = ProcEngine()
        val hardware = BgHardware()
        hardware.lcd.readFrom(iw9443, GLCD_CONTROL - IWRAM)

        val playSt = PlaySt().apply { decode(ew9444, GPLAYST - EWRAM) }
        assertEquals(1, playSt.chapterIndex, "f09444 chapterIndex == CHAPTER_L_1")
        assertEquals(2, playSt.saveMenuType, "f09444 save_menu_type")
        assertEquals(0, playSt.chapterStateBits, "f09444 chapterStateBits")

        val bmSt = BmSt().apply { decode(ew9443, GBMST - EWRAM) }
        val gmData = GmData(iw9443.copyOf(), RngService())
        val rTextData = SaveMenuRTextData(pid = 99, level = 99, nodeId = 99)
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val menu = SaveMenuPostChapter(
            engine, hardware, playSt, bmSt, gmData, saveLib, archivedFade, rTextData,
        )

        val parentScript = ProcScript(
            "test-parent",
            listOf(
                call(ProcFunc { menu.make6cSaveMenuPostChapter(it) }),
                yield(),
                end(),
            ),
        )
        val parent = engine.start(parentScript, 3)
        engine.run(engine.root(3))

        val proc = engine.find(menu.script)
        assertNotNull(proc, "gProcScr_SaveMenuPostChapter started")
        proc!!
        assertEquals(gc9444.u8(0x28), parent.lockCnt, "blocking lockCnt on parent at f09444")
        assertEquals(sm9444.scrCur, proc.scrCur, "SaveMenuPostChapter scrCur at f09444")
        assertEquals(0, proc.lockCnt, "SaveMenuPostChapter lockCnt at f09444")

        val state = proc.state as SaveMenuState
        assertEquals(sm9444.u8(0x2B), state.mainSelect, "main_select")
        assertEquals(sm9444.u8(0x2C), state.susSlot, "sus_slot")
        assertEquals(sm9444.u8(0x2E), state.jumpLabel, "jump_label")
        assertEquals(sm9444.u8(0x2F), state.unk2f, "unk_2f")
        assertEquals(sm9444.u8(0x30), state.mainOptions, "main_options")
        assertEquals(sm9444.u8(0x31), state.unk31, "unk_31")
        assertEquals(sm9444.u8(0x34), state.extraSelect, "extra_select")
        assertEquals(sm9444.u16(0x42), state.mainSelBitfile, "main_sel_bitfile")
        assertEquals(sm9444.u16(0x46), state.unk46, "unk_46")

        // Verify SaveMenuInit overwrites pre-populated non-zero sentinel values.
        val sentinelProc = engine.start(menu.script, 3)
        val sentinelState = sentinelProc.state as SaveMenuState
        sentinelState.jumpLabel = 0x55
        sentinelState.susSlot = 0x55
        sentinelState.mainSelect = 0x55
        sentinelState.extraSelect = 0x55
        sentinelState.unk46 = 0x55
        sentinelState.mainOptions = 0x55
        sentinelState.mainSelBitfile = 0x55
        sentinelState.unk31 = 0x55
        sentinelState.unk2f = 0x55
        menu.saveMenuInit(sentinelProc)
        assertEquals(sm9444.u8(0x2E), sentinelState.jumpLabel, "sentinel jump_label")
        assertEquals(sm9444.u8(0x2C), sentinelState.susSlot, "sentinel sus_slot")
        assertEquals(sm9444.u8(0x2B), sentinelState.mainSelect, "sentinel main_select")
        assertEquals(sm9444.u8(0x34), sentinelState.extraSelect, "sentinel extra_select")
        assertEquals(sm9444.u16(0x46), sentinelState.unk46, "sentinel unk_46")
        assertEquals(sm9444.u8(0x30), sentinelState.mainOptions, "sentinel main_options")
        assertEquals(sm9444.u16(0x42), sentinelState.mainSelBitfile, "sentinel main_sel_bitfile")
        assertEquals(sm9444.u8(0x31), sentinelState.unk31, "sentinel unk_31")
        assertEquals(sm9444.u8(0x2F), sentinelState.unk2f, "sentinel unk_2f")
        engine.end(sentinelProc)

        // Check gSaveMenuRTextData against f09444.
        assertEquals(ew9444[GSAVEMENU_RTEXT - EWRAM].toInt() and 0xFF, rTextData.pid, "rText.pid")
        assertEquals(ew9444[GSAVEMENU_RTEXT - EWRAM + 1].toInt(), rTextData.level, "rText.level")
        assertEquals(ew9444[GSAVEMENU_RTEXT - EWRAM + 2].toInt(), rTextData.nodeId, "rText.nodeId")

        // Check gLCDControlBuffer against f09444.
        val expectedLcd = LcdControl().apply { readFrom(iw9444, GLCD_CONTROL - IWRAM) }
        assertEquals(lcdFields(expectedLcd), lcdFields(hardware.lcd), "gLCDControlBuffer at f09444")
    }

    @Test
    @VerifiesPort("SaveMenuDirectlySelectSlotOnPrepScreen")
    fun `direct slot select runs NewFadeIn(4) and enters slot select loop at f09494`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasSram, "$RUN has no SRAM dumps")

        val ew9460 = run.region("r09460", "ewram")
        val iw9460 = run.region("r09460", "iwram")
        val ew9461 = run.region("r09461", "ewram")
        val iw9461 = run.region("r09461", "iwram")
        val ew9492 = run.region("r09492", "ewram")
        val ew9493 = run.region("r09493", "ewram")
        val ew9494 = run.region("f09494", "ewram")
        val iw9494 = run.region("f09494", "iwram")

        val sm9460 = ProcArrayDump.ofEwram(ew9460).find(PROC_SAVE_MENU_POST_CHAPTER)!!
        val sm9461 = ProcArrayDump.ofEwram(ew9461).find(PROC_SAVE_MENU_POST_CHAPTER)!!
        val fade9461 = ProcArrayDump.ofEwram(ew9461).find(PROC_BM_FADE_IN)!!
        val sm9492 = ProcArrayDump.ofEwram(ew9492).find(PROC_SAVE_MENU_POST_CHAPTER)!!
        val fade9492 = ProcArrayDump.ofEwram(ew9492).find(PROC_BM_FADE_IN)!!
        val sm9493 = ProcArrayDump.ofEwram(ew9493).find(PROC_SAVE_MENU_POST_CHAPTER)!!
        val sm9494 = ProcArrayDump.ofEwram(ew9494).find(PROC_SAVE_MENU_POST_CHAPTER)!!

        assertNull(ProcArrayDump.ofEwram(ew9460).find(PROC_BM_FADE_IN), "no fade yet at r09460")
        assertNull(ProcArrayDump.ofEwram(ew9493).find(PROC_BM_FADE_IN), "fade gone at r09493")

        val engine = ProcEngine()
        val hardware = BgHardware()
        hardware.lcd.readFrom(iw9460, GLCD_CONTROL - IWRAM)
        for (i in hardware.palette.indices) {
            hardware.palette[i] = u16(ew9460, GPALETTE_BUFFER - EWRAM + i * 2).toShort()
        }

        val sram = CartSram().also { it.copyIn(run.sram("s09350")) }
        val saveLib = BmSaveLib(sram).also { it.sramInit() }
        val playSt = PlaySt().apply { decode(ew9460, GPLAYST - EWRAM) }
        val bmSt = BmSt().apply { decode(ew9460, GBMST - EWRAM) }
        val gmData = GmData(iw9460.copyOf(), RngService())
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        var loopRan = false
        val menu = SaveMenuPostChapter(
            engine, hardware, playSt, bmSt, gmData, saveLib, archivedFade,
            hooks = SaveMenuPostChapter.Hooks(
                saveSlotSelectLoop = ProcFunc { loopRan = true },
            ),
        )

        val parentScript = ProcScript("test-parent", listOf(yield(), end()))
        val parent = engine.start(parentScript, 3)
        parent.idleCb = null
        val proc = menu.make6cSaveMenuPostChapter(parent)!!
        proc.idleCb = null
        proc.scrCur = sm9460.scrCur

        // Frame 9461: SaveMenuDirectlySelectSlotOnPrepScreen jumps to
        // PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN, starts NewFadeIn(4) on tree 4, and pauses at
        // PROC_WHILE(FadeInExists).
        engine.run(engine.root(3))
        engine.run(engine.root(4))

        assertEquals(sm9461.scrCur, proc.scrCur, "r09461 SaveMenuPostChapter scrCur")
        val fadeProc = engine.find(archivedFade.script)
        assertNotNull(fadeProc, "r09461 ProcScr_BmFadeIN started")
        val fadeState = archivedFade.stateOf(fadeProc!!)
        assertEquals(fade9461.u8(0x29) != 0, fadeState.whiteOut, "r09461 fade white_out")
        assertEquals(fade9461.u32(0x2C), fadeState.timer, "r09461 fade timer")
        assertEquals(fade9461.u32(0x30), fadeState.speed, "r09461 fade speed")
        assertEquals(fade9461.u32(0x34), fadeState.mask, "r09461 fade mask")
        assertEquals(
            u16(iw9461, GLCD_CONTROL - IWRAM),
            hardware.lcd.dispcnt,
            "r09461 DISPCNT enabled by FadeIn_Loop",
        )

        // Frames 9462..9492: fade reaches timer 0x80 and breaks on frame 9492.
        repeat(31) {
            engine.run(engine.root(3))
            engine.run(engine.root(4))
        }
        assertEquals(sm9492.scrCur, proc.scrCur, "r09492 SaveMenuPostChapter scrCur")
        assertEquals(fade9492.u32(0x2C), fadeState.timer, "r09492 fade timer")
        assertNull(fadeProc.idleCb, "r09492 fade broke repeat callback")
        assertTrue(archivedFade.exists(), "r09492 fade still waiting for PROC_END on tree 4")

        // Frame 9493: tree 3 sees FadeInExists() == true before tree 4 runs PROC_END.
        engine.run(engine.root(3))
        engine.run(engine.root(4))
        assertEquals(sm9493.scrCur, proc.scrCur, "r09493 SaveMenuPostChapter scrCur")
        assertFalse(archivedFade.exists(), "r09493 ProcScr_BmFadeIN ended on tree 4")

        // Frame 9494: tree 3 sees FadeInExists() == false and enters SaveMenu_SaveSlotSelectLoop.
        engine.run(engine.root(3))
        engine.run(engine.root(4))
        assertEquals(sm9494.scrCur, proc.scrCur, "f09494 SaveMenuPostChapter scrCur")
        assertTrue(loopRan, "f09494 SaveMenu_SaveSlotSelectLoop ran")
        assertEquals(
            u16(iw9494, GLCD_CONTROL - IWRAM),
            hardware.lcd.dispcnt,
            "f09494 DISPCNT",
        )
    }

    private fun lcdFields(lcd: LcdControl): List<Any> = listOf(
        lcd.dispcnt, lcd.dispstat, lcd.bgCnt.toList(), lcd.bgHofs.toList(), lcd.bgVofs.toList(),
        lcd.winH.toList(), lcd.winV.toList(), lcd.wincnt, lcd.mosaic, lcd.bldcnt,
        lcd.blendCoeffA, lcd.blendCoeffB, lcd.blendY, lcd.affine.toList(), lcd.colorAddition,
    )

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPALETTE_BUFFER = 0x020228A8
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GSAVEMENU_RTEXT = 0x0203EF64
        const val GLCD_CONTROL = 0x03003080
        const val PROC_GAME_CONTROL = 0x085916D4
        const val PROC_SAVE_MENU_POST_CHAPTER = 0x08A203A8
        const val PROC_BM_FADE_IN = 0x08A20DA4

        fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
