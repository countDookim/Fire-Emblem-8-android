package gbafe.text

/**
 * Readable control-code tags used in localization text files (e.g. `{NL}`, `{WAIT}`, `{PAUSE 30}`).
 *
 * This is a planned modding-facing tag set, not a decomp structure, and nothing in `game/` uses it
 * yet; the tags that the extracted `messages.json` really contains are the names of
 * [GbaFeTextCodes] (and [MessageCodec] reads and writes those). Each entry below names the original
 * control code it corresponds to, where there is one.
 *
 * @property tag tag name as written between braces.
 * @property argCount number of integer parameters accepted by this tag.
 */
public enum class TextTag(public val tag: String, public val argCount: Int) {
    /** Line break inside a dialog or description text box (`{NL}`); `CHFE_L_NL` (0x01). */
    NEWLINE("NL", 0),

    /** Wait for the player's input before the text goes on (`{A}`); `CHFE_L_A` (0x03). */
    WAIT("A", 0),

    /**
     * Pause text printing for the given number of frames (`{PAUSE frames}`). The original has no
     * code with a frame argument: it has the fixed pauses `CHFE_L_Pause8/16/32/64` (0x04..0x07),
     * whose lengths `GetTalkPauseCmdDuration` looks up.
     */
    PAUSE("PAUSE", 1),

    /**
     * Substitute the player's tactician name (`{PLAYER}`); the original code is the two-byte
     * `[Tact]` (`0x80 0x20`), handled in `TalkInterpret` with `GetTacticianName`.
     */
    PLAYER("PLAYER", 0),
    ;

    /** Lookup of tags by the name written between braces. */
    public companion object {
        private val byTag = entries.associateBy { it.tag }

        /** Looks up a tag by its name, or null if unknown. */
        public fun of(tag: String): TextTag? = byTag[tag]
    }
}
