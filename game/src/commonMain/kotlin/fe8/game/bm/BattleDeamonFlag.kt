package fe8.game.bm

/**
 * `gBattleDeamonActive` (src/banim-ekrbattle.c, EWRAM `0x0203E0F4`): set while the battle
 * animation system (`ProcScr_ekrBattleDeamon`) runs. The animation system is not ported, so
 * nothing sets it yet; hosts read it through [isBattleDeamonActive].
 */
public class BattleDeamonFlag {
    /** `gBattleDeamonActive`: non-zero while the battle animation system runs. */
    public var gBattleDeamonActive: Int = 0

    /**
     * `IsBattleDeamonActive`: whether the battle animation system runs.
     *
     * Transcribed from src/banim-ekrbattle.c: IsBattleDeamonActive (not verified:
     * gBattleDeamonActive is 0 in every captured window; the true branch needs M6-G battle
     * animations)
     */
    public fun isBattleDeamonActive(): Boolean = gBattleDeamonActive == 1
}
