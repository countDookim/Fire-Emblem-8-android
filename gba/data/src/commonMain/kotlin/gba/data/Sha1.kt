package gba.data

/**
 * SHA-1 in pure Kotlin for common code; same result as MessageDigest("SHA-1"). Used to identify the
 * player's ROM, not for security.
 */
public object Sha1 {
    /** The 20-byte SHA-1 digest (FIPS 180-4) of [bytes]. */
    public fun digest(bytes: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        val len = bytes.size
        // Message + 0x80 + zero padding + 8-byte bit length, in whole 64-byte blocks.
        val total = ((len + 8) / 64 + 1) * 64
        val bitLen = len.toLong() * 8
        val block = ByteArray(64)
        var off = 0
        while (off < total) {
            for (i in 0 until 64) {
                val p = off + i
                block[i] = when {
                    p < len -> bytes[p]
                    p == len -> 0x80.toByte()
                    p >= total - 8 -> (bitLen ushr (8 * (total - 1 - p))).toByte()
                    else -> 0
                }
            }
            for (t in 0 until 16) {
                w[t] = ((block[4 * t].toInt() and 0xFF) shl 24) or
                    ((block[4 * t + 1].toInt() and 0xFF) shl 16) or
                    ((block[4 * t + 2].toInt() and 0xFF) shl 8) or
                    (block[4 * t + 3].toInt() and 0xFF)
            }
            for (t in 16 until 80) {
                val x = w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]
                w[t] = (x shl 1) or (x ushr 31)
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (t in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    t < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    t < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val tmp = ((a shl 5) or (a ushr 27)) + f + e + k + w[t]
                e = d
                d = c
                c = (b shl 30) or (b ushr 2)
                b = a
                a = tmp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            off += 64
        }
        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0 until 5) {
            out[4 * i] = (hs[i] ushr 24).toByte()
            out[4 * i + 1] = (hs[i] ushr 16).toByte()
            out[4 * i + 2] = (hs[i] ushr 8).toByte()
            out[4 * i + 3] = hs[i].toByte()
        }
        return out
    }
}
