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
 * gain tail of `BattleGenerateRealInternal` is [battleApplyExpGains], whose level-up check is the
 * hook [checkBattleUnitLevelUp] (MC-17). The triangle attack neighbour scan needs [bmMap] and
 * [unitTable].
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

    /** The level-up check of one battle unit, a `fun interface` so that no lambda is boxed. */
    public fun interface LevelUpCheck {
        /** Runs `CheckBattleUnitLevelUp` on [unit]. */
        public fun check(unit: BattleUnit)
    }

    /**
     * Hook for `CheckBattleUnitLevelUp` (bmbattle.c, MC-17), which [battleApplyExpGains] runs on
     * both units after the exp is added. Stand-in until MC-17: level and exp bookkeeping only,
     * no stat gains (RNG not advanced). For a unit that can gain levels and has 100 exp it takes
     * 100 exp, adds a level and, at the maximum level (10 for `CA_MAXLEVEL10`, else 20),
     * subtracts the leftover exp from `expGain` and disables the exp. The RNG draws the stat
     * gains would make are missing, so RNG-dependent behaviour after a level-up diverges until
     * MC-17. Not verified: no capture levels up.
     */
    public var checkBattleUnitLevelUp: LevelUpCheck = LevelUpCheck { unit ->
        if (canBattleUnitGainLevels(unit) && unit.exp >= 100) {
            unit.exp -= 100
            val level = unit.bytes[OFF_LEVEL] + 1
            unit.bytes[OFF_LEVEL] = level.toByte()
            val maxLevel = if ((cAttributes(unit) and CA_MAXLEVEL10) != 0) 10 else 20
            if (level == maxLevel) {
                unit.expGain -= unit.exp
                unit.exp = UNIT_EXP_DISABLED
            }
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleApplyExpGains
     *
     * Unless both units are player units or the map is an extra (link / skirmish) map, sets
     * each unit's [BattleUnit.expGain], adds it to the unit's exp and runs the level-up check.
     */
    public fun battleApplyExpGains() {
        val actor = battle.actor
        val target = battle.target
        if (unitFaction(actor) == FACTION_BLUE && unitFaction(target) == FACTION_BLUE) return
        if ((playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP) != 0) return
        actor.expGain = getBattleUnitExpGain(actor, target)
        target.expGain = getBattleUnitExpGain(target, actor)
        actor.exp += actor.expGain
        target.exp += target.expGain
        checkBattleUnitLevelUp.check(actor)
        checkBattleUnitLevelUp.check(target)
    }

    /** `UNIT_FACTION`: the army bits of the unit's index. */
    private fun unitFaction(unit: BattleUnit): Int = unit.bytes[OFF_INDEX].toInt() and 0xC0

    /** `unit->exp` (u8, `UNIT_EXP_DISABLED` is 0xFF). */
    private var BattleUnit.exp: Int
        get() = bytes[OFF_EXP].toInt() and 0xFF
        set(value) {
            bytes[OFF_EXP] = value.toByte()
        }

    /** `UNIT_CATTRIBUTES`: the `CA_*` flags of the unit's character and class combined. */
    private fun cAttributes(unit: BattleUnit): Int =
        rom.u32(u32(unit.bytes, OFF_CHARACTER) + 0x28) or rom.u32(u32(unit.bytes, OFF_CLASS) + 0x28)

    /** `unit->pClassData->classRelativePower` (s8). */
    private fun relativePower(classAddr: Int): Int = rom.u8(classAddr + 0x1A).toByte().toInt()

    /**
     * Ported from src/bmbattle.c: CanBattleUnitGainLevels
     *
     * Whether the unit can gain exp: always in a link arena, else only a player unit whose exp
     * is not disabled.
     */
    public fun canBattleUnitGainLevels(unit: BattleUnit): Boolean {
        if (battle.isLinkArena) return true
        if (unit.exp == UNIT_EXP_DISABLED) return false
        return unitFaction(unit) == FACTION_BLUE
    }

    /**
     * Ported from src/bmbattle.c: GetUnitExpLevel
     *
     * The level, plus 20 for a promoted class.
     */
    public fun getUnitExpLevel(unit: BattleUnit): Int {
        val level = unit.bytes[OFF_LEVEL].toInt()
        return if ((cAttributes(unit) and CA_PROMOTED) != 0) level + 20 else level
    }

    /**
     * Ported from src/bmbattle.c: GetUnitRoundExp
     *
     * The exp for taking part: 31 less the level lead over [target] (floored at 0), divided by
     * the actor class's relative power.
     */
    public fun getUnitRoundExp(actor: BattleUnit, target: BattleUnit): Int {
        var expLevel = 31 - (getUnitExpLevel(actor) - getUnitExpLevel(target))
        if (expLevel < 0) expLevel = 0
        return expLevel / relativePower(u32(actor.bytes, OFF_CLASS))
    }

    /**
     * Ported from src/bmbattle.c: GetUnitPowerLevel
     *
     * Level times the class's relative power, plus 20 times the promotion class's for a
     * promoted unit.
     */
    public fun getUnitPowerLevel(unit: BattleUnit): Int {
        val classAddr = u32(unit.bytes, OFF_CLASS)
        var result = unit.bytes[OFF_LEVEL] * relativePower(classAddr)
        val promotion = rom.u8(classAddr + 0x05)
        if ((cAttributes(unit) and CA_PROMOTED) != 0 && promotion != 0) {
            result += 20 * relativePower(loader.classData(promotion))
        }
        return result
    }

    /**
     * Ported from src/bmbattle.c: GetUnitClassKillExpBonus
     *
     * The kill bonus of the target: 20 for a thief, 40 for a boss, 40 for the `ENTOUMBED` class.
     */
    public fun getUnitClassKillExpBonus(target: BattleUnit): Int {
        var result = 0
        val attributes = cAttributes(target)
        if ((attributes and CA_THIEF) != 0) result += 20
        if ((attributes and CA_BOSS) != 0) result += 40
        if (rom.u8(u32(target.bytes, OFF_CLASS) + 0x04) == CLASS_ENTOUMBED) result += 40
        return result
    }

    /**
     * Transcribed from src/bmbattle.c: GetUnitExpMultiplier
     *   (not verified: no assassin in the captures, and the kill bonus it multiplies is 0 there)
     *
     * 2 for an assassin when a hit of the battle was a Silencer kill, else 1.
     */
    public fun getUnitExpMultiplier(actor: BattleUnit): Int {
        if ((cAttributes(actor) and CA_ASSASSIN) == 0) return 1
        for (i in 0 until BATTLE_HIT_MAX) {
            if ((attributes(hitArray[i]) and ATTR_SILENCER) != 0) return 2
        }
        return 1
    }

    /**
     * Ported from src/bmbattle.c: GetUnitKillExpBonus
     *
     * 0 unless [target] died, else 20 plus the power level lead of the target (half the actor's
     * level counts against it in the prologue chapters), the class kill bonus, times the
     * multiplier; never negative.
     */
    public fun getUnitKillExpBonus(actor: BattleUnit, target: BattleUnit): Int {
        if (target.bytes[OFF_CUR_HP].toInt() == 0) {
            var result = 20
            if (battle.isLinkArena || playSt.chapterModeIndex != 1) {
                result = getUnitPowerLevel(target) + 20 - getUnitPowerLevel(actor)
            } else {
                val targetPower = getUnitPowerLevel(target)
                val actorPower = getUnitPowerLevel(actor)
                result += if (targetPower <= actorPower) {
                    targetPower - actorPower / 2
                } else {
                    targetPower - actorPower
                }
            }
            result += getUnitClassKillExpBonus(target)
            result *= getUnitExpMultiplier(actor)
            return if (result < 0) 0 else result
        }
        return 0
    }

    /**
     * Ported from src/bmbattle.c: ModifyUnitSpecialExp
     *
     * Overrides [exp]: 50 for killing a Gorgon egg (0 for hitting one), 0 for killing the demon
     * king, 0 for a phantom actor. Returns the new value.
     */
    public fun modifyUnitSpecialExp(actor: BattleUnit, target: BattleUnit, exp: Int): Int {
        var result = exp
        val targetClass = rom.u8(u32(target.bytes, OFF_CLASS) + 0x04)
        if (targetClass == CLASS_GORGONEGG || targetClass == CLASS_GORGONEGG2) {
            result = if (target.bytes[OFF_CUR_HP].toInt() == 0) 50 else 0
        }
        if (targetClass == CLASS_DEMON_KING && target.bytes[OFF_CUR_HP].toInt() == 0) result = 0
        if (rom.u8(u32(actor.bytes, OFF_CLASS) + 0x04) == CLASS_PHANTOM) result = 0
        return result
    }

    /**
     * Ported from src/bmbattle.c: GetBattleUnitExpGain
     *
     * The exp [actor] earns: 0 when it cannot gain levels, is dead or fights a lethality-immune
     * unit, 1 when it dealt no damage, else the round exp plus the kill bonus clamped to 1..100
     * and adjusted by [modifyUnitSpecialExp].
     */
    public fun getBattleUnitExpGain(actor: BattleUnit, target: BattleUnit): Int {
        if (!canBattleUnitGainLevels(actor) || actor.bytes[OFF_CUR_HP].toInt() == 0 ||
            (cAttributes(target) and CA_NEGATE_LETHALITY) != 0
        ) {
            return 0
        }
        if (actor.nonZeroDamage == 0) return 1
        var result = getUnitRoundExp(actor, target) + getUnitKillExpBonus(actor, target)
        if (result > 100) result = 100
        if (result < 1) result = 1
        return modifyUnitSpecialExp(actor, target, result)
    }

    /** `gBmMapUnit`, set by the boot; without it no triangle attack is found. */
    public var bmMap: BmMap? = null

    /** The unit table `GetUnit` reads, set by the boot; without it no triangle attack is found. */
    public var unitTable: UnitTable? = null

    /**
     * Transcribed from src/bmbattle.c: BattleCheckTriangleAttack
     *   (not verified: no capture has a triangle attack)
     *
     * Whether two units of the attacker's army stand next to the [defender] (4 neighbours through
     * `gBmMapUnit`), awake, not petrified, not a female wyvern knight and with a triangle attack
     * class attribute the attacker also has. The C also stores the first two in
     * `gBattleStats.taUnitA/B`, which only the battle animation reads (M6-H): not kept here.
     * Neighbours off the map count as empty.
     */
    private fun battleCheckTriangleAttack(attacker: BattleUnit, defender: BattleUnit): Boolean {
        val map = bmMap ?: return false
        val table = unitTable ?: return false
        val triangleAttackAttr = CA_TRIANGLEATTACK_ANY and cAttributes(attacker)
        val x = defender.unitX
        val y = defender.unitY
        val faction = unitFaction(attacker)
        var count = 0
        for (i in 0 until 4) {
            val nx = x + TA_DX[i]
            val ny = y + TA_DY[i]
            if (nx < 0 || ny < 0 || nx >= map.sizeX || ny >= map.sizeY) continue
            val uId = map.unit[ny, nx]
            if (uId == 0) continue
            val offset = table.unitOffset(uId)
            if (offset < 0) continue
            if ((uId and 0xC0) != faction) continue
            val status = table.bytes[offset + OFF_STATUS].toInt() and 0xF
            if (status == STATUS_SLEEP || status == STATUS_PETRIFY || status == STATUS_13) continue
            val classAddr = u32(table.bytes, offset + OFF_CLASS)
            if (rom.u8(classAddr + 4) == CLASS_WYVERN_KNIGHT_F) continue
            val attrs = rom.u32(u32(table.bytes, offset + OFF_CHARACTER) + 0x28) or
                rom.u32(classAddr + 0x28)
            if ((attrs and triangleAttackAttr) != 0) count++
        }
        return count >= 2
    }

    /** `BattleGetBattleUnitOrder` / `BattleGetFollowUpOrder` out parameter `outAttacker`. */
    private var orderAttacker: BattleUnit = battle.actor

    /** The `buFirst` that [battleGetFollowUpOrder] sets: the unit that strikes first. */
    public val firstStriker: BattleUnit get() = orderAttacker

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
     * unit (`index != 0`) the exp gains follow: they are [battleApplyExpGains], and
     * `PidStatsRecordBattleRes` / `PidStatsAddBattleAmt` (save statistics) are not ported.
     */
    public fun battleGenerateRealInternal(src: ByteArray, actorOff: Int, targetOff: Int) {
        generateCommon(src, actorOff, targetOff)
        battle.battleUnitTargetCheckCanCounter(battle.target)
        battleUnitTargetSetEquippedWeapon(battle.target)
        if ((battle.target.bytes[OFF_INDEX].toInt() and 0xFF) != 0) battleApplyExpGains()
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
     * Ported from src/bmbattle.c: BattleCheckBraveEffect
     *
     * Marks the current hit BRAVE and returns true for a brave weapon. (Not verified: the brave
     * branch; no capture has a brave weapon, only the not-brave path is covered.)
     */
    private fun battleCheckBraveEffect(attacker: BattleUnit): Boolean {
        if ((attacker.weaponAttributes and ItemInfo.IA_BRAVE) == 0) return false
        addAttributes(hitIterator, ATTR_BRAVE)
        return true
    }

    /**
     * Ported from src/bmbattle.c: BattleCheckSureShot
     *
     * A sniper (not on a ballista) rolls the skill for a sure shot. (Not verified: the sniper
     * branch; no capture has a sniper, only the not-a-sniper path, which rolls nothing, is
     * covered.)
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
     * Ported from src/bmbattle.c: BattleCheckPierce
     *
     * A wyvern knight rolls the skill for a pierce. (Not verified: the wyvern knight branch; no
     * capture has one, only the path that rolls nothing is covered.)
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
     * Ported from src/bmbattle.c: BattleCheckGreatShield
     *
     * A general defender rolls the skill for a great shield. (Not verified: the general branch;
     * no capture has a general defender, only the path that rolls nothing is covered.)
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
     *   (not verified: no traced crit roll succeeds, so no mutation of it changes captured data)
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
     * Ported from src/bmbattle.c: BattleCheckPetrify
     *
     * The Monster stone deals no damage. (Not verified: the Monster stone branch; no capture
     * has one, only the damage-kept path is covered.)
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
     * dealt damage. The branches of the checks no capture takes are marked on each.
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
     *   (not verified: no traced attacker has a triangle attack class attribute, so the first
     *   check returns every time and no mutation of the rest, nor of the scan, changes data)
     *
     * A triangle attack (two allies next to the defender, see `BattleCheckTriangleAttack`) on the
     * first hit of a range 1 battle makes it hit and crit for sure.
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
        if (!battleCheckTriangleAttack(attacker, defender)) return
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
     * Ported from src/bmbattle.c: BattleGenerateSimulation
     *
     * Generates the battle of the forecast (no rolls: a hit always lands, a crit and every skill
     * never happens) for the units at [actorOff] / [targetOff] of [src], with the actor
     * standing on ([x], [y]), or on its own tile when both are negative, and striking with item
     * slot [actorWpnSlot].
     */
    public fun battleGenerateSimulation(
        src: ByteArray,
        actorOff: Int,
        targetOff: Int,
        x: Int,
        y: Int,
        actorWpnSlot: Int,
    ) {
        var px = x
        var py = y
        if (px < 0 && py < 0) {
            px = s8(src, actorOff + OFF_X_POS)
            py = s8(src, actorOff + OFF_Y_POS)
        }
        battle.stats.config = BattleStats.CONFIG_SIMULATE
        battleGenerateSimulationInternal(src, actorOff, targetOff, px, py, actorWpnSlot)
    }

    /**
     * Ported from src/bmbattle.c: BattleGenerateSimulationInternal
     *
     * The body of [battleGenerateSimulation] (`stats.config` already set): like the real battle
     * set-up, but with the actor moved to ([x], [y]) before the range is measured, and its
     * weapon from [actorWpnSlot]. The light runes are not toggled (not ported).
     * (Not verified: a position other than the actor's own, as the AI passes, is in no capture.)
     */
    public fun battleGenerateSimulationInternal(
        src: ByteArray,
        actorOff: Int,
        targetOff: Int,
        x: Int,
        y: Int,
        actorWpnSlot: Int,
    ) {
        battle.initBattleUnit(battle.actor, src, actorOff)
        battle.initBattleUnit(battle.target, src, targetOff)
        battle.actor.setUnitPos(x, y)
        generateRest(actorWpnSlot)
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
        generateRest(BmBattle.BU_ISLOT_AUTO)
    }

    /**
     * The part of the battle set-up after the units are initialised (and, for a simulation,
     * moved): range, weapons ([actorWpnSlot] for the actor unless ballista), counter, weapon
     * triangle, terrain bonuses, then [battleGenerate].
     */
    private fun generateRest(actorWpnSlot: Int) {
        val actor = battle.actor
        val target = battle.target
        battle.stats.range = abs(actor.unitX - target.unitX) + abs(actor.unitY - target.unitY)
        if ((battle.stats.config and BattleStats.CONFIG_BALLISTA) != 0) {
            battle.setBattleUnitWeaponBallista(actor)
        } else {
            battle.setBattleUnitWeapon(actor, actorWpnSlot)
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
        private const val OFF_EXP = 0x09
        private const val FACTION_BLUE = 0x00
        private const val UNIT_EXP_DISABLED = 0xFF
        private const val PLAY_FLAG_EXTRA_MAP = 1 shl 7
        private const val CA_THIEF = 1 shl 3
        private const val CA_PROMOTED = 1 shl 8
        private const val CA_MAXLEVEL10 = 1 shl 19
        private const val CA_BOSS = 1 shl 15
        private const val CA_NEGATE_LETHALITY = 1 shl 24
        private const val CA_ASSASSIN = 1 shl 25
        private const val CLASS_GORGONEGG2 = 0x34
        private const val CLASS_PHANTOM = 0x51
        private const val CLASS_ENTOUMBED = 0x53
        private const val CLASS_GORGONEGG = 0x62
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
        private const val OFF_STATUS = 0x30
        private const val STATUS_SLEEP = 2
        private val TA_DX = intArrayOf(-1, 0, 1, 0)
        private val TA_DY = intArrayOf(0, -1, 0, 1)
        private const val OFF_X_POS = 0x10
        private const val OFF_Y_POS = 0x11
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_MAX_HP = 0x12
        private const val OFF_CUR_HP = 0x13
        private const val OFF_LCK = 0x19
        private const val OFF_ITEMS = 0x1E
    }
}
