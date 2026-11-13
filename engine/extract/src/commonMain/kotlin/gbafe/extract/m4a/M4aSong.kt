package gbafe.extract.m4a

import gba.data.ByteSink
import gbafe.extract.hexUpper

/**
 * Decoder and converter for the GBA m4a ("MusicPlayer2000" / mp2k / Sappy) sound engine format
 * shared by FE6, FE7, and FE8 (decomp `src/m4a.c`, `src/m4a_1.s`, `src/m4a_tables.c`,
 * `asm/macros/m4a.inc`, `asm/macros/music_voice.inc`, `include/gba/m4a_internal.h`). Reads song
 * tables, song headers, track byte streams, voicegroups and direct-sound samples out of the ROM,
 * and converts a song to a Standard MIDI File and a sample to a WAV file.
 *
 * ## Binary Layouts
 *
 * ### Song Table
 * Each entry in the global song table (`gSongTable`, `struct Song`) is 8 bytes. The decomp macro is
 * `song label, music_player, unknown`:
 * - 4-byte pointer to the song header in ROM
 * - 2-byte music player index (`music_player` in the macro; `ms` in `struct Song`)
 * - 2-byte field the decomp calls `unknown` (`me` in `struct Song`); its meaning is not known here
 *
 * ### Song Header
 * `SongHeader { u8 trackCount, blockCount, priority, reverb; u32 tone; u32 part[trackCount] }`:
 * - `trackCount`: number of parallel sequence tracks (1 byte)
 * - `blockCount`: not read by `MPlayStart`; meaning unknown here
 * - `priority`: copied to the music player; a new song replaces a playing one only if the playing
 *   priority is not higher (`MPlayStart`)
 * - `reverb`: if bit 7 is set, `MPlayStart` passes it to `m4aSoundMode`
 * - `tone`: GBA pointer to the instrument voicegroup (`ToneData` table)
 * - `part[trackCount]`: array of GBA pointers to the start of each track's byte stream
 *
 * ### Track Byte Stream Commands
 * Each track is an independent sequence of command bytes. The engine dispatches commands
 * `0xB1..0xCE` through `gMPlayJumpTableTemplate` (index = command - 0xB1, `src/m4a_tables.c`):
 * - `0x80..0xB0`: `WAIT` commands (advance time by 0..96 m4a clocks via [CLOCKS])
 * - `0xB1`: `FINE` / end of track
 * - `0xB2`: `GOTO` jump (4-byte ROM address target)
 * - `0xB3`: `PATT` pattern subroutine call (4-byte ROM address target; up to 3 levels deep)
 * - `0xB4`: `PEND` pattern subroutine return
 * - `0xB5`: `REPT` repeat (1-byte count, 4-byte ROM address target)
 * - `0xB9`: `MEMACC` memory access operation (3 parameter bytes)
 * - `0xBA..0xC8`: parameter commands with one byte of argument (see `PARAMS`: priority, tempo,
 *   key shift, voice, volume, pan, bend, bend range, LFO and modulation settings, tune); `0xC6`
 *   and `0xC7` map to `ply_fine` in the jump table and are not decoded
 * - `0xCD`: `XCMD` extended sound command (command number byte, then per-command arguments)
 * - `0xCE`: `EOT` end of tie / note off (optional key argument)
 * - `0xCF`: `TIE` tied note on (optional key and velocity arguments)
 * - `0xD0..0xFF`: `NOTE` (N01..N96) note on with duration (optional key, velocity, gate extra
 *   arguments)
 *
 * Commands `>= 0xBD` establish running status (`src/m4a_1.s`: stored when the command is >= 0xBD):
 * subsequent bytes `< 0x80` repeat the active command with new arguments. Time is measured in m4a
 * clocks ([CLOCKS_PER_QUARTER_NOTE] = 24 clocks per quarter note).
 */
public object M4aSong {
    /**
     * Clocks per quarter note: the m4a timebase used for the MIDI division. Not named in the
     * decomp (it is the mid2agb convention), so not checked against it.
     */
    public const val CLOCKS_PER_QUARTER_NOTE: Int = 24

    /** GBA address of the first ROM byte; subtracted from pointers to get file offsets. */
    public const val GBA_ROM_BASE: Int = 0x08000000

    /** Size of each song table entry in bytes (`struct Song`: pointer, 2 x u16). */
    public const val SONG_TABLE_ENTRY_SIZE: Int = 8

