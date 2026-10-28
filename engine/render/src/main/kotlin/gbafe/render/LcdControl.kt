package gbafe.render

/**
 * The game's shadow copy of the LCD I/O registers: a port of `struct LCDControlBuffer` (`include/hardware.h`,
 * 0x6C bytes; FE8 keeps it at IWRAM 0x03003080 as `gLCDControlBuffer`). Game code edits these fields during
 * a frame and [flushTo] copies them to the display registers once per VBlank, exactly as the original does.
 *
 * Bitfield structs are kept as their raw register words (same bit layout as the hardware register), e.g.
 * [dispcnt] bit 8 = BG0 on. Byte-identical with the original via [readFrom] / [flushTo]
 * (checked against mGBA RAM + I/O dumps by `LcdControlDifferentialTest`).
 */
public class LcdControl {
    /** `dispcnt` (struct DispCnt): `DISPCNT` word. */
    public var dispcnt: Int = 0

    /** `dispstat` (struct DispStat): `DISPSTAT` word (bits 0..2 are status flags the hardware owns). */
    public var dispstat: Int = 0

    /** `bg0cnt`..`bg3cnt` (struct BgCnt): `BGxCNT` words. */
    public val bgCnt: IntArray = IntArray(4)

    /** `bgoffset[n].x`: `BGnHOFS`. */
    public val bgHofs: IntArray = IntArray(4)

    /** `bgoffset[n].y`: `BGnVOFS`. */
    public val bgVofs: IntArray = IntArray(4)

    /** `win0_right, win0_left, win1_right, win1_left` (u8 each): low/high bytes of `WIN0H` / `WIN1H`. */
    public val winH: IntArray = IntArray(4)

    /** `win0_bottom, win0_top, win1_bottom, win1_top` (u8 each): low/high bytes of `WIN0V` / `WIN1V`. */
    public val winV: IntArray = IntArray(4)

    /** `wincnt` (struct WinCnt, 4 bytes): `WININ` in the low half, `WINOUT` in the high half. */
    public var wincnt: Int = 0

    /** `mosaic`: `MOSAIC` word. */
    public var mosaic: Int = 0

    /** `bldcnt` (struct BlendCnt, first u16): `BLDCNT` word. */
    public var bldcnt: Int = 0

    /** `blendCoeffA` (u8): EVA, low byte of `BLDALPHA`. */
    public var blendCoeffA: Int = 0

    /** `blendCoeffB` (u8): EVB, high byte of `BLDALPHA`. */
    public var blendCoeffB: Int = 0

    /** `blendY` (u8): `BLDY`. */
    public var blendY: Int = 0

    /** `bg2affin` / `bg3affin` (struct BgAffineDstData): for BG2 then BG3, `pa, pb, pc, pd` (s16), `dx, dy` (s32). */
    public val affine: IntArray = IntArray(12)

    /** `colorAddition` (s8): not a register; used by the palette colour-addition helpers. */
    public var colorAddition: Int = 0

    /**
     * Sets BG [bg]'s scroll offset (`bgoffset[bg]`, u16 each); other [bg] values are ignored.
     *
     * Ported from src/hardware.c: BG_SetPosition
     */
    public fun setBgPosition(bg: Int, x: Int, y: Int) {
        if (bg !in 0..3) return
        bgHofs[bg] = x and 0xFFFF
        bgVofs[bg] = y and 0xFFFF
    }

    /**
     * Sets the colour effect (`bldcnt.effect`, 0 none / 1 alpha / 2 brighten / 3 darken) and the EVA, EVB and
     * EVY coefficients (u8 each).
     *
     * Ported from src/hardware.c: SetBlendConfig
     */
    public fun setBlendConfig(effect: Int, coeffA: Int, coeffB: Int, blendY: Int) {
        bldcnt = (bldcnt and 0xC0.inv()) or ((effect and 3) shl 6)
        blendCoeffA = coeffA and 0xFF
        blendCoeffB = coeffB and 0xFF
        this.blendY = blendY and 0xFF
    }

