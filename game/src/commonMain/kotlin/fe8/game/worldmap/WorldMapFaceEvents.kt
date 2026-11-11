package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/**
 * The world map portrait commands `EventC4_WmShowPortrait` and `EventC5_WmClearPortrait` of
 * `src/eventscr_gmap.c`. Transcribed (not verified through the event engine: that comparison is
 * D11-wm8a..f; the faces they start are verified in [WorldMapFace]). Not registered anywhere yet;
 * [registerOn] does it.
 *
 * @param memory script words (the command arguments)
 * @param faces the portrait controller
 * @param wmMergeFace `WmMergeFace(delay, kind, faceSlot, fid, 0, 0, config)`: the delayed variant
 *   (kind 6 draws, 7 clears), not ported yet
 */
public class WorldMapFaceEvents(
    private val memory: ScriptMemory,
    private val faces: WorldMapFace,
    private val wmMergeFace: (delay: Int, kind: Int, slot: Int, fid: Int, config: Int) -> Unit =
        { _, _, _, _, _ -> },
) {
    /** Registers the handlers under opcodes 0xC4 and 0xC5 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0xC4, eventC4WmShowPortrait)
        engine.register(0xC5, eventC5WmClearPortrait)
    }

    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    /** `EVT_CMD_ARGV(cmd)[index]`: the signed halfword at `cmd + 2 + 2 * index`. */
    private fun argv(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 + 2 * index).toShort().toInt()

    /**
     * Transcribed from src/eventscr_gmap.c: EventC4_WmShowPortrait (not verified: see the class
     * note). Shows the portrait at once, or through the delayed merge when `delay` is set;
     * nothing while skipping.
     */
    public val eventC4WmShowPortrait: EventHandler = EventHandler { proc ->
        val slot = argv(proc, 1)
        val fid = argv(proc, 2)
        val config = argv(proc, 3)
        val delay = argv(proc, 4)
        if (!skipping(proc)) {
            if (delay != 0) {
                wmMergeFace(delay, 6, slot, fid, config)
            } else {
                faces.wmDrawFace(slot, fid, config and 0xFFFF)
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventC5_WmClearPortrait (not verified: see the class
     * note). Slides the portrait out at once, or through the delayed merge when `delay` is set;
     * while skipping it ends the face outright.
     */
    public val eventC5WmClearPortrait: EventHandler = EventHandler { proc ->
        val slot = argv(proc, 0) and 0xFFFF
        val config = argv(proc, 1) and 0xFFFF
        val delay = argv(proc, 2) and 0xFFFF
        if (skipping(proc)) {
            faces.endFaceById(slot)
        } else if (delay != 0) {
            wmMergeFace(delay, 7, slot, 0, config)
        } else {
            faces.wmClearFace(slot, config)
        }
        EVC_ADVANCE_CONTINUE
    }
}
