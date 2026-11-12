package gbafe.render

/**
 * The BG side of the games' `src/hardware.c` buffers: the four BG tilemap buffers, a VRAM image, the palette
 * buffer, the sync flags and the OAM buffer split, with the functions that set them up for a new screen.
 */
public class BgHardware(
    /** `gLCDControlBuffer`. */
    public val lcd: LcdControl = LcdControl(),
    /** `gPaletteBuffer` (512 colours). */
    public val palette: ShortArray = ShortArray(PALETTE_COLORS),
) {
    /** `gBG0TilemapBuffer`..`gBG3TilemapBuffer` (32×32 u16 entries each); see [mapBuffer]. */
    public val tilemaps: Array<ShortArray> = Array(4) { ShortArray(TILEMAP_ENTRIES) }

    /** BG/OBJ VRAM (`0x06000000`, 96 KiB). */
    public val vram: ByteArray = ByteArray(VRAM_SIZE)

    /** Palette RAM (`PLTT`, 512 colours), written by [flushBackgrounds]. */
    public val paletteRam: ShortArray = ShortArray(PALETTE_COLORS)

    /** `gBGVramTilemapPointers[bg] - VRAM`: where BG sync copies each tilemap buffer. */
    public val vramTilemapOffsets: IntArray = IntArray(4)

    /** `sModifiedBGs`: BG tilemap buffers to copy to VRAM on the next sync (bit n = BG n). */
    public var modifiedBgs: Int = 0

    /** `sModifiedPalette`: copy [palette] to palette RAM on the next sync. */
    public var modifiedPalette: Boolean = false

    /** HBlank handler slots (`SetPrimaryHBlankHandler` / `SetSecondaryHBlankHandler`). */
    public val hblank: HBlankHandlers = HBlankHandlers(lcd)

    /**
     * `sOamLo.count` (the `InitOam` argument); `sOamHi` gets the other `128 - oamLoCount` entries.
     * Separate from [OamBuffer.loCount], which [initOam] does not update (no caller sets it).
     */
    public var oamLoCount: Int = 0

    /**
     * `BG_GetMapBuffer`: the tilemap buffer of [bg].
     *
     * Ported from src/hardware.c: BG_GetMapBuffer
     */
    public fun mapBuffer(bg: Int): ShortArray = tilemaps[bg]

    /**
     * `GetBackgroundTileDataOffset`: VRAM offset of [bg]'s character data (`charBaseBlock << 14`).
     *
     * Ported from src/hardware.c: GetBackgroundTileDataOffset
     */
    public fun tileDataOffset(bg: Int): Int = ((lcd.bgCnt[bg] shr 2) and 3) shl 14

    /**
     * `SetBackgroundTileDataOffset`: sets [bg]'s 2-bit `charBaseBlock` to `offset >> 14`.
     *
     * Ported from src/hardware.c: SetBackgroundTileDataOffset
     */
    public fun setTileDataOffset(bg: Int, offset: Int) {
        // BGxCNT bits 2..3 = character base block (16 KiB units)
        lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 0x000C.inv()) or (((offset shr 14) and 3) shl 2)
    }

    /**
     * `SetBackgroundMapDataOffset`: if [offset] is 0x800-aligned, sets [bg]'s `screenBaseBlock` and its VRAM
     * tilemap destination; otherwise does nothing.
     *
     * Ported from src/hardware.c: SetBackgroundMapDataOffset
     */
    public fun setMapDataOffset(bg: Int, offset: Int) {
        if (offset and 0x7FF == 0) {
            // BGxCNT bits 8..12 = screen base block (2 KiB units)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 0x1F00.inv()) or (((offset shr 11) and 0x1F) shl 8)
            vramTilemapOffsets[bg] = offset
        }
    }

    /**
     * `SetBackgroundScreenSize`: sets [bg]'s 2-bit `screenSize`.
     *
     * Ported from src/hardware.c: SetBackgroundScreenSize
     */
    public fun setScreenSize(bg: Int, size: Int) {
        // BGxCNT bits 14..15 = screen size
        lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 0xC000.inv()) or ((size and 3) shl 14)
    }

    /**
     * `BG_Fill`: fills the whole 32×32 [buffer] with [value].
     *
     * Ported from src/hardware.c: BG_Fill
     */
    public fun fill(buffer: ShortArray, value: Int) {
        buffer.fill(value.toShort())
    }

    /** `CpuFastFill16(value, VRAM + vramOffset, bytes)`: fills [bytes] of [vram] with the u16 [value]. */
    public fun vramFill16(value: Int, vramOffset: Int, bytes: Int) {
        var i = vramOffset
        val end = vramOffset + bytes
        while (i < end) {
            vram[i] = value.toByte()
            vram[i + 1] = (value shr 8).toByte()
            i += 2
        }
    }

    /**
     * `InitOam`: splits the 128 OAM entries into a low part of [loSize] and a high part of the rest.
     *
     * Ported from src/hardware.c: InitOam
     */
    public fun initOam(loSize: Int) {
        oamLoCount = loSize
    }

    /**
     * `InitBmBgLayers` (src/bm.c): BG priorities 0, 1, 2, 3, or 0, 1, 2, 2 when [cloudsWeather]
     * (`gPlaySt.chapterWeatherId == WEATHER_CLOUDS`).
     *
     * Ported from src/bm.c: InitBmBgLayers
     */
    public fun initBmBgLayers(cloudsWeather: Boolean) {
        for (bg in 0..3) {
            val priority = if (cloudsWeather && bg == 3) 2 else bg
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or priority // BGxCNT bits 0..1
        }
    }

    /**
     * `EnablePaletteSync`: palette buffer to palette RAM on the next sync.
     *
     * Ported from src/hardware.c: EnablePaletteSync
     */
    public fun enablePaletteSync() {
        modifiedPalette = true
    }

    /**
     * `BG_EnableSyncByMask`: BG tilemap buffers in [mask] to VRAM on the next sync.
     *
     * Ported from src/hardware.c: BG_EnableSyncByMask
     */
    public fun enableBgSyncByMask(mask: Int) {
        modifiedBgs = modifiedBgs or mask
    }

    /**
     * `SetupBackgrounds`: resets BG control (from [config]: tile offset, map offset, screen size per BG, or the
     * default 0/0x6000, 0/0x6800, 0/0x7000, 0x8000/0x7800, all size 0), BG scroll, clears the tilemap buffers
     * and the first 64 bytes of each BG's tile data, BG priorities ([initBmBgLayers]), OAM split 0, backdrop
     * colour 0, and DISPCNT to mode 0 with all BGs and OBJ on, no windows, no forced blank. Marks all BGs and the
     * palette for sync.
     *
     * Ported from src/hardware.c: SetupBackgrounds
     */
    public fun setupBackgrounds(config: IntArray? = null, cloudsWeather: Boolean = false) {
        val bgConfig = config ?: DEFAULT_BG_CONFIG
        for (bg in 0..3) lcd.bgCnt[bg] = 0
        for (bg in 0..3) {
            setTileDataOffset(bg, bgConfig[bg * 3])
            setMapDataOffset(bg, bgConfig[bg * 3 + 1])
            setScreenSize(bg, bgConfig[bg * 3 + 2])
            lcd.setBgPosition(bg, 0, 0)
            fill(mapBuffer(bg), 0)
            vramFill16(0, tileDataOffset(bg), 64)
        }
        initBmBgLayers(cloudsWeather)
        modifiedBgs = modifiedBgs or 0xF // all four BGs
        initOam(0)
        palette[0] = 0
        modifiedPalette = true
        // Clear DISPCNT_FORCED_BLANK (bit 7), the mode (0..2) and DISPCNT_WIN0_ON / WIN1_ON /
        // OBJWIN_ON (13..15); set DISPCNT_BG_ALL_ON | DISPCNT_OBJ_ON (0x1F00).
        lcd.dispcnt = (lcd.dispcnt and (0xE000 or 0x0087).inv()) or 0x1F00
    }

    /**
     * `FlushBackgrounds` (VBlank sync): copies each BG tilemap buffer flagged in [modifiedBgs] to its VRAM
     * destination, then, if [modifiedPalette], the palette buffer to [paletteRam], with `colorAddition` added to
     * every channel when it is not 0 (`ApplyColorAddition_ClampMax` for positive values, clamped to
     * 31; `ApplyColorAddition_ClampMin` for negative ones, clamped to 0; one clamp to 0..31 covers
     * both here; not verified yet). Clears both flags.
     *
     * Ported from src/hardware.c: FlushBackgrounds
     */
    public fun flushBackgrounds() {
        for (bg in 0..3) {
            if (modifiedBgs and (1 shl bg) == 0) continue
            val tilemap = tilemaps[bg]
            var dstOffset = vramTilemapOffsets[bg]
            for (i in 0 until TILEMAP_ENTRIES) {
                val entry = tilemap[i].toInt()
                vram[dstOffset] = entry.toByte()
                vram[dstOffset + 1] = (entry shr 8).toByte()
                dstOffset += 2
            }
        }
        modifiedBgs = 0
        if (modifiedPalette) {
            modifiedPalette = false
            val addition = lcd.colorAddition
            if (addition == 0) {
                palette.copyInto(paletteRam)
            } else {
                for (i in 0 until PALETTE_COLORS) {
                    val color = palette[i].toInt()
                    val red = ((color and 0x1F) + addition).coerceIn(0, 31)
                    val green = (((color shr 5) and 0x1F) + addition).coerceIn(0, 31)
                    val blue = (((color shr 10) and 0x1F) + addition).coerceIn(0, 31)
                    paletteRam[i] = ((blue shl 10) or (green shl 5) or red).toShort()
                }
            }
        }
    }

    /** Buffer sizes and the default BG layout. */
    public companion object {
        /** Entries in one BG tilemap buffer. */
        public const val TILEMAP_ENTRIES: Int = 0x400

        /** BG + OBJ VRAM bytes. */
        public const val VRAM_SIZE: Int = 0x18000

        /** Colours in `gPaletteBuffer` (BG + OBJ). */
        public const val PALETTE_COLORS: Int = 0x200

        /** Per BG: tile data offset, tilemap offset, screen size (`defaultBgConfig`). */
        private val DEFAULT_BG_CONFIG = intArrayOf(
            0x0000, 0x6000, 0,
            0x0000, 0x6800, 0,
            0x0000, 0x7000, 0,
            0x8000, 0x7800, 0,
        )
    }
}
