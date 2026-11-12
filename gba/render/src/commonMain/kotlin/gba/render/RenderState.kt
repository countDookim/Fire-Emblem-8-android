package gba.render

/**
 * Per-frame semantic render state (ADR 0008), filled by game systems each tick and consumed by the software
 * backend (reference, frame compare) and the GPU backend. Everything is preallocated primitive arrays; filling
 * a frame allocates nothing. No VRAM/OAM byte layouts: tile graphics are addressed by sheet id + tile index.
 */
public class RenderState {
    /** The four background layers, index = BG number (0..3). */
    public val layers: Array<TileLayer> = Array(LAYERS) { TileLayer() }

    /** Sprites (objects) for this frame. */
    public val sprites: SpriteBatch = SpriteBatch()

    /** BG and OBJ palettes. */
    public val palettes: PaletteBank = PaletteBank()

    /** Blending, brightness, windows, mosaic. */
    public val effects: Effects = Effects()

    /** Per-scanline overrides (ported HBlank effects); inactive unless a system fills it. */
    public val lines: LineTable = LineTable()

    /** Resets every part to its power-on default (layers off, no sprites, effects off, table inactive). */
    public fun reset() {
        for (layer in layers) layer.reset()
        sprites.clear()
        palettes.clear()
        effects.reset()
        lines.clear()
    }

    /** Sizes shared by the render-state types. */
    public companion object {
        /** Background layers. */
        public const val LAYERS: Int = 4

        /** Visible scanlines. */
        public const val LINES: Int = FrameBuffer.GBA_HEIGHT
    }
}

/**
 * One background layer: a map of tile entries plus scroll/priority, in text (tiled, scrolling) or affine
 * (rotation/scaling) mode.
 *
 * A tile entry is a `Short`: bits 0..9 tile index into [sheet], bit 10 horizontal flip, bit 11 vertical flip,
 * bits 12..15 palette (4bpp only). For affine layers only the low 8 bits (tile index) are used, as on hardware.
 */
public class TileLayer {
    /** Whether the layer is drawn (`DISPCNT` BGn enable, and the video mode has this BG). */
    public var enabled: Boolean = false

    /** `BGxCNT` priority 0 (front) .. 3 (back); equal priorities draw the lower BG number first. */
    public var priority: Int = 0

    /** Tile sheet id (resolved by the backend; an asset, never a VRAM address). */
    public var sheet: Int = 0

    /** 8bpp tiles (one 256-colour palette) instead of 4bpp (16 palettes of 16). */
    public var bpp8: Boolean = false

    /** `BGxCNT` mosaic bit: the `MOSAIC` register's BG block size applies to this layer. */
    public var mosaic: Boolean = false

    /** Map width in tiles (32 or 64 for text layers; 16..128 for affine). */
    public var widthTiles: Int = 32
        set(v) { require(v in 1..MAX_TILES); field = v }

    /** Map height in tiles. */
    public var heightTiles: Int = 32
        set(v) { require(v in 1..MAX_TILES); field = v }

    /** Horizontal scroll in pixels (text mode, `BGxHOFS`, 0..511). */
    public var scrollX: Int = 0

    /** Vertical scroll in pixels (text mode, `BGxVOFS`, 0..511). */
    public var scrollY: Int = 0

    /** Affine (rotation/scaling) mode instead of text mode. */
    public var affine: Boolean = false

    /** `BGxCNT` area overflow: whether the affine map wraps (otherwise outside = transparent). */
    public var wrap: Boolean = false

    /** `BGxPA` (affine matrix, 8.8 fixed point): texture x step per screen pixel to the right. */
    public var pa: Int = 0x100

    /** `BGxPB`: texture x step per screen line down. */
    public var pb: Int = 0

    /** `BGxPC`: texture y step per screen pixel to the right. */
    public var pc: Int = 0

    /** `BGxPD`: texture y step per screen line down. */
    public var pd: Int = 0x100

