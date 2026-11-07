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

/** Services the text commands of `src/eventscr.c` use that live outside the event engine. */
public interface EventTextHost {
    /** `EndTalk`. */
    public fun endTalk()

    /** `EndCgText`. */
    public fun endCgText()

    /** `sub_808BB74`. */
    public fun endBoxDialogue()

    /** `ChangeUnitSpritePalette(id)`. */
    public fun changeUnitSpritePalette(id: Int)

    /** `Event23_DisaleMapDisp(proc)`: returns its result code. */
    public fun disableMapDisp(proc: EventEngineProc): Int

    /** `ClearTalkBubble`. */
    public fun clearTalkBubble()

    /** `Proc_EndEach(gProcScr_E_FACE)`, `ResetFaces`, `ClearTalkFaceRefs`. */
    public fun resetAllFaces()

    /** `FaceExists`. */
    public fun faceExists(): Boolean

    /** Fades out every face, then `Proc_StartBlocking(gUnknown_08591DE8, proc)`. */
    public fun fadeOutFacesAndWait(proc: EventEngineProc)

    /** `ResumeTalk`. */
    public fun resumeTalk()

    /** `IsTalkActive() && !IsTalkLocked()`. */
    public fun isTalkRunning(): Boolean

    /** `CgTextExists`. */
    public fun cgTextExists(): Boolean

    /** `Proc_Find(gProcScr_BoxDialogue) != NULL`. */
    public fun boxDialogueExists(): Boolean

    /** `GetTalkChoiceResult`. */
    public fun talkChoiceResult(): Int

    /** `SetBlendAlpha(a, b)`. */
    public fun setBlendAlpha(a: Int, b: Int)

    /** `gPlaySt.config.textSpeed`. */
    public var textSpeed: Int

    /** `InitTalk(0x80, lines, 1)`. */
    public fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean)

    /** `StartTalkMsg(x, y, msg)`. */
    public fun startTalkMsg(x: Int, y: Int, msg: Int)

    /** `SetTalkFlag(flag)` with a `TALK_FLAG_*` bit. */
    public fun setTalkFlag(flag: Int)

    /** The set-up of `sub_800E290`: window off, UI gfx, `InitTalk`, `StartCgText`, flags. */
    public fun startCgText(msg: Int, flags: Int)

    /** `GetBoxDialogueSize` of string [msg]: width and height. */
    public fun boxDialogueSize(msg: Int): Pair<Int, Int>

    /** `sub_808AA04(x, y, msg, NULL)` then `SetDialogueBoxConfig(flags)`. */
    public fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int)
}

/** The talk start/show/continue/wait commands (0x1A, 0x1B, 0x1C, 0x1D) of `src/eventscr.c`. */
public class EventText(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val host: EventTextHost,
) {
    /** Registers 0x1A, 0x1B, 0x1C and 0x1D on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x1A, event1aTextStart)
        engine.register(0x1B, event1bTextShow)
        engine.register(0x1C, event1dTalkContinue)
        engine.register(0x1D, event1dWaitForLockTalk)
    }

    /**
     * Ported from src/eventscr.c: Event1A_TEXTSTART
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
     * Verified path: no `EV_STATE_FADEDIN`, faces exist (f10566). The `EV_STATE_FADEDIN` branch
     * is in no trace.
     */
    public fun sub800E640(proc: EventEngineProc) {
        if (proc.evStateBits and EV_STATE_FADEDIN != 0) {
            host.clearTalkBubble()
            host.resetAllFaces()
        } else if (host.faceExists()) {
            host.clearTalkBubble()
            host.fadeOutFacesAndWait(proc)
        }
    }

    private fun endText(proc: EventEngineProc) {
        host.endTalk()
        host.endCgText()
        host.endBoxDialogue()
        if (proc.execType == EV_EXEC_CUTSCENE) {
            host.changeUnitSpritePalette(proc.mapSpritePalIdOverride)
        }
        sub800E640(proc)
    }

    /**
     * Ported from src/eventscr.c: sub_800E210
     *
     * Verified path: `init` true, `EV_STATE_0020`/`EV_STATE_0040` clear (f9865). Not in any
     * trace: `init` false (TEXTSHOW2) and the two flag bits.
     */
    private fun startTalk(proc: EventEngineProc, msg: Int, init: Boolean) {
        if (init) host.initTalk(0x80, 2, true)
        // `(evStateBits & EV_STATE_0040) == 1` is never true in the original.
        proc.overwrittenTextSpeed = -1
        host.startTalkMsg(1, 1, msg)
        if (proc.evStateBits and EV_STATE_0020 != 0) host.setTalkFlag(TALK_NOSKIP)
        if (proc.evStateBits and EV_STATE_0040 != 0) host.setTalkFlag(TALK_NOFAST)
    }

    /**
     * Transcribed from src/eventscr.c: sub_800E290 (not verified: no CG text in any trace)
     */
    private fun startCg(proc: EventEngineProc, msg: Int, flagsIn: Int) {
        var flags = flagsIn or 0x0400
        if (proc.evStateBits and EV_STATE_0020 != 0) flags = flags or 0x0040
        if (proc.evStateBits and EV_STATE_0040 != 0) flags = flags or 0x2000 or 0x0800 or 0x0020
        host.startCgText(msg, flags)
    }

    /**
     * Transcribed from src/eventscr.c: sub_800E31C (not verified: no box dialogue in any trace)
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
                if (proc.evStateBits ushr 2 and 1 == 0) showByType(proc, arg, true)
            }
            1 -> {
                if (proc.evStateBits ushr 2 and 1 == 0 && proc.evStateBits ushr 3 and 1 == 0) {
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
     * Transcribed from src/eventscr.c: Event1D_TalkContinue (not verified: no 0x1C in the traced
     * scripts)
     */
    public val event1dTalkContinue: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) endText(proc) else host.resumeTalk()
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr.c: Event1D_WaitForLockTalk
     *
     * Verified: the talk running (parked every frame, f9865..f10564) and finished (f10565). Not
     * in any trace: skipping, the world map exec types, a talk choice result.
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
        const val TALK_NOSKIP = 1 shl 2
        const val TALK_NOFAST = 1 shl 3
    }
}
