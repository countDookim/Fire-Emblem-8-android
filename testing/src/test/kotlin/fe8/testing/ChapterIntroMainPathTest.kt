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
 * The chapter intro's main path from the twin lights to the end of the 160-frame timer
 * (src/chapterintrofx.c, src/chapter_title.c, src/sysutil.c) against `ram_trace_newgame_intro`
 * (no key presses), frames 9322-9872: `ChapterIntro_InitBLDCNT`, the light burst (9455-9502) and
 * the chapter title draw (9455), the light explosion (9456-9525), `ChapterIntro_SetBG_802009C`,
 * Bg2Scroll, the decal banner and its reveal window, the fog fade-in and the timer (9526-9708).
 * Then `ChapterIntro_8020A40` and `8020A8C` (the palette fade out, to 9830), the END_EACH of the
 * scroll and decal procs (9831), `InitMapDisplay`, `BeginFadeToMap`, `LoopFadeToMap`, the
 * 30-frame sleep and `ChapterIntro_End`; the intro proc is freed at the hand-off frame 9872.
 *
 * Lag: the original's main loop overran its frame twice and the dump is sampled at VBlank in the
 * middle of the pass. [INIT_MAP_LAG] (the map graphics, 9832-9836) and [END_LAG]
 * (`RefreshBMapGraphics`, 9869-9872) are frames in which the fixture runs no procs and no key
 * update; [MID_RUN] (9831-9837, 9868-9871) are frames sampled mid-pass and not compared. The
 * dump of 9870 falls in the second window. At 9872 only the procs, keys, camera, fade state and
 * DISPCNT/BLDCNT/blend registers are compared, because `RefreshBMapGraphics` is a stand-in.
 *
 * Alignment is that of [ChapterIntroDifferentialTest] (seeded from the dump of 9321, step 9322 is
 * compared with the dump of 9325, then one step per frame, keys from the captured `inputs.bin`).
 * `GetGameClock` comes from the dump's `gGameClock`; the fixture also copies
 * `gLCDControlBuffer` to the display registers before each pass, as VBlank does.
 *
 * Compared every frame: the intro, KeyListen, Bg1And3Scroll, LightBurst, Bg2Scroll,
 * PutDecalSprite and RevealDecalSprite procs (script position, sleep, mark, lock count, repeat
 * callback), the `ChapterIntroFxProc` fields (`isSkipping`, `skipTarget`; twin light fields to
 * 9455; `timer`, `unk_64`, `unk_66`, `unk_68` after; the burst's and the reveal's `timer`, the
 * burst's `unk_4E` and `unk_66`), the key status, all four BG tilemap buffers, the whole palette
 * buffer (so the fog's bank 4), `gFadeComponentStep`, `gFadeComponents`, from 9455
 * `gChapterTitleFxSt.chr_str`, and the whole `gLCDControlBuffer` (DISPCNT, BGCNT, BG scroll,
 * windows, WINCNT, BLDCNT, BLDALPHA, BLDY, BG2/BG3 affine).
 *
 * At the full dumps (every 10 frames from 9460): BG and OBJ tile data in VRAM (BG2's lens flare
 * to 9520, the fog image after, the title image at tile 0x280), the I/O registers 0x00-0x55
 * (REG_BG3HOFS from the direct write, DISPSTAT and VCOUNT skipped) and the OAM: the four OBJ
 * affine matrices and the objects (attr0-2) of the previous pass's buffer, which is what VBlank
 * synced (six explosion objects, none while PutDecalSprite sleeps, then the banner twice).
 *
 * Limits: `GetPlayChapterId` and `GetNextUnclearedNode` come from the existing world map ports
 * (`WmNodeData`, `WorldMapPath` on the captured `gGMData`). `InitCameraYPos` is verified by
 * [ChapterIntroDifferentialTest] (its value is overwritten by `InitMapDisplay` in the same pass).
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
        "ChapterIntro_SetBG_802009C", "ChapterIntro_LightBurst_Init",
        "ChapterIntro_LightBurst_Loop", "ChapterIntro_LightExplosion_Init",
        "ChapterIntro_LightExplosion_Loop", "PutOppositeLightExplosionSprites",
        "BgAffinRotScaling", "BgAffinScaling", "BgAffinAnchoring",
        "ChapterIntro_Bg2Scroll_Loop", "ChapterIntro_PutDecalSprite_Loop",
        "ChapterIntro_RevealDecalSprite_Init", "ChapterIntro_RevealDecalSprite_Loop",
        "ChapterIntro_InitFogGfx", "ChapterIntro_BlendFogAlpha_Loop",
        "ChapterIntro_SetTimer", "ChapterIntro_TickTimerMaybe",
        "ChapterIntro_8020A40", "ChapterIntro_8020A8C", "ChapterIntro_InitMapDisplay", "ChapterIntro_BeginFadeToMap",
        "ChapterIntro_LoopFadeToMap",
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
        assertEquals(LAST - FIRST - 2 - MID_RUN.count(), sim.compared, "frames compared")
        val dumps = (9460..9830 step 10).toList() + (9840..9860 step 10).toList()
        assertEquals(dumps, sim.titleDumps)
        assertTrue(sim.titleBytesNonZero, "title image compared")
        assertEquals(1, sim.titleDrawnAt.size, "title drawn once")
        assertEquals(dumps, sim.oamDumps)
        assertEquals(dumps, sim.ioDumps)
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
        var prevOam = ShortArray(0)
        var prevObjectCount = 0
        var clock = 0
        val oamDumps = mutableListOf<Int>()
        val ioDumps = mutableListOf<Int>()
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
            it.gameClock = { clock }
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
            // The key status is read once per main loop pass, so lag frames do not update it.
            if (f !in INIT_MAP_LAG && f !in END_LAG) keys.update(data.inputs[f - 1])
            clock = u32(iwram(f), GAME_CLOCK - IWRAM) + CLOCK_SKEW + if (f == LATE_CLOCK) 1 else 0
            prevOam = oam.buffer.copyOf()
            prevObjectCount = oam.hiPut / 4
            oam.syncHi()
            // VBlank copies gLCDControlBuffer to the display registers before the procs run.
            hardware.lcd.flushTo(host.io)
            if (f == FIRST) engine.startBlocking(host.script, root)
            if (f in INIT_MAP_LAG) return
            for (tree in 0 until 8) engine.run(engine.root(tree))
        }

        /** Compares the state after our step [step] with the dump of frame [f]. */
        fun compare(step: Int, f: Int) {
            if (f in MID_RUN) return
            val e = ewram(f)
            val procs = ProcArrayDump.ofEwram(e, procNames)
            val orig = procs.find("gProcScr_ChapterIntro")
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
            compareProc(
                procs.find("ProcScr_ChapterIntro_Bg2Scroll"),
                engine.find(host.bg2ScrollScript), f, "bg2Scroll",
            )
            compareProc(
                procs.find("ProcScr_ChapterIntro_PutDecalSprite"),
                engine.find(host.putDecalScript), f, "putDecalSprite",
            )
            val revealOrig = procs.find("ProcScr_ChapterIntro_RevealDecalSprite")
            val reveal = engine.find(host.revealDecalScript)
            compareProc(revealOrig, reveal, f, "revealDecalSprite")
            if (revealOrig != null) {
                assertEquals(
                    s16(revealOrig, 0x4C), (reveal!!.state as ChapterIntro.State).timer,
                    "f$f reveal timer",
                )
            }
            val burstOrig = procs.find("ProcScr_ChapterIntro_LightBurst")
            val burst = engine.find(host.lightBurstScript)
            compareProc(burstOrig, burst, f, "lightBurst")
            if (burstOrig != null) {
                val b = burst!!.state as ChapterIntro.State
                assertEquals(s16(burstOrig, 0x4C), b.timer, "f$f burst timer")
                assertEquals(s16(burstOrig, 0x4E), b.unk4E, "f$f burst unk_4E")
                assertEquals(s16(burstOrig, 0x66), b.unk66, "f$f burst unk_66")
            }
            if (orig != null) {
                compareIntroFields(f, orig, engine.find(host.script)!!.state as ChapterIntro.State)
            }
            compareCamera(f, e)
            compareKeys(f, e)
            compareLcd(f)
            if (f < LAST) compareBuffers(f, e) else compareFadeState(f, e)
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

        fun compareCamera(f: Int, e: ByteArray) {
            val want = BmSt().also { it.decode(e, BM_ST - EWRAM) }
            assertEquals(want.cameraX, bmSt.cameraX, "f$f camera.x")
            assertEquals(want.cameraY, bmSt.cameraY, "f$f camera.y")
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
            } else {
                // The light explosion's timer and counters, then the fog and the timer.
                assertEquals(s16(orig, 0x4C), state.timer, "f$f timer")
                assertEquals(s16(orig, 0x64), state.unk64, "f$f unk_64")
                assertEquals(s16(orig, 0x66), state.unk66, "f$f unk_66")
                assertEquals(s16(orig, 0x68), state.unk68, "f$f unk_68")
            }
        }

        fun compareBuffers(f: Int, e: ByteArray) {
            for (bg in 0..3) {
                val at = BG_BUFFERS[bg] - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(
                        u16(e, at + 2 * i), hardware.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$f BG$bg tilemap[$i]",
                    )
                }
            }
            for (i in 0 until 512) {
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

        fun compareFadeState(f: Int, e: ByteArray) {
            // RefreshBMapGraphics reloads the palette (stand-in), but the fade state is left.
            for (i in 0 until 32) {
                assertEquals(e[FADE_STEP - EWRAM + i], colorFade.step[i], "f$f fade step $i")
            }
        }

        fun compareLcd(f: Int) {
            val want = LcdControl().also { it.readFrom(iwram(f), LCD_BUFFER - IWRAM) }
            val lcd = hardware.lcd
            val at = "f$f"
            assertEquals(want.dispcnt, lcd.dispcnt, "$at dispcnt")
            assertEquals(want.bldcnt, lcd.bldcnt, "$at bldcnt")
            assertEquals(want.blendCoeffA, lcd.blendCoeffA, "$at blendCoeffA")
            assertEquals(want.blendCoeffB, lcd.blendCoeffB, "$at blendCoeffB")
            assertEquals(want.blendY, lcd.blendY, "$at blendY")
            if (f == LAST) return
            for (i in 0 until 12) {
                assertEquals(want.affine[i], lcd.affine[i], "$at affine[$i]")
            }
            // The hand-off frame follows ChapterIntro_End's RefreshBMapGraphics (a stand-in):
            // only the registers sub_80141B0 writes are compared there.
            if (f == LAST) return
            for (bg in 0..3) {
                assertEquals(want.bgCnt[bg], lcd.bgCnt[bg], "$at bgcnt$bg")
                assertEquals(want.bgHofs[bg], lcd.bgHofs[bg], "$at hofs$bg")
                assertEquals(want.bgVofs[bg], lcd.bgVofs[bg], "$at vofs$bg")
                assertEquals(want.winH[bg], lcd.winH[bg], "$at winH$bg")
                assertEquals(want.winV[bg], lcd.winV[bg], "$at winV$bg")
            }
            assertEquals(want.wincnt, lcd.wincnt, "$at wincnt")
        }

        /**
         * The display registers: what VBlank flushed from `gLCDControlBuffer` (the fixture's
         * `step` does it) plus the direct `REG_BG3HOFS` write of Bg1And3Scroll and, to 9502, the
         * light burst's own `FlushLCDControl`. DISPSTAT and VCOUNT (hardware-owned) are skipped.
         */
        fun compareFlushedRegisters(f: Int, io: ByteArray) {
            for (i in 0 until 0x56) {
                if (i in 0x04..0x07) continue
                assertEquals(io[i], host.io[i], "f$f I/O +${Integer.toHexString(i)}")
            }
            ioDumps += f
        }

        /**
         * The dump holds the OAM synced in VBlank: the previous pass's buffer. Four OBJ affine
         * matrices and the six light explosion objects (attr0-2).
         */
        fun compareOam(f: Int, key: String) {
            val oamDump = data.region(key, "oam")
            for (m in 0 until 4) {
                for (k in intArrayOf(3, 7, 11, 15)) {
                    val at = m * 16 + k
                    assertEquals(
                        u16(oamDump, 2 * at), prevOam[at].toInt() and 0xFFFF, "f$f affine[$m][$k]",
                    )
                }
            }
            // Six light explosion objects to 9525, none while PutDecalSprite sleeps (30 frames
            // from 9526), then the decal banner put twice (4 + 4 objects).
            val expected =
                if (f <= EXPLOSION_LAST) 6 else if (f <= DECAL_FIRST) 0 else if (f <= SCROLL_END) 8 else 0
            assertEquals(expected, prevObjectCount, "f$f object count")
            for (obj in 0 until prevObjectCount) {
                for (attr in 0..2) {
                    assertEquals(
                        u16(oamDump, 2 * (4 * obj + attr)),
                        prevOam[4 * obj + attr].toInt() and 0xFFFF,
                        "f$f obj$obj attr$attr",
                    )
                }
            }
            oamDumps += f
        }

        fun compareFull(f: Int) {
            val key = names.getValue(f)
            val at = "f$f"
            val io = data.region(key, "io")
            assertEquals(u16(io, 0x1C), host.regBg3Hofs, "$at REG_BG3HOFS")
            if (f >= TITLE_FRAME) compareFlushedRegisters(f, io)
            if (f >= TITLE_FRAME) compareOam(f, key)
            val vram = data.region(key, "vram")
            val scratch = ByteArray(0x8000)
            val spans = mutableListOf(
                0 to rom.decompressInto(IMG_COMM_GAME_BG, scratch, 0),
                0x14000 to rom.decompressInto(IMG_SPRITES, scratch, 0),
            )
            // The fog image replaces the lens flare at 9526.
            spans += 0x8000 to if (f <= EXPLOSION_LAST) {
                rom.decompressInto(IMG_LENS_FLARE, scratch, 0)
            } else {
                rom.decompressInto(IMG_FOG, scratch, 0)
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
        const val LAST = 9872
        const val SCROLL_END = 9831

        /**
         * Passes in which the original's main loop did not reach the proc loop: `InitMapDisplay`
         * (decompressing and drawing the map) overran into 9832-9835, so the intro stayed
         * at `ChapterIntro_BeginFadeToMap` until 9835. [Sim.step] skips the proc run there. The
         * first pass after it ran at 9837 with `gGameClock` already one VBlank past the dump's
         * sample ([LATE_CLOCK]), which is what lets `LoopFadeToMap` (even clock only) break then.
         */
        val INIT_MAP_LAG = 9832..9836

        /**
         * Frames the dump samples in the middle of a long proc run, which our single pass
         * cannot reproduce: 9831 (after `SetSkipTarget(0)`, before `InitCameraYPos`; the same pass
         * is complete in ours) and `ChapterIntro_End` (9868-9871: `RefreshBMapGraphics` runs
         * 4 frames, the proc is freed at 9872; ours ends it in the pass of 9868).
         */
        /** `ChapterIntro_End`'s `RefreshBMapGraphics` overran: no key update, no proc run. */
        val END_LAG = 9869..9872

        const val LATE_CLOCK = 9837

        val MID_RUN = (9831..9837) + (9868..9871)
        const val DECAL_FIRST = 9556
        const val GAME_CLOCK = 0x03000014
        const val CLOCK_SKEW = 0
        const val IMG_FOG = 0x08B18F34
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

        fun u32(b: ByteArray, at: Int): Int = u16(b, at) or (u16(b, at + 2) shl 16)

        fun s16(p: ProcArrayDump.Proc, field: Int): Int = p.u16(field).toShort().toInt()
    }
}