    /**
     * `BGxX` affine reference point x: the texture x shown at the start of screen line 0 (28-bit
     * signed: sign, 19 integer bits, 8 fraction bits; stored sign-extended).
     */
    public var refX: Int = 0

    /** `BGxY` affine reference point y (same format as [refX]). */
    public var refY: Int = 0

    /** Tile entries, row-major with a fixed stride of [MAX_TILES]. */
    public val entries: ShortArray = ShortArray(MAX_TILES * MAX_TILES)

    /** Entry at tile ([x], [y]). */
    public fun entry(x: Int, y: Int): Int = entries[y * MAX_TILES + x].toInt() and 0xFFFF

    /** Sets the entry at tile ([x], [y]). */
    public fun setEntry(x: Int, y: Int, value: Int) {
        entries[y * MAX_TILES + x] = value.toShort()
    }

    /** Back to defaults: disabled, 32×32, no scroll, identity affine, empty map. */
    public fun reset() {
        enabled = false; priority = 0; sheet = 0; bpp8 = false; mosaic = false
        widthTiles = 32; heightTiles = 32; scrollX = 0; scrollY = 0
        affine = false; wrap = false; pa = 0x100; pb = 0; pc = 0; pd = 0x100; refX = 0; refY = 0
        entries.fill(0)
    }

    /** Entry bit layout and map limits. */
    public companion object {
        /** Largest map side in tiles (the biggest affine map, 128 tiles = 1024 px). */
        public const val MAX_TILES: Int = 128

        /** Tile index mask (bits 0..9 of a text tile entry). */
        public const val TILE_MASK: Int = 0x3FF

        /** Horizontal flip bit (bit 10; `TILE_HFLIP` in the decomp). */
        public const val HFLIP: Int = 0x400

        /** Vertical flip bit (bit 11; `TILE_VFLIP` in the decomp). */
        public const val VFLIP: Int = 0x800

        /** Shift of the 4-bit palette bank (bits 12..15; `TILEREF(chr, pal)` in the decomp). */
        public const val PALETTE_SHIFT: Int = 12
    }
}

/**
 * Fixed-capacity sprite list (hardware limit 128 objects), stored as parallel primitive arrays. Index order is
 * hardware order: a lower index wins between sprites of equal priority.
 */
