package gba.vfs

/**
 * Parsed `mod.json` manifest of a mod folder or zip. The loader (not written yet; M7 per
 * `MODDING.md`) is meant to validate dependencies, sort by dependencies then [loadOrder]
 * (`docs/decisions/0003-mod-vfs.md`), and mount each mod as a [VfsLayer].
 *
 * @property id reverse-DNS identifier, unique, e.g. `com.example.hardmode`.
 * @property version semantic version string.
 * @property name display name.
 * @property dependencies map of mod id to a version requirement (e.g. `>=1.0.0`).
 * @property loadOrder tie-breaker after dependencies; lower loads first (and is therefore
 *   overridden by higher).
 * @property formatVersion manifest schema version ([CURRENT_FORMAT]); see MODDING.md.
 */
public data class ModManifest(
    val id: String,
    val version: String,
    val name: String,
    val dependencies: Map<String, String> = emptyMap(),
    val loadOrder: Int = 0,
    val formatVersion: Int = CURRENT_FORMAT,
) {
    /** Manifest format constants. */
    public companion object {
        /** Current manifest format version. */
        public const val CURRENT_FORMAT: Int = 1
    }
}
