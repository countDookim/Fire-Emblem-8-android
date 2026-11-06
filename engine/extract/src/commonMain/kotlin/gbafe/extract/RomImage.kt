package gbafe.extract

import gba.extract.RomCheck
import gba.extract.RomImporter

/** Destination for the kept ROM image; a platform implements it with atomic file replacement. */
public interface RomSink {
    /** Appends [len] bytes of [bytes] starting at [off]. */
    public fun write(bytes: ByteArray, off: Int, len: Int)

    /** Makes everything written visible at the final path atomically (temp file + rename). */
    public fun commit()

    /** Discards everything written; no final file and no temp file remain. */
    public fun abort()
}

/** Keeps the SHA-1-verified ROM image in the base layer (ADR 0011, amended 2026-11-06). */
public object RomImage {
    /** Path of the kept image below the base layer root. */
    public const val PATH: String = "rom/baserom.gba"

    private const val CHUNK = 64 * 1024

    /**
     * Verifies [rom] with [importer]; when it passes, streams it in 64 KiB chunks into the sink
     * from [open] and commits. A wrong ROM returns its [RomCheck.WrongRom] and never opens a sink. If
     * writing throws, the sink is aborted and the exception propagates.
     */
    public fun keep(importer: RomImporter, rom: ByteArray, open: () -> RomSink): RomCheck {
        val check = importer.check(rom)
        if (check !is RomCheck.Ok) return check
        val sink = open()
        try {
            var pos = 0
            while (pos < rom.size) {
                val n = minOf(CHUNK, rom.size - pos)
                sink.write(rom, pos, n)
                pos += n
            }
            sink.commit()
        } catch (e: Exception) {
            sink.abort()
            throw e
        }
        return check
    }
}
