package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuScreen
import fe8.game.savemenu.SaveMenuState
import fe8.game.savemenu.SaveSlotData
import fe8.game.text.Fe8Text
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.text.Font
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for `PC-4a2` ([ChapterTitle.putChapterTitleBg] and [SaveMenuScreen]) against
 * `ram_trace_prologue_end` (`PC-cap1`, `f9444..9494`):
 * - `PutChapterTitleBG` and `SaveMenuPutChapterTitle`: title background (`0xAC0`, `chr_bg = 0x2C0`)
 *   and chapter title graphics for occupied slots `0..2` (`[0, 7, 6]`, `chr_str = 0x3C0`) in VRAM.
 * - `SaveMenuInitSubBoxText`: `gSaveMenuSubBoxFont`, `gSaveMenuSubBoxText`, `gActiveFont`, BG
 *   palette bank `4`, and VRAM `0x1000..0x1280`.
 * - `SaveMenuCopyPalette` and `SaveMenu_InitScreen`: full `f09444 -> r09458 / r09459 / f09494`
 *   transition covering `SaveMenuProc` fields, `BG0..3` tilemaps, all `512` `gPaletteBuffer`
 *   entries (`bank 0x11` checked against `r09458` before `SaveDraw_Loop_Main` cycles `0x111`),
 *   `gEndingTmScratchA`, OAM affine matrices `0..3`, `gLCDControlBuffer`, `ProcScr_savedraw`,
 *   and all 96 KiB of flushed VRAM at `f09494`.
 *
 * Per Rule 4, `PutChapterTitleBG` and `SaveMenuInitSubBoxText` are `Ported` (`@VerifiesPort`),
 * while `SaveMenu_InitScreen`, `SaveMenuPutChapterTitle`, and `SaveMenuCopyPalette` remain
 * `Transcribed` because of unexercised branches (`chapterIdx == 0xFF`, `count <= 0`) and setup
 * calls masked by identical `f09444` pre-state.
 */
class SaveMenuInitScreenDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(
        val run: ReferenceData.Run,
        val names: Map<Int, String>,
        val romBytes: ByteArray,
    ) {
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val scroll = BgVerticalScroll()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val playSt = PlaySt()
        val bmSt = BmSt()
        val gmData = GmData(ByteArray(0x8000), RngService())
        val saveLib = BmSaveLib(CartSram()).also { it.sramInit() }
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val chapterData = ChapterData(rom)
        val chapterTitle = ChapterTitle(
            rom,
            hardware,
            chapterData,
            getPlayChapterId = { error("not run") },
            getNextUnclearedNode = { error("not run") },
        )
        val slotData = SaveSlotData(saveLib, chapterTitle, hardware, rom)
        val chapterMapGfx = ChapterMapGfx(rom, chapterData, hardware, playSt)
        val textHost = object : TextHost {
            override fun language(): Int = 1

            override fun applyTextPalette(talk: Boolean, palid: Int) {
                val palAddr = PAL_TEXT + if (talk) 0x20 else 0
                val base = palid * 16
                for (i in 0 until 16) {
                    hardware.palette[base + i] = rom.u16(palAddr + 2 * i).toShort()
                }
                hardware.palette[base] = 0
                hardware.enablePaletteSync()
            }

            override fun readVram32(addr: Int): Int {
                val offset = addr - VRAM_BASE
                return (hardware.vram[offset].toInt() and 0xFF) or
                    ((hardware.vram[offset + 1].toInt() and 0xFF) shl 8) or
                    ((hardware.vram[offset + 2].toInt() and 0xFF) shl 16) or
                    ((hardware.vram[offset + 3].toInt() and 0xFF) shl 24)
            }

            override fun writeVram32(addr: Int, value: Int) {
                val offset = addr - VRAM_BASE
                for (k in 0..3) {
                    hardware.vram[offset + k] = (value shr (8 * k)).toByte()
                }
            }

            override fun clearVram(dest: Int, byteCount: Int) {
                val offset = dest - VRAM_BASE
                hardware.vram.fill(0, offset, offset + byteCount)
            }
        }
        val text = TextSystem(romBytes, textHost, Fe8Text.CONFIG)
        val cursor = SaveDrawCursor(engine, layers, rom)
        val apPlayer = SpriteAnimPlayer(
            { rom.u16(it) },
            layers,
            WorldMapApHost(rom, hardware, oam),
        )
        val apProcs = ApProcs(engine, apPlayer)
        val lifecycle = SaveDrawLifecycle(engine, hardware, scroll, oam, cursor, apProcs, rom)
        val menu = SaveMenuPostChapter(
            engine,
            hardware,
            playSt,
            bmSt,
            gmData,
            saveLib,
            archivedFade,
        )
        val screen = SaveMenuScreen(
            rom,
            hardware,
            oam,
            playSt,
            text,
            chapterTitle,
            slotData,
            menu::startSaveDraw,
            chapterMapGfx::loadObjUiGfx,
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
        assumeTrue(run.hasWatch("procs") && run.hasSram, "$RUN lacks procs watch / SRAM dumps")
        return Harness(run, names, romFile.readBytes())
    }

    @Test
    @VerifiesPort("PutChapterTitleBG")
    fun `putChapterTitleBg and saveMenuPutChapterTitle match PC-cap1 VRAM and ChapterTitleFxSt`() {
        val h = createHarness()
        val ew9458 = h.run.region("r09458", "ewram")
        val ew9459 = h.run.region("r09459", "ewram")
        val vr9444 = h.run.region("f09444", "vram")
        val vr9494 = h.run.region("f09494", "vram")

        vr9444.copyInto(h.hardware.vram)
        val sm9458 = h.procs(9458).find("gProcScr_SaveMenuPostChapter")!!
        val state = SaveMenuState()
        for (i in 0 until 3) {
            state.slots.chapterIdx[i] = sm9458.u8(SM_CHAPTER_IDX + i)
        }

        h.screen.saveMenuPutChapterTitle(state)

        assertEquals(
            u16(ew9458, GCHAPTER_TITLE_FX_ST - EWRAM),
            h.chapterTitle.chrBg,
            "chr_bg at r09458",
        )
        assertEquals(
            u16(ew9459, GCHAPTER_TITLE_FX_ST - EWRAM + 2),
            h.chapterTitle.chrStr,
            "chr_str at r09459",
        )
        assertArrayEquals(
            vr9494.copyOfRange(0x15800, 0x16800),
            h.hardware.vram.copyOfRange(0x15800, 0x16800),
            "PutChapterTitleBG VRAM 0x15800..0x16800",
        )
        assertArrayEquals(
            vr9494.copyOfRange(0x16800, 0x18000),
            h.hardware.vram.copyOfRange(0x16800, 0x18000),
            "PutChapterTitleGfx slots 0..2 VRAM 0x16800..0x18000",
        )
    }

    @Test
    @VerifiesPort("SaveMenuInitSubBoxText")
    fun `saveMenuInitSubBoxText matches PC-cap1 font, text, palette bank 4, and VRAM`() {
        val h = createHarness()
        val ew9444 = h.run.region("f09444", "ewram")
        val ew9458 = h.run.region("r09458", "ewram")
        val vr9444 = h.run.region("f09444", "vram")
        val vr9494 = h.run.region("f09494", "vram")

        vr9444.copyInto(h.hardware.vram)
        for (c in 0 until 512) {
            h.hardware.palette[c] = u16(ew9444, GPALETTE_BUFFER - EWRAM + c * 2).toShort()
        }
        h.text.resetText()
        assertSame(h.text.defaultFont, h.text.activeFont, "pre-init activeFont is defaultFont")

        h.screen.saveMenuInitSubBoxText()

        assertSame(h.screen.subBoxFont, h.text.activeFont, "gActiveFont switched to subBoxFont")
        val actualFont = ByteArray(Font.SIZE).also { h.screen.subBoxFont.serialize(it) }
        assertArrayEquals(
            ew9458.copyOfRange(
                GSAVEMENU_SUBBOX_FONT - EWRAM,
                GSAVEMENU_SUBBOX_FONT - EWRAM + Font.SIZE,
            ),
            actualFont,
            "gSaveMenuSubBoxFont",
        )
        val actualText = ByteArray(Text.SIZE).also { h.screen.subBoxText.serialize(it) }
        assertArrayEquals(
            ew9458.copyOfRange(
                GSAVEMENU_SUBBOX_TEXT - EWRAM,
                GSAVEMENU_SUBBOX_TEXT - EWRAM + Text.SIZE,
            ),
            actualText,
            "gSaveMenuSubBoxText",
        )
        for (c in 0 until 16) {
            val idx = 4 * 16 + c
            assertEquals(
                u16(ew9458, GPALETTE_BUFFER - EWRAM + idx * 2),
                h.hardware.palette[idx].toInt() and 0xFFFF,
                "BG palette bank 4 color $c",
            )
        }
        assertArrayEquals(
            vr9494.copyOfRange(0x1000, 0x1280),
            h.hardware.vram.copyOfRange(0x1000, 0x1280),
            "subBoxText VRAM 0x1000..0x1280",
        )
    }

    @Test
    fun `saveMenuInitScreen at f09444 reproduces r09458, r09459, and flushed f09494 VRAM`() {
        val h = createHarness()
        val ew9444 = h.run.region("f09444", "ewram")
        val iw9444 = h.run.region("f09444", "iwram")
        val vr9444 = h.run.region("f09444", "vram")
        val ew9458 = h.run.region("r09458", "ewram")
        val ew9459 = h.run.region("r09459", "ewram")
        val iw9459 = h.run.region("r09459", "iwram")
        val vr9494 = h.run.region("f09494", "vram")

        // Seed state from f09444 right after SaveMenu_SetLcdChapterIdx.
        vr9444.copyInto(h.hardware.vram)
        for (c in 0 until 512) {
            h.hardware.palette[c] = u16(ew9444, GPALETTE_BUFFER - EWRAM + c * 2).toShort()
        }
        for (bg in 0..3) {
            val tmOff = BG0_TILEMAP - EWRAM + bg * 0x800
            val buf = h.hardware.mapBuffer(bg)
            for (i in buf.indices) {
                buf[i] = u16(ew9444, tmOff + i * 2).toShort()
            }
            h.hardware.setMapDataOffset(bg, 0x6000 + bg * 0x800)
        }
        h.hardware.lcd.readFrom(iw9444, GLCD_CONTROL - IWRAM)
        for (i in h.oam.buffer.indices) {
            h.oam.buffer[i] = u16(iw9444, GOAM - IWRAM + i * 2).toShort()
        }
        h.playSt.decode(ew9444, GPLAYST - EWRAM)
        h.bmSt.decode(ew9444, GBMST - EWRAM)
        h.saveLib.sram.copyIn(h.run.sram("s09444"))
        h.text.resetText()

        val sm9444 = h.procs(9444).find("gProcScr_SaveMenuPostChapter")!!
        val gameControl = h.engine.start(ProcScript("gProcScr_GameControl", listOf(block())), 3)
        h.menu.wireScreen(h.screen, h.lifecycle)
        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!
        menuProc.idleCb = null
        menuProc.scrCur = sm9444.scrCur // index 5: call(hooks.initScreen)
        val state = menuProc.state as SaveMenuState
        state.scrollCnt = sm9444.u8(SM_SCROLL_CNT)
        state.susSlot = sm9444.u8(SM_SUS_SLOT)
        state.cursorSlot = sm9444.u8(SM_CURSOR_SLOT)
        state.jumpLabel = sm9444.u8(SM_JUMP_LABEL)
        state.unk2f = sm9444.u8(SM_UNK_2F)
        state.mainOptions = sm9444.u8(SM_MAIN_OPTIONS)
        state.cursorConfig = sm9444.u8(SM_CURSOR_CONFIG)
        state.unk3d = sm9444.u8(SM_UNK_3D)
        state.hbEn = sm9444.u8(SM_HB_EN) != 0
        state.susSlotCur = sm9444.u8(SM_SUS_SLOT_CUR)
        state.ctrlTimer = sm9444.u8(SM_CTRL_TIMER)
        state.mainSelBitfile = sm9444.u16(SM_MAIN_SEL_BITFILE)
        state.slots.unk44 = sm9444.u16(SM_UNK_44)

        // Execute SaveMenu_InitScreen (yields at scrCur = 7 and starts ProcScr_savedraw).
        h.engine.run(h.engine.root(3))

        val d9459 = h.procs(9459)
        val sm9459 = d9459.find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(sm9459.scrCur, menuProc.scrCur, "scrCur at r09459")
        assertEquals(sm9459.u8(SM_SCROLL_CNT), state.scrollCnt, "scroll_cnt")
        assertEquals(sm9459.u8(SM_CURSOR_SLOT), state.cursorSlot, "cursor_slot")
        assertEquals(sm9459.u8(SM_CURSOR_CONFIG), state.cursorConfig, "cursor_config")
        assertEquals(sm9459.u8(SM_UNK_3D), state.unk3d, "unk_3d")
        assertEquals(sm9459.u8(SM_HB_EN) != 0, state.hbEn, "hb_en")
        assertEquals(sm9459.u8(SM_SUS_SLOT_CUR), state.susSlotCur, "sus_slot_cur")
        assertEquals(sm9459.u8(SM_CTRL_TIMER), state.ctrlTimer, "ctrl_timer")
        assertEquals(sm9459.u16(SM_UNK_44), state.slots.unk44, "unk_44")
        assertEquals(
            (0 until 3).map { sm9459.u8(SM_CHAPTER_IDX + it) },
            state.slots.chapterIdx.toList(),
            "chapter_idx",
        )
        assertEquals(
            (0 until 3).map { sm9459.u8(SM_UNK_3A + it) },
            state.slots.unk3a.toList(),
            "unk_3a",
        )
        assertEquals(
            (0 until 3).map { sm9459.u32(SM_PLAYED_TIME + it * 4) },
            state.slots.playedTime.toList(),
            "played_time",
        )
        assertEquals(sm9459.u32(SM_TOTAL_TIME), state.slots.totalTime, "total_time")

        assertNotNull(state.savedraw, "savedraw started")
        assertEquals(
            d9459.find("ProcScr_savedraw")!!.scrCur,
            state.savedraw!!.scrCur,
            "savedraw scrCur",
        )
        assertNotNull(state.approc, "approc started")
        assertEquals(d9459.find("ProcScr_ApProc")!!.scrCur, state.approc!!.scrCur, "approc scrCur")
        assertNotNull(h.engine.find(h.cursor.script), "gProcScr_SaveDrawCursor started")

        // All four BG tilemaps match r09459.
        for (bg in 0..3) {
            val tmOff = BG0_TILEMAP - EWRAM + bg * 0x800
            val actual = h.hardware.mapBuffer(bg).map { it.toInt() and 0xFFFF }
            val expected = (0 until 0x400).map { u16(ew9459, tmOff + it * 2) }
            assertEquals(expected, actual, "BG$bg tilemap at r09459")
        }

        // All 512 palette colors: bank 0x11 against r09458 (before SaveDraw_Loop_Main's sub_80AA7AC
        // mutates color 0x111 at f9459), and all other colors against r09459.
        for (c in 0 until 512) {
            val src = if (c in 0x110..0x11F) ew9458 else ew9459
            assertEquals(
                u16(src, GPALETTE_BUFFER - EWRAM + c * 2),
                h.hardware.palette[c].toInt() and 0xFFFF,
                "gPaletteBuffer[$c]",
            )
        }

        // gEndingTmScratchA matches r09459.
        for (i in h.screen.endingTmScratchA.indices) {
            assertEquals(
                u16(ew9459, GENDING_TM_SCRATCH_A - EWRAM + i * 2),
                h.screen.endingTmScratchA[i].toInt() and 0xFFFF,
                "gEndingTmScratchA[$i]",
            )
        }

        // Font, text, and ChapterTitleFxSt match r09459.
        assertSame(h.screen.subBoxFont, h.text.activeFont, "gActiveFont")
        val fontBytes = ByteArray(Font.SIZE).also { h.screen.subBoxFont.serialize(it) }
        assertArrayEquals(
            ew9459.copyOfRange(
                GSAVEMENU_SUBBOX_FONT - EWRAM,
                GSAVEMENU_SUBBOX_FONT - EWRAM + Font.SIZE,
            ),
            fontBytes,
            "gSaveMenuSubBoxFont at r09459",
        )
        val textBytes = ByteArray(Text.SIZE).also { h.screen.subBoxText.serialize(it) }
        assertArrayEquals(
            ew9459.copyOfRange(
                GSAVEMENU_SUBBOX_TEXT - EWRAM,
                GSAVEMENU_SUBBOX_TEXT - EWRAM + Text.SIZE,
            ),
            textBytes,
            "gSaveMenuSubBoxText at r09459",
        )
        assertEquals(u16(ew9459, GCHAPTER_TITLE_FX_ST - EWRAM), h.chapterTitle.chrBg, "chr_bg")
        assertEquals(
            u16(ew9459, GCHAPTER_TITLE_FX_ST - EWRAM + 2),
            h.chapterTitle.chrStr,
            "chr_str",
        )

        // OAM affine matrices 0..3 match r09459 (matrix 0 changed from [284, 0, 0, 284] -> 256).
        for (m in 0 until 4) {
            assertEquals(
                oamAffine(iw9459, m),
                listOf(
                    h.oam.buffer[m * 16 + 3].toInt(),
                    h.oam.buffer[m * 16 + 7].toInt(),
                    h.oam.buffer[m * 16 + 11].toInt(),
                    h.oam.buffer[m * 16 + 15].toInt(),
                ),
                "OAM affine matrix $m",
            )
        }

        // LCD control buffer matches r09459.
        val expectedLcd = LcdControl().apply { readFrom(iw9459, GLCD_CONTROL - IWRAM) }
        assertEquals(
            lcdFields(expectedLcd),
            lcdFields(h.hardware.lcd),
            "gLCDControlBuffer at r09459",
        )

        // Flush synced BG tilemaps to VRAM and compare all 96 KiB against f09494.vram.bin.
        h.hardware.flushBackgrounds()
        assertArrayEquals(vr9494, h.hardware.vram, "full 96 KiB VRAM at f09494")
    }

    @Test
    fun `entry through InitScreen and NewFadeIn reaches PL_SAVEMENU_SAVE_SLOT_SEL at f09494`() {
        val h = createHarness()
        val ew9443 = h.run.region("r09443", "ewram")
        val iw9443 = h.run.region("r09443", "iwram")
        val vr9444 = h.run.region("f09444", "vram")
        val iw9494 = h.run.region("f09494", "iwram")
        val vr9494 = h.run.region("f09494", "vram")

        vr9444.copyInto(h.hardware.vram)
        h.hardware.lcd.readFrom(iw9443, GLCD_CONTROL - IWRAM)
        h.playSt.decode(ew9443, GPLAYST - EWRAM)
        h.bmSt.decode(ew9443, GBMST - EWRAM)
        h.saveLib.sram.copyIn(h.run.sram("s09350"))
        h.text.resetText()

        val gameControl = h.engine.start(ProcScript("gProcScr_GameControl", listOf(block())), 3)
        h.menu.wireScreen(h.screen, h.lifecycle)
        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!

        // f9444: SaveMenuInit + SaveMenu_SetLcdChapterIdx -> scrCur = 5.
        h.engine.run(h.engine.root(3))
        assertEquals(5, menuProc.scrCur)

        // InitScreen -> scrCur = 7.
        h.engine.run(h.engine.root(3))
        h.hardware.flushBackgrounds()
        assertEquals(7, menuProc.scrCur)
        assertArrayEquals(vr9494, h.hardware.vram, "VRAM after InitScreen + FlushBackgrounds")

        // DirectlySelectSlotOnPrepScreen -> NewFadeIn(4) on tree 4, 32 fade steps -> scrCur = 16.
        repeat(34) {
            h.engine.run(h.engine.root(3))
            h.engine.run(h.engine.root(4))
        }
        val sm9494 = h.procs(9494).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(sm9494.scrCur, menuProc.scrCur, "scrCur at f09494")
        assertEquals(
            u16(iw9494, GLCD_CONTROL - IWRAM),
            h.hardware.lcd.dispcnt,
            "DISPCNT at f09494",
        )
    }

    private fun oamAffine(iwram: ByteArray, matrix: Int): List<Int> = listOf(
        s16(iwram, GOAM - IWRAM + (matrix * 4 + 0) * 8 + 6),
        s16(iwram, GOAM - IWRAM + (matrix * 4 + 1) * 8 + 6),
        s16(iwram, GOAM - IWRAM + (matrix * 4 + 2) * 8 + 6),
        s16(iwram, GOAM - IWRAM + (matrix * 4 + 3) * 8 + 6),
    )

    private fun lcdFields(lcd: LcdControl): List<Any> = listOf(
        lcd.dispcnt,
        lcd.dispstat,
        lcd.bgCnt.toList(),
        lcd.bgHofs.toList(),
        lcd.bgVofs.toList(),
        lcd.winH.toList(),
        lcd.winV.toList(),
        lcd.wincnt,
        lcd.mosaic,
        lcd.bldcnt,
        lcd.blendCoeffA,
        lcd.blendCoeffB,
        lcd.blendY,
        lcd.affine.toList(),
        lcd.colorAddition,
    )

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val VRAM_BASE = 0x06000000
        const val PAL_TEXT = 0x0859EF00

        const val GENDING_TM_SCRATCH_A = 0x020007A0
        const val GSAVEMENU_SUBBOX_FONT = 0x02000920
        const val GSAVEMENU_SUBBOX_TEXT = 0x02000938
        const val GPALETTE_BUFFER = 0x020228A8
        const val BG0_TILEMAP = 0x02022CA8
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GCHAPTER_TITLE_FX_ST = 0x0203E78C
        const val GLCD_CONTROL = 0x03003080
        const val GOAM = 0x03003140

        const val SM_SCROLL_CNT = 0x29
        const val SM_SUS_SLOT = 0x2C
        const val SM_CURSOR_SLOT = 0x2D
        const val SM_JUMP_LABEL = 0x2E
        const val SM_UNK_2F = 0x2F
        const val SM_MAIN_OPTIONS = 0x30
        const val SM_CURSOR_CONFIG = 0x36
        const val SM_CHAPTER_IDX = 0x37
        const val SM_UNK_3A = 0x3A
        const val SM_UNK_3D = 0x3D
        const val SM_HB_EN = 0x3E
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_CTRL_TIMER = 0x40
        const val SM_MAIN_SEL_BITFILE = 0x42
        const val SM_UNK_44 = 0x44
        const val SM_PLAYED_TIME = 0x48
        const val SM_TOTAL_TIME = 0x54

        fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF
        fun u16(bytes: ByteArray, offset: Int): Int =
            u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
        fun s16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()
    }
}
