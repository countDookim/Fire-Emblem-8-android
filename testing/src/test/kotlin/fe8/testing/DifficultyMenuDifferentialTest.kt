package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.savemenu.DifficultyMenu
import fe8.game.savemenu.DifficultyMenuSpritesState
import fe8.game.savemenu.DifficultyMenuState
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcFunc
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * (No capture data is held in fields: each test builds its own rig and reads dumps on demand.)
 * `PC-9a` against `ram_trace_newgame` (steps 981..1089): the lifecycle of
 * `ProcScr_NewGameDifficultySelect` and the screen it builds. The capture has no per-step proc
 * watch, so the proc is decoded from the EWRAM dumps (`ProcArrayDump.ofEwram`). Frame lag makes
 * the init step boundaries unusable (the init spans steps 982..984), so the screen is compared
 * where it is settled: step 985 (before the fade-in), f1020 (video regions) and step 1088 (end).
 * The key handler (`PC-9b`, confirm at step 1060) and the sprite child (`PC-9c`) are stand-ins.
 */
class DifficultyMenuDifferentialTest {
    private class Rig(val run: ReferenceData.Run, val romBytes: ByteArray) {
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val playSt = PlaySt()
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
        val menu = DifficultyMenu(engine, hardware, rom, playSt, text, messages, fade)
        val names = ProcArrayDump.romNames()
        var frame = 0
        var loopStart = -1
        var confirmAt = -1
        lateinit var parent: Proc
        var spritesStarts = 0

        init {
            menu.hooks.keyHandler = ProcFunc { proc ->
                // PC-9b stand-in: confirm (Proc_Goto(proc, 1)) at the captured frame.
                if (loopStart >= 0 && frame == confirmAt) engine.goto(proc, 1)
            }
            menu.hooks.startSprites = { p ->
                spritesStarts++
                engine.start(
                    ProcScript("DrawDifficultyMenuSprites stand-in", listOf(block())) {
                        DifficultyMenuSpritesState()
                    },
                    p,
                )
            }
        }

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
            text.resetText()
            val saveMenu = ProcScript("ProcScr_SaveMenu", listOf(block())) { SaveMenuState() }
            parent = engine.start(saveMenu, 3)
            val state = parent.state as SaveMenuState
            val savedraw = engine.start(
                ProcScript("ProcScr_savedraw", listOf(block())) { SaveDrawProc() },
                parent,
            )
            (savedraw.state as SaveDrawProc).unk29 = ewram(step)[0x24E68 + 3 * 0x6c + 0x29].toInt()
            state.savedraw = savedraw
        }

        fun runFrame() {
            engine.run(engine.root(3))
            engine.run(engine.root(4))
            frame++
        }
    }

    private fun rig(): Rig {
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("r00981"), "$RUN not present")
        return Rig(run, romFile.readBytes())
    }

    /** The capture's difficulty proc cursor at [step]: its script position, or -1 when gone. */
    private fun capCursor(r: Rig, step: Int): Int {
        val d = ProcArrayDump.ofEwram(r.ewram(step), r.names)
        return d.find("ProcScr_NewGameDifficultySelect")?.scrCur ?: -1
    }

    private fun capExists(r: Rig, step: Int, name: String): Boolean =
        ProcArrayDump.ofEwram(r.ewram(step), r.names).find(name) != null

    @Test
    @Timeout(120)
    @VerifiesPort(
        "DifficultySelect_Init",
        "DifficultySelect_OnEnd",
        "InitDifficultySelectScreen",
        "DrawDifficultyModeText",
        "sub_80AC034",
    )
    fun `lifecycle, settled screen and end match steps 981 to 1089`() {
        val r = rig()
        r.seed(981, "f00962")
        r.confirmAt = Int.MAX_VALUE
        val proc = r.menu.newNewGameDifficultySelect(r.parent)
        assertNotNull(proc)
        val state = proc!!.state as DifficultyMenuState

        // Settled init: run until the sleep after the init call (script index 5, step 984).
        val cursors = ArrayList<Int>()
        val fadeIn = ArrayList<Boolean>()
        val fadeOut = ArrayList<Boolean>()
        var checked985 = false
        var frames = 0
        while (frames++ < 400) {
            r.runFrame()
            val cur = if (proc.script == null) -1 else proc.scrCur
            cursors += cur
            fadeIn += r.fade.exists()
            fadeOut += r.fade.fadeOutExists()
            if (cur == 5 && !checked985) {
                checked985 = true
                compareSettled(r, state, proc)
            }
            if (cur == 10 && r.loopStart < 0) {
                r.loopStart = r.frame
                // Capture: cursor 10 first at step 1003, confirm handled in the frame of 1060.
                // The handler runs before the frame counter is incremented, hence the - 1.
                r.confirmAt = r.frame + (1060 - 1003) - 1
            }
            if (cur == -1) break
        }
        assertTrue(checked985, "reached the settled init point")
        assertTrue(cursors.last() == -1, "proc ended")

        // Observed cursor path from the fade-in on, against the dumps from step 986.
        val capPath = (986..1088).map { capCursor(r, it) }
        val first7 = cursors.indexOf(7)
        val portPath = cursors.subList(first7, cursors.size)
        assertEquals(capPath, portPath, "cursor per step from the fade-in (steps 986..1088)")
        val capFadeIn = (986..1088).map { capExists(r, it, "ProcScr_BmFadeIN") }
        assertEquals(capFadeIn, fadeIn.subList(first7, fadeIn.size), "fade-in proc per step")
        val capFadeOut = (986..1088).map { capExists(r, it, "ProcScr_BmFadeOUT") }
        assertEquals(capFadeOut, fadeOut.subList(first7, fadeOut.size), "fade-out proc per step")

        // OnEnd: savedraw.unk_29 is 1 again (step 1088), the parent is unblocked.
        val saveMenu = r.parent.state as SaveMenuState
        assertEquals(1, (saveMenu.savedraw!!.state as SaveDrawProc).unk29)
        assertEquals(
            r.ewram(1088)[0x24E68 + 3 * 0x6c + 0x29].toInt(),
            (saveMenu.savedraw!!.state as SaveDrawProc).unk29,
            "unk_29 at step 1088",
        )
        assertEquals(0, r.parent.lockCnt, "parent unblocked")
    }

    private fun compareSettled(r: Rig, state: DifficultyMenuState, proc: Proc) {
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
    @Timeout(120)
    fun `video at f1020 equals the capture`() {
        val r = rig()
        r.seed(981, "f00962")
        r.confirmAt = Int.MAX_VALUE
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
        const val GPLAYST = 0x0202BCF0
        const val GLCD_CONTROL = 0x03003080
        const val PAL_ANIMATED = 337
        const val DISP_ENABLE_MASK = 0x1F00

        fun dump(step: Int): String =
            (if (step in intArrayOf(962, 1020, 1100, 1225)) "f%05d" else "r%05d").format(step)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
