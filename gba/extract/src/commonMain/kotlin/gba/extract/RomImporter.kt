package gba.extract

import gba.data.Sha1

/** Result of checking ROM verification against the expected SHA-1 digest ([RomImporter.check]). */
public sealed interface RomCheck {
    /** The ROM is verified as the expected dump. */
    public data object Ok : RomCheck

    /** The ROM does not match; [sha1] is the lowercase hex SHA-1 of what was provided. */
    public data class WrongRom(
        /** Lowercase hex SHA-1 digest of the given ROM. */
        val sha1: String,
    ) : RomCheck
}

/**
 * First-launch import orchestrator (docs/decisions/0011): checks the player's ROM by SHA-1, extracts
 * all required game assets through [extract] into [ExtractOutput], and writes the format stamp
 * [STAMP_PATH] last, so a partial or interrupted extraction is never mistaken for a complete one.
 * It knows nothing about any particular game: the expected hash, version and extractor are
 * supplied by the caller (FE8 example: `Fe8Import` in engine:extract).
 *
 * [formatVersion] is incremented whenever the extractor output schema or asset set changes;
 * [isCurrent] then reports false and triggers re-extraction on the next launch.
 */
public class RomImporter(
    /** Lowercase hex SHA-1 digest of the only accepted ROM dump. */
    public val expectedSha1: String,
    /** Extractor output schema version; recorded into the stamp file. */
    public val formatVersion: Int,
    /**
     * Performs the asset extraction from the verified ROM image into [ExtractOutput]. The returned
     * list is ignored by [import], which records the paths itself from the writes it observes.
     */
    private val extract: (rom: ByteArray, out: ExtractOutput) -> List<String>,
) {
    /** Compares the SHA-1 of [rom] with [expectedSha1] (case-insensitive on the expected value). */
    public fun check(rom: ByteArray): RomCheck {
        val sha1 = Sha1.digest(rom)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return if (sha1 == expectedSha1.lowercase()) RomCheck.Ok else RomCheck.WrongRom(sha1)
    }

    /**
     * True if [stamp] (contents of [STAMP_PATH], or null if absent) was written for this expected
     * SHA-1 and [formatVersion], ignoring surrounding whitespace.
     */
    public fun isCurrent(stamp: String?): Boolean = stamp?.trim() == stampText().trim()

    /**
     * Checks [rom] by SHA-1, extracts all game assets into [out], calling [onFile] with each
     * written path and a running count (1-based), and finally writes the format stamp [STAMP_PATH].
     *
     * @throws IllegalArgumentException if the ROM SHA-1 does not match [expectedSha1].
     * @return List of all relative paths written (excluding the format stamp itself).
     */
    public fun import(
        rom: ByteArray,
        out: ExtractOutput,
        onFile: (path: String, count: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val checkResult = check(rom)
        require(checkResult is RomCheck.Ok) {
            "wrong ROM: SHA-1 ${(checkResult as RomCheck.WrongRom).sha1}, expected $expectedSha1"
        }
        val paths = ArrayList<String>()
        val observingOutput = object : ExtractOutput {
            override fun write(path: String, bytes: ByteArray) {
                out.write(path, bytes)
                paths += path
                onFile(path, paths.size)
            }
        }
        extract(rom, observingOutput)
        out.writeText(STAMP_PATH, stampText())
        return paths
    }

    /** Stamp file content: `<expectedSha1> <formatVersion>` and a newline. */
    private fun stampText(): String = "$expectedSha1 $formatVersion\n"

    /** Location of the format stamp. */
    public companion object {
        /** Path of the format stamp inside the extract output; written last by [import]. */
        public const val STAMP_PATH: String = "extract-format.txt"
    }
}
