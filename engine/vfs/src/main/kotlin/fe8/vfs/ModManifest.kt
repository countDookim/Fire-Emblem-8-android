package fe8.vfs

/**
 * Parsed `mod.json` manifest of a mod folder or zip. The loader (milestone 7) validates dependencies,
 * sorts by [loadOrder] then dependency order, and mounts each mod as a [VfsLayer].
 *
 * @property id reverse-DNS identifier, unique, e.g. `com.example.hardmode`.
 * @property version semantic version string.
 * @property name display name.
 * @property dependencies map of mod id to a version requirement (e.g. `>=1.0.0`).
 * @property loadOrder tie-breaker; lower loads first (and is therefore overridden by higher).
 * @property formatVersion manifest schema version; see MODDING.md.
 */
public data class ModManifest(
    val id: String,
    val version: String,
    val name: String,
    val dependencies: Map<String, String> = emptyMap(),
    val loadOrder: Int = 0,
    val formatVersion: Int = CURRENT_FORMAT,
) {
    public companion object {
        /** Current manifest format version. */
        public const val CURRENT_FORMAT: Int = 1
    }
}
