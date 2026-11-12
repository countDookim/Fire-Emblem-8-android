package gbafe.text

import gba.data.ByteSink

/**
 * Lossless conversion between raw message bytes (as produced by [MsgHuffman]) and the readable
 * tagged text stored in `text/<lang>/messages.json`, e.g.
 * `{LoadFace FID_Eirika}{OpenLeft}Hello!{A}`.
 *
 * Grammar of the byte stream (from the game's text interpreter, decomp
 * `src/scene.c: TalkInterpret`): `0x10` (`CHFE_L_LoadFace`, `include/scene.h`) is followed by a
 * 2-byte little-endian face argument (face id + 0x100, or 0xFFFF for the active unit;
 * `TalkLoadFace`); `0x80 xx` is a two-byte control code; `0x81 0x40` is a tab (`TalkInterpret`
 * advances the pen 6 pixels); every other byte stands alone. Which bytes are written as characters
 * rather than tags is this codec's own choice, not the game's: printable ASCII (except `{` and
 * `}`) and `0xA0..0xFF` are characters, everything else a tag.
 *
 * Tags: `{Name}` for a code in [codes], `{LoadFace F}` where `F` is a name in [faces], and
 * `{0xNN ...}` (one or more hex bytes) for anything without a name, so unknown bytes survive a
 * round trip. When encoding, a tag may mix names and hex bytes (`{LoadFace 0x02 0x01}`); every name
 * in [codes] is accepted, including aliases of the same bytes (decoding uses the first name
 * listed).
 *
 * Load/extract time only, like [MsgHuffman].
 */