    /** First-target layers (BLDCNT bits 0..4) of `SetBlendTargetA` (src/hardware.c; not yet verified). */
    public fun setBlendTargetA(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        bldcnt = (bldcnt and 0x1F.inv()) or targets(bg0, bg1, bg2, bg3, obj)
    }

    /** Second-target layers (BLDCNT bits 8..12) of `SetBlendTargetB` (src/hardware.c; not yet verified). */
    public fun setBlendTargetB(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        bldcnt = (bldcnt and 0x1F00.inv()) or (targets(bg0, bg1, bg2, bg3, obj) shl 8)
    }

    /** The `SetDispEnable` macro (include/hardware.h): DISPCNT BG0..BG3 and OBJ enable bits (8..12). */
    public fun setDispEnable(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        dispcnt = (dispcnt and 0x1F00.inv()) or (targets(bg0, bg1, bg2, bg3, obj) shl 8)
    }

    /** The `SetWinEnable` macro (include/hardware.h): DISPCNT WIN0 / WIN1 / OBJ window bits. */
    public fun setWinEnable(win0: Int, win1: Int, objWin: Int) {
        val bits = ((win0 and 1) shl 13) or ((win1 and 1) shl 14) or ((objWin and 1) shl 15)
        dispcnt = (dispcnt and 0xE000.inv()) or bits
    }

    /** The `SetWin0Box` macro (include/hardware.h): `win0_left/top/right/bottom` (u8 each). */
    public fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {
        winH[1] = left and 0xFF
        winV[1] = top and 0xFF
        winH[0] = right and 0xFF
        winV[0] = bottom and 0xFF
    }

