/**
 * Writes the kept ROM image (`base/rom/baserom.gba`, see `RomImport.kt`) to disk through a
 * temporary file, so an interrupted import never leaves a half-written ROM at the final path.
 */

package fe8.app

import gbafe.extract.RomSink
import java.io.File
import java.io.FileOutputStream

/**
 * A [RomSink] that writes to [target] through a `.tmp` sibling file that is fsynced and renamed on
 * commit. [gbafe.extract.RomImage.keep] feeds it the ROM in 64 KiB chunks and aborts it on error.
 *
 * NOTE: [commit] deletes an existing [target] before the rename, so replacing a file is not
 * atomic (a crash in between leaves neither). The import writes into a fresh `base.tmp` folder,
 * so there is never an existing target there.
 *
 * @property target The final destination file on disk.
 */
internal class FileRomSink(private val target: File) : RomSink {
    /** Where bytes go until [commit]; deleted by [abort]. */
    private val tmp = File(target.parentFile, target.name + TMP_EXTENSION)
    private val out: FileOutputStream

    init {
        target.parentFile?.mkdirs()
        out = FileOutputStream(tmp)
    }

    /**
     * Appends [len] bytes of [bytes] from offset [off] to the temporary file.
     *
     * @param bytes Buffer containing the payload to write.
     * @param off Starting offset within [bytes].
     * @param len Number of bytes to transfer.
     */
    override fun write(bytes: ByteArray, off: Int, len: Int) = out.write(bytes, off, len)

    /**
     * Forces the data to storage (`fsync`), closes the file, and renames it to [target].
     *
     * @throws IllegalStateException If the old [target] cannot be deleted or the rename fails.
     */
    override fun commit() {
        out.fd.sync()
        out.close()
        if (target.exists() && !target.delete()) error("cannot replace $target")
        if (!tmp.renameTo(target)) error("cannot rename $tmp")
    }

    /**
     * Gives up: closes the stream (ignoring errors) and deletes the temporary file, leaving no
     * [target].
     */
    override fun abort() {
        runCatching { out.close() }
        tmp.delete()
    }

    private companion object {
        /** Suffix of the sibling file written before the rename. */
        private const val TMP_EXTENSION: String = ".tmp"
    }
}
