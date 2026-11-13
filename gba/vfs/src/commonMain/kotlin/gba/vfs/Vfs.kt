package gba.vfs

/**
 * One read-only source of files addressed by normalised virtual paths such as `data/items.json`
 * or `maps/ch01.tmj`. Implementations: [DirectoryLayer] (dev overrides, unpacked mods) and
 * `AssetLayer` / the base layer in `:app`; a zip layer for packaged mods does not exist yet (M7
 * per `MODDING.md`).
 */
public interface VfsLayer {
    /** Human-readable origin for diagnostics, e.g. `base`, `mod:com.example.hardmode@1.2.0`. */
    public val origin: String

    /** True if [path] exists in this layer. */
    public fun exists(path: String): Boolean

    /** Reads the whole file at [path]. Only called after [exists] returned true. */
    public fun read(path: String): ByteArray

    /**
     * Lists the files under [dir], recursively (as [DirectoryLayer] does; callers such as
     * `TextureOverrides` rely on that), as virtual paths; empty if [dir] does not exist.
     */
    public fun list(dir: String): List<String>
}

/** Thrown by [VirtualFileSystem.read] when no layer has the file. */
public class VfsFileNotFoundException(
    /** The virtual path that was not found. */
    public val path: String,
) : RuntimeException("not found in any VFS layer: $path")

/**
 * Layered virtual filesystem: the single way game code reads any data, map, script, asset or
 * string.
 *
 * Layers are searched from the **last added** (highest priority) to the first (the base game
 * data), so a mod added later overrides earlier mods and the base by simply providing a file at
 * the same path. Load-time only: nothing here is used inside the per-frame loop (content is
 * compiled into primitive tables at load). See `docs/decisions/0003-mod-vfs.md`.
 */
public class VirtualFileSystem {
    /** Mounted layers, lowest priority first (index 0 is the base game). */
    private val layers = ArrayList<VfsLayer>()

    /** Layers in priority order, lowest first. */
    public val stack: List<VfsLayer> get() = layers

    /** Pushes [layer] on top (highest priority). */
    public fun mount(layer: VfsLayer) {
        layers.add(layer)
    }

    /** Returns the highest-priority layer containing [path], or null. */
    public fun resolve(path: String): VfsLayer? {
        val normalized = normalize(path)
        for (i in layers.indices.reversed()) if (layers[i].exists(normalized)) return layers[i]
        return null
    }

    /** Reads [path] from the top-most layer that has it, else throws [VfsFileNotFoundException]. */
    public fun read(path: String): ByteArray =
        (resolve(path) ?: throw VfsFileNotFoundException(path)).read(normalize(path))

    /** Reads [path] as UTF-8 text. */
    public fun readText(path: String): String = read(path).decodeToString()

    /** Union of the files under [dir] across all layers (recursive), de-duplicated, sorted. */
    public fun list(dir: String): List<String> = layers.flatMap { it.list(dir) }.distinct().sorted()

    /** Path helpers shared by all layers. */
    public companion object {
        /**
         * Normalises a virtual path: backslashes become `/`, empty and `.` segments are dropped,
         * `..` pops the previous segment, no leading slash. Throws [IllegalArgumentException] if
         * a `..` would climb above the root (`docs/decisions/0003-mod-vfs.md`: `..` rejected).
         */
        public fun normalize(path: String): String {
            val kept = ArrayList<String>()
            for (segment in path.replace('\\', '/').split('/')) {
                when (segment) {
                    "", "." -> {}
                    ".." -> require(kept.isNotEmpty()) { "path escapes VFS root: $path" }
                        .also { kept.removeAt(kept.size - 1) }
                    else -> kept.add(segment)
                }
            }
            return kept.joinToString("/")
        }
    }
}
