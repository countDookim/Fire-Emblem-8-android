package fe8.game.event

import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * Game state outside the unit arrays that [EventUnitState] reaches. The defaults are "nothing":
 * no active unit, an empty map, no running death fade, and no-op effects.
 */
public interface EventUnitStateHooks {
    /** `gActiveUnit` as a unit id (as taken by [UnitTable.unitOffset]), or 0 for NULL. */
    public fun activeUnit(): Int = 0

    /** `gBmMapUnit[y][x]`: the id of the unit standing on the tile, or 0. */
    public fun unitAt(x: Int, y: Int): Int = 0

    /**
     * `GetPlayerLeaderPid`. The default 1 is a stand-in until M6-I (the leader of the save is not
     * modelled here); a caller that needs the real leader overrides it.
     */
    public fun playerLeaderPid(): Int = 1

    /** `EndAllMus`: ends every running MU (map unit, the walking sprite). */
    public fun endAllMus() {}

    /**
     * `RefreshEntityBmMaps`, `RefreshUnitSprites` and `RenderBmMap`: redraws the map and the unit
     * sprites after a unit's state changed.
     */
    public fun refreshMapAndSprites() {}

    /**
     * `HideUnitSprite`, `StartMu`, `SetAutoMuDefaultFacing` and `MU_StartDeathFade` for unit
     * [unitId] (the part of the kill that is not unit state).
     */
    public fun startUnitDeathFade(unitId: Int) {}

    /** `Proc_Find(ProcScr_MuDeathFade) != 0`: a unit's death fade is still running. */
    public fun muDeathFadeExists(): Boolean = false
}

/**
 * The unit state command of `src/eventscr.c`, `Event34_MessWithUnitState` (`EV_CMD_CHANGESTATE`
 * 0x34): hides, reveals, kills, removes or changes the faction, HP or state of a unit named by an
 * event character parameter, on the unit arrays of [table].
 *
 * @param state the event slots.
 * @param memory script words (the command arguments).
 * @param table the unit arrays the command changes.
 * @param loader the unit loader (`UnitChangeFaction`, `ClearUnit`, `GetUnitMaxHp`).
 * @param hooks the game state around the unit arrays.
 */
