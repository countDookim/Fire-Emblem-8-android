package fe8.game.worldmap

import gbafe.render.HBlankHandler
import gbafe.render.HBlankHandlers

/** Receives the palette colour [WorldMapHblank] writes to palette RAM during HBlank. */
public fun interface PaletteLineWriter {
    /** Sets palette entry [index] (0..255 background, 256.. object) to [color] from [line] on. */
    public fun write(line: Int, index: Int, color: Int)
}

/**
 * The scanline-effect state of the world map (`src/worldmap_hb.c`): a double-buffered table of
 * per-scanline register values (`gUnknown_0201BE32`) that `WorldMapHblank` copies into up to three
 * hardware registers during HBlank, plus the status bits `gWmHblankStatus`.
 *
 * The handler [handler] copies the active page's per-line values into up to three registers
 * ([registers] latched at line 160 into [activeRegisters]); the register is an I/O register
 * (written into the register image the renderer captures per line) or a palette entry (handed
 * to [palette]). The Prologue's world map uses BG0VOFS, BLDY and palette colour 42 (bank 2
 * colour 10, the text box's gradient).
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

    /** `gUnknown_0201C5CC[3]`: per-line flags; the accessors here only keep bits 0 and 1. */
    public val flags: IntArray = IntArray(3)

    /** `gUnknown_0201C5CF`: the page the HBlank handler is not reading. */
    public var page: Int = 0

    /** `gUnknown_0201C5D0`: the page the HBlank handler reads (written by the handler only). */
    public var activePage: Int = 0

    /**
     * `gWmHblankStatus` (shared with the text box, `worldmap_text.c`): bit 0 text, bit 2 enables
     * the register copy. Bit 1 (the `REG_WIN0H` copy of the map-animation scanline buffer) is
     * never set by world map code, so [onHBlank] leaves that branch out.
     */
    public var status: Int = 0

    /** `gUnknown_0201C5C0[3]`: the registers the handler writes this frame (latched at line 160). */
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
     * The index into [buffer] of line [line] on page `(page + pageOffset) & 1`; the original
     * returns the pointer.
     */
    public fun sub80C1DA0(line: Int, pageOffset: Int): Int =
        ((page + pageOffset) and 1) * PAGE_LENGTH + line * LINE_LENGTH

    /**
     * Ported from src/worldmap_hb.c: sub_80C1DC8
     *
     * Swaps the page the next [sub80C1DA0] with offset 1 refers to.
     */
    public fun sub80C1DC8() {
        page = page xor 1
    }

    /**
     * Ported from src/worldmap_hb.c: sub_80C1DD8
     *
     * Sets the register address line [idx] is copied to.
     */
    public fun sub80C1DD8(idx: Int, reg: Int) {
        registers[idx] = reg
    }

    /**
     * Transcribed from src/worldmap_hb.c: sub_80C1DE8 (not verified: [flags] is 0 in every dump
     * of the capture, so the masking cannot be told from a no-op)
     */
    public fun sub80C1DE8(idx: Int) {
        flags[idx] = flags[idx] and 3
    }

    private companion object {
        const val LINE_LENGTH = 160
        const val PAGE_LENGTH = 3 * LINE_LENGTH
        const val SCREEN_LINES = 160
        const val LAST_LINE = 0xE4
        const val IO_REGION = 4
        const val PALETTE_REGION = 5
    }
}
