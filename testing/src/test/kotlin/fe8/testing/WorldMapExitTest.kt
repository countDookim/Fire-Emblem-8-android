package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineCallback
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.GMapTextProc
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapMuEntryProc
import fe8.game.worldmap.GmapRmProc
import fe8.game.worldmap.WmFaceCtrlState
import fe8.game.worldmap.WmFaceHolderState
import fe8.game.worldmap.WmHblankStatus
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmTextHost
import fe8.game.worldmap.WorldMapExit
import fe8.game.worldmap.WorldMapFace
import fe8.game.worldmap.WorldMapHblank
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.worldmap.WorldMapMainScript
import fe8.game.worldmap.WorldMapMuEntry
import fe8.game.worldmap.WorldMapRm
import fe8.game.worldmap.WorldMapScroll
import fe8.game.worldmap.WorldMapText
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The world map exit, steps 9271-9313 of `ram_trace_prologue`: the end of the beginning event
 * (`ENDA`), `WorldMap_PostBeginningEvent`, the chapter intro event of the second engine run
 * (`EVBIT_MODIFY`, `ENUT`, `ENDA`), `WorldMap_WaitForChapterIntroEvents`, the label 25 sleep,
 * `WorldMap_Destruct`, the wrapper's end and `EndWM` / `sub_8009E28` of `GameControl`.
 *
 * Seed: the r09270 dump (GmData, LCD buffer) and the `procs` watch at 9270 for the fields of the
 * procs the exit code touches. The real procs are the game control stand-in (its commands sit at
 * the original's positions), the wrapper, `ProcScr_WorldMapMain` (prefix of ten commands, then
 * [WorldMapMainScript]), the event engine, portrait controller and holder, text handler, entry
 * band, big map and its direct updater. The other world map procs (screen, route, units, MUs,
 * dots, talk) are not created: the tree comparison covers only the procs above. The opcodes BE
 * (wait for the dot) and A0 (remove unit) are test stand-ins that advance exactly as the traced
 * `pEventCurrent` shows (D11-wm8e owns them), so the unit slots of `gGMData` are not compared.
 *
 * Lag: the original's frame that runs `WorldMap_PostBeginningEvent` spans steps 9286-9288 (its
 * snapshots at 9286/9287 show the work half done); the port runs one frame and compares at 9288.
 * Step 9314 (inside `StartBattleMap`) is not compared.
 */
class WorldMapExitTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty()

    @Test
    @VerifiesPort(
        "WorldMap_PostBeginningEvent", "WorldMap_CallIntroEvent", "CallChapterWMIntroEvents",
        "WorldMap_WaitForChapterIntroEvents", "WorldMap_Destruct", "ResetFaces", "EndWMFaceCtrl",
        "EndGmMuEntry", "EndGmapRM", "HideWmText",
    )
    fun `the world map exit matches the capture`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val playStTrace = run.watch("gplayst")
        val flagTrace = run.watch("flags")
        val bytes = romFile.readBytes()
        val rom = RomView.ofRom(bytes)
        val mem = ScriptMemory { a -> rom.u16(a) }
        val iwram = run.region("r09270", "iwram").copyOf(0x8000)
        val gm = GmData(iwram, RngService())
        val nodes = WmNodeData(rom)

        val engine = ProcEngine()
        engine.init()
        val hw = BgHardware()
        hw.lcd.readFrom(run.region("r09270", "iwram"), LCD_BUFFER)
        val playSt = PlaySt()
        playSt.chapterIndex = playStTrace.u8(9270, 0x0E)
        playSt.chapterModeIndex = playStTrace.u8(9270, 0x1B)
        playSt.chapterWeatherId = playStTrace.u8(9270, 0x15)
        val flags = Fe8EventFlags.create().also { it.copyIn(flagTrace.step(9270)) }

        // Face / talk services (the host of `EventEngine_OnEnd`'s `sub_800E640`).
        val rig = TalkRig(bytes, File(ref.root, "ram_trace_prologue"), engine, hw.vram, hw.palette)
        val eventTextHost = EventTextTalkHost(
            engine, rig.talk, rig.bubble, rig.face, rig, rig,
        ) { TalkRig.MSG_ADDR }
        val eventState = EventState()
        lateinit var eventText: EventText
        val events = EventEngine(
            engine, eventState, mem,
            object : EventEngineHooks {
                override fun blendY() = hw.lcd.blendY
                override fun chapterIndex() = playSt.chapterIndex
                override fun endEventTalk() = eventTextHost.endTalk()
                override fun eventEndCleanup(proc: EventEngineProc) = eventText.endTextFaces(proc)
            },
        )
        eventText = EventText(eventState, mem, eventTextHost)
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, flags, RngService(), playSt, KeyStatus(),
        )
        var step = 9270
        fun standIn(waits: Boolean) = EventHandler { proc ->
            val at = ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_StdEventEngine")
            when {
                at != null && at.u32(0x38) == proc.pEventCurrent -> EVC_STOP_YIELD
                waits -> EVC_ADVANCE_YIELD
                else -> EVC_ADVANCE_CONTINUE
            }
        }
        events.register(0x01, core.event01End)
        events.register(0x02, core.event02EvBitAndIdMod)
        events.register(0x10, core.event10ModifyEvBit)
        events.register(0xBE, standIn(true))
        events.register(0xA0, standIn(false))

        val hblank = WorldMapHblank()
        val faces = WorldMapFace(engine, hw, gm, rom, rig.face, rig, rig)
        faces.setupFaceConfig()
        val wmText = WorldMapText(
            engine, hw, WmHblankStatus(), SpriteLayers(),
            object : WmTextHost {
                override fun endTalk() {}
                override fun initSpriteTalk(chr: Int, pal: Int, lines: Int) {}
                override fun startTalkMsg(x: Int, y: Int, msg: Int) {}
                override fun setTalkPrintDelay(delay: Int) {}
                override fun setTalkFlag(flag: Int) {}
            },
        )
        val muEntry = WorldMapMuEntry(engine, hw, rom, hblank)
        val rm = WorldMapRm(engine, hw, rom, gm)
        val scroll = WorldMapScroll(engine, gm, nodes)

        // ProcScr_WorldMapMain: 10 prefix commands (the REPEAT at 9), then the real tail.
        lateinit var exit: WorldMapExit
        var armed = false
        val tail = WorldMapMainScript()
        val mainCmds = listOf(setEndCb(ProcFunc { exit.destruct(it) })) +
            List(6) { call(ProcFunc { }) } + listOf(
            label(0), label(1),
            repeat(ProcFunc { if (armed) exit.postBeginningEvent(it) }),
        )
        tail.funcs["WorldMap_CallIntroEvent"] = ProcFunc { exit.callIntroEvent(it) }
        tail.funcs["WorldMap_WaitForChapterIntroEvents"] =
            ProcFunc { exit.waitForChapterIntroEvents(it) }
        tail.funcs["WorldMap_HideEverything"] = ProcFunc { exit.hideEverything() }
        tail.funcs["ResetWorldMapScreen"] = ProcFunc { exit.resetWorldMapScreen() }
        tail.funcs["NULL_080B9F08"] = ProcFunc { }
        val mainScript = ProcScript(
            "ProcScr_WorldMapMain", mainCmds + tail.commands(),
        ) { WorldMapMainProc() }
        val fadeIn = ProcScript("ProcScr_BmFadeIN", listOf(block()))
        val wm = WorldMapMain(engine, playSt, gm, mainScript, fadeIn)
        exit = WorldMapExit(
            engine, hw, playSt, gm, nodes, rom, ChapterData(rom), events, mainScript, fadeIn,
            faces, wmText, muEntry, rm, scroll, { engine.find(mainScript) },
            WorldMapExit.Hooks(
                resetDialogueScreen = {
                    eventTextHost.clearTalkBubble()
                    eventTextHost.resetAllFaces()
                },
            ),
        )

        // GameControl's path: PROC_START_CHILD_BLOCKING at command 61, EndWM, sub_8009E28.
        val gcScript = ProcScript(
            "gProcScr_GameControl",
            List(61) { label(99) } + listOf(
                startChildBlocking { wm.wrapperScript },
                call(ProcFunc { exit.endWm(it) }),
                call(
                    ProcFunc {
                        hw.lcd.setBlendConfig(3, 0, 0, 16)
                        hw.lcd.setBlendTargetA(1, 1, 1, 1, 1)
                        hw.lcd.setBlendBackdropA(1)
                    },
                ),
                yield(),
                call(ProcFunc { }),
                yield(),
                block(),
            ),
        )

        // Wrapper path first, then the event engine (held by a callback during the warm-up).
        val seed = ProcArrayDump.ofWatch(trace, 9270, names)
        val engineSeed = checkNotNull(seed.find("ProcScr_StdEventEngine"))
        engine.start(gcScript, 3)
        fun frame() {
            for (i in 0 until 8) engine.run(engine.root(i))
        }
        repeat(4) { frame() }
        // The engine proc follows the main proc in the tree, as in the original.
        val vm = events.create(engineSeed.u32(0x30), EV_EXEC_WORLDMAP)
        vm.callback = EventEngineCallback { }
        armed = true
        val main = checkNotNull(engine.find(mainScript))
        (main.state as WorldMapMainProc).timer = seed.find("ProcScr_WorldMapMain")!!.u32(0x2C)
        val ctrl = faces.startWmFaceCtrl(main)
        muEntry.startGmapMuEntry()
        wmText.startWmTextHandler(main)
        rm.startGmapRm(0, 0, 0, null)
        repeat(8) { frame() }

        // Seeds of the fields.
        val ctrlSeed = checkNotNull(seed.find("ProcScr_WorldMapFaceCtrl"))
        val ctrlState = ctrl.state as WmFaceCtrlState
        ctrlState.timer = ctrlSeed.u16(0x2A)
        ctrlState.increment = ctrlSeed.u8(0x30).toByte().toInt()
        ctrlState.blendAmt = ctrlSeed.u8(0x31)
        val holderSeed = checkNotNull(seed.find("ProcScr_WorldMapFaceHolder"))
        val holder = ctrlState.faceHolderProc!!.state as WmFaceHolderState
        for (i in 0..1) {
            val at = 0x2C + 12 * i
            holder.faceWrapper[i].x = holderSeed.u16(at).toShort().toInt()
            holder.faceWrapper[i].y = holderSeed.u16(at + 2)
            holder.faceWrapper[i].faceId = holderSeed.u16(at + 8)
            holder.faceWrapper[i].unk0a = holderSeed.u8(at + 10).toByte().toInt()
        }
        val muSeed = checkNotNull(seed.find("gProcScr_GmapMuEntry2"))
        val mu = checkNotNull(engine.find(muEntry.script)).state as GmapMuEntryProc
        mu.flags0 = muSeed.u8(0x29) and 1 != 0
        mu.unk2a = muSeed.u8(0x2A)
        mu.unk2b = muSeed.u8(0x2B)
        mu.unk2c = muSeed.u8(0x2C)
        mu.unk2d = muSeed.u8(0x2D)
        mu.unk2e = muSeed.u8(0x2E)
        mu.unk2f = muSeed.u8(0x2F)
        mu.unk30 = muSeed.u16(0x30).toShort().toInt()
        mu.unk34 = muSeed.u32(0x34)
        mu.unk38 = muSeed.u32(0x38)
        mu.unk3c = muSeed.u32(0x3C)
        mu.unk40 = muSeed.u32(0x40)
        val rmSeed = checkNotNull(seed.find("ProcScr_GmapRM"))
        val rmState = checkNotNull(engine.find(rm.rmScript)).state as GmapRmProc
        rmState.flag = rmSeed.u8(0x29)
        rmState.x = rmSeed.u16(0x2E).toShort().toInt()
        rmState.y = rmSeed.u16(0x30).toShort().toInt()
        val textSeed = checkNotNull(seed.find("ProcScr_WorldMapTextHandler"))
        val textState = checkNotNull(engine.find(wmText.script)).state as GMapTextProc
        textState.drawAtBottom = textSeed.u8(0x29)
        textState.visible = textSeed.u8(0x2A)
        vm.callback = null
        vm.pEventCurrent = engineSeed.u32(0x38)
        vm.evStateBits = engineSeed.u16(0x3C)
        vm.evStallTimer = engineSeed.u16(0x3E)
        gm.stateRaw = GmData(run.region("r09270", "iwram").copyOf(0x8000), RngService()).stateRaw
        hw.lcd.readFrom(run.region("r09270", "iwram"), LCD_BUFFER)

        val symbol = HashMap<ProcScript, String>()
        for (s in listOf(
            events.stdScript, muEntry.script, rm.rmScript, rm.updateDirectScript, faces.ctrlScript,
            faces.holderScript, mainScript, wm.wrapperScript, gcScript,
        )) symbol[s] = s.name
        val watched = symbol.values.toSet()
        fun snapshot(): List<String> = engine.procs.filter { it.script != null }
            .mapNotNull { p ->
                val n = symbol[p.script!!] ?: return@mapNotNull null
                "$n@${p.scrCur}/l${p.lockCnt}"
            }.sorted()

        fun expected(at: Int): List<String> {
            val dump = ProcArrayDump.ofWatch(trace, at, names)
            return dump.live.filter { it.name in watched }
                .map { "${it.name}@${it.scrCur}/l${it.u8(0x28)}" }.sorted()
        }

        // Units 0-7 (0x10..0x2F) are cleared by the A0 handler of D11-wm8e, a stand-in here.
        fun gmDiff(dump: ByteArray) = (0 until 0xD4).filter {
            it !in 0x10 until 0x30 && dump[GmData.BASE + it] != iwram[GmData.BASE + it]
        }

        fun expectedConfig(dump: ByteArray) = (0 until 4).flatMap {
            listOf(dump.u32(SFACE_CONFIG + 8 * it), dump.u32(SFACE_CONFIG + 8 * it + 4))
        }

        fun ourConfig() =
            (0 until 4).flatMap { listOf(rig.face.tileOffsetOf(it), rig.face.paletteIdOf(it)) }

        assertEquals(
            expectedConfig(run.region("r09270", "ewram")), ourConfig(), "face config at the seed",
        )
        assertEquals(expected(9270), snapshot(), "proc tree at the seed")

        for (s in 9271..9313) {
            step = s
            if (s in LAG_STEPS) continue
            frame()
            assertEquals(expected(s), snapshot(), "proc tree at $s")
            val dump = ProcArrayDump.ofWatch(trace, s, names)
            val t = dump.find("ProcScr_StdEventEngine")
            val ours = events.findStd()?.state as EventEngineProc?
            assertEquals(t != null, ours != null, "event engine exists at $s")
            if (t != null && ours != null) {
                assertEquals(
                    listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E), t.u8(0x41)),
                    listOf(ours.pEventCurrent, ours.evStateBits, ours.evStallTimer, ours.execType),
                    "engine pEventCurrent / evStateBits / evStallTimer / execType at $s",
                )
            }
            assertEquals(
                playStTrace.u8(s, 0x0E), playSt.chapterIndex and 0xFF, "chapterIndex at $s",
            )
            val badFlags = (0 until 384).filter {
                (flagTrace.u8(s, it shr 3) shr (it and 7) and 1 == 1) !=
                    flags.checkFlag(it + PERMANENT_FLAGS_BASE)
            }
            assertEquals(emptyList<Int>(), badFlags, "event flags at $s")
            val textTrace = dump.find("ProcScr_WorldMapTextHandler")
            val textOurs = engine.find(wmText.script)?.state as GMapTextProc?
            assertEquals(textTrace != null, textOurs != null, "text handler exists at $s")
            if (textTrace != null && textOurs != null) {
                assertEquals(
                    listOf(textTrace.u8(0x29), textTrace.u8(0x2A)),
                    listOf(textOurs.drawAtBottom, textOurs.visible), "text handler fields at $s",
                )
            }
            if (s == 9300) {
                val ew = run.region("f09300", "ewram")
                val iw = run.region("f09300", "iwram")
                assertEquals(emptyList<Int>(), gmDiff(iw), "gGMData bytes at $s")
                assertEquals(expectedConfig(ew), ourConfig(), "sFaceConfig at $s")
                val expectedLcd = BgHardware().lcd
                expectedLcd.readFrom(iw, LCD_BUFFER)
                assertEquals(
                    listOf(
                        expectedLcd.dispcnt, expectedLcd.bldcnt, expectedLcd.blendCoeffA,
                        expectedLcd.blendCoeffB, expectedLcd.blendY,
                    ),
                    listOf(
                        hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB,
                        hw.lcd.blendY,
                    ),
                    "display and blend registers at $s",
                )
            }
        }
        // r09330 (inside the battle map start): gGMData and the restored face layout.
        assertEquals(emptyList<Int>(), gmDiff(run.region("r09330", "iwram")), "gGMData at 9330")
        assertEquals(
            expectedConfig(run.region("r09330", "ewram")), ourConfig(), "sFaceConfig at 9330",
        )
    }

    private fun ByteArray.u16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)

    private companion object {
        const val LCD_BUFFER = 0x3080

        /** The `flags` watch is the permanent array: bit k is event flag k + 0x65. */
        const val PERMANENT_FLAGS_BASE = 0x65

        /** `sFaceConfig` (0x0202A68C) as an EWRAM offset. */
        const val SFACE_CONFIG = 0x2A68C

        /** Steps without a frame of their own (see the class KDoc). */
        val LAG_STEPS = setOf(9286, 9287)
    }
}
