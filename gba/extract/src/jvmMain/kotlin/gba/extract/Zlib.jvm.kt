package gba.extract

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Buffer size used when deflating zlib stream chunks. */
private const val ZLIB_BUFFER_SIZE = 8192

/** JVM implementation of zlib deflate compression using [java.util.zip.Deflater]. */
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

/** JVM implementation of zlib inflate decompression using [java.util.zip.Inflater]. */
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
