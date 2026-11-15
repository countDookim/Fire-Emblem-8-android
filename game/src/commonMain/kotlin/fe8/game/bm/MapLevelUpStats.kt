package fe8.game.bm

import fe8.game.RomView
import gbafe.text.TextSystem

/**
 * The stat rows of the map-animation level-up panel: which number is drawn for a stat, from the
 * original unit in [units] and the actor's battle copy in [battles] (indexed by actor id).
 */
public class MapLevelUpStats(
    private val rom: RomView,
    private val units: UnitTable,
    private val battles: Array<BattleUnit>,
    private val text: TextSystem,
    private val bg0: ShortArray,
) {
    /**
     * Draws stat [stat] (0 = level, 1 = HP, then Pow, Skl, Spd, Lck, Def, Res, Con) of actor
     * [actorId] into BG0 at the row's label position (plus 4 columns) from panel origin
     * ([x], [y]), in the blue system color; [afterGain] adds the battle's gain.
     *
     * Verified against `ram_trace_prologue_levelup` (initial panel and the gain redraws of
     * level, HP, Skl, Lck and Def). The dash branch of the number routine is not covered.
     *
     * Ported from src/mapanim_lvupfx.c: PutManimLevelUpStat
     */
    public fun putManimLevelUpStat(actorId: Int, x: Int, y: Int, stat: Int, afterGain: Boolean) {
        val label = LABEL_INFO + stat * 12
        val at = (y + rom.u8(label + 1)) * 32 + x + rom.u8(label) + 4
        val value = getManimLevelUpBaseStat(actorId, stat) +
            if (afterGain) getManimLevelUpStatGain(actorId, stat) else 0
        text.putNumberOrBlank(bg0, at, 2, value)
    }

    /**
     * Returns the level-up change of panel stat [stat] for actor [actorId]: 1 for the level,
     * the battle copy's `change*` byte for the others, 0 for an unknown index.
     *
     * Verified for level, HP, Skl, Lck and Def in `ram_trace_prologue_levelup`; the Pow, Spd,
     * Res and Con reads are not exercised by that capture.
     *
     * Ported from src/mapanim_lvupfx.c: GetManimLevelUpStatGain
     */
    public fun getManimLevelUpStatGain(actorId: Int, stat: Int): Int = when (stat) {
        0 -> 1
        in 1..4 -> battles[actorId].bytes[0x72 + stat].toInt()
        5 -> battles[actorId].changeLck
        6 -> battles[actorId].changeDef
        7 -> battles[actorId].changeRes
        8 -> battles[actorId].changeCon
        else -> 0
    }

    /**
     * Returns panel stat [stat] of actor [actorId] before the gain: the battle copy's previous
     * level for 0, otherwise the field of the original unit (found by the battle copy's unit
     * index) with Con as character base plus class base.
     *
     * Verified through the initial-panel replay in `ram_trace_prologue_levelup`, which draws all
     * nine rows; the unit's stats there are those the panel showed.
     *
     * Ported from src/mapanim_lvupfx.c: GetManimLevelUpBaseStat
     */
    public fun getManimLevelUpBaseStat(actorId: Int, stat: Int): Int {
        val battle = battles[actorId].bytes
        val at = units.unitOffset(battle[0x0B].toInt())
        val bytes = units.bytes
        return when (stat) {
            0 -> battle[0x70].toInt()
            1 -> bytes[at + 0x12].toInt()
            in 2..4 -> bytes[at + 0x12 + stat].toInt()
            5 -> bytes[at + 0x19].toInt()
            6 -> bytes[at + 0x17].toInt()
            7 -> bytes[at + 0x18].toInt()
            8 -> {
                val character = StructCodec.u32(bytes, at)
                val unitClass = StructCodec.u32(bytes, at + 4)
                rom.u8(character + 0x13).toByte().toInt() +
                    rom.u8(unitClass + 0x11).toByte().toInt()
            }
            else -> 0
        }
    }

    private companion object {
        const val LABEL_INFO = 0x089A3ED4
    }
}
