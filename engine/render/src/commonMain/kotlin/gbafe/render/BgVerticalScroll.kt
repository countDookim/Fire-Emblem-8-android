package gbafe.render

/**
 * Per-scanline register program (in the decomp only `src/savedraw.c`, the save-menu screen, starts
 * it): a port of `struct BgVerticalScrollSt` (`include/bmlib.h`, 0x7A0 bytes) and its HBlank
 * handler `HBlank_BgVerticalScroll` (`src/bmlib-hblank.c`). Each frame the game
 * fills a table of one value per scanline for up to three I/O registers (the game passes the
 * register addresses to `SetBgVerticalScrollPosition`), and the HBlank handler writes the next
 * line's value before that line is drawn.
 *
 * I/O register pointers are stored as offsets from 0x04000000 (-1 = NULL), so the handler writes into a
 * register image (`ByteArray` laid out like the I/O region) that [HBlankLines] turns into [LineTable]
 * overrides.
 */
public class BgVerticalScroll {
    /** `buf`: two frames × three registers × 160 lines, index `frame * 480 + reg * 160 + line`. */
    public val buf: ShortArray = ShortArray(3 * 320)

    /** `src[3]`: registers armed by the game for the next frame (I/O offset, -1 = none). */
    public val src: IntArray = IntArray(3) { -1 }

    /** `dst[3]`: registers written during the current frame (I/O offset, -1 = none). */
    public val dst: IntArray = IntArray(3) { -1 }

    /**
     * `unk_798[3]`: per-register flags (bits 0..1) set by `sub_8014EF4` / `sub_8014F10` /
     * `sub_8014F30`; their meaning is unknown and `HBlank_BgVerticalScroll` does not read them.
     */
    public val mode: IntArray = IntArray(3)

    /** `frame_flip`: the half of [buf] the handler latches into [frame] at the next VBlank line. */
    public var frameFlip: Int = 0

    /** `frame`: half of [buf] the handler reads this frame. */
    public var frame: Int = 0

    /** `updating`: the handler writes registers this frame. */
    public var updating: Boolean = false

    /** `scroll_en`: start updating at the next VBlank (line 160); false stops it there. */
    public var scrollEnabled: Boolean = false

    /** Loads every field from a 0x7A0-byte `struct BgVerticalScrollSt` image in [mem] at [offset]. */
    public fun readFrom(mem: ByteArray, offset: Int) {
        fun u8(i: Int) = mem[offset + i].toInt() and 0xFF
        fun u32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
        for (i in buf.indices) buf[i] = (u8(2 * i) or (u8(2 * i + 1) shl 8)).toShort()
        for (i in 0 until 3) {
            src[i] = ioOffset(u32(0x780 + 4 * i))
            dst[i] = ioOffset(u32(0x78C + 4 * i))
            mode[i] = u8(0x798 + i)
        }
        frameFlip = u8(0x79B)
        frame = u8(0x79C)
        updating = u8(0x79D) != 0
        scrollEnabled = u8(0x79E) != 0
    }

    /**
     * HBlank handler, called with the line just drawn ([vcount] = `REG_VCOUNT`, 0..227): writes line
     * `vcount + 1`'s values into the register image [io] while updating; on entering VBlank latches the
     * armed registers and buffer half for the next frame.
     *
     * Ported from src/bmlib-hblank.c: HBlank_BgVerticalScroll
     */
    public fun onHBlank(vcount: Int, io: ByteArray) {
        var line = (vcount + 1) and 0xFFFF
        if (line == TOTAL_LINES) line = 0
        if (line < LINES) {
            if (updating) {
                val base = frame * 480 // 3 registers * 160 lines per half of [buf]
                for (i in 0 until 3) {
                    val reg = dst[i]
                    if (reg < 0) continue
                    val value = buf[base + LINES * i + line].toInt()
                    io[reg] = value.toByte()
                    io[reg + 1] = (value shr 8).toByte()
                }
            }
            return
        }
        if (line == LINES) {
            if (scrollEnabled) {
                updating = true
                frame = frameFlip
                for (i in 0 until 3) dst[i] = src[i]
            } else {
                updating = false
            }
        }
    }

    /**
     * Offset in [buf] of register [registerIndex]'s 160-line table in the half `frameFlip + flip`
     * (`flip` 1 = the half the game fills for the next frame).
     *
     * Ported from src/bmlib-hblank.c: GetBgVerticalScrollBuffer
     */
    public fun bufferOffset(registerIndex: Int, flip: Int): Int = ((frameFlip + flip) and 1) * 480 + registerIndex * LINES

    /**
     * Makes the half just filled the one the handler latches at the next VBlank.
     *
     * Ported from src/bmlib-hblank.c: FlipBgVerticalScroll
     */
    public fun flip() {
        frameFlip = frameFlip xor 1
    }

    private companion object {
        /** Visible scanlines (`DISPLAY_HEIGHT`); the handler's VBlank check is for this line. */
        const val LINES = 160

        /** Scanlines per frame including VBlank (`REG_VCOUNT` 0..227). */
        const val TOTAL_LINES = 228

        /** Register pointer (0x04000000 + offset) to an offset into the I/O image; NULL = -1. */
        fun ioOffset(ptr: Int): Int = if (ptr == 0) -1 else ptr - 0x04000000
    }
}
