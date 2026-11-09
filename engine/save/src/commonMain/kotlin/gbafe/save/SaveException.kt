package gbafe.save

/** Typed exception hierarchy for save container deserialization, validation, and migration failures. */
public sealed class SaveException(message: String) : RuntimeException(message) {
    /**
     * Thrown when save container bytes are invalid or corrupt (bad magic signature, truncated buffer,
     * header CRC mismatch, section CRC mismatch, or duplicate section ids).
     */
    public class Corrupt(
        /** Human-readable description of why the save data is considered corrupt. */
        public val reason: String,
    ) : SaveException("Corrupt save: $reason")

    /**
     * Thrown when the save container or a section payload has a version number higher than supported
     * by the current application build.
     */
    public class NewerVersion(
        /** Component that exceeded version: `"container"` or a section id like `"core"`. */
        public val what: String,
        /** The version number found in the save file. */
        public val found: Int,
        /** The maximum version number supported by this build. */
        public val supported: Int,
    ) : SaveException("Save $what is version $found, newest supported is $supported")

    /**
     * Thrown when an older section requires migration to the current version, but no migration
     * step is registered in [MigrationRegistry] for that version transition.
     */
    public class MissingMigration(
        /** Section identifier requiring migration. */
        public val sectionId: String,
        /** Source version without a registered upgrade step. */
        public val fromVersion: Int,
    ) : SaveException("No migration for section $sectionId from version $fromVersion")
}
