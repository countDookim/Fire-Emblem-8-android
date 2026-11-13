package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventConst.EV_EXEC_UNK4
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventConst.EV_STATE_0020
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * Services the text commands of `src/eventscr.c` use that live outside the event engine: the
 * talk bubbles (`src/scene.c`), CG text (`src/cgtext.c`), box dialogue (`src/helpbox.c`) and the
 * portrait faces.
 */
public interface EventTextHost {
    /** `EndTalk`: ends the talk bubble scene. */
    public fun endTalk()

    /** `EndCgText`: ends the CG text. */
    public fun endCgText()

    /** `sub_808BB74`: ends the box dialogue (the decomp name is a placeholder). */
    public fun endBoxDialogue()

    /** `ChangeUnitSpritePalette(id)`: sets the map unit sprite palette to [id]. */
    public fun changeUnitSpritePalette(id: Int)

    /** `Event23_DisaleMapDisp(proc)`: hides the map display; returns its result code. */
    public fun disableMapDisp(proc: EventEngineProc): Int

    /** `RefreshBMapGraphics`: redraws the map graphics. */
    public fun refreshBMapGraphics()

    /** `BG_Fill` of gBG0/gBG1TilemapBuffer with 0 and `BG_EnableSyncByMask` of BG0 and BG1. */
    public fun clearTalkTilemaps()

    /** `Event24_EnableMapDisp(proc)`: shows the map display again; returns its result code. */
    public fun enableMapDisp(proc: EventEngineProc): Int

    /** `ClearTalkBubble`: removes the talk bubble. */
    public fun clearTalkBubble()

    /** `Proc_EndEach(gProcScr_E_FACE)`, `ResetFaces`, `ClearTalkFaceRefs`. */
    public fun resetAllFaces()

    /** `FaceExists`: whether a portrait face is on screen. */
    public fun faceExists(): Boolean

    /** Fades out every face, then `Proc_StartBlocking(gUnknown_08591DE8, proc)`. */
    public fun fadeOutFacesAndWait(proc: EventEngineProc)

    /** `ResumeTalk`: continues a talk that is waiting for the continue command. */
    public fun resumeTalk()

    /** `IsTalkActive() && !IsTalkLocked()`. */
    public fun isTalkRunning(): Boolean

    /** `CgTextExists`: whether CG text is running. */
    public fun cgTextExists(): Boolean

    /** `Proc_Find(gProcScr_BoxDialogue) != NULL`. */
    public fun boxDialogueExists(): Boolean

    /** `GetTalkChoiceResult`: the choice the player made in the talk (`sTalkChoiceResult`). */
    public fun talkChoiceResult(): Int

    /** `SetBlendAlpha(a, b)`: sets the two blend weights of the screen blend effect. */
    public fun setBlendAlpha(a: Int, b: Int)

    /** `gPlaySt.config.textSpeed`: the player's text speed option. */
    public var textSpeed: Int

    /** `InitTalk(chr, lines, unpackBubble)`: (re)initialises the talk system. */
    public fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean)

    /** `StartTalkMsg(x, y, msg)`: starts the talk for message id [msg] at ([x], [y]). */
    public fun startTalkMsg(x: Int, y: Int, msg: Int)

    /** `SetTalkFlag(flag)` with a `TALK_FLAG_*` bit. */
    public fun setTalkFlag(flag: Int)

    /** The set-up of `sub_800E290`: window off, UI gfx, `InitTalk`, `StartCgText`, flags. */
    public fun startCgText(msg: Int, flags: Int)

    /**
     * `GetBoxDialogueSize` of message [msg] after `GetStringFromIndex` and
     * `StringInsertSpecialPrefixByCtrl`: width and height (unit not stated in the decomp).
     */
    public fun boxDialogueSize(msg: Int): Pair<Int, Int>

    /** `sub_808AA04(x, y, msg, NULL)` then `SetDialogueBoxConfig(flags)`. */
    public fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int)
}

