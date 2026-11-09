package gba.extract

/**
 * Compresses [raw] byte array as a zlib stream at the best compression level
 * (delegated to platform zlib implementation; the JDK on the JVM).
 */
internal expect fun zlibDeflate(raw: ByteArray): ByteArray

/**
 * Inflates zlib stream [z] into destination array [out], filling it exactly.
 *
 * @throws IllegalArgumentException with "image data truncated" if the stream holds fewer than `out.size` bytes.
 */
internal expect fun zlibInflate(z: ByteArray, out: ByteArray)
