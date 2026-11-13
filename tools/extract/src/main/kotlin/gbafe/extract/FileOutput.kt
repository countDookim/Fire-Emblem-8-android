package gbafe.extract

import gba.extract.ExtractOutput
import java.io.File

/**
 * [ExtractOutput] that writes each extracted file under the directory [root].
 */
public class FileOutput(
    /** Directory the relative output paths are resolved against. */
    private val root: File,
) : ExtractOutput {
    /**
     * Writes [bytes] to [path] (relative, `/`-separated) under [root], creating missing parent
     * directories and replacing an existing file.
     */
    override fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
