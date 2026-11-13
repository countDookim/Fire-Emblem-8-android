package fe8.game.event

import fe8.game.SoundWrapper
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * Event music commands from `src/eventscr.c` on [sound]: 0x12 (`Event12_StartBGM`, start a
 * song), 0x13 (`Event13_BgmFadeIn`, here only the fade-out form) and 0x15 (`Event15_BgmVolume`,
 * lower or restore the music volume around a scene). 0x14 (`Event14_BgmOverideRestore`) is not
 * ported here.
 */
public class EventBgm(
    /** Event global variables and parameter slots (`gEventSlots`). */
    private val state: EventState,
    /** Script word accessor resolving ROM and memory words. */
    private val memory: ScriptMemory,
    /** Game sound wrapper driving audio player and volume transitions. */
    private val sound: SoundWrapper,
) {
    /**
     * Registers event command handlers 0x12 and 0x15 on [engine]; 0x13 is registered separately
     * (`WorldMapBoot`).
     */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x12, event12StartBgm)
        engine.register(0x15, event15BgmVolume)
    }

    /**
     * Ported from src/eventscr.c: Event12_StartBGM
     *
     * Starts or changes the background music to the song id in the signed 16-bit argument
     * (`EVT_CMD_ARGV[0]`, `EvtStartBgm`) with speed 3 (`StartBgmExt(song, 3, NULL)`, the speed
     * of the change; its meaning is not documented in the decomp), and yields. A negative
     * argument takes the song id from `gEventSlots[2]`. While the scene is being skipped
     * (`EV_STATE_SKIPPING`) the music is left alone and the command continues.
     */
    public val event12StartBgm: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        var song = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (song < 0) song = state.slots[2]
        // -1 stands for the C call's NULL third argument.
        sound.startBgmExt(song, 3, -1)
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr.c: Event13_BgmFadeIn (not verified: `Sound_FadeOutBGM(2)`
     * leaves no state a trace shows without an audio player; only the dispatch and the yield
     * are checked in WorldMapEventHostTest)
     *
     * Only the `MUSCFAST(0x7FFF)` branch is run by the Prologue (`Sound_FadeOutBGM(subcode)`,
     * src/eventscr.c:618-625): the argument 0x7FFF (`INT16_MAX`) means "fade the music out",
     * with the sub-command nibble as fade speed (`EVSUBCMD_MUSCFAST` = 2 ... `MUSCSSLOW` = 8,
     * include/eventscript.h:375-378), or speed 1 while skipping.
     *
     * NOTE: any other argument is the decomp's fade-in branch (`StartBgmFadeIn(song, subcode, 0)`,
     * with a negative song taken from slot 2, and a plain continue while skipping,
     * src/eventscr.c:607-617); this port throws for it instead.
     */
    public val event13BgmFadeIn: EventHandler = EventHandler { proc ->
        val subcode = memory.u16(proc.pEventCurrent) and 0xF
        val argument = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        check(argument == 0x7FFF) { "not verified: no capture runs StartBgmFadeIn" }
        sound.fadeOutBgm(if (proc.evStateBits and EV_STATE_SKIPPING != 0) 1 else subcode)
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr.c: Event15_BgmVolume
     *
     * Verified (EventBgmTest) for subcode 0 against `ram_trace_prologue` (the one command in the
     * trace): the blocking volume-change proc's parent, flags, volumes and time, and the parent's
     * lock count. Subcode 1 and the skipping branches never run in a trace and are unverified.
     *
     * Subcode 0 (`EVSUBCMD_MUSI`, `EvtSetVolumeDown`) lowers the music volume from 0x100 to 0x90
     * (`StartBgmVolumeChange(0x100, 0x90, 10, proc)`, the last number being the length of the
     * change in volume-proc steps); subcode 1 (`EVSUBCMD_MUNO`, `EvtUnsetVolumeDown`) restores it
     * (0x90 to 0x100). While skipping, 0 does nothing and 1 sets the volume to 0x100 at once
     * (`Sound_SetSEVolume`, which in src/soundwrapper.c:54 controls the volume of both BGM
     * players despite its name). Yields, blocked by the volume-change proc, otherwise.
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
