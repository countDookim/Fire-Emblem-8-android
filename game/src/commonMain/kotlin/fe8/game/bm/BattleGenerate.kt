package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import kotlin.math.abs

/**
 * The scripted and event battle generation of `src/bmbattle.c`, `src/eventscr3.c` and
 * `src/eventscr.c`: the `gBattleHitArray` / `gActionData.scriptedBattleHits` state,
 * `SetScriptedBattle`, `BattleGenerate`, `BattleUnwindScripted` and the event-side
 * `EvtBattleGenerateReal` and `StartEventBattle` set-up, on the battle units of [battle]. A
 * scripted battle is one whose hits (who strikes, hit / miss / crit, damage) a map event dictates
 * instead of the random rolls.
 *
 * Hits are `struct BattleHit` words: `attributes` in bits 0..18, `info` in bits 19..23 and the
 * signed `hpChange` in bits 24..31. Both hit buffers are preallocated.
 *
 * Not ported: `ComputeBattleObstacleStats`, `PidStatsRecordBattleRes` / `PidStatsAddBattleAmt`
 * (save-file statistics), the light rune toggling and the staff / item effect battles. The exp
 * gain tail of `BattleGenerateRealInternal` is the hook [applyExpGains] (`BattleApplyExpGains`,
 * MC-14), the triangle attack neighbour scan the hook [checkTriangleAttack] (MC-8b).
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
     * `ItemBackupEvtBattle` (eventscr3.c): the actor's first item, saved while an event battle
     * swaps in another weapon (the `item` argument of `StartEventBattle`), or [ITEM_BACKUP_NONE].
     * [startEventBattle] resets it; the swap is not ported.
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
     * Hook for `BattleApplyExpGains` (bmbattle.c, MC-14), which the real battle runs after the
     * hits when the target is a unit. Stand-in until MC-14: does nothing, so exp, the exp
     * gain and level-up fields of the battle units are not generated.
     */
    public var applyExpGains: () -> Unit = {}

    /**
     * Hook for `BattleCheckTriangleAttack` (bmbattle.c, MC-8b): whether two units of the
     * attacker's army stand next to the defender. Stand-in until MC-8b: always false.
     */
    public var checkTriangleAttack: (attacker: BattleUnit, defender: BattleUnit) -> Boolean =
        { _, _ -> false }

    /** `BattleGetBattleUnitOrder` / `BattleGetFollowUpOrder` out parameter `outAttacker`. */
    private var orderAttacker: BattleUnit = battle.actor

    /** `BattleGetBattleUnitOrder` / `BattleGetFollowUpOrder` out parameter `outDefender`. */
    private var orderDefender: BattleUnit = battle.target

    /**
     * Ported from src/bmbattle.c: ClearBattleHits
     *
     * Zeroes the [BATTLE_HIT_MAX] hits of [hitArray] and rewinds the iterator.
     */
    public fun clearBattleHits() {
        for (i in 0 until BATTLE_HIT_MAX) hitArray[i] = 0
        hitIterator = 0
    }

    /**
     * Ported from src/eventscr.c: GenerateScriptBattleHitFormEventQueue
     *
     * Turns the event queue (`gEventSlotQueue`, 4-byte entries at [queueOff] of [queue]: side,
     * hp change, attributes as a u16) into hits in [scriptHits], at most [count] of them
     * (`gEventSlots[0xD]`) or up to an entry starting with 0xFF, and terminates them with an END
     * hit. Side 1 marks a strike by the target (`BATTLE_HIT_INFO_RETALIATION`), the first hit
     * gets BEGIN. Returns [scriptHits]. Unlike the C, at most [BATTLE_HIT_MAX] hits are read.
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
     * Ported from src/bmbattle.c: BattleGenerateRealInternal
     *
     * Initialises the battle units from the units at [actorOff] / [targetOff] of [src], generates
     * the battle and fixes up the target's counter and equipped weapon. For a target that is a
     * unit (`index != 0`) the exp gains follow: they are the hook [applyExpGains] (MC-14), and
     * `PidStatsRecordBattleRes` / `PidStatsAddBattleAmt` (save statistics) are not ported.
     */
    public fun battleGenerateRealInternal(src: ByteArray, actorOff: Int, targetOff: Int) {
        generateCommon(src, actorOff, targetOff)
        battle.battleUnitTargetCheckCanCounter(battle.target)
        battleUnitTargetSetEquippedWeapon(battle.target)
        if ((battle.target.bytes[OFF_INDEX].toInt() and 0xFF) != 0) applyExpGains()
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateReal
     *
     * Sets the real battle config, then [battleGenerateRealInternal].
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
        val actorWeapon = loader.getUnitEquippedWeapon(src, actorOff)
        battle.actor.weaponBefore = actorWeapon
        battle.actor.weapon = actorWeapon
        val targetWeapon = loader.getUnitEquippedWeapon(src, targetOff)
        battle.target.weaponBefore = targetWeapon
        battle.target.weapon = targetWeapon
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
        val tableBytes = table.bytes
        val actorOffset = table.unitOffset(s8(battle.actor.bytes, OFF_INDEX))
        val targetOffset = table.unitOffset(s8(battle.target.bytes, OFF_INDEX))
        put8(
            tableBytes,
            actorOffset + OFF_CUR_HP,
            StructCodec.u8(battle.actor.bytes, OFF_CUR_HP),
        )
        put8(
            tableBytes,
            targetOffset + OFF_CUR_HP,
            StructCodec.u8(battle.target.bytes, OFF_CUR_HP),
        )
        scriptedBattleHits = null
        aiRefreshMap()
        if (itemBackupEvtBattle != ITEM_BACKUP_NONE) {
            StructCodec.put16(tableBytes, actorOffset + OFF_ITEMS, itemBackupEvtBattle)
        }
        for (offset in intArrayOf(actorOffset, targetOffset)) {
            val state = u32(tableBytes, offset + OFF_STATE) and US_UNSELECTABLE.inv()
            StructCodec.put32(tableBytes, offset + OFF_STATE, state)
        }
        if ((battle.stats.config and BattleStats.CONFIG_MAPANIMS) != 0) {
            for (offset in intArrayOf(actorOffset, targetOffset)) {
                if (StructCodec.u8(tableBytes, offset + OFF_CUR_HP) == 0) {
                    val state = u32(tableBytes, offset + OFF_STATE) or US_HIDDEN or US_DEAD
                    StructCodec.put32(tableBytes, offset + OFF_STATE, state)
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
     * them, else the random hits ([battleUnwind]). The obstacle battle (`target == NULL`) is not
     * ported.
     */
    public fun battleGenerate() {
        battle.computeBattleUnitStats(battle.actor, battle.target)
        battle.computeBattleUnitStats(battle.target, battle.actor)
        battle.computeBattleUnitEffectiveStats(battle.actor, battle.target)
        battle.computeBattleUnitEffectiveStats(battle.target, battle.actor)
        if ((battle.stats.config and BattleStats.CONFIG_REAL) != 0 && scriptedBattleHits != null) {
            battleUnwindScripted()
        } else {
            battleUnwind()
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleUnwind
     *
     * Generates the hits of a random battle: the actor strikes (a brave weapon twice), the target
     * retaliates unless the battle already ended, and the faster unit (by the follow-up speed
     * threshold) strikes once more. The first hit gets BEGIN, the entry after the last one END.
     */
    public fun battleUnwind() {
        clearBattleHits()
        battleGetBattleUnitOrder()
        addInfo(hitIterator, INFO_BEGIN)
        if (!battleGenerateRoundHits(orderAttacker, orderDefender)) {
            addAttributes(hitIterator, ATTR_RETALIATE)
            if (!battleGenerateRoundHits(orderDefender, orderAttacker) &&
                battleGetFollowUpOrder()
            ) {
                setAttributes(hitIterator, ATTR_FOLLOWUP)
                battleGenerateRoundHits(orderAttacker, orderDefender)
            }
        }
        addInfo(hitIterator, INFO_END)
    }

    /**
     * Ported from src/bmbattle.c: BattleGetBattleUnitOrder
     *
     * Sets the striking order to actor first (the out parameters of the C are [orderAttacker]
     * and [orderDefender]).
     */
    public fun battleGetBattleUnitOrder() {
        orderAttacker = battle.actor
        orderDefender = battle.target
    }

    /**
     * Ported from src/bmbattle.c: BattleGetFollowUpOrder
     *
     * Whether a follow-up strike happens: the target must not be faster than 250, the speeds must
     * differ by [BATTLE_FOLLOWUP_SPEED_THRESHOLD] or more, and the faster unit's pre-battle
     * weapon must not halve HP nor be the Monster stone. Sets the order to the faster unit
     * first.
     */
    public fun battleGetFollowUpOrder(): Boolean {
        val actor = battle.actor
        val target = battle.target
        if (target.battleSpeed > 250) return false
        if (abs(actor.battleSpeed - target.battleSpeed) < BATTLE_FOLLOWUP_SPEED_THRESHOLD) {
            return false
        }
        if (actor.battleSpeed > target.battleSpeed) {
            orderAttacker = actor
            orderDefender = target
        } else {
            orderAttacker = target
            orderDefender = actor
        }
        if (items.getItemWeaponEffect(orderAttacker.weaponBefore) == WPN_EFFECT_HPHALVE) {
            return false
        }
        if (items.getItemIndex(orderAttacker.weapon) == ITEM_MONSTER_STONE) return false
        return true
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateRoundHits
     *
     * Generates the hits of one strike round of [attacker] (none without a weapon; a brave
     * weapon twice), each carrying the round's attributes (low 16 bits only, as the C does).
     * True when a hit ended the battle. (Not verified: the no-weapon return, the brave second
     * strike and a battle ending in the actor's own round; no capture takes them.)
     */
    public fun battleGenerateRoundHits(attacker: BattleUnit, defender: BattleUnit): Boolean {
        if (attacker.weapon == 0) return false
        val attrs = attributes(hitArray[hitIterator]) and 0xFFFF
        val count = getBattleUnitHitCount(attacker)
        for (i in 0 until count) {
            addAttributes(hitIterator, attrs)
            if (battleGenerateHit(attacker, defender)) return true
        }
        return false
    }

    /**
     * Ported from src/bmbattle.c: GetBattleUnitHitCount
     *
     * The strikes per round: 1, doubled by a brave weapon.
     */
    public fun getBattleUnitHitCount(attacker: BattleUnit): Int =
        1 shl (if (battleCheckBraveEffect(attacker)) 1 else 0)

    /**
     * Transcribed from src/bmbattle.c: BattleCheckBraveEffect
     *   (not verified: MC-8b; no traced weapon is brave)
     *
     * Marks the current hit BRAVE and returns true for a brave weapon.
     */
    private fun battleCheckBraveEffect(attacker: BattleUnit): Boolean {
        if ((attacker.weaponAttributes and ItemInfo.IA_BRAVE) == 0) return false
        addAttributes(hitIterator, ATTR_BRAVE)
        return true
    }

    /**
     * Transcribed from src/bmbattle.c: BattleCheckSureShot
     *   (not verified: MC-8b; no traced attacker is a sniper)
     */
    private fun battleCheckSureShot(attacker: BattleUnit) {
        if ((hitArray[hitIterator] and (ATTR_SURESHOT or ATTR_PIERCE or ATTR_GREATSHLD)) != 0) {
            return
        }
        val cls = classNumber(attacker)
        if (cls == CLASS_SNIPER || cls == CLASS_SNIPER_F) {
            when (items.getItemIndex(attacker.weapon)) {
                ITEM_BALLISTA_REGULAR, ITEM_BALLISTA_LONG, ITEM_BALLISTA_KILLER -> {}
                else -> if (battleRoll1Rn(unitLevel(attacker), false, crit = false)) {
                    addAttributes(hitIterator, ATTR_SURESHOT)
                }
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleCheckPierce
     *   (not verified: MC-8b; no traced attacker is a wyvern knight)
     */
    private fun battleCheckPierce(attacker: BattleUnit) {
        if ((hitArray[hitIterator] and (ATTR_SURESHOT or ATTR_PIERCE or ATTR_GREATSHLD)) != 0) {
            return
        }
        val cls = classNumber(attacker)
        if (cls == CLASS_WYVERN_KNIGHT || cls == CLASS_WYVERN_KNIGHT_F) {
            if (battleRoll1Rn(unitLevel(attacker), false, crit = false)) {
                addAttributes(hitIterator, ATTR_PIERCE)
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleCheckGreatShield
     *   (not verified: MC-8b; no traced defender is a general)
     */
    private fun battleCheckGreatShield(attacker: BattleUnit, defender: BattleUnit) {
        if ((hitArray[hitIterator] and
                (ATTR_MISS or ATTR_SURESHOT or ATTR_PIERCE or ATTR_GREATSHLD)) != 0
        ) {
            return
        }
        if (items.getItemWeaponEffect(attacker.weapon) == WPN_EFFECT_POISON) return
        val cls = classNumber(defender)
        if (cls == CLASS_GENERAL || cls == CLASS_GENERAL_F) {
            if (battleRoll1Rn(unitLevel(attacker), false, crit = false)) {
                addAttributes(hitIterator, ATTR_GREATSHLD)
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleCheckSilencer
     *   (not verified: MC-8b; no traced crit roll succeeds)
     *
     * Whether a critical hit is a silencer (instant kill) instead: never against the Demon King
     * nor the Necromancer of chapter 21, otherwise by the silencer rate.
     */
    private fun battleCheckSilencer(defender: BattleUnit): Boolean {
        val cls = classNumber(defender)
        if (cls == CLASS_DEMON_KING) return false
        if (cls == CLASS_NECROMANCER &&
            (playSt.chapterIndex == CHAPTER_E_21 || playSt.chapterIndex == CHAPTER_I_21)
        ) {
            return false
        }
        return battleRoll1Rn(battle.stats.silencerRate, false, crit = false)
    }

    /**
     * Transcribed from src/bmbattle.c: BattleCheckPetrify
     *   (not verified: MC-8b; no traced weapon is the Monster stone)
     */
    private fun battleCheckPetrify(attacker: BattleUnit) {
        if (items.getItemIndex(attacker.weapon) == ITEM_MONSTER_STONE) battle.stats.damage = 0
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHitAttributes
     *
     * Rolls the current hit: sure shot, hit (2 RN; a miss ends it), great shield, pierce, damage
     * (attack minus defense, none for a great shield), crit (silencer or triple damage), the
     * damage clamped to 0..[BATTLE_MAX_DAMAGE] and the petrify check. Marks [attacker] as having
     * dealt damage. The checks of the C are MC-8b; the branches no capture takes are marked there.
     */
    public fun battleGenerateHitAttributes(attacker: BattleUnit, defender: BattleUnit) {
        val stats = battle.stats
        stats.damage = 0
        battleCheckSureShot(attacker)
        if ((hitArray[hitIterator] and ATTR_SURESHOT) == 0) {
            if (!battleRoll2Rn(stats.hitRate, true)) {
                addAttributes(hitIterator, ATTR_MISS)
                return
            }
        }
        val attack = stats.attack.toShort().toInt()
        var defense = stats.defense.toShort().toInt()
        battleCheckGreatShield(attacker, defender)
        if ((hitArray[hitIterator] and ATTR_GREATSHLD) == 0) battleCheckPierce(attacker)
        if ((hitArray[hitIterator] and ATTR_PIERCE) != 0) defense = 0
        stats.damage = attack - defense
        if ((hitArray[hitIterator] and ATTR_GREATSHLD) != 0) stats.damage = 0
        if (battleRoll1Rn(stats.critRate, false, crit = true)) {
            if (battleCheckSilencer(defender)) {
                addAttributes(hitIterator, ATTR_SILENCER)
                stats.damage = BATTLE_MAX_DAMAGE
                hitArray[hitIterator] = hitArray[hitIterator] and ATTR_GREATSHLD.inv()
            } else {
                addAttributes(hitIterator, ATTR_CRIT)
                stats.damage = stats.damage * 3
            }
        }
        if (stats.damage > BATTLE_MAX_DAMAGE) stats.damage = BATTLE_MAX_DAMAGE
        if (stats.damage < 0) stats.damage = 0
        battleCheckPetrify(attacker)
        if (stats.damage != 0) attacker.nonZeroDamage = 1
    }

    /**
     * Transcribed from src/bmbattle.c: BattleGenerateHitTriangleAttack
     *   (not verified: MC-8b; no traced attacker is a pegasus or armor class)
     *
     * A triangle attack (two allies next to the defender, see [checkTriangleAttack]) on the first
     * hit of a range 1 battle makes it hit and crit for sure.
     */
    private fun battleGenerateHitTriangleAttack(attacker: BattleUnit, defender: BattleUnit) {
        val stats = battle.stats
        val attributes = rom.u32(u32(attacker.bytes, OFF_CHARACTER) + 0x28) or
            rom.u32(u32(attacker.bytes, OFF_CLASS) + 0x28)
        if ((attributes and CA_TRIANGLEATTACK_ANY) == 0) return
        if (stats.range != 1) return
        if ((info(hitArray[hitIterator]) and INFO_BEGIN) == 0) return
        if (attacker.unitStatusIndex == STATUS_BERSERK) return
        if ((stats.config and BattleStats.CONFIG_ARENA) != 0) return
        if (!checkTriangleAttack(attacker, defender)) return
        addAttributes(hitIterator, ATTR_TATTACK)
        stats.critRate = 100
        stats.hitRate = 100
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHit
     *
     * Generates one hit of [attacker] on [defender] at the iterator: updates the stats, rolls
     * the attributes, applies the effects and advances the iterator. A strike by the target is
     * flagged RETALIATION. A hit that leaves a unit at 0 HP (FINISHES, plus KILLS_TARGET when the
     * target died) or petrifies the defender ends the battle: true. (Not verified: the petrify
     * ending and the actor dying; no capture takes them.)
     */
    public fun battleGenerateHit(attacker: BattleUnit, defender: BattleUnit): Boolean {
        if (attacker === battle.target) addInfo(hitIterator, INFO_RETALIATION)
        battleUpdateBattleStats(attacker, defender)
        battleGenerateHitTriangleAttack(attacker, defender)
        battleGenerateHitAttributes(attacker, defender)
        battleGenerateHitEffects(attacker, defender)
        if (curHp(attacker) == 0 || curHp(defender) == 0) {
            attacker.wexpMultiplier++
            addInfo(hitIterator, INFO_FINISHES)
            if (curHp(battle.target) != 0) {
                hitIterator++
                return true
            }
            addInfo(hitIterator, INFO_KILLS_TARGET)
            hitIterator++
            return true
        } else if (defender.statusOut == STATUS_PETRIFY || defender.statusOut == STATUS_13) {
            addInfo(hitIterator, INFO_FINISHES)
            hitIterator++
            return true
        }
        hitIterator++
        return false
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHitScriptedDamage
     *
     * Sets `stats.damage` for the scripted hit under the iterator: 0 for a miss; otherwise the
     * hit's `hpChange` as a fixed damage, or when that is 0 attack minus defense (tripled for a
     * crit); capped to 0..[BATTLE_MAX_DAMAGE]. Marks [unit] (the striker) as having dealt damage
     * when the result is not 0.
     */
    public fun battleGenerateHitScriptedDamage(unit: BattleUnit) {
        val stats = battle.stats
        stats.damage = 0
        val currentHit = hitArray[hitIterator]
        if ((attributes(currentHit) and ATTR_MISS) == 0) {
            if (hpChange(currentHit) == 0) {
                stats.damage = stats.attack - stats.defense
                if ((attributes(currentHit) and ATTR_CRIT) != 0) stats.damage = 3 * stats.damage
            } else {
                stats.damage = hpChange(currentHit)
            }
            if (stats.damage > BATTLE_MAX_DAMAGE) stats.damage = BATTLE_MAX_DAMAGE
            if (stats.damage < 0) stats.damage = 0
            if (stats.damage != 0) unit.nonZeroDamage = 1
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleUnwindScripted
     *
     * Copies [scriptedBattleHits] into [hitArray] up to and including the END hit, then replays
     * every hit on the battle units (stats, damage, effects) until the END hit or a hit that ends
     * the battle: a unit reaching 0 HP (FINISHES, plus KILLS_TARGET when the target died) or a
     * petrified defender (FINISHES). That hit gets a new END hit after it, and the striker one
     * more `wexpMultiplier`. Clears [scriptedBattleHits] afterwards.
     */
    public fun battleUnwindScripted() {
        val sourceHits = checkNotNull(scriptedBattleHits)
        var i = 0
        while ((info(sourceHits[i]) and INFO_END) == 0) {
            hitArray[i] = sourceHits[i]
            i++
        }
        hitArray[i] = sourceHits[i]

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
        val stats = battle.stats
        stats.attack = attacker.battleAttack
        stats.defense = defender.battleDefense
        stats.hitRate = attacker.battleEffectiveHitRate
        stats.critRate = attacker.battleEffectiveCritRate
        stats.silencerRate = attacker.battleSilencerRate
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateHitEffects
     *
     * Applies the hit under the iterator (not a miss): the weapon's effect (poison, HP halve,
     * devil, HP drain, petrify; poison, halve and petrify do not work on the Demon King), the
     * damage to the defender (or to the attacker for a devil weapon), the damage recorded in the
     * hit's `hpChange` for the animation, and the weapon's use.
     */
    public fun battleGenerateHitEffects(attacker: BattleUnit, defender: BattleUnit) {
        val stats = battle.stats
        val hitIndex = hitIterator
        attacker.wexpMultiplier++
        val effect = items.getItemWeaponEffect(attacker.weapon)
        // The Demon King is immune to the poison, HP-halve and petrify weapon effects.
        val isDemonKing = rom.u8(u32(defender.bytes, OFF_CLASS) + 4) == CLASS_DEMON_KING

        if ((attributes(hitArray[hitIndex]) and ATTR_MISS) == 0) {
            if (!isDemonKing) {
                when (effect) {
                    WPN_EFFECT_POISON -> {
                        defender.statusOut = STATUS_POISON
                        addAttributes(hitIndex, ATTR_POISON)
                        // A petrified defender is no longer petrified once poisoned, so it
                        // is made selectable (not grayed out) again.
                        if (defender.unitStatusIndex == STATUS_PETRIFY ||
                            defender.unitStatusIndex == STATUS_13
                        ) {
                            val state = u32(defender.bytes, OFF_STATE) and US_UNSELECTABLE.inv()
                            StructCodec.put32(defender.bytes, OFF_STATE, state)
                        }
                    }
                    WPN_EFFECT_HPHALVE -> addAttributes(hitIndex, ATTR_HPHALVE)
                }
            }

            // A devil weapon rolls against 31 - luck; on success it hurts the attacker instead.
            if (effect == WPN_EFFECT_DEVIL &&
                battleRoll1Rn(31 - s8(attacker.bytes, OFF_LCK), false, crit = false)
            ) {
                addAttributes(hitIndex, ATTR_DEVIL)
                setCurHp(attacker, maxOf(curHp(attacker) - stats.damage, 0))
            } else {
                if (stats.damage > curHp(defender)) stats.damage = curHp(defender)
                setCurHp(defender, maxOf(curHp(defender) - stats.damage, 0))
            }

            if (effect == WPN_EFFECT_HPDRAIN) {
                if (s8(attacker.bytes, OFF_MAX_HP) < curHp(attacker) + stats.damage) {
                    setCurHp(attacker, s8(attacker.bytes, OFF_MAX_HP))
                } else {
                    setCurHp(attacker, curHp(attacker) + stats.damage)
                }
                addAttributes(hitIndex, ATTR_HPSTEAL)
            }

            if (!isDemonKing && effect == WPN_EFFECT_PETRIFY) {
                // A petrify hit on a unit of the army whose phase it is gives status 13, any
                // other target gets plain petrify; no change in other (non-army) phases.
                val faction = playSt.faction
                if (faction == UnitLoader.FACTION_BLUE || faction == UnitLoader.FACTION_RED ||
                    faction == UnitLoader.FACTION_GREEN
                ) {
                    val sameSide = (s8(defender.bytes, OFF_INDEX) and 0xC0) == faction
                    defender.statusOut = if (sameSide) STATUS_13 else STATUS_PETRIFY
                }
                addAttributes(hitIndex, ATTR_PETRIFY)
            }
        }

        // Record the damage dealt in the hit's `hpChange` (bits 24..31).
        hitArray[hitIndex] = (hitArray[hitIndex] and 0x00FFFFFF) or (stats.damage shl 24)

        // The weapon loses a use on a hit, and on a miss only for uncounterable or magic weapons.
        if ((attributes(hitArray[hitIndex]) and ATTR_MISS) == 0 ||
            (attacker.weaponAttributes and
                (ItemInfo.IA_UNCOUNTERABLE or ItemInfo.IA_MAGIC)) != 0
        ) {
            attacker.weapon = getItemAfterUse(attacker.weapon)
            if (attacker.weapon == 0) attacker.weaponBroke = 1
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleUnitTargetSetEquippedWeapon
     *
     * Gives a unit without a pre-battle weapon (it could not counter) its equipped weapon, so the
     * battle animation can still show it. The C's fallback to a usable staff for units with a
     * magic rank needs `CanUnitUseStaff` and is not ported.
     */
    public fun battleUnitTargetSetEquippedWeapon(unit: BattleUnit) {
        if (unit.weaponBefore != 0) return
        unit.weaponBefore = loader.getUnitEquippedWeapon(unit.bytes, 0)
    }

    /**
     * The shared part of the real battle generators: copies both units in, sets the range, the
     * weapons (ballista or auto slot), counter, weapon triangle and terrain bonuses, then
     * [battleGenerate] for the hits.
     */
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

    /**
     * Ported from src/bmbattle.c: BattleRoll1RN
     *
     * A single random roll that succeeds with probability [threshold] percent; [simResult] in a
     * simulated (forecast) battle. [crit] selects the crit roll of the active
     * [Mechanics.battleRolls] (the C's `Roll1RN` for the crit rate), else its skill roll.
     */
    private fun battleRoll1Rn(threshold: Int, simResult: Boolean, crit: Boolean): Boolean {
        if ((battle.stats.config and BattleStats.CONFIG_SIMULATE) != 0) return simResult
        val rolls = mechanics.battleRolls
        return if (crit) rolls.crit(rng, threshold) else rolls.skill(rng, threshold)
    }

    /**
     * Ported from src/bmbattle.c: BattleRoll2RN
     *
     * The hit roll (`Roll2RN`: two random numbers averaged) of the active
     * [Mechanics.battleRolls]; [simResult] in a simulated battle.
     */
    private fun battleRoll2Rn(threshold: Int, simResult: Boolean): Boolean {
        if ((battle.stats.config and BattleStats.CONFIG_SIMULATE) != 0) return simResult
        return mechanics.battleRolls.hit(rng, threshold)
    }

    /**
     * Ported from src/bmitem.c: GetItemAfterUse
     *
     * The item word after one use: the uses (high byte) drop by one, and the item is gone (0)
     * when none are left. Unbreakable items are unchanged.
     */
    private fun getItemAfterUse(item: Int): Int {
        if ((items.getItemAttributes(item) and ItemInfo.IA_UNBREAKABLE) != 0) return item
        val used = item - (1 shl 8)
        return if (used < (1 shl 8)) 0 else used
    }

    /** `UNIT_CLASS_ID`: the class number of the battle unit's class. */
    private fun classNumber(unit: BattleUnit): Int = rom.u8(u32(unit.bytes, OFF_CLASS) + 4)

    /** The `level` of the battle unit's unit struct. */
    private fun unitLevel(unit: BattleUnit): Int = unit.bytes[OFF_LEVEL].toInt()

    /** The current HP of [unit] (signed byte). */
    private fun curHp(unit: BattleUnit): Int = s8(unit.bytes, OFF_CUR_HP)

    /** Stores [hp] as the current HP of [unit]. */
    private fun setCurHp(unit: BattleUnit, hp: Int) = put8(unit.bytes, OFF_CUR_HP, hp)

    /** ORs [bits] (`ATTR_*`) into the attribute bits of the hit at [index]. */
    private fun addAttributes(index: Int, bits: Int) {
        hitArray[index] = hitArray[index] or bits
    }

    /** Replaces the attribute bits of the hit at [index] with [bits] (`ATTR_*`). */
    private fun setAttributes(index: Int, bits: Int) {
        hitArray[index] = (hitArray[index] and 0x7FFFF.inv()) or bits
    }

    /** ORs [bits] (`INFO_*`) into the info field of the hit at [index]. */
    private fun addInfo(index: Int, bits: Int) {
        hitArray[index] = hitArray[index] or (bits shl 19)
    }

    /** Replaces the info field of the hit at [index] with [bits] (`INFO_*`). */
    private fun setInfo(index: Int, bits: Int) {
        hitArray[index] = (hitArray[index] and (0x1F shl 19).inv()) or (bits shl 19)
    }

    /** `struct BattleHit` field helpers and constants. */
    public companion object {
        /** `BATTLE_HIT_MAX`. */
        public const val BATTLE_HIT_MAX: Int = 7

        /** `BATTLE_MAX_DAMAGE`. */
        public const val BATTLE_MAX_DAMAGE: Int = 127

        /** `BATTLE_HIT_INFO_BEGIN`: the first hit of the battle. */
        public const val INFO_BEGIN: Int = 1 shl 0

        /** `BATTLE_HIT_INFO_FINISHES`: this hit ends the battle (kill or petrify). */
        public const val INFO_FINISHES: Int = 1 shl 1

        /** `BATTLE_HIT_INFO_KILLS_TARGET`: this hit leaves the target at 0 HP. */
        public const val INFO_KILLS_TARGET: Int = 1 shl 2

        /** `BATTLE_HIT_INFO_RETALIATION`: the target strikes the actor (a counter-attack). */
        public const val INFO_RETALIATION: Int = 1 shl 3

        /** `BATTLE_HIT_INFO_END`: the terminating entry; it is not a hit. */
        public const val INFO_END: Int = 1 shl 4

        /** `BATTLE_HIT_ATTR_CRIT`: the hit is a critical hit (triple damage). */
        public const val ATTR_CRIT: Int = 1 shl 0

        /** `BATTLE_HIT_ATTR_MISS`: the strike misses. */
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

        /** `BATTLE_FOLLOWUP_SPEED_THRESHOLD`: the speed lead for a second strike. */
        public const val BATTLE_FOLLOWUP_SPEED_THRESHOLD: Int = 4

        private const val ATTR_FOLLOWUP = 1 shl 2
        private const val ATTR_RETALIATE = 1 shl 3
        private const val ATTR_BRAVE = 1 shl 4
        private const val ATTR_TATTACK = 1 shl 10
        private const val ATTR_SILENCER = 1 shl 11
        private const val ATTR_SURESHOT = 1 shl 14
        private const val ATTR_GREATSHLD = 1 shl 15
        private const val ATTR_PIERCE = 1 shl 16
        private const val CLASS_GENERAL = 0x0B
        private const val CLASS_GENERAL_F = 0x0C
        private const val CLASS_SNIPER = 0x1B
        private const val CLASS_SNIPER_F = 0x1C
        private const val CLASS_WYVERN_KNIGHT = 0x23
        private const val CLASS_WYVERN_KNIGHT_F = 0x24
        private const val CLASS_NECROMANCER = 0x4F
        private const val ITEM_BALLISTA_REGULAR = 0x35
        private const val ITEM_BALLISTA_LONG = 0x36
        private const val ITEM_BALLISTA_KILLER = 0x37
        private const val ITEM_MONSTER_STONE = 0xB5
        private const val CHAPTER_E_21 = 0x15
        private const val CHAPTER_I_21 = 0x22
        private const val CA_TRIANGLEATTACK_ANY = (1 shl 21) or (1 shl 22)
        private const val STATUS_BERSERK = 4
        private const val OFF_CHARACTER = 0x00
        private const val OFF_LEVEL = 0x08
        // More `BATTLE_HIT_ATTR_*` bits, set by the weapon effects of the hit.
        private const val ATTR_POISON = 1 shl 6
        private const val ATTR_DEVIL = 1 shl 7
        private const val ATTR_HPSTEAL = 1 shl 8
        private const val ATTR_HPHALVE = 1 shl 9
        private const val ATTR_PETRIFY = 1 shl 13
        // `WPN_EFFECT_*` (`ItemData` 0x1F `weaponEffectId`) and `UNIT_STATUS_*` ids;
        // `UNIT_STATUS_13` has no decomp name and is treated like petrify.
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
        // `US_*` unit state bits.
        private const val US_HIDDEN = 1 shl 0
        private const val US_UNSELECTABLE = 1 shl 1
        private const val US_DEAD = 1 shl 2
        private const val ITEM_BACKUP_NONE = 0xFFFF // the C's "no item saved" literal
        // `struct Unit` field offsets (include/bmunit.h).
        private const val OFF_CLASS = 0x04
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_MAX_HP = 0x12
        private const val OFF_CUR_HP = 0x13
        private const val OFF_LCK = 0x19
        private const val OFF_ITEMS = 0x1E
    }
}
