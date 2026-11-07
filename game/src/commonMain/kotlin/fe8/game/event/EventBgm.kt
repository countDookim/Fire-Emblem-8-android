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
    /** Registers 0x12 and 0x15 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x12, event12StartBgm)
        engine.register(0x15, event15BgmVolume)
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
