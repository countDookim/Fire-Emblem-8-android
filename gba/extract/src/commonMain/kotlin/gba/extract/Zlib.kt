package gba.extract

/** Compresses [raw] as a zlib stream at the best compression level (platform zlib; the JDK on the JVM). */
internal expect fun zlibDeflate(raw: ByteArray): ByteArray

/**
 * Inflates zlib stream [z] into [out], filling it exactly.
 *
 * @throws IllegalArgumentException "image data truncated" if the stream holds fewer than `out.size` bytes.
 */
internal expect fun zlibInflate(z: ByteArray, out: ByteArray)
