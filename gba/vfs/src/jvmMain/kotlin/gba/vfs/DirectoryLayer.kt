package gba.vfs

import java.io.File

/** A layer backed by a directory on disk. */
public class DirectoryLayer(private val root: File, override val origin: String) : VfsLayer {
    override fun exists(path: String): Boolean = File(root, VirtualFileSystem.normalize(path)).isFile

    override fun read(path: String): ByteArray =
        File(root, VirtualFileSystem.normalize(path)).readBytes()

    override fun list(dir: String): List<String> {
        val base = File(root, VirtualFileSystem.normalize(dir))
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }
}
