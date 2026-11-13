package gba.extract

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Size of the scratch buffer [zlibDeflate] drains the compressor into. */
private const val ZLIB_BUFFER_SIZE = 8192

/** JVM [zlibDeflate] using [java.util.zip.Deflater] with a zlib header. */
internal actual fun zlibDeflate(raw: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val sink = ByteArrayOutputStream()
    val buf = ByteArray(ZLIB_BUFFER_SIZE)
    while (!deflater.finished()) {
        val count = deflater.deflate(buf)
        sink.write(buf, 0, count)
    }
    deflater.end()
    return sink.toByteArray()
}

/** JVM [zlibInflate] using [java.util.zip.Inflater]; an `inflate` result of 0 means truncated. */
internal actual fun zlibInflate(z: ByteArray, out: ByteArray) {
    val inflater = Inflater()
    inflater.setInput(z)
    var n = 0
    while (n < out.size) {
        val k = inflater.inflate(out, n, out.size - n)
        require(k > 0) { "image data truncated" }
        n += k
    }
    inflater.end()
}
