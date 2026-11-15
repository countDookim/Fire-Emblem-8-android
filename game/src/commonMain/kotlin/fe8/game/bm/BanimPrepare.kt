package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import kotlin.math.abs

/**
 * The battle animation set-up: `PrepareBattleGraphicsMaybe` (src/banim-ekrbattleintro.c), which
 * decides whether a battle is shown with battle animations and fills the `gBanim*` and `gEkr*`
 * EWRAM variables the animation reads, and the helpers it calls (src/banim-battleparse.c,
 * src/banim-ekrarena.c, src/banim-ekrdragonstatus.c, src/banim-ekrhensei.c). The two-element
 * arrays are indexed by side (0 left, 1 right). Tables are read from the ROM; nothing is
 * allocated.
 *
 * Not written: the unit-colour background of battle animations on (`GetBanimBackgroundIndex`) and
 * the draco zombie and demon king status of the left unit; both fail loudly.
 *
 * @param rom the ROM holding the tables.
 * @param playSt `gPlaySt` (chapter, weather).
 * @param bm `gBmSt` (camera, link arena bit).
 * @param battle `gBattleActor`, `gBattleTarget` and `gBattleStats`.
 * @param hitArray `gBattleHitArray` (hits packed as in `struct BattleHit`).
 * @param setup the set-up queries of EP-14.
 * @param items the item getters.
 * @param spells the spell association table.
 * @param chapters the chapter table.
 */
