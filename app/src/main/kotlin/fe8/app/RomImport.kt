package fe8.app

import android.content.Context
import android.net.Uri
import android.util.Log
import gba.extract.ExtractOutput
import gba.extract.RomCheck
import gba.extract.RomImporter
import gba.vfs.DirectoryLayer
import gba.vfs.VfsLayer
import gbafe.extract.fe8.Fe8Import
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Approximate file count of a full extraction; the progress denominator. */
internal const val EXPECTED_FILES = 1830

/** An [ExtractOutput] that writes files below [root], creating parent directories. */
internal class DirOutput(private val root: File) : ExtractOutput {
    override fun write(path: String, bytes: ByteArray) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}

/** Outcome of [importRom]. */
internal sealed interface ImportResult {
    /** [count] files were extracted in [ms] milliseconds and the new base is in place. */
    data class Done(val count: Int, val ms: Long) : ImportResult

    /** The chosen file is not the expected ROM; [sha1] is its SHA-1. */
    data class WrongRom(val sha1: String) : ImportResult

    /** Reading or extracting failed with [message]. */
    data class Failed(val message: String) : ImportResult
}

/** The directory holding the extracted `base` layer. */
internal fun baseDir(ctx: Context): File = File(ctx.filesDir, "base")

/** True when `base/extract-format.txt` exists and matches the current extraction format. */
internal fun baseIsCurrent(ctx: Context): Boolean {
    val stamp = File(baseDir(ctx), RomImporter.STAMP_PATH)
    val text = if (stamp.isFile) runCatching { stamp.readText() }.getOrNull() else null
    return Fe8Import.importer().isCurrent(text)
}

/**
 * The VFS base layer: the imported [baseDir] when current, else the APK's bundled assets
 * (debug/test builds; empty in release builds).
 */
internal fun baseLayer(ctx: Context): VfsLayer =
    if (baseIsCurrent(ctx)) DirectoryLayer(baseDir(ctx), "base") else AssetLayer(ctx.assets)

/**
 * Reads the ROM at [uri], verifies it and extracts it into [baseDir], reporting progress in 0..1
 * through [onProgress] (called on the IO thread). Extraction goes to `base.tmp` and replaces
 * `base` only after the stamp is written. The ROM bytes are not stored anywhere.
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
        val base = baseDir(ctx)
        base.deleteRecursively()
        if (!tmp.renameTo(base)) {
            tmp.deleteRecursively()
            return@withContext ImportResult.Failed("Could not move the extracted files into place.")
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i("Fe8Import", "extracted ${files.size} files in $ms ms")
        onProgress(1f)
        ImportResult.Done(files.size, ms)
    } catch (e: Exception) {
        File(ctx.filesDir, "base.tmp").deleteRecursively()
        ImportResult.Failed(e.message ?: e.javaClass.simpleName)
    }
}
