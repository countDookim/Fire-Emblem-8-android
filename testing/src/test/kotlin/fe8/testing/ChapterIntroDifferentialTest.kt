package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.ChapterIntro
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The start of the chapter intro (src/chapterintrofx.c) against `ram_trace_newgame_bm`, frames
 * 9322-9420: `ChapterIntro_Init` (the `ChapterIntro_8020010` background load and `sub_80017B4` run
 * inside it), the 60-frame sleep, the key listener, the twin lights and the Bg1And3Scroll child,
 * until A at 9420 sends the intro to label 1.
 *
 * Alignment: the dump of frame N is the state after the main loop pass of frame N. The port is
 * seeded from frame 9321 and the intro is started in the step of frame 9322 (the start runs
 * `BMapDispSuspend`, `ChapterIntro_Init` and arms the sleep). In the original `ChapterIntro_Init`
 * spans the passes 9322-9324 (VBlank keeps running inside it), which the port runs atomically in
 * one update, so the step of 9322 is compared with the dump of 9325 and nothing is compared for
 * 9323-9324. Frames 9326-9420 run the proc trees once each and are compared one to one, with the
 * key status updated from the captured `inputs.bin` every frame. The original updates the key
 * status once during the span 9322-9325 (its idle counter shows it; the inputs there are 0), so
 * the port does one update for it too.
 *
 * Compared every compared frame: the intro, KeyListen and Bg1And3Scroll procs (script position,
 * sleep, mark, lock count, whether a repeat callback is set, the `ChapterIntroFxProc` fields the
 * functions write: from 9385 on `skipTarget`, `timer`, `unk_4E`, `xLight`, `yLight`, `unk_64`,
 * `unk_66`, `unk_68`, always `isSkipping`, and the Bg1And3Scroll `timer`), the key status
 * struct, `gLCDControlBuffer` (DISPCNT, BGCNT, scroll, windows, WINCNT, BLDCNT, blend
 * coefficients), BG0-BG3 tilemap buffers, `gPaletteBuffer`, `gFadeComponentStep` and
 * `gFadeComponents`. Full dumps (9349, 9379, 9409) also compare BG3HOFS from the I/O dump, the
 * BG tile data and sprite tiles `ChapterIntro_Init` decompresses, and (9409) the OAM
 * affine matrix and, at 9409, the two twin-light objects. Not compared: the sound effect of
 * `ChapterIntro_TwinLights_InitAndPlaySe` and `BMapDispSuspend`/`Resume` (hooks).
 */
class ChapterIntroDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ChapterIntro_Init", "ChapterIntro_8020010", "sub_80017B4",
        "ChapterIntro_KeyListen_Init", "ChapterIntro_KeyListen_Loop",
        "ChapterIntro_SetSkipTarget", "ChapterIntro_Bg1And3Scroll_Init",
        "ChapterIntro_Bg1And3Scroll_Loop", "ChapterIntro_TwinLights_Loop",
    )
    fun `intro start twin lights and A skip match every frame`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim()
        sim.seed()
        sim.step(FIRST)
        sim.compare(FIRST + 3)
        for (f in FIRST + 4..LAST) {
            sim.step(f)
            sim.compare(f)
        }
        assertEquals(1, sim.suspends)
        assertEquals(0, sim.resumes)
        assertEquals(LAST - FIRST - 2, sim.compared, "frames compared")
        assertEquals(listOf(9349, 9379, 9409), sim.fullDumps, "full dumps compared")
        assertTrue(sim.sawTwinObjects, "twin light objects compared")
        assertEquals(1, sim.skipSeenAt.size, "skip happened once")
        assertEquals(LAST, sim.skipSeenAt[0])
    }

    private inner class Sim {
        val data = ref.run("ram_trace_newgame_bm")
        val names = data.dumps.entries.associate { it.value to it.key }
        val procNames = ProcArrayDump.romNames()
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        val colorFade = ColorFade(hardware.palette)
        val oam = OamBuffer()
        val keys = KeyStatus()
        var suspends = 0
        var resumes = 0
        var compared = 0
        val fullDumps = mutableListOf<Int>()
        var sawTwinObjects = false
        val skipSeenAt = mutableListOf<Int>()
        var prevOam = ShortArray(0)
        var prevObjectCount = 0
        val host = ChapterIntro(
            engine, hardware, colorFade, oam, rom, SoundWrapper(engine, null) { 0 },
            ChapterIntro.Hooks(
                newKeys = { keys.newKeys },
                bMapDispSuspend = { suspends++ },
                bMapDispResume = { resumes++ },
            ),
        )
        val root: Proc = engine.start(ProcScript("test BMapMain", listOf(block(), end())), 2)
        lateinit var intro: Proc

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun seed() {
            val e = ewram(FIRST - 1)
            hardware.lcd.readFrom(iwram(FIRST - 1), LCD_BUFFER - IWRAM)
            for (i in 0 until 512) hardware.palette[i] = u16(e, PALETTE - EWRAM + 2 * i).toShort()
            for (bg in 0..3) {
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) hardware.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            e.copyInto(colorFade.step, 0, FADE_STEP - EWRAM, FADE_STEP - EWRAM + 32)
            e.copyInto(colorFade.components, 0, FADE_COMPONENTS - EWRAM,
                FADE_COMPONENTS - EWRAM + 0x600)
            data.region("f09319", "vram").copyInto(hardware.vram)
            keys.readFrom(e, KEY_STATUS - EWRAM)
        }

        fun step(f: Int) {
            keys.update(data.inputs[f - 1])
            prevOam = oam.buffer.copyOf()
            prevObjectCount = oam.hiPut / 4
            oam.syncHi()
            if (f == FIRST) {
                intro = engine.startBlocking(host.script, root)!!
            }
            for (tree in 0 until 8) engine.run(engine.root(tree))
        }

        fun compare(f: Int) {
            val e = ewram(f)
            val procs = ProcArrayDump.ofEwram(e, procNames)
            compareProc(procs.find("gProcScr_ChapterIntro"), engine.find(host.script), f, "intro")
            val keyListen = engine.find(host.keyListenScript)
            val keyOrig = procs.find("ProcScr_ChapterIntro_KeyListen")
            compareProc(keyOrig, keyListen, f, "keyListen")
            val scroll = engine.find(host.bg1And3ScrollScript)
            val scrollOrig = procs.find("ProcScr_ChapterIntro_Bg1And3Scroll")
            compareProc(scrollOrig, scroll, f, "bg1And3Scroll")
            val orig = procs.find("gProcScr_ChapterIntro")!!
            val state = engine.find(host.script)!!.state as ChapterIntro.State
            assertEquals(orig.u16(0x52), state.isSkipping, "f$f isSkipping")
            if (f >= 9385) {
                assertEquals(s16(orig, 0x50), state.skipTarget, "f$f skipTarget")
                assertEquals(s16(orig, 0x4C), state.timer, "f$f twin timer")
                assertEquals(s16(orig, 0x4E), state.unk4E, "f$f unk_4E")
                assertEquals(orig.u32(0x2C), state.xLight, "f$f xLight")
                assertEquals(orig.u32(0x30), state.yLight, "f$f yLight")
                assertEquals(s16(orig, 0x64), state.unk64, "f$f unk_64")
                assertEquals(s16(orig, 0x66), state.unk66, "f$f unk_66")
                assertEquals(s16(orig, 0x68), state.unk68, "f$f unk_68")
            }
            if (scrollOrig != null) {
                assertEquals(s16(scrollOrig, 0x4C), (scroll!!.state as ChapterIntro.State).timer,
                    "f$f scroll timer")
            }
            if (keyOrig == null && f == LAST) skipSeenAt += f
            val keyBytes = ByteArray(KeyStatus.STRUCT_SIZE)
            keys.writeTo(keyBytes)
            assertEquals(
                e.copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)
                    .toList(),
                keyBytes.toList(), "f$f key status",
            )
            compareLcd(f)
            for (bg in 0..3) {
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(u16(e, at + 2 * i), hardware.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$f BG$bg tilemap[$i]")
                }
            }
            for (i in 0 until 512) {
                assertEquals(u16(e, PALETTE - EWRAM + 2 * i),
                    hardware.palette[i].toInt() and 0xFFFF, "f$f palette[$i]")
            }
            for (i in 0 until 32) {
                assertEquals(e[FADE_STEP - EWRAM + i], colorFade.step[i], "f$f fade step $i")
            }
            for (i in 0 until 0x600) {
                assertEquals(e[FADE_COMPONENTS - EWRAM + i], colorFade.components[i],
                    "f$f fade component $i")
            }
            compared++
            if (names.getValue(f).startsWith("f")) compareFull(f, names.getValue(f))
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
            assertEquals(want.dispcnt, lcd.dispcnt, "$at dispcnt")
            for (bg in 0..3) {
                assertEquals(want.bgCnt[bg], lcd.bgCnt[bg], "$at bgcnt$bg")
                assertEquals(want.bgHofs[bg], lcd.bgHofs[bg], "$at hofs$bg")
                assertEquals(want.bgVofs[bg], lcd.bgVofs[bg], "$at vofs$bg")
                assertEquals(want.winH[bg], lcd.winH[bg], "$at winH$bg")
                assertEquals(want.winV[bg], lcd.winV[bg], "$at winV$bg")
            }
            assertEquals(want.wincnt, lcd.wincnt, "$at wincnt")
            assertEquals(want.bldcnt, lcd.bldcnt, "$at bldcnt")
            assertEquals(want.blendCoeffA, lcd.blendCoeffA, "$at blendCoeffA")
            assertEquals(want.blendCoeffB, lcd.blendCoeffB, "$at blendCoeffB")
            assertEquals(want.blendY, lcd.blendY, "$at blendY")
        }

        fun compareFull(f: Int, key: String) {
            val at = "f$f"
            val io = data.region(key, "io")
            assertEquals(u16(io, 0x1C), host.regBg3Hofs, "$at REG_BG3HOFS")
            val vram = data.region(key, "vram")
            val scratch = ByteArray(0x8000)
            val spans = listOf(
                0 to rom.decompressInto(IMG_COMM_GAME_BG, scratch, 0),
                0x8000 to rom.decompressInto(IMG_LENS_FLARE, scratch, 0),
                0x14000 to rom.decompressInto(IMG_SPRITES, scratch, 0),
            )
            for ((start, size) in spans) {
                for (i in start until start + size) {
                    assertEquals(vram[i], hardware.vram[i], "$at VRAM +${Integer.toHexString(i)}")
                }
            }
            if (f == 9409) {
                // The dump holds the OAM synced in VBlank: the previous pass's buffer.
                val oamDump = data.region(key, "oam")
                for (k in intArrayOf(3, 7, 11, 15)) {
                    assertEquals(u16(oamDump, 2 * k), prevOam[k].toInt() and 0xFFFF,
                        "$at affine[$k]")
                }
                if (f == 9409) {
                    assertEquals(2, prevObjectCount, "$at twin light object count")
                    for (obj in 0 until 2) {
                        for (attr in 0..2) {
                            assertEquals(u16(oamDump, 2 * (4 * obj + attr)),
                                prevOam[4 * obj + attr].toInt() and 0xFFFF, "$at obj$obj attr$attr")
                        }
                    }
                    sawTwinObjects = true
                }
            }
            fullDumps += f
        }
    }

    private companion object {
        const val FIRST = 9322
        const val LAST = 9420
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val LCD_BUFFER = 0x03003080
        const val PALETTE = 0x020228A8
        const val FADE_STEP = 0x02022288
        const val FADE_COMPONENTS = 0x020222A8
        const val KEY_STATUS = 0x02024CC0
        val BG_BUFFERS = intArrayOf(0x02022CA8, 0x020234A8, 0x02023CA8, 0x020244A8)
        const val IMG_COMM_GAME_BG = 0x08B12DB4
        const val IMG_LENS_FLARE = 0x08B17B64
        const val IMG_SPRITES = 0x08B19874

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(p: ProcArrayDump.Proc, field: Int): Int = p.u16(field).toShort().toInt()
    }
}
