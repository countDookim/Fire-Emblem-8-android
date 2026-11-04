package gba.extract

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun zlibDeflate(raw: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val z = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
    deflater.end()
    return z.toByteArray()
}

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
