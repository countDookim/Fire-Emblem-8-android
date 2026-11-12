package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/**
 * The world map portrait commands (the character face that slides in beside the world map while
 * a character talks) `EventC4_WmShowPortrait` and `EventC5_WmClearPortrait` of
 * `src/eventscr_gmap.c`, verified through the event VM for steps 5701-7781 of the Prologue
 * (`WorldMapEventFaceTest`; the faces they start are verified in [WorldMapFace]). The skipping
 * branches and the delayed merge (`delay != 0`) are not run by the capture. [registerOn]
 * registers both.
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

    /** `EVENT_IS_SKIPPING(proc)`: the player is skipping the event. */
    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    /** `EVT_CMD_ARGV(cmd)[index]` (event.h:138): the signed halfword at `cmd + 2 + 2 * index`. */
    private fun argv(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 + 2 * index).toShort().toInt()

    /**
     * Ported from src/eventscr_gmap.c: EventC4_WmShowPortrait
     *
     * Shows portrait `fid` in face slot `slot` ([WorldMapFace.wmDrawFace]) at once, or through
     * the delayed merge (`WmMergeFace` kind 6) when `delay` is set; nothing while skipping. The
     * command's halfwords at `+4..+10` are slot, fid, config and delay. In `WmDrawFace` the low
     * byte of `config` is the face's x, bit 0x400 flips it and bits 0x2000/0x4000 pick the
     * sprite layer (`worldmap_face.c:317-355`); the face is always drawn at y 28.
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
     * Ported from src/eventscr_gmap.c: EventC5_WmClearPortrait
     *
     * Slides the portrait of face slot `slot` out ([WorldMapFace.wmClearFace]) at once, or
     * through the delayed merge (`WmMergeFace` kind 7) when `delay` is set; while skipping it
     * ends the face outright (`EndFaceById`). Unlike `EventC4` the halfwords here start at
     * `ARGV[0]` (`+2`): slot, config, delay.
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
