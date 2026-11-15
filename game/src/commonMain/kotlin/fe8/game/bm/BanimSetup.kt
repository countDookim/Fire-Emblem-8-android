package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32

/**
 * The battle animation set-up queries `PrepareBattleGraphicsMaybe` (EP-15) asks: which battle
 * animation a class shows for a weapon, the faction palette, the animation setting, the scripted
 * battle flag and the link arena flag (src/banim-ekrcmd.c, src/banim-ekrbattle.c and
 * `GetBattleAnimPreconfType` of src/bmbattle.c). The animation ids come from the class's
 * `pBattleAnimDef` table in the ROM; nothing is allocated.
 *
 * @param rom the ROM holding the class and animation tables.
 * @param playSt `gPlaySt` (`config.animationType`, `config.unitColor`).
 * @param battle `gBattleActor` and `gBattleTarget`.
 * @param items the item getters.
 */
public class BanimSetup(
    private val rom: RomView,
    private val playSt: PlaySt,
    private val battle: BmBattle,
    private val items: ItemInfo = ItemInfo(rom),
) {
    /**
     * `gBattleScripted` (EWRAM): a scripted battle (an event starts it) shows the battle
     * animation whatever the animation setting is. [setBattleUnscripted] clears it.
     */
    public var battleScripted: Boolean = false

    /** `gBanimLinkArenaFlag` (EWRAM), see [setBanimLinkArenaFlag]. */
    private var linkArenaFlag: Int = 0

    /**
     * `gEfxHpLut[22]` (u16): the hit points the battle animation shows per round and side.
     * Written by `ParseBattleHitToBanimCmd` (EP-15), read by [getEfxHp].
     */
    public val efxHpLut: IntArray = IntArray(EFX_HP_LUT_SIZE)

    /**
     * The `*out` of the last [getBattleAnimationId]: the position of the matching entry in the
     * class's table (0 when none matched).
     */
    public var animEntry: Int = 0
        private set

    /**
     * Ported from src/banim-ekrcmd.c: GetBattleAnimationId
     *
     * The battle animation index of a unit whose class has the animation table [animDef] (ROM
     * address of `pBattleAnimDef`, 0 for none) fighting with [wpn]: the table entry of the item
     * itself, else the entry of its item type (`0x100 | type`), minus one. 0xFFFF (-1) for no
     * table, or for an item that is not displayed in battle. The decomp's unused `unit`
     * parameter is dropped. The position of the entry goes to [animEntry].
     */
    public fun getBattleAnimationId(animDef: Int, wpn: Int): Int {
        if (animDef == 0) return 0xFFFF
        if (items.getItemType(wpn) == ITYPE_ITEM && !isItemDisplayedInBattle(wpn)) return 0xFFFF
        val type = if (wpn == 0) ITYPE_ITEM else items.getItemType(wpn)
        animEntry = 0
        var index = 0
        var found = false
        for (pass in 0 until 2) {
            var j = 0
            var src = animDef
            var wtype = rom.u16(src)
            while (wtype != 0) {
                if ((pass == 1 || wtype < 0x100) && (pass != 1 || wtype >= 0x100) &&
                    (wtype == items.getItemIndex(wpn) || (wtype - 0x100) == type)
                ) {
                    index = rom.u16(src + 2)
                    animEntry = j
                    found = true
                    break
                }
                src += 4
                wtype = rom.u16(src)
                j++
            }
            if (found) break
        }
        return (index - 1) and 0xFFFF
    }

    /**
     * Transcribed from src/banim-ekrcmd.c: IsItemDisplayedInBattle (not verified: no captured
     *   battle uses an item of type "item"; only [getBattleAnimationId] calls it)
     *
     * Whether the item is one of the four Rings of the legendary weapons' staffs shown in a
     * battle animation.
     */
    public fun isItemDisplayedInBattle(item: Int): Boolean {
        val index = items.getItemIndex(item)
        return index == ITEM_FILLAS_MIGHT || index == ITEM_NINISS_GRACE ||
            index == ITEM_THORS_IRE || index == ITEM_SETS_LITANY
    }

    /**
     * Transcribed from src/banim-ekrcmd.c: FilterBattleAnimCharacterPalette (not verified: the
     *   captures show only the null default; the ballista animations are not in a capture)
     *
     * The ROM address of the palette that replaces the character palette of ballista animation
     * [index] for ballista [item], 0 (NULL) for any other animation or item.
     */
    public fun filterBattleAnimCharacterPalette(index: Int, item: Int): Int {
        when (index) {
            0x9E, 0xC9, 0xCA, 0xCB -> return when (items.getItemIndex(item)) {
                ITEM_BALLISTA_REGULAR -> PAL_BALLISTA_REGULAR
                ITEM_BALLISTA_LONG -> PAL_BALLISTA_LONG
                ITEM_BALLISTA_KILLER -> PAL_BALLISTA_KILLER
                else -> 0
            }
            else -> return 0
        }
    }

    /**
     * Ported from src/banim-ekrcmd.c: GetBanimFactionPalette
     *
     * The `BANIMPAL_*` palette of a unit's faction ([faction] is `unit->index & 0xC0`). Red and
     * blue are seen; green and purple are not (no capture has an ally or a fourth team).
     */
    public fun getBanimFactionPalette(faction: Int): Int = when (faction and 0xFF) {
        FACTION_RED -> BANIMPAL_RED
        FACTION_GREEN -> BANIMPAL_GREEN
        FACTION_PURPLE -> BANIMPAL_PURPLE
        FACTION_BLUE -> BANIMPAL_BLUE
        else -> BANIMPAL_BLUE
    }

    /**
     * Ported from src/banim-ekrcmd.c: GetEfxHp
     *
     * The low byte of `gEfxHpLut[index]`: the HP shown for round position [index].
     */
    public fun getEfxHp(index: Int): Int = efxHpLut[index] and 0xFF

    /** Ported from src/banim-ekrcmd.c: SetBattleUnscripted. Clears [battleScripted]. */
    public fun setBattleUnscripted() {
        battleScripted = false
    }

    /**
     * Transcribed from src/banim-ekrcmd.c: CheckBattleScripted (not verified: the result only
     *   feeds the branch of `PrepareBattleGraphicsMaybe` (EP-15) that is not taken either way)
     *
     * Whether the battle is scripted ([battleScripted]).
     */
    public fun checkBattleScripted(): Boolean = battleScripted

    /** Ported from src/banim-ekrbattle.c: SetBanimLinkArenaFlag. Stores [flag]. */
    public fun setBanimLinkArenaFlag(flag: Int) {
        linkArenaFlag = flag
    }

    /** Ported from src/banim-ekrbattle.c: GetBanimLinkArenaFlag. The stored flag (0 or 1). */
    public fun getBanimLinkArenaFlag(): Int = linkArenaFlag

    /**
     * Transcribed from src/bmbattle.c: GetBattleAnimPreconfType (not verified: every capture has
     *   the setting OFF, so only the early return is seen; neither solo animation branch is, and
     *   inverting the test for the solo setting gives the same answer for all three battles)
     *
     * The `PLAY_ANIMCONF_*` setting for the battle between `gBattleActor` and `gBattleTarget`:
     * the global setting, unless it is the solo animation setting, which asks the player unit's
     * `US_SOLOANIM_*` state bits.
     */
    public fun getBattleAnimPreconfType(): Int {
        if (playSt.animationType != PLAY_ANIMCONF_SOLO_ANIM) return playSt.animationType

        val actor = battle.actor
        val target = battle.target
        val actorBlue = faction(actor) == FACTION_BLUE
        val targetBlue = faction(target) == FACTION_BLUE
        if (actorBlue && targetBlue) return soloAnimPreconfType(actor)
        if (!actorBlue && !targetBlue) return CombatAction.PLAY_ANIMCONF_OFF
        return if (actorBlue) soloAnimPreconfType(actor) else soloAnimPreconfType(target)
    }

    /**
     * Transcribed from src/bmbattle.c: GetSoloAnimPreconfType (not verified: see
     *   [getBattleAnimPreconfType])
     */
    private fun soloAnimPreconfType(unit: BattleUnit): Int {
        val state = u32(unit.bytes, OFF_STATE)
        if ((state and US_SOLOANIM_1) != 0) return PLAY_ANIMCONF_ON
        if ((state and US_SOLOANIM_2) != 0) return PLAY_ANIMCONF_ON_UNIQUE_BG
        return CombatAction.PLAY_ANIMCONF_OFF
    }

    private fun faction(unit: BattleUnit): Int = s8(unit.bytes, OFF_INDEX) and 0xC0

    private companion object {
        const val OFF_INDEX = 0x0B
        const val OFF_STATE = 0x0C

        const val EFX_HP_LUT_SIZE = 22
        const val ITYPE_ITEM = 9

        const val ITEM_BALLISTA_REGULAR = 0x35
        const val ITEM_BALLISTA_LONG = 0x36
        const val ITEM_BALLISTA_KILLER = 0x37
        const val ITEM_FILLAS_MIGHT = 0x7D
        const val ITEM_NINISS_GRACE = 0x7E
        const val ITEM_THORS_IRE = 0x7F
        const val ITEM_SETS_LITANY = 0x80

        // ROM addresses of the ballista palettes (Pal_085BA300, Pal_085BA2E0, Pal_085BA320).
        const val PAL_BALLISTA_REGULAR = 0x085BA300
        const val PAL_BALLISTA_LONG = 0x085BA2E0
        const val PAL_BALLISTA_KILLER = 0x085BA320

        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
        const val FACTION_PURPLE = 0xC0

        const val BANIMPAL_BLUE = 0
        const val BANIMPAL_RED = 1
        const val BANIMPAL_GREEN = 2
        const val BANIMPAL_PURPLE = 3

        const val PLAY_ANIMCONF_ON = 0
        const val PLAY_ANIMCONF_SOLO_ANIM = 2
        const val PLAY_ANIMCONF_ON_UNIQUE_BG = 3

        const val US_SOLOANIM_1 = 1 shl 14
        const val US_SOLOANIM_2 = 1 shl 15
    }
}
