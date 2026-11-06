package fe8.game.event

import fe8.game.SoundWrapper
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/** The BGM start command of `src/eventscr.c` (0x12 STARTBGM) on [sound]. */
public class EventBgm(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val sound: SoundWrapper,
) {
    /** Registers 0x12 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x12, event12StartBgm)
    }

    /**
     * Transcribed from src/eventscr.c: Event12_StartBGM (not verified:
     *   EventVmDifferentialTest compares only the result code, not the song start)
     *
     * Not while skipping: starts (or changes to) the song of the s16 argument (a negative one
     * reads slot 2) with `StartBgmExt(song, 3, NULL)` and yields.
     */
    public val event12StartBgm: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        var song = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (song < 0) song = state.slots[2]
        sound.startBgmExt(song, 3, -1)
        EVC_ADVANCE_YIELD
    }
}
