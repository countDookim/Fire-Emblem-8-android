package fe8.game.worldmap

/**
 * The scanline-effect state of the world map (`src/worldmap_hb.c`): a double-buffered table of
 * per-scanline register values (`gUnknown_0201BE32`) that `WorldMapHblank` copies into up to three
 * hardware registers during HBlank, plus the status bits `gWmHblankStatus`.
 *
 * Only the state and the four accessors the Prologue's `GmapMuEntry2` uses are written. The
 * HBlank handler itself (`WorldMapHblank`), `SetWorldMapHblank` and `ClearWmHblank` are not: the
 * handler also writes [activePage] and the active register slots in a way no step-level capture
 * shows, so only [buffer], [registers], [flags], [page] and [status] are verified.
 */
public class WorldMapHblank {
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

    /** `gWmHblankStatus`: bit 2 enables the register copy. */
    public var status: Int = 0

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
    }
}
