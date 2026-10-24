package fe8.save

/**
 * Versioned, extensible save container: a header with [CURRENT_VERSION], the core game section, and
 * one opaque section per mod keyed by mod id. Older versions are upgraded by registered [Migration]s.
 * Import from the original 32 KiB `.sav` arrives in milestone 5.
 */
public object SaveFormat {
    /** Current container version. Bump and add a [Migration] for every layout change. */
    public const val CURRENT_VERSION: Int = 1
}

/** Upgrades a save from [fromVersion] to `fromVersion + 1`. */
public fun interface Migration {
    /** Returns the migrated bytes of the core section. */
    public fun migrate(core: ByteArray): ByteArray

    /** Placeholder so implementations can override; vanilla starts at version 1. */
    public val fromVersion: Int get() = 1
}
