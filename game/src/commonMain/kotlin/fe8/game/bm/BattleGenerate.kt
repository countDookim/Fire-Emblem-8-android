package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import kotlin.math.abs

/**
 * The scripted and event battle generation of `src/bmbattle.c` and `src/eventscr3.c`: the
 * `gBattleHitArray` / `gActionData.scriptedBattleHits` state, `SetScriptedBattle`,
 * `BattleGenerate`, `BattleUnwindScripted` and the event-side `EvtBattleGenerateReal`
 * and `StartEventBattle` set-up, on the battle units of [battle].
 *
 * Hits are `struct BattleHit` words: `attributes` in bits 0..18, `info` in bits 19..23 and the
 * signed `hpChange` in bits 24..31. Both hit buffers are preallocated.
 *
 * Not ported (the callers stop with an error instead): `BattleUnwind` (random hits),
 * `ComputeBattleObstacleStats`, the exp gain tail of `BattleGenerateRealInternal`
 * (`BattleApplyExpGains`, M6-40), the light rune toggling and the staff / item effect battles.
 *
 * @param battle the battle units, stats and stat computes.
 * @param rom the item and class data.
 * @param loader the unit helpers (equipped weapon).
 * @param playSt `gPlaySt` (`faction`).
 * @param rng the random number generator handed to [Mechanics.battleRolls].
 * @param mechanics the active battle roll strategy.
 */
