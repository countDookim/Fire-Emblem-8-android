package gbafe.text

/**
 * Readable control-code tags used in localization text files (e.g. `{NL}`, `{WAIT}`, `{PAUSE 30}`).
 *
 * The extractor converts binary ROM text control codes into these tags; the text system parses
 * them into compact token streams at runtime.
 *
 * @property tag tag name as written between braces.
 * @property argCount number of integer parameters accepted by this tag.
 */
public enum class TextTag(public val tag: String, public val argCount: Int) {
    /** Line break inside a dialog or description text box (`{NL}`). */
    NEWLINE("NL", 0),

    /** Wait for button press (A button), then clear the text window (`{A}`). */
    WAIT("A", 0),

    /** Pause text printing for specified number of frames (`{PAUSE frames}`). */
    PAUSE("PAUSE", 1),

    /** Dynamically substitute the player tactician's name (`{PLAYER}`). */
    PLAYER("PLAYER", 0),
    ;

    /** Name-based lookup of tags. */
    public companion object {
        private val byTag = entries.associateBy { it.tag }

        /** Looks up a tag by its name, or null if unknown. */
        public fun of(tag: String): TextTag? = byTag[tag]
    }
}
