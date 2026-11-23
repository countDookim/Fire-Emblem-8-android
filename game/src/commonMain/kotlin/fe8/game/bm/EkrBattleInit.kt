package fe8.game.bm

/**
 * `ekrBattle_Init` (src/banim-ekrbattle.c), the first step of the `ekrBattle` proc, with the
 * dragon status set-up it calls (src/banim-ekrdragon-myrrh.c, src/banim-ekrdragon-utils.c) and
 * `ShouldCallBattleQuote` (src/eventinfo.c). The proc fields and the `gEkrDragonStatus*` types
 * are public fields; `Proc_Break` and the proc scheduling are the caller's.
 *
 * @param banim the anim creation, run by `InitMainAnims`; also holds `gEkrBgPosition`.
 * @param env the variables and queries outside the animation code.
 */
public class EkrBattleInit(
    private val banim: BanimInit,
    private val env: Env,
) {
    /**
     * Variables and queries `ekrBattle_Init` reads outside the battle animation code.
     *
     * Stand-ins: [battleQuoteEntryExists] (`GetBattleQuoteEntry`, which reads the quote table and
     * event flags) and [battleMapKind] (`GetBattleMapKind`) are not ported here; the caller
     * supplies them.
     */
    public interface Env {
        /** `gEkrInitPosReal`. */
        public val initPosReal: Int

        /** `gEkrInitialHitSide`. */
        public val initialHitSide: Int

        /** `gEkrPids[side]`. */
        public fun pid(side: Int): Int

        /** `GetBattleAnimArenaFlag`. */
        public val arenaFlag: Boolean

        /** `gActionData.unitActionType`. */
        public val unitActionType: Int

        /** `GetBattleMapKind` (stand-in). */
        public fun battleMapKind(): Int

        /** `GetBattleQuoteEntry(pidA, pidB) != NULL` (stand-in). */
        public fun battleQuoteEntryExists(pidA: Int, pidB: Int): Boolean

        /** `UNIT_CLASS_ID` of the battle unit of [side] (`gpEkrBattleUnitLeft/Right`). */
        public fun classId(side: Int): Int
    }

    /** `gCtrlC01Blocking`. */
    public var ctrlC01Blocking: Int = 0

    /** `gEkrDragonStatusLeft/Right.type`. */
    public val dragonType: IntArray = IntArray(2)

    /** `ProcEkrBattle.timer` (0x2C). */
    public var procTimer: Int = 0

    /** `ProcEkrBattle.is_quote` (0x54). */
    public var procIsQuote: Int = 0

    /** `ProcEkrBattle.unk58` (0x58). */
    public var procUnk58: Int = 0

    /** Ported from src/banim-ekrbattle.c: ekrBattle_Init */
    public fun run() {
        banim.bgPosition = 0
        if (env.initPosReal == 0) {
            banim.bgPosition = if (banim.distanceType == EKR_DISTANCE_FAR) -0x20 else -0xF0
        }

        banim.initMainAnims()
        registerEkrDragonStatusType()
        initEkrDragonStatus()

        ctrlC01Blocking = 1
        procTimer = if (env.arenaFlag) 0 else 0x1E

        procIsQuote = if (env.initialHitSide == 0) {
            shouldCallBattleQuote(env.pid(0), env.pid(1))
        } else {
            shouldCallBattleQuote(env.pid(1), env.pid(0))
        }
        procUnk58 = 0
    }

    /**
     * Ported from src/banim-ekrdragon-myrrh.c: RegisterEkrDragonStatusType
     *
     * Only the CLOSE/FAR/FARFAR entry and the non-Myrrh classes are ported; the Myrrh arms stay
     * unported and throw. The draco zombie and demon king arms are not run by any capture.
     */
    public fun registerEkrDragonStatusType() {
        when (banim.distanceType) {
            BanimInit.EKR_DISTANCE_CLOSE, EKR_DISTANCE_FAR, EKR_DISTANCE_FARFAR -> Unit
            else -> return
        }
        val left = banim.anims[0]!!
        val right = banim.anims[2]!!
        when (env.classId(0)) {
            CLASS_MANAKETE_MYRRH -> TODO("RegisterEkrDragonStatusType: Myrrh arm not ported")
            CLASS_DRACO_ZOMBIE -> setDragonType(left.state2, TYPE_DRACO_ZOMBIE)
            CLASS_DEMON_KING -> setDragonType(left.state2, TYPE_DEMON_KING)
            else -> setDragonType(left.state2, TYPE_NORMAL)
        }
        if (env.classId(1) == CLASS_MANAKETE_MYRRH) {
            TODO("RegisterEkrDragonStatusType: Myrrh arm not ported")
        } else {
            setDragonType(right.state2, TYPE_NORMAL)
        }
    }

    /** `SetEkrDragonStatusType`: the side comes from the anim's `ANIM_BIT2_POS_RIGHT`. */
    private fun setDragonType(state2: Int, type: Int) {
        dragonType[if (state2 and STATE2_POS_RIGHT != 0) 1 else 0] = type
    }

    /**
     * Transcribed from src/banim-ekrdragon-utils.c: InitEkrDragonStatus (not verified: the
     * captured windows only run the no-op path; the draco zombie and demon king arms that hide
     * the left anim need a dragon capture).
     */
    public fun initEkrDragonStatus() {
        val type = if (dragonType[0] != 0) dragonType[0] else dragonType[1]
        if (type == TYPE_DRACO_ZOMBIE || type == TYPE_DEMON_KING) {
            val anim = banim.anims[0]!!
            anim.state = anim.state or BanimInit.ANIM_BIT_HIDDEN
        }
    }

    /** Ported from src/eventinfo.c: ShouldCallBattleQuote */
    public fun shouldCallBattleQuote(pidA: Int, pidB: Int): Int {
        if (env.battleMapKind() == BattleMapKind.SKIRMISH) return 0
        if (env.unitActionType != UNIT_ACTION_COMBAT) return 0
        if (env.battleQuoteEntryExists(pidA, pidB)) return 1
        if (env.battleQuoteEntryExists(pidA, 0)) return 1
        if (env.battleQuoteEntryExists(0, pidB)) return 1
        return 0
    }

    private companion object {
        const val EKR_DISTANCE_FAR = 1
        const val EKR_DISTANCE_FARFAR = 2
        const val UNIT_ACTION_COMBAT = 2
        const val CLASS_MANAKETE_MYRRH = 0x3C
        const val CLASS_DRACO_ZOMBIE = 0x65
        const val CLASS_DEMON_KING = 0x66
        const val TYPE_NORMAL = 0
        const val TYPE_DRACO_ZOMBIE = 1
        const val TYPE_DEMON_KING = 2
        const val STATE2_POS_RIGHT = 1 shl 9
    }
}
