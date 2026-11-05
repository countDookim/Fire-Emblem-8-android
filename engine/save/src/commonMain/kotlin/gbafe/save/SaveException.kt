package gbafe.save

/** Typed failure of reading, migrating or validating a save container. */
public sealed class SaveException(message: String) : RuntimeException(message) {
    /** The bytes are not a valid container (bad magic, truncated, bad checksum, bad table). */
    public class Corrupt(
        /** Human-readable reason. */
        public val reason: String,
    ) : SaveException("Corrupt save: $reason")

    /** The save was written by a newer version than this build understands. */
    public class NewerVersion(
        /** What is versioned: `container` or a section id. */
        public val what: String,
        /** The version found in the save. */
        public val found: Int,
        /** The newest version this build supports. */
        public val supported: Int,
    ) : SaveException("Save $what is version $found, newest supported is $supported")

    /** An older section version has no registered migration step. */
    public class MissingMigration(
        /** Section id. */
        public val sectionId: String,
        /** Version without a registered upgrade step. */
        public val fromVersion: Int,
    ) : SaveException("No migration for section $sectionId from version $fromVersion")
}
