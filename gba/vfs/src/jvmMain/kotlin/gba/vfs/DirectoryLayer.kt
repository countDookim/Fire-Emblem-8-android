package gba.vfs

import java.io.File

/**
 * A layer backed by the directory [root] on disk: virtual path `a/b.json` is the file
 * `root/a/b.json`. [origin] names the layer in diagnostics.
 */
public class DirectoryLayer(private val root: File, override val origin: String) : VfsLayer {
    override fun exists(path: String): Boolean = File(root, VirtualFileSystem.normalize(path)).isFile

    override fun read(path: String): ByteArray =
        File(root, VirtualFileSystem.normalize(path)).readBytes()

    /** All files under [dir], recursively, as sorted paths relative to [root] (not to [dir]). */
    override fun list(dir: String): List<String> {
        val base = File(root, VirtualFileSystem.normalize(dir))
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }
}
