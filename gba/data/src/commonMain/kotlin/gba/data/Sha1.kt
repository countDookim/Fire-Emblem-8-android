package gba.data

/**
 * SHA-1 in pure Kotlin for common code; same result as MessageDigest("SHA-1"). Used to identify the
 * player's ROM, not for security (FIPS PUB 180-1 specification).
 */
public object Sha1 {
    /** SHA-1 block size in bytes (512 bits = 64 bytes). */
    public const val BLOCK_SIZE: Int = 64

    /** SHA-1 digest size in bytes (160 bits = 20 bytes). */
    public const val DIGEST_SIZE: Int = 20

    // Initial hash values (FIPS 180-1 section 5.1)
    private const val H0_INIT = 0x67452301
    private const val H1_INIT = 0xEFCDAB89.toInt()
    private const val H2_INIT = 0x98BADCFE.toInt()
    private const val H3_INIT = 0x10325476
    private const val H4_INIT = 0xC3D2E1F0.toInt()

    // Round constant words K_t (FIPS 180-1 section 4.2)
    private const val K00_19 = 0x5A827999
    private const val K20_39 = 0x6ED9EBA1
    private const val K40_59 = 0x8F1BBCDC.toInt()
    private const val K60_79 = 0xCA62C1D6.toInt()

    /** Computes the 20-byte SHA-1 digest of [data]. */
    public fun digest(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8
        val padLen = ((BLOCK_SIZE - 8 - ((data.size + 1) % BLOCK_SIZE)) % BLOCK_SIZE + BLOCK_SIZE) % BLOCK_SIZE
        val padded = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bitLen ushr (56 - 8 * i)).toByte()
        }

        var h0 = H0_INIT
        var h1 = H1_INIT
        var h2 = H2_INIT
        var h3 = H3_INIT
        var h4 = H4_INIT

        val w = IntArray(80)
        var offset = 0
        while (offset < padded.size) {
            for (t in 0 until 16) {
                val j = offset + 4 * t
                w[t] = ((padded[j].toInt() and 0xFF) shl 24) or
                    ((padded[j + 1].toInt() and 0xFF) shl 16) or
                    ((padded[j + 2].toInt() and 0xFF) shl 8) or
                    (padded[j + 3].toInt() and 0xFF)
            }
            for (t in 16 until 80) {
                val v = w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]
                w[t] = (v shl 1) or (v ushr 31)
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (t in 0 until 80) {
                val (f, k) = when (t) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to K00_19
                    in 20..39 -> (b xor c xor d) to K20_39
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to K40_59
                    else -> (b xor c xor d) to K60_79
                }
                val rotA = (a shl 5) or (a ushr 27)
                val temp = rotA + f + e + k + w[t]
                e = d
                d = c
                c = (b shl 30) or (b ushr 2)
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            offset += BLOCK_SIZE
        }

        val out = ByteArray(DIGEST_SIZE)
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
