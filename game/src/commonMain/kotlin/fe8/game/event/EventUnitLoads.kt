package fe8.game.event

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitLoader.UnitDef
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventConst.EV_STATE_UNITCAM
import gbafe.core.rng.RngService

/**
 * The unit load commands of `src/eventscr.c` (`Event2B_ConfigLoadUnit`, `Event2C_LoadUnits`) and
 * their helpers. Definitions are held as [UnitDef] values; `gLoadUnitBuffer` is a list of them.
 *
 * @param state the event slots.
 * @param memory script words (the command arguments).
 * @param rom the ROM the definitions at the commands' addresses are read from.
 * @param table the unit arrays the units are loaded into.
 * @param loader the unit loader (`LoadUnit_800F704`).
 * @param rng the game's random number service.
 * @param terrainAt `gBmMapTerrain[y][x]`.
 * @param unitAt `gBmMapUnit[y][x]`.
 * @param clearOtherMap `BmMapFill(gBmMapOther, 0)`.
 */
public class EventUnitLoads(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val rom: RomView,
    private val table: UnitTable,
    private val loader: UnitLoader,
    private val rng: RngService,
    private val terrainAt: (x: Int, y: Int) -> Int,
    private val unitAt: (x: Int, y: Int) -> Int = { _, _ -> 0 },
    private val clearOtherMap: () -> Unit = {},
) {
    /** Registers 0x2B and 0x2C on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x2B, event2bConfigLoadUnit)
        engine.register(0x2C, event2cLoadUnits)
    }

    private fun subCmd(cur: Int): Int = memory.u16(cur) and 0xF

    /**
     * Ported from src/eventscr.c: Event2B_ConfigLoadUnit
     *
     * Sets the unit count (subcode 0), chance (1, clamped to 0..100) or REDA disabling (2).
     */
    public val event2bConfigLoadUnit: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        var argument = memory.u16(cur + 2).toShort().toInt()
        if (argument < 0) argument = state.slots[2]
        when (subCmd(cur)) {
            0 -> proc.unitLoadCount = argument.toShort().toInt()
            1 -> proc.chance = argument.coerceIn(0, 100)
            2 -> proc.disableReda = true
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event2C_LoadUnits
     *
     * partial: only the skipping / faded-in path (`EventLoadUnitSliently`). The walking path
     * (`EventLoadUnitWithMovement`: camera, MU) and subcode 3 (`GetChapterEnemyUnitDefinitions`)
     * throw [UnsupportedOperationException]; definitions must be in the ROM.
     */
    public val event2cLoadUnits: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val subcode = subCmd(cur)
        var argument = memory.u16(cur + 2) and 0xFF
        var address = memory.u16(cur + 4) or (memory.u16(cur + 6) shl 16)
        var count = proc.unitLoadCount and 0xFFFF
        when (subcode) {
            1, 0, 2 -> {
                if (subcode == 1) argument = 2
                if (address < 0) address = state.slots[2]
            }
            3 -> throw UnsupportedOperationException("GetChapterEnemyUnitDefinitions not ported")
        }
        if (count == 0) count = getLoadUnitsAmount(address)
        val defs = getUnitDefinitionFromEventScr(
            readDefs(address, count), count, proc.chance, subcode == 2, proc.disableReda,
        )
        clearOtherMap()
        if ((proc.evStateBits and EV_STATE_SKIPPING) != 0 ||
            (proc.evStateBits and EV_STATE_FADEDIN) != 0
        ) {
            eventLoadUnitSliently(defs, count, argument)
            proc.unitLoadCount = 0
            proc.chance = 0
        } else {
            throw UnsupportedOperationException(
                "EventLoadUnitWithMovement (TryPrepareEventUnitMovement, MU) not ported",
            )
        }
        proc.disableReda = false
        EVC_ADVANCE_YIELD
    }

    private fun readDefs(address: Int, count: Int): List<UnitDef> =
        List(count) { UnitDef(rom, address + 20 * it) }

    /**
     * Ported from src/eventscr.c: GetLoadUnitsAmount
     *
     * The number of definitions at ROM [address] before the one with character 0.
     */
    public fun getLoadUnitsAmount(address: Int): Int {
        var result = 0
        while (rom.u8(address + 20 * result) != 0) result++
        return result and 0xFFFF
    }

    /**
     * Ported from src/eventscr.c: ShouldUNITBeLoaded
     * Ported from src/eventinfo.c: IsSethLArachelMyrrhInnes
     * Ported from src/bmunit.c: IsUnitSlotAvailable
     *
     * Whether [def] is to be loaded for load parameter [param]: a blue character that is dead
     * is skipped for 0 and, unless Seth, L'Arachel, Innes or Myrrh, for 2; a red one needs a
     * free tile (unless summoned or walking in) and a free red slot.
     */
    public fun shouldUnitBeLoaded(def: UnitDef, param: Int): Boolean {
        if (def.allegiance == 0 && param != 1) {
            val id = table.getUnitFromCharId(def.charIndex)
            if (id >= 0 && (table.state(id) and US_DEAD) != 0) {
                when (param) {
                    0 -> return false
                    2 -> if (def.charIndex !in SETH_LARACHEL_MYRRH_INNES) return false
                }
            }
        }
        if (def.allegiance != 2) return true
        if (!def.sumFlag && def.redaCount == 0 && unitAt(def.x, def.y) != 0) return false
        return (0x81 until 0xC0).any { table.unitOffset(it) >= 0 && !table.isValid(it) }
    }

    /**
     * Ported from src/eventscr.c: EventLoadUnitSliently
     *
     * Loads up to [count] of [defs] that [shouldUnitBeLoaded] accepts, quietly.
     */
    public fun eventLoadUnitSliently(defs: List<UnitDef>, count: Int, param: Int) {
        var left = count.toShort().toInt()
        for (def in defs) {
            if (def.charIndex == 0 || left <= 0) break
            if (!shouldUnitBeLoaded(def, param)) continue
            loader.loadUnitForEvent(def, 1, true, param == 2, terrainAt)
            left--
        }
    }

    /**
     * Ported from src/eventscr.c: GetUnitDefinitionFromEventScr
     *
     * partial: [sub80125c0] (`arg3`, subcode 2) throws [UnsupportedOperationException].
     *
     * Builds `gLoadUnitBuffer` from the first [count] of [source]: [chance] percent of the
     * summon-flagged ones (rounded, chosen with `NextRN_N`) get `sumFlag` set and go last, the
     * others get it cleared and keep their order. With [disableReda] the REDAs are removed.
     */
    public fun getUnitDefinitionFromEventScr(
        source: List<UnitDef>,
        count: Int,
        chance: Int,
        sub80125c0: Boolean,
        disableReda: Boolean,
    ): List<UnitDef> {
        val summon = ArrayList<Int>()
        var left = 0
        if (chance != 0) {
            for (i in 0 until count) if (source[i].sumFlag) summon.add(i)
            left = (summon.size * chance + 50) / 100
        }
        val chosen = BooleanArray(count)
        while (left != 0) {
            val r = summon[rng.nextN(summon.size)]
            if (!chosen[r]) {
                chosen[r] = true
                left--
            }
        }
        val out = ArrayList<UnitDef>()
        for (i in 0 until count) if (!chosen[i]) out.add(source[i].copy(sumFlag = false))
        for (i in 0 until count) if (chosen[i]) out.add(source[i].copy(sumFlag = true))
        val result = if (disableReda) {
            out.map { it.copy(redaCount = 0, redas = 0) }
        } else {
            out
        }
        if (sub80125c0) throw UnsupportedOperationException("sub_80125C0 not ported")
        return result
    }

    /**
     * Ported from src/eventscr.c: ModifyMoveUnitFlag
     *
     * The move flags for a load: bit 0 if [unk], bit 2 with the unit camera, bit 3 with
     * `EV_STATE_0040`.
     */
    public fun modifyMoveUnitFlag(proc: EventEngineProc, unk: Boolean): Int {
        var result = if (unk) 1 else 0
        if ((proc.evStateBits and EV_STATE_UNITCAM) != 0) result = result or 4
        if ((proc.evStateBits and EV_STATE_0040) != 0) result = result or 8
        return result
    }

    private companion object {
        const val US_DEAD = 1 shl 2
        val SETH_LARACHEL_MYRRH_INNES = intArrayOf(0x02, 0x19, 0x0B, 0x1E)
    }
}
