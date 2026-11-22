package fe8.game.savemenu

import fe8.game.RomView
import gbafe.core.rng.LcgRng
import gbafe.proc.Proc
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * Palette cycling, suspend bookmark rotation/scaling, and drifting fog particles for the save
 * menu (`PC-4c2`: `sub_80AA7AC` in `src/savemenu.c`, `sub_80AACBC` and `sub_80AB56C` in
 * `src/savedraw.c`).
 *
 * @param hardware palette buffer (`gPaletteBuffer`) and sync flag (`EnablePaletteSync`)
 * @param oam OAM shadow buffer receiving affine matrix `3` for the suspend bookmark spin
 * @param layers sprite layer lists receiving fog particle sprites on layer `14`
 * @param slotData slot palette owner (`SaveMenuInitSlotPalette`, `SaveDrawSetDifficultSlotPalette`)
 * @param lcg secondary LCG (`gLCGRNValue` / `AdvanceGetLCGRNValue`) used to spawn particles
 * @param particles the 20 entries of `gUnknown_020007E0` owned by [SaveDrawLifecycle.particles]
 * @param sin 320-entry `gSinLookup` table (`256 + 64` signed 4.12 halfwords for `SIN` and `COS`)
 * @param cyclePalette 16-entry `Pal_08A28088` highlight palette cycle table
 * @param particleTopSprite `Sprite_08A20580` (`8x8` tile `0x1F1` drawn above `unk_04 == 0x3BF`)
 * @param particleBottomSprite `Sprite_08A20578` (`8x8` tile `0x1D1` drawn below `unk_04 == 0x3BF`)
 */
