package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.ActiveUnit
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.ActiveEventRegistry
import fe8.game.event.EventBgm
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventScrHooks
import fe8.game.event.EventState
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential verification of the Prologue win condition (`PC-1`) on `ram_trace_prologue_end`
 * (`r04893` -> `r04894`).
 *
 * After the last enemy is defeated, `gProcScr_PlayerPhase` advances from `HandlePostActionTraps`
 * (`scrCur` 33) through `RunPotentialWaitEvents` (`CheckForWaitEvents` -> `RunWaitEvents` ->
 * `EvCheck01_AFEV` -> `StartEventFromInfo` -> `CallEvent` empty-queue branch ->
 * `EventEngine_Create`) to `scrCur` 35 on tree 2, and `ProcScr_StdEventEngine` runs frame 4894
 * on tree 3 to start `EventScr_Prologue_EndingScene` (`MUSC(SONG_VICTORY)` ->
 * `gMusicProc3Script`).
 */
class WinConditionDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "RunPotentialWaitEvents",
        "CheckForWaitEvents",
        "RunWaitEvents",
        "EvCheck01_AFEV",
        "StartEventFromInfo",
        "EventEngine_Create",
    )
    fun `win condition wait event at f4893 to f4894 matches PC-cap1`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val procsTrace = run.watch("procs")

        val ew93 = run.region("r04893", "ewram")
        val iw93 = run.region("r04893", "iwram")
        val ew94 = run.region("r04894", "ewram")
        val iw94 = run.region("r04894", "iwram")

        val playSt = PlaySt().also { it.decode(ew93, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(ew93, BM_ST - EWRAM) }
        val chapters = ChapterData(rom)
        val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(ew93) }
        readPool(ew93, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(ew93, GBM_MAP_UNIT, map, map.unit)

        val engine = ProcEngine()
        val keys = KeyStatus()
        val keyStart = KEY_STATUS - EWRAM
        keys.readFrom(ew94.copyOfRange(keyStart, keyStart + KeyStatus.STRUCT_SIZE))
        val flags = Fe8EventFlags.create().also { it.copyIn(iw93, FLAGS - IWRAM) }
        val words = ScriptWords({ a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        })
        val state = EventState()
        for (i in 0 until 32) {
            state.callQueue[i] = word(iw93, GLOBALS + 4 * i - IWRAM)
        }
        for (k in 0 until 14) {
            state.slots[k] = word(iw93, GLOBALS + 0x80 + 4 * k - IWRAM)
        }

        val sound = SoundWrapper(engine, null) {
            if (playSt.disableBgm != 0) GameControl.CONFIG_DISABLE_BGM else 0
        }
        val preSongId = half(ew93, SOUND_ST + 4 - EWRAM)
        if (ew93[SOUND_ST + 6 - EWRAM].toInt() != 0) {
            sound.startBgmCore(preSongId, -1)
        }

        val infos = EventInfos(words, flags, playSt)
        infos.registry.load(iw93, REGISTRY - IWRAM)

        val events = EventEngine(
            engine,
            state,
            words,
            object : EventEngineHooks {
                override fun lockGame() {
                    bm.lock++
                }

                override fun chapterIndex(): Int = playSt.chapterIndex
            },
        )
        EventScrCore(
            state,
            words,
            flags = flags,
            rng = RngService(),
            playSt = playSt,
            keys = keys,
            hooks = object : EventScrHooks {
                override fun countRedUnits(): Int = units.countRedUnits()
                override fun eventTriggerId(script: Int): Int =
                    (0 until infos.registry.idx).firstOrNull {
                        infos.registry.scripts[it] == script
                    }?.let { infos.registry.flags[it] } ?: 0
            },
        ).registerAll(events)
        EventBgm(state, words, sound).registerOn(events)

        var refreshAlliesCalls = 0
        infos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) = events.callEvent(script, execType)
            override fun chapterEventGroup(chapter: Int): Int =
                chapters.getChapterEventDataPointer(chapter)
            override fun areAnyEnemyUnitDead(): Boolean = units.areAnyEnemyUnitAlive()
            override fun countAvailableBlueUnits(): Int = units.countAvailableBlueUnits()
            override fun refreshAllies() {
                refreshAlliesCalls++
                units.refreshAllies()
            }
        }

        val active = ActiveUnit()
        val phase = PlayerPhase(
            engine,
            bm,
            playSt,
            map,
            units,
            CamMove(engine, bm),
            EntityBmMaps(map, units, playSt),
            BmMapRender(bm, map, BgHardware()),
            keys,
            { events.eventEngineExists() },
            active = active,
        )
        active.unit = unitIdAt(units, word(iw93, GACTIVEUNIT - IWRAM))
        active.unitId = ew93[GACTIVEUNIT_ID - EWRAM].toInt() and 0xFF
        active.moveOriginX = half(ew93, GACTIVEUNIT_ORIGIN - EWRAM)
        active.moveOriginY = half(ew93, GACTIVEUNIT_ORIGIN - EWRAM + 2)
        readAction(ew93, active)

        val h = phase.hooks
        h.checkForWaitEvents = {
            infos.checkForWaitEvents(units.xPos(active.unit), units.yPos(active.unit))
        }
        h.runWaitEvents = {
            infos.runWaitEvents(units.xPos(active.unit), units.yPos(active.unit))
        }
        h.shouldCallEndEvent = { infos.shouldCallEndEvent() }

        val phaseProc = engine.start(phase.script, 2)
        phaseProc.scrCur = 33
        val want93 = ProcArrayDump.ofWatch(procsTrace, 4893, names)
        assertEquals(33, want93.find("gProcScr_PlayerPhase")!!.scrCur, "f4893 PlayerPhase scrCur")
        assertNull(want93.find("ProcScr_StdEventEngine"), "f4893 no StdEventEngine")

        for (t in 0 until 8) {
            if (t != 2 || bm.lock == 0) engine.run(engine.root(t))
        }

        val want94 = ProcArrayDump.ofWatch(procsTrace, 4894, names)
        val wantPhase94 = want94.find("gProcScr_PlayerPhase")!!
        assertEquals(35, wantPhase94.scrCur, "f4894 PlayerPhase scrCur (capture)")
        assertEquals(wantPhase94.scrCur, phaseProc.scrCur, "f4894 PlayerPhase scrCur")

        val wantEv94 = want94.find("ProcScr_StdEventEngine")!!
        val myEvProc = events.findStd()
        assertNotNull(myEvProc, "f4894 ProcScr_StdEventEngine created")
        assertEquals(wantEv94.scrCur, myEvProc!!.scrCur, "f4894 StdEventEngine scrCur")
        val evState = myEvProc.state as EventEngineProc
        assertEquals(wantEv94.u32(EV_START), evState.pEventStart, "f4894 pEventStart")
        assertEquals(wantEv94.u32(EV_IDK), evState.pEventIdk, "f4894 pEventIdk")
        assertEquals(wantEv94.u32(EV_CURRENT), evState.pEventCurrent, "f4894 pEventCurrent")
        assertEquals(wantEv94.u16(EV_STATE_BITS), evState.evStateBits, "f4894 evStateBits")
        assertEquals(wantEv94.u8(EV_EXEC_TYPE), evState.execType, "f4894 execType")
        assertEquals(wantEv94.u8(EV_CHAPTER_INDEX), evState.chapterIndex, "f4894 chapterIndex")

        val wantMusic94 = want94.find("gMusicProc3Script")!!
        val myMusicProc = engine.find(sound.delayedSongScript)
        assertNotNull(myMusicProc, "f4894 gMusicProc3Script created")
        assertEquals(wantMusic94.scrCur, myMusicProc!!.scrCur, "f4894 gMusicProc3Script scrCur")
        assertEquals(
            ew94[SOUND_ST + 6 - EWRAM].toInt() != 0,
            sound.isSongPlaying,
            "f4894 gSoundSt.is_song_playing",
        )
        assertEquals(half(ew94, SOUND_ST + 4 - EWRAM), sound.songId, "f4894 gSoundSt.songId")

        assertEquals(ew94[BM_ST - EWRAM + 1], bm.lock.toByte(), "f4894 gBmSt.lock")
        assertTrue(flags.checkFlag(EVFLAG_WIN), "f4894 EVFLAG_WIN")
        assertTrue(flags.checkFlag(EVFLAG_DEFEAT_ALL), "f4894 EVFLAG_DEFEAT_ALL")
        assertTrue(
            flags.checkFlag(EVFLAG_HIDE_BLINKING_ICON),
            "f4894 EVFLAG_HIDE_BLINKING_ICON",
        )
        val gotFlags = (0 until 0x30).map { flags.byteAt(it).toByte() }
        val wantFlags = (0 until 0x30).map { iw94[FLAGS - IWRAM + it] }
        assertEquals(wantFlags, gotFlags, "f4894 event flags")

        val gotRegistry = ByteArray(ActiveEventRegistry.BYTES).also { infos.registry.store(it, 0) }
        val wantRegistry = iw94.copyOfRange(
            REGISTRY - IWRAM,
            REGISTRY - IWRAM + ActiveEventRegistry.BYTES,
        )
        assertEquals(wantRegistry.toList(), gotRegistry.toList(), "f4894 gActiveEventRegistry")
        assertTrue(state.callQueue.all { it == 0 }, "f4894 empty callQueue")
        assertEquals(1, refreshAlliesCalls, "f4894 RefreshAllies called once")

        for (k in 0 until UnitTable.BLUE_COUNT * UnitTable.UNIT_SIZE) {
            if (k % UnitTable.UNIT_SIZE in 0x3C..0x3F) continue
            assertEquals(
                ew94[UnitTable.BLUE_ADDRESS - EWRAM + k],
                units.bytes[k],
                "f4894 blue unit byte 0x${k.toString(16)}",
            )
        }
        val gotPlaySt = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
        assertEquals(
            ew94.copyOfRange(PLAY_ST - EWRAM, PLAY_ST - EWRAM + PlaySt.SIZE).toList(),
            gotPlaySt.toList(),
            "f4894 gPlaySt",
        )
    }

    private fun readAction(e: ByteArray, active: ActiveUnit) {
        val d = ACTION_DATA - EWRAM
        active.action.subjectIndex = e[d + 0x0C].toInt() and 0xFF
        active.action.targetIndex = e[d + 0x0D].toInt() and 0xFF
        active.action.xMove = e[d + 0x0E].toInt() and 0xFF
        active.action.yMove = e[d + 0x0F].toInt() and 0xFF
        active.action.moveCount = e[d + 0x10].toInt() and 0xFF
        active.action.unitActionType = e[d + 0x11].toInt() and 0xFF
        active.action.itemSlotIndex = e[d + 0x12].toInt() and 0xFF
        active.action.trapType = e[d + 0x15].toInt() and 0xFF
        active.action.suspendPointType = e[d + 0x16].toInt() and 0xFF
    }

    private fun unitIdAt(units: UnitTable, address: Int): Int {
        if (address == 0) return 0
        return units.bytes[address - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
    }

    private fun word(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun half(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    private fun rowPointer(e: ByteArray, handle: Int, row: Int): Int {
        val pool = word(e, handle - EWRAM)
        return word(e, pool - EWRAM + 4 * row)
    }

    private fun readPool(e: ByteArray, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = rowPointer(e, handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = e[first + (y + 2) * (map.sizeX + 2) + x - EWRAM].toInt() and 0xFF
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val SOUND_ST = 0x02024E5C
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val GACTIVEUNIT_ID = 0x0202BE44
        const val GACTIVEUNIT_ORIGIN = 0x0202BE48
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GLOBALS = 0x03000438
        const val REGISTRY = 0x03001C80
        const val FLAGS = 0x03005250

        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val EV_EXEC_TYPE = 0x41
        const val EV_CHAPTER_INDEX = 0x43
        const val EVFLAG_WIN = 3
        const val EVFLAG_DEFEAT_ALL = 6
        const val EVFLAG_HIDE_BLINKING_ICON = 132
    }
}
