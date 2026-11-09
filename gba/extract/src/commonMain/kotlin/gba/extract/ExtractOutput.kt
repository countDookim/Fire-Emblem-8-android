package gba.extract

/**
 * File output abstraction interface for extraction pipelines.
 *
 * Extraction steps write their generated assets through this interface using relative `/`-separated paths.
 * Implementations include:
 * - Desktop CLI: writes files directly to the filesystem.
 * - Android on-device import: writes directly into the app's Virtual File System (VFS) base storage layer.
 */
public interface ExtractOutput {
    /** Writes binary [bytes] to [path], creating parent directories as necessary. */
    public fun write(path: String, bytes: ByteArray)

    /** Writes [text] as UTF-8 encoded bytes to [path]. */
    public fun writeText(path: String, text: String): Unit = write(path, text.encodeToByteArray())
}
