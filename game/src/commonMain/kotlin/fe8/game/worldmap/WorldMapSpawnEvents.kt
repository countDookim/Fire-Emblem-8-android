package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/**
 * The world map commands `EventC2_WmNoFade` and `EventC3_SpawnLord` of `src/eventscr_gmap.c`
 * (the two the Prologue's opening script runs). Register them one by one with
 * `engine.register(0xC2, ...)`.
 *
 * @param memory script words (the command arguments)
 * @param gmData `gGMData`
 * @param setGmCharUnit `SetGmCharUnit(id, pid, allegiance, nodeId)`
 */
public class WorldMapSpawnEvents(
    private val memory: ScriptMemory,
    private val gmData: GmData,
    private val setGmCharUnit: (id: Int, pid: Int, allegiance: Int, nodeId: Int) -> Unit,
) {
    /**
     * Ported from src/eventscr_gmap.c: EventC2_WmNoFade
     *
     * Sets `gGMData.state.bits.no_fade` (bit 7 of the first byte).
     */
    public val eventC2WmNoFade: EventHandler = EventHandler { _ ->
        gmData.stateRaw = gmData.stateRaw or NO_FADE
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventC3_SpawnLord
     *
     * Puts character `pid` on unit slot `unitId` at node `nodeId` unless the slot already holds
     * that character. A class slot (state bit 1) is not run by any capture and fails loudly.
     */
    public val eventC3SpawnLord: EventHandler = EventHandler { proc ->
        val unitId = arg16(proc, 2)
        val pid = arg16(proc, 3)
        val nodeId = arg16(proc, 4) or (arg16(proc, 5) shl 16)
        if (gmData.unitState(unitId) and 2 == 0) {
            if (gmData.unitId(unitId) == pid) return@EventHandler EVC_ADVANCE_CONTINUE
        } else {
            error("not verified: no capture spawns on a class slot")
        }
        setGmCharUnit(unitId, pid, -1, nodeId)
        EVC_ADVANCE_CONTINUE
    }

    /** The unsigned halfword at halfword [index] of the command. */
    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    private companion object {
        /** `GMStateBits.no_fade`. */
        const val NO_FADE = 0x80
    }
}