public class SpriteBatch(
    /** Maximum sprites per frame. */
    public val capacity: Int = 128,
) {
    /** Sprites in use this frame. */
    public var count: Int = 0
        private set

    /** Screen x of the top-left corner (may be negative). */
    public val x: IntArray = IntArray(capacity)

    /** Screen y of the top-left corner (may be negative). */
    public val y: IntArray = IntArray(capacity)

    /** Width in pixels (8, 16, 32 or 64), from OAM attr0 shape + attr1 size. */
    public val width: ByteArray = ByteArray(capacity)

    /** Height in pixels (8, 16, 32 or 64). */
    public val height: ByteArray = ByteArray(capacity)

    /** Sheet id holding the sprite's tiles. */
    public val sheet: IntArray = IntArray(capacity)

    /** First tile index in [sheet] (OAM attr2 bits 0..9), in 32-byte units (8bpp tile = two). */
    public val tile: ShortArray = ShortArray(capacity)

    /** OBJ palette bank 0..15 (OAM attr2 bits 12..15; 4bpp only, ignored for 8bpp). */
    public val palette: ByteArray = ByteArray(capacity)

    /**
     * Priority 0 (front) .. 3 (back) (OAM attr2 bits 10..11), compared with the BG layers'
     * priority; a sprite is in front of a BG with an equal or greater value.
     */
    public val priority: ByteArray = ByteArray(capacity)

    /**
     * Flag bits (OAM attr0/attr1 bits regrouped): [HFLIP], [VFLIP], [MOSAIC], [BPP8], [AFFINE],
     * [DOUBLE_SIZE], [MODE_SEMI], [MODE_WINDOW].
     */
    public val flags: ShortArray = ShortArray(capacity)

    /** Affine matrix index (0..31, OAM attr1 bits 9..13) when [AFFINE] is set. */
    public val affineIndex: ByteArray = ByteArray(capacity)

    /** 32 affine matrices × (pa, pb, pc, pd), 8.8 fixed point (the OAM rotation/scaling slots). */
    public val affine: ShortArray = ShortArray(AFFINE_MATRICES * 4)

    /**
     * Tile layout of multi-tile sprites (`DISPCNT_OBJ_1D_MAP`, bit 6). False = 2D: the sheet is 32
     * units wide, so the tile below unit `t` is `t + 32` (`obj1dMap = 0`, set in
     * `src/banim-ekrdispup.c` and `src/opanim-main.c`). True = 1D: a sprite's tiles are
     * consecutive, row after row (`obj1dMap = 1`, set in `src/opanim-main.c`, the opening
     * animation). Kept by [clear].
     */
    public var mapping1d: Boolean = false

    /** Removes all sprites (matrices are kept). */
    public fun clear() {
        count = 0
    }

    /** Appends a sprite with the fields above; returns its index, or -1 if the batch is full. */
    public fun add(x: Int, y: Int, width: Int, height: Int, sheet: Int, tile: Int, palette: Int, priority: Int, flags: Int = 0, affineIndex: Int = 0): Int {
        if (count == capacity) return -1
        val spriteIndex = count++
        this.x[spriteIndex] = x; this.y[spriteIndex] = y
        this.width[spriteIndex] = width.toByte(); this.height[spriteIndex] = height.toByte()
        this.sheet[spriteIndex] = sheet; this.tile[spriteIndex] = tile.toShort()
        this.palette[spriteIndex] = palette.toByte(); this.priority[spriteIndex] = priority.toByte()
        this.flags[spriteIndex] = flags.toShort(); this.affineIndex[spriteIndex] = affineIndex.toByte()
        return spriteIndex
    }

    /** Sets affine matrix [index] (0..31) to ([pa], [pb], [pc], [pd]), 8.8 fixed point. */
    public fun setAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {
        val offset = index * 4
        affine[offset] = pa.toShort()
        affine[offset + 1] = pb.toShort()
        affine[offset + 2] = pc.toShort()
        affine[offset + 3] = pd.toShort()
    }

    /** Flag bits. */
    public companion object {
        /** Horizontal flip (non-affine). */
        public const val HFLIP: Int = 1

        /** Vertical flip (non-affine). */
        public const val VFLIP: Int = 2

        /** Mosaic applies. */
        public const val MOSAIC: Int = 4

        /** 8bpp tiles. */
        public const val BPP8: Int = 8

        /** Uses affine matrix [affineIndex]. */
        public const val AFFINE: Int = 16

        /** Affine with a doubled bounding box. */
        public const val DOUBLE_SIZE: Int = 32

        /**
     * Semi-transparent (OAM attr0 mode 1): alpha-blended with the pixel below when that pixel's
     * layer is a second target (whatever the first-target bits say).
     */
        public const val MODE_SEMI: Int = 64

        /** OBJ window (OAM attr0 mode 2): not drawn; its opaque pixels form the OBJ window mask. */
        public const val MODE_WINDOW: Int = 128

        /** Number of affine matrices. */
        public const val AFFINE_MATRICES: Int = 32
    }
}

/** BG and OBJ palettes: 256 BGR555 colours each. BG colour 0 is the backdrop. */
public class PaletteBank {
    /** Background colours (16 palettes × 16, or one 256-colour palette). */
    public val bg: ShortArray = ShortArray(COLORS)

    /** Object colours. */
    public val obj: ShortArray = ShortArray(COLORS)

    /** All colours black. */
    public fun clear() {
        bg.fill(0); obj.fill(0)
    }

    /** Sizes. */
    public companion object {
        /** Colours per bank. */
        public const val COLORS: Int = 256
    }
}

/**
 * Colour effects and windows for the whole frame. Layer masks use bits 0..3 = BG0..BG3, bit 4 = OBJ,
 * bit 5 = backdrop (blend targets) or colour-effects-enabled (window masks).
 */
