package fe8.game.worldmap

import gbafe.render.HBlankHandler
import gbafe.render.HBlankHandlers

/**
 * Receives the palette color [WorldMapHblank] writes to palette RAM during HBlank (the
 * per-scanline palette entry change the world map's entry band uses, see
 * `GmMuEntry`/worldmap_entry.c:513).
 */
public fun interface PaletteLineWriter {
    /** Sets palette entry [index] (0..255 background, 256.. object) to [color] from [line] on. */
    public fun write(line: Int, index: Int, color: Int)
}

/**
 * The scanline-effect state of the world map (`src/worldmap_hb.c`): a double-buffered table of
 * per-scanline register values (`gUnknown_0201BE32`) that `WorldMapHblank` copies into up to three
 * hardware registers during HBlank, plus the status bits `gWmHblankStatus`. World map effects
 * that change something line by line (the opening's line fade, the entry band) fill the table
 * and register the targets.
 *
 * The handler [handler] copies the active page's per-line values into up to three registers
 * ([registers] latched at line 160 into [activeRegisters]); the register is an I/O register
 * (written into the register image the renderer captures per line) or a palette entry (handed
 * to [palette]). The world map uses BG0VOFS (`0x04000012`, worldmap_entry.c:508), BLDY
 * (`0x04000054`, worldmap_line_fade.c:108) and palette byte 0x54 (`0x05000054`,
 * worldmap_entry.c:513), i.e. palette color 42 (bank 2 color 10).
 *
 * @param palette where `0x05000000` register writes go; null drops them (tests)
 */
public class WorldMapHblank(private val palette: PaletteLineWriter? = null) {
    /**
     * `gUnknown_0201BE32`: two pages of three lines of [LINE_LENGTH] halfwords (480 each).
     */
    public val buffer: ShortArray = ShortArray(2 * PAGE_LENGTH)

    /**
     * `gUnknown_0201C5B4[3]`: the register address each line is written to (0 = unused).
     */
    public val registers: IntArray = IntArray(3)

    /**
     * `gUnknown_0201C5CC[3]`: per-line flags (meaning unknown; the decomp's accessors only keep
     * bits 0 and 1 and nothing reads them in the files checked).
     */
    public val flags: IntArray = IntArray(3)

    /** `gUnknown_0201C5CF`: the page the HBlank handler is not reading. */
    public var page: Int = 0

    /** `gUnknown_0201C5D0`: the page the HBlank handler reads (written by the handler only). */
    public var activePage: Int = 0

    /**
     * `gWmHblankStatus` (also cleared by the text box, `worldmap_text.c`): bit 0 is only cleared
     * by the text code and never read (meaning unknown), bit 2 enables the register copy. Bit 1
     * (the `REG_WIN0H` copy of the map-animation scanline buffer) is never set by world map code,
     * so [onHBlank] leaves that branch out.
     */
    public var status: Int = 0

    /** `gUnknown_0201C5C0[3]`: the registers written this frame (latched at line 160). */
    public val activeRegisters: IntArray = IntArray(3)

    /** `gUnknown_0201C5D1`: the handler writes [activeRegisters] this frame. */
    public var copyEnabled: Boolean = false

    /** The handler to install in the primary HBlank slot. */
    public val handler: HBlankHandler = HBlankHandler { vcount, io -> onHBlank(vcount, io) }

    /**
     * Ported from src/worldmap_hb.c: WorldMapHblank
     *
     * Runs at the HBlank of line [vcount] (`REG_VCOUNT`); the values it writes apply to the next
     * line. Lines 0..159 copy `buffer[activePage * 480 + line + 160 * i]` to register i of
     * [activeRegisters]; at line 160 the [status] bit 2, [page] and [registers] are latched.
     */
    public fun onHBlank(vcount: Int, io: ByteArray) {
        var line = vcount + 1
        if (line == LAST_LINE) line = 0
        if (line < SCREEN_LINES) {
            if (copyEnabled) {
                val src = activePage * PAGE_LENGTH + line
                for (i in 0 until 3) {
                    val reg = activeRegisters[i]
                    if (reg != 0) write(reg, buffer[src + i * LINE_LENGTH].toInt() and 0xFFFF, line, io)
                }
            }
        } else if (line == SCREEN_LINES) {
            if (status and 4 != 0) {
                copyEnabled = true
                activePage = page
                for (i in 0 until 3) activeRegisters[i] = registers[i]
            } else {
                copyEnabled = false
            }
        }
    }