public class BattleGenerate(
    private val battle: BmBattle,
    private val rom: RomView,
    private val loader: UnitLoader,
    private val playSt: PlaySt,
    private val rng: RngService,
    private val mechanics: Mechanics,
) {
    private val items = ItemInfo(rom)

    /** `gBattleHitArray`: [BATTLE_HIT_MAX] hits plus room for the terminating hit. */
    public val hitArray: IntArray = IntArray(BATTLE_HIT_MAX + 1)

    /** `gBattleHitIterator`, as an index into [hitArray]. */
    public var hitIterator: Int = 0
        private set

    /** `gActionData.script_hits`: the buffer the event queue is turned into. */
    public val scriptHits: IntArray = IntArray(BATTLE_HIT_MAX + 1)

    /**
     * `ItemBackupEvtBattle`: the actor's first item saved by an event battle with an item
     * override, or [ITEM_BACKUP_NONE]. [startEventBattle] resets it; the override is not ported.
     */
    public var itemBackupEvtBattle: Int = ITEM_BACKUP_NONE

    /** `gActionData.scriptedBattleHits`: the hits to unwind, or `null`. */
    public var scriptedBattleHits: IntArray? = null
        private set

    /**
     * Transcribed from src/bmbattle.c: SetScriptedBattle
     *   (not verified: a plain pointer store, observable only through the unwind)
     *
     * Makes the next real battle replay [hits] (terminated by a hit with the END info), or
     * generate its own for `null`.
     */
    public fun setScriptedBattle(hits: IntArray?) {
        scriptedBattleHits = hits
    }

    /**
     * Transcribed from src/bmbattle.c: ClearBattleHits
     *   (not verified: the traced battle never clears a hit array with data)
     *
     * Zeroes [hitArray] and rewinds the iterator.
     */
    public fun clearBattleHits() {
        hitArray.fill(0)
        hitIterator = 0
    }

    /**
     * Ported from src/eventscr.c: GenerateScriptBattleHitFormEventQueue
     *
     * Turns the event queue (`gEventSlotQueue`, 4-byte entries at [queueOff] of [queue]: side,
     * hp change, attributes) into hits in [scriptHits], at most [count] of them
     * (`gEventSlots[0xD]`) or up to an entry starting with 0xFF, and terminates them with an END
     * hit. Returns [scriptHits]. Unlike the C, at most [BATTLE_HIT_MAX] hits are read.
     */
    public fun generateScriptBattleHitFormEventQueue(
        count: Int,
        queue: ByteArray,
        queueOff: Int,
    ): IntArray {
        var i = 0
        while (i < count && i < BATTLE_HIT_MAX && StructCodec.u8(queue, queueOff + 4 * i) != 0xFF) {
            val side = StructCodec.u8(queue, queueOff + 4 * i)
            var info = if (i == 0) INFO_BEGIN else 0
            if (side == 1) info = info or INFO_RETALIATION
            scriptHits[i] = hit(
                StructCodec.u16(queue, queueOff + 4 * i + 2), info,
                StructCodec.u8(queue, queueOff + 4 * i + 1),
            )
            i++
        }
        scriptHits[i] = (scriptHits[i] and (0x1F shl 19).inv()) or (INFO_END shl 19)
        return scriptHits
    }

    /**
     * Transcribed from src/bmbattle.c: BattleGenerateRealInternal
     *   (not verified: the Prologue battle runs the event variant; the exp tail is not ported)
     *
     * Initialises the battle units from the units at [actorOff] / [targetOff] of [src], generates
     * the battle and fixes up the target's counter and equipped weapon.
     */
    public fun battleGenerateRealInternal(src: ByteArray, actorOff: Int, targetOff: Int) {
        generateCommon(src, actorOff, targetOff)
        battle.battleUnitTargetCheckCanCounter(battle.target)
        battleUnitTargetSetEquippedWeapon(battle.target)
        error("BattleApplyExpGains and the pid stats are not ported (M6-40)")
    }

    /**
     * Transcribed from src/bmbattle.c: BattleGenerateReal
     *   (not verified: see [battleGenerateRealInternal])
     */
    public fun battleGenerateReal(src: ByteArray, actorOff: Int, targetOff: Int) {
        battle.stats.config = BattleStats.CONFIG_REAL
        battleGenerateRealInternal(src, actorOff, targetOff)
    }

    /**
     * Ported from src/eventscr3.c: EvtBattleGenerateRealInternal
     *
     * [battleGenerateRealInternal] as the event engine runs it: no exp gains. Needs
     * `stats.config` set by the caller.
     */
    public fun evtBattleGenerateRealInternal(src: ByteArray, actorOff: Int, targetOff: Int) {
        generateCommon(src, actorOff, targetOff)
        battle.battleUnitTargetCheckCanCounter(battle.target)
        battleUnitTargetSetEquippedWeapon(battle.target)
    }

    /**
     * Ported from src/eventscr3.c: EvtBattleGenerateReal
     *
     * Sets the real battle config, then [evtBattleGenerateRealInternal].
     */
    public fun evtBattleGenerateReal(src: ByteArray, actorOff: Int, targetOff: Int) {
        battle.stats.config = BattleStats.CONFIG_REAL
        evtBattleGenerateRealInternal(src, actorOff, targetOff)
    }

    /**
     * Transcribed from src/eventscr3.c: EvtBattleGenerateBallistaReal
     *   (not verified: no ballista in the trace)
     *
     * Sets the real ballista battle config, then [evtBattleGenerateRealInternal].
     */
    public fun evtBattleGenerateBallistaReal(src: ByteArray, actorOff: Int, targetOff: Int) {
        battle.stats.config = BattleStats.CONFIG_REAL or BattleStats.CONFIG_BALLISTA
        evtBattleGenerateRealInternal(src, actorOff, targetOff)
    }

    /**
     * Transcribed from src/eventscr3.c: StartEventBattle
     *   (not verified: the weapon / exp reset is covered by the Prologue battle, the rest of the
     *   function is not ported)
     *
     * The plain weapon battle of the event engine: stores [hits] as the scripted battle, runs
     * [evtBattleGenerateReal] (or the ballista variant) and resets both battle units' weapons to
     * the unit's equipped weapon and their exp gain to 0. Staff and item-effect battles, the
     * `item` argument and the animation start are not ported.
     */
    public fun startEventBattle(
        src: ByteArray,
        actorOff: Int,
        targetOff: Int,
        isBallista: Boolean,
        hits: IntArray?,
    ) {
        check(items.getItemType(StructCodec.u16(src, actorOff + OFF_ITEMS)) != ITYPE_STAFF) {
            "staff battles are not ported"
        }
        scriptedBattleHits = hits
        if (isBallista) {
            evtBattleGenerateBallistaReal(src, actorOff, targetOff)
        } else {
            evtBattleGenerateReal(src, actorOff, targetOff)
        }
        val a = loader.getUnitEquippedWeapon(src, actorOff)
        battle.actor.weaponBefore = a
        battle.actor.weapon = a
        val t = loader.getUnitEquippedWeapon(src, targetOff)
        battle.target.weaponBefore = t
        battle.target.weapon = t
        battle.actor.expGain = 0
        battle.target.expGain = 0
        itemBackupEvtBattle = ITEM_BACKUP_NONE
    }

    /**
     * Ported from src/eventscr3.c: EventBattleReloadBmStatus
     *
     * Ends an event battle: copies the battle units' HP into the units of [table], drops the
     * scripted hits, restores the actor's first item from [itemBackupEvtBattle], clears
     * `US_UNSELECTABLE` on both units and, for a map-animation battle, hides and kills the units
     * left at 0 HP. [aiRefreshMap], [refreshEntityBmMaps] and [refreshUnitSprites] stand for
     * `AiRefreshMap`, `RefreshEntityBmMaps` and `RefreshUnitSprites`, which are separate systems.
     */
    public fun eventBattleReloadBmStatus(
        table: UnitTable,
        aiRefreshMap: () -> Unit,
        refreshEntityBmMaps: () -> Unit,
        refreshUnitSprites: () -> Unit,
    ) {
        val b = table.bytes
        val a = table.unitOffset(s8(battle.actor.bytes, OFF_INDEX))
        val t = table.unitOffset(s8(battle.target.bytes, OFF_INDEX))
        put8(b, a + OFF_CUR_HP, StructCodec.u8(battle.actor.bytes, OFF_CUR_HP))
        put8(b, t + OFF_CUR_HP, StructCodec.u8(battle.target.bytes, OFF_CUR_HP))
        scriptedBattleHits = null
        aiRefreshMap()
        if (itemBackupEvtBattle != ITEM_BACKUP_NONE) {
            StructCodec.put16(b, a + OFF_ITEMS, itemBackupEvtBattle)
        }
        for (o in intArrayOf(a, t)) {
            StructCodec.put32(b, o + OFF_STATE, u32(b, o + OFF_STATE) and US_UNSELECTABLE.inv())
        }
        if ((battle.stats.config and BattleStats.CONFIG_MAPANIMS) != 0) {
            for (o in intArrayOf(a, t)) {
                if (StructCodec.u8(b, o + OFF_CUR_HP) == 0) {
                    val state = u32(b, o + OFF_STATE) or US_HIDDEN or US_DEAD
                    StructCodec.put32(b, o + OFF_STATE, state)
                }
            }
        }
        refreshEntityBmMaps()
        refreshUnitSprites()
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerate
     *
     * Computes both battle units' stats, then unwinds the scripted hits when a real battle has
     * them. The random unwind and the obstacle battle (`target == NULL`) are not ported.
     */
    public fun battleGenerate() {
        battle.computeBattleUnitStats(battle.actor, battle.target)
        battle.computeBattleUnitStats(battle.target, battle.actor)
        battle.computeBattleUnitEffectiveStats(battle.actor, battle.target)
        battle.computeBattleUnitEffectiveStats(battle.target, battle.actor)
        if ((battle.stats.config and BattleStats.CONFIG_REAL) != 0 && scriptedBattleHits != null) {
            battleUnwindScripted()
        } else {
            error("BattleUnwind (random hits) is not ported")
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHitScriptedDamage
     *
     * Sets `stats.damage` for the scripted hit under the iterator: 0 for a miss, the scripted
     * `hpChange`, or attack minus defense (tripled for a crit) when that is 0; capped to 0..127.
     * Marks [bu] as having dealt damage.
     */
    public fun battleGenerateHitScriptedDamage(bu: BattleUnit) {
        val st = battle.stats
        st.damage = 0
        val hit = hitArray[hitIterator]
        if ((attributes(hit) and ATTR_MISS) == 0) {
            if (hpChange(hit) == 0) {
                st.damage = st.attack - st.defense
                if ((attributes(hit) and ATTR_CRIT) != 0) st.damage = 3 * st.damage
            } else {
                st.damage = hpChange(hit)
            }
            if (st.damage > BATTLE_MAX_DAMAGE) st.damage = BATTLE_MAX_DAMAGE
            if (st.damage < 0) st.damage = 0
            if (st.damage != 0) bu.nonZeroDamage = 1
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleUnwindScripted
     *
     * Copies [scriptedBattleHits] into [hitArray] up to and including the END hit, then replays
     * every hit on the battle units (hit effects, wexp, finish flags) until the END hit or a hit
     * that finishes the battle. Clears [scriptedBattleHits] afterwards.
     */
    public fun battleUnwindScripted() {
        val src = checkNotNull(scriptedBattleHits)
        var i = 0
        while ((info(src[i]) and INFO_END) == 0) {
            hitArray[i] = src[i]
            i++
        }
        hitArray[i] = src[i]

        val actor = battle.actor
        val target = battle.target
        hitIterator = 0
        while ((info(hitArray[hitIterator]) and INFO_END) == 0) {
            val attacker: BattleUnit
            val defender: BattleUnit
            if ((info(hitArray[hitIterator]) and INFO_RETALIATION) != 0) {
                attacker = target
                defender = actor
            } else {
                attacker = actor
                defender = target
            }

            battleUpdateBattleStats(attacker, defender)
            battleGenerateHitScriptedDamage(attacker)
            battleGenerateHitEffects(attacker, defender)

            if (curHp(attacker) == 0 || curHp(defender) == 0) {
                attacker.wexpMultiplier++
                addInfo(hitIterator, INFO_FINISHES)
                if (curHp(target) == 0) addInfo(hitIterator, INFO_KILLS_TARGET)
                setInfo(hitIterator + 1, INFO_END)
                break
            }

            if (defender.unitStatusIndex == STATUS_PETRIFY ||
                defender.unitStatusIndex == STATUS_13 ||
                defender.statusOut == STATUS_PETRIFY ||
                defender.statusOut == STATUS_13
            ) {
                attacker.wexpMultiplier++
                addInfo(hitIterator, INFO_FINISHES)
                setInfo(hitIterator + 1, INFO_END)
                break
            }
            hitIterator++
        }
        scriptedBattleHits = null
    }

    /**
     * Ported from src/bmbattle.c: BattleUpdateBattleStats
     *
     * Loads the attacker's attack, hit, crit and silencer rates and the defender's defense into
     * `stats`.
     */
    public fun battleUpdateBattleStats(attacker: BattleUnit, defender: BattleUnit) {
        val st = battle.stats
        st.attack = attacker.battleAttack
        st.defense = defender.battleDefense
        st.hitRate = attacker.battleEffectiveHitRate
        st.critRate = attacker.battleEffectiveCritRate
        st.silencerRate = attacker.battleSilencerRate
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHitEffects
     *
     * Applies the hit under the iterator: weapon effects (poison, halve, devil, drain, petrify),
     * HP changes, `hpChange` of the hit and the weapon's use.
     */
    public fun battleGenerateHitEffects(attacker: BattleUnit, defender: BattleUnit) {
        val st = battle.stats
        val it = hitIterator
        attacker.wexpMultiplier++
        val effect = items.getItemWeaponEffect(attacker.weapon)
        val isDemonKing = rom.u8(u32(defender.bytes, OFF_CLASS) + 4) == CLASS_DEMON_KING

        if ((attributes(hitArray[it]) and ATTR_MISS) == 0) {
            if (!isDemonKing) {
                when (effect) {
                    WPN_EFFECT_POISON -> {
                        defender.statusOut = STATUS_POISON
                        addAttributes(it, ATTR_POISON)
                        if (defender.unitStatusIndex == STATUS_PETRIFY ||
                            defender.unitStatusIndex == STATUS_13
                        ) {
                            val state = u32(defender.bytes, OFF_STATE) and US_UNSELECTABLE.inv()
                            StructCodec.put32(defender.bytes, OFF_STATE, state)
                        }
                    }
                    WPN_EFFECT_HPHALVE -> addAttributes(it, ATTR_HPHALVE)
                }
            }

            if (effect == WPN_EFFECT_DEVIL &&
                battleRoll1Rn(31 - s8(attacker.bytes, OFF_LCK))
            ) {
                addAttributes(it, ATTR_DEVIL)
                setCurHp(attacker, maxOf(curHp(attacker) - st.damage, 0))
            } else {
                if (st.damage > curHp(defender)) st.damage = curHp(defender)
                setCurHp(defender, maxOf(curHp(defender) - st.damage, 0))
            }

            if (effect == WPN_EFFECT_HPDRAIN) {
                if (s8(attacker.bytes, OFF_MAX_HP) < curHp(attacker) + st.damage) {
                    setCurHp(attacker, s8(attacker.bytes, OFF_MAX_HP))
                } else {
                    setCurHp(attacker, curHp(attacker) + st.damage)
                }
                addAttributes(it, ATTR_HPSTEAL)
            }

            if (!isDemonKing && effect == WPN_EFFECT_PETRIFY) {
                val faction = playSt.faction
                if (faction == UnitLoader.FACTION_BLUE || faction == UnitLoader.FACTION_RED ||
                    faction == UnitLoader.FACTION_GREEN
                ) {
                    val sameSide = (s8(defender.bytes, OFF_INDEX) and 0xC0) == faction
                    defender.statusOut = if (sameSide) STATUS_13 else STATUS_PETRIFY
                }
                addAttributes(it, ATTR_PETRIFY)
            }
        }

        hitArray[it] = (hitArray[it] and 0x00FFFFFF) or (st.damage shl 24)

        if ((attributes(hitArray[it]) and ATTR_MISS) == 0 ||
            (attacker.weaponAttributes and
                (ItemInfo.IA_UNCOUNTERABLE or ItemInfo.IA_MAGIC)) != 0
        ) {
            attacker.weapon = getItemAfterUse(attacker.weapon)
            if (attacker.weapon == 0) attacker.weaponBroke = 1
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleUnitTargetSetEquippedWeapon
     *   (not verified: the traced target already has a weaponBefore; the staff fallback for
     *   magic users needs `CanUnitUseStaff` and is not ported)
     *
     * Gives a target without a pre-battle weapon its equipped weapon.
     */
    public fun battleUnitTargetSetEquippedWeapon(bu: BattleUnit) {
        if (bu.weaponBefore != 0) return
        bu.weaponBefore = loader.getUnitEquippedWeapon(bu.bytes, 0)
    }

    private fun generateCommon(src: ByteArray, actorOff: Int, targetOff: Int) {
        val actor = battle.actor
        val target = battle.target
        battle.initBattleUnit(actor, src, actorOff)
        battle.initBattleUnit(target, src, targetOff)
        battle.stats.range = abs(actor.unitX - target.unitX) + abs(actor.unitY - target.unitY)
        if ((battle.stats.config and BattleStats.CONFIG_BALLISTA) != 0) {
            battle.setBattleUnitWeaponBallista(actor)
        } else {
            battle.setBattleUnitWeapon(actor, BmBattle.BU_ISLOT_AUTO)
        }
        battle.setBattleUnitWeapon(target, BmBattle.BU_ISLOT_AUTO)
        battle.battleInitTargetCanCounter()
        battle.battleApplyWeaponTriangleEffect(actor, target)
        battle.setBattleUnitTerrainBonusesAuto(actor)
        battle.setBattleUnitTerrainBonusesAuto(target)
        battleGenerate()
    }

    /** `BattleRoll1RN(threshold, FALSE)`. */
    private fun battleRoll1Rn(threshold: Int): Boolean =
        (battle.stats.config and BattleStats.CONFIG_SIMULATE) == 0 &&
            mechanics.battleRolls.skill(rng, threshold)

    private fun getItemAfterUse(item: Int): Int {
        if ((items.getItemAttributes(item) and ItemInfo.IA_UNBREAKABLE) != 0) return item
        val used = item - (1 shl 8)
        return if (used < (1 shl 8)) 0 else used
    }

    private fun curHp(bu: BattleUnit): Int = s8(bu.bytes, OFF_CUR_HP)

    private fun setCurHp(bu: BattleUnit, v: Int) = put8(bu.bytes, OFF_CUR_HP, v)

    private fun addAttributes(index: Int, bits: Int) {
        hitArray[index] = hitArray[index] or bits
    }

    private fun addInfo(index: Int, bits: Int) {
        hitArray[index] = hitArray[index] or (bits shl 19)
    }

    private fun setInfo(index: Int, bits: Int) {
        hitArray[index] = (hitArray[index] and (0x1F shl 19).inv()) or (bits shl 19)
    }

    /** `struct BattleHit` field helpers and constants. */
    public companion object {
        /** `BATTLE_HIT_MAX`. */
        public const val BATTLE_HIT_MAX: Int = 7

        /** `BATTLE_MAX_DAMAGE`. */
        public const val BATTLE_MAX_DAMAGE: Int = 127

        /** `BATTLE_HIT_INFO_BEGIN`. */
        public const val INFO_BEGIN: Int = 1 shl 0

        /** `BATTLE_HIT_INFO_FINISHES`. */
        public const val INFO_FINISHES: Int = 1 shl 1

        /** `BATTLE_HIT_INFO_KILLS_TARGET`. */
        public const val INFO_KILLS_TARGET: Int = 1 shl 2

        /** `BATTLE_HIT_INFO_RETALIATION`. */
        public const val INFO_RETALIATION: Int = 1 shl 3

        /** `BATTLE_HIT_INFO_END`. */
        public const val INFO_END: Int = 1 shl 4

        /** `BATTLE_HIT_ATTR_CRIT`. */
        public const val ATTR_CRIT: Int = 1 shl 0

        /** `BATTLE_HIT_ATTR_MISS`. */
        public const val ATTR_MISS: Int = 1 shl 1

        /** Packs a hit word. */
        public fun hit(attributes: Int, info: Int, hpChange: Int): Int =
            (attributes and 0x7FFFF) or ((info and 0x1F) shl 19) or (hpChange shl 24)

        /** The `attributes` bit field (bits 0..18) of hit word [w]. */
        public fun attributes(w: Int): Int = w and 0x7FFFF

        /** The `info` bit field (bits 19..23) of hit word [w]. */
        public fun info(w: Int): Int = (w ushr 19) and 0x1F

        /** The signed `hpChange` (bits 24..31) of hit word [w]. */
        public fun hpChange(w: Int): Int = w shr 24

        private const val ATTR_POISON = 1 shl 6
        private const val ATTR_DEVIL = 1 shl 7
        private const val ATTR_HPSTEAL = 1 shl 8
        private const val ATTR_HPHALVE = 1 shl 9
        private const val ATTR_PETRIFY = 1 shl 13
        private const val WPN_EFFECT_POISON = 1
        private const val WPN_EFFECT_HPDRAIN = 2
        private const val WPN_EFFECT_HPHALVE = 3
        private const val WPN_EFFECT_DEVIL = 4
        private const val WPN_EFFECT_PETRIFY = 5
        private const val STATUS_POISON = 1
        private const val STATUS_PETRIFY = 11
        private const val STATUS_13 = 13
        private const val ITYPE_STAFF = 4
        private const val CLASS_DEMON_KING = 0x66
        private const val US_HIDDEN = 1 shl 0
        private const val US_UNSELECTABLE = 1 shl 1
        private const val US_DEAD = 1 shl 2
        private const val ITEM_BACKUP_NONE = 0xFFFF
        private const val OFF_CLASS = 0x04
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_MAX_HP = 0x12
        private const val OFF_CUR_HP = 0x13
        private const val OFF_LCK = 0x19
        private const val OFF_ITEMS = 0x1E
    }
}
