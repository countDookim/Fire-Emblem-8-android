package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventState
import fe8.game.event.GameOverStart
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the game over start (`PC-7a`) on `ram_trace_prologue_gameover`
 * (`r05832` -> `r05833`): after Eirika's death the enemy phase's `CpPerform` runs
 * `RunPotentialWaitEvents`; `EVFLAG_GAMEOVER` (set at f5583 by Eirika's defeat entry, so
 * `CheckForWaitEvents` is true) sends `RunWaitEvents` to `sub_80837B0`, which sets the flag,
 * starts `SONG_GAME_OVER` (the old song fades through `gMusicProc3Script`, the new one plays
 * from f5881), sets `config.disableBgm` and calls `CallGameOverEvent`
 * (`EventEngine_Create(EventScr_GameOver, EV_EXEC_GAMEPLAY)`, which locks the game).
 *
 * The capture's engine proc has already run its first frame at f5833 (script cursor, `evStateBits`
 * and the `BmGameOver` child are the script's work and are not compared here); the creation
 * fields are.
 */
class GameOverStartDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Result(
        val flags: List<Byte>,
        val playSt: List<Byte>,
        val lock: Int,
        val start: Int,
        val idk: Int,
        val execType: Int,
        val chapterIndex: Int,
        val textSpeed: Int,
        val songRequested: Int,
        val musicProcScrCur: Int?,
        val songId: Int,
        val songPlaying: Boolean,
    )

    private fun replay(direct: Boolean): Triple<Result, Int, ReferenceData.Run>? {
        if (!ref.available || !romFile.isFile) return null
        val run = ref.run(RUN)
        if (!run.hasWatch("procs")) return null
        val names = ProcArrayDump.romNames()
        if (names.isEmpty()) return null
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val ew = run.region("f05832", "ewram")
        val iw = run.region("f05832", "iwram")

        val playSt = PlaySt().also { it.decode(ew, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(ew, BM_ST - EWRAM) }
        val units = UnitTable(rom).also { it.loadFrom(ew) }
        val flags = Fe8EventFlags.create().also { it.copyIn(iw, FLAGS - IWRAM) }
        val words = ScriptWords({ a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        })
        val engine = ProcEngine()
        val sound = SoundWrapper(engine, null) {
            if (playSt.disableBgm != 0) GameControl.CONFIG_DISABLE_BGM else 0
        }
        if (ew[SOUND_ST + 6 - EWRAM].toInt() != 0) {
            sound.startBgmCore(half(ew, SOUND_ST + 4 - EWRAM), -1)
        }
        val events = EventEngine(
            engine,
            EventState(),
            words,
            object : EventEngineHooks {
                override fun lockGame() {
                    bm.lock++
                }

                override fun chapterIndex(): Int = playSt.chapterIndex
            },
        )
        var songRequested = -1
        val gameOver = GameOverStart(
            flags,
            playSt,
            { song ->
                songRequested = song
                sound.startBgm(song, 0)
            },
            { events.callGameOverEvent() },
        )
        val infos = EventInfos(words, flags, playSt)
        infos.hooks = object : EventInfoHooks {
            override fun areAnyEnemyUnitDead(): Boolean = units.areAnyEnemyUnitAlive()
            override fun countAvailableBlueUnits(): Int = units.countAvailableBlueUnits()
            override fun startGameOver() = gameOver.startGameOver()
        }

        // The game over branches return before the active unit's position is used.
        if (direct) {
            // sub_80837B0 alone, from a state without EVFLAG_GAMEOVER (so setting it is visible)
            flags.clearFlag(GameOverStart.EVFLAG_GAMEOVER)
            gameOver.startGameOver()
        } else {
            assertTrue(infos.checkForWaitEvents(0, 0), "f5832 CheckForWaitEvents")
            infos.runWaitEvents(0, 0)
        }

        val proc = events.findStd()
        assertNotNull(proc, "f5833 StdEventEngine created")
        val evState = proc!!.state as EventEngineProc
        val wantSound = run.watch("soundst")
        assertEquals(SONG_GAME_OVER, wantSound.u16(5881, SOUND_SONG), "capture song f5881")
        val result = Result(
            flags = (0 until 0x30).map { flags.byteAt(it).toByte() },
            playSt = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }.toList(),
            lock = bm.lock,
            start = evState.pEventStart,
            idk = evState.pEventIdk,
            execType = evState.execType,
            chapterIndex = evState.chapterIndex,
            textSpeed = evState.overwrittenTextSpeed,
            songRequested = songRequested,
            musicProcScrCur = engine.find(sound.delayedSongScript)?.scrCur,
            songId = sound.songId,
            songPlaying = sound.isSongPlaying,
        )
        return Triple(result, 0, run)
    }

    @Test
    @VerifiesPort("RunWaitEvents", "CheckForWaitEvents")
    fun `lord death reaches the game over start through RunWaitEvents at f5832 to f5833`() =
        compareWithCapture(direct = false)

    @Test
    @VerifiesPort("sub_80837B0", "CallGameOverEvent")
    fun `game over start sets the flag, song, disableBgm and event like PC-cap2`() =
        compareWithCapture(direct = true)

    private fun compareWithCapture(direct: Boolean) {
        val (got, _, run) = replay(direct) ?: run {
            assumeTrue(false, "ROM / mGBA reference data not present")
            return
        }
        val names = ProcArrayDump.romNames()
        val ew = run.region("f05833", "ewram")
        val iw = run.region("f05833", "iwram")
        val want = ProcArrayDump.ofWatch(run.watch("procs"), 5833, names)
        val wantEv = want.find("ProcScr_StdEventEngine")!!

        assertEquals((0 until 0x30).map { iw[FLAGS - IWRAM + it] }, got.flags, "f5833 flags")
        assertEquals(
            ew.copyOfRange(PLAY_ST - EWRAM, PLAY_ST - EWRAM + PlaySt.SIZE).toList(),
            got.playSt,
            "f5833 gPlaySt (config.disableBgm)",
        )
        assertEquals(ew[BM_ST - EWRAM + 1].toInt(), got.lock, "f5833 gBmSt.lock")
        assertEquals(wantEv.u32(EV_START), got.start, "pEventStart")
        assertEquals(wantEv.u32(EV_IDK), got.idk, "pEventIdk")
        assertEquals(wantEv.u8(EV_EXEC_TYPE), got.execType, "execType")
        assertEquals(wantEv.u8(EV_CHAPTER_INDEX), got.chapterIndex, "chapterIndex")
        assertEquals(wantEv.u8(EV_TEXT_SPEED).toByte().toInt(), got.textSpeed, "text speed")
        assertEquals(SONG_GAME_OVER, got.songRequested, "StartBgm song")
        assertEquals(
            want.find("gMusicProc3Script")!!.scrCur,
            got.musicProcScrCur,
            "gMusicProc3Script scrCur",
        )
        assertEquals(half(ew, SOUND_ST + 4 - EWRAM), got.songId, "f5833 gSoundSt.songId")
        assertEquals(ew[SOUND_ST + 6 - EWRAM].toInt() != 0, got.songPlaying, "is_song_playing")
    }

    private fun half(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val SOUND_ST = 0x02024E5C
        const val FLAGS = 0x03005250
        const val SOUND_SONG = 4
        const val SONG_GAME_OVER = 0x3E

        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_TEXT_SPEED = 0x40
        const val EV_EXEC_TYPE = 0x41
        const val EV_CHAPTER_INDEX = 0x43
    }
}
