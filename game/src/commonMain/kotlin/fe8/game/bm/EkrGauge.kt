package fe8.game.bm

/**
 * Control state and lifecycle of `ProcScr_ekrGauge` (`src/banim-ekrgauge.c`): the battle HP gauge
 * and side stat panel proc (`gpProcEkrGauge`), covering setup, position/flag helpers, per-frame
 * [ekrGaugeMain] control/dispatch, and teardown.
 *
 * Unused hide/enable setters (`EkrGauge_Set4C50`, `EkrGauge_Set4C`, `EkrGauge_Set50`,
 * `EnableEkrGauge`) are omitted until a capture exercises them.
 *
 * @property env globals read by [newEkrGauge] and [ekrGaugeMain].
 * @property hooks VRAM, BG0, palette, digit tile, OAM, and icon side effects.
 */
public class EkrGauge(
    private val env: Env,
    private val hooks: Hooks = object : Hooks {},
) {
    /** Globals read by [newEkrGauge] and [ekrGaugeMain]. */
    public interface Env {
        /** `gEkrBg0QuakeVec.x` (s16). */
        public val quakeX: Int

        /** `gEkrBg0QuakeVec.y` (s16). */
        public val quakeY: Int

        /** `gEkrGaugeHp[2]` (s16). */
        public val gaugeHp: IntArray

        /** `gBanimFactionPal[2]` (s16). */
        public val factionPal: IntArray

        /** `gEkrGaugeHit[2]` (s16). */
        public val hit: IntArray

        /** `gEkrGaugeDmg[2]` (s16). */
        public val dmg: IntArray

        /** `gEkrGaugeCrt[2]` (s16). */
        public val crt: IntArray

        /** `gpEkrBattleUnitLeft->weaponBefore` (u16). */
        public val weaponBeforeLeft: Int

        /** `gpEkrBattleUnitRight->weaponBefore` (u16). */
        public val weaponBeforeRight: Int

        /** `GetGameClock()` (`gGameClock`, frames). */
        public val gameClock: Int

        /** `gEkrDistanceType` (`EKR_DISTANCE_*`). */
        public val distanceType: Int

        /** `gBanimMaxHP[2]` (s16). */
        public val maxHp: IntArray

        /** `gBanimWtaBonus[2]` (s16). */
        public val wtaBonus: IntArray

        /** `gEkrHitNow[2]` (s16), read by `CheckEkrHitNow`. */
        public val hitNow: IntArray

        /** `gBanimValid[2]`, read for `EKR_DISTANCE_MONOCOMBAT`. */
        public val banimValid: BooleanArray get() = booleanArrayOf(true, true)
    }

    /** Side effects of [newEkrGauge] and [ekrGaugeMain] (BG0, VRAM, palette, OAM, icons). */
    public interface Hooks {
        /**
         * Copies the HP bar palette for [side] (0 left -> `PAL_OBJ(0xB)`, 1 right ->
         * `PAL_OBJ(0xC)`): `gPalEfxHpBarPurple` when [purple] (`gEkrGaugeHp[side] > 0x50`), else
         * `gUnknown_08802B04 + factionPal * 0x10`.
         */
        public fun copyHpBarPalette(side: Int, purple: Boolean, factionPal: Int) {}

        /**
         * `LZ77UnCompVram` of `Img_EfxSideHitDmgCrit` (`0x6013800`), `Img_EfxWTAArrow1`
         * (`0x6013940`), and `Img_EfxWTAArrow2` (`0x6013D40`).
         */
        public fun lz77UnCompSideGfx() {}

        /**
         * Copies the side gauge faction palette for [side] (0 left -> `PAL_OBJ(0x5)`, 1 right ->
         * `PAL_OBJ(0x6)`) from `gUnknown_08802884 + factionPal * 0x10`.
         */
        public fun copySideFactionPalette(side: Int, factionPal: Int) {}

        /** `EnablePaletteSync()`. */
        public fun enablePaletteSync() {}

        /**
         * Decodes hit/dmg/crit via `ModDec` into `gEkrGaugeDecoder`, fills and builds
         * `gObjBuf_EkrSideHitDmgCrit`, and registers DMA transfers to OBJ VRAM.
         */
        public fun modDecAndRegisterHitDmgCrit(
            hit0: Int,
            dmg0: Int,
            crt0: Int,
            hit1: Int,
            dmg1: Int,
            crt1: Int,
        ) {}

        /** `ResetIconGraphics_()`. */
        public fun resetIconGraphics() {}

        /** `LoadIconPalette(palSlot, palBank)`. */
        public fun loadIconPalette(palSlot: Int, palBank: Int) {}

        /**
         * `LoadIconObjectGraphics(GetItemIconId(weaponId), oamTile)`, where [weaponId] is the
         * unit's `weaponBefore` item word.
         */
        public fun loadIconObjectGraphics(weaponId: Int, oamTile: Int) {}

        /** `ApplyPalette(gPal_MiscUiGraphics, palOffset)`. */
        public fun applyMiscUiPalette(palOffset: Int) {}

        /**
         * `FillBGRect(gBG0TilemapBuffer + tileOffset, width, height, tile, chr)` during the
         * `battleInit == 0` BG0 panel redraw.
         */
        public fun fillBgRect(tileOffset: Int, width: Int, height: Int, tile: Int, chr: Int) {}

        /**
         * Left BG0 panel copy (`EfxTmCpyBG` + `sub_8070D04`, width 15, [height], palette 2,
         * chr 0x80) from `gUnknown_08802274 + srcOffset` to `[rowOffset][colOffset]`.
         */
        public fun copyBgPanelLeft(rowOffset: Int, colOffset: Int, height: Int, srcOffset: Int) {}

        /**
         * Right BG0 panel copy (`EfxTmCpyBG` + `sub_8070D04`, width 16, [height], palette 3,
         * chr 0x80) from `gUnknown_08802428` when [leftHidden] else `gUnknown_08802348` plus
         * [srcOffset] to `gBG0TilemapBuffer2D[rowOffset][colOffset]`.
         */
        public fun copyBgPanelRight(
            rowOffset: Int,
            colOffset: Int,
            height: Int,
            srcOffset: Int,
            leftHidden: Boolean,
        ) {}

        /** `BG_EnableSyncByMask(BG0_SYNC_BIT)`. */
        public fun enableBg0Sync() {}

        /**
         * Copies decoded HP digits (`local_d0[0..3]`) from `gUnknown_088026E4` into
         * `gUnk_Banim_02016DC8` and registers DMA transfers to `0x060139C0` and `0x06013DC0`.
         */
        public fun updateHpDigits(d0: Int, d1: Int, d2: Int, d3: Int) {}

        /**
         * Displays the gauge side arrow (`gUnknown_085B940C`, with `BanimUpdateSpriteRotScale`
         * when [rotScale] is true and `CheckEkrHitNow(side) == 1`) via `AnimDisplay`.
         */
        public fun displayArrowAnim(
            side: Int,
            x: Int,
            y: Int,
            oam2Base: Int,
            oamBase: Int,
            rotScale: Boolean,
        ) {}

        /**
         * Computes HP bar tile indices via `sub_8071068` and copies tile graphics via
         * `sub_8050E40` into the side/row EWRAM buffer (`gUnk_Banim_02016E48`..`2017448`).
         */
        public fun updateHpBarTiles(side: Int, upperRow: Boolean, barHp: Int, barMax: Int) {}

        /** Displays one HP bar row sprite (`gUnknown_085B93D0`) via `AnimDisplay`. */
        public fun displayHpBar(side: Int, upperRow: Boolean, x: Int, y: Int, oam2Base: Int) {}

        /** `RegisterDataMove(gUnk_Banim_02016E48, (void *)0x06013000, 0x800)`. */
        public fun registerHpBarDataMove() {}

        /**
         * Displays static side panel sprites (`part == 0`: `gUnknown_085B9424`; `part == 1`:
         * `gUnknown_085B949C` on left, `gUnknown_085B94F0` on right) via `AnimDisplay`.
         */
        public fun displayStaticPanelSprites(
            side: Int,
            part: Int,
            x: Int,
            y: Int,
            oam2Base: Int,
        ) {}

        /**
         * Selects the animated WTA arrow sprite via `sub_8051238(anim, wtaBonus, clk)` and
         * displays it via `AnimDisplay`.
         */
        public fun displayWtaArrow(
            side: Int,
            x: Int,
            y: Int,
            oam2Base: Int,
            wtaBonus: Int,
            clk: Int,
        ) {}

        /** Displays the weapon icon sprite (`gUnknown_085B9544`) via `AnimDisplay`. */
        public fun displayWeaponIcon(side: Int, x: Int, y: Int, oam2Base: Int) {}
    }

    /** Whether `gpProcEkrGauge` is currently running (`Proc_Start` .. `EndEkrGauge`). */
    public var active: Boolean = false

    /** `ProcEkrGauge.battle_init` (offset +0x29, 0 or 1). */
    public var battleInit: Int = 0

    /** `ProcEkrGauge.valid` (offset +0x2A, 0 or 1). */
    public var valid: Int = 0

    /** `ProcEkrGauge.unk32` (`x`, s16, offset +0x32). */
    public var unk32: Int = 0

    /** `ProcEkrGauge.unk3A` (`y`, s16, offset +0x3A). */
    public var unk3A: Int = 0

    /** `ProcEkrGauge.unk44` (u16 OAM priority shift, offset +0x44). */
    public var unk44: Int = 0

    /** `ProcEkrGauge.unk4C` (hide left flag, offset +0x4C). */
    public var unk4C: Int = 0

    /** `ProcEkrGauge.unk50` (hide right flag, offset +0x50). */
    public var unk50: Int = 0

    /** `gEkrGaugeHpBak[2]` (s16): previous HP values rendered by the gauge. */
    public val hpBak: IntArray = IntArray(2)

    /**
     * Partially ported from src/banim-ekrgauge.c: NewEkrGauge
     *
     * Starts `ProcScr_ekrGauge`, initializes control fields (`setup44(0)`, `clr4C50()`,
     * `disableEkrGauge()`, `clrInitFlag()`, `clr323A(quakeX, quakeY)`), sets `hpBak` to
     * `(-1, -1)`, and runs palette/VRAM/digit/icon [Hooks]. Only the `gaugeHp[side] <= 0x50`
     * palette arm is verified in the prologue capture; the `> 0x50` purple-bar arm is
     * unverified.
     */
    public fun newEkrGauge() {
        active = true
        setup44(0)
        clr4C50()
        disableEkrGauge()
        clrInitFlag()
        clr323A(env.quakeX, env.quakeY)

        hooks.copyHpBarPalette(0, env.gaugeHp[0] > HP_PURPLE_THRESHOLD, env.factionPal[0])
        hooks.copyHpBarPalette(1, env.gaugeHp[1] > HP_PURPLE_THRESHOLD, env.factionPal[1])

        hpBak[0] = -1
        hpBak[1] = -1

        hooks.lz77UnCompSideGfx()
        hooks.copySideFactionPalette(0, env.factionPal[0])
        hooks.copySideFactionPalette(1, env.factionPal[1])
        hooks.enablePaletteSync()

        hooks.modDecAndRegisterHitDmgCrit(
            env.hit[0],
            env.dmg[0],
            env.crt[0],
            env.hit[1],
            env.dmg[1],
            env.crt[1],
        )

        hooks.resetIconGraphics()
        hooks.loadIconPalette(0, ICON_PAL_BANK_LEFT)
        hooks.loadIconPalette(0, ICON_PAL_BANK_RIGHT)
        hooks.loadIconObjectGraphics(env.weaponBeforeLeft, ICON_TILE_LEFT)
        hooks.loadIconObjectGraphics(env.weaponBeforeRight, ICON_TILE_RIGHT)
        hooks.applyMiscUiPalette(MISC_UI_PAL_OFFSET)
    }

    /**
     * Partially ported from src/banim-ekrgauge.c: ekrGaugeMain
     *
     * Runs one frame of `ProcScr_ekrGauge`: redraws BG0 panels while `battleInit == 0`, tracks
     * `hpBak` changes to emit digit and HP-bar DMA updates, computes gauge `(x, y)`, and dispatches
     * arrow (normal vs `CheckEkrHitNow` rotate-scale), HP bar, static label/frame, WTA bonus
     * arrow, and weapon icon sprites through [Hooks].
     *
     * Unverified arms (never reached in `ram_trace_prologue_attack`): `valid == 1` early return,
     * `unk4C`/`unk50` non-zero hide flags, `gaugeHp > 0x50` (`0xC` digit cap), `maxHp > 0x28`
     * second HP bar row (`upperMax != 0`), `distanceType` `FAR`/`FARFAR`/`MONOCOMBAT`/`PROMOTION`
     * offsets, and `wtaBonus == 0` arrow skip.
     */
    public fun ekrGaugeMain() {
        val clk = (env.gameClock / CLOCK_FRAME_DIVISOR) % CLOCK_FRAME_COUNT
        if (valid == 1) return

        if (battleInit == 0) {
            redrawBg0Panels()
        }

        val hp0 = env.gaugeHp[0].toShort().toInt()
        val hp1 = env.gaugeHp[1].toShort().toInt()
        val hpChanged = hpBak[0] != hp0 || hpBak[1] != hp1
        hpBak[0] = hp0
        hpBak[1] = hp1

        val x = resolveGaugeX()
        val y = if (battleInit == 0) unk3A and TILE_ALIGN_MASK else unk3A

        val (d0, d1) = decodeHpDigits(hp0)
        val (d2, d3) = decodeHpDigits(hp1)
        if (hpChanged) {
            hooks.updateHpDigits(d0, d1, d2, d3)
        }

        renderSideArrow(0, unk4C != 0, x, y)
        renderSideArrow(1, unk50 != 0, x, y)

        renderSideHpBar(0, unk4C != 0, x, y, hpChanged)
        renderSideHpBar(1, unk50 != 0, x, y, hpChanged)
        if (hpChanged) {
            hooks.registerHpBarDataMove()
        }

        renderSideStaticSprites(0, unk4C != 0, x, y)
        renderSideStaticSprites(1, unk50 != 0, x, y)

        renderSideWtaAndIcon(0, unk4C != 0, x, y, clk)
        renderSideWtaAndIcon(1, unk50 != 0, x, y, clk)
    }

    /** Ported from src/banim-ekrgauge.c: EndEkrGauge */
    public fun endEkrGauge() {
        active = false
    }

    /** Ported from src/banim-ekrgauge.c: EkrGauge_Clr4C50 */
    public fun clr4C50() {
        unk4C = 0
        unk50 = 0
    }

    /** Ported from src/banim-ekrgauge.c: EkrGauge_Setup44 */
    public fun setup44(value: Int) {
        unk44 = (value * 0x400) and 0xFFFF
    }

    /**
     * Ported from src/banim-ekrgauge.c: EkrGauge_Clr323A
     *
     * Sign-extends [x] and [y] as `s16` and clears [battleInit] (only `0..57` inputs appear in the
     * prologue capture; negative/overflow `s16` inputs are not exercised).
     */
    public fun clr323A(x: Int, y: Int) {
        unk32 = x.toShort().toInt()
        unk3A = y.toShort().toInt()
        battleInit = 0
    }

    /**
     * Ported from src/banim-ekrgauge.c: EkrGauge_Setxy323A
     *
     * Sign-extends [x] and [y] as `s16` and sets [battleInit] to `1`.
     */
    public fun setxy323A(x: Int, y: Int) {
        unk32 = x.toShort().toInt()
        unk3A = y.toShort().toInt()
        battleInit = 1
    }

    /** Ported from src/banim-ekrgauge.c: EkrGauge_SetInitFlag */
    public fun setInitFlag() {
        battleInit = 1
    }

    /** Ported from src/banim-ekrgauge.c: EkrGauge_ClrInitFlag */
    public fun clrInitFlag() {
        battleInit = 0
    }

    /** Ported from src/banim-ekrgauge.c: DisableEkrGauge */
    public fun disableEkrGauge() {
        valid = 0
    }

    private fun checkEkrHitNow(pos: Int): Int = env.hitNow[pos]

    private fun redrawBg0Panels() {
        val tileY = unk3A shr 3
        val rowOffset = maxOf(0, (tileY shl 5) + BG0_GAUGE_OFFSET)
        val height = minOf(BG0_PANEL_MAX_HEIGHT, tileY + BG0_PANEL_MAX_HEIGHT)
        val srcOffset = (BG0_PANEL_MAX_HEIGHT - height) * BG0_GAUGE_WIDTH
        val (leftCol, rightCol) = when (env.distanceType) {
            DISTANCE_CLOSE, DISTANCE_FAR, DISTANCE_FARFAR -> 0 to BG0_RIGHT_COL_CLOSE
            else -> BG0_COL_CENTERED to BG0_COL_CENTERED
        }

        hooks.fillBgRect(BG0_GAUGE_OFFSET, BG0_GAUGE_WIDTH, BG0_GAUGE_HEIGHT, 0, BG0_CHR_BASE)
        if (unk4C == 0) {
            hooks.copyBgPanelLeft(rowOffset, leftCol, height, srcOffset)
        }
        if (unk50 == 0) {
            hooks.copyBgPanelRight(rowOffset, rightCol, height, srcOffset, unk4C != 0)
        }
        hooks.enableBg0Sync()
    }

    private fun resolveGaugeX(): Int = when (env.distanceType) {
        DISTANCE_MONOCOMBAT -> {
            if (env.banimValid[0]) unk32 + MONO_X_OFFSET else unk32 - MONO_X_OFFSET
        }
        DISTANCE_CLOSE, DISTANCE_FAR, DISTANCE_FARFAR -> unk32
        else -> unk32 - MONO_X_OFFSET
    }

    private fun decodeHpDigits(hp: Int): Pair<Int, Int> {
        if (hp > HP_PURPLE_THRESHOLD) return DIGIT_OVER_THRESHOLD to DIGIT_OVER_THRESHOLD
        val tens = hp / 10
        val ones = hp - tens * 10
        return (if (tens == 0) DIGIT_BLANK else tens) to ones
    }

    private fun renderSideArrow(side: Int, hidden: Boolean, x: Int, y: Int) {
        if (hidden) return
        val baseOam2 = if (side == 0) OAM2_ARROW_LEFT else OAM2_ARROW_RIGHT
        var drawX = x + if (side == 0) ARROW_X_LEFT else ARROW_X_RIGHT
        var drawY = y + ARROW_Y_OFFSET
        val hit = checkEkrHitNow(side) == 1
        if (hit) {
            drawX -= HIT_NOW_OFFSET
            drawY -= HIT_NOW_OFFSET
        }
        hooks.displayArrowAnim(
            side = side,
            x = drawX,
            y = drawY,
            oam2Base = baseOam2 or unk44,
            oamBase = if (hit) HIT_NOW_OAM_BASE else 0,
            rotScale = hit,
        )
    }

    private fun renderSideHpBar(side: Int, hidden: Boolean, x: Int, y: Int, hpChanged: Boolean) {
        if (hidden) return
        val hp = env.gaugeHp[side].toShort().toInt()
        val max = env.maxHp[side].toShort().toInt()
        val upperHp = (hp - HP_BAR_ROW_MAX).coerceIn(0, HP_BAR_ROW_MAX)
        val upperMax = (max - HP_BAR_ROW_MAX).coerceIn(0, HP_BAR_ROW_MAX)
        val lowerHp = minOf(hp, HP_BAR_ROW_MAX)
        val lowerMax = minOf(max, HP_BAR_ROW_MAX)
        val drawX = x + if (side == 0) HP_BAR_X_LEFT else HP_BAR_X_RIGHT
        val oam2Hi = ((if (side == 0) OAM2_PAL_LEFT else OAM2_PAL_RIGHT) or unk44) and OAM2_HI_MASK

        if (upperMax != 0) {
            if (hpChanged) {
                hooks.updateHpBarTiles(side, true, upperHp, upperMax)
            }
            val tileOff = if (side == 0) 0 else HP_BAR_TILE_R_UPPER
            hooks.displayHpBar(side, true, drawX, y + HP_BAR_Y_UPPER, oam2Hi or tileOff)
        }

        if (hpChanged) {
            hooks.updateHpBarTiles(side, false, lowerHp, lowerMax)
        }
        val drawY = y + if (upperMax != 0) HP_BAR_Y_LOWER_TWO else HP_BAR_Y_LOWER_ONE
        val tileOff = if (side == 0) HP_BAR_TILE_L_LOWER else HP_BAR_TILE_R_LOWER
        hooks.displayHpBar(side, false, drawX, drawY, oam2Hi or tileOff)
    }

    private fun renderSideStaticSprites(side: Int, hidden: Boolean, x: Int, y: Int) {
        if (hidden) return
        val labelX = x + if (side == 0) LABEL_X_LEFT else LABEL_X_RIGHT
        val labelOam2 = (if (side == 0) OAM2_LABEL_LEFT else OAM2_LABEL_RIGHT) or unk44
        hooks.displayStaticPanelSprites(side, 0, labelX, y + LABEL_Y_OFFSET, labelOam2)

        val frameX = x + if (side == 0) FRAME_X_LEFT else FRAME_X_RIGHT
        val frameOam2 = (if (side == 0) OAM2_FRAME_LEFT else OAM2_FRAME_RIGHT) or unk44
        hooks.displayStaticPanelSprites(side, 1, frameX, y + FRAME_Y_OFFSET, frameOam2)
    }

    private fun renderSideWtaAndIcon(side: Int, hidden: Boolean, x: Int, y: Int, clk: Int) {
        if (hidden) return
        val wta = env.wtaBonus[side].toShort().toInt()
        if (wta != 0) {
            val wtaX = x + if (side == 0) WTA_X_LEFT else WTA_X_RIGHT
            hooks.displayWtaArrow(side, wtaX, y + WTA_ICON_Y_OFFSET, OAM2_WTA or unk44, wta, clk)
        }
        val iconX = x + if (side == 0) ICON_X_LEFT else ICON_X_RIGHT
        val iconOam2 = (if (side == 0) OAM2_ICON_LEFT else OAM2_ICON_RIGHT) or unk44
        hooks.displayWeaponIcon(side, iconX, y + WTA_ICON_Y_OFFSET, iconOam2)
    }

    private companion object {
        const val HP_PURPLE_THRESHOLD = 0x50
        const val ICON_PAL_BANK_LEFT = 0x1D
        const val ICON_PAL_BANK_RIGHT = 0x1E
        const val ICON_TILE_LEFT = 0x1DC
        const val ICON_TILE_RIGHT = 0x1DE
        const val MISC_UI_PAL_OFFSET = 0x10

        const val CLOCK_FRAME_DIVISOR = 8
        const val CLOCK_FRAME_COUNT = 3
        const val TILE_ALIGN_MASK = 0xFFF8
        const val BG0_GAUGE_OFFSET = 0x1A0
        const val BG0_GAUGE_WIDTH = 30
        const val BG0_GAUGE_HEIGHT = 8
        const val BG0_PANEL_MAX_HEIGHT = 7
        const val BG0_CHR_BASE = 0x80
        const val BG0_RIGHT_COL_CLOSE = 15
        const val BG0_COL_CENTERED = 8

        const val DISTANCE_CLOSE = 0
        const val DISTANCE_FAR = 1
        const val DISTANCE_FARFAR = 2
        const val DISTANCE_MONOCOMBAT = 3
        const val MONO_X_OFFSET = 0x38

        const val DIGIT_BLANK = 0xB
        const val DIGIT_OVER_THRESHOLD = 0xC

        const val OAM2_ARROW_LEFT = 0xB1CE
        const val OAM2_ARROW_RIGHT = 0xC1EE
        const val ARROW_X_LEFT = 9
        const val ARROW_X_RIGHT = 0x81
        const val ARROW_Y_OFFSET = 0x91
        const val HIT_NOW_OFFSET = 8
        const val HIT_NOW_OAM_BASE = 0x200

        const val HP_BAR_ROW_MAX = 0x28
        const val HP_BAR_X_LEFT = 0x1D
        const val HP_BAR_X_RIGHT = 0x95
        const val HP_BAR_Y_UPPER = 0x8E
        const val HP_BAR_Y_LOWER_ONE = 0x91
        const val HP_BAR_Y_LOWER_TWO = 0x95
        const val OAM2_PAL_LEFT = 0xB000
        const val OAM2_PAL_RIGHT = 0xC000
        const val OAM2_HI_MASK = 0xFC00
        const val HP_BAR_TILE_R_UPPER = 0x10
        const val HP_BAR_TILE_L_LOWER = 0x20
        const val HP_BAR_TILE_R_LOWER = 0x30

        const val LABEL_X_LEFT = 0x12
        const val LABEL_X_RIGHT = 0xD8
        const val LABEL_Y_OFFSET = 0x70
        const val OAM2_LABEL_LEFT = 0xB1D0
        const val OAM2_LABEL_RIGHT = 0xC1F0
        const val FRAME_X_LEFT = 0x65
        const val FRAME_X_RIGHT = 0x87
        const val FRAME_Y_OFFSET = 0x78
        const val OAM2_FRAME_LEFT = 0xB1C0
        const val OAM2_FRAME_RIGHT = 0xC1C0

        const val WTA_X_LEFT = 0x36
        const val WTA_X_RIGHT = 0x85
        const val WTA_ICON_Y_OFFSET = 0x79
        const val OAM2_WTA = 0x1CA
        const val ICON_X_LEFT = 0x2C
        const val ICON_X_RIGHT = 0x7B
        const val OAM2_ICON_LEFT = 0xD1DC
        const val OAM2_ICON_RIGHT = 0xE1DE
    }
}