public class MessageCodec(
    /** Control-code names to bytes; 1 byte, or 2 bytes starting with `0x80`/`0x81`. */
    private val codes: Map<String, ByteArray> = GbaFeTextCodes.CONTROL,
    /**
     * Face argument names (`FID_*`) to their 2-byte value `lo | hi << 8` as stored after
     * `LoadFace`.
     */
    private val faces: Map<String, Int> = emptyMap(),
) {
    /** Decoding table for one-byte codes: byte to the first name listed for it. */
    private val single = HashMap<Int, String>()

    /**
     * Decoding table for two-byte codes: `lead shl 8 or second` to the first name listed for it.
     */
    private val pair = HashMap<Int, String>()

    /** Decoding table for `LoadFace` arguments: 16-bit value to its `FID_*` name. */
    private val faceNames = HashMap<Int, String>()

    init {
        for ((name, b) in codes) {
            require(name.isNotEmpty() && name.none { it == ' ' || it == '{' || it == '}' } && !isHex(name)) { "bad code name '$name'" }
            when (b.size) {
                1 -> single.putIfAbsent(b[0].u8, name)
                2 -> pair.putIfAbsent(b[0].u8 shl 8 or b[1].u8, name)
                else -> throw IllegalArgumentException("code $name must be 1 or 2 bytes")
            }
        }
        for ((name, v) in faces) {
            require(name !in codes && !isHex(name) && ' ' !in name) { "bad face name '$name'" }
            faceNames.putIfAbsent(v and 0xFFFF, name)
        }
    }

    /**
     * Converts message [bytes] (ending in the 0 terminator) to tagged text (without the
     * terminator).
     */
    public fun decode(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.last().toInt() == 0) { "message must end with the 0 terminator" }
        val n = bytes.size - 1
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val b = bytes[i].u8
            if (b == LOAD_FACE && i + 2 < n && single[b] != null) {
                val v = bytes[i + 1].u8 or (bytes[i + 2].u8 shl 8)
                val arg = faceNames[v] ?: "${hex(bytes[i + 1].u8)} ${hex(bytes[i + 2].u8)}"
                sb.append('{').append(single[b]).append(' ').append(arg).append('}')
                i += 3
            } else if ((b == 0x80 || b == 0x81) && i + 1 < n) {
                val name = pair[b shl 8 or bytes[i + 1].u8]
                if (name != null) sb.append('{').append(name).append('}')
                else sb.append('{').append(hex(b)).append(' ').append(hex(bytes[i + 1].u8)).append('}')
                i += 2
            } else {
                when {
                    isLiteral(b) -> sb.append(b.toChar())
                    single[b] != null -> sb.append('{').append(single[b]).append('}')
                    else -> sb.append('{').append(hex(b)).append('}')
                }
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Converts tagged [text] back to message bytes, including the 0 terminator.
     *
     * @throws IllegalArgumentException on unknown tag names, malformed tags, or characters that
     *   have no single-byte encoding.
     */
    public fun encode(text: String): ByteArray {
        val out = ByteSink()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                val end = text.indexOf('}', i)
                require(end > i + 1) { "unterminated or empty tag at $i in '$text'" }
                for (tok in text.substring(i + 1, end).split(' ')) {
                    when {
                        isHex(tok) -> out.write(tok.substring(2).toInt(16))
                        tok in codes -> out.write(codes.getValue(tok))
                        tok in faces -> faces.getValue(tok).let { out.write(it and 0xFF); out.write(it ushr 8 and 0xFF) }
                        else -> throw IllegalArgumentException("unknown tag '$tok' in '$text'")
                    }
                }
                i = end + 1
            } else {
                require(c != '}' && isLiteral(c.code)) { "character '$c' (U+${c.code.toString(16).uppercase().padStart(4, '0')}) has no encoding" }
                out.write(c.code)
                i++
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    private companion object {
        /** `CHFE_L_LoadFace` (`include/scene.h`): the only code followed by a 2-byte argument. */
        const val LOAD_FACE = 0x10
        val Byte.u8 get() = toInt() and 0xFF
        fun isLiteral(b: Int) = (b in 0x20..0x7E && b != '{'.code && b != '}'.code) || b in 0xA0..0xFF
        fun hex(b: Int) = "0x" + b.toString(16).uppercase().padStart(2, '0')
        fun isHex(s: String) = s.length in 3..4 && s.startsWith("0x") && s.substring(2).all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
    }
}

/**
 * Control-code names as defined by the FE8 decomp's `texts/textdefs.txt` (pinned commit 04d1edb);
 * the values are FE8U's and are not checked against other GBA Fire Emblem games. `[X]` (0, the
 * terminator) is omitted: it never appears inside a decoded message. Aliases keep textdefs' order;
 * the first name of a byte sequence wins when decoding. Face names (`FID_*`) are game-specific and
 * passed to [MessageCodec] separately.
 *
 * Names follow textdefs, not `include/scene.h`: textdefs `FastPrint` is 29 (0x1D,
 * `CHFE_L_FastPrint2` in scene.h, which textdefs marks with `// ?`) while scene.h's
 * `CHFE_L_FastPrint` is 0x13, which textdefs does not name. The four `....` codes 4..7 are
 * `CHFE_L_Pause8/16/32/64`. `.` (0x1F) is `CHFE_L_DEnd`; `SetMsgTerminator` turns trailing ones
 * into terminators.
 */
public object GbaFeTextCodes {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Name to bytes, in textdefs order. */
    public val CONTROL: Map<String, ByteArray> = linkedMapOf(
        "NL" to b(1), "NL2" to b(2), "LF" to b(1), "CR" to b(2), "A" to b(3),
        "...." to b(4), "....." to b(5), "......" to b(6), "......." to b(7),
        "OpenFarLeft" to b(8), "OpenMidLeft" to b(9), "OpenLeft" to b(10), "OpenRight" to b(11),
        "OpenMidRight" to b(12), "OpenFarRight" to b(13), "OpenFarFarLeft" to b(14), "OpenFarFarRight" to b(15),
        "LoadFace" to b(16), "ClearFace" to b(17), "NormalPrint" to b(18), "FastPrint" to b(29),
        "CloseSpeechFast" to b(20), "CloseSpeechSlow" to b(21), "ToggleMouthMove" to b(22), "ToggleSmile" to b(23),
        "Yes" to b(24), "No" to b(25), "BuySell" to b(26), "ShopContinue" to b(27), "SendToBack" to b(28),
        "." to b(31), "HASH" to b(0x23), "DashedLine" to b(0x7F),
        "LoadOverworldFaces" to b(0x80, 0x04), "BreakTalk" to b(0x80, 0x04), "G" to b(0x80, 0x05),
        "MoveFarLeft" to b(0x80, 0x0A), "MoveMidLeft" to b(0x80, 0x0B), "MoveLeft" to b(0x80, 0x0C),
        "MoveRight" to b(0x80, 0x0D), "MoveMidRight" to b(0x80, 0x0E), "MoveFarRight" to b(0x80, 0x0F),
        "MoveFarFarLeft" to b(0x80, 0x10), "MoveFarFarRight" to b(0x80, 0x11),
        "EnableBlinking" to b(0x80, 0x16), "Unknown8017" to b(0x80, 0x17), "DelayBlinking" to b(0x80, 0x18),
        "PauseBlinking" to b(0x80, 0x19), "DisableBlinking" to b(0x80, 0x1B), "OpenEyes" to b(0x80, 0x1C),
        "CloseEyes" to b(0x80, 0x1D), "HalfCloseEyes" to b(0x80, 0x1E), "Wink" to b(0x80, 0x1F),
        "Tact" to b(0x80, 0x20), "ToggleRed" to b(0x80, 0x21), "Item" to b(0x80, 0x22), "SetName" to b(0x80, 0x23),
        "ToggleColorInvert" to b(0x80, 0x25), "TAB" to b(0x81, 0x40), "LQuote" to b(0x93), "RQuote" to b(0x94),
        "AccentedE" to b(0xE9),
    )

    /** The active unit's portrait as a `LoadFace` argument (`[FID_Active] = 0xFF, 0xFF`). */
    public const val FACE_ACTIVE: Int = 0xFFFF
}