public class BanimPrepare(
    private val rom: RomView,
    private val playSt: PlaySt,
    private val bm: BmSt,
    private val battle: BmBattle,
    private val hitArray: IntArray,
    private val setup: BanimSetup,
    private val items: ItemInfo = ItemInfo(rom),
    private val spells: SpellAssoc = SpellAssoc(rom),
    private val chapters: ChapterData = ChapterData(rom),
) {
    /** `gBaArenaFlag` (EWRAM), see [setBanimArenaFlag]. */
    private var arenaFlag: Int = 0

    /** `gEkrDragonStatusLeft/Right` `.type`, `.attr`, `.unk08` (the other fields are pointers). */
    public val dragonStatus: IntArray = IntArray(DRAGON_STATUS_FIELDS * 2)

    /** `gBanimBG`. */
    public var banimBg: Int = 0

    /** `gEkrInitialHitSide`: the side that strikes first. */
    public var initialHitSide: Int = 0

    /** `gEkrSnowWeather`. */
    public var snowWeather: Int = 0

    /** `gEkrDistanceType` (`EKR_DISTANCE_*`). */
    public var distanceType: Int = 0

    /** `gBanimValid[2]`: whether the side has a fighter in the animation. */
    public val valid: IntArray = IntArray(2)

    /** `gBanimPositionIsEnemy[2]`. */
    public val positionIsEnemy: IntArray = IntArray(2)

    /** `gBanimIdx_bak[2]` (u16, stored as s16). */
    public val idxBak: IntArray = IntArray(2)

    /** `gBanimIdx[2]` (s16): the battle animation index per side, -1 for none. */
    public val idx: IntArray = IntArray(2)

    /** `gBanimUniquePal[2]` (s16): the character-specific palette, -1 for none. */
    public val uniquePal: IntArray = IntArray(2)

    /** `gBanimFactionPal[2]` (s16). */
    public val factionPal: IntArray = IntArray(2)

    /** `gEkrSpellAnimIndex[2]` (s16). */
    public val spellAnimIndex: IntArray = IntArray(2)

    /** `gBanimFloorfx[2]` (s16). */
    public val floorfx: IntArray = IntArray(2)

    /** `gEkrBmLocation[4]` (s16): x and y on screen in tiles of the left, then the right unit. */
    public val bmLocation: IntArray = IntArray(4)

    /** `gAnimRoundData[20]` (u16): per round and side the round type and its flags. */
    public val animRoundData: IntArray = IntArray(ROUND_DATA_SIZE)

    /** `gpEkrBattleUnitLeft` is `gBattleActor` (else `gBattleTarget`). */
    public var leftIsActor: Boolean = false

    /** `gEkrPids[2]` (u8). */
    public val pids: IntArray = IntArray(2)

    /** `gpEkrTriangleUnits[2]`: the triangle attack partners' addresses. */
    public val triangleUnits: IntArray = IntArray(2)

    /** `gBanimTriAtkPalettes[2]`: ROM addresses, 0 for none. */
    public val triAtkPalettes: IntArray = IntArray(2)

    /** `gEkrGaugeHp[2]` (s16). */
    public val gaugeHp: IntArray = IntArray(2)

    /** `gBanimMaxHP[2]` (s16). */
    public val maxHp: IntArray = IntArray(2)

    /** `gEkrGaugeHit[2]` (s16), -1 for none. */
    public val gaugeHit: IntArray = IntArray(2)

    /** `gEkrGaugeDmg[2]` (s16), -1 for none. */
    public val gaugeDmg: IntArray = IntArray(2)

    /** `gEkrGaugeCrt[2]` (s16), -1 for none. */
    public val gaugeCrt: IntArray = IntArray(2)

    /** `gBanimExpPrevious[2]` (s16). */
    public val expPrevious: IntArray = IntArray(2)

    /** `gBanimExpGain[2]` (s16). */
    public val expGain: IntArray = IntArray(2)

    /** `gBanimTerrain[2]` (s16). */
    public val terrain: IntArray = IntArray(2)

    /** `gBanimCon[2]` (s16). */
    public val con: IntArray = IntArray(2)

    /** `gBanimWtaBonus[2]` (s16). */
    public val wtaBonus: IntArray = IntArray(2)

    /** `gBanimEffectiveness[2]` (s16). */
    public val effectiveness: IntArray = IntArray(2)

    /** `gBanimForceUnitChgDebug[2]`. */
    public val forceUnitChgDebug: IntArray = IntArray(2)

    /** `gBanimUniquePaletteDisabled[2]` (s16). */
    public val uniquePaletteDisabled: IntArray = IntArray(2)

    /** Ported from src/banim-ekrarena.c: SetBanimArenaFlag. Stores [flag]. */
    public fun setBanimArenaFlag(flag: Int) {
        arenaFlag = flag
    }

    /** Ported from src/banim-ekrarena.c: GetBattleAnimArenaFlag. The stored flag. */
    public fun getBattleAnimArenaFlag(): Int = arenaFlag

    /**
     * Ported from src/banim-ekrdragonstatus.c: ResetEkrDragonStatus
     *
     * Clears the type, attribute and `unk08` of both dragon statuses.
     */
    public fun resetEkrDragonStatus() {
        dragonStatus.fill(0)
    }

    /**
     * Ported from src/banim-ekrhensei.c: CheckBanimHensei
     *
     * Whether the battle is the promotion preparation (`BATTLE_CONFIG_PROMOTION_PREP`).
     */
    public fun checkBanimHensei(): Boolean = (battle.stats.config and CONFIG_PROMOTION_PREP) != 0

    /**
     * Transcribed from src/banim-battleparse.c: GetBanimTerrainGround (not verified: every capture
     *   is in tileset 0, so the tileset choice is not seen; only two terrains are)
     *
     * The floor effect of [terrain] in battle tileset [tileset] (the table of the tileset, the
     * default table for 0 or an unknown one), minus one. The tables are consecutive in the ROM.
     */
    public fun getBanimTerrainGround(terrain: Int, tileset: Int): Int {
        val table = if (tileset in 1..TILESET_COUNT) tileset else 0
        return signed8(rom.u8(TERRAIN_GROUND + TERRAIN_TABLE_SIZE * table + terrain)) - 1
    }

    /**
     * Transcribed from src/banim-battleparse.c: GetSpellAnimId (not verified: the captured
     *   weapons all have the animation id 0, so the class choice of the generic id is not seen)
     *
     * The spell animation id (s16, so an `efx` of 0xFFFF is -1) of [weapon]'s entry; for the
     * generic mounted-attack id 3 the one of
     * the class [jid].
     */
    public fun getSpellAnimId(jid: Int, weapon: Int): Int {
        val ret = spells.getSpellAssocEfx(items.getItemIndex(weapon))
        if (ret != 3) return s16(ret)
        return when (jid) {
            CLASS_CAVALIER, CLASS_CAVALIER_F -> 4
            CLASS_SOLDIER -> 5
            CLASS_PALADIN -> 6
            CLASS_PALADIN_F -> 0xD
            CLASS_PEGASUS_KNIGHT -> 7
            CLASS_FALCON_KNIGHT -> 8
            CLASS_WYVERN_RIDER, CLASS_WYVERN_RIDER_F -> 9
            CLASS_WYVERN_LORD, CLASS_WYVERN_LORD_F -> 0xA
            CLASS_GENERAL, CLASS_GENERAL_F -> 0xB
            else -> ret
        }
    }

    /**
     * Transcribed from src/banim-battleparse.c: UnsetMapStaffAnim (not verified: no captured
     *   battle uses a map staff)
     *
     * Turns the spell animation index at [side] of [spellAnimIndex] from -1 into 0 and, when the
     * side does not strike first, for the warp, rescue, torch and unlock staffs into 0.
     */
    public fun unsetMapStaffAnim(side: Int, weapon: Int) {
        val item = items.getItemIndex(weapon)
        if (spellAnimIndex[side] == -1) spellAnimIndex[side] = 0
        if (initialHitSide == side) return
        when (item) {
            ITEM_STAFF_WARP, ITEM_STAFF_RESCUE, ITEM_STAFF_TORCH, ITEM_STAFF_UNLOCK ->
                spellAnimIndex[side] = 0
        }
    }

    /**
     * Ported from src/banim-battleparse.c: ParseBattleHitToBanimCmd
     *
     * Turns the battle's hits into [animRoundData] (per round the round type of each side and
     * flags) and the HP shown per round into `gEfxHpLut` ([BanimSetup.efxHpLut]). Needs
     * [leftIsActor], [positionIsEnemy], [distanceType], [gaugeHp] and [maxHp] set. The dark
     * breath branch (`sub_80716B0` random pick) is not written: the C never sets its flag.
     */
    public fun parseBattleHitToBanimCmd() {
        val lut = setup.efxHpLut
        animRoundData.fill(0xFFFF)
        for (i in 0 until ROUND_DATA_SIZE) lut[2 + i] = 0xFFFF
        triangleUnits[0] = 0
        triangleUnits[1] = 0

        if (distanceType == DISTANCE_PROMOTION) {
            animRoundData[0] = 4
            animRoundData[1] = 4
            return
        }
        if ((battle.stats.config and CONFIG_REFRESH) != 0) {
            animRoundData[0] = 6
            animRoundData[1] = 0
            return
        }

        var distanceLeft = distanceType
        var distanceRight = distanceType
        val left = if (leftIsActor) battle.actor else battle.target
        val right = if (leftIsActor) battle.target else battle.actor
        for (item in SHORT_RANGE_ITEMS) {
            if (items.getItemIndex(left.weaponBefore) == item && distanceLeft == DISTANCE_CLOSE) {
                distanceLeft = DISTANCE_FAR
            }
            if (items.getItemIndex(right.weaponBefore) == item && distanceRight == DISTANCE_CLOSE) {
                distanceRight = DISTANCE_FAR
            }
        }

        lut[0] = gaugeHp[0] and 0xFFFF
        lut[1] = gaugeHp[1] and 0xFFFF

        var round = 0
        var leftHits = 0
        var rightHits = 0
        while (round < hitArray.size && (info(hitArray[round]) and INFO_END) == 0) {
            val hit = hitArray[round]
            val attributes = hit and ATTR_MASK
            val hpChange = hit shr 24
            val isEnemy = if ((info(hit) and INFO_RETALIATION) != 0) 1 else 0
            val selfLeft = positionIsEnemy[0] == isEnemy
            val self = if (selfLeft) 0 else 1
            val other = 1 - self
            val distanceSelf = if (selfLeft) distanceLeft else distanceRight
            val distanceOther = if (selfLeft) distanceRight else distanceLeft
            val unit = if (selfLeft) left else right
            if (round == 0) initialHitSide = self

            if ((attributes and ATTR_TATTACK) != 0) {
                triangleUnits[0] = battle.stats.taUnitA
                triangleUnits[1] = battle.stats.taUnitB
            }

            val magic = hasMagicRank(unit)
            var own: Int
            if ((attributes and ATTR_CRIT) != 0 || (attributes and ATTR_SILENCER) != 0) {
                own = roundType(if (magic) TABLE_CRIT_FAR else TABLE_CRIT_CLOSE, distanceSelf)
            } else {
                own = roundType(if (magic) TABLE_NONCRIT_FAR_MAGIC else TABLE_HIT, distanceSelf)
            }
            val opposite: Int
            if ((attributes and ATTR_MISS) != 0) {
                own = roundType(if (magic) TABLE_NONCRIT_FAR_MAGIC else TABLE_MISS, distanceSelf)
                opposite = roundType(TABLE_TAKING_MISS, distanceOther)
            } else {
                opposite = roundType(TABLE_TAKING_HIT, distanceOther)
            }
            animRoundData[2 * round + self] = own
            animRoundData[2 * round + other] = opposite

            if ((attributes and ATTR_MISS) == 0) {
                // The side that strikes loses nothing; the struck side (index [other]) loses HP.
                if ((attributes and ATTR_DEVIL) != 0) {
                    // The attacker is hurt by its own weapon.
                    if (selfLeft) {
                        leftHits++
                        lut[2 * leftHits] = clampLow(setup.getEfxHp(2 * (leftHits - 1)) - hpChange)
                        animRoundData[2 * round] = animRoundData[2 * round] or ROUND_DEVIL
                    } else {
                        rightHits++
                        lut[2 * rightHits + 1] =
                            clampLow(setup.getEfxHp(2 * (rightHits - 1) + 1) - hpChange)
                        animRoundData[2 * round + 1] = animRoundData[2 * round + 1] or ROUND_DEVIL
                    }
                } else if ((attributes and ATTR_HPSTEAL) != 0) {
                    if (selfLeft) {
                        rightHits++
                        lut[2 * rightHits + 1] =
                            clampLow(setup.getEfxHp(2 * (rightHits - 1) + 1) - hpChange)
                        leftHits++
                        lut[2 * leftHits] =
                            minOf(setup.getEfxHp(2 * (leftHits - 1)) + hpChange, maxHp[0]) and
                            0xFFFF
                    } else {
                        leftHits++
                        lut[2 * leftHits] = clampLow(setup.getEfxHp(2 * (leftHits - 1)) - hpChange)
                        rightHits++
                        lut[2 * rightHits + 1] =
                            minOf(setup.getEfxHp(2 * (rightHits - 1) + 1) + hpChange, maxHp[1]) and
                            0xFFFF
                    }
                } else {
                    if (selfLeft) {
                        rightHits++
                        lut[2 * rightHits + 1] =
                            clampLow(setup.getEfxHp(2 * (rightHits - 1) + 1) - hpChange)
                    } else {
                        leftHits++
                        lut[2 * leftHits] = clampLow(setup.getEfxHp(2 * (leftHits - 1)) - hpChange)
                    }
                    if ((attributes and ATTR_POISON) != 0) {
                        animRoundData[2 * round + other] =
                            animRoundData[2 * round + other] or ROUND_POISON
                    }
                    if ((attributes and ATTR_SILENCER) != 0) {
                        animRoundData[2 * round + self] =
                            animRoundData[2 * round + self] or ROUND_SILENCER
                    }
                    if ((attributes and ATTR_SURESHOT) != 0) {
                        animRoundData[2 * round + self] =
                            animRoundData[2 * round + self] or ROUND_SURE_SHOT
                    }
                    if ((attributes and ATTR_PIERCE) != 0) {
                        animRoundData[2 * round + self] =
                            animRoundData[2 * round + self] or ROUND_PIERCE
                    }
                    if ((attributes and ATTR_GREATSHLD) != 0) {
                        animRoundData[2 * round + self] =
                            animRoundData[2 * round + self] or ROUND_GREAT_SHIELD
                    }
                }
            }
            round++
        }
    }

    /**
     * Ported from src/banim-ekrbattleintro.c: PrepareBattleGraphicsMaybe (`sub_8055BC4` and
     *   `sub_8055BB4` call it)
     *
     * Fills the battle animation variables of this class for the battle between `gBattleActor`
     * and `gBattleTarget`. Returns whether the battle is shown with battle animations: false when
     * the setting is off (and the battle is no promotion, arena or scripted battle), or for a
     * ballista, a Myrrh status staff or a berserked or unplaceable fighter. Calls
     * [BanimSetup.setBattleUnscripted] before the checks of the units.
     */
    public fun prepareBattleGraphicsMaybe(): Boolean {
        resetEkrDragonStatus()
        setBanimArenaFlag(if ((battle.stats.config and CONFIG_ARENA) == 0) 0 else 1)
        setup.setBanimLinkArenaFlag(if ((bm.gameStateBits and BM_FLAG_LINKARENA) == 0) 0 else 1)
        distanceType =
            if ((battle.stats.config and CONFIG_PROMOTION) != 0) DISTANCE_PROMOTION
            else DISTANCE_CLOSE

        val actor = battle.actor
        val target = battle.target
        var charCnt = 1
        val bu1: BattleUnit
        val bu2: BattleUnit
        if (distanceType == DISTANCE_PROMOTION) {
            leftIsActor = true
            bu1 = actor
            bu2 = target
            positionIsEnemy[0] = 0
            positionIsEnemy[1] = 0
            valid[1] = 1
            valid[0] = 1
        } else {
            val faction1 = setup.getBanimFactionPalette(s8(actor.bytes, OFF_INDEX) and 0xC0)
            val faction2 = setup.getBanimFactionPalette(s8(target.bytes, OFF_INDEX) and 0xC0)
            charCnt = when {
                (battle.stats.config and CONFIG_REFRESH) != 0 -> 2
                actor.weaponBefore == 0 -> 2
                else -> spells.getSpellAssocCharCount(items.getItemIndex(actor.weaponBefore))
            }
            valid[0] = 1
            valid[1] = 1
            if (allyPosition(faction1, faction2) == 1) {
                leftIsActor = false
                bu1 = target
                bu2 = actor
                positionIsEnemy[0] = 1
                positionIsEnemy[1] = 0
                if (charCnt == 1) valid[0] = 0
            } else {
                leftIsActor = true
                bu1 = actor
                bu2 = target
                positionIsEnemy[0] = 0
                positionIsEnemy[1] = 1
                if (charCnt == 1) valid[1] = 0
            }
        }

        val class1 = u32(bu1.bytes, OFF_CLASS)
        val class2 = u32(bu2.bytes, OFF_CLASS)
        val char1 = u32(bu1.bytes, OFF_CHARACTER)
        val char2 = u32(bu2.bytes, OFF_CHARACTER)
        val validL = valid[0] != 0
        val validR = valid[1] != 0
        val animDef1 = if (validL) rom.u32(class1 + OFF_CLASS_ANIM_DEF) else 0
        val animDef2 = if (validR) rom.u32(class2 + OFF_CLASS_ANIM_DEF) else 0

        if (validL) {
            bmLocation[0] = (16 * bu1.unitX - bm.cameraX) shr 4
            bmLocation[1] = (16 * bu1.unitY - bm.cameraY) shr 4
        }
        if (validR) {
            bmLocation[2] = (16 * bu2.unitX - bm.cameraX) shr 4
            bmLocation[3] = (16 * bu2.unitY - bm.cameraY) shr 4
        }

        if (distanceType != DISTANCE_PROMOTION) {
            if ((items.getItemAttributes(actor.weaponBefore) and ItemInfo.IA_UNCOUNTERABLE) != 0) {
                distanceType = DISTANCE_FARFAR
            } else {
                distanceType = DISTANCE_MONOCOMBAT
                if (valid[0] + valid[1] == 2) {
                    val x = abs(bmLocation[0] - bmLocation[2])
                    val y = abs(bmLocation[1] - bmLocation[3])
                    distanceType = when {
                        x + y <= 1 -> DISTANCE_CLOSE
                        x + y <= 3 -> DISTANCE_FAR
                        else -> DISTANCE_FARFAR
                    }
                }
            }
        }

        if (distanceType == DISTANCE_PROMOTION) {
            idx[0] = s16(setup.getBattleAnimationId(animDef1, bu1.weapon))
            idxBak[0] = idx[0]
            idx[1] = s16(setup.getBattleAnimationId(animDef2, bu2.weapon))
            idxBak[1] = idx[1]
        } else {
            if (validL) {
                idx[0] = s16(setup.getBattleAnimationId(animDef1, bu1.weaponBefore))
                idxBak[0] = idx[0]
            }
            if (validR) {
                idx[1] = s16(setup.getBattleAnimationId(animDef2, bu2.weaponBefore))
                idxBak[1] = idx[1]
            }
        }

        val jid1 = rom.u8(class1 + OFF_CLASS_NUMBER)
        val jid2 = rom.u8(class2 + OFF_CLASS_NUMBER)
        uniquePalette(0, rom.u8(char1 + OFF_CHARACTER_NUMBER) - 1, jid1, validL)
        uniquePalette(1, rom.u8(char2 + OFF_CHARACTER_NUMBER) - 1, jid2, validR)

        if (validL) {
            triAtkPalettes[0] = setup.filterBattleAnimCharacterPalette(idx[0], bu1.weaponBefore)
        }
        if (validR) {
            triAtkPalettes[1] = setup.filterBattleAnimCharacterPalette(idx[1], bu2.weaponBefore)
        }

        terrain[0] = bu1.terrainId
        terrain[1] = bu2.terrainId
        floorfx[0] = -1
        floorfx[1] = -1
        val tileset = rom.u8(chapters.getROMChapterStruct(playSt.chapterIndex) + OFF_BATTLE_TILESET)
        if (validL) floorfx[0] = getBanimTerrainGround(bu1.terrainId, tileset)
        if (validR) floorfx[1] = getBanimTerrainGround(bu2.terrainId, tileset)

        if ((bm.gameStateBits and BM_FLAG_LINKARENA) != 0) {
            terrain[1] = TERRAIN_ARENA_30
            terrain[0] = TERRAIN_ARENA_30
            if (validL) floorfx[0] = getBanimTerrainGround(terrain[0], tileset)
            if (validR) floorfx[1] = getBanimTerrainGround(terrain[1], tileset)
        }
        if (checkBanimHensei()) {
            floorfx[0] = 20
            floorfx[1] = 20
            terrain[0] = TERRAIN_ARENA_30
            terrain[1] = TERRAIN_ARENA_30
        }
        if (distanceType == DISTANCE_PROMOTION) floorfx[0] = floorfx[1]

        snowWeather = if (
            playSt.chapterWeatherId == WEATHER_SNOW ||
            playSt.chapterWeatherId == WEATHER_SNOWSTORM
        ) 1 else 0

        if (validL) con[0] = signed8(rom.u8(class1 + OFF_CLASS_BASE_CON))
        if (validR) con[1] = signed8(rom.u8(class2 + OFF_CLASS_BASE_CON))
        if (validL) {
            gaugeHp[0] = bu1.hpInitial
            maxHp[0] = s8(bu1.bytes, OFF_MAX_HP)
        }
        if (validR) {
            gaugeHp[1] = bu2.hpInitial
            maxHp[1] = s8(bu2.bytes, OFF_MAX_HP)
        }

        parseBattleHitToBanimCmd()

        if (distanceType == DISTANCE_PROMOTION) {
            spellAnimIndex[1] = 1
            spellAnimIndex[0] = 1
        } else {
            if (validL) spellAnimIndex[0] = getSpellAnimId(jid1, bu1.weaponBefore)
            if (validR) spellAnimIndex[1] = getSpellAnimId(jid2, bu2.weaponBefore)
            if ((battle.stats.config and CONFIG_REFRESH) != 0 &&
                !setup.isItemDisplayedInBattle(bu2.weaponBefore) && jid2 == CLASS_DANCER
            ) {
                spellAnimIndex[1] = 0xF
            }
        }
        if (validL) unsetMapStaffAnim(0, bu1.weaponBefore)
        if (validR) unsetMapStaffAnim(1, bu2.weaponBefore)

        when (distanceType) {
            DISTANCE_CLOSE, DISTANCE_FAR, DISTANCE_FARFAR ->
                check(jid1 != CLASS_DRACO_ZOMBIE && jid1 != CLASS_DEMON_KING) {
                    "SetEkrDragonStatusType is not written"
                }
        }

        if (validL) factionPal[0] = setup.getBanimFactionPalette(s8(bu1.bytes, OFF_INDEX) and 0xC0)
        if (validR) factionPal[1] = setup.getBanimFactionPalette(s8(bu2.bytes, OFF_INDEX) and 0xC0)

        pids[1] = 0
        pids[0] = 0
        if (validL) pids[0] = rom.u8(char1 + OFF_CHARACTER_NUMBER)
        if (validR) pids[1] = rom.u8(char2 + OFF_CHARACTER_NUMBER)

        if (validL) gaugeHit[0] = bu1.battleEffectiveHitRate
        if (validR) gaugeHit[1] = bu2.battleEffectiveHitRate
        if (gaugeHit[0] == 0xFF) gaugeHit[0] = -1
        if (gaugeHit[1] == 0xFF) gaugeHit[1] = -1

        if (validL) gaugeDmg[0] = damage(bu1, bu2)
        if (validR) gaugeDmg[1] = damage(bu2, bu1)

        if (validL) gaugeCrt[0] = bu1.battleEffectiveCritRate
        if (validR) gaugeCrt[1] = bu2.battleEffectiveCritRate
        if (gaugeCrt[0] == 0xFF) gaugeCrt[0] = -1
        if (gaugeCrt[1] == 0xFF) gaugeCrt[1] = -1
        if (items.getItemIndex(bu1.weapon) == ITEM_MONSTER_STONE) gaugeCrt[0] = -1
        if (items.getItemIndex(bu2.weapon) == ITEM_MONSTER_STONE) gaugeCrt[1] = -1

        if (distanceType == DISTANCE_PROMOTION) {
            gaugeHit[1] = -1
            gaugeDmg[1] = -1
            gaugeCrt[1] = -1
        }

        if (validL) expPrevious[0] = bu1.expPrevious
        if (validR) expPrevious[1] = bu2.expPrevious
        if (validL) expGain[0] = bu1.expGain
        if (validR) expGain[1] = bu2.expGain

        wtaBonus[1] = 0
        wtaBonus[0] = 0
        if (distanceType != DISTANCE_PROMOTION) {
            if (validL) wtaBonus[0] = bu1.wTriangleHitBonus
            if (validR) wtaBonus[1] = bu2.wTriangleHitBonus
            if (validL) effectiveness[0] = effective(bu1, bu2, bu1.weapon)
            if (validR) effectiveness[1] = effective(bu2, bu1, bu2.weapon)
        }

        forceUnitChgDebug[0] = 0
        forceUnitChgDebug[1] = 0

        val unitColor = if (setup.getBanimLinkArenaFlag() == 1 || playSt.unitColor != 0) 1 else 0
        uniquePaletteDisabled[0] = unitColor
        uniquePaletteDisabled[1] = unitColor

        banimBg = 0
        if (setup.getBattleAnimPreconfType() == PLAY_ANIMCONF_ON_UNIQUE_BG) {
            error("GetBanimBackgroundIndex is not written")
        }
        if (checkBanimHensei()) banimBg = 0x3C

        var userEnabled = false
        val preconf = setup.getBattleAnimPreconfType()
        if (preconf == PLAY_ANIMCONF_ON) userEnabled = true
        if (preconf == PLAY_ANIMCONF_ON_UNIQUE_BG) userEnabled = true
        if (preconf == CombatAction.PLAY_ANIMCONF_OFF) {
            if (distanceType == DISTANCE_PROMOTION) userEnabled = true
            if (getBattleAnimArenaFlag() == 1) userEnabled = true
            if (setup.checkBattleScripted()) userEnabled = true
        }

        setup.setBattleUnscripted()

        if (distanceType != DISTANCE_PROMOTION) {
            if ((u32(bu1.bytes, OFF_STATE) and US_IN_BALLISTA) != 0) return false
            if ((u32(bu2.bytes, OFF_STATE) and US_IN_BALLISTA) != 0) return false
        }

        val myrrh1 = jid1 == CLASS_MANAKETE_MYRRH
        val myrrh2 = jid2 == CLASS_MANAKETE_MYRRH
        val w1 = items.getItemIndex(bu1.weaponBefore)
        val w2 = items.getItemIndex(bu2.weaponBefore)
        for (item in MYRRH_BLOCKED_ITEMS) {
            if (myrrh1 && w2 == item) return false
            if (myrrh2 && w1 == item) return false
        }

        if (charCnt != 1 && jid1 == CLASS_DEMON_KING && w1 != ITEM_NIGHTMARE &&
            jid2 != CLASS_PHANTOM && jid2 != CLASS_DRACO_ZOMBIE
        ) {
            return true
        }

        if (!userEnabled) return false

        if (valid[0] == 1) {
            if (!sideUsable(0, bu1)) return false
        }
        if (valid[1] == 1) {
            if (!sideUsable(1, bu2)) return false
        }
        return true
    }

    /**
     * Ported from src/bmbattle.c: sub_8055BC4 (banim-ekrbattlestarting.c)
     *
     * Whether the battle is shown with battle animations: [prepareBattleGraphicsMaybe].
     */
    public fun sub8055BC4(): Boolean = prepareBattleGraphicsMaybe()

    private fun sideUsable(side: Int, unit: BattleUnit): Boolean =
        (s8(unit.bytes, OFF_STATUS) and 0xF) != UNIT_STATUS_BERSERK &&
            idx[side] != -1 && spellAnimIndex[side] != -2 && floorfx[side] != -1 &&
            terrain[side] != TERRAIN_WALL_DAMAGED && terrain[side] != TERRAIN_SNAG

    private fun damage(own: BattleUnit, opponent: BattleUnit): Int {
        var dmg = own.battleAttack - opponent.battleDefense
        if (dmg < 0) dmg = 0
        if (own.battleAttack == 0xFF) dmg = -1
        if (items.getItemIndex(own.weapon) == ITEM_MONSTER_STONE) dmg = -1
        return dmg
    }

    private fun effective(own: BattleUnit, opponent: BattleUnit, weapon: Int): Int {
        if (battle.isUnitEffectiveAgainst(own.bytes, 0, opponent.bytes, 0)) return 1
        return if (battle.isItemEffectiveAgainst(weapon, opponent.bytes, 0)) 1 else 0
    }

    private fun uniquePalette(side: Int, pid: Int, jid: Int, enabled: Boolean) {
        if (enabled) uniquePal[side] = -1
        for (i in 0 until PAL_CONFIG_COLUMNS) {
            if (rom.u8(ANIM_CHARA_PAL_CONFIG + pid * PAL_CONFIG_COLUMNS + i) == jid && enabled) {
                uniquePal[side] = rom.u8(ANIM_CHARA_PAL_IT + pid * PAL_CONFIG_COLUMNS + i) - 1
                break
            }
        }
    }

    /** `GetBanimAllyPosition`: the side (0 left, 1 right) the unit of [faction1] stands on. */
    private fun allyPosition(faction1: Int, faction2: Int): Int {
        var pos = 0
        if (setup.getBanimLinkArenaFlag() != 1) {
            if (faction1 == FACTION_ID_BLUE) {
                pos = 1
            } else if (faction1 == FACTION_ID_RED) {
                pos = 1
            } else if (faction1 == FACTION_ID_GREEN && faction2 == FACTION_ID_GREEN) {
                pos = 1
            }
        }
        return pos
    }

    /**
     * Mirrors [UnitTable.unitHasMagicRank] for a [BattleUnit] byte array (not verified: no unit
     * in a capture holds a staff, anima, light or dark rank).
     */
    private fun hasMagicRank(unit: BattleUnit): Boolean {
        var combined = 0
        for (i in ITYPE_STAFF..ITYPE_DARK) {
            combined = combined or (unit.bytes[OFF_RANKS + i].toInt() and 0xFF)
        }
        return combined != 0
    }

    private fun roundType(table: Int, distance: Int): Int = rom.u16(table + 2 * distance)

    private fun clampLow(hp: Int): Int = (if (hp < 0) 0 else hp) and 0xFFFF

    private fun s16(value: Int): Int = value.toShort().toInt()

    private fun signed8(value: Int): Int = value.toByte().toInt()

    private fun info(hit: Int): Int = (hit ushr 19) and 0x1F

    private companion object {
        const val DRAGON_STATUS_FIELDS = 3
        const val ROUND_DATA_SIZE = 20

        const val OFF_CHARACTER = 0x00
        const val OFF_CLASS = 0x04
        const val OFF_INDEX = 0x0B
        const val OFF_STATE = 0x0C
        const val OFF_MAX_HP = 0x12
        const val OFF_RANKS = 0x28
        const val OFF_STATUS = 0x30
        const val OFF_CHARACTER_NUMBER = 0x04
        const val OFF_CLASS_NUMBER = 0x04
        const val OFF_CLASS_BASE_CON = 0x11
        const val OFF_CLASS_ANIM_DEF = 0x34
        const val OFF_BATTLE_TILESET = 0x13

        const val CONFIG_PROMOTION = 1 shl 4
        const val CONFIG_ARENA = 1 shl 5
        const val CONFIG_REFRESH = 1 shl 6
        const val CONFIG_PROMOTION_PREP = 1 shl 8
        const val BM_FLAG_LINKARENA = 1 shl 6
        const val US_IN_BALLISTA = 1 shl 11
        const val UNIT_STATUS_BERSERK = 4
        const val PLAY_ANIMCONF_ON = 0
        const val PLAY_ANIMCONF_ON_UNIQUE_BG = 3
        const val WEATHER_SNOW = 1
        const val WEATHER_SNOWSTORM = 2

        const val TERRAIN_WALL_DAMAGED = 0x1B
        const val TERRAIN_ARENA_30 = 0x30
        const val TERRAIN_SNAG = 0x33

        const val DISTANCE_CLOSE = 0
        const val DISTANCE_FAR = 1
        const val DISTANCE_FARFAR = 2
        const val DISTANCE_MONOCOMBAT = 3
        const val DISTANCE_PROMOTION = 4

        const val FACTION_ID_BLUE = 0
        const val FACTION_ID_GREEN = 1
        const val FACTION_ID_RED = 2

        const val INFO_END = 1 shl 4
        const val INFO_RETALIATION = 1 shl 3
        const val ATTR_MASK = 0x7FFFF
        const val ATTR_CRIT = 1 shl 0
        const val ATTR_MISS = 1 shl 1
        const val ATTR_POISON = 1 shl 6
        const val ATTR_DEVIL = 1 shl 7
        const val ATTR_HPSTEAL = 1 shl 8
        const val ATTR_TATTACK = 1 shl 10
        const val ATTR_SILENCER = 1 shl 11
        const val ATTR_SURESHOT = 1 shl 14
        const val ATTR_GREATSHLD = 1 shl 15
        const val ATTR_PIERCE = 1 shl 16

        const val ROUND_PIERCE = 0x0200
        const val ROUND_GREAT_SHIELD = 0x0400
        const val ROUND_SURE_SHOT = 0x0800
        const val ROUND_SILENCER = 0x1000
        const val ROUND_POISON = 0x2000
        const val ROUND_DEVIL = 0x8000

        // ROM tables (u16 per distance type): gUnknown_080DAE8C (hit), ...96 (miss), ...A0 (crit),
        // ...AA (taking a miss), ...B4 (taking a hit), ...BE (far hit by magic), ...C8 (far crit by
        // magic).
        const val TABLE_HIT = 0x080DAE8C
        const val TABLE_MISS = 0x080DAE96
        const val TABLE_CRIT_CLOSE = 0x080DAEA0
        const val TABLE_TAKING_MISS = 0x080DAEAA
        const val TABLE_TAKING_HIT = 0x080DAEB4
        const val TABLE_NONCRIT_FAR_MAGIC = 0x080DAEBE
        const val TABLE_CRIT_FAR = 0x080DAEC8

        const val TERRAIN_GROUND = 0x0880C7C6
        const val TERRAIN_TABLE_SIZE = 0x41
        const val TILESET_COUNT = 0x14
        const val ANIM_CHARA_PAL_CONFIG = 0x0895E0A4
        const val ANIM_CHARA_PAL_IT = 0x0895EEA4
        const val PAL_CONFIG_COLUMNS = 7

        const val ITYPE_STAFF = 4
        const val ITYPE_DARK = 7

        const val ITEM_SWORD_RUNESWORD = 0x11
        const val ITEM_AXE_HANDAXE = 0x28
        const val ITEM_AXE_TOMAHAWK = 0x29
        const val ITEM_AXE_HATCHET = 0x2C
        const val ITEM_STAFF_SILENCE = 0x51
        const val ITEM_STAFF_SLEEP = 0x52
        const val ITEM_STAFF_BERSERK = 0x53
        const val ITEM_STAFF_WARP = 0x54
        const val ITEM_STAFF_RESCUE = 0x55
        const val ITEM_STAFF_TORCH = 0x56
        const val ITEM_STAFF_UNLOCK = 0x58
        const val ITEM_NIGHTMARE = 0xA6
        const val ITEM_MONSTER_STONE = 0xB5

        val SHORT_RANGE_ITEMS = intArrayOf(
            ITEM_SWORD_RUNESWORD, ITEM_AXE_HANDAXE, ITEM_AXE_TOMAHAWK, ITEM_AXE_HATCHET,
        )
        val MYRRH_BLOCKED_ITEMS = intArrayOf(
            ITEM_STAFF_SLEEP, ITEM_STAFF_BERSERK, ITEM_STAFF_SILENCE, ITEM_MONSTER_STONE,
        )

        const val CLASS_CAVALIER = 0x05
        const val CLASS_CAVALIER_F = 0x06
        const val CLASS_PALADIN = 0x07
        const val CLASS_PALADIN_F = 0x08
        const val CLASS_GENERAL = 0x0B
        const val CLASS_GENERAL_F = 0x0C
        const val CLASS_WYVERN_RIDER = 0x1F
        const val CLASS_WYVERN_RIDER_F = 0x20
        const val CLASS_WYVERN_LORD = 0x21
        const val CLASS_WYVERN_LORD_F = 0x22
        const val CLASS_MANAKETE_MYRRH = 0x3C
        const val CLASS_PEGASUS_KNIGHT = 0x48
        const val CLASS_FALCON_KNIGHT = 0x49
        const val CLASS_DANCER = 0x4D
        const val CLASS_SOLDIER = 0x4E
        const val CLASS_PHANTOM = 0x51
        const val CLASS_DRACO_ZOMBIE = 0x65
        const val CLASS_DEMON_KING = 0x66
    }
}
