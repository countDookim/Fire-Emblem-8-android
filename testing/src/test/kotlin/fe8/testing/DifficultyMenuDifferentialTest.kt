package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.DifficultyMenu
import fe8.game.savemenu.DifficultyMenuSpritesState
import fe8.game.savemenu.DifficultyMenuState
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-9a` / `PC-9b` against `ram_trace_newgame` (steps 981..1089): the lifecycle of
 * `ProcScr_NewGameDifficultySelect`, screen setup, real `inputs.bin` key handling (`Down` at
 * `f1030`, `A` at `f1060`), description redraw, `sub_80AC680` cursor move seeding, sound-effect
 * hooks, and `SaveMenu_SetDifficultyChoice` on a `ProcScr_SaveMenu` stand-in parent. The sprite
 * loop (`PC-9c`) and `ProcScr_SaveMenu` (`PC-9d`) are stand-ins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class DifficultyMenuDifferentialTest {
    private var cachedRun: ReferenceData.Run? = null
    private var cachedRomBytes: ByteArray? = null

    @AfterAll
    fun releaseReferenceData() {
        cachedRun = null
        cachedRomBytes = null
    }

    private class Rig(val run: ReferenceData.Run, val romBytes: ByteArray) {
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
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
        val sounds = ArrayList<Int>()
        val menu = DifficultyMenu(
            engine,
            hardware,
            rom,
            playSt,
            text,
            messages,
            fade,
            keys = keys,
        ).also { m ->
            m.hooks.playSoundEffect = { id -> sounds += id }
            m.hooks.setDifficultyChoice = saveMenuPostChapter::saveMenuSetDifficultyChoice
            m.hooks.startSprites = { p ->
                spritesStarts++
                engine.start(
                    ProcScript("DrawDifficultyMenuSprites stand-in", listOf(block())) {
                        DifficultyMenuSpritesState()
                    },
                    p,
                )
            }
        }
        val names = ProcArrayDump.romNames()
        var frame = 0
        lateinit var parent: Proc
        var spritesStarts = 0

        fun ewram(step: Int) = run.region(dump(step), "ewram")

        fun seed(step: Int, vramDump: String) {
            val ew = ewram(step)
            val iw = run.region(dump(step), "iwram")
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

        fun runFrame() {
            engine.run(engine.root(3))
            engine.run(engine.root(4))
            frame++
        }
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

    /** The capture's difficulty proc cursor at [step]: its script position, or -1 when gone. */
    private fun capCursor(r: Rig, step: Int): Int {
        val d = ProcArrayDump.ofEwram(r.ewram(step), r.names)
        return d.find("ProcScr_NewGameDifficultySelect")?.scrCur ?: -1
    }

    private fun capExists(r: Rig, step: Int, name: String): Boolean =
        ProcArrayDump.ofEwram(r.ewram(step), r.names).find(name) != null

    @Test
    @VerifiesPort(
        "DifficultySelect_Init",
        "DifficultySelect_OnEnd",
        "InitDifficultySelectScreen",
        "DrawDifficultyModeText",
        "sub_80AC034",
        "sub_80AC680",
    )
    fun `lifecycle, settled screen, selection input and end match steps 981 to 1089`() {
        val r = rig()
        r.seed(981, "f00962")
        val proc = r.menu.newNewGameDifficultySelect(r.parent)
        assertNotNull(proc)
        val state = proc!!.state as DifficultyMenuState
        val saveMenu = r.parent.state as SaveMenuState

        // Settled init: one frame advances past yield -> init -> sleep(1) (cursor 5, step 985).
        r.runFrame()
        assertEquals(5, proc.scrCur, "settled init cursor")
        compareSettled(r, state)
        val sprites = state.spritesProc!!.state as DifficultyMenuSpritesState

        val sel0TextVram = r.run.region("f01020", "vram")
            .copyOfRange(TEXT_VRAM_START, TEXT_VRAM_END)
        val sel1TailVram = r.run.region("f01100", "vram")
            .copyOfRange(TEXT_TAIL_VRAM_START, TEXT_VRAM_END)

        val portPath = ArrayList<Int>()
        val portFadeIn = ArrayList<Boolean>()
        val portFadeOut = ArrayList<Boolean>()
        val steps = 986..1088
        for (step in steps) {
            r.keys.update(r.run.inputs[step - 1])
            r.runFrame()
            val cur = if (proc.script == null) -1 else proc.scrCur
            portPath += cur
            portFadeIn += r.fade.exists()
            portFadeOut += r.fade.fadeOutExists()

            if (step == 1020) {
                r.hardware.flushBackgrounds()
                assertEquals(0, r.hardware.modifiedBgs, "BG sync cleared at f1020")
            }

            if (step in 1003..1087) {
                val ew = r.ewram(step)
                val dump = ProcArrayDump.ofEwram(ew, r.names)
                val dmDump = dump.find("ProcScr_NewGameDifficultySelect")!!
                val spDump = dump.find("gProcScr_DrawDifficultyMenuSprites")!!
                val smDump = dump.find("ProcScr_SaveMenu")!!

                assertEquals(
                    dmDump.u8(0x30),
                    state.currentSelection,
                    "current_selection at f$step",
                )
                assertEquals(
                    spDump.u8(0x2B),
                    sprites.currentSelection,
                    "sprite selection at f$step",
                )
                assertEquals(smDump.u8(0x2A), saveMenu.difficulty, "saveMenu.difficulty at f$step")
                assertEquals(smDump.u8(0x3D), saveMenu.unk3d, "saveMenu.unk_3d at f$step")

                val expBg0 = (0 until 0x400).map { u16(ew, BG0_TILEMAP - EWRAM + it * 2) }
                val actBg0 = r.hardware.mapBuffer(0).map { it.toInt() and 0xFFFF }
                assertEquals(expBg0, actBg0, "BG0 tilemap at f$step")
            }

            if (step == 1030) {
                val spDump = ProcArrayDump.ofEwram(r.ewram(step), r.names)
                    .find("gProcScr_DrawDifficultyMenuSprites")!!
                assertEquals(spDump.u8(0x29) and 1 != 0, sprites.flags0, "sprites.flags_0 at f1030")
                assertEquals(spDump.u16(0x30), sprites.unk30, "sprites.unk_30 at f1030")
                assertEquals(spDump.u16(0x32), sprites.unk32, "sprites.unk_32 at f1030")
                assertEquals(spDump.u16(0x34), sprites.unk34, "sprites.unk_34 at f1030")
                assertEquals(spDump.u16(0x36), sprites.unk36, "sprites.unk_36 at f1030")
                assertEquals(listOf(0x66), r.sounds, "cursor sound at f1030")
                assertTrue(r.hardware.modifiedBgs and 1 != 0, "BG0 sync requested at f1030")

                val actFullTextVram = r.hardware.vram.copyOfRange(TEXT_VRAM_START, TEXT_VRAM_END)
                assertFalse(
                    sel0TextVram.contentEquals(actFullTextVram),
                    "text VRAM redrawn for selection 1 at f1030",
                )
                assertArrayEquals(
                    sel1TailVram,
                    r.hardware.vram.copyOfRange(TEXT_TAIL_VRAM_START, TEXT_VRAM_END),
                    "selection 1 text VRAM 0x1280..0x2180 matches f01100",
                )
            }

            if (step == 1060) {
                val dmDump = ProcArrayDump.ofEwram(r.ewram(step), r.names)
                    .find("ProcScr_NewGameDifficultySelect")!!
                assertEquals(listOf(0x66, 0x6A), r.sounds, "confirm sound at f1060")
                assertEquals(dmDump.u32(0x2C), state.unk2c, "unk_2c at f1060")
                assertEquals(0, dmDump.u32(0x0C), "capture idleCb cleared at f1060")
                assertNull(proc.idleCb, "port idleCb cleared at f1060")
                assertEquals(1, saveMenu.difficulty, "saveMenu.difficulty at f1060")
                assertEquals(0, saveMenu.unk3d, "saveMenu.unk_3d at f1060")
            }
        }
        assertEquals(-1, portPath.last(), "proc ended at step 1088")

        // Observed cursor path and fade lifetimes from step 986..1088.
        val capPath = steps.map { capCursor(r, it) }
        assertEquals(capPath, portPath, "cursor per step from the fade-in (steps 986..1088)")
        val capFadeIn = steps.map { capExists(r, it, "ProcScr_BmFadeIN") }
        assertEquals(capFadeIn, portFadeIn, "fade-in proc per step")
        val capFadeOut = steps.map { capExists(r, it, "ProcScr_BmFadeOUT") }
        assertEquals(capFadeOut, portFadeOut, "fade-out proc per step")

        // OnEnd: savedraw.unk_29 is 1 again (step 1088), the parent is unblocked.
        assertEquals(1, (saveMenu.savedraw!!.state as SaveDrawProc).unk29)
        assertEquals(
            r.ewram(1088)[0x24E68 + 3 * 0x6c + 0x29].toInt(),
            (saveMenu.savedraw!!.state as SaveDrawProc).unk29,
            "unk_29 at step 1088",
        )
        assertEquals(0, r.parent.lockCnt, "parent unblocked")
        assertEquals(listOf(0x66, 0x6A), r.sounds, "exact sound sequence across run")
    }

    private fun compareSettled(r: Rig, state: DifficultyMenuState) {
        val ew = r.ewram(985)
        // unk_29 cleared by init (step 983 on).
        assertEquals(
            ew[0x24E68 + 3 * 0x6c + 0x29].toInt(),
            ((r.parent.state as SaveMenuState).savedraw!!.state as SaveDrawProc).unk29,
            "unk_29 after init",
        )
        // Proc fields: unk_2c, current_selection.
        val b = 0x24E68 + 5 * 0x6c
        assertEquals(u32(ew, b + 0x2C), state.unk2c, "unk_2c")
        assertEquals(ew[b + 0x30].toInt() and 0xFF, state.currentSelection, "current_selection")
        // Sprite child fields seeded by init (slot 1 at step 985).
        val s = 0x24E68 + 1 * 0x6c
        val sprites = state.spritesProc!!.state as DifficultyMenuSpritesState
        assertEquals(ew[s + 0x2B].toInt() and 0xFF, sprites.currentSelection, "sprite selection")
        assertEquals(u16(ew, s + 0x2C).toShort().toInt(), sprites.unk2c, "sprite unk_2c")
        assertEquals(u16(ew, s + 0x2E).toShort().toInt(), sprites.unk2e, "sprite unk_2e")
        // BG tilemaps.
        for (bg in 0..3) {
            val exp = (0 until 0x400).map { u16(ew, BG0_TILEMAP - EWRAM + bg * 0x800 + it * 2) }
            val act = r.hardware.mapBuffer(bg).map { it.toInt() and 0xFFFF }
            assertEquals(exp, act, "BG$bg tilemap at step 985")
        }
        // Palettes (entry 337 is animated by the sprite/draw procs: PC-9c).
        val bad = (0 until 512).filter {
            it != PAL_ANIMATED &&
                u16(ew, GPALETTE - EWRAM + it * 2) != (r.hardware.palette[it].toInt() and 0xFFFF)
        }
        assertEquals(emptyList<Int>(), bad, "gPaletteBuffer mismatches at step 985")
        // Display disabled until EnableAllGfx.
        assertEquals(
            u16(r.run.region("r00985", "iwram"), GLCD_CONTROL - IWRAM) and DISP_ENABLE_MASK,
            r.hardware.lcd.dispcnt and DISP_ENABLE_MASK,
            "DISPCNT layers at step 985",
        )
    }

    @Test
    fun `video at f1020 equals the capture`() {
        val r = rig()
        r.seed(981, "f00962")
        val proc = r.menu.newNewGameDifficultySelect(r.parent)!!
        var frames = 0
        while (proc.scrCur != 10 && frames++ < 100) r.runFrame()
        assertEquals(10, proc.scrCur)
        r.hardware.flushBackgrounds()
        val vram = r.run.region("f01020", "vram")
        val mism = vram.indices.filter { vram[it] != r.hardware.vram[it] }
        assertEquals(
            emptyList<String>(),
            mism.take(40).map { "0x%X".format(it) + " of " + mism.size },
            "VRAM at f1020",
        )
        val pal = r.run.region("f01020", "ewram")
        val bad = (0 until 512).filter {
            it != PAL_ANIMATED &&
                u16(pal, GPALETTE - EWRAM + it * 2) != (r.hardware.palette[it].toInt() and 0xFFFF)
        }
        assertEquals(emptyList<Int>(), bad, "gPaletteBuffer at f1020")
        assertEquals(
            u16(r.run.region("f01020", "iwram"), GLCD_CONTROL - IWRAM) and DISP_ENABLE_MASK,
            r.hardware.lcd.dispcnt and DISP_ENABLE_MASK,
            "DISPCNT layers at f1020",
        )
    }

    private companion object {
        const val RUN = "ram_trace_newgame"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val VRAM_BASE = 0x06000000
        const val PAL_TEXT = 0x0859EF00
        const val GPALETTE = 0x020228A8
        const val BG0_TILEMAP = 0x02022CA8
        const val GKEYSTATUS = 0x02024CC0
        const val GPLAYST = 0x0202BCF0
        const val GLCD_CONTROL = 0x03003080
        const val PAL_ANIMATED = 337
        const val DISP_ENABLE_MASK = 0x1F00
        const val TEXT_VRAM_START = 0x1000
        const val TEXT_TAIL_VRAM_START = 0x1280
        const val TEXT_VRAM_END = 0x2180

        fun dump(step: Int): String =
            (if (step in intArrayOf(962, 1020, 1100, 1225)) "f%05d" else "r%05d").format(step)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