    /** Byte offset in `SongHeader` of `part[]`, the array of track pointers. */
    public const val SONG_HEADER_TRACK_POINTERS_OFFSET: Int = 8

    /** Size of each `ToneData` voice entry in bytes (`struct ToneData`). */
    public const val TONE_SIZE: Int = 12

    /** Bytes of `WaveData` before the samples: type, status, freq, loopStart, size. */
    public const val WAVE_HEADER_SIZE: Int = 16

    /**
     * Upper bound (4 MiB) on `WaveData.size` accepted by [readWave]; a sanity limit of this
     * extractor, not a format limit.
     */
    public const val MAX_WAVE_SIZE: Int = 0x400000

    /**
     * Bit in `WaveData.status` taken to mean "sample loops". The decomp's m4a code names no
     * such flag, so this follows the common m4a format description and is not checked there.
     */
    public const val WAVE_LOOP_FLAG: Int = 0x4000

    /** Scale between `WaveData.freq` and the sample rate in Hz (freq = rate x 1024). */
    public const val WAVE_FREQ_SCALE: Int = 1024

    // --- m4a command bytes (see the class KDoc) ---
    private const val CMD_WAIT_MIN = 0x80
    private const val CMD_WAIT_MAX = 0xB0
    private const val CMD_FINE = 0xB1
    private const val CMD_GOTO = 0xB2
    private const val CMD_PATT = 0xB3
    private const val CMD_PEND = 0xB4
    private const val CMD_REPT = 0xB5
    private const val CMD_MEMACC = 0xB9
    private const val CMD_PARAM_MIN = 0xBA
    private const val CMD_PARAM_MAX = 0xC8
    private const val CMD_RUNNING_STATUS_MIN = 0xBD
    private const val CMD_XCMD = 0xCD
    private const val CMD_EOT = 0xCE
    private const val CMD_TIE = 0xCF
    private const val CMD_NOTE_MIN = 0xD0
    private const val CMD_NOTE_MAX = 0xFF

    // --- Note defaults: this decoder's own starting key/velocity, used if a NOTE/TIE/EOT
    // --- arrives before any explicit key or velocity. The engine zero-fills track state at start
    // --- instead, so these only matter for streams that rely on unset values.
    private const val DEFAULT_MIDI_KEY = 60 // MIDI middle C
    private const val DEFAULT_VELOCITY = 127

    // --- Standard MIDI File constants (status bytes, meta event types, controller numbers) ---
    private const val MIDI_NOTE_OFF = 0x80
    private const val MIDI_NOTE_ON = 0x90
    private const val MIDI_CONTROL_CHANGE = 0xB0
    private const val MIDI_PROGRAM_CHANGE = 0xC0
    private const val MIDI_PITCH_BEND = 0xE0
    private const val MIDI_META_EVENT = 0xFF

    private const val META_TEMPO = 0x51
    private const val META_MARKER = 0x06
    private const val META_END_OF_TRACK = 0x2F

    // Controller numbers: 1/7/10 are standard MIDI (modulation, volume, pan); 6/100/101 select
    // RPN 0 (pitch bend range); 21/22/24/26/33 are this converter's own choices for the m4a LFO
    // speed, modulation type, tune, LFO delay and priority commands.
    private const val CC_MODULATION = 1
    private const val CC_DATA_ENTRY_MSB = 6
    private const val CC_VOLUME = 7
    private const val CC_PAN = 10
    private const val CC_LFOS = 21
    private const val CC_MODT = 22
    private const val CC_TUNE = 24
    private const val CC_LFODL = 26
    private const val CC_PRIORITY = 33
    private const val CC_RPN_LSB = 100
    private const val CC_RPN_MSB = 101

    private const val MICROS_PER_MINUTE = 60_000_000
    private const val MIDI_PITCH_BEND_CENTER = 8192
    private const val MIDI_CHANNEL_COUNT = 16

