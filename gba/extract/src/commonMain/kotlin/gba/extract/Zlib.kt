package gba.extract

/**
 * Compresses [raw] as a zlib stream at the best compression level (the platform zlib; the JDK
 * on the JVM). Used for PNG `IDAT` data.
 */
internal expect fun zlibDeflate(raw: ByteArray): ByteArray

/**
 * Inflates zlib stream [z] into destination array [out], filling it exactly.
 *
 * @throws IllegalArgumentException with "image data truncated" if the stream ends before `out.size`
 * bytes were produced. Bytes beyond `out.size` are ignored.
 */
internal expect fun zlibInflate(z: ByteArray, out: ByteArray)