    /** The `SetWin0Layers` macro (include/hardware.h): `wincnt.win0_enableBg0..Obj` (bits 0..4). */
    public fun setWin0Layers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        setWinLayers(0, bg0, bg1, bg2, bg3, obj)
    }

    /** The `SetWin1Layers` macro (include/hardware.h): `wincnt.win1_enableBg0..Obj` (bits 8..12). */
    public fun setWin1Layers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        setWinLayers(8, bg0, bg1, bg2, bg3, obj)
    }

    /** The `SetWOutLayers` macro (include/hardware.h): `wincnt.wout_enableBg0..Obj` (bits 16..20). */
    public fun setWOutLayers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        setWinLayers(16, bg0, bg1, bg2, bg3, obj)
    }

    /**
     * Sets or clears a `wincnt` bit: 5 `win0_enableBlend`, 13 `win1_enableBlend`, 21
     * `wout_enableBlend`, 29 `wobj_enableBlend` (or any other `wincnt` bit).
     */
    public fun setWincntBit(bit: Int, on: Boolean) {
        wincnt = if (on) wincnt or (1 shl bit) else wincnt and (1 shl bit).inv()
    }

    private fun setWinLayers(shift: Int, bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
        wincnt = (wincnt and (0x1F shl shift).inv()) or (targets(bg0, bg1, bg2, bg3, obj) shl shift)
    }

    private fun targets(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int): Int =
        (bg0 and 1) or ((bg1 and 1) shl 1) or ((bg2 and 1) shl 2) or ((bg3 and 1) shl 3) or ((obj and 1) shl 4)

    /** Loads every field from a 0x6C-byte `struct LCDControlBuffer` image in [src] at [offset]. */
    public fun readFrom(src: ByteArray, offset: Int = 0) {
        fun u8(i: Int) = src[offset + i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun s16(i: Int) = u16(i).toShort().toInt()
        fun s32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        dispcnt = u16(0x00)
        dispstat = u16(0x04)
        for (n in 0 until 4) {
            bgCnt[n] = u16(0x0C + 4 * n)
            bgHofs[n] = u16(0x1C + 4 * n)
            bgVofs[n] = u16(0x1E + 4 * n)
            winH[n] = u8(0x2C + n)
            winV[n] = u8(0x30 + n)
        }
        wincnt = s32(0x34)
        mosaic = u16(0x38)
        bldcnt = u16(0x3C)
        blendCoeffA = u8(0x44)
        blendCoeffB = u8(0x45)
        blendY = u8(0x46)
        for (b in 0 until 2) {
            val o = 0x48 + 0x10 * b
            for (k in 0 until 4) affine[6 * b + k] = s16(o + 2 * k)
            affine[6 * b + 4] = s32(o + 8)
            affine[6 * b + 5] = s32(o + 12)
        }
        colorAddition = src[offset + 0x68].toInt()
    }

    /**
     * Copies the shadow registers to the display registers: [io] is the I/O register block (offset 0 =
     * 0x04000000). Writes `DISPCNT`, `DISPSTAT`, `BG0..3CNT`, `BG0..3HOFS/VOFS`, `WIN0H/WIN1H`, `WIN0V/WIN1V`,
     * `WININ/WINOUT`, `MOSAIC`, `BLDCNT`, `BLDALPHA`, `BLDY` (one byte) and the BG2/BG3 affine registers,
     * with the original's access widths.
     *
     * Ported from src/hardware.c: FlushLCDControl
     */
    public fun flushTo(io: ByteArray) {
        put16(io, 0x00, dispcnt)
        put16(io, 0x04, dispstat)
        for (n in 0 until 4) {
            put16(io, 0x08 + 2 * n, bgCnt[n])
            put16(io, 0x10 + 4 * n, bgHofs[n])
            put16(io, 0x12 + 4 * n, bgVofs[n])
            io[0x40 + n] = winH[n].toByte()
            io[0x44 + n] = winV[n].toByte()
        }
        put16(io, 0x48, wincnt)
        put16(io, 0x4A, wincnt ushr 16)
        put16(io, 0x4C, mosaic)
        put16(io, 0x50, bldcnt)
        io[0x52] = blendCoeffA.toByte()
        io[0x53] = blendCoeffB.toByte()
        io[0x54] = blendY.toByte()
        for (b in 0 until 2) {
            val o = 0x20 + 0x10 * b
            for (k in 0 until 4) put16(io, o + 2 * k, affine[6 * b + k])
            put16(io, o + 8, affine[6 * b + 4]); put16(io, o + 10, affine[6 * b + 4] ushr 16)
            put16(io, o + 12, affine[6 * b + 5]); put16(io, o + 14, affine[6 * b + 5] ushr 16)
        }
    }

    /** Inverse of [flushTo]: loads the register fields from an I/O register block (offset 0 = 0x04000000).
     * [colorAddition] is not a register and is left unchanged. */
    public fun readRegisters(io: ByteArray) {
        fun u16(i: Int) = (io[i].toInt() and 0xFF) or ((io[i + 1].toInt() and 0xFF) shl 8)
        dispcnt = u16(0x00)
        dispstat = u16(0x04)
        for (n in 0 until 4) {
            bgCnt[n] = u16(0x08 + 2 * n)
            bgHofs[n] = u16(0x10 + 4 * n)
            bgVofs[n] = u16(0x12 + 4 * n)
            winH[n] = io[0x40 + n].toInt() and 0xFF
            winV[n] = io[0x44 + n].toInt() and 0xFF
        }
        wincnt = u16(0x48) or (u16(0x4A) shl 16)
        mosaic = u16(0x4C)
        bldcnt = u16(0x50)
        blendCoeffA = io[0x52].toInt() and 0xFF
        blendCoeffB = io[0x53].toInt() and 0xFF
        blendY = io[0x54].toInt() and 0xFF
        for (b in 0 until 2) {
            val o = 0x20 + 0x10 * b
            for (k in 0 until 4) affine[6 * b + k] = u16(o + 2 * k).toShort().toInt()
            affine[6 * b + 4] = u16(o + 8) or (u16(o + 10) shl 16)
            affine[6 * b + 5] = u16(o + 12) or (u16(o + 14) shl 16)
        }
    }

    /**
     * Decodes the registers into the semantic render state, as the PPU interprets them: per-BG enable (by
     * video mode), priority, character block (= [TileLayer.sheet]), mosaic, colour mode, map size, wrap,
     * scroll, affine mode/matrix; windows, blending, mosaic sizes; OBJ enable/mapping into
     * [SpriteBatch.mapping1d]. Map entries, palettes and sprites are not registers and are left untouched.
     * Returns false when `DISPCNT` forced blank is set (the screen shows white). Allocation-free.
     */
    public fun applyTo(state: RenderState): Boolean {
        val mode = dispcnt and 7
        for (bg in 0 until RenderState.LAYERS) {
            val l = state.layers[bg]
            val cnt = bgCnt[bg]
            l.enabled = dispcnt and (0x100 shl bg) != 0 &&
                when (mode) { 0 -> true; 1 -> bg < 3; 2 -> bg >= 2; else -> false }
            l.affine = mode == 2 || (mode == 1 && bg == 2)
            l.priority = cnt and 3
            l.sheet = (cnt shr 2) and 3
            l.mosaic = cnt and 0x40 != 0
            l.bpp8 = cnt and 0x80 != 0 || l.affine
            l.wrap = cnt and 0x2000 != 0
            val size = (cnt shr 14) and 3
            if (l.affine) {
                l.widthTiles = 16 shl size
                l.heightTiles = 16 shl size
                val o = 6 * (bg - 2)
                l.pa = affine[o]; l.pb = affine[o + 1]; l.pc = affine[o + 2]; l.pd = affine[o + 3]
                l.refX = (affine[o + 4] shl 4) shr 4 // 28-bit signed 20.8
                l.refY = (affine[o + 5] shl 4) shr 4
            } else {
                l.widthTiles = if (size and 1 != 0) 64 else 32
                l.heightTiles = if (size and 2 != 0) 64 else 32
            }
            l.scrollX = bgHofs[bg] and 0x1FF
            l.scrollY = bgVofs[bg] and 0x1FF
        }
        val fx = state.effects
        fx.win0 = dispcnt and 0x2000 != 0
        fx.win1 = dispcnt and 0x4000 != 0
        fx.objWindow = dispcnt and 0x8000 != 0
        for (w in 0..1) {
            fx.winRight[w] = winH[2 * w]; fx.winLeft[w] = winH[2 * w + 1]
            fx.winBottom[w] = winV[2 * w]; fx.winTop[w] = winV[2 * w + 1]
        }
        fx.winInside[0] = wincnt and 0x3F
        fx.winInside[1] = (wincnt shr 8) and 0x3F
        fx.winOutside = (wincnt shr 16) and 0x3F
        fx.winInside[2] = (wincnt shr 24) and 0x3F
        for (i in 0 until 4) fx.mosaic[i] = ((mosaic shr (4 * i)) and 0xF) + 1
        fx.blendTop = bldcnt and 0x3F
        fx.blendMode = (bldcnt shr 6) and 3 // hardware numbering = Effects.BLEND_*
        fx.blendBottom = (bldcnt shr 8) and 0x3F
        fx.eva = blendCoeffA and 0x1F
        fx.evb = blendCoeffB and 0x1F
        fx.evy = blendY and 0x1F
        state.sprites.mapping1d = dispcnt and 0x40 != 0
        return dispcnt and 0x80 == 0
    }

    /** True if `DISPCNT` enables sprites. */
    public val objEnabled: Boolean get() = dispcnt and 0x1000 != 0

    private fun put16(io: ByteArray, at: Int, v: Int) {
        io[at] = v.toByte()
        io[at + 1] = (v ushr 8).toByte()
    }

    /** Struct size. */
    public companion object {
        /** Size of `struct LCDControlBuffer` in bytes. */
        public const val STRUCT_SIZE: Int = 0x6C
    }
}
