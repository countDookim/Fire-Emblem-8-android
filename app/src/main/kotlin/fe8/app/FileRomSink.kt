/**
 * Atomic file output sink for ROM storage and extracted assets.
 *
 * Implements [gbafe.extract.RomSink] to safely stream data to disk via an adjacent temporary
 * file before fsyncing and atomically renaming it to the final destination file, guarding
 * against corrupted or partial writes if interrupted.
 */

package fe8.app

import gbafe.extract.RomSink
import java.io.File
import java.io.FileOutputStream

/**
 * A [RomSink] that writes to [target] through a `.tmp` sibling file that is fsynced and renamed on commit.
 *
 * @property target The final destination file on disk.
 */
internal class FileRomSink(private val target: File) : RomSink {
    private val tmp = File(target.parentFile, target.name + TMP_EXTENSION)
    private val out: FileOutputStream

    init {
        target.parentFile?.mkdirs()
        out = FileOutputStream(tmp)
    }

    /**
     * Appends [len] bytes from [bytes] starting at offset [off] into the temporary file stream.
     *
     * @param bytes Buffer containing the payload to write.
     * @param off Starting offset within [bytes].
     * @param len Number of bytes to transfer.
     */
    override fun write(bytes: ByteArray, off: Int, len: Int) = out.write(bytes, off, len)

    /**
     * Flushes buffered data, synchronizes to physical storage (`fsync`), closes the file,
     * and atomically moves the temporary file to [target].
     *
     * @throws IllegalStateException If existing target removal or atomic renaming fails.
     */
    override fun commit() {
        out.fd.sync()
        out.close()
        if (target.exists() && !target.delete()) error("cannot replace $target")
        if (!tmp.renameTo(target)) error("cannot rename $tmp")
    }

    /**
     * Aborts the in-progress write, silently closing the output stream and deleting the temporary file.
     */
    override fun abort() {
        runCatching { out.close() }
        tmp.delete()
    }

    private companion object {
        private const val TMP_EXTENSION: String = ".tmp"
    }
}