public class SaveDrawEffects(
    private val hardware: BgHardware,
    private val oam: OamBuffer,
    private val layers: SpriteLayers,
    private val slotData: SaveSlotData,
    private val lcg: LcgRng,
    private val particles: Array<SaveDrawParticle>,
    private val sin: ShortArray,
    private val cyclePalette: ShortArray,
    private val particleTopSprite: SpriteObject,
    private val particleBottomSprite: SpriteObject,
) {
    /**
     * Creates a [SaveDrawEffects] loading `gSinLookup`, `Pal_08A28088`, `Sprite_08A20580`, and
     * `Sprite_08A20578` from [rom].
     *
     * @param hardware palette buffer (`gPaletteBuffer`) and sync flag (`EnablePaletteSync`)
     * @param oam OAM shadow buffer receiving affine matrix `3` for the suspend bookmark spin
     * @param layers sprite layer lists receiving fog particle sprites on layer `14`
     * @param slotData slot palette owner (`SaveMenuInitSlotPalette`,
     *   `SaveDrawSetDifficultSlotPalette`)
     * @param lcg secondary LCG (`gLCGRNValue` / `AdvanceGetLCGRNValue`) used to spawn particles
     * @param particles the 20 entries of `gUnknown_020007E0` owned by [SaveDrawLifecycle.particles]
     * @param rom ROM view holding `gSinLookup`, `Pal_08A28088`, and the particle sprite objects
     */
    public constructor(
        hardware: BgHardware,
        oam: OamBuffer,
        layers: SpriteLayers,
        slotData: SaveSlotData,
        lcg: LcgRng,
        particles: Array<SaveDrawParticle>,
        rom: RomView,
    ) : this(
        hardware = hardware,
        oam = oam,
        layers = layers,
        slotData = slotData,
        lcg = lcg,
        particles = particles,
        sin = rom.pal(SIN_LOOKUP_ADDRESS, SIN_LOOKUP_SIZE),
        cyclePalette = rom.pal(PAL_CYCLE_ADDRESS, PAL_CYCLE_COLORS),
        particleTopSprite = rom.objN(SPRITE_PARTICLE_TOP_ADDRESS),
        particleBottomSprite = rom.objN(SPRITE_PARTICLE_BOTTOM_ADDRESS),
    )

    /**
     * Cycles the cursor highlight color (OBJ palette bank `1`, color `1` at `0x111`) and the
     * selected slot's palette highlight color (`b * 0x20 + 0x1A1`) across the 16 colors of
     * `Pal_08A28088` using `(a & 0x3F) >> 2`, and requests palette sync.
     *
     * Verified across all 16 palette steps on slot `b = 0` in `PC-cap1`; non-zero slot strides
     * `b * 0x20` for slots `1..2` are unexercised in `PC-cap1` because `susSlot` remains `0`.
     *
     * Ported from src/savemenu.c: sub_80AA7AC
     */
    public fun sub80AA7AC(a: Int, b: Int) {
        val offset = (a and PAL_CYCLE_MASK) ushr PAL_CYCLE_SHIFT
        val color = cyclePalette[offset]
        hardware.palette[PAL_CURSOR_COLOR_OFFSET] = color
        hardware.palette[b * SLOT_PALETTE_STRIDE + PAL_SLOT_BASE_COLOR_OFFSET] = color
        hardware.enablePaletteSync()
    }

    /**
     * Updates save slot palettes on slot changes, pulses difficult slot palettes, spins/shrinks
     * OAM affine matrix `3` while the suspend bookmark (`susSlotCur != 0xFF`) dismisses
     * (`unk44 != 0x100`), hides the bookmark once `unk44 < 0x10`, runs [sub80AA7AC], and
     * increments [SaveDrawProc.unk2a].
     *
     * Transcribed from src/savedraw.c: sub_80AACBC (not verified: the slot-change branch
     * unk_3c != sus_slot never runs in PC-cap1 where sus_slot stays 0, and
     * SaveDrawSetDifficultSlotPalette never sees a PLAY_FLAG_HARD slot; sus_slot_cur != 0xFF,
     * unk_44 != 0x100 spin/scale, unk_44 < 0x10 hide, sub_80AA7AC, and unk_2a++ match PC-cap1)
     */
    public fun sub80AACBC(proc: Proc) {
        val state = proc.state as SaveDrawProc
        val menu = checkNotNull(proc.parent?.state as? SaveDrawSlotsParent) {
            "SaveDrawProc requires a SaveDrawSlotsParent parent"
        }

        if (state.unk3c != menu.susSlot) {
            slotData.saveMenuInitSlotPalette(menu.susSlot)
            state.unk3c = menu.susSlot and BYTE_MASK
        }

        slotData.saveDrawSetDifficultSlotPalette(state.unk2a, state.unk3c)
        hardware.enablePaletteSync()

        if (menu.susSlotCur != NO_SUSPEND_SLOT) {
            val angleScale = menu.unk44 and UINT16_MASK
            if (angleScale != REST_ANGLE_SCALE) {
                if (angleScale < MIN_SPIN_ANGLE_SCALE) {
                    menu.susSlotCur = NO_SUSPEND_SLOT
                } else {
                    val cosVal = sin[COS_TABLE_OFFSET + (angleScale and BYTE_MASK)].toInt()
                    val sinVal = sin[angleScale and BYTE_MASK].toInt()
                    oam.setObjAffine(
                        BOOKMARK_AFFINE_INDEX,
                        (cosVal * AFFINE_NUMERATOR_SCALE) / angleScale,
                        (-sinVal * AFFINE_NUMERATOR_SCALE) / angleScale,
                        (sinVal * AFFINE_NUMERATOR_SCALE) / angleScale,
                        (cosVal * AFFINE_NUMERATOR_SCALE) / angleScale,
                    )
                }
                menu.unk44 = (angleScale - SPIN_STEP) and UINT16_MASK
            }
        }

        sub80AA7AC(state.unk2a, state.unk3c)
        state.unk2a = (state.unk2a + 1) and UINT16_MASK
    }

    /**
     * Advances and draws the 20 background fog particles (`gUnknown_020007E0[0..19]`) on sprite
     * layer `14` using the post-increment frame counter [a] (`SaveDrawProc.unk2a`), and spawns new
     * particles via `AdvanceGetLCGRNValue()` while fewer than `19` remain active.
     *
     * Transcribed from src/savedraw.c: sub_80AB56C (not verified: the bottom deactivation check
     * unk_04 > 0xB3F is never taken and unk_0a += unk_0d is unobservable because unk_0d stays 0
     * across PC-cap1; active drawing/clipping, velocity/acceleration updates, horizontal/top
     * deactivation, LCG spawning up to 19 particles, and layer 14 OAM output match PC-cap1)
     */
    public fun sub80AB56C(a: Int) {
        var activeCount = 0

        for (i in particles.indices) {
            val particle = particles[i]
            if (particle.unk0e != 0) {
                if ((particle.unk00 + PARTICLE_X_DRAW_BIAS).toUInt() <= PARTICLE_X_DRAW_MAX) {
                    val x = (particle.unk00 / PARTICLE_SUBPIXELS) and OAM1_X_MASK
                    val y = (particle.unk04 / PARTICLE_SUBPIXELS) and OAM0_Y_MASK
                    val sprite = if (particle.unk04 <= PARTICLE_Y_TOP_MAX) {
                        particleTopSprite
                    } else {
                        particleBottomSprite
                    }
                    layers.putExt(PARTICLE_SPRITE_LAYER, x, y, sprite, PARTICLE_OAM2)
                }

                particle.unk00 += particle.unk08
                particle.unk04 += particle.unk0a

                if ((a and PARTICLE_ACCEL_MASK) == 0) {
                    particle.unk08 = (particle.unk08 + particle.unk0c).toShort().toInt()
                    particle.unk0a = (particle.unk0a + particle.unk0d).toShort().toInt()
                }

                val outOfBounds =
                    (particle.unk00 + PARTICLE_X_OOB_BIAS).toUInt() > PARTICLE_X_OOB_MAX ||
                        particle.unk04 < PARTICLE_Y_MIN ||
                        particle.unk04 > PARTICLE_Y_MAX
                if (outOfBounds) {
                    particle.unk0e = 0
                } else {
                    activeCount++
                }
            }
        }

        if (activeCount < PARTICLE_MAX_SPAWN_COUNT) {
            for (i in particles.indices) {
                val particle = particles[i]
                if (particle.unk0e == 0 && lcg.advance() % SPAWN_CHANCE_MOD == 0) {
                    val coarseX = (lcg.advance() % SPAWN_X_COARSE_MOD - SPAWN_X_COARSE_BIAS) *
                        SPAWN_X_COARSE_STEP
                    val fineX = lcg.advance() % SPAWN_X_FINE_MOD
                    particle.unk00 = (coarseX + fineX) * PARTICLE_SUBPIXELS
                    particle.unk04 = SPAWN_Y
                    particle.unk08 = lcg.advance() % SPAWN_VX_MOD + SPAWN_VX_BASE
                    particle.unk0a = SPAWN_VY_BASE - (lcg.advance() % SPAWN_VY_MOD)
                    particle.unk0c = lcg.advance() % SPAWN_AX_MOD + SPAWN_AX_BASE
                    particle.unk0d = 0
                    particle.unk0e = 1
                }
            }
        }
    }

    /** ROM addresses and particle/palette constants from `src/savedraw.c` and `src/savemenu.c`. */
    public companion object {
        /** ROM address of `gSinLookup` (`data/trig_rodata.s`). */
        public const val SIN_LOOKUP_ADDRESS: Int = 0x080D751C

        /** Number of entries in `gSinLookup` (`256 + 64` for `SIN` and `COS`). */
        public const val SIN_LOOKUP_SIZE: Int = 320

        /** ROM address of `Pal_08A28088` (16-color highlight cycle palette). */
        public const val PAL_CYCLE_ADDRESS: Int = 0x08A28088

        /** Number of halfword colors in `Pal_08A28088`. */
        public const val PAL_CYCLE_COLORS: Int = 16

        /** ROM address of `Sprite_08A20578` (`8x8` tile `0x1D1` lower fog particle). */
        public const val SPRITE_PARTICLE_BOTTOM_ADDRESS: Int = 0x08A20578

        /** ROM address of `Sprite_08A20580` (`8x8` tile `0x1F1` upper fog particle). */
        public const val SPRITE_PARTICLE_TOP_ADDRESS: Int = 0x08A20580

        /** Sprite layer used by `PutSpriteExt(0xe, ...)` in `sub_80AB56C`. */
        public const val PARTICLE_SPRITE_LAYER: Int = 14

        /** `OAM2_PAL(3)` passed as `oam2` in `sub_80AB56C`. */
        public const val PARTICLE_OAM2: Int = 0x3000

        /** `gPaletteBuffer` index of the cursor highlight color (`PAL_OBJ_COLOR(1, 1)`). */
        public const val PAL_CURSOR_COLOR_OFFSET: Int = 0x111

        /** `gPaletteBuffer` base index of slot 0's cycled color (`PAL_OBJ_COLOR(10, 1)`). */
        public const val PAL_SLOT_BASE_COLOR_OFFSET: Int = 0x1A1

        /** Halfword stride in `gPaletteBuffer` between save slots (`0x20` = 2 palette banks). */
        public const val SLOT_PALETTE_STRIDE: Int = 0x20

        private const val PAL_CYCLE_MASK = 0x3F
        private const val PAL_CYCLE_SHIFT = 2
        private const val NO_SUSPEND_SLOT = 0xFF
        private const val REST_ANGLE_SCALE = 0x100
        private const val MIN_SPIN_ANGLE_SCALE = 0x10
        private const val SPIN_STEP = 16
        private const val BOOKMARK_AFFINE_INDEX = 3
        private const val COS_TABLE_OFFSET = 0x40
        private const val AFFINE_NUMERATOR_SCALE = 16
        private const val BYTE_MASK = 0xFF
        private const val UINT16_MASK = 0xFFFF

        private const val PARTICLE_X_DRAW_BIAS = 0xC0
        private const val PARTICLE_X_DRAW_MAX = 0xC5Fu
        private const val PARTICLE_Y_TOP_MAX = 0x3BF
        private const val PARTICLE_SUBPIXELS = 12
        private const val PARTICLE_ACCEL_MASK = 3
        private const val PARTICLE_X_OOB_BIAS = 0x5A0
        private const val PARTICLE_X_OOB_MAX = 0x167Fu
        private const val PARTICLE_Y_MIN = -0x3C0
        private const val PARTICLE_Y_MAX = 0xB3F
        private const val PARTICLE_MAX_SPAWN_COUNT = 0x13
        private const val OAM1_X_MASK = 0x1FF
        private const val OAM0_Y_MASK = 0xFF

        private const val SPAWN_CHANCE_MOD = 0xB
        private const val SPAWN_X_COARSE_MOD = 0x11
        private const val SPAWN_X_COARSE_BIAS = 8
        private const val SPAWN_X_COARSE_STEP = 0x1E
        private const val SPAWN_X_FINE_MOD = 0x1F
        private const val SPAWN_Y = 0x780
        private const val SPAWN_VX_MOD = 7
        private const val SPAWN_VX_BASE = 6
        private const val SPAWN_VY_BASE = -0x18
        private const val SPAWN_VY_MOD = 0x19
        private const val SPAWN_AX_MOD = 3
        private const val SPAWN_AX_BASE = 1
    }
}
