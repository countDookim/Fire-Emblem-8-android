package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventBgm
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.scene.FaceState
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapRmBaPalAnimProc
import fe8.game.worldmap.GmapRmBorderProc
import fe8.game.worldmap.WmFaceCtrlState
import fe8.game.worldmap.WmFaceHolderState
import fe8.game.worldmap.WorldMapFace
import fe8.game.worldmap.WorldMapFaceEvents
import fe8.game.worldmap.WorldMapRmBaPalAnim
import fe8.game.worldmap.WorldMapRmBorder
import fe8.game.scene.TalkScene
import gbafe.core.rng.LcgRng
import gbafe.render.ApHost
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteObject
import fe8.game.worldmap.GmapLineFadeProc
import fe8.game.worldmap.GmapMuEntryProc
import fe8.game.worldmap.GmapRmProc
import fe8.game.worldmap.WmBigMapHooks
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WmTextFadeHooks
import fe8.game.worldmap.WorldMapBeginning
import fe8.game.worldmap.WorldMapBigMapEvents
import fe8.game.worldmap.WorldMapHblank
import fe8.game.worldmap.WorldMapLineFade
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.worldmap.WorldMapMuEntry
import fe8.game.worldmap.WorldMapPath
import fe8.game.worldmap.WorldMapRm
import fe8.game.worldmap.WorldMapScroll
import fe8.game.worldmap.WorldMapSpawnEvents
import fe8.game.worldmap.WorldMapText
import fe8.game.worldmap.WorldMapTextEvents
import fe8.game.worldmap.WorldMapTextFadeEvents
import fe8.game.worldmap.WmHblankStatus
import fe8.game.worldmap.WmTextHost
import fe8.game.worldmap.GMapTextProc
import fe8.game.scene.TalkState
import fe8.game.scene.TalkWaitState
import fe8.game.scene.TalkSpriteShiftState
import fe8.game.worldmap.GmapRmUpdateProc
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.proc.ProcScripts.block
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The opening of the Prologue world map, steps 4907-8110 of `ram_trace_prologue`, run through the
 * event VM: `EventScrWM_Prologue_Beginning` (started by `WorldMap_CallBeginningEvent`'s
 * `CallEvent`) drives the opcodes registered one by one below; the `GmapRM`, `GmapLineFade`,
 * `BmFadeIN` and `GmapMuEntry2` procs are started by the script, not by the test.
 *
 * From step 5496 (the joined run, D11-wm8) the same VM runs the portrait, camera scroll and nation
 * highlight commands with all handlers real (C4, C5, B6, B8, B9, BA, 1C, 1D, 0E): the portrait
 * controller and faces, `GmapRmUpdateExt`, the border and its blend pulse join the watched procs.
 * The original's frames can straddle several steps there; see the loop at the end of the test.
 *
 * Seeds: the `r04920` dump (GmData with the camera and `no_fade` poisoned, LCD buffer, palette
 * buffer, scanline buffer, BG0 tilemap: `WorldMap_Init` and the fade set them before 4907 and
 * nothing the script runs changes them before 4944). Port frame
 * `n` is step `4907 + n` up to 4943; steps 4944-4947 are lag frames of one original callback
 * (`GmapRm_StartUpdateDirect`, whose snapshots show a half-run frame), so frame `n >= 37` is
 * step `4911 + n`.
 */
class WorldMapEventHostTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty()

    @Test
    @VerifiesPort(
        "EventC2_WmNoFade", "EventC3_SpawnLord", "GetGmMuEntryFlag", "StartGmapLineFade",
        "IsGmapLineFadeActive", "GmapLineFade_Loop",
        "GmapLineFade_80C0358", "GmapLineFade_OnEnd", "EventAF_WmShowTextBox",
        "EventB1_WmTextBoxWait", "EventB2_WmFancyFade", "EventB3_WmFancyFadeWait",
        "EventB4_WmDisplayBigMap", "EventB6_WmMoveBigMap", "EventC6_WmDisplayText", "EventC7_",
        "StartWmTextMsg", "sub_80080D0", "sub_8008108", "sub_8008F3C",
        "sub_8008F64", "sub_8008FAC", "sub_8008FB4", "TalkSpritePrepNextChar",
        "InitSpriteTextFont", "InitSpriteText", "Event1D_TalkContinue",
        // The joined run 5495-8110, with the handlers of the sibling tests (camera, faces):
        "EventB8_WmShowBigMapHighlight", "EventB9_WmRemoveHighlightNationPart1",
        "EventBA_WmRemoveHighlightNationPart2", "EventC4_WmShowPortrait",
        "EventC5_WmClearPortrait", "Event0E_STAL",
    )
    fun `the opening script starts the map, fades and entry bands like the original`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val playStTrace = run.watch("gplayst")
        val rom = RomView.ofRom(romFile.readBytes())
        val iwram = run.region("r04920", "iwram").copyOf(0x8000)
        // Hardware state `WorldMap_Init` and the fade left (nothing changes it before 4944).
        val hwIwram = run.region("r04920", "iwram")
        val ewram = run.region("r04920", "ewram")
        // Poison what the script writes (the camera 0,0 to 32,8, `no_fade`), so a missing write
        // fails against the dumps.
        val seedGm = GmData(iwram, RngService())
        seedGm.xCamera = 0x7F
        seedGm.yCamera = 0x7F
        seedGm.stateRaw = seedGm.stateRaw and 0x80.inv()

        val engine = ProcEngine()
        engine.init()
        val gm = GmData(iwram, RngService())
        val hw = BgHardware()
        // Screen blocks as the capture's BGCNT registers set them.
        val bgcnt = run.region("f05100", "io")
        for (bg in 0..3) hw.setMapDataOffset(bg, ((bgcnt.u16(8 + 2 * bg) shr 8) and 0x1F) * 0x800)
        hw.initBmBgLayers(false)
        hw.lcd.readFrom(hwIwram, LCD_BUFFER)
        for (i in 0 until 512) hw.palette[i] = ewram.u16(PALETTE_BUFFER + 2 * i).toShort()
        for (i in hw.tilemaps[0].indices) hw.tilemaps[0][i] = ewram.u16(BG0_TILEMAP + 2 * i).toShort()
        val hblank = WorldMapHblank()
        for (i in hblank.buffer.indices) hblank.buffer[i] = ewram.u16(HB_BUFFER + 2 * i).toShort()
        for (i in 0..2) hblank.registers[i] = ewram.u32(HB_REGISTERS + 4 * i)
        hblank.page = ewram[HB_PAGE].toInt() and 0xFF
        hblank.status = ewram[HB_STATUS].toInt() and 0xFF

        val mem = ScriptMemory { a -> rom.u16(a) }
        val playSt = PlaySt()
        playSt.chapterIndex = 0x55
        playSt.chapterModeIndex = playStTrace.u8(4860, 0x1B)
        val flags = Fe8EventFlags.create().also { it.copyIn(run.watch("flags").step(4860)) }
        val eventState = EventState()
        val events = EventEngine(
            engine, eventState, mem,
            object : EventEngineHooks {
                override fun blendY() = hw.lcd.blendY
                override fun chapterIndex() = playSt.chapterIndex
            },
        )
        val nodes = WmNodeData(rom)
        val keyStatus = KeyStatus()

        // The talk system behind the world map text (sprite talk) on the same VRAM and palette.
        val romBytes = romFile.readBytes()
        val rig = TalkRig(romBytes, File(ref.root, "ram_trace_prologue"), engine, hw.vram, hw.palette)
        rig.talk.state.decode(hwIwram, TalkRig.STATE)
        val msgRoot = rig.trace.u32(romBytes, 0x0815D488 - 0x08000000) - 0x08000000
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - 0x08000000, msgRoot),
            0x0815D48C - 0x08000000,
        )
        rig.msg = messages.buffer
        val eventTextHost = EventTextTalkHost(
            engine, rig.talk, rig.bubble, rig.face, rig, rig,
        ) { msg ->
            messages.getStringFromIndex(msg)
            TalkRig.MSG_ADDR
        }
        val layers = SpriteLayers().also { it.clear() }
        val wmText = WorldMapText(
            engine, hw, WmHblankStatus(), layers,
            object : WmTextHost {
                override fun endTalk() = eventTextHost.endTalk()

                override fun initSpriteTalk(chr: Int, pal: Int, lines: Int) =
                    rig.talk.initSpriteTalk(chr, lines, pal)

                override fun startTalkMsg(x: Int, y: Int, msg: Int) =
                    eventTextHost.startTalkMsg(x, y, msg)

                override fun setTalkPrintDelay(delay: Int) = rig.talk.setTalkPrintDelay(delay)

                override fun setTalkFlag(flag: Int) = rig.talk.setTalkFlag(flag)
            },
        )
        // `ProcScr_WorldMapMain` (a root, 4899) and its text handler child (4901).
        val mainProc = engine.start(
            ProcScript("ProcScr_WorldMapMain", listOf(block(), end())) { null }, 3,
        )
        wmText.startWmTextHandler(mainProc)
        engine.run(engine.root(3)) // the handler's `PROC_YIELD`

        // Services.
        val muEntry = WorldMapMuEntry(engine, hw, rom, hblank)
        val rm = WorldMapRm(engine, hw, rom, gm)
        val fade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine)
        val lineFade = WorldMapLineFade(engine, hw.lcd, hblank) { fade.newFadeIn(it, null) }
        val scroll = WorldMapScroll(engine, gm, nodes)
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, flags, RngService(), playSt, keyStatus,
        )
        val bgm = EventBgm(eventState, mem, SoundWrapper(engine, null) { 0 })
        val spawn = WorldMapSpawnEvents(mem, gm) { _, _, _, _ -> error("slot already holds Eirika") }
        val textFade = WorldMapTextFadeEvents(
            mem, gbafe.render.ScreenFade(engine, hw.lcd),
            WmTextFadeHooks(
                gmMuEntryStartShow = { s, b -> muEntry.gmMuEntryStartShow(s, b) },
                gmMuEntryFlag = muEntry::getGmMuEntryFlag,
                startGmapLineFade = { lineFade.startGmapLineFade(it) },
                isGmapLineFadeActive = lineFade::isGmapLineFadeActive,
            ),
        )
        val wmTextEvents = WorldMapTextEvents(mem, wmText)
        // The big map border (nation highlight) and its blend pulse.
        val spriteCache = HashMap<Int, SpriteObject>()
        val apHost = object : ApHost {
            override fun objectAt(addr: Int) = spriteCache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, apHost)
        player.clearAll()
        val baPal = WorldMapRmBaPalAnim(engine, hw.lcd) { 1 }
        val border = WorldMapRmBorder(engine, hw, rom, player, baPal) { rm.gmapRmGetPosition() }
        // The portraits run on the production talk scene (its own face procs and palette loads).
        val scene = TalkScene(
            rom, romBytes, hw, engine, layers, keyStatus, { 0 },
            MsgStrings(romBytes, MsgHuffman(romBytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val faces = WorldMapFace(engine, hw, gm, rom, scene.face, scene, scene)
        faces.setupFaceConfig()
        val faceEvents = WorldMapFaceEvents(mem, faces)
        val bigMap = WorldMapBigMapEvents(mem, WmBigMapHooks.of(rm, border))
        val eventText = EventText(eventState, mem, eventTextHost)
        // Exactly the opcodes of steps 4907-8110.
        events.register(0x0E, core.event0eStal)
        events.register(0x10, core.event10ModifyEvBit)
        events.register(0x12, bgm.event12StartBgm)
        events.register(0x13, bgm.event13BgmFadeIn)
        events.register(0x1C, eventText.event1dTalkContinue)
        events.register(0x1D, eventText.event1dWaitForLockTalk)
        events.register(0x85, scroll.CameraEvents(mem).event85SetCameraOntoUnit)
        events.register(0xAF, textFade.eventAFWmShowTextBox)
        events.register(0xB1, textFade.eventB1WmTextBoxWait)
        events.register(0xB2, textFade.eventB2WmFancyFade)
        events.register(0xB3, textFade.eventB3WmFancyFadeWait)
        events.register(0xB4, bigMap.eventB4WmDisplayBigMap)
        events.register(0xB6, bigMap.eventB6WmMoveBigMap)
        events.register(0xB8, bigMap.eventB8WmShowBigMapHighlight)
        events.register(0xB9, bigMap.eventB9WmRemoveHighlightNationPart1)
        events.register(0xBA, bigMap.eventBAWmRemoveHighlightNationPart2)
        events.register(0xC4, faceEvents.eventC4WmShowPortrait)
        events.register(0xC5, faceEvents.eventC5WmClearPortrait)
        events.register(0xC6, wmTextEvents.eventC6WmDisplayText)
        events.register(0xC7, wmTextEvents.eventC7)
        events.register(0xC2, spawn.eventC2WmNoFade)
        events.register(0xC3, spawn.eventC3SpawnLord)

        // `WorldMap_CallBeginningEvent` starts the script and the entry bands (step 4907).
        val main = WorldMapMain(
            engine, playSt, gm, ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        val beginning = WorldMapBeginning(
            rom, ChapterData(rom), WorldMapPath(gm, WmPathData(rom)), events, {},
        )
        val proc = WorldMapMainProc()
        main.callBeginningEvent(
            proc, nodes, { flags.checkFlag(it) }, beginning.hooks(proc, faces = faces, muEntry = muEntry, mainProc = { mainProc }),
        )

        // The original starts the face controller inside the main proc's pass (step 4907), so it
        // has already run its first loop pass when the step ends.
        engine.run(engine.find(faces.ctrlScript))

        val symbol = HashMap<ProcScript, String>()
        for (s in listOf(
            events.stdScript, muEntry.script, rm.rmScript, rm.updateDirectScript, fade.script,
            lineFade.script, rm.updateExtScript, wmText.script, faces.ctrlScript, faces.holderScript,
            faces.extraFrameScript, border.script, baPal.script, rig.talk.talkScript, rig.skipScript,
            rig.talk.waitForInputScript, rig.talk.lockScript, rig.talk.spriteShiftClearScript,
            rig.talk.spriteShiftWaitScript, rig.talk.spriteShiftStepScript,
        )) symbol[s] = s.name
        val watched = symbol.values.toSet()

        // The port's E_FACE script has no camera-move wait (WorldMapFace.extraFrameScript): its
        // sleep and idle positions are the original's 3 (first step) and 6 (idle repeat).
        fun faceCursor(cur: Int) = when (cur) {
            1 -> 3
            4 -> 6
            else -> -cur
        }

        fun snapshot(): List<String> = engine.procs.filter { it.script != null }
            .mapNotNull { p ->
                val n = symbol[p.script!!] ?: return@mapNotNull null
                val cur = if (p.script === faces.extraFrameScript) faceCursor(p.scrCur) else p.scrCur
                "$n@$cur/l${p.lockCnt}/${p.parent?.script?.name ?: "root${p.rootIndex}"}"
            }.sorted()

        fun expectedSnapshot(step: Int): List<String> {
            val dump = ProcArrayDump.ofWatch(trace, step, names)
            return dump.live.filter { it.name in watched }.map { p ->
                val parent = dump.live.firstOrNull { it.address == p.u32(0x14) }
                "${p.name}@${p.scrCur}/l${p.u8(0x28)}/${parent?.name ?: "root${p.u32(0x14)}"}"
            }.sorted()
        }

        val borderAddress = names.entries.first { it.value == "ProcScr_GmapRmBorder1" }.key

        fun compareFields(step: Int, dump: ProcArrayDump) {
            dump.find("gProcScr_GmapMuEntry2")?.let { t ->
                val e = checkNotNull(engine.find(muEntry.script)).state as GmapMuEntryProc
                assertEquals(
                    listOf(
                        t.u8(0x29) and 1 != 0, t.u8(0x2A), t.u8(0x2B), t.u8(0x2C), t.u8(0x2D),
                        t.u8(0x2E), t.u8(0x2F), t.u16(0x30).toShort().toInt(), t.u32(0x34),
                        t.u32(0x38), t.u32(0x3C), t.u32(0x40),
                    ),
                    listOf(
                        e.flags0, e.unk2a, e.unk2b, e.unk2c, e.unk2d, e.unk2e, e.unk2f, e.unk30,
                        e.unk34, e.unk38, e.unk3c, e.unk40,
                    ),
                    "GmapMuEntry2 fields at $step",
                )
            }
            dump.find("ProcScr_GmapRM")?.let { t ->
                val r = checkNotNull(engine.find(rm.rmScript)).state as GmapRmProc
                assertEquals(
                    listOf(t.u8(0x29), t.u16(0x2E).toShort().toInt(), t.u16(0x30).toShort().toInt()),
                    listOf(r.flag, r.x, r.y),
                    "GmapRM flag/x/y at $step",
                )
            }
            dump.find("ProcScr_GmapLineFade")?.let { t ->
                val l = checkNotNull(engine.find(lineFade.script)).state as GmapLineFadeProc
                assertEquals(
                    listOf(t.u8(0x29), t.u16(0x2A).toShort().toInt(), t.u16(0x2C).toShort().toInt()),
                    listOf(l.unk29, l.unk2a, l.unk2c),
                    "GmapLineFade fields at $step",
                )
            }
            dump.find("ProcScr_WorldMapTextHandler")?.let { t ->
                val g = checkNotNull(engine.find(wmText.script)).state as GMapTextProc
                assertEquals(
                    listOf(t.u8(0x29), t.u8(0x2A)), listOf(g.drawAtBottom, g.visible),
                    "WorldMapTextHandler fields at $step",
                )
            }
            dump.find("gProcScr_TalkWaitForInput")?.let { t ->
                val w = checkNotNull(engine.find(rig.talk.waitForInputScript)).state as TalkWaitState
                assertEquals(
                    listOf(t.u16(0x64), t.u16(0x66), t.u16(0x68)), listOf(w.x, w.y, w.unk68),
                    "TalkWaitForInput x/y/unk68 at $step",
                )
            }
            dump.find("ProcScr_08591624")?.let { t ->
                val w = checkNotNull(engine.find(rig.talk.spriteShiftStepScript)).state as
                    TalkSpriteShiftState
                assertEquals(
                    listOf(t.u32(0x4C), t.u32(0x54), t.u32(0x58), t.u16(0x64).toShort().toInt()),
                    listOf(w.vram, w.rows, w.fill, w.step),
                    "sprite shift fields at $step",
                )
            }
            dump.find("ProcScr_GmapRmUpdateExt")?.let { t ->
                val x = checkNotNull(engine.find(rm.updateExtScript)).state as GmapRmUpdateProc
                // The timer is only set by `GmapRmUpdateExt_InitPosition`.
                val keep = if (t.scrCur < 6) listOf(0, 1, 2, 3, 4, 6) else (0..6).toList()
                val theirs = IntArray(7) { t.u16(0x2A + 2 * it).toShort().toInt() }
                val mine = intArrayOf(x.x0, x.y0, x.x1, x.y1, x.delay, x.timer, x.speed)
                assertEquals(
                    keep.map { theirs[it] }, keep.map { mine[it] }, "GmapRmUpdateExt fields at $step",
                )
            }
            dump.find("ProcScr_WorldMapFaceCtrl")?.let { c ->
                val ctrl = checkNotNull(engine.find(faces.ctrlScript)).state as WmFaceCtrlState
                assertEquals(
                    listOf(c.u16(0x2A), c.u8(0x30), c.u8(0x31)),
                    listOf(ctrl.timer, ctrl.increment and 0xFF, ctrl.blendAmt),
                    "WorldMapFaceCtrl timer / increment / blendAmt at $step",
                )
            }
            dump.find("ProcScr_WorldMapFaceHolder")?.let { p ->
                val holder = checkNotNull(engine.find(faces.holderScript)).state as WmFaceHolderState
                for (i in 0..1) {
                    val at = 0x2C + 12 * i
                    val w = holder.faceWrapper[i]
                    assertEquals(
                        listOf(
                            p.u16(at), p.u16(at + 2), if (p.u32(at + 4) == 0) 0 else 1,
                            p.u16(at + 8), p.u8(at + 10).toByte().toInt(),
                        ),
                        listOf(
                            w.x and 0xFFFF, w.y, if (w.faceProc == null) 0 else 1, w.faceId, w.unk0a,
                        ),
                        "face wrapper $i at $step",
                    )
                }
            }
            dump.find("gProcScr_E_FACE_ExtraFrame")?.let { f ->
                val st = checkNotNull(engine.find(faces.extraFrameScript)).state as FaceState
                assertEquals(
                    listOf(
                        f.u32(0x30), f.u16(0x34).toShort().toInt(), f.u16(0x36).toShort().toInt(),
                        f.u16(0x3C), f.u16(0x3E), f.u8(0x40), f.u8(0x41), f.u32(0x38),
                    ),
                    listOf(
                        st.displayBits, st.xPos, st.yPos, st.oam2, st.faceId, st.faceSlot,
                        st.spriteLayer, st.sprite?.id,
                    ),
                    "E_FACE fields at $step",
                )
            }
            val borders = dump.findAll(borderAddress)
            var mine = engine.findAfter(border.script, null)
            for (t in borders) {
                val b = checkNotNull(mine) { "border proc at $step" }
                val st = b.state as GmapRmBorderProc
                assertEquals(
                    listOf(t.u8(0x29), t.u8(0x2A), t.u8(0x2B), t.u16(0x30)),
                    listOf(st.flag, st.index, st.slot, st.timer),
                    "GmapRmBorder1 fields at $step",
                )
                mine = engine.findAfter(border.script, b)
            }
            dump.find("ProcScr_GmapRmBaPalAnim1")?.let { t ->
                val st = checkNotNull(engine.find(baPal.script)).state as GmapRmBaPalAnimProc
                assertEquals(
                    listOf(t.u8(0x29), t.u16(0x2A).toShort().toInt()), listOf(st.flag, st.timer),
                    "GmapRmBaPalAnim1 fields at $step",
                )
            }
            dump.find("ProcScr_BmFadeIN")?.let { t ->
                val f = fade.stateOf(checkNotNull(engine.find(fade.script)))
                assertEquals(
                    listOf(t.u8(0x29) != 0, t.u32(0x2C), t.u32(0x30), t.u32(0x34)),
                    listOf(f.whiteOut, f.timer, f.speed, f.mask),
                    "BmFadeIN fields at $step",
                )
            }
        }

        fun u32(b: ByteArray, at: Int) = b.u32(at)

        fun compareDump(step: Int) {
            val name = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val ew = run.region(name, "ewram")
            val iw = run.region(name, "iwram")
            val theirs = GmData(iw.copyOf(0x8000), RngService())
            assertEquals(
                listOf(theirs.xCamera, theirs.yCamera, theirs.stateRaw and 0x80, theirs.unitId(0),
                    theirs.unitState(0) and 2, theirs.unitLocation(0)),
                listOf(gm.xCamera, gm.yCamera, gm.stateRaw and 0x80, gm.unitId(0),
                    gm.unitState(0) and 2, gm.unitLocation(0)),
                "camera, no_fade and unit 0 id/location at $name",
            )
            val bad = hblank.buffer.indices.filter {
                hblank.buffer[it].toInt() and 0xFFFF != ew.u16(HB_BUFFER + 2 * it)
            }
            assertEquals(0, bad.size, "scanline buffer at $name, first bad ${bad.firstOrNull()}")
            assertEquals(
                listOf(
                    ew.u32(HB_REGISTERS), ew.u32(HB_REGISTERS + 4), ew.u32(HB_REGISTERS + 8),
                    ew[HB_PAGE].toInt() and 0xFF, ew[HB_STATUS].toInt() and 0xFF,
                ),
                listOf(
                    hblank.registers[0], hblank.registers[1], hblank.registers[2], hblank.page,
                    hblank.status,
                ),
                "scanline registers, page and status at $name",
            )
            val expected = BgHardware().lcd
            expected.readFrom(iw, LCD_BUFFER)
            assertEquals(
                listOf(
                    expected.dispcnt, expected.bldcnt, expected.blendCoeffA, expected.blendCoeffB,
                    expected.blendY,
                ),
                listOf(
                    hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB,
                    hw.lcd.blendY,
                ),
                "display and blend registers at $name",
            )
        }

        fun compareTalk(step: Int) {
            val name = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val iw = run.region(name, "iwram")
            val got = ByteArray(TalkState.SIZE)
            rig.talk.state.encode(got)
            for (i in 0 until TalkState.SIZE) {
                if (i in 0x18 until 0x38) continue
                assertEquals(iw[TalkRig.STATE + i], got[i], "sTalkState byte 0x%02X at $name".format(i))
            }
            val t = ByteArray(Text.SIZE)
            for (i in 0..1) {
                rig.talk.texts[i].serialize(t)
                for (k in 0 until Text.SIZE) {
                    assertEquals(iw[TalkRig.TEXTS + 8 * i + k], t[k], "sTalkText[$i] byte $k at $name")
                }
            }
            val font = ByteArray(0x18)
            rig.talk.font.serialize(font)
            for (k in 0 until 0x18) {
                assertEquals(iw[TalkRig.FONT + k], font[k], "sTalkFont byte $k at $name")
            }
        }

        fun compareTextVram(step: Int) {
            val name = "f%05d".format(step)
            val v = run.region(name, "vram")
            for (row in 0 until 4) {
                val at = WorldMapText.TEXT_VRAM + row * 0x400
                val bad = (0 until 0x380).filter { v[at + it] != hw.vram[at + it] }
                assertEquals(0, bad.size, "text OBJ tiles row $row at $name, first bad ${bad.firstOrNull()}")
            }
        }

        fun comparePortraitTiles(step: Int) {
            val vram = run.region("f%05d".format(step), "vram")
            assertEquals(
                vram.copyOfRange(0x5000, 0x6000).toList(),
                hw.vram.copyOfRange(0x5000, 0x6000).toList(),
                "BG portrait tiles at $step",
            )
        }

        fun compare(step: Int) {
            val dump = ProcArrayDump.ofWatch(trace, step, names)
            val t = checkNotNull(dump.find("ProcScr_StdEventEngine")) { "event engine at $step" }
            val ours = checkNotNull(events.findStd()).state as EventEngineProc
            assertEquals(
                listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E)),
                listOf(ours.pEventCurrent, ours.evStateBits, ours.evStallTimer),
                "pEventCurrent / evStateBits / evStallTimer at $step",
            )
            assertEquals(expectedSnapshot(step), snapshot(), "proc tree at $step")
            compareFields(step, dump)
        }

        fun frame() {
            layers.clear()
            rig.sprites.clear()
            for (i in 0..5) engine.run(engine.root(i))
        }

        fun compareFullDump() {
            // Full dump f05100: the big map's tiles, BG1/BG2 tilemaps, palette banks 5-8 and the BG
            // registers.
            hw.flushBackgrounds()
            val vram = run.region("f05100", "vram")
            val tileBytes = rom.decompressInto(IMG_EVENT_GMAP, ByteArray(0x10000), 0)
            val bandBytes = rom.decompressInto(WorldMapMuEntry.IMG_BAND, ByteArray(0x2000), 0)
            for ((range, what) in listOf(
                (0 until tileBytes) to "BG tile data",
                (0x6800 until 0x7000) to "BG1 tilemap",
                (0x7000 until 0x7800) to "BG2 tilemap",
                (BAND_TILES until BAND_TILES + bandBytes) to "band tiles",
            )) {
                val bad = range.filter { vram[it] != hw.vram[it] }
                assertEquals(
                    0, bad.size,
                    "$what at f05100: first bad ${bad.firstOrNull()}, last ${bad.lastOrNull()}, " +
                        bad.groupBy { it / 0x400 }.mapValues { it.value.size } +
                        " theirs ${vram.slice(0 until 16)} ours ${hw.vram.slice(0 until 16)}",
                )
            }
            val pal = run.region("f05100", "pal")
            for (i in 5 * 16 until 9 * 16) {
                assertEquals(pal.u16(2 * i), hw.paletteRam[i].toInt() and 0xFFFF, "palette entry $i")
            }
            // The sprite talk's object palette 2 (`InitSpriteTalk`).
            for (i in 18 * 16 until 19 * 16) {
                assertEquals(
                    pal.u16(2 * i), hw.palette[i].toInt() and 0xFFFF, "object palette entry $i",
                )
            }
            val io = run.region("f05100", "io")
            val ours = ByteArray(0x100)
            hw.lcd.flushTo(ours)
            for (bg in 0 until 4) {
                assertEquals(io.u16(8 + 2 * bg) and 3, ours.u16(8 + 2 * bg) and 3, "BG$bg priority")
                // BG0's VOFS is the HBlank copy of the scanline buffer, not the register state.
                if (bg == 0) continue
                assertEquals(io.u16(0x10 + 4 * bg) and 0x1FF, ours.u16(0x10 + 4 * bg) and 0x1FF, "BG$bg HOFS")
                assertEquals(io.u16(0x12 + 4 * bg) and 0x1FF, ours.u16(0x12 + 4 * bg) and 0x1FF, "BG$bg VOFS")
            }
        }

        compare(4907)
        val clock0 = rig.trace.clock(4920) - 4920
        for (step in 4908..TEXT_LAST) {
            if (step in LAG_STEPS) continue
            // One frame per non-lag step; the frame's inputs and clock are those of its end step.
            keyStatus.update(run.inputs[step - 1])
            rig.keys = keyStatus.newKeys
            rig.clock = clock0 + step
            frame()
            compare(step)
            if (step in DUMPS) compareDump(step)
            if (step in TALK_DUMPS) compareTalk(step)
            if (step == 5100 || step == 5400) compareTextVram(step)
            if (step == 5100) compareFullDump()
        }

        // 5496..8110: portraits, scrolls and highlights. A frame of the original can straddle
        // several steps (a long handler, e.g. the graphics load of B8 or C4, sits inside the event
        // engine's turn): the face controller, the first proc of the frame, ticks on the first
        // step of each frame, and the procs after the engine only run once the handler is done.
        // So the port runs one frame per tick of the controller's timer (the first step of the
        // original's frame) and compares at the last step before the next tick.
        fun ticked(step: Int): Boolean =
            ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_WorldMapFaceCtrl")!!.u16(0x2A) !=
                ProcArrayDump.ofWatch(trace, step - 1, names).find("ProcScr_WorldMapFaceCtrl")!!.u16(0x2A)
        var frames = 0
        var multi = 0
        var first = TEXT_LAST + 1
        while (first <= LAST_STEP) {
            check(ticked(first)) { "step $first starts no frame" }
            var last = first
            while (last < LAST_STEP && !ticked(last + 1)) last++
            keyStatus.update(run.inputs[first - 1])
            rig.keys = keyStatus.newKeys
            rig.clock = clock0 + first
            frame()
            frames++
            if (last > first) multi++
            compare(last)
            for (d in PORTRAIT_DUMPS) if (d in first..last) comparePortraitTiles(d)
            first = last + 1
        }
        assertEquals(LAG_STEPS_AFTER, multi, "frames spanning several steps")
        assertTrue(frames > 2400, "frames run: $frames")
    }

    /**
     * `EventC3_SpawnLord` on an emptied unit slot 0 (the real run finds Eirika there already and
     * returns early): it must call `SetGmCharUnit(0, CHARACTER_EIRIKA, -1, node)` with the
     * script's arguments, the node being the leader's location in the `r04920` dump.
     */
    @Test
    @VerifiesPort("EventC3_SpawnLord")
    fun `spawn lord on an empty slot passes the script arguments to SetGmCharUnit`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val run = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        val mem = ScriptMemory { a -> rom.u16(a) }
        val expectedNode =
            GmData(run.region("r04920", "iwram").copyOf(0x8000), RngService()).unitLocation(0)
        // The first 0xC3 command of the script, found by walking command lengths.
        val engine = ProcEngine()
        engine.init()
        val gm = GmData(ByteArray(0x8000), RngService())
        val calls = mutableListOf<List<Int>>()
        val spawn = WorldMapSpawnEvents(mem, gm) { a, b, c, d -> calls += listOf(a, b, c, d) }
        val script = ProcArrayDump.ofWatch(run.watch("procs"), 4907, ProcArrayDump.romNames())
            .find("ProcScr_StdEventEngine")!!.u32(0x30)
        var at = script
        while (mem.u16(at) ushr 8 != 0xC3) at += 2 * ((mem.u16(at) ushr 4) and 0xF)
        val proc = EventEngineProc()
        proc.pEventCurrent = at
        spawn.eventC3SpawnLord.run(proc)
        assertEquals(listOf(listOf(0, 1, -1, expectedNode)), calls)
        // The leader already in the slot: nothing is called.
        gm.setUnitId(0, 1)
        calls.clear()
        spawn.eventC3SpawnLord.run(proc)
        assertEquals(emptyList<List<Int>>(), calls)
    }

    private companion object {
        const val LCD_BUFFER = 0x3080

        /** VRAM offset of the entry bands' tiles. */
        const val BAND_TILES = 0x4C00

        /** ROM address of `Img_EventGmap`. */
        const val IMG_EVENT_GMAP = 0x08A99140
        const val PALETTE_BUFFER = 0x228A8
        const val BG0_TILEMAP = 0x22CA8
        const val HB_BUFFER = 0x1BE32
        const val HB_REGISTERS = 0x1C5B4
        const val HB_PAGE = 0x1C5CF
        const val HB_STATUS = 0x1BE30

        /** Steps inside a lag frame: `GmapRm_StartUpdateDirect` and `StartWmTextMsg`. */
        val LAG_STEPS = setOf(4944, 4945, 4946, 4947, 5103, 5104, 5105, 5191, 5221, 5281, 5311, 5371)

        /** Last step of the text phase (one frame per step); the next frame is at 5496. */
        const val TEXT_LAST = 5495

        /** Frames of the portrait/scroll phase that span more than one step (read from the capture). */
        const val LAG_STEPS_AFTER = 17

        /** Full dumps inside the portrait phase: the BG portrait tiles. */
        val PORTRAIT_DUMPS = setOf(6000, 6300, 6600, 6900, 7200, 7500)

        /** Last step compared. */
        const val LAST_STEP = 8110

        /** Steps with a RAM dump inside the opening window (blend and scanline state). */
        val DUMPS = setOf(4920, 4950, 4980, 5010, 5040, 5070, 5100)

        /** Dumps (every 30 steps) with the talk state of the text phase. */
        val TALK_DUMPS = (5100..5490 step 30).toSet() + 5100
    }
}

private fun ByteArray.u16(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
