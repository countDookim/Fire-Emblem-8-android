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
 * What the walking load (units that walk onto the map during a cutscene) reaches outside the unit
 * system: the camera and the map unit (MU) pool. Defaults: nothing blocks.
 */
public interface EventUnitLoadsHost {
    /** `Proc_Find(ProcScr_CamMove) != NULL`: a camera move is already running. */
    public fun camMoveExists(): Boolean = false

    /**
     * `EnsureCameraOntoPosition(proc, x, y)`: moves the camera to show ([x], [y]); true if a
     * camera move was started (the unit then has to wait).
     */
    public fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int): Boolean = false

    /** `CanStartMu`: a free MU (map unit, the walking sprite) slot exists. */
    public fun canStartMu(): Boolean = true
}

/**
 * The unit load commands of `src/eventscr.c` (`Event2B_ConfigLoadUnit` = `EV_CMD_LOAD_PRECONF`,
 * `Event2C_LoadUnits` = `EV_CMD_LOADUNIT`) and their helpers: they put the units of a chapter's
 * `UnitDefinition` list (character, class, allegiance, position, REDA move script) on the map,
 * either walking in or at once. Definitions are held as [UnitDef] values; `gLoadUnitBuffer` is a
 * list of them.
 *
 * @param state the event slots.
 * @param memory script words (the command arguments).
 * @param rom the ROM the definitions at the commands' addresses are read from.
 * @param table the unit arrays the units are loaded into.
 * @param loader the unit loader (`LoadUnit_800F704`).
 * @param rng the game's random number service.
 * @param terrainAt `gBmMapTerrain[y][x]`: the terrain id at a tile.
 * @param unitAt `gBmMapUnit[y][x]`: the unit id standing on a tile, 0 for none.
 * @param clearOtherMap `BmMapFill(gBmMapOther, 0)`: clears the scratch map before a load.
 * @param host camera and MU queries of the walking load.
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
    private val host: EventUnitLoadsHost = object : EventUnitLoadsHost {},
) {
    /** The definitions of the walking load in progress (the C's `pUnitLoadData` list). */
    private var pendingDefs: List<UnitDef> = emptyList()

    /** Index in [pendingDefs] of the next definition to load. */
    private var pendingIndex: Int = 0

    /** Registers 0x2B and 0x2C on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x2B, event2bConfigLoadUnit)
        engine.register(0x2C, event2cLoadUnits)
    }

    /** The sub-command nibble of the command at [cur]. */
    private fun subCmd(cur: Int): Int = memory.u16(cur) and 0xF

    /**
     * Transcribed from src/eventscr.c: Event2B_ConfigLoadUnit (not verified: test restates C
     *   semantics; no trace comparison yet)
     *
     * Configures the next LOAD: the unit count (`EVSUBCMD_LOAD_SETCOUNT`, subcode 0), the
     * percent chance that summon-flagged definitions are loaded (`EVSUBCMD_LOAD_SETCHANCE`, 1,
     * clamped to 0..100) or REDA disabling (`EVSUBCMD_LOAD_NOREDA`, 2: the units appear without
     * their move script, for the next LOAD only). A negative argument is taken from slot 2.
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
     * Loads the definitions at the command's address (the "LOAD" command): subcode 0 normal, 1
     * the same with load parameter 2 (summon, see [shouldUnitBeLoaded]), 2 with [sub80125c0]
     * (unknown purpose), 3 the chapter's enemy definitions. While skipping or faded in the units
     * appear at once, otherwise they walk in over several frames.
     *
     * partial: subcode 3 (`GetChapterEnemyUnitDefinitions`) throws
     * [UnsupportedOperationException]; definitions must be in the ROM.
     */
    public val event2cLoadUnits: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val subcode = subCmd(cur)
        // The load parameter, see `shouldUnitBeLoaded`: 0 normal, 1 never skips a dead unit,
        // 2 summon.
        var argument = memory.u16(cur + 2) and 0xFF
        var address = memory.u16(cur + 4) or (memory.u16(cur + 6) shl 16)
        var count = proc.unitLoadCount and 0xFFFF
        when (subcode) {
            1, 0, 2 -> {
                if (subcode == 1) argument = 2
                // A negative definition address is taken from slot 2.
                if (address < 0) address = state.slots[2]
            }
            3 -> throw UnsupportedOperationException("GetChapterEnemyUnitDefinitions not ported")
        }
        // No count set by the LOAD config: load every definition up to the terminating (character
        // 0) one.
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
            proc.pUnitLoadData = address
            pendingDefs = defs
            pendingIndex = 0
            proc.unitLoadCount = count
            proc.unitLoadParameter = argument
            proc.callback = EventEngineCallback { eventLoadUnitWithMovement(it) }
            eventLoadUnitWithMovement(proc)
        }
        proc.disableReda = false
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr.c: EventLoadUnitWithMovement (not verified: mocked MU capacity;
     *   no trace comparison yet)
     *
     * The walking load, run once by [event2cLoadUnits] and then as the proc's callback each frame
     * until every definition is loaded;
     * a definition whose [tryPrepareEventUnitMovement] fails is retried the next frame. The
     * definitions are the ones kept by [event2cLoadUnits] (the C's `pUnitLoadData` pointer).
     */
    public fun eventLoadUnitWithMovement(proc: EventEngineProc) {
        var count = proc.unitLoadCount.toShort().toInt()
        val param = proc.unitLoadParameter
        val moveFlags = modifyMoveUnitFlag(proc, true)
        val summon = param == 2
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) {
            eventLoadUnitSliently(pendingDefs.drop(pendingIndex), count, param)
        } else {
            while (pendingIndex < pendingDefs.size) {
                val def = pendingDefs[pendingIndex]
                if (def.charIndex == 0 || count <= 0) break
                if (!shouldUnitBeLoaded(def, param)) {
                    pendingIndex++
                    continue
                }
                if (!tryPrepareEventUnitMovement(proc, def.x, def.y)) {
                    proc.unitLoadCount = count
                    return
                }
                loader.loadUnitForEvent(def, moveFlags, false, summon, terrainAt)
                count--
                pendingIndex++
            }
        }
        proc.unitLoadCount = 0
        proc.chance = 0
        proc.callback = null
    }

    /**
     * Transcribed from src/eventscr.c: TryPrepareEventUnitMovement (not verified: mocked host; no
     *   trace comparison yet)
     *
     * Whether a unit may start walking in at ([x], [y]): with the unit camera on
     * (`EV_STATE_UNITCAM`), no camera move may exist or be started to show the tile, and an MU
     * slot must be free.
     */
    public fun tryPrepareEventUnitMovement(proc: EventEngineProc, x: Int, y: Int): Boolean {
        if (proc.evStateBits and EV_STATE_UNITCAM != 0) {
            if (host.camMoveExists()) return false
            if (host.ensureCameraOntoPosition(proc, x, y)) return false
        }
        return host.canStartMu()
    }

    /** Reads [count] `UnitDefinition`s (20 bytes each: 0x14) from ROM [address]. */
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
     * Ported from src/eventinfo.c: IsSethLArachelMyrrhInnes
     *
     * Whether [pid] is Seth, L'Arachel, Innes or Myrrh.
     */
    public fun isSethLArachelMyrrhInnes(pid: Int): Boolean =
        pid in SETH_LARACHEL_MYRRH_INNES

    /**
     * Transcribed from src/bmunit.c: IsUnitSlotAvailable (not verified: synthetic slot fill; no
     *   trace comparison yet)
     *
     * Whether there is an available unit slot in [faction] (faction + 1 until faction + 0x40).
     */
    public fun isUnitSlotAvailable(faction: Int): Boolean {
        for (i in (faction + 1) until (faction + 0x40)) {
            if (table.unitOffset(i) >= 0 && !table.isValid(i)) return true
        }
        return false
    }

    /**
     * Transcribed from src/eventscr.c: ShouldUNITBeLoaded (not verified: synthetic dead unit and
     *   tile cases; no trace comparison yet)
     *
     * Whether [def] is to be loaded for load parameter [param]: a player-allegiance (0) character
     * who is dead is skipped for parameter 0 and, unless Seth, L'Arachel, Innes or Myrrh, for 2
     * (parameter 1 never skips); an enemy-allegiance (2, `FACTION_ID_RED`) definition needs a
     * free red slot, and an empty tile unless it is summon-flagged or has a REDA move script.
     */
    public fun shouldUnitBeLoaded(def: UnitDef, param: Int): Boolean {
        if (def.allegiance == 0 && param != 1) {
            val id = table.getUnitFromCharId(def.charIndex)
            if (id >= 0 && (table.state(id) and US_DEAD) != 0) {
                when (param) {
                    0 -> return false
                    2 -> if (!isSethLArachelMyrrhInnes(def.charIndex)) return false
                }
            }
        }
        if (def.allegiance != 2) return true
        if (!def.sumFlag && def.redaCount == 0 && unitAt(def.x, def.y) != 0) return false
        return isUnitSlotAvailable(0x80)
    }

    /**
     * Ported from src/eventscr.c: EventLoadUnitSliently
     *
     * Loads up to [count] of [defs] that [shouldUnitBeLoaded] accepts, at once (no walking).
     * Used while skipping a cutscene or when the screen is faded in.
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
     * Transcribed from src/eventscr.c: ModifyMoveUnitFlag (not verified: test restates bit
     *   combinations; no trace comparison yet)
     *
     * The move flags for a load: bit 0 if [unk], bit 2 with the unit camera
     * (`EV_STATE_UNITCAM`), bit 3 with `EV_STATE_0040`. The decomp has a TODO for naming the
     * bits; their meaning is not documented there.
     */
    public fun modifyMoveUnitFlag(proc: EventEngineProc, unk: Boolean): Int {
        var result = if (unk) 1 else 0
        if ((proc.evStateBits and EV_STATE_UNITCAM) != 0) result = result or 4
        if ((proc.evStateBits and EV_STATE_0040) != 0) result = result or 8
        return result
    }

    private companion object {
        /** `US_DEAD`: the unit is dead. */
        const val US_DEAD = 1 shl 2

        /** Character ids of Seth, L'Arachel, Innes and Myrrh. */
        val SETH_LARACHEL_MYRRH_INNES = intArrayOf(0x02, 0x19, 0x0B, 0x1E)
    }
}
