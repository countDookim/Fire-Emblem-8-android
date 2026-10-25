package gbafe.extract.m4a

import java.io.ByteArrayOutputStream

/**
 * Decoder for the GBA m4a ("MusicPlayer2000"/Sappy) song format shared by FE6/7/8 (decomp `src/m4a.c`,
 * `asm/macros/m4a.inc`, `include/m4a.h`): `SongHeader {u8 trackCount, blockCount, priority, reverb; u32 tone;
 * u32 part[trackCount]}`, each part a byte stream of commands (0x80–0xB0 WAIT, 0xB1 FINE, 0xB2 GOTO, 0xB3 PATT,
 * 0xB4 PEND, 0xB5 REPT, 0xB9 MEMACC, 0xBA–0xC8 parameter commands, 0xCD XCMD, 0xCE EOT, 0xCF TIE, 0xD0–0xFF
 * N01–N96). Bytes below 0x80 repeat the last command ≥ 0xBD with new arguments (running status). Note and TIE
 * arguments (key, velocity, gate extra) are optional and sticky. Time is in m4a clocks (24 per quarter note).
 *
 * The decoder follows one pass: PATT is a one-level call, REPT repeats its pattern, GOTO becomes a `GOTO`
 * event (arg = loop start time) and decoding continues to FINE, because mid2agb writes the track's tail after
 * the loop jump (unreachable in game, but part of the data). Every track ends with an `END` event at FINE.
 */
