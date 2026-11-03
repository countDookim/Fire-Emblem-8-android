package gbafe.extract

import java.io.File

/** [ExtractOutput] into the directory [root]. */
public class FileOutput(private val root: File) : ExtractOutput {
    /** Writes [bytes] to `<root>/<path>`, creating parent directories. */
    override fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
