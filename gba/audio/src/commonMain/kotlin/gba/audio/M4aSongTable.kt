package gba.audio

/**
 * A ROM's `gSongTable` (`struct Song`, 8 bytes: `header` pointer, `ms` player index, `me`) read
 * through [bus]. Song numbers index it; `ms` indexes `gMPlayTable` (the game's players, in
 * table order). `me` is not used by any m4a function in the decomp, so it is not read.
 */
public class M4aSongTable(
    private val bus: M4aBus,
    /** GBA address of `gSongTable`. */
    public val address: Int,
    /** Number of entries (the decomp declares `gSongTable[]` without a length). */
    public val size: Int,
) {
    /** `songTable[n].header`: GBA address of song [n]'s header. */
    public fun header(n: Int): Int {
        val a = entry(n)
        return bus.read8(a) or (bus.read8(a + 1) shl 8) or (bus.read8(a + 2) shl 16) or
            (bus.read8(a + 3) shl 24)
    }

    /** `songTable[n].ms`: the `gMPlayTable` index of the player song [n] plays on. */
    public fun player(n: Int): Int {
        val a = entry(n) + 4
        return bus.read8(a) or (bus.read8(a + 1) shl 8)
    }

    private fun entry(n: Int): Int {
        require(n in 0 until size) { "song $n out of 0..${size - 1}" }
        return address + 8 * n
    }
}