public object M4aSong {
    /** `gClockTable`: WAIT/N lengths in clocks for command - 0x80 (WAIT) or - 0xCF (N01 = index 1). */
    public val CLOCKS: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        28, 30, 32, 36, 40, 42, 44, 48, 52, 54, 56, 60, 64, 66, 68, 72, 76, 78, 80, 84, 88, 90, 92, 96,
    )

    /** One decoded event at [time] (clocks). [type] is the m4a mnemonic (`NOTE`, `TIE`, `EOT`, `VOL`, ...). */
    public data class Event(val time: Int, val type: String, val args: List<Int>)

    /** A decoded song: header fields, and per track its events plus the loop start time (GOTO target) or -1. */
    public class Song(
        public val blockCount: Int, public val priority: Int, public val reverb: Int, public val voicegroup: Int,
        public val tracks: List<List<Event>>, public val loopStarts: List<Int>,
    )

    /** One song table entry (`.4byte header; .2byte ms, me`, decomp `asm/macros/m4a.inc: song`). */
    public data class SongEntry(val header: Int, val musicPlayer: Int, val unknown: Int)

    /** Reads [count] 8-byte song table entries at ROM offset [offset]. */
    public fun readSongTable(rom: ByteArray, offset: Int, count: Int): List<SongEntry> = List(count) {
        val o = offset + 8 * it
        SongEntry(u32(rom, o), u8(rom, o + 4) or (u8(rom, o + 5) shl 8), u8(rom, o + 6) or (u8(rom, o + 7) shl 8))
    }

    /** Decodes the song header at ROM offset [offset]. */
    public fun decode(rom: ByteArray, offset: Int): Song {
        val n = u8(rom, offset)
        val tracks = ArrayList<List<Event>>()
        val loops = ArrayList<Int>()
        for (t in 0 until n) {
            val (ev, loop) = decodeTrack(rom, u32(rom, offset + 8 + 4 * t) - ROM)
            tracks += ev
            loops += loop
        }
        return Song(u8(rom, offset + 1), u8(rom, offset + 2), u8(rom, offset + 3), u32(rom, offset + 4), tracks, loops)
    }

    private fun decodeTrack(rom: ByteArray, start: Int): Pair<List<Event>, Int> {
        val out = ArrayList<Event>()
        val posTime = HashMap<Int, Int>()
        var p = start
        var time = 0
        var status = 0
        var key = 60
        var vel = 127
        var ret = -1
        var reptLeft = 0
        var reptReturn = -1
        var loop = -1
        fun optional(): Int? = if (u8(rom, p) < 0x80) u8(rom, p++) else null
        var guard = 0
        while (guard++ < 1_000_000) {
            posTime.putIfAbsent(p, time)
            var b = u8(rom, p)
            if (b >= 0x80) p++ else { require(status != 0) { "m4a: argument byte without running status at 0x%X".format(p) }; b = status }
            if (b >= 0xBD) status = b
            when {
                b <= 0xB0 -> time += CLOCKS[b - 0x80]
                b == 0xB1 -> { out += Event(time, "END", emptyList()); break }
                b == 0xB2 -> {
                    if (loop < 0) loop = posTime[u32(rom, p) - ROM] ?: -1
                    out += Event(time, "GOTO", listOf(posTime[u32(rom, p) - ROM] ?: -1))
                    p += 4
                }
                b == 0xB3 -> { ret = p + 4; p = u32(rom, p) - ROM }
                b == 0xB4 -> if (ret >= 0) { p = ret; ret = -1 }
                b == 0xB5 -> {
                    // REPT count, pattern: count 0 = forever (treated as once); repeats are calls to the pattern.
                    if (reptReturn != p) { reptReturn = p; reptLeft = maxOf(1, u8(rom, p)) }
                    if (reptLeft-- > 0) { ret = p - 1; p = u32(rom, p + 1) - ROM } else { p += 5; reptReturn = -1 }
                }
                b == 0xB9 -> { out += Event(time, "MEMACC", List(3) { u8(rom, p + it) }); p += 3 }
                b == 0xCD -> { out += Event(time, "XCMD", List(2) { u8(rom, p + it) }); p += 2 }
                b in 0xBA..0xC8 -> {
                    val name = PARAMS[b] ?: throw IllegalArgumentException("m4a: unknown command 0x%X at 0x%X".format(b, p - 1))
                    out += Event(time, name, listOf(u8(rom, p++)))
                }
                b == 0xCE -> { optional()?.let { key = it }; out += Event(time, "EOT", listOf(key)) }
                b == 0xCF -> {
                    optional()?.let { key = it; optional()?.let { v -> vel = v } }
                    out += Event(time, "TIE", listOf(key, vel))
                }
                else -> {
                    var len = CLOCKS[b - 0xCF]
                    optional()?.let { key = it; optional()?.let { v -> vel = v; optional()?.let { g -> len += g } } }
                    out += Event(time, "NOTE", listOf(key, vel, len))
                }
            }
        }
        return out to loop
    }

    private val PARAMS = mapOf(
        0xBA to "PRIO", 0xBB to "TEMPO", 0xBC to "KEYSH", 0xBD to "VOICE", 0xBE to "VOL", 0xBF to "PAN", 0xC0 to "BEND",
        0xC1 to "BENDR", 0xC2 to "LFOS", 0xC3 to "LFODL", 0xC4 to "MOD", 0xC5 to "MODT", 0xC8 to "TUNE",
    )

    /**
     * Standard MIDI file (format 1, 24 ticks per quarter = m4a clocks). Track 0 holds TEMPO (bpm = value × 2);
     * m4a track i → MIDI track i + 1 on channel i % 16. Keys include KEYSH. VOICE → program change, VOL → CC7,
     * PAN → CC10, BEND → pitch bend ((v - 64) × 128 + 8192), BENDR → RPN 0, MOD → CC1, LFOS → CC21, LFODL → CC26,
     * MODT → CC22, TUNE → CC24, PRIO → CC33; GOTO → markers `[` (at the loop start) and `]`; ties still open at
     * END are released there. MEMACC/XCMD are dropped (they stay in the event lists).
     */
    public fun toMidi(song: Song): ByteArray {
        val tracks = ArrayList<ByteArray>()
        val tempo = song.tracks.flatten().filter { it.type == "TEMPO" }.sortedBy { it.time }
        tracks += track(tempo.map { e -> e.time to byteArrayOf(0xFF.toByte(), 0x51, 3) + be24(60_000_000 / (e.args[0] * 2)) })
        for ((i, events) in song.tracks.withIndex()) {
            val ch = i % 16
            var shift = 0
            val msgs = ArrayList<Pair<Int, ByteArray>>()
            val tieKeys = ArrayDeque<Int>()
            for (e in events) {
                fun cc(c: Int, v: Int) { msgs += e.time to bytes(0xB0 or ch, c, v) }
                when (e.type) {
                    "KEYSH" -> shift = e.args[0].toByte().toInt()
                    "VOICE" -> msgs += e.time to bytes(0xC0 or ch, e.args[0])
                    "VOL" -> cc(7, e.args[0]); "PAN" -> cc(10, e.args[0]); "MOD" -> cc(1, e.args[0])
                    "LFOS" -> cc(21, e.args[0]); "LFODL" -> cc(26, e.args[0]); "MODT" -> cc(22, e.args[0])
                    "TUNE" -> cc(24, e.args[0]); "PRIO" -> cc(33, e.args[0])
                    "BENDR" -> { cc(101, 0); cc(100, 0); cc(6, e.args[0]) }
                    "BEND" -> { val v = (e.args[0] - 64) * 128 + 8192; msgs += e.time to bytes(0xE0 or ch, v and 0x7F, v shr 7) }
                    "NOTE" -> {
                        val k = (e.args[0] + shift).coerceIn(0, 127)
                        msgs += e.time to bytes(0x90 or ch, k, e.args[1])
                        msgs += e.time + e.args[2] to bytes(0x80 or ch, k, 0)
                    }
                    "TIE" -> { val k = (e.args[0] + shift).coerceIn(0, 127); tieKeys += k; msgs += e.time to bytes(0x90 or ch, k, e.args[1]) }
                    "EOT" -> {
                        val k = (e.args[0] + shift).coerceIn(0, 127)
                        if (tieKeys.remove(k)) msgs += e.time to bytes(0x80 or ch, k, 0)
                    }
                    "GOTO" -> if (e.args[0] >= 0) {
                        msgs += e.args[0] to (bytes(0xFF, 0x06, 1) + "[".toByteArray())
                        msgs += e.time to (bytes(0xFF, 0x06, 1) + "]".toByteArray())
                    }
                    "END" -> { for (k in tieKeys) msgs += e.time to bytes(0x80 or ch, k, 0); tieKeys.clear() }
                }
            }
            tracks += track(msgs.withIndex().sortedWith(compareBy({ it.value.first }, { if (it.value.second[0].toInt() and 0xF0 == 0x80) 0 else 1 }, { it.index })).map { it.value })
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(be32(6)); out.write(bytes(0, 1)); out.write(bytes(tracks.size shr 8, tracks.size and 0xFF)); out.write(bytes(0, 24))
        for (t in tracks) { out.write("MTrk".toByteArray()); out.write(be32(t.size)); out.write(t) }
        return out.toByteArray()
    }

    /** Size of `ToneData`. */
    public const val TONE_SIZE: Int = 12

    /**
     * Reads [count] `ToneData` entries (decomp `asm/macros/music_voice.inc`) at ROM offset [offset], every byte
     * kept: `{u8 type, key, length, panSweep; u32 data; u8 attack, decay, sustain, release}`. `data` is the sample /
     * wave / sub-voicegroup pointer (a square voice's duty cycle for types 1/2/9/10); ROM pointers are given as their
     * symbol name when [symbols] knows them, else as an int. Keysplit voices (type 0x40) keep their table pointer
     * in the 4 trailing bytes, so `attack..release` are also reported as `split` (u32) for them.
     */
    public fun readVoicegroup(rom: ByteArray, offset: Int, count: Int = 128, symbols: Map<Int, String> = emptyMap()): List<Map<String, Any>> =
        List(count) { i ->
            val o = offset + i * TONE_SIZE
            val type = u8(rom, o)
            fun ref(v: Int): Any = symbols[v] ?: v.toLong().and(0xFFFFFFFFL)
            linkedMapOf<String, Any>(
                "type" to type, "key" to u8(rom, o + 1), "length" to u8(rom, o + 2), "panSweep" to u8(rom, o + 3),
                "data" to ref(u32(rom, o + 4)),
                "attack" to u8(rom, o + 8), "decay" to u8(rom, o + 9), "sustain" to u8(rom, o + 10), "release" to u8(rom, o + 11),
            ).also { if (type and 0x40 != 0) it["split"] = ref(u32(rom, o + 8)) }
        }

    /** A direct-sound sample (`struct WaveData`, decomp `include/gba/m4a_internal.h`); [data] = [size] signed samples. */
    public class Wave(public val type: Int, public val status: Int, public val freq: Int, public val loopStart: Int, public val data: ByteArray) {
        /** Loop flag (`status & 0x4000`, the m4a's WAVE_LOOP). */
        public val loops: Boolean get() = status and 0x4000 != 0

        /** Sample rate in Hz (`freq` is the rate × 1024, the pitch at the sample's base key). */
        public val sampleRate: Int get() = freq ushr 10
    }

    /** Reads the `WaveData` at ROM offset [offset]. */
    public fun readWave(rom: ByteArray, offset: Int): Wave {
        val size = u32(rom, offset + 12)
        require(size in 0..0x400000 && offset + 16 + size <= rom.size) { "wave at 0x%X: size %d".format(offset, size) }
        return Wave(u8(rom, offset) or (u8(rom, offset + 1) shl 8), u8(rom, offset + 2) or (u8(rom, offset + 3) shl 8),
            u32(rom, offset + 4), u32(rom, offset + 8), rom.copyOfRange(offset + 16, offset + 16 + size))
    }

    /**
     * Every direct-sound sample (voice types 0/8/16) reachable from [voicegroups] (ROM offsets), following
     * keysplit (0x40) and drum (0x80) sub-voicegroups one level (128 entries scanned; entries whose wave header
     * is implausible are skipped). Result: wave ROM offsets in first-seen order.
     */
    public fun collectWaves(rom: ByteArray, voicegroups: Collection<Int>): List<Int> {
        val waves = LinkedHashSet<Int>()
        val visited = HashSet<Int>()
        fun plausible(o: Int) = o in 0 until rom.size - 16 && u16w(rom, o) == 0 && (u16w(rom, o + 2) and 0xBFFF) == 0 && u32(rom, o + 12) in 1..0x400000
        fun scan(vg: Int, depth: Int) {
            if (!visited.add(vg)) return
            for (i in 0 until 128) {
                val o = vg + i * TONE_SIZE
                if (o + TONE_SIZE > rom.size) return
                val type = u8(rom, o)
                val ptr = u32(rom, o + 4) - ROM
                when {
                    type == 0 || type == 8 || type == 16 -> if (plausible(ptr)) waves += ptr
                    (type == 0x40 || type == 0x80) && depth == 0 && ptr in 0 until rom.size -> scan(ptr, 1)
                }
            }
        }
        for (vg in voicegroups) scan(vg, 0)
        return waves.toList()
    }

    /** RIFF WAVE, 8-bit unsigned mono at [Wave.sampleRate]; a looping sample gets a `smpl` chunk (loop to the end). */
    public fun toWav(w: Wave): ByteArray {
        val body = ByteArrayOutputStream()
        fun le32(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF, v shr 16 and 0xFF, v ushr 24)
        fun le16(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF)
        body.write("WAVE".toByteArray())
        body.write("fmt ".toByteArray()); body.write(le32(16)); body.write(le16(1)); body.write(le16(1))
        body.write(le32(w.sampleRate)); body.write(le32(w.sampleRate)); body.write(le16(1)); body.write(le16(8))
        body.write("data".toByteArray()); body.write(le32(w.data.size)); body.write(ByteArray(w.data.size) { (w.data[it] + 128).toByte() })
        if (w.data.size % 2 == 1) body.write(0)
        if (w.loops) {
            body.write("smpl".toByteArray()); body.write(le32(36 + 24))
            for (v in intArrayOf(0, 0, 1_000_000_000 / maxOf(1, w.sampleRate), 60, 0, 0, 0, 1, 0)) body.write(le32(v))
            for (v in intArrayOf(0, 0, w.loopStart, w.data.size - 1, 0, 0)) body.write(le32(v))
        }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(le32(body.size())); out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun u16w(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun track(msgs: List<Pair<Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        var last = 0
        for ((t, m) in msgs) { vlq(o, t - last); o.write(m); last = t }
        vlq(o, 0); o.write(bytes(0xFF, 0x2F, 0))
        return o.toByteArray()
    }

    private fun vlq(o: ByteArrayOutputStream, v: Int) {
        var buf = v and 0x7F
        var x = v ushr 7
        while (x > 0) { buf = (buf shl 8) or 0x80 or (x and 0x7F); x = x ushr 7 }
        while (true) { o.write(buf and 0xFF); if (buf and 0x80 != 0) buf = buf ushr 8 else break }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun be24(v: Int) = bytes(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
    private fun be32(v: Int) = bytes(v ushr 24, v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)

    private const val ROM = 0x08000000
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u32(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)
}
