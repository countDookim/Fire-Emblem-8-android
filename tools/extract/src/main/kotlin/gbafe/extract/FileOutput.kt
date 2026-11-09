package gbafe.extract

import gba.extract.ExtractOutput
import java.io.File

/**
 * Filesystem implementation of [ExtractOutput] that writes extracted assets into files rooted at [root].
 */
public class FileOutput(
    /** Destination root directory for extracted files. */
    private val root: File,
) : ExtractOutput {
    /**
     * Writes [bytes] to the relative destination [path] under [root], creating any missing
     * parent directory structures automatically.
     */
    override fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