/**
 * The talk start/show/continue/wait commands (`EV_CMD_SETTEXTTYPE` 0x1A, `EV_CMD_DISPLAYTEXT`
 * 0x1B, `EV_CMD_CONTINUETEXT` 0x1C, `EV_CMD_ENDTEXT` 0x1D) and the clear screen command (0x22)
 * of `src/eventscr.c`. The decomp names the 0x1C and 0x1D handlers `Event1D_TalkContinue` and
 * `Event1D_WaitForLockTalk`.
 *
 * @param state the event slots (slot 2 is the default message, slot 0xC receives results).
 * @param memory script words (the command arguments).
 * @param host the talk, CG text, box dialogue and face services.
 */
public class EventText(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val host: EventTextHost,
) {
    /** Registers 0x1A, 0x1B, 0x1C, 0x1D and 0x22 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x1A, event1aTextStart)
        engine.register(0x1B, event1bTextShow)
        engine.register(0x1C, event1dTalkContinue)
        engine.register(0x1D, event1dWaitForLockTalk)
        engine.register(0x22, event22ClearScreen)
    }

    /**
     * Ported from src/eventscr.c: Event22_ClearScreen
     *
     * The `CLEAN` event command: clears the screen between scenes (map graphics refreshed, talk
     * tilemaps, bubble and faces cleared) and re-enables the map display
     * (`Event24_EnableMapDisp`).
     */
    public val event22ClearScreen: EventHandler = EventHandler { proc ->
        host.refreshBMapGraphics()
        host.changeUnitSpritePalette(proc.mapSpritePalIdOverride)
        host.clearTalkTilemaps()
        host.clearTalkBubble()
        host.resetAllFaces()
        host.enableMapDisp(proc)
    }

    /**
     * Ported from src/eventscr.c: Event1A_TEXTSTART
     *
     * Sets the text type for the following TEXTSHOW commands (`activeTextType`): 0 talk bubbles,
     * 3 tutorial text box, 4 solo text box, 5 (`EVSUBCMD_0x1A25`, meaning unknown) keeps the
     * current type. Subs 1 (`EVSUBCMD_REMOVEPORTRAITS`) and 2 (`EVSUBCMD_0x1A22`) also hide the
     * map display; the decomp does not say how they differ. A change of type first ends the text
     * of the old type.
     *
     * Verified path: sub 0 with the type unchanged (`ram_trace_prologue_talk`, f9864). Not in
     * any trace: a type change (the `endText` branch), subs 1 and 2 (`disableMapDisp`).
     */
    public val event1aTextStart: EventHandler = EventHandler { proc ->
        val sub = memory.u16(proc.pEventCurrent) and 0xF
        if (sub != proc.activeTextType && sub != 5) endText(proc)
        proc.activeTextType = sub
        when (sub) {
            0, 3, 4, 5 -> EVC_ADVANCE_YIELD
            1, 2 -> host.disableMapDisp(proc)
            else -> EVC_ERROR
        }
    }

    /**
     * Ported from src/eventscr.c: sub_800E640
     *
     * `sub_800E640` in the decomp, a placeholder name.
     *
     * Removes the portraits when a text scene ends: at once when the screen is faded in, else
     * fading them out first (the proc waits until they are gone).
     *
     * Verified paths: no `EV_STATE_FADEDIN`, faces exist (f10566); `EV_STATE_FADEDIN` set while
     * skipping (f10010 of `ram_trace_prologue_cutscene_skip`, the face procs end).
     */
    public fun endTextFaces(proc: EventEngineProc) {
        if (proc.evStateBits and EV_STATE_FADEDIN != 0) {
            host.clearTalkBubble()
            host.resetAllFaces()
        } else if (host.faceExists()) {
            host.clearTalkBubble()
            host.fadeOutFacesAndWait(proc)
        }
    }

    /**
     * Ends every kind of text on screen (talk, CG text, box dialogue) and its faces; the map
     * sprite palette is restored for cutscenes.
     */
    private fun endText(proc: EventEngineProc) {
        host.endTalk()
        host.endCgText()
        host.endBoxDialogue()
        if (proc.execType == EV_EXEC_CUTSCENE) {
            host.changeUnitSpritePalette(proc.mapSpritePalIdOverride)
        }
        endTextFaces(proc)
    }

    /**
     * Ported from src/eventscr.c: sub_800E210
     *
     * Starts a talk bubble for message [msg] at the fixed talk text position (1, 1); [init]
     * first (re)initialises the talk (TEXTSHOW does, TEXTSHOW2 continues the running talk). The
     * `EV_STATE_0020` and `EV_STATE_0040` bits (both set by EVBIT_MODIFY) become the talk flags
     * `TALK_FLAG_NOSKIP` and `TALK_FLAG_NOFAST`.
     *
     * Verified path: `init` true, `EV_STATE_0020`/`EV_STATE_0040` clear (f9865). Not in any
     * trace: `init` false (TEXTSHOW2) and the two flag bits.
     */
    private fun startTalk(proc: EventEngineProc, msg: Int, init: Boolean) {
        if (init) host.initTalk(0x80, 2, true)
        // The original tests `(evStateBits & EV_STATE_0040) == 1`, which is never true (the bit
        // is 0x40), so it never overrides the text speed: it only stores 0xFF (-1 as s8) to mean
        // "nothing to restore" for Event1D_WaitForLockTalk.
        proc.overwrittenTextSpeed = -1
        host.startTalkMsg(1, 1, msg)
        if (proc.evStateBits and EV_STATE_0020 != 0) host.setTalkFlag(TALK_NOSKIP)
        if (proc.evStateBits and EV_STATE_0040 != 0) host.setTalkFlag(TALK_NOFAST)
    }

    /**
     * Transcribed from src/eventscr.c: sub_800E290 (not verified: no CG text in any trace)
     *
     * Starts CG text for message [msg] with its flag bits: the `EV_STATE_0020` bit adds 0x40
     * (`CG_TEXT_FLAG_6`, "prevent skipping?" in the decomp) and the `EV_STATE_0040` bit adds
     * 0x2000, 0x800 and 0x20 (`CG_TEXT_FLAG_5`, "instantly show all text"). 0x400 is always
     * added; the decomp does not name 0x400, 0x800 or 0x2000.
     */
    private fun startCg(proc: EventEngineProc, msg: Int, flagsIn: Int) {
        var flags = flagsIn or 0x0400
        if (proc.evStateBits and EV_STATE_0020 != 0) flags = flags or 0x0040
        if (proc.evStateBits and EV_STATE_0040 != 0) flags = flags or 0x2000 or 0x0800 or 0x0020
        host.startCgText(msg, flags)
    }

    /**
     * Transcribed from src/eventscr.c: sub_800E31C (not verified: no box dialogue in any trace)
     *
     * Starts a box dialogue for message [msg]. Slot 0xB holds its x and y (one byte each, from
     * two halfwords); 0xFF on one axis centres the box on that axis from its measured size
     * (constants 224 and 144 as in the original), 0xFF on both puts it at (0, 0) with config bit
     * 0x100. The state bits add config bits 0x80 and 0x08. The decomp does not name the
     * `SetDialogueBoxConfig` bits.
     */
    private fun startBox(proc: EventEngineProc, msg: Int, flagsIn: Int) {
        var flags = flagsIn
        var x = state.slots[0xB] and 0xFF
        var y = (state.slots[0xB] ushr 16) and 0xFF
        if (x == 0xFF && y == 0xFF) {
            x = 0
            y = 0
            flags = flags or 0x0100
        } else {
            val (w, h) = host.boxDialogueSize(msg)
            if (x == 0xFF) {
                x = (224 - w) / 2 - 8
            } else if (y == 0xFF) {
                y = (144 - h) / 2
            }
        }
        if (proc.evStateBits and EV_STATE_0020 != 0) flags = flags or 0x0080
        if (proc.evStateBits and EV_STATE_0040 != 0) flags = flags or 0x0008
        host.startBoxDialogue(x, y, msg, flags)
    }

    /**
     * Ported from src/eventscr.c: Event1B_TEXTSHOW
     *
     * `EV_CMD_DISPLAYTEXT`: shows message `arg` (negative: the message id in slot 2; zero: do
     * nothing) in the current text type. Sub 0 (TEXTSHOW) starts a new text and clears
     * `EV_STATE_0008`, sub 1 (TEXTSHOW2) continues unless `EV_STATE_0008` is set, sub 2 (REMA) ends
     * all text. Nothing is shown while skipping events.
     *
     * Verified: sub 0 with a talk type (f9865) and sub 2 (f10566). Not in any trace: sub 1, the
     * CG and box types, a negative argument and a zero argument. At f10566 the Talk proc has
     * already ended, so the effect of `EndTalk` there is not observable.
     */
    public val event1bTextShow: EventHandler = EventHandler { proc ->
        val sub = memory.u16(proc.pEventCurrent) and 0xF
        var arg = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (sub != 2) {
            if (arg < 0) arg = state.slots[2]
            if (arg == 0) return@EventHandler EVC_ADVANCE_CONTINUE
        }
        when (sub) {
            0 -> {
                proc.evStateBits = proc.evStateBits and EV_STATE_0008.inv()
                if (proc.evStateBits and EV_STATE_SKIPPING == 0) showByType(proc, arg, true)
            }
            1 -> {
                if (proc.evStateBits and (EV_STATE_SKIPPING or EV_STATE_0008) == 0) {
                    showByType(proc, arg, false)
                }
            }
            2 -> {
                proc.evStateBits = proc.evStateBits and EV_STATE_0008.inv()
                endText(proc)
                return@EventHandler EVC_ADVANCE_YIELD
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Shows message [arg] with the text type chosen by the last TEXTSTART (`activeTextType`):
     * 0 and 1 talk bubble, 2 CG text, 3 and 5 box dialogue with config bits 0x10 (and 0x20 for 5),
     * 4 plain box dialogue. [init] is true for TEXTSHOW and false for TEXTSHOW2.
     */
    private fun showByType(proc: EventEngineProc, arg: Int, init: Boolean) {
        when (proc.activeTextType) {
            0, 1 -> startTalk(proc, arg, init)
            2 -> startCg(proc, arg, 0)
            3 -> startBox(proc, arg, 0x10)
            4 -> startBox(proc, arg, 0)
            5 -> startBox(proc, arg, 0x10 or 0x20)
        }
    }

    /**
     * Ported from src/eventscr.c: Event1D_TalkContinue
     *
     * `EV_CMD_CONTINUETEXT`: continues the talk waiting at a continue marker, or ends all text
     * while skipping.
     *
     * Verified: the not-skipping branch (`ResumeTalk` ends the `TalkLock` proc of a sprite talk),
     * the 0x1C commands of the Prologue world map (steps 5496-8110, WorldMapEventHostTest). The
     * skipping branch is not in a trace.
     */
    public val event1dTalkContinue: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) endText(proc) else host.resumeTalk()
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr.c: Event1D_WaitForLockTalk
     *
     * `EV_CMD_ENDTEXT`: waits until the talk, CG text and box dialogue are over, then stores the
     * player's talk choice in slot 0xC and restores a text speed the event had overridden (never
     * the case in the port, see `startTalk`). While text runs it blends the screen for talk and CG
     * types (blend weights 0x10 and 1; meaning of the values not documented). Skipping ends all
     * text and stores 0 in slot 0xC.
     *
     * Verified: the talk running (parked every frame, f9865..f10564), finished (f10565), and
     * skipping (f10010 of `ram_trace_prologue_cutscene_skip`: the talk tree ends). Not in any
     * trace: the world map exec types, a talk choice result.
     */
    public val event1dWaitForLockTalk: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) {
            endText(proc)
            state.slots[0xC] = 0
        } else {
            val busy = host.isTalkRunning() || host.cgTextExists() || host.boxDialogueExists()
            if (busy) {
                if (proc.execType == EV_EXEC_WORLDMAP || proc.execType == EV_EXEC_UNK4) {
                    return@EventHandler EVC_STOP_YIELD
                }
                if (proc.activeTextType in 0..2) host.setBlendAlpha(0x10, 1)
                return@EventHandler EVC_STOP_YIELD
            }
            state.slots[0xC] = host.talkChoiceResult()
        }
        if (proc.overwrittenTextSpeed != -1) host.textSpeed = proc.overwrittenTextSpeed
        EVC_ADVANCE_YIELD
    }

    private companion object {
        /** `TALK_FLAG_NOSKIP` (scene.h): the player cannot skip the talk. */
        const val TALK_NOSKIP = 1 shl 2

        /** `TALK_FLAG_NOFAST`: the player cannot speed the talk up. */
        const val TALK_NOFAST = 1 shl 3
    }
}
