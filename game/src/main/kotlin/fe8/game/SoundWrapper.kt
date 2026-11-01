package fe8.game

import gbafe.audio.M4aCommands
import gbafe.audio.M4aPlayer
import gbafe.audio.M4aSound
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat

/**
 * FE8's sound wrapper (decomp `src/soundwrapper.c`): the BGM state `gSoundSt` and the calls the
 * game makes to start songs and fade them out. [sound] is null without sound data: the state is
 * still kept, but no m4a call is made. Player arguments are `gMPlayTable` indices (-1: none, the
 * song's own player).
 */
public class SoundWrapper(
    /** Proc engine (the delayed song start runs on proc tree 3). */
    private val engine: ProcEngine,
    /** The m4a state, or null. */
    public val sound: M4aSound?,
    /** `gPlaySt.config` (see [GameControl.CONFIG_DISABLE_BGM]). */
    private val config: () -> Int,
) {
    /** `gSoundSt.songId`. */
    public var songId: Int = 0
        private set

    /** `gSoundSt.is_song_playing`. */
    public var isSongPlaying: Boolean = false
        private set

    /** `gSoundSt.unk7`. */
    public var unk7: Int = 0
        private set

    /** `gSoundSt.maxChannels` (s8). */
    public var maxChannels: Int = 0
        private set

    /** `sMusicProc1` (fade in) / `sMusicProc2` (volume change); not started by any ported caller. */
    private var musicProc1: Proc? = null
    private var musicProc2: Proc? = null

    /** `struct MusicProc` fields of the delayed start. */
    private class DelayState {
        var songId = 0
        var delayCounter = 0
        var player = -1
    }

    /** `gMusicProc3Script`: [startSongDelayed]'s proc. */
    public val delayedSongScript: ProcScript = ProcScript(
        "gMusicProc3Script",
        listOf(repeat(ProcFunc { delaySongOnLoop(it) }), end()),
        newState = { DelayState() },
    )

    private val bgm1: M4aPlayer? get() = sound?.players?.get(BGM1)
    private val bgm2: M4aPlayer? get() = sound?.players?.get(BGM2)

    /**
     * `Sound_FadeOutBGM`: breaks the fade-in / volume-change procs, fades BGM1 and BGM2 out at
     * [speed] (negative: 6) and marks no song playing.
     *
     * Ported from src/soundwrapper.c: Sound_FadeOutBGM
     */
    public fun fadeOutBgm(speed: Int) {
        val s = if (speed < 0) 6 else speed
        musicProc1?.let { engine.breakProc(it) }
        musicProc1 = null
        musicProc2?.let { engine.breakProc(it) }
        musicProc2 = null
        bgm1?.let { M4aCommands.fadeOut(it, s) }
        bgm2?.let { M4aCommands.fadeOut(it, s) }
        isSongPlaying = false
    }

    /**
     * `StartBgmCore`: marks [song] playing, starts it ([playSong]) and resets BGM1/BGM2's starting
     * tracks at once (`m4aMPlayImmInit`).
     *
     * Ported from src/soundwrapper.c: StartBgmCore
     */
    public fun startBgmCore(song: Int, player: Int) {
        isSongPlaying = true
        unk7 = 0
        songId = song and 0xFFFF
        playSong(song, player)
        val s = sound ?: return
        s.sequencer.immInit(s.players[BGM1])
        s.sequencer.immInit(s.players[BGM2])
    }

    /**
     * `StartOrChangeBgm`: nothing if [song] already plays; unless BGM is disabled, ends pending
     * delayed starts, then either fades the current song out at [speed] and starts [song]
     * `speed * 16` frames later, or starts it at once.
     *
     * Ported from src/soundwrapper.c: StartOrChangeBgm
     */
    public fun startOrChangeBgm(song: Int, speed: Int, player: Int) {
        if (isSongPlaying && songId == song) return
        if (config() and GameControl.CONFIG_DISABLE_BGM != 0) return
        engine.endEach(delayedSongScript) // DeleteAll6CWaitMusicRelated
        if (isSongPlaying) {
            fadeOutBgm(speed)
            startSongDelayed(song, speed * 16, player)
        } else {
            startBgmCore(song, player)
        }
    }

    /**
     * `StartBgm`: [startOrChangeBgm] with speed 3. (No boot caller: not verified yet.)
     */
    public fun startBgm(song: Int, player: Int): Unit = startOrChangeBgm(song, 3, player)

    /**
     * `StartBgmExt`: [startOrChangeBgm].
     *
     * Ported from src/soundwrapper.c: StartBgmExt
     */
    public fun startBgmExt(song: Int, speed: Int, player: Int): Unit =
        startOrChangeBgm(song, speed, player)

    /**
     * `StartSongDelayed`: unless BGM is disabled, starts a tree-3 proc that plays [song] after
     * [delay] more frames.
     *
     * Ported from src/soundwrapper.c: StartSongDelayed
     */
    public fun startSongDelayed(song: Int, delay: Int, player: Int) {
        if (config() and GameControl.CONFIG_DISABLE_BGM != 0) return
        val st = engine.start(delayedSongScript, 3).state as DelayState
        st.delayCounter = delay.toShort().toInt()
        st.songId = song.toShort().toInt()
        st.player = player
    }

    /**
     * `DelaySong_OnLoop`: counts down; below 0 marks the song playing, plays it and ends.
     *
     * Ported from src/soundwrapper.c: DelaySong_OnLoop
     */
    public fun delaySongOnLoop(proc: Proc) {
        val st = proc.state as DelayState
        st.delayCounter = (st.delayCounter - 1).toShort().toInt()
        if (st.delayCounter < 0) {
            isSongPlaying = true
            songId = st.songId and 0xFFFF
            playSong(st.songId, st.player)
            engine.end(proc)
        }
    }

    /**
     * `PlaySong`: for songs below 128 sets the channel count ([setChannelsForSong]; the sound
     * room unlock, `UnlockSoundRoomSong`, is save data and not modelled), then starts the song
     * on [player] (`MPlayStart`) or on its own player (`m4aSongNumStart`).
     *
     * Partially ported from src/soundwrapper.c: PlaySong
     */
    public fun playSong(song: Int, player: Int) {
        if (song < 128) setChannelsForSong(song)
        val s = sound ?: return
        if (player >= 0) {
            s.sequencer.start(s.players[player], s.songs.header(song and 0xFFFF))
        } else {
            s.songNumStart(song)
        }
    }

    /**
     * `PlaySoundEffect` (macro in `include/soundwrapper.h`): `m4aSongNumStart` unless sound effects
     * are disabled.
     */
    public fun playSoundEffect(song: Int) {
        if (config() and GameControl.CONFIG_DISABLE_SOUND_EFFECTS != 0) return
        sound?.songNumStart(song)
    }

    /**
     * `Sound_SetDefaultMaxNumChannels`: 7 channels, `maxChannels` = -1.
     *
     * Ported from src/soundwrapper.c: Sound_SetDefaultMaxNumChannels
     */
    public fun setDefaultMaxNumChannels() {
        setMaxNumChannels(7)
        maxChannels = -1
    }

    /**
     * `Sound_SetMaxNumChannels`: `m4aSoundMode(maxchn << 8)`.
     *
     * Ported from src/soundwrapper.c: Sound_SetMaxNumChannels
     */
    public fun setMaxNumChannels(maxchn: Int) {
        maxChannels = maxchn.toByte().toInt()
        sound?.info?.soundMode(maxchn shl 8)
    }

    /**
     * `sub_80028FC`: the title / staff roll themes use 8 channels, every other song the default.
     *
     * Ported from src/soundwrapper.c: sub_80028FC
     */
    public fun setChannelsForSong(song: Int) {
        if (song in EIGHT_CHANNEL_SONGS) {
            if (maxChannels != 8) setMaxNumChannels(8)
        } else if (maxChannels != -1) {
            setDefaultMaxNumChannels()
        }
    }

    /** Player indices and song ids. */
    public companion object {
        /** `gMPlayInfo_BGM1`. */
        public const val BGM1: Int = 0

        /** `gMPlayInfo_BGM2`. */
        public const val BGM2: Int = 1

        /** `SONG_THE_VALIANT`: the op anim BGM (`SetupOpAnimWorldMapfx`). */
        public const val SONG_THE_VALIANT: Int = 0x01

        /** `SONG_MAIN_THEME_EXT`. */
        public const val SONG_MAIN_THEME_EXT: Int = 0x02

        /** `SONG_MAIN_THEME`. */
        public const val SONG_MAIN_THEME: Int = 0x43

        /** `SONG_6D` (title: A/Start). */
        public const val SONG_6D: Int = 0x6D

        /** `SONG_317` (title: main screen appears). */
        public const val SONG_317: Int = 0x317

        /** `sub_80028FC`'s 8-channel songs (THE_VALIANT, MAIN_THEME_EXT, ...; decomp songs.h). */
        private val EIGHT_CHANNEL_SONGS: Set<Int> = setOf(0x01, 0x02, 0x42, 0x43, 0x40, 0x56, 0x74)
    }
}
