package fe8.core.mechanics

import fe8.core.rng.RngService

/**
 * How battle decides random outcomes from a percentage: the swap point for "1-RN hit", "no crits" and
 * similar mods. Battle code never calls [RngService.roll1]/[RngService.roll2] directly; it asks the
 * active [BattleRolls] (see [Mechanics.battleRolls]).
 *
 * Vanilla is [VanillaBattleRolls]. The shipped game contains no other implementation: alternatives are
 * for mods (docs/decisions/0009-vanilla-product-mod-groundwork.md).
 *
 * The original's simulation mode (`BATTLE_CONFIG_SIMULATE` in `BattleRoll1RN`/`BattleRoll2RN`,
 * src/bmbattle.c), which returns a fixed result without drawing, stays in battle code: implementations
 * are only called for real rolls, so a swap cannot desync battle forecasts.
 *
 * Implementations must not allocate (called in the frame loop).
 */
public interface BattleRolls {
    /** Does an attack with displayed hit rate [hitRate] (0..100) connect? Vanilla: two RNs averaged. */
    public fun hit(rng: RngService, hitRate: Int): Boolean

    /** Does an attack with crit rate [critRate] (0..100) crit? Vanilla: one RN. */
    public fun crit(rng: RngService, critRate: Int): Boolean

    /**
     * Does a percentage-based skill trigger (silencer, Great Shield, Pierce, Sure Strike level checks)?
     * Vanilla: one RN.
     */
    public fun skill(rng: RngService, rate: Int): Boolean
}

/**
 * The original rolls: `BattleGenerateHitAttributes` (src/bmbattle.c) uses `BattleRoll2RN` for hit
 * ("true hit": the average of two RNs must be below the hit rate) and `BattleRoll1RN` for crit and
 * skill procs.
 */
public object VanillaBattleRolls : BattleRolls {
    /** Two-RN hit, as `BattleRoll2RN(hitRate)`. */
    override fun hit(rng: RngService, hitRate: Int): Boolean = rng.roll2(hitRate)

    /** One-RN crit, as `BattleRoll1RN(critRate)`. */
    override fun crit(rng: RngService, critRate: Int): Boolean = rng.roll1(critRate)

    /** One-RN skill proc, as `BattleRoll1RN(rate)`. */
    override fun skill(rng: RngService, rate: Int): Boolean = rng.roll1(rate)
}

/**
 * The active gameplay strategies. Each ported game system adds its swap points here (damage, exp,
 * growth, AI, ...), each defaulting to the vanilla implementation. Mods replace a strategy at load
 * time; the shipped game never does.
 */
public class Mechanics {
    /** Random battle outcomes (hit, crit, skill procs). */
    public var battleRolls: BattleRolls = VanillaBattleRolls

    /** True when every strategy is the vanilla one (what differential tests and vanilla mode require). */
    public val isVanilla: Boolean get() = battleRolls === VanillaBattleRolls
}
