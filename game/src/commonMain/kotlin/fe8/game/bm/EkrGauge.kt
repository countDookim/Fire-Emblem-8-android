package fe8.game.bm

/**
 * Control state and lifecycle of `ProcScr_ekrGauge` (`src/banim-ekrgauge.c`): the battle HP gauge
 * and side stat panel proc (`gpProcEkrGauge`), covering setup, position/flag helpers and teardown
 * for BA-p9a.
 *
 * `ekrGaugeMain` and `EkrGauge_Setxy323A` belong to BA-p9b; unused hide/enable setters
 * (`EkrGauge_Set4C50`, `EkrGauge_Set4C`, `EkrGauge_Set50`, `EnableEkrGauge`) are omitted until
 * a capture exercises them.
 *
 * @property env globals read by [newEkrGauge].
 * @property hooks VRAM, palette, digit tile and icon side effects of [newEkrGauge].
 */
public class EkrGauge(
    private val env: Env,
    private val hooks: Hooks = object : Hooks {},
) {
    /** Globals read by [newEkrGauge]. */
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
    }

    /** Side effects of [newEkrGauge] (palette copies, VRAM unpacks, digit DMA, icon loads). */
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

    private companion object {
        const val HP_PURPLE_THRESHOLD = 0x50
        const val ICON_PAL_BANK_LEFT = 0x1D
        const val ICON_PAL_BANK_RIGHT = 0x1E
        const val ICON_TILE_LEFT = 0x1DC
        const val ICON_TILE_RIGHT = 0x1DE
        const val MISC_UI_PAL_OFFSET = 0x10
    }
}
