package fe8.game.event

import fe8.game.GameControl
import fe8.game.SoundWrapper
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EV_STATE_CHANGECH
import fe8.game.event.EventConst.EV_STATE_CHANGEGM

/**
 * The move-to-chapter command of `src/eventscr.c` (`EV_CMD_CHANGECHAPTER` 0x2A,
 * `Event2A_MoveToChapter`): switches to the next chapter, returns to the title, or triggers the
 * post-game ending at the end of an event script.
 *
 * @param state the event globals (`gEventSlots[2]` supplies the chapter when the argument is
 *   negative).
 * @param memory script words (the command arguments).
 * @param playSt `gPlaySt` (`save_menu_type`).
 * @param sound `SoundWrapper` (`DeleteAll6CWaitMusicRelated` and `Sound_FadeOutBGM`).
 * @param setNextGameActionId `SetNextGameActionId` on `gProcScr_GameControl`.
 * @param setNextChapterId `SetNextChapterId` on `gProcScr_GameControl`.
 * @param gotoChapterWithoutSave `GotoChapterWithoutSave` for `EVSUBCMD_MNC3`.
 */
public class EventMoveToChapter(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val playSt: PlaySt,
    private val sound: SoundWrapper,
    private val setNextGameActionId: (Int) -> Unit,
    private val setNextChapterId: (Int) -> Unit,
    private val gotoChapterWithoutSave: (Int) -> Unit,
) {
    /** Registers command 0x2A (`EV_CMD_CHANGECHAPTER`) on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x2A, event2aMoveToChapter)
    }

    /**
     * Transcribed from src/eventscr.c: Event2A_MoveToChapter (not verified: only EVSUBCMD_MNC2
     * with non-negative chapter argument runs in PC-cap1 f9424; MNTS, MNCH, MNC3, MNC4 and the
     * negative chapter slot-2 fallback never run in captures)
     *
     * Subcommands: `EVSUBCMD_MNTS` (0) returns to title, `EVSUBCMD_MNCH` (1) moves to chapter via
     * world map (`save_menu_type = 1`), `EVSUBCMD_MNC2` (2) moves directly to battle map
     * (`save_menu_type = 2`, `GAME_ACTION_USR_SKIPPED`), `EVSUBCMD_MNC3` (3) switches without save
     * (`GotoChapterWithoutSave`), `EVSUBCMD_MNC4` (4) moves to the game ending
     * (`save_menu_type = 3`, `GAME_ACTION_PLAYED_THROUGH`). Sets `EV_STATE_CHANGECH`, cancels any
     * delayed music proc, and fades BGM out at speed 4.
     */
    public val event2aMoveToChapter: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val subcode = memory.u16(currentCmd) and 0xF
        var chIndex = memory.u16(currentCmd + 2).toShort().toInt()
        if (chIndex < 0) chIndex = state.slots[2]

        when (subcode) {
            EVSUBCMD_MNTS -> {
                setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
                proc.evStateBits = (proc.evStateBits or EV_STATE_CHANGEGM) and 0xFFFF
            }
            EVSUBCMD_MNCH -> {
                setNextChapterId(chIndex)
                playSt.saveMenuType = 1
                setNextGameActionId(GameControl.GAME_ACTION_CLASS_REEL)
                proc.evStateBits = (proc.evStateBits or EV_STATE_CHANGEGM) and 0xFFFF
            }
            EVSUBCMD_MNC2 -> {
                setNextChapterId(chIndex)
                playSt.saveMenuType = 2
                setNextGameActionId(GameControl.GAME_ACTION_USR_SKIPPED)
                proc.evStateBits = (proc.evStateBits or EV_STATE_CHANGEGM) and 0xFFFF
            }
            EVSUBCMD_MNC3 -> gotoChapterWithoutSave(chIndex and 0xFFFF)
            EVSUBCMD_MNC4 -> {
                playSt.saveMenuType = 3
                setNextGameActionId(GameControl.GAME_ACTION_PLAYED_THROUGH)
                proc.evStateBits = (proc.evStateBits or EV_STATE_CHANGEGM) and 0xFFFF
            }
        }

        proc.evStateBits = (proc.evStateBits or EV_STATE_CHANGECH) and 0xFFFF

        sound.deleteAll6CWaitMusicRelated()
        sound.fadeOutBgm(4)

        EVC_ADVANCE_CONTINUE
    }

    private companion object {
        // Sub-command numbers from `enum event_sub_cmd_idx` (`include/eventscript.h`).
        const val EVSUBCMD_MNTS = 0
        const val EVSUBCMD_MNCH = 1
        const val EVSUBCMD_MNC2 = 2
        const val EVSUBCMD_MNC3 = 3
        const val EVSUBCMD_MNC4 = 4
    }
}
