package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.Mu
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EVENT_SCR_08592114
import fe8.game.event.EventBgm
import fe8.game.event.EventChangeMap
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventFade
import fe8.game.event.EventMoveToChapter
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextBg
import fe8.game.event.EventTextBgHost
import fe8.game.event.EventTextHost
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.mark
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.ScreenFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential verification of the Prologue ending scene opcodes (`PC-2`) against
 * `ram_trace_prologue_end` (`PC-cap1`):
 * - Ending start (`f4894..5000`): opcodes `12`, `05`, `0A`, `03`, `0C`, `17` (FADI/FADU),
 *   `08`, `1A` (`Event23_DisaleMapDisp` via `blockEachMarked`), `21` (`EventShowTextBgDirect`),
 *   `01`, `1B` (`TEXTSHOW`), `1D` (`TEXTEND`), plus `gMusicProc3Script` countdown to `f4945`.
 * - Ending tail (`f9406..9438`): `17` (FADI), `1B` (`REMA`), 11 `02` (`ENUT` `0xE0..0xC9`),
 *   `2A` (`Event2A_MoveToChapter` `MNC2(1)` -> `SetNextChapterId(1)`, `save_menu_type = 2`,
 *   `nextAction = GAME_ACTION_USR_SKIPPED`, `DeleteAll6CWaitMusicRelated`, `Sound_FadeOutBGM`),
 *   `01` (`ENDA` -> `EventScr_08592114` -> `EventScr_08592170`), `03` (`CHECK_EVBIT` 10/8/11),
 *   `19` (`CHECK_CHAPTER_NUMBER`), `06` (`SADD`), `25` (`LOMA(0xFFFF)` -> `unblockEachMarked` +
 *   clear `EV_STATE_GFXLOCKED`), and ending at `0x0859213C` (`f9438..9439`).
 */
class EndingSceneOpcodesDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val symbols = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `ending start opcodes f4894 to f5000 match PC-cap1`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")

        val procsTrace = run.watch("procs")
        val globalsTrace = run.watch("evglobals")
        val romBytes = romFile.readBytes()
        val ew93 = run.region("r04893", "ewram")
        val iw93 = run.region("r04893", "iwram")

        val engine = ProcEngine().also { it.init() }
        val keys = KeyStatus()
        val control = newGameControl(engine, keys)
        control.playSt.decode(ew93, PLAY_ST - EWRAM)
        val bm = BmSt().also { it.decode(ew93, BM_ST - EWRAM) }
        val lcd = LcdControl().also { it.readFrom(iw93, lcdOffset()) }
        val screenFade = ScreenFade(engine, lcd)
        val state = EventState().also { it.load(iw93, EventState.BASE_ADDRESS - IWRAM) }
        val flags = Fe8EventFlags.create().also { it.copyIn(iw93, FLAGS - IWRAM) }
        val words = scriptWords(romBytes)

        val preSong = half(ew93, SOUND_ST + 4 - EWRAM)
        if (ew93[SOUND_ST + 6 - EWRAM].toInt() != 0) {
            control.sound.startBgmCore(preSong, -1)
        }

        // Seed the idle ProcScr_Mu proc from f4893 (reuses blockEachMarked without editing Mu.kt).
        val muScript = ProcScript("ProcScr_Mu", listOf(mark(Mu.PROC_MARK_MU), block()))
        val muProc = engine.start(muScript, 5)
        muProc.lockCnt =
            ProcArrayDump.ofWatch(procsTrace, 4893, names).find("ProcScr_Mu")!!.u8(LOCK)

        var loadedBg = -1
        var talkMsg = -1
        var talkRunning = false

        val events = createEventEngine(engine, state, words, control.playSt, bm, screenFade)
        EventScrCore(
            state,
            words,
            flags = flags,
            rng = RngService(),
            playSt = control.playSt,
            keys = keys,
        ).registerAll(events)
        EventFade(words, screenFade).registerOn(events)
        EventBgm(state, words, control.sound).registerOn(events)
        EventTextBg(
            state,
            words,
            recordingBgHost { bg -> loadedBg = bg },
        ).registerOn(events)
        EventText(
            state,
            words,
            recordingTextHost(
                engine,
                isTalkActive = { talkRunning },
                onStartTalk = { msg ->
                    talkMsg = msg
                    talkRunning = true
                },
            ),
        ).registerOn(events)

        val evState = events.create(EVENT_PROLOGUE_ENDING_SCENE, EV_EXEC_CUTSCENE)

        // f4894: MUSC(SONG_VICTORY) -> gMusicProc3Script starts, advances to 0x089EF168.
        stepTree(engine, 3, 0)
        assertStepMatches(4894, run, procsTrace, globalsTrace, names, evState, state)
        assertNotNull(engine.find(control.sound.delayedSongScript), "f4894 gMusicProc3Script")
        val ew94 = run.region("r04894", "ewram")
        assertEquals(half(ew94, SOUND_ST + 4 - EWRAM), control.sound.songId, "f4894 songId")
        assertEquals(
            ew94[SOUND_ST + 6 - EWRAM].toInt() != 0,
            control.sound.isSongPlaying,
            "f4894 isSongPlaying",
        )

        // f4895: SVAL(2, 0x1D), CALL(0x089EE2E8), CHECK_EVBIT(8), BNE, FADI(16) -> locks on fade.
        stepTree(engine, 3, 0)
        assertStepMatches(4895, run, procsTrace, globalsTrace, names, evState, state)
        assertLcdAndFadeMatches(4895, run, procsTrace, names, engine, lcd, screenFade.toBlack)

        // f4896..f4911: ProcScr_FadeToBlack runs 16 frames, then unlocks StdEventEngine at f4911.
        for (frame in 4896..4911) {
            stepTree(engine, 3, 0)
            assertStepMatches(frame, run, procsTrace, globalsTrace, names, evState, state)
            assertLcdAndFadeMatches(frame, run, procsTrace, names, engine, lcd, screenFade.toBlack)
        }

        // f4912: LABEL(0x80D2), Event1A_TEXTSTART(1) -> Event23_DisaleMapDisp locks Mu.
        stepTree(engine, 3, 0)
        assertStepMatches(4912, run, procsTrace, globalsTrace, names, evState, state)
        assertEquals(1, evState.activeTextType, "f4912 activeTextType")
        assertEquals(
            ProcArrayDump.ofWatch(procsTrace, 4912, names).find("ProcScr_Mu")!!.u8(LOCK),
            muProc.lockCnt,
            "f4912 ProcScr_Mu lockCnt",
        )

        // f4913 and f4914 do not run the tree: the capture's delayCounter stays 30 while
        // Event21_TextBg decompresses BG_PLAIN_2 (0x1D), so f4915 is the next step.
        stepTree(engine, 3, 0)
        assertEquals(0x1D, loadedBg, "f4915 BG_PLAIN_2 loaded")
        assertStepMatches(4915, run, procsTrace, globalsTrace, names, evState, state)

        // f4916: FADU(16) -> starts ProcScr_FadeFromBlack, clears EV_STATE_FADEDIN.
        stepTree(engine, 3, 0)
        assertStepMatches(4916, run, procsTrace, globalsTrace, names, evState, state)
        assertLcdAndFadeMatches(4916, run, procsTrace, names, engine, lcd, screenFade.fromBlack)

        // f4917..f4932: ProcScr_FadeFromBlack runs 16 frames, unlocks StdEventEngine at f4932.
        for (frame in 4917..4932) {
            stepTree(engine, 3, 0)
            assertStepMatches(frame, run, procsTrace, globalsTrace, names, evState, state)
            assertLcdAndFadeMatches(
                frame, run, procsTrace, names, engine, lcd, screenFade.fromBlack,
            )
        }

        // f4933: ENDA pops CALL, TEXTSHOW(0x918) starts talk and yields to TEXTEND (0x089EF17C).
        stepTree(engine, 3, 0)
        assertEquals(0x918, talkMsg, "f4933 TEXTSHOW message id")
        assertStepMatches(4933, run, procsTrace, globalsTrace, names, evState, state)

        // f4934..f5000: TEXTEND waits on talk; gMusicProc3Script expires at f4945.
        for (frame in 4934..5000) {
            stepTree(engine, 3, 0)
            if (frame in 4943..4946) {
                assertEquals(
                    ProcArrayDump.ofWatch(procsTrace, frame, names)
                        .find("gMusicProc3Script") != null,
                    engine.find(control.sound.delayedSongScript) != null,
                    "f$frame gMusicProc3Script presence",
                )
            }
        }
        val ew45 = run.region("r04945", "ewram")
        assertNull(engine.find(control.sound.delayedSongScript), "f4945 gMusicProc3Script ended")
        assertEquals(half(ew45, SOUND_ST + 4 - EWRAM), control.sound.songId, "f4945 songId")
        assertEquals(
            ew45[SOUND_ST + 6 - EWRAM].toInt() != 0,
            control.sound.isSongPlaying,
            "f4945 isSongPlaying",
        )
        assertStepMatches(5000, run, procsTrace, globalsTrace, names, evState, state)
    }

    @Test
    @VerifiesPort("SetNextChapterId")
    fun `ending tail f9406 to f9438 runs REMA, ENUTx11, MNC2, and 08592114 to 08592170`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")

        val procsTrace = run.watch("procs")
        val globalsTrace = run.watch("evglobals")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val ew05 = run.region("r09405", "ewram")
        val iw05 = run.region("r09405", "iwram")

        val engine = ProcEngine().also { it.init() }
        val keys = KeyStatus()
        val control = newGameControl(engine, keys)
        control.playSt.decode(ew05, PLAY_ST - EWRAM)
        val bm = BmSt().also { it.decode(ew05, BM_ST - EWRAM) }
        val lcd = LcdControl().also { it.readFrom(iw05, lcdOffset()) }
        val screenFade = ScreenFade(engine, lcd)
        val state = EventState().also { it.load(iw05, EventState.BASE_ADDRESS - IWRAM) }
        val flags = Fe8EventFlags.create().also { it.copyIn(iw05, FLAGS - IWRAM) }
        val words = scriptWords(romBytes)

        val preSong = half(ew05, SOUND_ST + 4 - EWRAM)
        if (ew05[SOUND_ST + 6 - EWRAM].toInt() != 0) {
            control.sound.startBgmCore(preSong, -1)
        }

        // Seed gProcScr_GameControl at scrCur 81 (locked) and ProcScr_Mu (locked) from r09405.
        val dump05 = ProcArrayDump.ofWatch(procsTrace, 9405, names)
        val gcDump05 = dump05.find("gProcScr_GameControl")!!
        val gcProc = control.startGame()
        gcProc.scrCur = gcDump05.scrCur
        gcProc.lockCnt = gcDump05.u8(LOCK)
        val gcState = gcProc.state as GameControl.State
        gcState.nextAction = gcDump05.u8(GC_NEXT_ACTION)
        gcState.nextChapter = gcDump05.u8(GC_NEXT_CHAPTER)

        val muScript = ProcScript("ProcScr_Mu", listOf(mark(Mu.PROC_MARK_MU), block()))
        val muProc = engine.start(muScript, 5)
        muProc.lockCnt = dump05.find("ProcScr_Mu")!!.u8(LOCK)

        val chapters = ChapterData(rom)
        val map = BmMap(rom, chapters, bm).also { it.initChapterMap(control.playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(ew05) }
        val entityMaps = EntityBmMaps(map, units, control.playSt)
        val render = BmMapRender(bm, map, BgHardware())

        var resetAllFacesCalls = 0
        var clearTalkBubbleCalls = 0
        var endTalkCalls = 0

        val events = createEventEngine(engine, state, words, control.playSt, bm, screenFade)
        EventScrCore(
            state,
            words,
            flags = flags,
            rng = RngService(),
            playSt = control.playSt,
            keys = keys,
        ).registerAll(events)
        EventFade(words, screenFade).registerOn(events)
        EventBgm(state, words, control.sound).registerOn(events)
        EventMoveToChapter(
            state,
            words,
            control.playSt,
            control.sound,
            setNextGameActionId = { id -> control.setNextGameActionId(id) },
            setNextChapterId = { id -> control.setNextChapterId(id) },
            gotoChapterWithoutSave = { error("MNC3 does not run in PC-cap1") },
        ).registerOn(events)
        EventText(
            state,
            words,
            recordingTextHost(
                engine,
                onEndTalk = { endTalkCalls++ },
                onClearBubble = { clearTalkBubbleCalls++ },
                onResetFaces = { resetAllFacesCalls++ },
            ),
        ).registerOn(events)
        EventChangeMap(
            state,
            words,
            control.playSt,
            bm,
            BgHardware(),
            restartBattleMap = { map.initChapterMap(control.playSt.chapterIndex) },
            refreshEntityBmMaps = { entityMaps.refreshEntityBmMaps() },
            renderBmMap = { render.renderBmMap() },
            resumeMapDisplay = { engine.unblockEachMarked(Mu.PROC_MARK_MU) },
        ).registerOn(events)

        val evDump05 = dump05.find("ProcScr_StdEventEngine")!!
        val evState = events.create(evDump05.u32(EV_START), evDump05.u8(EV_EXEC_TYPE))
        evState.pEventIdk = evDump05.u32(EV_IDK)
        evState.pEventCurrent = evDump05.u32(EV_CURRENT)
        evState.evStateBits = evDump05.u16(EV_STATE_BITS)
        evState.activeTextType = evDump05.u8(EV_ACTIVE_TEXT_TYPE)
        evState.chapterIndex = evDump05.u8(EV_CHAPTER_INDEX)
        evState.proc!!.scrCur = evDump05.scrCur
        evState.proc!!.idleCb = events.stdScript.cmds[4].ptr as ProcFunc

        // f9406..f9422: FADI(16) fades to black and unlocks StdEventEngine at f9422.
        for (frame in 9406..9422) {
            stepTree(engine, 3, 0)
            assertStepMatches(frame, run, procsTrace, globalsTrace, names, evState, state)
            assertLcdAndFadeMatches(frame, run, procsTrace, names, engine, lcd, screenFade.toBlack)
        }

        // f9423: REMA (EV_STATE_FADEDIN set -> clears bubble and faces, ends talk).
        stepTree(engine, 3, 0)
        assertStepMatches(9423, run, procsTrace, globalsTrace, names, evState, state)
        assertEquals(1, endTalkCalls, "f9423 REMA EndTalk")
        assertEquals(1, clearTalkBubbleCalls, "f9423 REMA ClearTalkBubble")
        assertEquals(1, resetAllFacesCalls, "f9423 REMA ResetFaces")

        // f9424: 11x ENUT, MNC2(1), ENDA -> EventScr_08592114 -> EventScr_08592170 -> LOMA(0xFFFF).
        // In mGBA, Event25_ChangeMap takes 13 frames (f9424..f9436) before advancing cur at f9437.
        stepTree(engine, 3, 0)

        val ew24 = run.region("r09424", "ewram")
        val iw24 = run.region("r09424", "iwram")
        val dump24 = ProcArrayDump.ofWatch(procsTrace, 9424, names)
        val gcDump24 = dump24.find("gProcScr_GameControl")!!
        val evDump24 = dump24.find("ProcScr_StdEventEngine")!!

        assertEquals(gcDump24.u8(GC_NEXT_ACTION), gcState.nextAction, "f9424 nextAction")
        assertEquals(gcDump24.u8(GC_NEXT_CHAPTER), gcState.nextChapter, "f9424 nextChapter")
        assertEquals(
            dump24.find("ProcScr_Mu")!!.u8(LOCK),
            muProc.lockCnt,
            "f9424 ProcScr_Mu unlocked",
        )
        assertEquals(evDump24.u16(EV_STATE_BITS), evState.evStateBits, "f9424 evStateBits")
        assertEquals(evDump24.u8(EV_EXEC_TYPE), evState.execType, "f9424 execType")
        assertEquals(evDump24.u32(EV_IDK), evState.pEventIdk, "f9424 pEventIdk")
        assertEquals(
            ew24[SOUND_ST + 6 - EWRAM].toInt() != 0,
            control.sound.isSongPlaying,
            "f9424 Sound_FadeOutBGM",
        )
        assertNull(engine.find(control.sound.delayedSongScript), "f9424 no gMusicProc3Script")

        val gotPlaySt = ByteArray(PlaySt.SIZE).also { control.playSt.encode(it, 0) }
        assertEquals(
            ew24.copyOfRange(PLAY_ST - EWRAM, PLAY_ST - EWRAM + PlaySt.SIZE).toList(),
            gotPlaySt.toList(),
            "f9424 gPlaySt (save_menu_type = 2)",
        )
        for (flag in ENDING_ENUT_FLAGS) {
            assertTrue(flags.checkFlag(flag), "f9424 ENUT flag 0x${flag.toString(16)}")
        }
        val gotFlags = (0 until 0x30).map { flags.byteAt(it).toByte() }
        val wantFlags = (0 until 0x30).map { iw24[FLAGS - IWRAM + it] }
        assertEquals(wantFlags, gotFlags, "f9424 event flags")

        // The capture is inside the 13-frame Event25_ChangeMap (RestartBattleMap) from f9424, so
        // pEventCurrent and the slots only move at f9437; the port runs it in one step.
        assertStepMatches(9437, run, procsTrace, globalsTrace, names, evState, state)
        assertEquals(0, state.slots[2], "f9437 SADD(2, C, 0) overwrote slot 2")

        // f9438: LABEL(1), ENDA (08592170), LABEL(1), ENDA (08592114) -> EVC_END (scrCur -> 6).
        stepTree(engine, 3, 0)
        assertEquals(EVENT_SCR_08592114, evState.pEventIdk, "f9438 pEventIdk")
        assertStepMatches(9438, run, procsTrace, globalsTrace, names, evState, state)

        // f9439: Proc_Break left the repeat, so the engine proc now yields at its tail.
        stepTree(engine, 3, 0)
        assertStepMatches(9439, run, procsTrace, globalsTrace, names, evState, state)
        assertEquals(
            ProcArrayDump.ofWatch(procsTrace, 9439, names).find("ProcScr_StdEventEngine")!!.scrCur,
            evState.proc!!.scrCur,
            "f9439 StdEventEngine scrCur after EVC_END",
        )
    }

    private fun newGameControl(engine: ProcEngine, keys: KeyStatus): GameControl {
        val dummy = ProcScript("dummy", listOf(block()))
        return GameControl(engine, keys, { dummy }, { dummy })
    }

    private fun createEventEngine(
        engine: ProcEngine,
        state: EventState,
        words: ScriptWords,
        playSt: PlaySt,
        bm: BmSt,
        screenFade: ScreenFade,
    ): EventEngine = EventEngine(
        engine,
        state,
        words,
        object : EventEngineHooks {
            override fun fadeExists(): Boolean = screenFade.fadeExists()
            override fun lockGame() {
                bm.lock++
            }
            override fun chapterIndex(): Int = playSt.chapterIndex
        },
    )

    private fun stepTree(engine: ProcEngine, vararg trees: Int) {
        for (t in trees) engine.run(engine.root(t))
    }

    private fun assertStepMatches(
        step: Int,
        run: ReferenceData.Run,
        procsTrace: gba.testkit.WatchTrace,
        globalsTrace: gba.testkit.WatchTrace,
        names: Map<Int, String>,
        evState: EventEngineProc,
        state: EventState,
    ) {
        val evDump = ProcArrayDump.ofWatch(procsTrace, step, names).find("ProcScr_StdEventEngine")!!
        assertEquals(evDump.u32(EV_IDK), evState.pEventIdk, "f$step pEventIdk")
        assertEquals(evDump.u32(EV_CURRENT), evState.pEventCurrent, "f$step pEventCurrent")
        assertEquals(evDump.u16(EV_STATE_BITS), evState.evStateBits, "f$step evStateBits")
        assertEquals(evDump.u8(EV_EXEC_TYPE), evState.execType, "f$step execType")
        assertEquals(evDump.u8(LOCK), evState.proc!!.lockCnt, "f$step StdEventEngine lockCnt")

        val g = globalsTrace.step(step)
        for (slot in 1 until EventState.SLOT_COUNT) {
            assertEquals(
                word(g, EventState.SLOTS_OFFSET + 4 * slot),
                state.slots[slot],
                "f$step slot $slot",
            )
        }
        for (i in 0 until EventState.ACTIVE_QUEUE_WORDS) {
            assertEquals(
                word(g, EventState.ACTIVE_QUEUE_OFFSET + 4 * i),
                state.activeQueue[i],
                "f$step activeQueue[$i]",
            )
        }
        // Also ensure dense RAM dump (when present) agrees with watch.
        val dumpName = run.dumps.entries.firstOrNull { it.value == step }?.key
        if (dumpName != null) {
            val iw = run.region(dumpName, "iwram")
            assertEquals(
                word(iw, EventState.BASE_ADDRESS - IWRAM + EventState.SLOTS_OFFSET + 8),
                state.slots[2],
                "f$step dump $dumpName slot 2",
            )
        }
    }

    private fun assertLcdAndFadeMatches(
        step: Int,
        run: ReferenceData.Run,
        procsTrace: gba.testkit.WatchTrace,
        names: Map<Int, String>,
        engine: ProcEngine,
        lcd: LcdControl,
        fadeScript: ProcScript,
    ) {
        val dump = ProcArrayDump.ofWatch(procsTrace, step, names)
        val wantFade = dump.find(fadeScript.name)
        val gotFade = engine.find(fadeScript)
        assertEquals(wantFade != null, gotFade != null, "f$step ${fadeScript.name} exists")
        if (wantFade != null && gotFade != null) {
            val st = gotFade.state as ScreenFade.State
            assertEquals(wantFade.u16(0x64).toShort().toInt(), st.speed, "f$step fade speed")
            assertEquals(wantFade.u16(0x66).toShort().toInt(), st.level, "f$step fade level")
        }
        val dumpName = run.dumps.entries.firstOrNull { it.value == step }?.key
            ?: "r%05d".format(step)
        val iw = run.region(dumpName, "iwram")
        val wantLcd = LcdControl().also { it.readFrom(iw, lcdOffset()) }
        assertEquals(wantLcd.bldcnt, lcd.bldcnt, "f$step bldcnt")
        assertEquals(wantLcd.blendY, lcd.blendY, "f$step blendY")
    }

    private fun recordingBgHost(onLoadConvoBg: (Int) -> Unit): EventTextBgHost =
        object : EventTextBgHost {
            override fun resetBgPositions() {}
            override fun randomBackground(): Int = 0
            override fun loadConvoBackground(bgIndex: Int) {
                onLoadConvoBg(bgIndex)
            }
            override fun displayCg(bgIndex: Int) {}
            override fun startBackgroundFade(
                fadeType: Int,
                unkType: Int,
                bgIndex: Int,
                fadeSpeed: Int,
                proc: EventEngineProc,
            ) {}
            override fun clearScreen(proc: EventEngineProc): Int = EVC_ADVANCE_YIELD
            override fun colorFadeIn(speed: Int, color: Int, proc: EventEngineProc) {}
            override fun colorFadeOut(speed: Int, color: Int, proc: EventEngineProc) {}
        }

    private fun recordingTextHost(
        engine: ProcEngine,
        isTalkActive: () -> Boolean = { false },
        onStartTalk: (Int) -> Unit = {},
        onEndTalk: () -> Unit = {},
        onClearBubble: () -> Unit = {},
        onResetFaces: () -> Unit = {},
    ): EventTextHost = object : EventTextHost {
        override var textSpeed: Int = 0
        override fun endTalk() = onEndTalk()
        override fun endCgText() {}
        override fun endBoxDialogue() {}
        override fun changeUnitSpritePalette(id: Int) {}
        override fun disableMapDisp(proc: EventEngineProc): Int {
            if (proc.evStateBits and EV_STATE_GFXLOCKED == 0) {
                engine.blockEachMarked(Mu.PROC_MARK_MU)
            }
            proc.evStateBits = proc.evStateBits or EV_STATE_GFXLOCKED
            return EVC_ADVANCE_YIELD
        }
        override fun refreshBMapGraphics() {}
        override fun clearTalkTilemaps() {}
        override fun enableMapDisp(proc: EventEngineProc): Int {
            if (proc.evStateBits and EV_STATE_GFXLOCKED != 0) {
                engine.unblockEachMarked(Mu.PROC_MARK_MU)
            }
            proc.evStateBits = proc.evStateBits and EV_STATE_GFXLOCKED.inv()
            return EVC_ADVANCE_YIELD
        }
        override fun clearTalkBubble() = onClearBubble()
        override fun resetAllFaces() = onResetFaces()
        override fun faceExists(): Boolean = false
        override fun fadeOutFacesAndWait(proc: EventEngineProc) {}
        override fun resumeTalk() {}
        override fun isTalkRunning(): Boolean = isTalkActive()
        override fun cgTextExists(): Boolean = false
        override fun boxDialogueExists(): Boolean = false
        override fun talkChoiceResult(): Int = 0
        override fun setBlendAlpha(a: Int, b: Int) {}
        override fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean) {}
        override fun startTalkMsg(x: Int, y: Int, msg: Int) = onStartTalk(msg)
        override fun setTalkFlag(flag: Int) {}
        override fun startCgText(msg: Int, flags: Int) {}
        override fun boxDialogueSize(msg: Int): Pair<Int, Int> = 0 to 0
        override fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int) {}
    }

    private fun scriptWords(romBytes: ByteArray): ScriptWords = ScriptWords({ a ->
        val o = a - 0x08000000
        (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
    })

    private fun lcdOffset(): Int = symbols["gLCDControlBuffer"].address - IWRAM

    private fun word(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun half(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val SOUND_ST = 0x02024E5C
        const val FLAGS = 0x03005250

        const val LOCK = 0x28
        const val GC_NEXT_ACTION = 0x29
        const val GC_NEXT_CHAPTER = 0x2A
        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_EXEC_TYPE = 0x41
        const val EV_ACTIVE_TEXT_TYPE = 0x42
        const val EV_CHAPTER_INDEX = 0x43

        const val EVENT_PROLOGUE_ENDING_SCENE = 0x089EF164
        val ENDING_ENUT_FLAGS = intArrayOf(
            0xE0, 0xE1, 0xB7, 0xB4, 0xB5, 0xDC, 0xB9, 0xC2, 0xC3, 0xE7, 0xC9,
        )
    }
}
