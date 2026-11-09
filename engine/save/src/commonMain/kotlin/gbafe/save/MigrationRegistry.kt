package gbafe.save

/** Upgrades the payload of one section from version `n` to `n + 1`. */
public fun interface Migration {
    /** Returns the payload migrated by one version. */
    public fun migrate(data: ByteArray): ByteArray
}

/**
 * Chained, per-section migrations keyed by (section id, from-version). A section id with a declared
 * current version is upgraded step by step on [upgrade]; ids without one (typically unknown `mod:`
 * sections) pass through unchanged.
 */
public class MigrationRegistry {
    private val steps = HashMap<String, Migration>()
    private val current = HashMap<String, Int>()

    init {
        current[SaveFormat.CORE_SECTION] = SaveFormat.CORE_VERSION
    }

    /** Declares that [sectionId] is currently at [version] (the version a rewrite stores). */
    public fun declareCurrent(sectionId: String, version: Int) {
        require(version in 1..MAX_VERSION) { "version out of range: $version" }
        current[sectionId] = version
    }

    /** Registers the step upgrading [sectionId] from [fromVersion] to `fromVersion + 1`. */
    public fun register(sectionId: String, fromVersion: Int, migration: Migration) {
        require(fromVersion in 1 until MAX_VERSION) { "fromVersion out of range: $fromVersion" }
        steps[key(sectionId, fromVersion)] = migration
    }

    /** The declared current version of [sectionId], or null if this registry does not know it. */
    public fun currentVersion(sectionId: String): Int? = current[sectionId]

    /**
     * Returns [section] upgraded to its current version by applying each registered step once, in
     * order; returns [section] itself when nothing is to be done.
     *
     * @throws SaveException.NewerVersion if the section is newer than the declared current version.
     * @throws SaveException.MissingMigration if a step in the chain is not registered.
     */
    public fun upgrade(section: SaveSection): SaveSection {
        val target = current[section.id] ?: return section
        if (section.version > target) {
            throw SaveException.NewerVersion(section.id, section.version, target)
        }
        var version = section.version
        var data = section.data
        while (version < target) {
            val step = steps[key(section.id, version)]
                ?: throw SaveException.MissingMigration(section.id, version)
            data = step.migrate(data)
            version++
        }
        return if (version == section.version) section else SaveSection(section.id, version, data)
    }

    private fun key(sectionId: String, fromVersion: Int): String = "$sectionId@$fromVersion"

    /** Version boundary constants. */
    public companion object {
        /** Maximum allowable section version number. */
        public const val MAX_VERSION: Int = 0xFFFF
    }
}