public class EventUnitState(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val table: UnitTable,
    private val loader: UnitLoader,
    private val hooks: EventUnitStateHooks,
) {
    /** Registers 0x34 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x34, event34MessWithUnitState)
    }

    /**
     * Transcribed from src/eventscr.c: Event34_MessWithUnitState (not verified: only subs 5, 10 and
     *   15 are compared; breaks in other subs and the CLEA loop survive)
     *
     * The subcodes: REMU (hide and make unavailable), REVEAL (undo REMU), CUSA/CUSN/CUSE (move
     * the unit to the player, ally or enemy faction), SET_HP (HP from slot 1, dead at 0),
     * SET_ENDTURN (unselectable, as after ending its turn), 0x7 (`EVSUBCMD_3427`: has moved),
     * SET_STATE (deploy or undeploy from slot 1), 0x9 (`EVSUBCMD_3529`: no unit change, only the
     * map and sprite refresh), CLEA (hide all player units and drop cutscene-loaded ones), CLEN
     * and CLEE (remove every ally / enemy unit), KILL (hide and play the death fade), DISA_IF
     * (DISA once no death fade runs) and DISA (remove the unit at once). With the unit missing
     * (id < 0) KILL, DISA_IF and DISA continue, CLEA/CLEN/CLEE do not need a unit and every
     * other subcode is an error. `UnitChangeFaction` is partial in [UnitLoader] (`gActiveUnit` is
     * not updated), the MU parts of KILL are [EventUnitStateHooks].
     *
     * Verification: partial. The differential test compares the unit arrays only for subcodes
     * 5 (SET_HP), 10 (CLEA) and 15 (DISA) in the 8 windows of the trace that touch units; the
     * other subcodes are not checked against the game. For CLEA only the unit state words are
     * compared with `US_HIDDEN` masked out, which hides its main effect (hiding the units), so
     * its hide / clear-bits loop is unverified.
     */
    public val event34MessWithUnitState: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val subcmd = memory.u16(cur) and 0xF
        val unit = getUnitStructFromEventParameter(memory.u16(cur + 2).toShort().toInt())
        val missing = unit < 0
        when (subcmd) {
            SUB_CLEA, SUB_CLEN, SUB_CLEE -> {}
            SUB_KILL, SUB_DISA_IF, SUB_DISA -> if (missing) return@EventHandler EVC_ADVANCE_CONTINUE
            else -> if (missing) return@EventHandler EVC_ERROR
        }
        when (subcmd) {
            SUB_REMU -> setBits(unit, US_HIDDEN or US_BIT16 or US_BIT26)
            SUB_REVEAL -> clearBits(unit, US_HIDDEN or US_BIT16 or US_BIT26)
            SUB_CUSA, SUB_CUSN, SUB_CUSE -> loader.unitChangeFaction(unit, FACTIONS[subcmd - 2])
            SUB_SET_HP -> setUnitHp(unit, state.slots[1])
            SUB_SET_ENDTURN -> setBits(unit, US_UNSELECTABLE)
            SUB_HAS_MOVED -> setBits(unit, US_HAS_MOVED)
            SUB_SET_STATE -> setDeployed(unit, state.slots[1])
            SUB_CLEA -> hideAllAllies()
            SUB_CLEN -> clearFaction(0x41, 0x80)
            SUB_CLEE -> clearFaction(0x81, 0xC0)
            SUB_KILL -> {
                if ((proc.evStateBits and EV_STATE_SKIPPING) == 0) {
                    setBits(unit, US_HIDDEN)
                    hooks.startUnitDeathFade(unit)
                    return@EventHandler EVC_ADVANCE_YIELD
                }
                return@EventHandler EVC_ADVANCE_CONTINUE
            }
            SUB_DISA_IF, SUB_DISA -> {
                if (subcmd == SUB_DISA_IF && hooks.muDeathFadeExists()) {
                    return@EventHandler EVC_STOP_YIELD
                }
                loader.clearUnit(table.unitOffset(unit))
            }
        }
        hooks.refreshMapAndSprites()
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_utils.c: GetUnitStructFromEventParameter
     *
     * The id of the unit that event character parameter [pid] names (the leader for 0, the
     * active unit for -1, the unit at the tile in slot 0xB for -2, the character of slot 2 for
     * -3), or -1 for NULL.
     *
     * Differences from C: for -2 the result is -1 when `table.unitOffset(id) < 0`, whereas C
     * `GetUnit` accepts any non-zero map id. Pid 0 uses the stand-in (M6-I)
     * [EventUnitStateHooks.playerLeaderPid]. Verification: only the `pid > 0` path is reached by
     * the trace; the special parameters are unverified.
     */
    public fun getUnitStructFromEventParameter(pid: Int): Int {
        if (pid < -3) return -1
        var charId = pid
        when (pid) {
            -3 -> charId = state.slots[2] and 0xFFFF
            -2 -> {
                val slot = state.slots[0xB]
                val id = hooks.unitAt(slot and 0xFFFF, slot ushr 16)
                return if (id != 0 && table.unitOffset(id) >= 0) id else -1
            }
            -1 -> return hooks.activeUnit().let { if (it != 0) it else -1 }
            0 -> charId = hooks.playerLeaderPid()
        }
        return table.getUnitFromCharId(charId)
    }

    /** Sets [bits] in the state word of unit [id]. */
    private fun setBits(id: Int, bits: Int) = table.setState(id, table.state(id) or bits)

    /** Clears [bits] in the state word of unit [id]. */
    private fun clearBits(id: Int, bits: Int) = table.setState(id, table.state(id) and bits.inv())

    /**
     * `SetUnitHp` (current HP set to [value] and capped at the unit's maximum HP) plus the dead
     * flag of subcode SET_HP when [value] is 0.
     */
    private fun setUnitHp(id: Int, value: Int) {
        val o = table.unitOffset(id)
        var hp = value.toByte().toInt()
        val max = loader.getUnitMaxHp(o)
        if (hp > max) hp = max
        table.bytes[o + OFF_CUR_HP] = hp.toByte()
        if (value == 0) setBits(id, US_DEAD)
    }

    /** SET_STATE: 1 deploys the unit, 0 undeploys it, -1 restores it from `US_BIT21`. */
    private fun setDeployed(id: Int, value: Int) {
        when (value) {
            1 -> clearBits(id, US_NOT_DEPLOYED)
            0 -> setBits(id, US_NOT_DEPLOYED)
            -1 -> if ((table.state(id) and US_BIT21) != 0) {
                setBits(id, US_NOT_DEPLOYED)
            } else {
                clearBits(id, US_NOT_DEPLOYED)
            }
        }
    }

    /** The decomp's `UNIT_IS_VALID`: whether [id] names a unit that exists and is in use. */
    private fun valid(id: Int): Boolean = table.unitOffset(id) >= 0 && table.isValid(id)

    /**
     * The CLEA loop plus `ClearCutsceneUnits`: hides every valid player unit and makes it
     * selectable and free of rescue state, then drops the units that were loaded for a cutscene.
     */
    private fun hideAllAllies() {
        hooks.endAllMus()
        for (id in 1 until 0x40) {
            if (!valid(id)) continue
            setBits(id, US_HIDDEN)
            clearBits(id, US_UNSELECTABLE or US_RESCUING or US_RESCUED)
        }
        clearCutsceneUnits()
    }

    /**
     * Ported from src/bmunit.c: ClearCutsceneUnits
     *
     * Clears the player units with `US_BIT22` that are alive; the dead ones lose the bit. The
     * unit load (`LoadUnit_800F704`) sets `US_BIT22` on a player unit it newly creates, or finds
     * dead, when the load parameter is 2 (summon), and clears it otherwise.
     * Verification: partial. A break of the `US_DEAD` branch (always clearing the unit) survives
     * the differential test, so that branch is not checked against the game.
     */
    private fun clearCutsceneUnits() {
        for (id in 1 until 0x40) {
            if (!valid(id)) continue
            val s = table.state(id)
            if ((s and US_BIT22) == 0) continue
            if ((s and US_DEAD) != 0) {
                clearBits(id, US_BIT22)
            } else {
                loader.clearUnit(table.unitOffset(id))
            }
        }
    }

    /**
     * The CLEN / CLEE loops: clears every valid unit with id in [first] until [end] (the ally
     * ids 0x41..0x7F and the enemy ids 0x81..0xBF).
     */
    private fun clearFaction(first: Int, end: Int) {
        hooks.endAllMus()
        for (id in first until end) {
            if (valid(id)) loader.clearUnit(table.unitOffset(id))
        }
    }

    private companion object {
        // Sub-command nibbles of the command word (`EVSUBCMD_*`).
        const val SUB_REMU = 0x0
        const val SUB_REVEAL = 0x1
        const val SUB_CUSA = 0x2
        const val SUB_CUSN = 0x3
        const val SUB_CUSE = 0x4
        const val SUB_SET_HP = 0x5
        const val SUB_SET_ENDTURN = 0x6
        const val SUB_HAS_MOVED = 0x7
        const val SUB_SET_STATE = 0x8
        const val SUB_CLEA = 0xA
        const val SUB_CLEN = 0xB
        const val SUB_CLEE = 0xC
        const val SUB_KILL = 0xD
        const val SUB_DISA_IF = 0xE
        const val SUB_DISA = 0xF

        /** `allegianceLookup`: blue, green, red. */
        val FACTIONS = intArrayOf(0x00, 0x40, 0x80)

        /** Offset of the current HP byte in a unit record. */
        const val OFF_CUR_HP = 0x13

        // Unit state bits (`US_*` in bmunit.h); the decomp names the numbered ones by bit only.
        // US_BIT16 is part of `US_UNAVAILABLE`; US_BIT21 and US_BIT26 have no documented meaning.
        const val US_HIDDEN = 1 shl 0
        const val US_UNSELECTABLE = 1 shl 1
        const val US_DEAD = 1 shl 2
        const val US_NOT_DEPLOYED = 1 shl 3
        const val US_RESCUING = 1 shl 4
        const val US_RESCUED = 1 shl 5
        const val US_HAS_MOVED = 1 shl 6
        const val US_BIT16 = 1 shl 16
        const val US_BIT21 = 1 shl 21
        const val US_BIT22 = 1 shl 22
        const val US_BIT26 = 1 shl 26
    }
}