    /**
     * `gClockTable` (`src/m4a_tables.c`): length in clocks, indexed by command - 0x80 for WAIT
     * and by command - 0xCF for TIE/NOTE (TIE = index 0, N01 = index 1, N96 = index 48).
     */
    public val CLOCKS: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        28, 30, 32, 36, 40, 42, 44, 48, 52, 54, 56, 60, 64, 66, 68, 72, 76, 78, 80, 84, 88, 90, 92, 96,
    )

    /**
     * m4a parameter command byte to mnemonic. The handler behind each byte is in
     * `gMPlayJumpTableTemplate` (`ply_prio`, `ply_tempo`, `ply_keysh`, `ply_voice`, `ply_vol`,
     * `ply_pan`, `ply_bend`, `ply_bendr`, `ply_lfos`, `ply_lfodl`, `ply_mod`, `ply_modt`,
     * `ply_tune`); each takes one argument byte.
     */
    private val PARAMS = mapOf(
        0xBA to "PRIO", 0xBB to "TEMPO", 0xBC to "KEYSH", 0xBD to "VOICE", 0xBE to "VOL",
        0xBF to "PAN", 0xC0 to "BEND", 0xC1 to "BENDR", 0xC2 to "LFOS", 0xC3 to "LFODL",
        0xC4 to "MOD", 0xC5 to "MODT", 0xC8 to "TUNE",
    )

    /** One decoded track event: a command with its arguments at a time in m4a clocks. */
    public data class Event(
        /** Time in m4a clocks from the start of the sequence. */
        val time: Int,
        /** m4a mnemonic (`NOTE`, `TIE`, `EOT`, `VOL`, `TEMPO`, `GOTO`, ...); `END` is FINE. */
        val type: String,
        /**
         * Arguments: NOTE = key, velocity, gate length in clocks; TIE = key, velocity; EOT = key;
         * GOTO = target time in clocks (-1 if unknown); parameter commands = their one byte.
         */
        val args: List<Int>,
    )

    /** A decoded song: header fields plus, per track, its events and loop start time. */
    public class Song(
        /** `SongHeader.blockCount`; `MPlayStart` does not read it, meaning unknown here. */
        public val blockCount: Int,
        /** `SongHeader.priority`: compared with the playing song's priority by `MPlayStart`. */
        public val priority: Int,
        /** `SongHeader.reverb`: if bit 7 is set, `MPlayStart` passes it to `m4aSoundMode`. */
        public val reverb: Int,
        /** GBA address of the voicegroup (`SongHeader.tone`, a `ToneData` array). */
        public val voicegroup: Int,
        /** Events per track in playback order. */
        public val tracks: List<List<Event>>,
        /** Loop start time per track (first GOTO target time in m4a clocks), or -1 if non-looping. */
        public val loopStarts: List<Int>,
    )

    /** One song table entry (`struct Song`; decomp macro `song` in `asm/macros/m4a.inc`). */
    public data class SongEntry(
        /** Song header GBA ROM address. */
        val header: Int,
        /** Music player index (`music_player` in the macro; `ms` in `struct Song`). */
        val musicPlayer: Int,
        /** Second u16: named `unknown` in the decomp macro (`me` in `struct Song`). */
        val unknown: Int,
    )

    /** Reads [count] 8-byte song table entries at ROM file offset [offset]. */
    public fun readSongTable(
        rom: ByteArray,
        offset: Int,
        count: Int,
    ): List<SongEntry> = List(count) { index ->
        val entryOffset = offset + SONG_TABLE_ENTRY_SIZE * index
        val headerAddress = u32(rom, entryOffset)
        val musicPlayer = u8(rom, entryOffset + 4) or (u8(rom, entryOffset + 5) shl 8)
        val unknown = u8(rom, entryOffset + 6) or (u8(rom, entryOffset + 7) shl 8)
        SongEntry(headerAddress, musicPlayer, unknown)
    }

    /** Decodes the song header and all its track byte streams at ROM file offset [offset]. */
    public fun decode(rom: ByteArray, offset: Int): Song {
        val trackCount = u8(rom, offset)
        val tracks = ArrayList<List<Event>>(trackCount)
        val loops = ArrayList<Int>(trackCount)
        for (trackIndex in 0 until trackCount) {
            val trackPointerOffset = offset + SONG_HEADER_TRACK_POINTERS_OFFSET + 4 * trackIndex
            val trackRomOffset = u32(rom, trackPointerOffset) - GBA_ROM_BASE
            val (trackEvents, loopStart) = decodeTrack(rom, trackRomOffset)
            tracks += trackEvents
            loops += loopStart
        }
        val blockCount = u8(rom, offset + 1)
        val priority = u8(rom, offset + 2)
        val reverb = u8(rom, offset + 3)
        val voicegroup = u32(rom, offset + 4)
        return Song(blockCount, priority, reverb, voicegroup, tracks, loops)
    }

    /**
     * Decodes one m4a track byte stream starting at ROM file offset [start], until its `FINE`.
     *
     * Walks the stream in byte order, tracking running status, following `PATT` calls (one return
     * level, whereas the engine keeps 3: `patternStack[3]`) and `REPT` repeats. `GOTO` is
     * recorded as an event but not followed: decoding continues after its operand, relying on
     * the track ending with `FINE` there. Returns the events and the clock time of the first
     * `GOTO` target that was already decoded (the loop start), or -1 if there is none.
     *
     * NOTE: possible mismatches with the engine (not fixed here):
     * - `REPT` (`ply_rept`, src/m4a_1.s) jumps to its target without pushing a return address and
     *   jumps count - 1 times; count 0 jumps unconditionally. This decoder treats it as a pattern
     *   call that returns to the `REPT` and jumps `count` times (count 0 as 1).
     * - `XCMD` operands vary by command number (`gXcmdTable`, src/m4a_tables.c): `ply_xwave`
     *   (src/m4a.c) takes 4 bytes, `ply_xxx` none, the rest 1; exactly 2 bytes are consumed here.
     * - Commands `0xB6..0xB8`, `0xC6`, `0xC7`, `0xC9..0xCC` are not handled; most throw.
     */
    private fun decodeTrack(rom: ByteArray, start: Int): Pair<List<Event>, Int> {
        val events = ArrayList<Event>()
        val offsetToTime = HashMap<Int, Int>()
        var cursor = start
        var currentClock = 0
        var runningStatus = 0
        var currentKey = DEFAULT_MIDI_KEY
        var currentVelocity = DEFAULT_VELOCITY
        var patternReturn = -1
        var reptCountLeft = 0
        var reptReturnOffset = -1
        var loopStartTime = -1

        fun readOptionalArg(): Int? =
            if (u8(rom, cursor) < 0x80) u8(rom, cursor++) else null

        // Stops a malformed stream that never reaches FINE (e.g. a PATT loop) from hanging.
        var safetyGuard = 0
        while (safetyGuard++ < 1_000_000) {
            offsetToTime.putIfAbsent(cursor, currentClock)
            var cmd = u8(rom, cursor)
            if (cmd >= 0x80) {
                cursor++
            } else {
                require(runningStatus != 0) {
                    "m4a: argument byte without running status at 0x${hexUpper(cursor)}"
                }
                cmd = runningStatus
            }
            if (cmd >= CMD_RUNNING_STATUS_MIN) {
                runningStatus = cmd
            }

            when {
                cmd <= CMD_WAIT_MAX -> {
                    currentClock += CLOCKS[cmd - CMD_WAIT_MIN]
                }
                cmd == CMD_FINE -> {
                    events += Event(currentClock, "END", emptyList())
                    break
                }
                cmd == CMD_GOTO -> {
                    val targetRomOffset = u32(rom, cursor) - GBA_ROM_BASE
                    val targetTime = offsetToTime[targetRomOffset] ?: -1
                    if (loopStartTime < 0) {
                        loopStartTime = targetTime
                    }
                    events += Event(currentClock, "GOTO", listOf(targetTime))
                    cursor += 4
                }
                cmd == CMD_PATT -> {
                    patternReturn = cursor + 4
                    cursor = u32(rom, cursor) - GBA_ROM_BASE
                }
                cmd == CMD_PEND -> {
                    if (patternReturn >= 0) {
                        cursor = patternReturn
                        patternReturn = -1
                    }
                }
                cmd == CMD_REPT -> {
                    // See the NOTE in the KDoc: REPT is modelled as a pattern call that returns
                    // here, which differs from ply_rept in the engine.
                    if (reptReturnOffset != cursor) {
                        reptReturnOffset = cursor
                        reptCountLeft = maxOf(1, u8(rom, cursor))
                    }
                    if (reptCountLeft-- > 0) {
                        patternReturn = cursor - 1
                        cursor = u32(rom, cursor + 1) - GBA_ROM_BASE
                    } else {
                        cursor += 5
                        reptReturnOffset = -1
                    }
                }
                cmd == CMD_MEMACC -> {
                    events += Event(currentClock, "MEMACC", List(3) { u8(rom, cursor + it) })
                    cursor += 3
                }
                cmd == CMD_XCMD -> {
                    events += Event(currentClock, "XCMD", List(2) { u8(rom, cursor + it) })
                    cursor += 2
                }
                cmd in CMD_PARAM_MIN..CMD_PARAM_MAX -> {
                    val name = PARAMS[cmd] ?: throw IllegalArgumentException(
                        "m4a: unknown command 0x${hexUpper(cmd)} at 0x${hexUpper(cursor - 1)}",
                    )
                    events += Event(currentClock, name, listOf(u8(rom, cursor++)))
                }
                cmd == CMD_EOT -> {
                    readOptionalArg()?.let { currentKey = it }
                    events += Event(currentClock, "EOT", listOf(currentKey))
                }
                cmd == CMD_TIE -> {
                    readOptionalArg()?.let {
                        currentKey = it
                        readOptionalArg()?.let { vel -> currentVelocity = vel }
                    }
                    events += Event(currentClock, "TIE", listOf(currentKey, currentVelocity))
                }
                else -> {
                    var gateLength = CLOCKS[cmd - CMD_TIE]
                    readOptionalArg()?.let {
                        currentKey = it
                        readOptionalArg()?.let { vel ->
                            currentVelocity = vel
                            readOptionalArg()?.let { extra -> gateLength += extra }
                        }
                    }
                    events += Event(
                        currentClock, "NOTE", listOf(currentKey, currentVelocity, gateLength),
                    )
                }
            }
        }
        return events to loopStartTime
    }

    /**
     * Converts [song] to a Standard MIDI File (SMF format 1, 24 ticks per quarter note = m4a
     * clocks).
     *
     * Structure:
     * - Track 0: tempo events. `ply_tempo` (src/m4a_1.s) stores 2 x the TEMPO argument as the
     *   tempo, taken here as BPM, so microseconds per quarter = 60,000,000 / (argument * 2);
     *   NOTE: a TEMPO argument of 0 would divide by zero here.
     * - Tracks 1..N: one per m4a track, on MIDI channel `trackIndex % 16` (so tracks 16 and above
     *   share channels with earlier ones; `MAX_MUSICPLAYER_TRACKS` is 16).
     * - Controllers: VOICE -> Program Change, VOL -> CC7, PAN -> CC10, MOD -> CC1, LFOS -> CC21,
     *   LFODL -> CC26, MODT -> CC22, TUNE -> CC24, PRIO -> CC33, BENDR -> RPN 0, BEND -> Pitch Bend.
     * - Loop markers: a `[` marker at each GOTO's target time and a `]` marker at its own time.
     * - Ties still open at `FINE` are released there.
     */
    public fun toMidi(song: Song): ByteArray {
        val tracks = ArrayList<ByteArray>()
        val tempoEvents = song.tracks.flatten().filter { it.type == "TEMPO" }.sortedBy { it.time }
        val tempoTrackMessages = tempoEvents.map { e ->
            val microsPerQuarter = MICROS_PER_MINUTE / (e.args[0] * 2)
            val tempoMeta = byteArrayOf(MIDI_META_EVENT.toByte(), META_TEMPO.toByte(), 3)
            e.time to (tempoMeta + be24(microsPerQuarter))
        }
        tracks += track(tempoTrackMessages)

        for ((trackIndex, events) in song.tracks.withIndex()) {
            val channel = trackIndex % MIDI_CHANNEL_COUNT
            var keyShift = 0
            val midiMessages = ArrayList<Pair<Int, ByteArray>>()
            val tieKeys = ArrayDeque<Int>()

            for (e in events) {
                fun cc(controller: Int, value: Int) {
                    midiMessages += e.time to bytes(MIDI_CONTROL_CHANGE or channel, controller, value)
                }

                when (e.type) {
                    "KEYSH" -> keyShift = e.args[0].toByte().toInt()
                    "VOICE" -> midiMessages +=
                        e.time to bytes(MIDI_PROGRAM_CHANGE or channel, e.args[0])
                    "VOL" -> cc(CC_VOLUME, e.args[0])
                    "PAN" -> cc(CC_PAN, e.args[0])
                    "MOD" -> cc(CC_MODULATION, e.args[0])
                    "LFOS" -> cc(CC_LFOS, e.args[0])
                    "LFODL" -> cc(CC_LFODL, e.args[0])
                    "MODT" -> cc(CC_MODT, e.args[0])
                    "TUNE" -> cc(CC_TUNE, e.args[0])
                    "PRIO" -> cc(CC_PRIORITY, e.args[0])
                    "BENDR" -> {
                        cc(CC_RPN_MSB, 0)
                        cc(CC_RPN_LSB, 0)
                        cc(CC_DATA_ENTRY_MSB, e.args[0])
                    }
                    "BEND" -> {
                        // m4a bend is 0..127, centre 64; MIDI pitch bend is 14-bit, centre 8192.
                        val bend14 = (e.args[0] - 64) * 128 + MIDI_PITCH_BEND_CENTER
                        midiMessages += e.time to
                            bytes(MIDI_PITCH_BEND or channel, bend14 and 0x7F, bend14 shr 7)
                    }
                    "NOTE" -> {
                        val key = (e.args[0] + keyShift).coerceIn(0, 127)
                        val velocity = e.args[1]
                        val duration = e.args[2]
                        midiMessages += e.time to bytes(MIDI_NOTE_ON or channel, key, velocity)
                        midiMessages += e.time + duration to bytes(MIDI_NOTE_OFF or channel, key, 0)
                    }
                    "TIE" -> {
                        val key = (e.args[0] + keyShift).coerceIn(0, 127)
                        val velocity = e.args[1]
                        tieKeys += key
                        midiMessages += e.time to bytes(MIDI_NOTE_ON or channel, key, velocity)
                    }
                    "EOT" -> {
                        val key = (e.args[0] + keyShift).coerceIn(0, 127)
                        if (tieKeys.remove(key)) {
                            midiMessages += e.time to bytes(MIDI_NOTE_OFF or channel, key, 0)
                        }
                    }
                    "GOTO" -> if (e.args[0] >= 0) {
                        val marker = bytes(MIDI_META_EVENT, META_MARKER, 1)
                        midiMessages += e.args[0] to (marker + "[".encodeToByteArray())
                        midiMessages += e.time to (marker + "]".encodeToByteArray())
                    }
                    "END" -> {
                        for (key in tieKeys) {
                            midiMessages += e.time to bytes(MIDI_NOTE_OFF or channel, key, 0)
                        }
                        tieKeys.clear()
                    }
                }
            }

            // At equal times note-offs sort before everything else, so a note ending exactly when
            // the next starts does not overlap it; ties are broken by original order.
            val sortedMessages = midiMessages.withIndex().sortedWith(
                compareBy(
                    { it.value.first },
                    { if (it.value.second[0].toInt() and 0xF0 == MIDI_NOTE_OFF) 0 else 1 },
                    { it.index },
                )
            ).map { it.value }

            tracks += track(sortedMessages)
        }

        val out = ByteSink()
        // SMF Header: 'MThd', length 6, format 1, track count, division (ticks per quarter note)
        out.write("MThd".encodeToByteArray())
        out.write(be32(6))
        out.write(bytes(0, 1)) // format 1
        out.write(bytes(tracks.size shr 8, tracks.size and 0xFF))
        out.write(bytes(0, CLOCKS_PER_QUARTER_NOTE))

        for (trackChunk in tracks) {
            out.write("MTrk".encodeToByteArray())
            out.write(be32(trackChunk.size))
            out.write(trackChunk)
        }
        return out.toByteArray()
    }

    /**
     * Reads [count] `ToneData` voices (`struct ToneData`; decomp macros in
     * `asm/macros/music_voice.inc`) at ROM file offset [offset], keeping every byte:
     * `{u8 type, key, length, panSweep; u32 data; u8 attack, decay, sustain, release}`. `type`
     * is 0, 8 or 16 for direct-sound voices, 1/2/9/10 square waves, 3 programmable wave, 0x40
     * key split and 0x80 rhythm (all keys). `data` is the sample / wave / sub-voicegroup
     * pointer (for square voices the macros put the duty cycle there instead); ROM pointers are
     * given as their symbol name when [symbols] (GBA address to name) knows them, else as an
     * unsigned int. Key-split voices hold their key-to-voice table pointer in the 4 trailing
     * bytes, so for them `attack..release` are also reported as `split` (u32).
     */
    public fun readVoicegroup(
        rom: ByteArray,
        offset: Int,
        count: Int = 128,
        symbols: Map<Int, String> = emptyMap(),
    ): List<Map<String, Any>> = List(count) { i ->
        val o = offset + i * TONE_SIZE
        val type = u8(rom, o)
        fun ref(v: Int): Any = symbols[v] ?: v.toLong().and(0xFFFFFFFFL)
        linkedMapOf<String, Any>(
            "type" to type,
            "key" to u8(rom, o + 1),
            "length" to u8(rom, o + 2),
            "panSweep" to u8(rom, o + 3),
            "data" to ref(u32(rom, o + 4)),
            "attack" to u8(rom, o + 8),
            "decay" to u8(rom, o + 9),
            "sustain" to u8(rom, o + 10),
            "release" to u8(rom, o + 11),
        ).also { if (type and 0x40 != 0) it["split"] = ref(u32(rom, o + 8)) }
    }

    /**
     * A direct-sound sample (`struct WaveData`, `include/gba/m4a_internal.h`); [data] holds the
     * `size` signed 8-bit samples.
     */
    public class Wave(
        /** `WaveData.type`; [collectWaves] only accepts 0. */
        public val type: Int,
        /** `WaveData.status`; see [WAVE_LOOP_FLAG] for the loop bit. */
        public val status: Int,
        /** `WaveData.freq`: sample rate x [WAVE_FREQ_SCALE]. */
        public val freq: Int,
        /** `WaveData.loopStart`: sample index where a looping sample restarts. */
        public val loopStart: Int,
        /** Signed 8-bit PCM audio samples. */
        public val data: ByteArray,
    ) {
        /** Whether the sample loops (`status` has [WAVE_LOOP_FLAG]). */
        public val loops: Boolean get() = status and WAVE_LOOP_FLAG != 0

        /** Sample rate in Hz (`freq` / 1024, rounded down). */
        public val sampleRate: Int get() = freq ushr 10
    }

    /** Reads the `WaveData` at ROM file offset [offset]; fails if it runs past the ROM. */
    public fun readWave(rom: ByteArray, offset: Int): Wave {
        val size = u32(rom, offset + 12)
        require(size in 0..MAX_WAVE_SIZE && offset + WAVE_HEADER_SIZE + size <= rom.size) {
            "wave at 0x${hexUpper(offset)}: size $size"
        }
        val type = u8(rom, offset) or (u8(rom, offset + 1) shl 8)
        val status = u8(rom, offset + 2) or (u8(rom, offset + 3) shl 8)
        val freq = u32(rom, offset + 4)
        val loopStart = u32(rom, offset + 8)
        val sampleData = rom.copyOfRange(offset + WAVE_HEADER_SIZE, offset + WAVE_HEADER_SIZE + size)
        return Wave(type, status, freq, loopStart, sampleData)
    }

    /**
     * Collects the ROM file offsets of every direct-sound sample (voice types 0/8/16) reachable
     * from [voicegroups] (ROM file offsets), in first-seen order. Scans 128 voices per group and
     * follows the sub-voicegroup of key-split (0x40) and rhythm (0x80) voices one level only.
     * A sample is accepted only if its header looks plausible (type 0, no status bits other than
     * 0x4000, size 1..[MAX_WAVE_SIZE]); a heuristic, since the ROM has no table of samples.
     */
    public fun collectWaves(rom: ByteArray, voicegroups: Collection<Int>): List<Int> {
        val waves = LinkedHashSet<Int>()
        val visited = HashSet<Int>()

        fun plausible(o: Int) =
            o in 0 until rom.size - WAVE_HEADER_SIZE &&
                u16w(rom, o) == 0 &&
                (u16w(rom, o + 2) and 0xBFFF) == 0 &&
                u32(rom, o + 12) in 1..MAX_WAVE_SIZE

        fun scan(vg: Int, depth: Int) {
            if (!visited.add(vg)) return
            for (i in 0 until 128) {
                val o = vg + i * TONE_SIZE
                if (o + TONE_SIZE > rom.size) return
                val type = u8(rom, o)
                val ptr = u32(rom, o + 4) - GBA_ROM_BASE
                when {
                    type == 0 || type == 8 || type == 16 -> if (plausible(ptr)) waves += ptr
                    (type == 0x40 || type == 0x80) && depth == 0 && ptr in 0 until rom.size -> scan(ptr, 1)
                }
            }
        }
        for (vg in voicegroups) scan(vg, 0)
        return waves.toList()
    }

    /**
     * Serializes [w] as a RIFF WAVE file (8-bit unsigned mono PCM at [Wave.sampleRate]); the
     * signed m4a samples are shifted by 128. Looping samples add a `smpl` chunk with one forward
     * loop from `loopStart` to the last sample.
     */
    public fun toWav(w: Wave): ByteArray {
        val body = ByteSink()
        fun le32(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, v ushr 24)
        fun le16(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF)

        body.write("WAVE".encodeToByteArray())
        // fmt chunk: PCM format (1), mono (1 channel), sampleRate, byteRate, blockAlign (1), 8 bits per sample
        body.write("fmt ".encodeToByteArray())
        body.write(le32(16)) // fmt chunk size
        body.write(le16(1))  // PCM format tag
        body.write(le16(1))  // 1 channel (mono)
        body.write(le32(w.sampleRate)) // sample rate
        body.write(le32(w.sampleRate)) // byte rate (sampleRate * channels * bits/8)
        body.write(le16(1))  // block align
        body.write(le16(8))  // bits per sample

        // data chunk: convert signed 8-bit (-128..127) to unsigned 8-bit (0..255)
        body.write("data".encodeToByteArray())
        body.write(le32(w.data.size))
        val unsignedPcm = ByteArray(w.data.size) { (w.data[it] + 128).toByte() }
        body.write(unsignedPcm)
        if (w.data.size % 2 == 1) {
            body.write(0) // word-aligned padding
        }

        if (w.loops) {
            // smpl chunk: sampler chunk with one loop region
            body.write("smpl".encodeToByteArray())
            body.write(le32(36 + 24))
            val periodNanoseconds = 1_000_000_000 / maxOf(1, w.sampleRate)
            // Sampler chunk header: manufacturer (0), product (0), sample period, unity note 60, pitch fraction 0,
            // SMPTE format 0, SMPTE offset 0, loop count 1, sampler data size 0
            for (v in intArrayOf(0, 0, periodNanoseconds, 60, 0, 0, 0, 1, 0)) {
                body.write(le32(v))
            }
            // Loop record: cue point ID 0, type 0 (forward), start sample, end sample, fraction 0, play count 0 (infinite)
            for (v in intArrayOf(0, 0, w.loopStart, w.data.size - 1, 0, 0)) {
                body.write(le32(v))
            }
        }

        val out = ByteSink()
        out.write("RIFF".encodeToByteArray())
        out.write(le32(body.size))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /** Reads an unsigned little-endian halfword at [offset] in [bytes]. */
    private fun u16w(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /**
     * Builds a standard MIDI track chunk from a list of timestamped MIDI event byte sequences.
     * Encodes delta times using variable-length quantity (VLQ) and appends End of Track (`0xFF 0x2F 0x00`).
     */
    private fun track(messages: List<Pair<Int, ByteArray>>): ByteArray {
        val sink = ByteSink()
        var lastTime = 0
        for ((time, msg) in messages) {
            vlq(sink, time - lastTime)
            sink.write(msg)
            lastTime = time
        }
        vlq(sink, 0)
        sink.write(bytes(MIDI_META_EVENT, META_END_OF_TRACK, 0))
        return sink.toByteArray()
    }

    /** Encodes integer [value] as a MIDI Variable-Length Quantity (VLQ) into [sink]. */
    private fun vlq(sink: ByteSink, value: Int) {
        var buffer = value and 0x7F
        var remaining = value ushr 7
        while (remaining > 0) {
            buffer = (buffer shl 8) or 0x80 or (remaining and 0x7F)
            remaining = remaining ushr 7
        }
        while (true) {
            sink.write(buffer and 0xFF)
            if (buffer and 0x80 != 0) {
                buffer = buffer ushr 8
            } else {
                break
            }
        }
    }

    /** Packs the low 8 bits of each of [values] into a byte array. */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** Big-endian 3-byte form of [value] (MIDI tempo meta event payload). */
    private fun be24(value: Int): ByteArray =
        bytes((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

    /** Big-endian 4-byte form of [value] (MIDI chunk lengths). */
    private fun be32(value: Int): ByteArray =
        bytes(value ushr 24, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

    /** Reads an unsigned byte. */
    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    /** Reads a little-endian 32-bit word (pointers and sizes). */
    private fun u32(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or
            (u8(bytes, offset + 1) shl 8) or
            (u8(bytes, offset + 2) shl 16) or
            (u8(bytes, offset + 3) shl 24)
}