public class Effects {
    /** `BLDCNT` effect (bits 6..7): one of the `BLEND_*` constants. */
    public var blendMode: Int = BLEND_NONE

    /** First-target layer mask (`BLDCNT` bits 0..5). */
    public var blendTop: Int = 0

    /** Second-target layer mask (`BLDCNT` bits 8..13; used by alpha blending). */
    public var blendBottom: Int = 0

    /** `BLDALPHA` EVA: weight of the first target, sixteenths 0..16. */
    public var eva: Int = 0

    /** `BLDALPHA` EVB: weight of the second target, sixteenths 0..16. */
    public var evb: Int = 0

    /** `BLDY` EVY: brightness change, sixteenths 0..16. */
    public var evy: Int = 0

    /** Window 0 enabled (`DISPCNT_WIN0_ON`). */
    public var win0: Boolean = false

    /** Window 1 enabled (`DISPCNT_WIN1_ON`). */
    public var win1: Boolean = false

    /** OBJ window enabled (`DISPCNT_OBJWIN_ON`). */
    public var objWindow: Boolean = false

    /** Window bounds: index 0/1 = window 0/1; left edge (inclusive). */
    public val winLeft: IntArray = IntArray(2)

    /** Right edge (exclusive). */
    public val winRight: IntArray = IntArray(2)

    /** Top edge (inclusive). */
    public val winTop: IntArray = IntArray(2)

    /** Bottom edge (exclusive). */
    public val winBottom: IntArray = IntArray(2)

    /**
     * Layer masks inside window 0, window 1 and the OBJ window (`WININ` low/high byte, `WINOUT`
     * high byte).
     */
    public val winInside: IntArray = IntArray(3)

    /** Layer mask outside all windows (`WINOUT` low byte). */
    public var winOutside: Int = 0

    /**
     * Mosaic block sizes in pixels (1 = off): BG horizontal, BG vertical, OBJ horizontal, OBJ
     * vertical (`MOSAIC` register nibbles + 1). OBJ mosaic is not drawn by the renderers yet.
     */
    public val mosaic: IntArray = intArrayOf(1, 1, 1, 1)

    /** Everything off. */
    public fun reset() {
        blendMode = BLEND_NONE; blendTop = 0; blendBottom = 0; eva = 0; evb = 0; evy = 0
        win0 = false; win1 = false; objWindow = false
        winLeft.fill(0); winRight.fill(0); winTop.fill(0); winBottom.fill(0); winInside.fill(0); winOutside = 0
        mosaic.fill(1)
    }

    /** Blend modes and mask bits. */
    public companion object {
        /** No colour effect. */
        public const val BLEND_NONE: Int = 0

        /** Alpha blend first over second target. */
        public const val BLEND_ALPHA: Int = 1

        /** Brightness increase. */
        public const val BLEND_BRIGHTEN: Int = 2

        /** Brightness decrease. */
        public const val BLEND_DARKEN: Int = 3

        /** Mask bit for OBJ. */
        public const val OBJ: Int = 1 shl 4

        /** Mask bit for the backdrop / colour effects enable. */
        public const val BACKDROP: Int = 1 shl 5
    }
}

/**
 * Per-scanline overrides that replace the original's HBlank handlers (ADR 0008). For each line a bit mask
 * says which fields override the frame-wide state; palette writes are a bounded list applied before their
 * line is composed (and persist for the following lines, as hardware writes do).
 */
