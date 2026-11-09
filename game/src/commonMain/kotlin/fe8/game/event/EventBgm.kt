package fe8.game.event

import fe8.game.SoundWrapper
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * Event sound and music commands from `src/eventscr.c` (0x12 STARTBGM and 0x15 MUSCFAST) on
 * [sound].
 */
public class EventBgm(
    /** Event global variables and parameter slots (`gEventSlots`). */
    private val state: EventState,
    /** Script word accessor resolving ROM and memory words. */
    private val memory: ScriptMemory,
    /** Game sound wrapper driving audio player and volume transitions. */
    private val sound: SoundWrapper,
) {
    /** Registers event command handlers 0x12 and 0x15 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x12, event12StartBgm)
        engine.register(0x15, event15BgmVolume)
    }

    /**
     * Ported from src/eventscr.c: Event12_StartBGM
     *
     * Starts or changes the background music track to the song specified by the 16-bit argument
     * at `pEventCurrent + 2` with speed 3 (`StartBgmExt(song, 3, NULL)`), and yields execution.
     * If the song argument is negative (e.g. -1), reads the song ID from `gEventSlots[2]`.
     * When event skipping is active (`EV_STATE_SKIPPING`), does not change music and continues.
     */
    public val event12StartBgm: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        var song = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (song < 0) song = state.slots[2]
        sound.startBgmExt(song, 3, -1)
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr.c: Event15_BgmVolume (not verified:
     *   EventVmDifferentialTest compares only the result code, not the volume)
     *
     * Subcode 0 ducks the BGM (`StartBgmVolumeChange(0x100, 0x90, 10, proc)`), subcode 1 restores
     * it (`0x90` to `0x100`); while skipping, 0 does nothing and 1 sets volume 0x100. Yields
     * (blocked by the volume-change proc).
     */
    public val event15BgmVolume: EventHandler = EventHandler { proc ->
        val skipping = proc.evStateBits and EV_STATE_SKIPPING != 0
        when (memory.u16(proc.pEventCurrent) and 0xF) {
            0 -> {
                if (skipping) return@EventHandler EVC_ADVANCE_CONTINUE
                sound.startBgmVolumeChange(0x100, 0x90, 10, proc.proc)
            }
            1 -> {
                if (skipping) {
                    sound.setSeVolume(0x100)
                    return@EventHandler EVC_ADVANCE_CONTINUE
                }
                sound.startBgmVolumeChange(0x90, 0x100, 10, proc.proc)
            }
        }
        EVC_ADVANCE_YIELD
    }
}
