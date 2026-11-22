package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.PostSaveMenu
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveDrawParticle
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuState
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.render.HBlankLines
import gba.render.LineTable
import gba.render.RenderState
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for `PC-4b` ([SaveDrawLifecycle] and [BgVerticalScroll] lifecycle methods)
 * against `ram_trace_prologue_end` (`PC-cap1`, `f9458..9863`):
 * - `r09458 -> r09459`: `StartSaveDraw`, `SaveDraw_Init` (`ProcScr_savedraw` mark `12`,
 *   `gProcScr_SaveDrawCursor`, suspend bookmark `ProcScr_ApProc`, `StartBgVerticalScroll`,
 *   `SetBgVerticalScrollPosition(0, REG_BG2HOFS)`, `SaveDraw_ScrollFogBG`, `sub_80AB548`).
 * - `f9460..9862`: mid-run fog scroll (`f09494`, `f09600`, `r09862`), `gLCDControlBuffer` BG2
 *   offsets, and per-scanline `HBlank_BgVerticalScroll` writes to `BG2HOFS`.
 * - `r09862 -> f09863`: `PostSaveMenuHandler` teardown via [SaveMenuPostChapter.endMenuProcs] and
 *   [SaveMenuPostChapter.clearHBlankHandler], calling `SaveDraw_OnEnd` -> `EndBgVerticalScroll`.
 *
 * `SaveDraw_Init` (`susSlotCur == 0xFF` branch unexercised; `SetObjAffine(0..3)` and
 * `sub_8014EF4(0)` unobservable over `r09458` state) and `sub_8014EF4` remain `Transcribed` and
 * are excluded from `@VerifiesPort`.
 */
class SaveDrawLifecycleDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(
        val rom: RomView,
        val run: ReferenceData.Run,
        val names: Map<Int, String>,
    ) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val scroll = BgVerticalScroll()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val cursor = SaveDrawCursor(engine, layers, rom)
        val apPlayer = SpriteAnimPlayer(
            { rom.u16(it) },
            layers,
            WorldMapApHost(rom, hardware, oam),
        )
        val apProcs = ApProcs(engine, apPlayer)
        val lifecycle = SaveDrawLifecycle(engine, hardware, scroll, oam, cursor, apProcs, rom)
        val playSt = PlaySt()
        val bmSt = BmSt()
        val gmData = GmData(ByteArray(0x8000), RngService())
        val saveLib = BmSaveLib(CartSram()).also { it.sramInit() }
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val menu = SaveMenuPostChapter(
            engine, hardware, playSt, bmSt, gmData, saveLib, archivedFade,
        )

        fun procs(step: Int): ProcArrayDump =
            ProcArrayDump.ofWatch(run.watch("procs"), step, names)
    }

    private fun createHarness(): Harness {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        return Harness(RomView.ofRom(romFile.readBytes()), run, names)
    }

    @Test
    @VerifiesPort(
        "StartBgVerticalScroll",
        "SetBgVerticalScrollPosition",
        "StartSaveDraw",
        "sub_80AB548",
    )
    fun `entry at f9458 to f9459 starts savedraw, cursor, ApProc, HBlank scroll, and particles`() {
        val h = createHarness()
        val ew9458 = h.run.region("r09458", "ewram")
        val iw9458 = h.run.region("r09458", "iwram")
        val ew9459 = h.run.region("r09459", "ewram")
        val iw9459 = h.run.region("r09459", "iwram")

        // Verify StartBgVerticalScroll also resets dirty frame/flip/mode/src/scrollEnabled from
        // the end-of-menu r09862 state to match the initialized scroll fields in r09459.
        val ew9862 = h.run.region("r09862", "ewram")
        val dirtyScroll = BgVerticalScroll().apply {
            readFrom(ew9862, ADDR_BG_VSCROLL - EWRAM)
            mode[0] = 3
            mode[1] = 2
            mode[2] = 1
        }
        val dirtyHblank = BgHardware().also {
            it.lcd.readFrom(iw9458, ADDR_LCD_CONTROL - IWRAM)
        }
        dirtyScroll.start(dirtyHblank.hblank)
        assertEquals(listOf(0, 0, 0), dirtyScroll.mode.toList(), "mode cleared by Start")
        assertEquals(listOf(-1, -1, -1), dirtyScroll.src.toList(), "src cleared by Start")
        assertEquals(listOf(-1, -1, -1), dirtyScroll.dst.toList(), "dst cleared by Start")
        assertEquals(0, dirtyScroll.frame, "frame cleared by Start")
        assertEquals(0, dirtyScroll.frameFlip, "frameFlip cleared by Start")
        assertFalse(dirtyScroll.updating, "updating cleared by Start")
        assertFalse(dirtyScroll.scrollEnabled, "scrollEnabled cleared by Start")
        assertSame(dirtyScroll, dirtyHblank.hblank.primary, "primary handler installed")
        assertSame(dirtyScroll, dirtyHblank.hblank.active, "active IRQ handler installed")
        assertTrue(dirtyHblank.hblank.irqEnabled, "HBlank IRQ enabled")

        // Seed live state from r09458 right before StartSaveDraw runs at f9459.
        h.hardware.lcd.readFrom(iw9458, ADDR_LCD_CONTROL - IWRAM)
        h.scroll.readFrom(ew9458, ADDR_BG_VSCROLL - EWRAM)
        readParticles(ew9458, h.lifecycle.particles)

        val d9458 = h.procs(9458)
        val sm9458 = d9458.find("gProcScr_SaveMenuPostChapter")!!
        val parentScript = ProcScript("gProcScr_GameControl", listOf(block()))
        val gameControl = h.engine.start(parentScript, 3)

        h.menu.hooks.initScreen = ProcFunc { proc -> h.menu.startSaveDraw(proc) }
        h.menu.wireSaveDraw(h.lifecycle)
        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!
        menuProc.idleCb = null
        // sm9458 has scrCur == 6 while SaveMenu_InitScreen (cmd 5) spans VBlanks; start at cmd 5
        // so this step runs initScreen -> StartSaveDraw -> SaveDraw_Init and yields at scrCur 7.
        menuProc.scrCur = sm9458.scrCur - 1
        val menuState = menuProc.state as SaveMenuState
        menuState.susSlot = sm9458.u8(SM_SUS_SLOT)
        menuState.susSlotCur = sm9458.u8(SM_SUS_SLOT_CUR)
        menuState.mainSelBitfile = sm9458.u16(SM_MAIN_SEL_BITFILE)
        menuState.slots.unk44 = sm9458.u16(SM_UNK_44)

        // Frame 9459: SaveMenu_InitScreen calls StartSaveDraw -> SaveDraw_Init, yields at scrCur 7,
        // and ProcEngine immediately executes child ProcScr_savedraw's SaveDraw_Loop_Main once.
        h.engine.run(h.engine.root(3))

        val d9459 = h.procs(9459)
        val expectedSm = d9459.find("gProcScr_SaveMenuPostChapter")!!
        val expectedSd = d9459.find("ProcScr_savedraw")!!
        val expectedSc = d9459.find("gProcScr_SaveDrawCursor")!!
        val expectedAp = d9459.find("ProcScr_ApProc")!!

        assertEquals(expectedSm.scrCur, menuProc.scrCur, "SaveMenuPostChapter scrCur at f9459")

        val sdProc = menuState.savedraw
        assertNotNull(sdProc, "SaveMenuState.savedraw populated")
        sdProc!!
        assertSame(menuProc, sdProc.parent, "savedraw parent")
        assertEquals(expectedSd.scrCur, sdProc.scrCur, "savedraw scrCur at f9459")
        assertEquals(expectedSd.u8(PROC_MARK), sdProc.mark, "savedraw PROC_MARK_SAVEDRAW")
        assertNotNull(sdProc.endCb, "savedraw endCb set")
        assertNotNull(sdProc.idleCb, "savedraw idleCb set")

        val sdState = sdProc.state as SaveDrawProc
        assertEquals(expectedSd.u8(SD_UNK29), sdState.unk29, "sd.unk29")
        assertEquals(expectedSd.u16(SD_UNK2C), sdState.unk2c, "sd.unk2c")
        assertEquals(expectedSd.u16(SD_UNK2E), sdState.unk2e, "sd.unk2e")
        assertEquals(expectedSd.u16(SD_UNK30), sdState.unk30, "sd.unk30")
        assertEquals(expectedSd.u8(SD_UNK32), sdState.unk32, "sd.unk32")
        assertEquals(expectedSd.u8(SD_UNK39), sdState.unk39, "sd.unk39")
        assertEquals(expectedSd.u8(SD_UNK3A), sdState.unk3a, "sd.unk3a")
        assertEquals(expectedSd.u8(SD_UNK3B), sdState.unk3b, "sd.unk3b")
        assertEquals(expectedSd.u8(SD_UNK3C), sdState.unk3c, "sd.unk3c")
        assertEquals(expectedSd.u16(SD_BG_X), sdState.fog.bgX, "sd.bg_x")
        assertEquals(expectedSd.u16(SD_BG_Y), sdState.fog.bgY, "sd.bg_y")

        val scProc = sdState.unk34
        assertNotNull(scProc, "savedraw unk_34 cursor proc started")
        assertSame(sdProc, scProc!!.parent, "cursor parent is savedraw")
        assertEquals(expectedSc.scrCur, scProc.scrCur, "cursor scrCur at f9459")
        assertNotNull(scProc.idleCb, "cursor idleCb set")

        val apProc = menuState.approc
        assertNotNull(apProc, "SaveMenuState.approc started")
        apProc!!
        assertEquals(3, apProc.rootIndex, "approc on tree 3")
        assertEquals(expectedAp.scrCur, apProc.scrCur, "approc scrCur at f9459")
        val apState = apProc.state as ApProcs.State
        assertEquals(expectedAp.u32(AP_X), apState.x, "approc x")
        assertEquals(expectedAp.u32(AP_Y), apState.y, "approc y")
        assertApHandleMatches(iw9459, 0, apState)

        // Compare BgVerticalScroll and HBlank state against r09459.
        val expectedScroll = BgVerticalScroll().apply { readFrom(ew9459, ADDR_BG_VSCROLL - EWRAM) }
        assertEquals(expectedScroll.src.toList(), h.scroll.src.toList(), "scroll src at r09459")
        assertEquals(expectedScroll.dst.toList(), h.scroll.dst.toList(), "scroll dst at r09459")
        assertEquals(expectedScroll.mode.toList(), h.scroll.mode.toList(), "scroll mode at r09459")
        assertEquals(expectedScroll.frameFlip, h.scroll.frameFlip, "scroll frameFlip at r09459")
        assertEquals(expectedScroll.frame, h.scroll.frame, "scroll frame at r09459")
        assertEquals(expectedScroll.updating, h.scroll.updating, "scroll updating at r09459")
        assertEquals(
            expectedScroll.scrollEnabled,
            h.scroll.scrollEnabled,
            "scroll scrollEnabled at r09459",
        )
        assertEquals(expectedScroll.buf.toList(), h.scroll.buf.toList(), "scroll buf at r09459")

        assertEquals(
            ADDR_HBLANK_BG_VERTICAL_SCROLL,
            u32(iw9459, ADDR_HBLANK1 - IWRAM),
            "captured sHBlankHandler1 at r09459",
        )
        assertSame(h.scroll, h.hardware.hblank.primary, "primary HBlank handler")
        assertSame(h.scroll, h.hardware.hblank.active, "active HBlank handler")
        assertTrue(h.hardware.hblank.irqEnabled, "REG_IE HBlank bit enabled")

        val expectedLcd = LcdControl().apply { readFrom(iw9459, ADDR_LCD_CONTROL - IWRAM) }
        assertEquals(expectedLcd.dispstat, h.hardware.lcd.dispstat, "LCD DISPSTAT at r09459")
        assertEquals(expectedLcd.bgHofs[2], h.hardware.lcd.bgHofs[2], "BG2HOFS at r09459")
        assertEquals(expectedLcd.bgVofs[2], h.hardware.lcd.bgVofs[2], "BG2VOFS at r09459")

        // Compare all 20 particles of gUnknown_020007E0 against r09459 (including unk_0c/d).
        val expectedParticles = Array(SaveDrawLifecycle.PARTICLE_COUNT) { SaveDrawParticle() }
        readParticles(ew9459, expectedParticles)
        assertEquals(
            expectedParticles.map { particleFields(it) },
            h.lifecycle.particles.map { particleFields(it) },
            "gUnknown_020007E0 at r09459",
        )
    }

    @Test
    fun `mid-run fog scroll, BG2 offsets, and HBlank BG2HOFS match PC-cap1 f9460 to f9862`() {
        val h = createHarness()
        val ew9459 = h.run.region("r09459", "ewram")
        val iw9459 = h.run.region("r09459", "iwram")

        val gameControl = h.engine.start(ProcScript("gProcScr_GameControl", listOf(block())), 3)
        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!
        menuProc.idleCb = null
        menuProc.scrCur = 7 // past SaveMenu_InitScreen, parked until exit
        val menuState = menuProc.state as SaveMenuState
        menuState.susSlot = 0
        menuState.susSlotCur = 0
        menuState.mainSelBitfile = PostSaveMenu.MAIN_MENU_OPTION_INVALID

        h.hardware.lcd.readFrom(iw9459, ADDR_LCD_CONTROL - IWRAM)
        h.scroll.readFrom(ew9459, ADDR_BG_VSCROLL - EWRAM)
        val sdProc = h.lifecycle.startSaveDraw(menuProc)
        val sdState = sdProc.state as SaveDrawProc
        // Align to post-r09459 state (SaveDraw_Init + first SaveDraw_Loop_Main already ran).
        h.scroll.readFrom(ew9459, ADDR_BG_VSCROLL - EWRAM)
        h.hardware.lcd.readFrom(iw9459, ADDR_LCD_CONTROL - IWRAM)
        val capturedSd9459 = h.procs(9459).find("ProcScr_savedraw")!!
        sdState.fog.bgX = capturedSd9459.u16(SD_BG_X)
        sdState.fog.bgY = capturedSd9459.u16(SD_BG_Y)

        for (step in 9460..9862) {
            if (step !in SKIPPED_LOOP_STEPS) {
                h.layers.clear()
                h.engine.run(h.engine.root(3))
            }
            if (step in setOf(9494, 9600, 9862)) {
                val prefix = if (step == 9862) "r" else "f"
                val ew = h.run.region("%s%05d".format(prefix, step), "ewram")
                val iw = h.run.region("%s%05d".format(prefix, step), "iwram")
                val sdDump = h.procs(step).find("ProcScr_savedraw")!!
                val expectedLcd = LcdControl().apply { readFrom(iw, ADDR_LCD_CONTROL - IWRAM) }
                val expectedScroll = BgVerticalScroll().apply {
                    readFrom(ew, ADDR_BG_VSCROLL - EWRAM)
                }

                assertEquals(sdDump.u16(SD_BG_X), sdState.fog.bgX, "bg_x at $prefix$step")
                assertEquals(sdDump.u16(SD_BG_Y), sdState.fog.bgY, "bg_y at $prefix$step")
                assertEquals(
                    expectedLcd.bgHofs[2],
                    h.hardware.lcd.bgHofs[2],
                    "BG2HOFS $prefix$step",
                )
                assertEquals(
                    expectedLcd.bgVofs[2],
                    h.hardware.lcd.bgVofs[2],
                    "BG2VOFS $prefix$step",
                )
                assertEquals(expectedScroll.frameFlip, h.scroll.frameFlip, "frameFlip $prefix$step")
                assertEquals(expectedScroll.buf.toList(), h.scroll.buf.toList(), "buf $prefix$step")

                if (step in setOf(9494, 9600)) {
                    val io = h.run.region("f%05d".format(step), "io")
                    val renderState = RenderState()
                    renderState.layers[2].enabled = true
                    renderState.layers[2].scrollX = -1 // ensure every line records its HBlank write
                    // Dumped RAM is one scroll ahead of the scanned frame (as in FrameCompareTest):
                    // scanlines 0..159 of f$step read half (frameFlip xor 1), before line 159
                    // HBlank latched expectedScroll.frame for the next frame.
                    val drawnHalf = expectedScroll.frameFlip xor 1
                    val replay = BgVerticalScroll().apply {
                        readFrom(ew, ADDR_BG_VSCROLL - EWRAM)
                        frameFlip = drawnHalf
                    }
                    HBlankLines.capture(io, renderState) { v, regs -> replay.onHBlank(v, regs) }
                    val activeBase = drawnHalf * 480
                    for (line in 0 until 160) {
                        assertEquals(
                            LineTable.BG_SCROLL shl 2,
                            renderState.lines.mask[line] and (LineTable.BG_SCROLL shl 2),
                            "line $line mask at f$step",
                        )
                        assertEquals(
                            expectedScroll.buf[activeBase + line].toInt() and 0x1FF,
                            renderState.lines.scrollX[2 * RenderState.LINES + line],
                            "line $line BG2HOFS at f$step",
                        )
                    }
                    val ioLine159 = io.copyOf()
                    replay.onHBlank(158, ioLine159)
                    assertEquals(
                        u16(io, 0x18),
                        u16(ioLine159, 0x18),
                        "line 159 IO.BG2HOFS at f$step",
                    )
                }
            }
        }
    }

    @Test
    @VerifiesPort("SaveDraw_OnEnd", "EndBgVerticalScroll")
    fun `exit at f9862 to f9863 ends savedraw, cursor, and HBlank scroll`() {
        val h = createHarness()
        val ew9862 = h.run.region("r09862", "ewram")
        val iw9862 = h.run.region("r09862", "iwram")
        val ew9863 = h.run.region("f09863", "ewram")
        val iw9863 = h.run.region("f09863", "iwram")
        val io9863 = h.run.region("f09863", "io")

        val gc9862 = h.procs(9862).find("gProcScr_GameControl")!!
        val sm9862 = h.procs(9862).find("gProcScr_SaveMenuPostChapter")!!
        val gc9863 = h.procs(9863).find("gProcScr_GameControl")!!

        val gameControl = h.engine.start(
            ProcScript("gProcScr_GameControl", listOf(yield(), end())),
            3,
        )
        gameControl.idleCb = null
        val gcNextAction = gc9862.u8(GC_NEXT_ACTION)
        var recordedAction = gcNextAction

        val postSaveMenu = PostSaveMenu(
            h.playSt,
            h.saveLib,
            setNextGameActionId = { recordedAction = it },
        )
        h.menu.wireSaveDraw(h.lifecycle, postSaveMenu)

        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!
        menuProc.idleCb = null
        menuProc.scrCur = sm9862.scrCur // index 32: PROC_YIELD before PostSaveMenuHandler
        val menuState = menuProc.state as SaveMenuState
        menuState.susSlot = sm9862.u8(SM_SUS_SLOT)
        menuState.susSlotCur = sm9862.u8(SM_SUS_SLOT_CUR)
        menuState.mainSelBitfile = sm9862.u16(SM_MAIN_SEL_BITFILE)

        h.hardware.lcd.readFrom(iw9862, ADDR_LCD_CONTROL - IWRAM)
        val sdProc = h.lifecycle.startSaveDraw(menuProc)
        menuState.savedraw = sdProc
        // Seed the live scroll table and HBlank state from r09862 (320 non-zero entries).
        h.scroll.readFrom(ew9862, ADDR_BG_VSCROLL - EWRAM)
        assertTrue(h.scroll.buf.any { it.toInt() != 0 }, "r09862 scroll buf is non-zero")
        assertTrue(h.scroll.scrollEnabled, "r09862 scrollEnabled is true")
        h.hardware.hblank.setPrimary(h.scroll)

        // Advance one frame (f9862 -> f9863): PostSaveMenuHandler runs, ends savedraw ->
        // SaveDraw_OnEnd -> EndBgVerticalScroll, clears HBlank, and unlocks GameControl.
        h.engine.run(h.engine.root(3))

        assertNull(h.engine.find(h.menu.script), "SaveMenuPostChapter ended at f9863")
        assertNull(h.engine.find(h.lifecycle.script), "ProcScr_savedraw ended at f9863")
        assertNull(h.engine.find(h.cursor.script), "gProcScr_SaveDrawCursor ended at f9863")
        assertEquals(gc9863.u8(PROC_LOCK_CNT), gameControl.lockCnt, "GameControl unlocked")
        assertEquals(gc9863.u8(GC_NEXT_ACTION), recordedAction, "nextAction untouched")

        assertEquals(0, u32(iw9863, ADDR_HBLANK1 - IWRAM), "captured sHBlankHandler1 == 0")
        assertNull(h.hardware.hblank.primary, "primary HBlank handler cleared")
        assertEquals(
            u16(io9863, 0x200) and 2 != 0,
            h.hardware.hblank.irqEnabled,
            "REG_IE HBlank bit cleared at f9863",
        )
        val expectedLcd9863 = LcdControl().apply { readFrom(iw9863, ADDR_LCD_CONTROL - IWRAM) }
        assertEquals(expectedLcd9863.dispstat, h.hardware.lcd.dispstat, "LCD DISPSTAT at f9863")

        val expectedScroll9863 = BgVerticalScroll().apply {
            readFrom(ew9863, ADDR_BG_VSCROLL - EWRAM)
        }
        assertEquals(expectedScroll9863.scrollEnabled, h.scroll.scrollEnabled, "scroll_en at f9863")
        assertEquals(expectedScroll9863.buf.toList(), h.scroll.buf.toList(), "scroll buf zeroed")
        assertEquals(expectedScroll9863.src.toList(), h.scroll.src.toList(), "src preserved")
        assertEquals(expectedScroll9863.dst.toList(), h.scroll.dst.toList(), "dst preserved")
        assertEquals(expectedScroll9863.frameFlip, h.scroll.frameFlip, "frameFlip preserved")
        assertEquals(expectedScroll9863.frame, h.scroll.frame, "frame preserved")
        assertEquals(expectedScroll9863.updating, h.scroll.updating, "updating preserved")

        // Also verify endMenuProcs deletes a non-null approc if SaveMenu exits while it is live.
        val parent2 = h.engine.start(ProcScript("parent2", listOf(yield(), end())), 3)
        val menuProc2 = h.menu.make6cSaveMenuPostChapter(parent2)!!
        val state2 = menuProc2.state as SaveMenuState
        state2.susSlotCur = 0
        state2.savedraw = h.lifecycle.startSaveDraw(menuProc2)
        val liveAp = state2.approc
        assertNotNull(liveAp, "approc created when susSlotCur == 0")
        h.menu.endMenuProcs(menuProc2)
        assertNull(liveAp!!.script, "APProc ended by endMenuProcs")
        assertNull(state2.savedraw!!.script, "savedraw ended by endMenuProcs")
    }

    private fun assertApHandleMatches(iwram: ByteArray, index: Int, apState: ApProcs.State) {
        val handle = apState.handle
        assertNotNull(handle, "ApHandle allocated")
        handle!!
        val base = (ADDR_S_AP_ARRAY - IWRAM) + index * AP_HANDLE_SIZE
        assertEquals(u32(iwram, base + 0x00), handle.pDefinition, "ap.pDefinition")
        assertEquals(u32(iwram, base + 0x04), handle.pFrameData, "ap.pFrameData")
        assertEquals(u32(iwram, base + 0x08), handle.pAnimDataStart, "ap.pAnimDataStart")
        assertEquals(u32(iwram, base + 0x0C), handle.pAnimDataCurrent, "ap.pAnimDataCurrent")
        assertEquals(u32(iwram, base + 0x10), handle.pCurrentObjData, "ap.pCurrentObjData")
        assertEquals(u32(iwram, base + 0x14), handle.pCurrentRotScale, "ap.pCurrentRotScale")
        assertEquals(s16(iwram, base + 0x18), handle.frameTimer, "ap.frameTimer")
        assertEquals(u16(iwram, base + 0x1A), handle.frameInterval, "ap.frameInterval")
        assertEquals(u16(iwram, base + 0x1C), handle.subframeTimer, "ap.subframeTimer")
        assertEquals(u16(iwram, base + 0x1E), handle.objLayer, "ap.objLayer")
        assertEquals(u8(iwram, base + 0x20) != 0, handle.gfxNeedsUpdate, "ap.gfxNeedsUpdate")
        assertEquals(u8(iwram, base + 0x21), handle.rotScaleIndex, "ap.rotScaleIndex")
        assertEquals(u16(iwram, base + 0x22), handle.tileBase, "ap.tileBase")
        assertEquals(u32(iwram, base + 0x24), handle.pGraphics, "ap.pGraphics")
    }

    private fun readParticles(ewram: ByteArray, dst: Array<SaveDrawParticle>) {
        val base = ADDR_G_UNKNOWN_020007E0 - EWRAM
        for (i in dst.indices) {
            val o = base + i * PARTICLE_SIZE
            dst[i].unk00 = u32(ewram, o + 0x00)
            dst[i].unk04 = u32(ewram, o + 0x04)
            dst[i].unk08 = s16(ewram, o + 0x08)
            dst[i].unk0a = s16(ewram, o + 0x0A)
            dst[i].unk0c = ewram[o + 0x0C].toInt()
            dst[i].unk0d = ewram[o + 0x0D].toInt()
            dst[i].unk0e = ewram[o + 0x0E].toInt()
        }
    }

    private fun particleFields(p: SaveDrawParticle): List<Int> =
        listOf(p.unk00, p.unk04, p.unk08, p.unk0a, p.unk0c, p.unk0d, p.unk0e)

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = GbaMem.EWRAM
        const val IWRAM = GbaMem.IWRAM

        const val ADDR_BG_VSCROLL = 0x02000000
        const val ADDR_G_UNKNOWN_020007E0 = 0x020007E0
        const val ADDR_S_AP_ARRAY = 0x03000108
        const val ADDR_LCD_CONTROL = 0x03003080
        const val ADDR_HBLANK1 = 0x03003134
        const val ADDR_HBLANK_BG_VERTICAL_SCROLL = 0x08014CC5

        const val AP_HANDLE_SIZE = 0x28
        const val PARTICLE_SIZE = 0x10

        const val PROC_MARK = 0x26
        const val PROC_LOCK_CNT = 0x28
        const val GC_NEXT_ACTION = 0x29

        const val SM_SUS_SLOT = 0x2C
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_MAIN_SEL_BITFILE = 0x42
        const val SM_UNK_44 = 0x44

        const val SD_UNK29 = 0x29
        const val SD_UNK2C = 0x2C
        const val SD_UNK2E = 0x2E
        const val SD_UNK30 = 0x30
        const val SD_UNK32 = 0x32
        const val SD_UNK39 = 0x39
        const val SD_UNK3A = 0x3A
        const val SD_UNK3B = 0x3B
        const val SD_UNK3C = 0x3C
        const val SD_BG_X = 0x3E
        const val SD_BG_Y = 0x40

        const val AP_X = 0x54
        const val AP_Y = 0x58

        val SKIPPED_LOOP_STEPS = setOf(9460, 9800, 9801, 9813, 9814, 9839, 9840, 9841, 9842, 9843)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