public class LineTable(
    /** Maximum palette writes per frame. */
    public val paletteWriteCapacity: Int = 512,
) {
    /** False = no overrides this frame (backends skip all per-line work). */
    public var active: Boolean = false

    /**
     * Per line: override bits ([BG_SCROLL] shl bg, [WIN0_X], [WIN1_X], [BLEND], [BLEND_CONTROL]).
     */
    public val mask: IntArray = IntArray(RenderState.LINES)

    /** Per BG × line: scroll x (text) or affine refX, index `bg * LINES + line`. */
    public val scrollX: IntArray = IntArray(RenderState.LAYERS * RenderState.LINES)

    /** Per BG × line: scroll y (text) or affine refY. */
    public val scrollY: IntArray = IntArray(RenderState.LAYERS * RenderState.LINES)

    /** Per window (0/1) × line: left edge, index `win * LINES + line`. */
    public val winLeft: IntArray = IntArray(2 * RenderState.LINES)

    /** Per window × line: right edge. */
    public val winRight: IntArray = IntArray(2 * RenderState.LINES)

    /** Per line: eva, evb, evy packed as `eva | evb shl 8 | evy shl 16`. */
    public val blend: IntArray = IntArray(RenderState.LINES)

    /** Palette writes in use. */
    public var paletteWrites: Int = 0
        private set

    /** Palette write: first line it takes effect on (written in the HBlank before it). */
    public val pwLine: ShortArray = ShortArray(paletteWriteCapacity)

    /** Palette write: index 0..255 = BG, 256..511 = OBJ. */
    public val pwIndex: ShortArray = ShortArray(paletteWriteCapacity)

    /** Palette write: BGR555 colour. */
    public val pwColor: ShortArray = ShortArray(paletteWriteCapacity)

    /** Overrides BG [bg] scroll on [line]. */
    public fun setScroll(line: Int, bg: Int, x: Int, y: Int) {
        active = true
        mask[line] = mask[line] or (BG_SCROLL shl bg)
        scrollX[bg * RenderState.LINES + line] = x
        scrollY[bg * RenderState.LINES + line] = y
    }

    /** Overrides window [win] (0/1) horizontal bounds on [line]. */
    public fun setWindowX(line: Int, win: Int, left: Int, right: Int) {
        active = true
        mask[line] = mask[line] or (if (win == 0) WIN0_X else WIN1_X)
        winLeft[win * RenderState.LINES + line] = left
        winRight[win * RenderState.LINES + line] = right
    }

    /** Overrides blend coefficients on [line]. */
    public fun setBlend(line: Int, eva: Int, evb: Int, evy: Int) {
        active = true
        mask[line] = mask[line] or BLEND
        blend[line] = eva or (evb shl 8) or (evy shl 16)
    }

    /** `BLDCNT` per line (valid where [mask] has [BLEND_CONTROL]). */
    public val blendControl: IntArray = IntArray(RenderState.LINES)

    /** Overrides the blend mode and targets on [line] with the `BLDCNT` value [bldcnt]. */
    public fun setBlendControl(line: Int, bldcnt: Int) {
        active = true
        mask[line] = mask[line] or BLEND_CONTROL
        blendControl[line] = bldcnt and 0x3FFF
    }

    /** Queues a palette write before [line]; false if the list is full. Writes must be added in line order. */
    public fun writePalette(line: Int, index: Int, color: Int): Boolean {
        if (paletteWrites == paletteWriteCapacity) return false
        require(paletteWrites == 0 || pwLine[paletteWrites - 1] <= line) { "palette writes out of line order" }
        active = true
        val writeIndex = paletteWrites++
        pwLine[writeIndex] = line.toShort()
        pwIndex[writeIndex] = index.toShort()
        pwColor[writeIndex] = color.toShort()
        return true
    }

    /** Removes all overrides. */
    public fun clear() {
        if (!active) return
        active = false
        mask.fill(0)
        paletteWrites = 0
    }

    /** Mask bits. */
    public companion object {
        /** BG scroll override; shift left by the BG number (bits 0..3). */
        public const val BG_SCROLL: Int = 1

        /** Window 0 horizontal bounds override. */
        public const val WIN0_X: Int = 1 shl 4

        /** Window 1 horizontal bounds override. */
        public const val WIN1_X: Int = 1 shl 5

        /** Blend coefficients override. */
        public const val BLEND: Int = 1 shl 6

        /** Blend control (`BLDCNT`: mode and targets) override. */
        public const val BLEND_CONTROL: Int = 1 shl 7
    }
}
