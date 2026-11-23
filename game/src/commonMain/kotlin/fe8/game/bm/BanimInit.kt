package fe8.game.bm

import fe8.game.RomView
import gbafe.anim.Anim
import gbafe.anim.AnimPool

/**
 * The creation of the four main battle animations (src/banim-ekrmain.c): `InitMainAnims`,
 * `InitBattleAnimFrame`, `InitLeftAnim` and `InitRightAnim`. The EWRAM variables they write are
 * public fields indexed by side (0 left, 1 right); the RAM tables they read are passed as
 * parameters.
 *
 * Only the `EKR_DISTANCE_CLOSE` arm of [initMainAnims] is ported (verified at the two animated
 * windows); the other distances fail loudly.
 *
 * @param rom the ROM holding `BanimDefaultModeConfig`, `BanimTypesPosLeft/Right` and
 *   `BanimLeftDefaultPos`.
 * @param pool the anim pool `AnimCreate` takes slots from.
 * @param buffers the addresses and tables of the loaded animation (`gBanim*`).
 */
public class BanimInit(
    private val rom: RomView,
    private val pool: AnimPool,
    private val buffers: Buffers,
) {
    /**
     * The RAM variables of the loaded battle animation that the four functions read.
     *
     * @property modesLeft `gpBanimModesLeft[frame]`, the script offset of a mode (left).
     * @property modesRight `gpBanimModesRight[frame]` (right).
     * @property scrLeft address of `gBanimScrLeft`.
     * @property scrRight address of `gBanimScrRight`.
     * @property defaultAnimScr address of `BanimScr_DefaultAnim`.
     * @property imgSheetBufLeft address of `gBanimLeftImgSheetBuf`.
     * @property imgSheetBufRight address of `gBanimRightImgSheetBuf`.
     * @property oaml address of `gBanimOaml`.
     * @property oamr2 address of `gBanimOamr2`.
     */
    public class Buffers(
        public val modesLeft: (Int) -> Int,
        public val modesRight: (Int) -> Int,
        public val scrLeft: Int,
        public val scrRight: Int,
        public val defaultAnimScr: Int,
        public val imgSheetBufLeft: Int,
        public val imgSheetBufRight: Int,
        public val oaml: Int,
        public val oamr2: Int,
    )

    /** `gAnims[4]`: left front, left back, right front, right back. */
    public val anims: Array<Anim?> = arrayOfNulls(4)

    /** `gEkrXPosReal[2]`. */
    public val xPosReal: IntArray = IntArray(2)

    /** `gEkrYPosReal[2]`. */
    public val yPosReal: IntArray = IntArray(2)

    /** `gEkrXPosBase[2]`. */
    public val xPosBase: IntArray = IntArray(2)

    /** `gEkrYPosBase[2]`. */
    public val yPosBase: IntArray = IntArray(2)

    /** `gEfxHpLutOff[2]`. */
    public val hpLutOff: IntArray = IntArray(2)

    /** `gBanimValid[2]`. */
    public val valid: BooleanArray = BooleanArray(2)

    /** `gEkrDistanceType`. */
    public var distanceType: Int = EKR_DISTANCE_CLOSE

    /** `gEkrBgPosition`. */
    public var bgPosition: Int = 0

    /**
     * Ported from src/banim-ekrmain.c: InitMainAnims
     *
     * Only the CLOSE arm (and the identical MONOCOMBAT and PROMOTION entry) is ported; FAR and
     * FARFAR stay unported and throw.
     */
    public fun initMainAnims() {
        when (distanceType) {
            EKR_DISTANCE_CLOSE, EKR_DISTANCE_MONOCOMBAT, EKR_DISTANCE_PROMOTION ->
                initBattleAnimFrame(ROUND_TAKING_HIT_CLOSE, ROUND_TAKING_HIT_CLOSE)
            else -> TODO("InitMainAnims: FAR and FARFAR arms are not ported")
        }
        hpLutOff[0] = 0
        hpLutOff[1] = 0
    }

    /** Ported from src/banim-ekrmain.c: InitBattleAnimFrame */
    public fun initBattleAnimFrame(roundTypeLeft: Int, roundTypeRight: Int) {
        anims.fill(null)
        if (valid[0]) initLeftAnim(roundTypeLeft)
        if (valid[1]) initRightAnim(roundTypeRight)
        if (distanceType == EKR_DISTANCE_PROMOTION) {
            anims[0]!!.state = anims[0]!!.state or ANIM_BIT_HIDDEN
            anims[1]!!.state = anims[1]!!.state or ANIM_BIT_HIDDEN
        }
    }

    /** Ported from src/banim-ekrmain.c: InitLeftAnim */
    public fun initLeftAnim(roundType: Int) {
        val posLeft = rom.u8(TYPES_POS_LEFT + distanceType)
        xPosBase[0] = -rom.u16(LEFT_DEFAULT_POS + 2 * distanceType)
        yPosBase[0] = 0
        xPosReal[0] = xPosBase[0] + posLeft
        yPosReal[0] = 0x58
        anims[0] = createAnim(
            roundType, 0, buffers.modesLeft, buffers.scrLeft, buffers.imgSheetBufLeft,
            buffers.oaml, OAM2_LEFT, STATE2_LEFT_FRONT, 0,
        )
        anims[1] = createAnim(
            roundType, 2, buffers.modesLeft, buffers.scrLeft, buffers.imgSheetBufLeft,
            buffers.oaml, OAM2_LEFT, STATE2_LEFT_BACK, 0,
        )
    }

    /** Ported from src/banim-ekrmain.c: InitRightAnim */
    public fun initRightAnim(roundType: Int) {
        xPosBase[1] = 0
        yPosBase[1] = 0
        xPosReal[1] = rom.u8(TYPES_POS_RIGHT + distanceType)
        yPosReal[1] = 0x58
        anims[2] = createAnim(
            roundType, 0, buffers.modesRight, buffers.scrRight, buffers.imgSheetBufRight,
            buffers.oamr2, OAM2_RIGHT, STATE2_RIGHT, 1,
        )
        anims[3] = createAnim(
            roundType, 2, buffers.modesRight, buffers.scrRight, buffers.imgSheetBufRight,
            buffers.oamr2, OAM2_RIGHT, STATE2_RIGHT or STATE2_FRONT_FRAME, 1,
        )
    }

    /** The shared body of the four `AnimCreate` blocks; [slot] 0 is front, 2 is back. */
    private fun createAnim(
        roundType: Int,
        slot: Int,
        modes: (Int) -> Int,
        scr: Int,
        imgSheetBuf: Int,
        spritePool: Int,
        oam2: Int,
        state2Bits: Int,
        side: Int,
    ): Anim {
        val frame = rom.u8(MODE_CONFIG + roundType * 4 + slot)
        val priority = rom.u8(MODE_CONFIG + roundType * 4 + slot + 1)
        val script = if (frame == 0xFF) buffers.defaultAnimScr else scr + modes(frame)
        val anim = checkNotNull(pool.create(script, priority)) { "AnimCreate: pool full" }
        anim.xPosition = ((xPosReal[side] - bgPosition) shl 16) shr 16
        anim.yPosition = (yPosReal[side] shl 16) shr 16
        anim.oam2Base = oam2
        anim.state2 = anim.state2 or STATE2_0400 or state2Bits
        anim.nextRoundId = 0
        anim.currentRoundType = roundType
        anim.pImgSheetBuf = imgSheetBuf
        anim.pSpriteDataPool = spritePool
        return anim
    }

    /** ROM table addresses and constants of src/banim-ekrmain.c and include/anime.h. */
    public companion object {
        /** `EKR_DISTANCE_CLOSE`. */
        public const val EKR_DISTANCE_CLOSE: Int = 0

        /** `EKR_DISTANCE_MONOCOMBAT`. */
        public const val EKR_DISTANCE_MONOCOMBAT: Int = 3

        /** `EKR_DISTANCE_PROMOTION`. */
        public const val EKR_DISTANCE_PROMOTION: Int = 4

        /** `ANIM_ROUND_TAKING_HIT_CLOSE`. */
        public const val ROUND_TAKING_HIT_CLOSE: Int = 6

        /** `ANIM_BIT_HIDDEN`. */
        public const val ANIM_BIT_HIDDEN: Int = 1 shl 1

        private const val MODE_CONFIG = 0x080DAEF0 // BanimDefaultModeConfig
        private const val TYPES_POS_LEFT = 0x080DAF1D
        private const val TYPES_POS_RIGHT = 0x080DAF22
        private const val LEFT_DEFAULT_POS = 0x080DAF28
        private const val STATE2_FRONT_FRAME = 1 shl 8
        private const val STATE2_POS_RIGHT = 1 shl 9
        private const val STATE2_0400 = 1 shl 10
        private const val STATE2_LEFT_FRONT = 0 // ANIM_BIT2_BACK_FRAME is 0
        private const val STATE2_LEFT_BACK = STATE2_FRONT_FRAME
        private const val STATE2_RIGHT = STATE2_POS_RIGHT
        private const val OAM2_LEFT = (0x7 shl 12) + (0x2 shl 10) + 0x4000 / 0x20
        private const val OAM2_RIGHT = (0x9 shl 12) + (0x2 shl 10) + 0x6000 / 0x20
    }
}
