package fe8.testing

import fe8.game.GameLoop
import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.MapCursor
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitSprites
import fe8.game.event.EventConst.EV_EXEC_UNK5
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.MapCursorProc
import fe8.game.event.ScriptMemory
import fe8.game.event.createProcScrEventDisplayCursor
import fe8.game.flags.Fe8EventFlags
import fe8.game.scene.TalkScene
import fe8.game.scene.TalkState
import fe8.game.ui.ItemIcon
import fe8.game.ui.UiFrame
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.LcgRng
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-6e-c: event teardown helpers, `gProcScr_TalkSkipListener` and `ProcScr_EventDisplayCursor`
 * verified against `ram_trace_prologue_end` (`PC-cap1`, `f10297..f25580`, especially
 * `f25419..f25423`).
 *
 * - Teardown helpers (`EndTalk`, `EndCgText`, `sub_808BB74`, `ResetBkselPalette`, `ResumeMenu`,
 *   `ChangeUnitSpritePalette`) stay `Transcribed`: none of `CgText`, the five box-dialogue procs,
 *   `BKSEL`, or `Menu` ever exists in `PC-cap1`, every `gProcScr_Talk` ends before `REMA` and
 *   before `EventEngine_OnEnd` at `f25422` (`f25404`), `mapSpritePalIdOverride` is always 0, and
 *   the OBJ unit sprite palettes are already in place before `f25421..25422`. Running the real
 *   teardown at `f25420 -> f25422` resets `activeFont`, requests palette sync, drops `gBmSt.lock`
 *   to 0, and leaves `gPaletteBuffer` and the live proc list matching `PC-cap1`.
 * - `gProcScr_TalkSkipListener` (`TalkSkipListener_OnIdle`) runs in all 5 Chapter 1 talks, sets
 *   `instantScroll = 1` on `A` when `TalkWaitForInput` is absent (`f10600`), and skips
 *   fast-forward when `TalkWaitForInput` is up (`f10800`, `f25400`).
 * - `ProcScr_EventDisplayCursor` (`createProcScrEventDisplayCursor` + `MapCursor.put`) matches
 *   all 5 cursor windows (`f10436..10495`, `f13103..13162`, `f15558..15617`, `f16211..16270`,
 *   `f20269..20328`) and reproduces hardware `OAM[0..3]` at `f15600` and `f20300`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1TeardownDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    @AfterAll
    fun releaseRig() {
        shared = null
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data, nm file, or ROM not present")
        return r!!
    }

    private fun loadRig(): Rig? {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val rom = File(assets, "baserom.gba")
        val names = ProcArrayDump.romNames()
        if (!ref.available || !rom.isFile || names.isEmpty()) return null
        val run = ref.run(RUN)
        if (!run.hasWatch("procs") || !run.hasWatch("evglobals")) return null
        return Rig(rom.readBytes(), run, File(assets, "reference/out/$RUN"), names)
    }

    @Test
    fun `event teardown targets and restores at f25419 to f25423 match PC-cap1`() {
        val r = rig()
        r.assertTeardownPreconditions()
        r.assertEventEndTeardown()
    }

    @Test
    @VerifiesPort("TalkSkipListener_OnIdle")
    fun `gProcScr_TalkSkipListener runs in Chapter 1 talks and fast-forwards on A in PC-cap1`() {
        val r = rig()
        r.assertTalkWindow1()
        r.assertTalkWindow5Tail()
    }

    @Test
    @VerifiesPort("EventDisplayCursor_Loop", "PutMapCursor")
    fun `ProcScr_EventDisplayCursor lifecycle and OAM across Chapter 1 opening match PC-cap1`() {
        val r = rig()
        r.assertDisplayCursorWindows()
    }

    private class Rig(
        private val romBytes: ByteArray,
        private val run: ReferenceData.Run,
        private val runDir: File,
        private val names: Map<Int, String>,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val sym = RamSymbols.fromEnvironment()
        private val procsWatch = run.watch("procs")
        private val globalsWatch = run.watch("evglobals")
        private val gbmWatch = run.watch("gbmst")
        private val inputs = run.inputs
        private val trace = TalkTrace(runDir)

        private fun addressOf(name: String): Int =
            names.entries.firstOrNull { it.value == name }?.key
                ?: error("symbol '$name' not in the ROM name table")

        private fun procsAt(step: Int) = ProcArrayDump.ofWatch(procsWatch, step, names)

        private fun dumpName(step: Int): String =
            if (File(runDir, "r%05d.ewram.bin".format(step)).isFile) "r%05d".format(step)
            else "f%05d".format(step)

        private fun ewram(step: Int): ByteArray = run.region(dumpName(step), "ewram")

        private fun iwram(step: Int): ByteArray = run.region(dumpName(step), "iwram")

        private fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

        private fun newTalkScene(
            hw: BgHardware,
            engine: ProcEngine,
            keys: KeyStatus,
            clock: () -> Int,
            textSpeed: () -> Int,
            setSkipEvBit: () -> Unit,
            sprites: SpriteLayers = SpriteLayers(),
        ): TalkScene {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes,
                MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root),
                0x0815D48C - ROM_BASE,
            )
            return TalkScene(
                rom, romBytes, hw, engine, sprites, keys, clock, messages, {},
                textSpeed, setSkipEvBit, LcgRng(),
            )
        }

        private fun hardwareFrom(ew: ByteArray, ioFile: String): BgHardware {
            val hw = BgHardware()
            val io = File(runDir, ioFile).readBytes()
            hw.lcd.readFrom(io, 0)
            for (i in 0 until BgHardware.PALETTE_COLORS) {
                hw.palette[i] = u16(ew, PALETTE_BUF + 2 * i).toShort()
            }
            for (i in 0 until 0x400) {
                hw.mapBuffer(0)[i] = u16(ew, BG0_TM + 2 * i).toShort()
                hw.mapBuffer(1)[i] = u16(ew, BG1_TM + 2 * i).toShort()
            }
            return hw
        }

        fun assertTeardownPreconditions() {
            // None of the non-talk teardown target procs ever exists anywhere in PC-cap1 opening.
            // Resolve symbols once: ProcArrayDump.find(name) scans the whole name table.
            val absentAddrs = ABSENT_TEARDOWN_PROCS.associateWith { addressOf(it) }
            val engineAddr = addressOf("ProcScr_StdEventEngine")
            for (step in 10297..25580) {
                val dump = procsAt(step)
                for ((absent, addr) in absentAddrs) {
                    assertNull(dump.find(addr), "$absent at f$step")
                }
                val eng = dump.find(engineAddr)
                if (eng != null && step <= 25423) {
                    assertEquals(0, eng.u16(MAP_SPRITE_PAL_OVERRIDE), "override at f$step")
                }
            }
            // No B or START press occurs in f10297..25580.
            for (step in 10297..25580) {
                val k = inputs[step - 1]
                assertEquals(0, k and (B_BUTTON or START), "no skip key at f$step")
            }
            // Every gProcScr_Talk in PC-cap1 ends before REMA and before EventEngine_OnEnd f25422.
            for ((talkEnd, remaStep) in TALK_END_TO_REMA) {
                assertNotNull(procsAt(talkEnd).find("gProcScr_Talk"), "talk alive at f$talkEnd")
                assertNull(
                    procsAt(talkEnd + 1).find("gProcScr_Talk"),
                    "talk gone at f${talkEnd + 1}",
                )
                assertNull(
                    procsAt(remaStep).find("gProcScr_Talk"),
                    "talk gone before REMA f$remaStep",
                )
            }
            for (step in 25418..25423) {
                assertNull(procsAt(step).find("gProcScr_Talk"), "talk at f$step")
                assertNull(procsAt(step).find("gProcScr_TalkSkipListener"), "skip at f$step")
            }

            // OBJ sprite palettes 27..31 match standard palettes before f25422 and at every
            // non-battle dump in 10297..25580 (only FIGHT f15110..15480 swaps them temporarily).
            val ew10300 = ewram(10300)
            val ew25420 = ewram(25420)
            val ew25422 = ewram(25422)
            for (i in 0 until 5 * 16) {
                val at = PALETTE_BUF + (27 * 16 + i) * 2
                assertEquals(u16(ew10300, at), u16(ew25420, at), "obj pal 27..31[$i] f25420")
                assertEquals(u16(ew25420, at), u16(ew25422, at), "obj pal 27..31[$i] f25422")
            }
            for (step in run.dumps.values) {
                if (step !in 10297..25580 || step in 15110..15480) continue
                val ew = ewram(step)
                for (i in 0 until 5 * 16) {
                    val at = PALETTE_BUF + (27 * 16 + i) * 2
                    assertEquals(u16(ew10300, at), u16(ew, at), "obj pal 27..31[$i] at f$step")
                }
            }
        }

        fun assertEventEndTeardown() {
            val ew20 = ewram(25420)
            val iw20 = iwram(25420)
            val ew22 = ewram(25422)
            val iw22 = iwram(25422)
            assertEquals(TALK_FONT_ADDR, u32(ew20, G_ACTIVE_FONT - EWRAM), "r25420 talk font")
            assertEquals(DEFAULT_FONT_ADDR, u32(ew22, G_ACTIVE_FONT - EWRAM), "r25422 default font")
            assertEquals(0, iw20[S_MODIFIED_PALETTE - IWRAM].toInt() and 0xFF, "r25420 sModPal")
            assertEquals(1, iw22[S_MODIFIED_PALETTE - IWRAM].toInt() and 0xFF, "r25422 sModPal")

            val hw = hardwareFrom(ew20, "f25400.io.bin")
            val engine = ProcEngine().also { it.init() }
            val keys = KeyStatus()
            val clock = GameClock()
            val loop = GameLoop(engine, keys, clock, { 0 })
            loop.bmSt.decode(gbmWatch.step(25420), 0)
            assertEquals(1, loop.lock, "seed lock 1")
            val playSt = PlaySt().apply { decode(ew20, PLAY_ST - EWRAM) }
            val sprites = UnitSprites(rom, hw, loop.bmSt)
            val scene = newTalkScene(
                hw, engine, keys, { clock.frames.toInt() }, { playSt.textSpeed }, {},
            )
            // Start from talkFont active just like r25420 (gActiveFont == sTalkFont).
            scene.talk.initTalk(0x80, 2, false)
            assertNotEquals(scene.text.defaultFont, scene.text.activeFont, "talk font active")

            val gfx = ChapterMapGfx(rom, ChapterData(rom), hw, playSt)
            gfx.resetText = { scene.text.resetText() }
            gfx.loadUiFrameGraphics = {
                UiFrame.loadUiFrameGraphics(hw, rom, playSt.windowColor)
            }
            gfx.loadIconPalettes = { ItemIcon.loadIconPalettes(hw, rom, it) }

            val eventTextHost = EventTextTalkHost(
                engine, scene.talk, scene.bubble, scene.face, scene, scene,
                readTextSpeed = { playSt.textSpeed },
                writeTextSpeed = { playSt.textSpeed = it },
                changeSpritePalette = { sprites.changeUnitSpritePalette(it) },
                stringFromIndex = scene::stringFromIndex,
            )
            val eventText = EventText(EventState(), ScriptMemory { 0 }, eventTextHost)
            val hooks = object : EventEngineHooks {
                override fun resetTextFont() {
                    scene.text.setTextFont(null)
                    scene.text.initSystemTextFont()
                    UiFrame.loadUiFrameGraphics(hw, rom, playSt.windowColor)
                }

                override fun restoreAfterEvent5(mapSpritePalIdOverride: Int) {
                    gfx.readGameSaveCoreGfx()
                    gfx.unpackChapterMapPalette()
                    sprites.changeUnitSpritePalette(mapSpritePalIdOverride)
                }

                override fun resumeGameplay() {
                    loop.unlockGame()
                }

                override fun endEventTalk() {
                    eventTextHost.endTalk()
                    eventTextHost.endCgText()
                    eventTextHost.endBoxDialogue()
                }

                override fun eventEndCleanup(proc: EventEngineProc) {
                    eventText.endTextFaces(proc)
                }
            }
            val eventState = EventState()
            val eventEngine = EventEngine(engine, eventState, ScriptMemory { 0 }, hooks)
            val dumpProc20 = procsAt(25420).find("ProcScr_StdEventEngine")!!
            val evProc = eventEngine.create(
                dumpProc20.u32(0x30),
                dumpProc20.u8(0x41),
            )
            assertEquals(EV_EXEC_UNK5, evProc.execType)
            evProc.evStateBits = dumpProc20.u16(0x3C)
            evProc.mapSpritePalIdOverride = dumpProc20.u16(MAP_SPRITE_PAL_OVERRIDE)
            hw.modifiedPalette = false
            assertFalse(hw.modifiedPalette, "sync cleared before onEnd")

            engine.end(evProc.proc)

            assertEquals(
                scene.text.defaultFont,
                scene.text.activeFont,
                "activeFont reset at f25422",
            )
            assertTrue(hw.modifiedPalette, "palette sync requested at f25422")
            assertEquals(gbmWatch.u8(25422, 1), loop.lock, "gBmSt.lock at f25422")
            for (i in 0 until BgHardware.PALETTE_COLORS) {
                assertEquals(
                    u16(ew22, PALETTE_BUF + 2 * i),
                    hw.palette[i].toInt() and 0xFFFF,
                    "gPaletteBuffer[$i] at f25422",
                )
            }
            assertNull(engine.find(eventEngine.stdScript), "StdEventEngine gone at f25422")
            assertNull(engine.find(scene.talk.talkScript), "Talk gone at f25422")
            assertNull(engine.find(scene.skipListener.script), "SkipListener gone at f25422")
            assertNull(engine.find(eventTextHost.waitForFacesScript), "FaceWait gone at f25422")
        }

        fun assertTalkWindow1() {
            // Seed right before Event1B_TEXTSHOW (f10533 -> f10534 starts gProcScr_Talk,
            // f10536 Talk_OnInit starts gProcScr_TalkSkipListener, f10600 A sets instantScroll=1,
            // f10800 A hits TalkWaitForInput guard so instantScroll stays 0).
            val ew30 = ewram(10530)
            val iw30 = iwram(10530)
            val hw = hardwareFrom(ew30, "f10500.io.bin")
            val engine = ProcEngine().also { it.init() }
            val keys = KeyStatus()
            val playSt = PlaySt().apply { decode(ew30, PLAY_ST - EWRAM) }
            var clockNow = u32(iw30, G_GAME_CLOCK - IWRAM) + 3
            var skipBitSet = false
            val sprites = SpriteLayers()
            val scene = newTalkScene(
                hw, engine, keys, { clockNow }, { playSt.textSpeed }, { skipBitSet = true },
                sprites = sprites,
            )
            scene.lcg.set(u32(iw30, G_LCG - IWRAM))
            scene.talk.state.decode(iw30, S_TALK_STATE - IWRAM)

            val eventState = EventState()
            eventState.load(globalsWatch.step(10533), 0)
            val words = ScriptMemory { a ->
                val o = a - ROM_BASE
                (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
            }
            val events = EventEngine(engine, eventState, words)
            EventScrCore(
                eventState,
                words,
                flags = Fe8EventFlags.create(),
                rng = RngService(),
                playSt = playSt,
                keys = keys,
            ).registerAll(events)
            val host = EventTextTalkHost(
                engine, scene.talk, scene.bubble, scene.face, scene, scene,
                readTextSpeed = { playSt.textSpeed },
                writeTextSpeed = { playSt.textSpeed = it },
                stringFromIndex = scene::stringFromIndex,
            )
            EventText(eventState, words, host).registerOn(events)
            val seedEngine = procsAt(10533).find("ProcScr_StdEventEngine")!!
            val evProc = events.create(seedEngine.u32(0x30), seedEngine.u8(0x41))
            evProc.pEventIdk = seedEngine.u32(0x34)
            evProc.pEventCurrent = seedEngine.u32(0x38)
            evProc.evStateBits = seedEngine.u16(0x3C)
            evProc.evStallTimer = seedEngine.u16(0x3E)
            evProc.overwrittenTextSpeed = seedEngine.u8(0x40).toByte().toInt()
            evProc.activeTextType = seedEngine.u8(0x42)
            evProc.chapterIndex = seedEngine.u8(0x43)
            evProc.proc!!.scrCur = seedEngine.scrCur
            evProc.proc!!.idleCb = gbafe.proc.ProcFunc {
                events.onUpdate(it.state as EventEngineProc)
            }

            var sawInstantScroll = false
            var sawWaitGuard = false
            for (step in 10534..10820) {
                val cur = inputs[step - 1]
                val prev = inputs[step - 2]
                val nk = cur and prev.inv() and 0x3FF
                keys.newKeys = nk
                keys.heldKeys = cur
                clockNow++
                sprites.clear()

                engine.run(engine.root(3))
                engine.run(engine.root(5))
                scene.flushTileMoves()

                val wantProcs = procsAt(step)
                assertEquals(
                    wantProcs.findAll(ADDR_TALK).size,
                    engine.procs.count { it.script === scene.talk.talkScript },
                    "gProcScr_Talk count at f$step",
                )
                assertEquals(
                    wantProcs.findAll(ADDR_SKIP).size,
                    engine.procs.count { it.script === scene.skipListener.script },
                    "gProcScr_TalkSkipListener count at f$step",
                )
                val wantSkip = wantProcs.find(ADDR_SKIP)
                val mySkip = engine.find(scene.skipListener.script)
                if (wantSkip != null && mySkip != null) {
                    assertEquals(wantSkip.scrCur, mySkip.scrCur, "skip scrCur at f$step")
                    assertEquals(wantSkip.u8(0x28), mySkip.lockCnt, "skip lockCnt at f$step")
                }
                assertEquals(
                    wantProcs.findAll(ADDR_WAIT).size,
                    engine.procs.count { it.script === scene.talk.waitForInputScript },
                    "gProcScr_TalkWaitForInput count at f$step",
                )
                val wantEng = wantProcs.find("ProcScr_StdEventEngine")!!
                assertEquals(wantEng.u32(0x38), evProc.pEventCurrent, "event cur at f$step")

                if (step % 10 == 0) {
                    val iw = iwram(step)
                    val ew = ewram(step)
                    val wantState = TalkState().apply { decode(iw, S_TALK_STATE - IWRAM) }
                    assertEquals(
                        wantState.instantScroll,
                        scene.talk.state.instantScroll,
                        "instantScroll f$step",
                    )
                    assertEquals(
                        wantState.str - MSG_BASE,
                        scene.talk.state.str - TalkScene.MSG_ADDR,
                        "str offset at f$step",
                    )
                    assertEquals(
                        wantState.lineActive,
                        scene.talk.state.lineActive,
                        "lineActive f$step",
                    )
                    assertEquals(
                        wantState.printClock,
                        scene.talk.state.printClock,
                        "printClock f$step",
                    )
                    assertEquals(wantState.putLines, scene.talk.state.putLines, "putLines f$step")
                    assertEquals(wantState.config, scene.talk.state.config, "config f$step")
                    if (step == 10600) {
                        assertEquals(
                            1,
                            scene.talk.state.instantScroll,
                            "A sets instantScroll at f10600",
                        )
                        sawInstantScroll = true
                    }
                    if (step == 10800) {
                        assertEquals(
                            0,
                            scene.talk.state.instantScroll,
                            "WaitForInput blocks fast at f10800",
                        )
                        sawWaitGuard = true
                    }
                    for (i in 0 until 0x400) {
                        assertEquals(
                            u16(ew, BG0_TM + 2 * i),
                            hw.mapBuffer(0)[i].toInt() and 0xFFFF,
                            "BG0[$i] at f$step",
                        )
                        assertEquals(
                            u16(ew, BG1_TM + 2 * i),
                            hw.mapBuffer(1)[i].toInt() and 0xFFFF,
                            "BG1[$i] at f$step",
                        )
                    }
                }
            }
            assertTrue(sawInstantScroll, "verified instantScroll = 1 at f10600")
            assertTrue(sawWaitGuard, "verified WaitForInput guard at f10800")
            assertFalse(skipBitSet, "no B/START skip in PC-cap1")
        }

        fun assertTalkWindow5Tail() {
            // Dense tail f25390..f25405: TalkWaitForInput + A at f25400, then Talk_OnIdle breaks
            // and Talk_OnEnd calls endTalkSkipListener() at f25404.
            val ew90 = ewram(25390)
            val iw90 = iwram(25390)
            val hw = hardwareFrom(ew90, "f25400.io.bin")
            val engine = ProcEngine().also { it.init() }
            val keys = KeyStatus()
            val playSt = PlaySt().apply { decode(ew90, PLAY_ST - EWRAM) }
            var clockNow = u32(iw90, G_GAME_CLOCK - IWRAM)
            val sprites = SpriteLayers()
            val scene = newTalkScene(
                hw, engine, keys, { clockNow }, { playSt.textSpeed }, { error("unexpected skip") },
                sprites = sprites,
            )
            scene.talk.initTalk(0x80, 2, false)
            scene.talk.state.decode(iw90, S_TALK_STATE - IWRAM)
            // Remap sTalkState.str from EWRAM sMsgString buffer into TalkScene.MSG_ADDR.
            scene.talk.state.str =
                TalkScene.MSG_ADDR + (scene.talk.state.str - MSG_BASE)
            val msgLen = ew90.indexOfFirstFrom(BUF_EWRAM, 0) - BUF_EWRAM + 1
            ew90.copyInto(scene.messages.buffer, 0, BUF_EWRAM, BUF_EWRAM + msgLen)
            for (i in 0..1) {
                val base = S_TALK_TEXT - IWRAM + 8 * i
                scene.talk.texts[i].chrPosition = u16(iw90, base)
                scene.talk.texts[i].x = iw90[base + 2].toInt() and 0xFF
                scene.talk.texts[i].colorId = iw90[base + 3].toInt() and 0xFF
                scene.talk.texts[i].tileWidth = iw90[base + 4].toInt() and 0xFF
            }

            // Reconstruct the live procs of f25390: Talk@5 (blocked by TalkWaitForInput@3),
            // TalkSkipListener@2, and two idle faces.
            val talkProc = engine.start(scene.talk.talkScript, 3)
            talkProc.scrCur = 5
            talkProc.sleepTime = 0
            talkProc.idleCb = gbafe.proc.ProcFunc { scene.talk.talkOnIdle(it) }
            val skipProc = scene.skipListener.start()
            val waitProc = engine.startBlocking(scene.talk.waitForInputScript, talkProc)!!
            val dumpWait = procsAt(25390).find(ADDR_WAIT)!!
            waitProc.scrCur = dumpWait.scrCur
            waitProc.sleepTime = 0
            (waitProc.state as fe8.game.scene.TalkWaitState).x = dumpWait.u16(0x64)
            (waitProc.state as fe8.game.scene.TalkWaitState).y = dumpWait.u16(0x66)
            waitProc.idleCb = gbafe.proc.ProcFunc { scene.talk.talkWaitForInputOnIdle(it) }
            skipProc.idleCb = gbafe.proc.ProcFunc { scene.skipListener.onIdle(it) }
            var gSlot = 0
            for (slot in scene.talk.state.faces.indices) {
                if (scene.talk.state.faces[slot] != 0) {
                    val fp = engine.start(scene.face.faceScript, 3)
                    fp.scrCur = 4
                    fp.sleepTime = 0
                    fp.idleCb = gbafe.proc.ProcFunc {}
                    (fp.state as fe8.game.scene.FaceState).faceSlot = gSlot
                    scene.face.gFaces[gSlot] = fp
                    scene.talk.state.faces[slot] = fe8.game.scene.TalkFace.FACE_HANDLE + gSlot
                    gSlot++
                }
            }

            for (step in 25391..25405) {
                val cur = inputs[step - 1]
                val prev = inputs[step - 2]
                keys.newKeys = cur and prev.inv() and 0x3FF
                keys.heldKeys = cur
                clockNow++
                sprites.clear()

                engine.run(engine.root(3))
                engine.run(engine.root(5))
                scene.flushTileMoves()

                val wantProcs = procsAt(step)
                assertEquals(
                    wantProcs.findAll(ADDR_TALK).size,
                    engine.procs.count { it.script === scene.talk.talkScript },
                    "tail Talk at f$step",
                )
                assertEquals(
                    wantProcs.findAll(ADDR_SKIP).size,
                    engine.procs.count { it.script === scene.skipListener.script },
                    "tail SkipListener at f$step",
                )
                assertEquals(
                    wantProcs.findAll(ADDR_WAIT).size,
                    engine.procs.count { it.script === scene.talk.waitForInputScript },
                    "tail WaitForInput at f$step",
                )
                val iw = iwram(step)
                val wantState = TalkState().apply { decode(iw, S_TALK_STATE - IWRAM) }
                assertEquals(
                    wantState.instantScroll,
                    scene.talk.state.instantScroll,
                    "tail instant f$step",
                )
            }
            assertNull(engine.find(scene.talk.talkScript), "Talk ended by f25404")
            assertNull(engine.find(scene.skipListener.script), "SkipListener ended by f25404")
        }

        fun assertDisplayCursorWindows() {
            var clockNow = 0
            val bmSt = BmSt()
            val layers = SpriteLayers()
            val cursor = MapCursor(rom, bmSt, layers)
            cursor.gameClock = { clockNow }
            val cursorScript = createProcScrEventDisplayCursor(
                putMapCursor = { x, y, kind -> cursor.put(x, y, kind) },
            )

            for (w in CURSOR_WINDOWS) {
                val engine = ProcEngine().also { it.init() }
                val parent = engine.start(
                    gbafe.proc.ProcScript("parent", listOf(gbafe.proc.ProcScripts.block())),
                    3,
                )
                val p = engine.start(cursorScript, parent)
                val st = p.state as MapCursorProc
                st.x = w.x
                st.y = w.y
                st.subcmd = w.subcmd
                st.timer = 0
                assertNull(procsAt(w.start - 1).find(ADDR_CURSOR), "cursor absent f${w.start - 1}")

                for (step in w.start..w.end) {
                    val dump = procsAt(step).find(ADDR_CURSOR)
                    assertNotNull(dump, "ProcScr_EventDisplayCursor alive at f$step")
                    val d = dump!!
                    assertEquals(w.x, d.u16(0x64).toShort().toInt(), "x at f$step")
                    assertEquals(w.y, d.u16(0x66).toShort().toInt(), "y at f$step")
                    assertEquals(w.subcmd, d.u16(0x68), "subcmd at f$step")
                    assertEquals(0, d.u16(0x6A), "timer at f$step")
                    assertEquals(1, d.scrCur, "scrCur at f$step")

                    if (step in w.oamSteps) {
                        val ew = ewram(step)
                        val iw = iwram(step)
                        val dumpClock = u32(iw, G_GAME_CLOCK - IWRAM)
                        bmSt.decode(ew, G_BM_ST - EWRAM)
                        clockNow = dumpClock - 1
                        layers.clear()
                        engine.run(engine.root(3))
                        val oam = OamBuffer()
                        layers.pushLayerObjects(4) {
                            oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                        }
                        assertEquals(4, oam.hiPut / 4, "cursor 4 OAM objs at f$step")
                        val hwOam = File(runDir, "f%05d.oam.bin".format(step)).readBytes()
                        for (i in 0 until 4) {
                            for (attr in 0..2) {
                                assertEquals(
                                    u16(hwOam, i * 8 + attr * 2),
                                    oam.buffer[i * 4 + attr].toInt() and 0xFFFF,
                                    "f$step OAM[$i] attr$attr",
                                )
                            }
                        }
                    } else {
                        layers.clear()
                        engine.run(engine.root(3))
                    }
                    assertEquals(d.scrCur, p.scrCur, "sim scrCur at f$step")
                }

                engine.endEach(cursorScript)
                assertNull(engine.find(cursorScript), "sim cursor ended f${w.end + 1}")
                assertNull(procsAt(w.end + 1).find(ADDR_CURSOR), "ref cursor ended f${w.end + 1}")
            }
        }

        private fun ByteArray.indexOfFirstFrom(start: Int, target: Byte): Int {
            for (i in start until size) if (this[i] == target) return i
            return -1
        }

        private val PALETTE_BUF = sym["gPaletteBuffer"].address - EWRAM
        private val BG0_TM = sym["gBG0TilemapBuffer"].address - EWRAM
        private val BG1_TM = sym["gBG1TilemapBuffer"].address - EWRAM
        private val G_BM_ST = sym["gBmSt"].address
        private val PLAY_ST = sym["gPlaySt"].address
        private val G_ACTIVE_FONT = sym["gActiveFont"].address
        private val DEFAULT_FONT_ADDR = sym["gDefaultFont"].address
        private val TALK_FONT_ADDR = sym["sTalkFont"].address
        private val S_MODIFIED_PALETTE = sym["sModifiedPalette"].address
        private val S_TALK_STATE = sym["sTalkStateCore"].address
        private val S_TALK_TEXT = sym["sTalkText"].address
        private val G_GAME_CLOCK = sym["gGameClock"].address
        private val G_LCG = sym["gLCGRNValue"].address
        private val MSG_BASE = sym["sMsgString"].address
        private val BUF_EWRAM = MSG_BASE - EWRAM
    }

    private class CursorWindow(
        val start: Int,
        val end: Int,
        val x: Int,
        val y: Int,
        val subcmd: Int,
        val oamSteps: Set<Int> = emptySet(),
    )

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val ROM_BASE = 0x08000000
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000

        const val MAP_SPRITE_PAL_OVERRIDE = 0x44
        const val B_BUTTON = 2
        const val START = 8

        const val ADDR_SKIP = 0x08591340
        const val ADDR_TALK = 0x08591358
        const val ADDR_WAIT = 0x085913F0
        const val ADDR_CURSOR = 0x08591F08

        val ABSENT_TEARDOWN_PROCS = listOf(
            "gProcScr_CgTextMain",
            "gProcScr_BoxDialogue",
            "ProcScr_TalkBoxIdle",
            "ProcScr_MergeBoxDialogue",
            "ProcScr_BoxDialogueDrawTextExt",
            "gUnknown_08A01800",
            "gProcScr_BKSEL",
            "sProc_Menu",
            "sProc_MenuMain",
        )

        val TALK_END_TO_REMA = listOf(
            13004 to 13006,
            15004 to 15006,
            16003 to 16005,
            20203 to 20205,
            25403 to 25405,
        )

        val CURSOR_WINDOWS = listOf(
            CursorWindow(10436, 10495, 2, 2, 0),
            CursorWindow(13103, 13162, 2, 5, 1),
            CursorWindow(15558, 15617, 2, 3, 1, setOf(15600)),
            CursorWindow(16211, 16270, 2, 2, 0),
            CursorWindow(20269, 20328, 12, 9, 1, setOf(20300)),
        )
    }
}
