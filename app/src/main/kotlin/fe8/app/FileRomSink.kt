package fe8.app

import gbafe.extract.RomSink
import java.io.File
import java.io.FileOutputStream

/** A [RomSink] writing [target] through a `.tmp` sibling that is fsynced and renamed on commit. */
internal class FileRomSink(private val target: File) : RomSink {
    private val tmp = File(target.parentFile, target.name + ".tmp")
    private val out: FileOutputStream

    init {
        target.parentFile?.mkdirs()
        out = FileOutputStream(tmp)
    }

    override fun write(bytes: ByteArray, off: Int, len: Int) = out.write(bytes, off, len)

    override fun commit() {
        out.fd.sync()
        out.close()
        if (target.exists() && !target.delete()) error("cannot replace $target")
        if (!tmp.renameTo(target)) error("cannot rename $tmp")
    }

    override fun abort() {
        runCatching { out.close() }
        tmp.delete()
    }
}
