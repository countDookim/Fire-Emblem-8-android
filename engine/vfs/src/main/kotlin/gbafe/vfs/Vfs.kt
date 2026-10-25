package gbafe.vfs

import java.io.File
import java.io.InputStream

/**
 * One read-only source of files addressed by normalised virtual paths such as `data/items.json` or
 * `maps/ch01.tmj`. Implementations: [DirectoryLayer] (dev overrides, unpacked mods), a zip layer for
 * packaged mods (milestone 7), and an Android-assets layer in `:app`.
 */
public interface VfsLayer {
    /** Human-readable origin, e.g. `base`, `mod:com.example.hardmode@1.2.0`. */
    public val origin: String

    /** True if [path] exists in this layer. */
    public fun exists(path: String): Boolean

    /** Opens [path]; caller closes. Only called after [exists] returned true. */
    public fun open(path: String): InputStream

    /** Lists files directly or recursively under [dir] (virtual paths). */
    public fun list(dir: String): List<String>
}

/** A layer backed by a directory on disk. */
public class DirectoryLayer(private val root: File, override val origin: String) : VfsLayer {
    override fun exists(path: String): Boolean = File(root, VirtualFileSystem.normalize(path)).isFile

    override fun open(path: String): InputStream = File(root, VirtualFileSystem.normalize(path)).inputStream()

    override fun list(dir: String): List<String> {
        val base = File(root, VirtualFileSystem.normalize(dir))
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }
}

/**
 * Layered virtual filesystem: the single way game code reads any data, map, script, asset or string.
 *
 * Layers are searched from the **last added** (highest priority) to the first (the base game data), so
 * a mod added later overrides earlier mods and the base by simply providing a file at the same path.
 * Load-time only: nothing here is used inside the per-frame loop (content is compiled into primitive
 * tables at load). See `docs/decisions/0003-mod-vfs.md`.
 */
public class VirtualFileSystem {
    private val layers = ArrayList<VfsLayer>()

    /** Layers in priority order, lowest first. */
    public val stack: List<VfsLayer> get() = layers

    /** Pushes [layer] on top (highest priority). */
    public fun mount(layer: VfsLayer) {
        layers.add(layer)
    }

    /** Returns the highest-priority layer containing [path], or null. */
    public fun resolve(path: String): VfsLayer? {
        val p = normalize(path)
        for (i in layers.indices.reversed()) if (layers[i].exists(p)) return layers[i]
        return null
    }

    /** Opens [path] from the highest-priority layer that has it. */
    public fun open(path: String): InputStream =
        (resolve(path) ?: throw NoSuchFileException(File(path), reason = "not found in any VFS layer")).open(normalize(path))

    /** Reads [path] as UTF-8 text. */
    public fun readText(path: String): String = open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Union of files under [dir] across all layers, de-duplicated, sorted. */
    public fun list(dir: String): List<String> = layers.flatMap { it.list(dir) }.distinct().sorted()

    /** Path helpers shared by all layers. */
    public companion object {
        /** Normalises a virtual path: forward slashes, no leading slash, no `.`/`..` escapes. */
        public fun normalize(path: String): String {
            val parts = ArrayList<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> require(parts.isNotEmpty()) { "path escapes VFS root: $path" }.also { parts.removeAt(parts.size - 1) }
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}