    /**
     * Writes [value] to the register address [reg]: region 4 (`0x04......`) is the I/O register
     * image, region 5 (`0x05......`) palette RAM, applying from [line] on.
     */
    private fun write(reg: Int, value: Int, line: Int, io: ByteArray) {
        val offset = reg and 0xFFFFFF
        when (reg ushr 24) {
            IO_REGION -> if (offset + 1 < io.size) {
                io[offset] = value.toByte()
                io[offset + 1] = (value shr 8).toByte()
            }
            PALETTE_REGION -> palette?.write(line, offset shr 1, value)
        }
    }

    /**
     * Ported from src/worldmap_hb.c: SetWorldMapHblank
     *
     * Called when the world map starts.
     *
     * Clears the slots, pages and [status] and installs [handler] as [handlers]' primary handler.
     */
    public fun setWorldMapHblank(handlers: HBlankHandlers) {
        for (i in 0 until 3) {
            flags[i] = 0
            activeRegisters[i] = 0
            registers[i] = 0
        }
        activePage = 0
        page = 0
        copyEnabled = false
        status = 0
        handlers.setPrimary(null)
        handlers.setPrimary(handler)
    }

    /**
     * Ported from src/worldmap_hb.c: ClearWmHblank
     *
     * Called when the world map ends (`WorldMap_Destruct`).
     *
     * Removes the primary handler of [handlers], zeroes [buffer] and [status].
     */
    public fun clearWmHblank(handlers: HBlankHandlers) {
        handlers.setPrimary(null)
        buffer.fill(0)
        status = 0
    }

    /**
     * Ported from src/worldmap_hb.c: sub_80C1DA0
     *
     * Decomp name `sub_80C1DA0`. The index into [buffer] of line [line] on page
     * `(page + pageOffset) & 1`; the original returns the pointer.
     */
    public fun lineBufferIndex(line: Int, pageOffset: Int): Int =
        ((page + pageOffset) and 1) * PAGE_LENGTH + line * LINE_LENGTH

    /**
     * Ported from src/worldmap_hb.c: sub_80C1DC8
     *
     * Decomp name `sub_80C1DC8`. Swaps the two pages: the page filled with offset 1 by
     * [lineBufferIndex] becomes the one the handler reads next.
     */
    public fun swapPages() {
        page = page xor 1
    }

    /**
     * Ported from src/worldmap_hb.c: sub_80C1DD8
     *
     * Decomp name `sub_80C1DD8`. Sets the register address line [idx] (0..2) is copied to; 0 =
     * unused.
     */
    public fun setLineRegister(idx: Int, reg: Int) {
        registers[idx] = reg
    }

    /**
     * Transcribed from src/worldmap_hb.c: sub_80C1DE8 (not verified: [flags] is 0 in every dump
     * of the capture, so the masking cannot be told from a no-op)
     *
     * Decomp name `sub_80C1DE8`. Keeps only bits 0 and 1 of the per-line [flags] entry [idx].
     */
    public fun maskLineFlags(idx: Int) {
        flags[idx] = flags[idx] and 3
    }

    private companion object {
        /** Halfwords per line of a page (one per scanline of the 160 line screen). */
        const val LINE_LENGTH = 160

        /** Halfwords per page: three lines (one per register), `* 480` in the decomp. */
        const val PAGE_LENGTH = 3 * LINE_LENGTH

        /** Visible scanlines (`0xA0`). */
        const val SCREEN_LINES = 160

        /** `vcount == 0xE4` (the last line, 228) wraps to line 0. */
        const val LAST_LINE = 0xE4

        /** Top byte of the register address: `0x04` I/O registers, `0x05` palette RAM. */
        const val IO_REGION = 4
        const val PALETTE_REGION = 5
    }
}
