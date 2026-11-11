package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterIntro
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MapAnimations
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitTable
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WorldMapPath
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.render.BgHardware
import gbafe.render.ColorFade
import gbafe.render.LcdControl
import gbafe.render.OamBuffer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The chapter intro's main path from the twin lights to the end of the light explosion
 * (src/chapterintrofx.c, src/chapter_title.c) against `ram_trace_newgame_intro` (no key presses),
 * frames 9322-9535: `ChapterIntro_InitBLDCNT` and the light burst start (9455), the chapter title
 * draw `ChapterIntro_DrawChapterTitle` (9455), the light explosion (9456-9525) and
 * `ChapterIntro_SetBG_802009C` with the start of Bg2Scroll, the decal and the fog (9526).
 *
 * Alignment is that of [ChapterIntroDifferentialTest] (seeded from the dump of 9321, step 9322 is
 * compared with the dump of 9325, then one step per frame, keys from the captured `inputs.bin`).
 * Compared every frame: the intro, KeyListen and Bg1And3Scroll procs (script position, sleep,
 * mark, lock count, repeat callback; `ChapterIntroFxProc` fields `isSkipping` and `skipTarget`,
 * the twin light fields to 9455, `timer` through the explosion to 9525), the key status, the BG0,
 * BG1 and BG3 tilemap buffers (BG2 to 9525), the palette buffer (bank 4, the fog palette, not
 * from 9526), `gFadeComponentStep`, `gFadeComponents`, from 9455 `gChapterTitleFxSt.chr_str`, and the LCD
 * buffer: all of it to 9454; at 9455 DISPCNT (the light explosion sets the display enables from
 * 9456) and the BLDCNT target bits; to 9525 BGCNT, BG scroll and window registers; from 9526 DISPCNT
 * mode and BGCNT. At the full dumps (every 10 frames) the BG and OBJ tile data that
 * `ChapterIntro_Init` decompresses are compared with VRAM, BG2's lens flare to 9520, and from
 * 9460 the 0x800 bytes of title image at tile 0x280; the BG3 scroll register comes from the I/O
 * dump (to 9454: the light burst's `FlushLCDControl` rewrites it after that).
 *
 * Limits (stand-ins, D11-ci3): the light burst proc is not run, so the blend effect bits and
 * coefficients, BG2's affine registers and `SetBlendAlpha` state are not compared from 9455; the
 * light explosion is reduced to the `timer` count and the break at 70 (no sprites, affine
 * matrices or `SetDispEnable`), so the OAM is not compared after the twin lights; Bg2Scroll,
 * the decal procs and the fog init do not run, so the intro's `timer`, BG2, the fog's palette
 * bank 4, the lens flare tile data and BG scroll registers are not compared from 9526.
 * `GetPlayChapterId` and `GetNextUnclearedNode` come from the existing world map ports
 * (`WmNodeData`, `WorldMapPath` on the captured `gGMData`).
 */
class ChapterIntroMainPathTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ChapterIntro_InitBLDCNT", "ChapterIntro_DrawChapterTitle", "sub_80895B4",
        "_PutChapterTitleGfx", "PutChapterTitleGfx", "sub_80896FC", "GetChapterTitleWM",
        "ChapterIntro_SetBG_802009C",
    )
    fun `twin lights to title draw and light explosion match every frame`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(
            File(ref.root, "ram_trace_newgame_intro/manifest.txt").isFile,
            "ram_trace_newgame_intro not present (run tools/setup.sh)",
        )
        val sim = Sim()
        sim.seed()
        sim.step(FIRST)
        sim.compare(FIRST, FIRST + 3)
        for (f in FIRST + 4..LAST) {
            sim.step(f)
            sim.compare(f, f)
        }
        assertEquals(LAST - FIRST - 2, sim.compared, "frames compared")
        assertEquals(listOf(9460, 9470, 9480, 9490, 9500, 9510, 9520, 9530), sim.titleDumps)
        assertTrue(sim.titleBytesNonZero, "title image compared")
        assertEquals(1, sim.titleDrawnAt.size, "title drawn once")
    }

    private inner class Sim {
        val data = ref.run("ram_trace_newgame_intro")
        val names = data.dumps.entries.associate { it.value to it.key }
        val procNames = ProcArrayDump.romNames()
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        val colorFade = ColorFade(hardware.palette)
        val oam = OamBuffer()
        val keys = KeyStatus()
        var compared = 0
        val titleDumps = mutableListOf<Int>()
        val titleDrawnAt = mutableListOf<Int>()
        var titleBytesNonZero = false
        val playSt = PlaySt()
        val bmSt = BmSt()
        val units = UnitTable(rom)
        val chapterData = ChapterData(rom)
        val map = BmMap(rom, chapterData, bmSt)
        val mapGfx = ChapterMapGfx(rom, chapterData, hardware, playSt)
        val unitSprites = UnitSprites(rom, hardware, bmSt)
        val mapRender = BmMapRender(bmSt, map, hardware)
        val nodes = WmNodeData(rom)
        val gmIwram = ByteArray(0x8000)
        val worldMapPath = WorldMapPath(GmData(gmIwram, RngService()), WmPathData(rom))
        val hooks = ChapterIntro.Hooks(
            newKeys = { keys.newKeys },
            bMapDispSuspend = { },
            bMapDispResume = { },
        ).also {
            it.getPlayChapterId = { chapter ->
                (0 until NODE_MAX).firstOrNull { node ->
                    nodes.chapterId(node, playSt.chapterModeIndex) == chapter
                } ?: -1
            }
            it.getNextUnclearedNode = { worldMapPath.getNextUnclearedNode() }
        }
        val host = ChapterIntro(
            engine, hardware, colorFade, oam, rom, SoundWrapper(engine, null) { 0 },
            bmSt, playSt, chapterData, mapGfx,
            MapAnimations(rom, chapterData, hardware, playSt, engine, bmSt),
            unitSprites, EntityBmMaps(map, units, playSt), mapRender, hooks,
        )
        val root: Proc = engine.start(ProcScript("test BMapMain", listOf(block(), end())), 2)

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun seed() {
            val e = ewram(FIRST - 1)
            hardware.lcd.readFrom(iwram(FIRST - 1), LCD_BUFFER - IWRAM)
            iwram(FIRST - 1).copyInto(gmIwram)
            for (i in 0 until 512) hardware.palette[i] = u16(e, PALETTE - EWRAM + 2 * i).toShort()
            for (bg in 0..3) {
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) hardware.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            e.copyInto(colorFade.step, 0, FADE_STEP - EWRAM, FADE_STEP - EWRAM + 32)
            e.copyInto(
                colorFade.components, 0, FADE_COMPONENTS - EWRAM, FADE_COMPONENTS - EWRAM + 0x600,
            )
            data.region("f09320", "vram").copyInto(hardware.vram)
            keys.readFrom(e, KEY_STATUS - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            bmSt.decode(e, BM_ST - EWRAM)
            units.loadFrom(e)
            map.initChapterMap(playSt.chapterIndex)
        }

        fun step(f: Int) {
            keys.update(data.inputs[f - 1])
            oam.syncHi()
            if (f == FIRST) engine.startBlocking(host.script, root)
            for (tree in 0 until 8) engine.run(engine.root(tree))
        }

        /** Compares the state after our step [step] with the dump of frame [f]. */
        fun compare(step: Int, f: Int) {
            val e = ewram(f)
            val procs = ProcArrayDump.ofEwram(e, procNames)
            val orig = procs.find("gProcScr_ChapterIntro")!!
            compareProc(orig, engine.find(host.script), f, "intro")
            compareProc(
                procs.find("ProcScr_ChapterIntro_KeyListen"),
                engine.find(host.keyListenScript), f, "keyListen",
            )
            val scrollOrig = procs.find("ProcScr_ChapterIntro_Bg1And3Scroll")
            val scroll = engine.find(host.bg1And3ScrollScript)
            compareProc(scrollOrig, scroll, f, "bg1And3Scroll")
            if (scrollOrig != null) {
                assertEquals(
                    s16(scrollOrig, 0x4C), (scroll!!.state as ChapterIntro.State).timer,
                    "f$f scroll timer",
                )
            }
            compareIntroFields(f, orig, engine.find(host.script)!!.state as ChapterIntro.State)
            compareKeys(f, e)
            compareLcd(f)
            compareBuffers(f, e)
            if (f >= TITLE_FRAME) {
                assertEquals(
                    u16(e, CHAPTER_TITLE_FX_ST + 2 - EWRAM), host.chapterTitle.chrStr,
                    "f$f chr_str",
                )
            }
            if (f == TITLE_FRAME) titleDrawnAt += f
            compared++
            if (names.getValue(f).startsWith("f") && step > FIRST) compareFull(f)
        }

        fun compareKeys(f: Int, e: ByteArray) {
            val keyBytes = ByteArray(KeyStatus.STRUCT_SIZE)
            keys.writeTo(keyBytes)
            assertEquals(
                e.copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)
                    .toList(),
                keyBytes.toList(), "f$f key status",
            )
        }

        fun compareIntroFields(f: Int, orig: ProcArrayDump.Proc, state: ChapterIntro.State) {
            assertEquals(orig.u16(0x52), state.isSkipping, "f$f isSkipping")
            if (f < 9385) return
            assertEquals(s16(orig, 0x50), state.skipTarget, "f$f skipTarget")
            if (f <= TITLE_FRAME) {
                assertEquals(s16(orig, 0x4C), state.timer, "f$f twin timer")
                assertEquals(s16(orig, 0x4E), state.unk4E, "f$f unk_4E")
                assertEquals(orig.u32(0x2C), state.xLight, "f$f xLight")
                assertEquals(orig.u32(0x30), state.yLight, "f$f yLight")
                assertEquals(s16(orig, 0x64), state.unk64, "f$f unk_64")
                assertEquals(s16(orig, 0x66), state.unk66, "f$f unk_66")
                assertEquals(s16(orig, 0x68), state.unk68, "f$f unk_68")
            } else if (f <= EXPLOSION_LAST) {
                assertEquals(s16(orig, 0x4C), state.timer, "f$f explosion timer")
            }
        }

        fun compareBuffers(f: Int, e: ByteArray) {
            for (bg in 0..3) {
                if (bg == 2 && f > EXPLOSION_LAST) continue
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(
                        u16(e, at + 2 * i), hardware.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$f BG$bg tilemap[$i]",
                    )
                }
            }
            for (i in 0 until 512) {
                if (f > EXPLOSION_LAST && i in 64 until 80) continue
                assertEquals(
                    u16(e, PALETTE - EWRAM + 2 * i), hardware.palette[i].toInt() and 0xFFFF,
                    "f$f palette[$i]",
                )
            }
            for (i in 0 until 32) {
                assertEquals(e[FADE_STEP - EWRAM + i], colorFade.step[i], "f$f fade step $i")
            }
            for (i in 0 until 0x600) {
                assertEquals(
                    e[FADE_COMPONENTS - EWRAM + i], colorFade.components[i],
                    "f$f fade component $i",
                )
            }
        }

        fun compareProc(orig: ProcArrayDump.Proc?, ours: Proc?, f: Int, what: String) {
            assertEquals(orig == null, ours == null, "f$f $what live")
            if (orig == null || ours == null) return
            assertEquals(orig.scrCur, ours.scrCur, "f$f $what cmd")
            assertEquals(s16(orig, 0x24), ours.sleepTime, "f$f $what sleep")
            assertEquals(orig.u8(0x26), ours.mark, "f$f $what mark")
            assertEquals(orig.u8(0x28), ours.lockCnt, "f$f $what lock")
            assertEquals(orig.u32(0x0C) != 0, ours.idleCb != null, "f$f $what repeat callback")
        }

        fun compareLcd(f: Int) {
            val want = LcdControl().also { it.readFrom(iwram(f), LCD_BUFFER - IWRAM) }
            val lcd = hardware.lcd
            val at = "f$f"
            if (f < TITLE_FRAME) {
                assertEquals(want.dispcnt, lcd.dispcnt, "$at dispcnt")
                assertEquals(want.bldcnt, lcd.bldcnt, "$at bldcnt")
                assertEquals(want.blendCoeffA, lcd.blendCoeffA, "$at blendCoeffA")
                assertEquals(want.blendCoeffB, lcd.blendCoeffB, "$at blendCoeffB")
                assertEquals(want.blendY, lcd.blendY, "$at blendY")
            } else {
                // The light burst (SetBlendAlpha) owns the effect bits and the coefficients.
                assertEquals(
                    want.bldcnt and BLD_EFFECT.inv(), lcd.bldcnt and BLD_EFFECT.inv(),
                    "$at bldcnt targets",
                )
            }
            if (f == TITLE_FRAME) assertEquals(want.dispcnt, lcd.dispcnt, "$at dispcnt")
            if (f > EXPLOSION_LAST) {
                assertEquals(want.dispcnt and 7, lcd.dispcnt and 7, "$at dispcnt mode")
            }
            for (bg in 0..3) {
                assertEquals(want.bgCnt[bg], lcd.bgCnt[bg], "$at bgcnt$bg")
                if (f > EXPLOSION_LAST) continue
                assertEquals(want.bgHofs[bg], lcd.bgHofs[bg], "$at hofs$bg")
                assertEquals(want.bgVofs[bg], lcd.bgVofs[bg], "$at vofs$bg")
                assertEquals(want.winH[bg], lcd.winH[bg], "$at winH$bg")
                assertEquals(want.winV[bg], lcd.winV[bg], "$at winV$bg")
            }
            if (f <= EXPLOSION_LAST) assertEquals(want.wincnt, lcd.wincnt, "$at wincnt")
        }

        fun compareFull(f: Int) {
            val key = names.getValue(f)
            val at = "f$f"
            val io = data.region(key, "io")
            // From the light burst on, its FlushLCDControl writes BG3HOFS (not ported).
            if (f < TITLE_FRAME) assertEquals(u16(io, 0x1C), host.regBg3Hofs, "$at REG_BG3HOFS")
            val vram = data.region(key, "vram")
            val scratch = ByteArray(0x8000)
            val spans = mutableListOf(
                0 to rom.decompressInto(IMG_COMM_GAME_BG, scratch, 0),
                0x14000 to rom.decompressInto(IMG_SPRITES, scratch, 0),
            )
            // The fog image replaces the lens flare at 9526.
            if (f <= EXPLOSION_LAST) {
                spans += 0x8000 to rom.decompressInto(IMG_LENS_FLARE, scratch, 0)
            }
            if (f >= TITLE_FRAME) {
                spans += TITLE_CHR * 0x20 to TITLE_BYTES
                titleDumps += f
                titleBytesNonZero =
                    (0 until TITLE_BYTES).any { hardware.vram[TITLE_CHR * 0x20 + it] != 0.toByte() }
            }
            for ((start, size) in spans) {
                for (i in start until start + size) {
                    assertEquals(vram[i], hardware.vram[i], "$at VRAM +${Integer.toHexString(i)}")
                }
            }
        }
    }

    private companion object {
        const val FIRST = 9322
        const val LAST = 9535
        const val TITLE_FRAME = 9455
        const val EXPLOSION_LAST = 9525
        const val TITLE_CHR = 0x280
        const val TITLE_BYTES = 0x40 * 0x20
        const val BLD_EFFECT = 0xC0
        const val NODE_MAX = 0x1D
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val LCD_BUFFER = 0x03003080
        const val PALETTE = 0x020228A8
        const val FADE_STEP = 0x02022288
        const val FADE_COMPONENTS = 0x020222A8
        const val KEY_STATUS = 0x02024CC0
        const val CHAPTER_TITLE_FX_ST = 0x0203E78C
        val BG_BUFFERS = intArrayOf(0x02022CA8, 0x020234A8, 0x02023CA8, 0x020244A8)
        const val IMG_COMM_GAME_BG = 0x08B12DB4
        const val IMG_LENS_FLARE = 0x08B17B64
        const val IMG_SPRITES = 0x08B19874

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(p: ProcArrayDump.Proc, field: Int): Int = p.u16(field).toShort().toInt()
    }
}
