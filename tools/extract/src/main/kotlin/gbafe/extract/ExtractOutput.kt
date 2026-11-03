package gbafe.extract

import java.io.File

/**
 * Where extraction steps put their files (paths relative to the output root, `/`-separated):
 * a directory for the CLI ([FileOutput]), the VFS `base` layer on device (M4-04).
 */
public interface ExtractOutput {
    /** Writes [bytes] as [path], creating parent directories. */
    public fun write(path: String, bytes: ByteArray)

    /** Writes [text] as UTF-8 to [path]. */
    public fun writeText(path: String, text: String): Unit = write(path, text.encodeToByteArray())
}

/** [ExtractOutput] into the directory [root]. */
public class FileOutput(private val root: File) : ExtractOutput {
    override fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
