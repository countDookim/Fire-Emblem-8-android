/**
 * The launcher's "Import ROM" step (decision 0011): read the ROM the player picked, check it is
 * the FE8 (U) dump by SHA-1, extract its assets into `filesDir/base/`, and keep the verified ROM
 * image there as `rom/baserom.gba`. Also finds that base layer again on later launches.
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

/**
 * Rough number of files a full extraction writes: only the denominator of the progress bar,
 * clamped to 1.0, so a wrong value costs accuracy, not correctness. The reference extraction in
 * `.assets/extracted` has 1877 files (not necessarily the same format version), so the constant
 * may be a little low and the bar can sit at 100% before the end.
 */
internal const val EXPECTED_FILES = 1830

/** Conversion factor from nanoseconds to milliseconds. */
private const val NANOS_PER_MILLIS: Long = 1_000_000L

/**
 * An [ExtractOutput] that writes each extracted file to disk under [root], creating folders as
 * needed. Not fsynced: a half-written `base.tmp` is deleted on failure and renamed only when whole.
 *
 * @property root The target base directory on the local filesystem.
 */
internal class DirOutput(private val root: File) : ExtractOutput {
    /** Writes [bytes] to `root/path`, replacing any existing file. */
    override fun write(path: String, bytes: ByteArray) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}

/** Result of [importRom]; the launcher turns each case into a message or the file count. */
internal sealed interface ImportResult {
    /**
     * Verified and extracted.
     *
     * @property count Files extracted, not counting the format stamp or the kept ROM image.
     * @property ms Milliseconds from the start of extraction to the move into place.
     */
    data class Done(val count: Int, val ms: Long) : ImportResult

    /**
     * The file's SHA-1 is not the FE8 (U) one (`Fe8Import.ROM_SHA1`): a different game, region
     * or a patched dump.
     *
     * @property sha1 Lowercase hex SHA-1 of the file, shown to the player.
     */
    data class WrongRom(val sha1: String) : ImportResult

    /**
     * Reading, extracting or moving failed (unreadable file, full storage, bad ROM data).
     *
     * @property message Reason shown to the player.
     */
    data class Failed(val message: String) : ImportResult
}

/** The folder holding the extracted base layer: `filesDir/base` (decision 0011, point 5). */
internal fun baseDir(ctx: Context): File = File(ctx.filesDir, "base")

/**
 * Whether `base/` holds a finished extraction for this app's expected ROM hash and extractor
 * format version. The stamp is written last, so a partial extraction reads as not current; a new
 * app version with a newer format makes the player import again.
 *
 * @param ctx Android context.
 * @return `true` if the stamp file matches, `false` if it is missing, unreadable or older.
 */
internal fun baseIsCurrent(ctx: Context): Boolean {
    val stamp = File(baseDir(ctx), RomImporter.STAMP_PATH)
    val text = if (stamp.isFile) runCatching { stamp.readText() }.getOrNull() else null
    return Fe8Import.importer().isCurrent(text)
}

/**
 * The kept, SHA-1-verified ROM image (`base/rom/baserom.gba`, on this device only) that Play
 * reads whole into memory, or `null` if nothing is imported, the format is outdated, or the
 * install predates keeping the image (decision 0011, amended 2026-11-06).
 *
 * @param ctx Android context.
 * @return The file, or `null` as above.
 */
internal fun keptRom(ctx: Context): File? =
    if (baseIsCurrent(ctx)) File(baseDir(ctx), RomImage.PATH).takeIf { it.isFile } else null

/**
 * The `base` file-system layer: the imported folder when current, else the (empty) APK assets
 * via [AssetLayer], so before an import every lookup finds nothing.
 *
 * @param ctx Android context.
 * @return The layer to read extracted game files from.
 */
internal fun baseLayer(ctx: Context): VfsLayer =
    if (baseIsCurrent(ctx)) DirectoryLayer(baseDir(ctx), "base") else AssetLayer(ctx.assets)

/**
 * The import itself: reads the whole ROM at [uri] (16 MB, in memory), checks its SHA-1, extracts
 * the assets and the format stamp into `filesDir/base.tmp`, adds the kept ROM image, then renames
 * `base.tmp` to [baseDir]. Progress runs 0..1 by files written over [EXPECTED_FILES]; [onProgress]
 * is called on the IO dispatcher.
 *
 * NOTE: the old `base/` is deleted before the rename, so if the rename fails (or the process
 * dies between the two steps) the player is left with no imported ROM, not the previous one.
 * Whether `renameTo` is atomic here is Android behaviour, not checked.
 *
 * @param ctx Android context.
 * @param uri Content URI from the document picker.
 * @param onProgress Receives progress 0.0..1.0.
 * @return [ImportResult.WrongRom] for the wrong dump, [ImportResult.Failed] on any error
 *   (the temporary folder is removed), else [ImportResult.Done].
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
