package fe8.game.bm

import gbafe.core.math.Interpolate
import gbafe.render.HBlankHandler
import gbafe.render.HBlankHandlers

/**
 * The map animation scanline buffers and the frame gradient effect (`src/mapanim_scanline.c`):
 * two 320-halfword buffers (`gManimScanlineBufA` holds both) that an HBlank handler reads one
 * entry per line. Only the gradient effect of the map battle info box is here (EP-22).
 *
 * The decomp's buffer pointers (`gManimScanlineBufs`, `gManimActiveScanlineBuf`) are indices into
 * [bufA].
 *
 * @param hblank the HBlank slots `SetPrimaryHBlankHandler` writes; null drops the handler (tests)
 */
public class MapAnimScanline(private val hblank: HBlankHandlers? = null) {
    /**
     * Receives the palette entry the gradient handler writes during HBlank: palette entry
     * [index] (0..255 background) becomes [color] from [line] on. Null drops the writes.
     */
    public fun interface PaletteLineWriter {
        /** Sets palette entry [index] to [color] from [line] on. */
        public fun write(line: Int, index: Int, color: Int)
    }

    /** Where the gradient handler's palette writes go; null drops them. */
    public var paletteWriter: PaletteLineWriter? = null

    /** `gManimScanlineBufA`: both buffers, 4 * 160 halfwords. */
    public val bufA: ShortArray = ShortArray(DISPLAY_HEIGHT * 4)

    /** `gManimScanlineBufs[2]`: start index in [bufA] of each buffer; -1 is NULL. */
    public val bufs: IntArray = intArrayOf(NULL, NULL)

    /** `gManimActiveScanlineBuf`: start index in [bufA] of the buffer the handler reads; -1 is NULL. */
    public var activeBuf: Int = NULL
        private set

    /** The gradient handler, installed by [startManimFrameGradientScanlineEffect]. */
    public val gradientHandler: HBlankHandler = HBlankHandler { vcount, _ -> sub8081FFC(vcount) }

    /**
     * Initialises both buffers to the empty window ([InitScanlineBuf]) and makes the first the
     * active one.
     *
     * Ported from src/mapanim_scanline.c: InitScanline
     */
    public fun initScanline() {
        initScanlineBuf(0)
        initScanlineBuf(SECOND_BUF)
        bufs[0] = 0
        bufs[1] = SECOND_BUF
        activeBuf = 0
    }

    /**
     * Sets the 160 halfwords at [buf] to `DISPLAY_WIDTH | (DISPLAY_WIDTH << 8)`.
     *
     * Ported from src/mapanim_scanline.c: InitScanlineBuf
     */
    public fun initScanlineBuf(buf: Int) {
        for (i in 0 until DISPLAY_HEIGHT) {
            bufA[buf + i] = (DISPLAY_WIDTH or (DISPLAY_WIDTH shl 8)).toShort()
        }
    }

    /** Swaps the two buffers of [bufs]; the handler picks the new first one up at the next frame. */
    public fun swapScanlineBufs() {
        val tmp = bufs[0]
        bufs[0] = bufs[1]
        bufs[1] = tmp
    }

    /**
     * Fills 160 halfwords at [buf] with a vertical gradient: [colorA] above line [yTop], [colorB]
     * below line [yBottom], and the per-channel linear blend in between.
     *
     * Ported from src/mapanim_scanline.c: PrepareGradientScanlineBuf
     */
    public fun prepareGradientScanlineBuf(
        buf: Int,
        yTop: Int,
        yBottom: Int,
        colorA: Int,
        colorB: Int,
    ) {
        val scanlines = yBottom - yTop
        var scanline = 0
        for (i in 0 until DISPLAY_HEIGHT) {
            if (i < yTop) {
                bufA[buf + i] = colorA.toShort()
                continue
            }
            if (i > yBottom) {
                bufA[buf + i] = colorB.toShort()
                continue
            }
            val r = Interpolate.interpolate(
                Interpolate.LINEAR, colorA and 0x001F, colorB and 0x001F, scanline, scanlines,
            )
            val g = Interpolate.interpolate(
                Interpolate.LINEAR, colorA and 0x03E0, colorB and 0x03E0, scanline, scanlines,
            )
            val b = Interpolate.interpolate(
                Interpolate.LINEAR, colorA and 0x7C00, colorB and 0x7C00, scanline, scanlines,
            )
            bufA[buf + i] = ((r and 0x001F) or (g and 0x03E0) or (b and 0x7C00)).toShort()
            scanline++
        }
    }

    /**
     * Prepares two gradients in the spare buffer (the first half from [colorArg3] to its
     * halved colour, the second from [colorArg4] to its halved colour), swaps it in and installs
     * [gradientHandler] as the primary HBlank handler.
     *
     * Ported from src/mapanim_scanline.c: StartManimFrameGradientScanlineEffect
     */
    public fun startManimFrameGradientScanlineEffect(
        yTop: Int,
        yBottom: Int,
        colorArg3: Int,
        colorArg4: Int,
    ) {
        prepareGradientScanlineBuf(bufs[1], yTop, yBottom, colorArg3, rgbHalved(colorArg3))
        prepareGradientScanlineBuf(
            bufs[1] + DISPLAY_HEIGHT, yTop, yBottom, colorArg4, rgbHalved(colorArg4),
        )
        swapScanlineBufs()
        hblank?.setPrimary(gradientHandler)
    }

    /**
     * The gradient effect's HBlank handler: after line [vcount] it loads palette entries 17
     * (bank 1 colour 1) and 33 (bank 2 colour 1) for the next line from the active buffer's two
     * halves; the line past the screen picks up the buffer that was swapped in
     * (`gManimScanlineBufs[0]`) and starts at line 0.
     *
     * Transcribed from src/mapanim_scanline.c: sub_8081FFC (not verified: the capture's hardware
     * palette is not sampled at a known line and its entry 33 matches no buffer value; a
     * per-line palette trace would verify it)
     */
    public fun sub8081FFC(vcount: Int) {
        var line = vcount
        if (line >= DISPLAY_HEIGHT) {
            activeBuf = bufs[0]
            line = 0
        } else {
            line++
        }
        // After line 159 the original reads past the buffer (VBlank, no line is drawn); skipped.
        if (line >= DISPLAY_HEIGHT) return
        paletteWriter?.write(line, PALETTE_ENTRY_A, bufA[activeBuf + line].toInt() and 0xFFFF)
        paletteWriter?.write(
            line, PALETTE_ENTRY_B, bufA[activeBuf + DISPLAY_HEIGHT + line].toInt() and 0xFFFF,
        )
    }

    private fun rgbHalved(color: Int): Int =
        halved(color, 0x1F) or halved(color, 0x1F shl 5) or halved(color, 0x1F shl 10)

    private fun halved(color: Int, mask: Int): Int = ((mask and color) shr 1) and mask

    private companion object {
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160

        /** Offset of the second buffer in [bufA] (`gManimScanlineBufA + 0x140`). */
        const val SECOND_BUF = 0x140
        const val NULL = -1

        /** `PLTT[0x10 * (1 + 0) + 1]` and `PLTT[0x10 * (1 + 1) + 1]`. */
        const val PALETTE_ENTRY_A = 0x11
        const val PALETTE_ENTRY_B = 0x21
    }
}
