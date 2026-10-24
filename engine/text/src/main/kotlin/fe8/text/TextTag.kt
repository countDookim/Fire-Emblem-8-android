package fe8.text

/**
 * The readable control-code tags used in localisation files, e.g. `{PLAYER}`, `{PAUSE 30}`. The
 * extractor (milestone 2) converts the ROM's binary control codes into these; the text system parses
 * them once at load into a compact `IntArray` stream.
 *
 * @property tag name as written between braces.
 * @property argCount number of integer arguments.
 */
public enum class TextTag(public val tag: String, public val argCount: Int) {
    /** Line break inside a text box. */
    NEWLINE("NL", 0),

    /** Wait for a button press, then clear. */
    WAIT("A", 0),

    /** Pause for N frames. */
    PAUSE("PAUSE", 1),

    /** Insert the tactician's name. */
    PLAYER("PLAYER", 0),
    ;

    public companion object {
        private val byTag = entries.associateBy { it.tag }

        /** Looks up a tag by its name, or null if unknown. */
        public fun of(tag: String): TextTag? = byTag[tag]
    }
}
