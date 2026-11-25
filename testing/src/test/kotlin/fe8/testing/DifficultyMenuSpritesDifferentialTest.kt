package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.DifficultyMenu
import fe8.game.savemenu.DifficultyMenuSprites
import fe8.game.savemenu.DifficultyMenuSpritesState
import fe8.game.savemenu.DifficultyMenuState
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Differential test of `PC-9c` ([DifficultyMenuSprites]: `StartDrawDifficultyMenuSprites`,
 * `DrawDifficultySprite_Init`, `DrawDifficultyMenuCursorMaybe`, `sub_80AC4F8`,
 * `DrawDifficultySprites_Loop`, and `gProcScr_DrawDifficultyMenuSprites`) against
 * `ram_trace_newgame` (`f984..1088`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class DifficultyMenuSpritesDifferentialTest {
    private var cachedRun: ReferenceData.Run? = null
    private var cachedRomBytes: ByteArray? = null

    @AfterAll
    fun releaseReferenceData() {
        cachedRun = null
        cachedRomBytes = null
    }

    private fun rig(): Rig {
        var run = cachedRun
        var romBytes = cachedRomBytes
        if (run == null || romBytes == null) {
            val ref = ReferenceData.fromEnvironment()
            val romFile = File(
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
                "baserom.gba",
            )
            assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
            assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
            val loadedRun = ref.run(RUN)
            assumeTrue(loadedRun.dumps.containsKey("r00981"), "$RUN not present")
            run = loadedRun
            romBytes = romFile.readBytes()
            cachedRun = run
            cachedRomBytes = romBytes
        }
        return Rig(run, romBytes)
    }

    @Test
    @VerifiesPort("StartDrawDifficultyMenuSprites", "DrawDifficultySprites_Loop")
    fun `sprites proc state, palettes, sprite pool, and f1020 OAM match ram_trace_newgame`() {
        val r = rig()
        r.seed(981, "f00962")
        val proc = r.menu.newNewGameDifficultySelect(r.parent)!!
        val state = proc.state as DifficultyMenuState

        // f982..984 overrun InitDifficultySelectScreen; f985 is the settled first sprite frame.
        r.runFrame(syncOam = false)
        assertNotNull(state.spritesProc)
        val spritesProc = state.spritesProc!!
        assertEquals(proc, spritesProc.parent, "sprites parent at r00984/r00985")
        val sprites = spritesProc.state as DifficultyMenuSpritesState

        assertSpriteStepMatches(
            r,
            984,
            spritesProc,
            sprites,
            checkPool = true,
            checkGOam = false,
            checkAllPalettes = true,
        )
        assertSpriteStepMatches(
            r,
            985,
            spritesProc,
            sprites,
            checkPool = true,
            checkGOam = true,
            checkAllPalettes = true,
        )

        val observedBobIndices = HashSet<Int>()
        val observedCycleColors = HashSet<Int>()
        val observedSelections = HashSet<Int>()
        val observedInterpSteps = HashSet<Int>()
        var lastFinished = true

        for (step in 986..1088) {
            r.keys.update(r.run.inputs[step - 1])
            val ew = r.ewram(step)
            val isOverrun = u8(ew, G_BM_ST - EWRAM) == 0

            if (isOverrun) {
                // At f1030, VBlank fired after key handler before DrawDifficultySprites_Loop.
                spritesProc.lockCnt = 1
                r.runFrame(syncOam = lastFinished)
                spritesProc.lockCnt = 0
                assertSpriteStepMatches(
                    r,
                    step,
                    spritesProc,
                    sprites,
                    checkPool = false,
                    checkGOam = false,
                    checkAllPalettes = true,
                )
                assertEquals(0, r.layers.allocIt, "overrun f$step has no layer 4 sprites")
                assertEquals(0, r.oam.hiPut, "overrun f$step has empty gOam")
                assertTrue(
                    readLayer4FromPool(r.ewram(step), r.iwram(step)).isEmpty(),
                    "capture f$step pool empty",
                )
                assertTrue(readDifficultyGOam(r.iwram(step)).isEmpty(), "capture f$step gOam empty")
                lastFinished = false
                continue
            }

            r.runFrame(syncOam = lastFinished)
            lastFinished = true

            if (step == 1088) {
                assertNull(spritesProc.script, "sprites proc ended with parent at step 1088")
                assertNull(
                    ProcArrayDump.ofEwram(ew, r.names).find("gProcScr_DrawDifficultyMenuSprites"),
                    "capture sprites proc gone at step 1088",
                )
                continue
            }

            val unfaded = step in 1003..1070
            assertSpriteStepMatches(
                r,
                step,
                spritesProc,
                sprites,
                checkPool = true,
                checkGOam = true,
                checkAllPalettes = unfaded,
            )

            if (step == 1020) {
                r.hardware.flushBackgrounds()
                assertArrayEquals(r.run.region("f01020", "vram"), r.hardware.vram, "VRAM at f1020")
                assertHwOamMatches(r, 1020)
            }

            observedBobIndices += (sprites.unk2a ushr 3) and 7
            observedCycleColors += (sprites.unk2a and 0x3F) ushr 2
            observedSelections += sprites.currentSelection
            if (step in 1031..1034) {
                observedInterpSteps += sprites.unk3c
            }
        }

        assertEquals((0..7).toSet(), observedBobIndices, "all 8 bobbing LUT indices exercised")
        assertEquals((0..15).toSet(), observedCycleColors, "all 16 palette cycle colors exercised")
        assertEquals(setOf(0, 1), observedSelections, "selections 0 and 1 exercised")
        assertEquals((1..4).toSet(), observedInterpSteps, "cursor interp steps 1..4 exercised")
    }

    @Test
    fun `wireSprites and DrawDifficultySprite_Init clearing stale unk2a match f1020`() {
        val r = rig()
        val customSprites = DifficultyMenuSprites(r.engine, r.hardware, r.layers, r.rom)
        r.menu.wireSprites(customSprites)
        r.seed(981, "f00962")

        // Verify DrawDifficultySprite_Init clears stale slot 1 unk_2a (48 in r00983).
        val staleProc = r.engine.start(customSprites.script, r.parent)
        val staleState = staleProc.state as DifficultyMenuSpritesState
        val ew983 = r.ewram(983)
        val s983 = 0x24E68 + 1 * 0x6C
        staleState.unk2a = u8(ew983, s983 + 0x2A)
        assertEquals(48, staleState.unk2a)
        customSprites.drawDifficultySpriteInit(staleProc)
        assertEquals(0, staleState.unk2a, "DrawDifficultySprite_Init clears unk_2a")
        r.engine.end(staleProc)

        // Reset and run through f1020 via wireSprites.
        r.engine.init()
        r.seed(981, "f00962")
        val proc = r.menu.newNewGameDifficultySelect(r.parent)!!
        var lastFinished = false
        r.runFrame(syncOam = false)
        lastFinished = true
        for (step in 986..1020) {
            r.keys.update(r.run.inputs[step - 1])
            r.runFrame(syncOam = lastFinished)
            lastFinished = true
        }
        assertEquals(10, proc.scrCur)
        assertHwOamMatches(r, 1020)
        val spritesProc = (proc.state as DifficultyMenuState).spritesProc!!
        val sp = spritesProc.state as DifficultyMenuSpritesState
        assertSpriteStepMatches(
            r,
            1020,
            spritesProc,
            sp,
            checkPool = true,
            checkGOam = true,
            checkAllPalettes = true,
        )
    }

    private fun assertSpriteStepMatches(
        r: Rig,
        step: Int,
        spritesProc: Proc,
        sprites: DifficultyMenuSpritesState,
        checkPool: Boolean,
        checkGOam: Boolean,
        checkAllPalettes: Boolean,
    ) {
        val ew = r.ewram(step)
        val iw = r.iwram(step)
        val spDump = ProcArrayDump.ofEwram(ew, r.names).find("gProcScr_DrawDifficultyMenuSprites")!!

        assertEquals(spDump.scrCur, spritesProc.scrCur, "scrCur at f$step")
        val expectedFields = listOf(
            spDump.u8(0x29) and 1 != 0,
            spDump.u8(0x29) and 2 != 0,
            spDump.u8(0x2A),
            spDump.u8(0x2B),
            s16(spDump, 0x2C),
            s16(spDump, 0x2E),
            spDump.u16(0x30),
            spDump.u16(0x32),
            spDump.u16(0x34),
            spDump.u16(0x36),
            s16(spDump, 0x38),
            s16(spDump, 0x3A),
            s16(spDump, 0x3C),
        )
        val actualFields = listOf(
            sprites.flags0,
            sprites.flags1,
            sprites.unk2a,
            sprites.currentSelection,
            sprites.unk2c,
            sprites.unk2e,
            sprites.unk30,
            sprites.unk32,
            sprites.unk34,
            sprites.unk36,
            sprites.unk38,
            sprites.unk3a,
            sprites.unk3c,
        )
        assertEquals(expectedFields, actualFields, "DifficultyMenuSpritesProc fields at f$step")

        if (checkAllPalettes) {
            val bad = (0 until 512).filter { idx ->
                u16(ew, GPALETTE - EWRAM + idx * 2) != (r.hardware.palette[idx].toInt() and 0xFFFF)
            }
            assertEquals(emptyList<Int>(), bad, "gPaletteBuffer mismatches at f$step")
        } else {
            val objPalMismatches = (0x110 until 0x1B0).filter { idx ->
                u16(ew, GPALETTE - EWRAM + idx * 2) != (r.hardware.palette[idx].toInt() and 0xFFFF)
            }
            assertEquals(emptyList<Int>(), objPalMismatches, "OBJ palettes 1..10 at f$step")
        }

        if (checkPool) {
            assertEquals(
                u8(iw, ADDR_S_MODIFIED_PALETTE - IWRAM) != 0,
                r.hardware.modifiedPalette,
                "sModifiedPalette at f$step",
            )
            assertEquals(
                readLayer4FromPool(ew, iw),
                readOwnLayer4(r.layers),
                "layer 4 sSpritePool at f$step",
            )
        }
        if (checkGOam) {
            assertEquals(
                readDifficultyGOam(iw),
                readOwnGOam(r.oam),
                "gOam difficulty objects at f$step",
            )
        }
    }

    private fun assertHwOamMatches(r: Rig, step: Int) {
        val hwOam = r.run.region("f%05d".format(step), "oam")
        val dumped = (0 until DIFFICULTY_OAM_COUNT).map { i ->
            val off = i * 8
            listOf(u16(hwOam, off), u16(hwOam, off + 2), u16(hwOam, off + 4))
        }
        val own = (0 until DIFFICULTY_OAM_COUNT).map { i ->
            val off = i * 4
            listOf(
                r.oam.oam[off].toInt() and 0xFFFF,
                r.oam.oam[off + 1].toInt() and 0xFFFF,
                r.oam.oam[off + 2].toInt() and 0xFFFF,
            )
        }
        assertEquals(dumped, own, "HW OAM objects 0..19 at f$step")
    }

    private fun readOwnLayer4(layers: SpriteLayers): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        layers.pushLayerObjects(DifficultyMenuSprites.SPRITE_LAYER) { entry ->
            out += listOf(entry.oam1, entry.oam0, entry.oam2, entry.obj!!.id)
        }
        return out
    }

    private fun readLayer4FromPool(ewram: ByteArray, iwram: ByteArray): List<List<Int>> {
        val allocIt = GbaMem.u32(iwram, IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        val count = (allocIt - ADDR_S_SPRITE_POOL) / SPRITE_ENTRY_SIZE
        val poolEnd = ADDR_S_SPRITE_POOL + count * SPRITE_ENTRY_SIZE
        val head = ADDR_S_SPRITE_LAYERS + DifficultyMenuSprites.SPRITE_LAYER * SPRITE_ENTRY_SIZE
        val out = ArrayList<List<Int>>()
        var addr = u32(ewram, head - EWRAM)
        while (addr in ADDR_S_SPRITE_POOL until poolEnd) {
            val base = addr - EWRAM
            val obj = u32(ewram, base + 0x0C)
            if (obj in TRACKED_SPRITES) {
                out += listOf(
                    s16(ewram, base + 0x04),
                    s16(ewram, base + 0x06),
                    u16(ewram, base + 0x08),
                    obj,
                )
            }
            addr = u32(ewram, base + 0x00)
        }
        return out
    }

    private fun readOwnGOam(oam: OamBuffer): List<List<Int>> =
        (0 until oam.hiPut / 4).map { i ->
            val off = i * 4
            listOf(
                oam.buffer[off].toInt() and 0xFFFF,
                oam.buffer[off + 1].toInt() and 0xFFFF,
                oam.buffer[off + 2].toInt() and 0xFFFF,
            )
        }

    private fun readDifficultyGOam(iwram: ByteArray): List<List<Int>> {
        val base = ADDR_G_OAM - IWRAM
        val out = ArrayList<List<Int>>()
        for (i in 0 until DIFFICULTY_OAM_COUNT) {
            val off = base + i * 8
            val attr0 = u16(iwram, off)
            if (attr0 != OamBuffer.OFFSCREEN_Y) {
                out += listOf(attr0, u16(iwram, off + 2), u16(iwram, off + 4))
            }
        }
        return out
    }

    private class Rig(val run: ReferenceData.Run, val romBytes: ByteArray) {
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val layers = SpriteLayers()
        val oam = OamBuffer()
        val playSt = PlaySt()
        val keys = KeyStatus()
        val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val host = object : TextHost {
            override fun language(): Int = 1

            override fun applyTextPalette(talk: Boolean, palid: Int) {
                val palAddr = PAL_TEXT + if (talk) 0x20 else 0
                for (i in 0 until 16) {
                    hardware.palette[palid * 16 + i] = rom.u16(palAddr + 2 * i).toShort()
                }
                hardware.palette[palid * 16] = 0
                hardware.enablePaletteSync()
            }

            override fun readVram32(addr: Int): Int {
                val o = addr - VRAM_BASE
                var v = 0
                for (k in 0..3) v = v or ((hardware.vram[o + k].toInt() and 0xFF) shl (8 * k))
                return v
            }

            override fun writeVram32(addr: Int, value: Int) {
                val o = addr - VRAM_BASE
                for (k in 0..3) hardware.vram[o + k] = (value shr (8 * k)).toByte()
            }

            override fun clearVram(dest: Int, byteCount: Int) {
                hardware.vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
            }
        }
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val saveMenuScript = ProcScript("ProcScr_SaveMenu", listOf(block())) { SaveMenuState() }
        val saveMenuPostChapter = SaveMenuPostChapter(
            engine,
            hardware,
            playSt,
            BmSt(),
            GmData(ByteArray(0x8000), RngService()),
            BmSaveLib(CartSram()),
            fade,
        ).also { it.hooks.saveMenuProcScript = saveMenuScript }
        val menu = DifficultyMenu(
            engine,
            hardware,
            rom,
            playSt,
            text,
            messages,
            fade,
            keys = keys,
            layers = layers,
        ).also { m ->
            m.hooks.setDifficultyChoice = saveMenuPostChapter::saveMenuSetDifficultyChoice
        }
        val names = ProcArrayDump.romNames()
        lateinit var parent: Proc

        fun ewram(step: Int): ByteArray = run.region(dump(step), "ewram")
        fun iwram(step: Int): ByteArray = run.region(dump(step), "iwram")

        fun seed(step: Int, vramDump: String) {
            val ew = ewram(step)
            val iw = iwram(step)
            run.region(vramDump, "vram").copyInto(hardware.vram)
            for (c in 0 until 512) hardware.palette[c] = u16(ew, GPALETTE - EWRAM + c * 2).toShort()
            for (bg in 0..3) {
                val buf = hardware.mapBuffer(bg)
                val base = BG0_TILEMAP - EWRAM + bg * 0x800
                for (i in buf.indices) buf[i] = u16(ew, base + i * 2).toShort()
            }
            hardware.lcd.readFrom(iw, GLCD_CONTROL - IWRAM)
            for (bg in 0..3) hardware.setMapDataOffset(bg, 0x6000 + bg * 0x800)
            playSt.decode(ew, GPLAYST - EWRAM)
            keys.readFrom(ew, GKEYSTATUS - EWRAM)
            text.resetText()
            parent = engine.start(saveMenuScript, 3)
            val state = parent.state as SaveMenuState
            val savedraw = engine.start(
                ProcScript("ProcScr_savedraw", listOf(block())) { SaveDrawProc() },
                parent,
            )
            (savedraw.state as SaveDrawProc).unk29 = ew[0x24E68 + 3 * 0x6c + 0x29].toInt()
            state.savedraw = savedraw
        }

        fun runFrame(syncOam: Boolean) {
            if (syncOam) oam.syncHi()
            layers.clear()
            oam.clear(0, OamBuffer.OBJ_COUNT)
            oam.hiPut = 0
            hardware.modifiedPalette = false
            engine.run(engine.root(3))
            engine.run(engine.root(4))
            layers.pushLayerObjects(DifficultyMenuSprites.SPRITE_LAYER) { entry ->
                oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_newgame"
        const val EWRAM = GbaMem.EWRAM
        const val IWRAM = GbaMem.IWRAM
        const val VRAM_BASE = 0x06000000
        const val PAL_TEXT = 0x0859EF00
        const val GPALETTE = 0x020228A8
        const val BG0_TILEMAP = 0x02022CA8
        const val GKEYSTATUS = 0x02024CC0
        const val G_BM_ST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_S_SPRITE_LAYERS = 0x0202A58C
        const val ADDR_S_MODIFIED_PALETTE = 0x0300000E
        const val GLCD_CONTROL = 0x03003080
        const val ADDR_G_OAM = 0x03003140
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val SPRITE_ENTRY_SIZE = 16
        const val DIFFICULTY_OAM_COUNT = 20

        val TRACKED_SPRITES = setOf(
            DifficultyMenuSprites.SPRITE_CURSOR,
            DifficultyMenuSprites.SPRITE_SELECT_MODE_BG,
            DifficultyMenuSprites.SPRITE_SELECT_MODE_TEXT,
            DifficultyMenuSprites.SPRITE_OPTION_BG,
            0x08A20AD0,
            0x08A20AE4,
            0x08A20AF2,
        )

        fun dump(step: Int): String =
            (if (step in intArrayOf(962, 1020, 1100, 1225)) "f%05d" else "r%05d").format(step)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
        fun s16(p: ProcArrayDump.Proc, field: Int): Int = p.u16(field).toShort().toInt()
    }
}
