package gbafe.extract

import java.security.MessageDigest

/** Result of [RomImporter.check]. */
public sealed interface RomCheck {
    /** The ROM is the expected dump. */
    public data object Ok : RomCheck

    /** Wrong ROM; [sha1] is the lowercase hex SHA-1 of what was given. */
    public data class WrongRom(val sha1: String) : RomCheck
}

/**
 * First-launch import (docs/decisions/0011): checks the player's ROM by SHA-1, extracts it through
 * [extract] into an [ExtractOutput] and writes the format stamp [STAMP_PATH] last, so a partial
 * extraction is never taken as complete. [formatVersion] is bumped whenever extractor output
 * changes; [isCurrent] then reports false and the app re-extracts.
 */
public class RomImporter(
    public val expectedSha1: String,
    public val formatVersion: Int,
    private val extract: (rom: ByteArray, out: ExtractOutput) -> List<String>,
) {
    /** Compares the SHA-1 of [rom] with [expectedSha1]. */
    public fun check(rom: ByteArray): RomCheck {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(rom)
            .joinToString("") { "%02x".format(it) }
        return if (sha1 == expectedSha1.lowercase()) RomCheck.Ok else RomCheck.WrongRom(sha1)
    }

    /** True if [stamp] (contents of [STAMP_PATH], null if absent) matches this importer. */
    public fun isCurrent(stamp: String?): Boolean = stamp?.trim() == stampText().trim()

    /**
     * Checks [rom], then extracts it into [out], calling [onFile] with each path written and a
     * running count. Throws [IllegalArgumentException] for a wrong ROM. Returns the written paths
     * (stamp excluded), as observed on [out]: the
     * summaries [extract] itself returns are ignored.
     */
    public fun import(
        rom: ByteArray,
        out: ExtractOutput,
        onFile: (path: String, count: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val check = check(rom)
        require(check is RomCheck.Ok) {
            "wrong ROM: SHA-1 ${(check as RomCheck.WrongRom).sha1}, expected $expectedSha1"
        }
        val paths = ArrayList<String>()
        val observing = object : ExtractOutput {
            override fun write(path: String, bytes: ByteArray) {
                out.write(path, bytes)
                paths += path
                onFile(path, paths.size)
            }
        }
        extract(rom, observing)
        out.writeText(STAMP_PATH, stampText())
        return paths
    }

    private fun stampText(): String = "$expectedSha1 $formatVersion\n"

    public companion object {
        /** Path of the format stamp, written after everything else. */
        public const val STAMP_PATH: String = "extract-format.txt"
    }
}
