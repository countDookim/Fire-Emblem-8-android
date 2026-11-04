package gba.audio

/**
 * A ROM's `gSongTable` (`struct Song`: header pointer, `ms` player index, `me`) read through [bus].
 * Song numbers index it; `ms` indexes `gMPlayTable` (the game's players, in table order).
 */
public class M4aSongTable(
    private val bus: M4aBus,
    /** GBA address of `gSongTable`. */
    public val address: Int,
    /** Number of entries. */
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
