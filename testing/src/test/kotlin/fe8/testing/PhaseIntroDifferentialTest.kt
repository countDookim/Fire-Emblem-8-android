package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PhaseIntro
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The phase intro banner (D-12, src/phasechangefx.c) against `ram_trace_ch6_phase_start`: the
 * player phase banner of chapter 6, dump frames 1683..1753.
 *
 * Alignment: the dump of frame N is the state after the main loop pass of frame N, except f1683:
 * the pass that starts the intro overruns the frame and the dump caught it inside `InitGraphics`
 * (intro at command 2, BG1 still empty), so f1683 is not compared. The data is seeded from f1682,
 * the intro is started as a child of a blocked root (that start is the f1683 pass: it runs the
 * script to `WaitForEnd` and starts the children), and each of f1684..f1753 runs the proc trees
 * once and is compared with the dump of the same frame; nothing is re-seeded in between.
 * Each frame compares BG0 and BG1 tilemap buffers, gBmSt.altBlend*, the
 * live children with their `timer` / `stat_index`, BG0 scroll and the WIN0 box; the
 * DISPCNT, WINCNT, BLDCNT and blend coefficients of `gLCDControlBuffer`; each full dump (every
 * 10th frame) also BG VRAM, the two palette banks and (from f1700) the OAM affine parameters of
 * the banner sprite, which are synced in VBlank so the dump holds the previous pass's values.
 */
class PhaseIntroDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "PhaseIntro_EndIfNoUnits", "PhaseIntroText_InLoop", "PhaseIntroText_OutLoop",
        "PhaseIntroClearText", "PhaseIntroSquares_Init", "PhaseIntroSquares_InLoop",
        "PhaseIntroSquares_OutLoop", "PhaseIntroBlendBox_Init", "PhaseIntroBlendBox_InLoop",
        "PhaseIntroBlendBox_OutLoop", "PhaseIntroUnk_Loop", "PhaseIntroUnk_Init",
        "PhaseIntroText_PutText", "PhaseIntro_InitGraphics", "PhaseIntro_InitDisp",
        "PhaseIntro_WaitForEnd",
    )
    fun `ch6 player phase banner matches every frame`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim("ram_trace_ch6_phase_start")
        sim.start()
        for (f in FIRST + 1..LAST) {
            sim.prevOam = sim.oam.buffer.copyOf()
            sim.runRoots()
            sim.compare(f)
        }
        assertEquals(LAST - FIRST, sim.compared, "frames compared")
        assertEquals(7, sim.fullDumps, "full dumps compared")
        assertTrue(sim.sawSprite, "sprite affine compared")
    }

    private inner class Sim(val run: String) {
        val data = ref.run(run)
        val names = data.dumps.entries.associate { it.value to it.key }
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val rom = RomView.ofRom(romFile.readBytes())
        val units = UnitTable(rom)
        val hw = BgHardware()
        val oam = OamBuffer()
        val sprites = SpriteLayers()
        val intro = PhaseIntro(engine, bm, playSt, units, rom, hw, oam, sprites)
        val root: Proc = engine.start(
            ProcScript("test root", listOf(ProcScripts.block(), ProcScripts.end())),
            0,
        )
        var compared = 0
        var fullDumps = 0
        var sawSprite = false
        var prevOam = ShortArray(0)

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun start() {
            val e = ewram(FIRST - 1)
            bm.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            units.loadFrom(e)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            hw.lcd.readFrom(iwram(FIRST - 1), LCD_BUFFER - IWRAM)
            intro.start(root)
        }

        fun runRoots() {
            for (t in 0 until 8) engine.run(engine.root(t))
        }

        fun compare(f: Int) {
            val e = ewram(f)
            val at = "f$f"
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "$at BG$bg tilemap[$i]")
                }
            }
            val want = BmSt().also { it.decode(e, BM_ST - EWRAM) }
            assertEquals(want.altBlendACa, bm.altBlendACa, "$at altBlendACa")
            assertEquals(want.altBlendACb, bm.altBlendACb, "$at altBlendACb")
            assertEquals(want.altBlendBCa, bm.altBlendBCa, "$at altBlendBCa")
            assertEquals(want.altBlendBCb, bm.altBlendBCb, "$at altBlendBCb")

            val procs = ProcArrayDump.ofEwram(e)
            val rootProc = procs.find(INTRO)
            val gotRoot = engine.find(intro.script)
            assertEquals(rootProc == null, gotRoot == null, "$at intro live")
            if (rootProc != null) assertEquals(rootProc.scrCur, gotRoot!!.scrCur, "$at intro cmd")
            for ((addr, script) in listOf(
                UNK to intro.unkScript, TEXT to intro.textScript,
                SQUARES to intro.squaresScript, BLEND_BOX to intro.blendBoxScript,
            )) {
                val w = procs.find(addr)
                val g = engine.find(script)
                assertEquals(w == null, g == null, "$at ${script.name} live")
                if (w != null && g != null) {
                    assertEquals(w.scrCur, g.scrCur, "$at ${script.name} cmd")
                    val s = g.state as PhaseIntro.SubState
                    assertEquals(w.u16(0x4C).toShort().toInt(), s.timer, "$at ${script.name} timer")
                    assertEquals(w.u16(0x4E).toShort().toInt(), s.statIndex,
                        "$at ${script.name} stat_index")
                }
            }

            val iw = iwram(f)
            val lcd = LCD_BUFFER - IWRAM
            assertEquals(u16(iw, lcd + 0x1C), hw.lcd.bgHofs[0], "$at BG0 x offset")
            for (i in 0 until 4) {
                assertEquals(iw[lcd + 0x2C + i].toInt() and 0xFF, hw.lcd.winH[i], "$at winH[$i]")
                assertEquals(iw[lcd + 0x30 + i].toInt() and 0xFF, hw.lcd.winV[i], "$at winV[$i]")
            }
            assertEquals(u16(iw, lcd), hw.lcd.dispcnt, "$at dispcnt")
            assertEquals(u16(iw, lcd + 0x34) or (u16(iw, lcd + 0x36) shl 16), hw.lcd.wincnt,
                "$at wincnt")
            assertEquals(u16(iw, lcd + 0x3C), hw.lcd.bldcnt, "$at bldcnt")
            assertEquals(iw[lcd + 0x44].toInt() and 0xFF, hw.lcd.blendCoeffA, "$at blendCoeffA")
            assertEquals(iw[lcd + 0x45].toInt() and 0xFF, hw.lcd.blendCoeffB, "$at blendCoeffB")
            compared++
            if (names.getValue(f).startsWith("f")) compareFull(f, names.getValue(f))
        }

        fun compareFull(f: Int, key: String) {
            val at = "f$f"
            val vram = data.region(key, "vram")
            val scratch = ByteArray(0x8000)
            val from = 0x100 * 0x20
            val to = 0x140 * 0x20 + rom.decompressInto(IMG_PLAYER, scratch, 0)
            val unkBytes = rom.decompressInto(IMG_UNK, scratch, 0)
            for (i in from until to) {
                assertEquals(vram[i], hw.vram[i], "$at VRAM +${Integer.toHexString(i)}")
            }
            for (i in 0xA00 * 0x20 until 0xA00 * 0x20 + unkBytes) {
                assertEquals(vram[i], hw.vram[i], "$at obj VRAM +${Integer.toHexString(i)}")
            }
            val pal = data.region(key, "pal")
            for (bank in intArrayOf(5, 18)) {
                for (c in 0 until 16) {
                    val i = bank * 16 + c
                    val ours = hw.palette[i].toInt() and 0xFFFF
                    assertEquals(u16(pal, 2 * i), ours, "$at pal $bank/$c")
                }
            }
            if (f >= 1700) { // OAM is synced in VBlank: the dump holds the previous pass's buffer
                val oamDump = data.region(key, "oam")
                for (k in intArrayOf(3, 7, 11, 15)) {
                    assertEquals(u16(oamDump, 2 * k), prevOam[k].toInt() and 0xFFFF,
                        "$at affine[$k]")
                }
                sawSprite = true
            }
            fullDumps++
        }
    }

    private companion object {
        const val FIRST = 1683
        const val LAST = 1753
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val BG0_BUFFER = 0x02022CA8
        const val BG1_BUFFER = 0x020234A8
        const val LCD_BUFFER = 0x03003080
        const val INTRO = 0x0859AF80
        const val UNK = 0x0859AEE8
        const val TEXT = 0x0859AEF8
        const val SQUARES = 0x0859AF40
        const val BLEND_BOX = 0x0859AF60

        const val IMG_UNK = 0x0859F020
        const val IMG_PLAYER = 0x0859F3F8

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
}
