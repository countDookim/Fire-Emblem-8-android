package gba.core.sram

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [SaveStorage] backed by one file. Stores write a sibling `<name>.tmp`, sync it to disk, then
 * atomically move it over [file], so a crash never leaves a torn save. A file whose length is not
 * [CartSram.SIZE] is treated as absent, and a stale `.tmp` is ignored on load and overwritten by
 * the next store.
 *
 * On Android pass a file in the app's private files directory (`Context.filesDir`), e.g.
 * `File(filesDir, "fe8.sav")`.
 *
 * @property file the save file.
 */
public class FileSaveStorage internal constructor(
    public val file: File,
    /** Test hook run after the temp file is synced and before it replaces [file]. */
    private val beforeRename: () -> Unit,
) : SaveStorage {
    /** Creates storage for [file]; parent directories are created on the first store. */
    public constructor(file: File) : this(file, {})

    override fun load(dest: ByteArray): Boolean {
        require(dest.size == CartSram.SIZE) { "dest must be ${CartSram.SIZE} bytes" }
        if (!file.isFile || file.length() != CartSram.SIZE.toLong()) return false
        val bytes = file.readBytes()
        if (bytes.size != CartSram.SIZE) return false
        bytes.copyInto(dest)
        return true
    }

    override fun store(src: ByteArray) {
        require(src.size == CartSram.SIZE) { "src must be ${CartSram.SIZE} bytes" }
        // Temp file lives beside the target so the final move stays on one filesystem (atomic).
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(src)
            out.flush()
            out.fd.sync()
        }
        beforeRename()
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
