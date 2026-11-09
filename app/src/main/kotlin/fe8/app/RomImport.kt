/**
 * ROM import, verification, and asset extraction pipeline.
 *
 * Provides utilities to read a user-selected Fire Emblem: The Sacred Stones (U) GBA ROM from
 * a content [Uri], verify its SHA-1 hash against the known retail release, extract all game
 * assets into internal app storage under `base/`, and preserve the verified binary image for runtime execution.
 */

package fe8.app

import android.content.Context
import android.net.Uri
import android.util.Log
import gba.extract.ExtractOutput
import gba.extract.RomCheck
import gba.extract.RomImporter
import gba.vfs.DirectoryLayer
import gba.vfs.VfsLayer
import gbafe.extract.RomImage
import gbafe.extract.fe8.Fe8Import
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Approximate file count of a full extraction; used as the denominator for progress calculation. */
internal const val EXPECTED_FILES = 1830

/** Conversion factor from nanoseconds to milliseconds. */
private const val NANOS_PER_MILLIS: Long = 1_000_000L

/**
 * An [ExtractOutput] that writes extracted assets to disk under [root], automatically creating parent directories.
 *
 * @property root The target base directory on the local filesystem.
 */
internal class DirOutput(private val root: File) : ExtractOutput {
    /**
     * Writes the extracted file [bytes] to the specified relative [path] under [root].
     */
    override fun write(path: String, bytes: ByteArray) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}

/** Outcome of the [importRom] extraction operation. */
internal sealed interface ImportResult {
    /**
     * ROM successfully verified and extracted.
     *
     * @property count Total number of individual files extracted.
     * @property ms Wall-clock elapsed time in milliseconds.
     */
    data class Done(val count: Int, val ms: Long) : ImportResult

    /**
     * The provided file does not match the expected retail FE8 (U) ROM hash.
     *
     * @property sha1 The hex-encoded SHA-1 hash of the supplied file.
     */
    data class WrongRom(val sha1: String) : ImportResult

    /**
     * Extraction failed due to an I/O or parsing error.
     *
     * @property message Descriptive failure reason.
     */
    data class Failed(val message: String) : ImportResult
}

/** Returns the local directory holding the extracted `base` layer in the app's internal storage. */
internal fun baseDir(ctx: Context): File = File(ctx.filesDir, "base")

/**
 * Checks whether the internal `base` directory contains an extraction stamp matching the current format version.
 *
 * @param ctx Android application context.
 * @return `true` if the existing extraction is up to date, `false` otherwise.
 */
internal fun baseIsCurrent(ctx: Context): Boolean {
    val stamp = File(baseDir(ctx), RomImporter.STAMP_PATH)
    val text = if (stamp.isFile) runCatching { stamp.readText() }.getOrNull() else null
    return Fe8Import.importer().isCurrent(text)
}

/**
 * Resolves the verified ROM image file (`base/rom/baserom.gba`) if the base layer is valid and current.
 *
 * @param ctx Android application context.
 * @return The ROM file, or `null` if not imported or format is outdated.
 */
internal fun keptRom(ctx: Context): File? =
    if (baseIsCurrent(ctx)) File(baseDir(ctx), RomImage.PATH).takeIf { it.isFile } else null

/**
 * Resolves the primary VFS layer: uses [DirectoryLayer] over [baseDir] when current,
 * falling back to APK assets via [AssetLayer] if not yet imported.
 *
 * @param ctx Android application context.
 * @return Virtual file system layer for base assets.
 */
internal fun baseLayer(ctx: Context): VfsLayer =
    if (baseIsCurrent(ctx)) DirectoryLayer(baseDir(ctx), "base") else AssetLayer(ctx.assets)

/**
 * Reads the ROM at [uri], verifies it, and extracts it into [baseDir], reporting progress in 0..1
 * through [onProgress] (called on the IO coroutine dispatcher).
 *
 * Extraction writes to a temporary `base.tmp` folder first, moving it into place via atomic rename
 * only after all assets and format stamps are successfully committed.
 *
 * @param ctx Android application context.
 * @param uri Content URI pointing to the source GBA ROM file.
 * @param onProgress Callback invoked with normalized progress values between 0.0 and 1.0.
 * @return [ImportResult] detailing success or error information.
 */
internal suspend fun importRom(
    ctx: Context,
    uri: Uri,
    onProgress: (Float) -> Unit,
): ImportResult = withContext(Dispatchers.IO) {
    try {
        val rom = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ImportResult.Failed("Could not open the selected file.")
        val importer = Fe8Import.importer()
        val check = importer.check(rom)
        if (check is RomCheck.WrongRom) return@withContext ImportResult.WrongRom(check.sha1)
        val tmp = File(ctx.filesDir, "base.tmp")
        tmp.deleteRecursively()
        val started = System.nanoTime()
        val files = importer.import(rom, DirOutput(tmp)) { _, count ->
            onProgress((count.toFloat() / EXPECTED_FILES).coerceIn(0f, 1f))
        }
        // Keep the verified image beside the extracted files; it moves into place with them.
        RomImage.keep(importer, rom) { FileRomSink(File(tmp, RomImage.PATH)) }
        val base = baseDir(ctx)
        base.deleteRecursively()
        if (!tmp.renameTo(base)) {
            tmp.deleteRecursively()
            return@withContext ImportResult.Failed("Could not move the extracted files into place.")
        }
        val ms = (System.nanoTime() - started) / NANOS_PER_MILLIS
        Log.i("Fe8Import", "extracted ${files.size} files in $ms ms")
        onProgress(1f)
        ImportResult.Done(files.size, ms)
    } catch (e: Exception) {
        File(ctx.filesDir, "base.tmp").deleteRecursively()
        ImportResult.Failed(e.message ?: e.javaClass.simpleName)
    }
}
