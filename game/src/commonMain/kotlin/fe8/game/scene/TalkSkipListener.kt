package fe8.game.scene

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat

/**
 * Services needed by [TalkSkipListener] (`gProcScr_TalkSkipListener`, `src/scene.c`):
 * reading `gKeyStatusPtr->newKeys`, skipping the dialogue (`SetDialogueSkipEvBit`,
 * `SetTalkFaceNoMouthMove`), and blanking/syncing BG0 and BG1.
 */
public interface TalkSkipListenerHost {
    /** `gKeyStatusPtr->newKeys`: buttons newly pressed this frame. */
    public fun newKeys(): Int

    /** `SetDialogueSkipEvBit()`: marks the event engine as skipping dialogue. */
    public fun setDialogueSkipEvBit()

    /** `SetTalkFaceNoMouthMove(faceSlot)`: stops the active portrait's mouth animation. */
    public fun setTalkFaceNoMouthMove(faceSlot: Int)

    /**
     * `BG_Fill(gBG0TilemapBuffer, 0)`, `BG_Fill(gBG1TilemapBuffer, 0)` and
     * `BG_EnableSyncByMask(BG0_SYNC_BIT | BG1_SYNC_BIT)`.
     */
    public fun clearTalkBgTilemaps()
}

/**
 * Dialogue skip and fast-forward listener (`gProcScr_TalkSkipListener`, `src/scene.c:38, 494`).
 * Started on `PROC_TREE_3` by `Talk_OnInit` and ended by `Talk_OnEnd`.
 */
public class TalkSkipListener(
    private val procs: ProcEngine,
    private val talk: Talk,
    private val shiftClearScript: ProcScript,
    private val shiftClearAllScript: ProcScript,
    private val host: TalkSkipListenerHost,
) {
    /**
     * `gProcScr_TalkSkipListener` (`src/scene.c:38`): watches B/START (dialogue skip) and
     * D-pad/A/B (instant text scroll) while a talk runs (`PROC_TREE_3`).
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_TalkSkipListener",
        listOf(
            mark(Talk.PROC_MARK_TALK),
            repeat(ProcFunc { onIdle(it) }),
            ProcScripts.end(),
        ),
    )

    /** Starts [script] on `PROC_TREE_3` (`Proc_Start(gProcScr_TalkSkipListener, PROC_TREE_3)`). */
    public fun start(): Proc = procs.start(script, TREE_3)

    /** Ends every running [script] proc (`Proc_EndEach(gProcScr_TalkSkipListener)`). */
    public fun end() {
        procs.endEach(script)
    }

    /**
     * Per-frame body of `gProcScr_TalkSkipListener`. While a line shift runs it does nothing. A
     * B or START press (unless `TALK_FLAG_NOSKIP`) skips the dialogue: sets the dialogue-skip
     * event bit, stops mouth movement, ends the listener and the talk, and blanks BG0 and BG1.
     * Otherwise, unless the press-key arrow is up, a D-pad, A or B press (unless
     * `TALK_FLAG_NOFAST`) switches to instant printing (`instantScroll = 1`).
     *
     * Partially ported from src/scene.c: TalkSkipListener_OnIdle (verified: `PC-cap1`
     * `f10534..10820` — `Proc_Start`/`Proc_EndEach` lifecycle with `gProcScr_Talk`, `A_BUTTON`
     * setting `instantScroll = 1` at `f10600` when `gProcScr_TalkWaitForInput` is absent, and the
     * `gProcScr_TalkWaitForInput` guard returning before setting `instantScroll` at `f10800`; not
     * verified: `gProcScr_TalkShiftClearAll` and `gProcScr_TalkShiftClear` guards have
     * `newKeys == 0` during every shift in `PC-cap1`, `TALK_FLAG_NOSKIP`/`TALK_FLAG_NOFAST` stay
     * clear, and `B_BUTTON`/`START_BUTTON`/`DPAD_ANY` are never pressed in `PC-cap1`).
     */
    public fun onIdle(proc: Proc) {
        if (procs.find(shiftClearAllScript) != null) return
        if (procs.find(shiftClearScript) != null) return
        val talkState = talk.state
        val newKeys = host.newKeys()
        if (talk.checkTalkFlag(TalkFlags.NOSKIP) == 0 && newKeys and (B_BUTTON or START) != 0) {
            host.setDialogueSkipEvBit()
            host.setTalkFaceNoMouthMove(talkState.activeFaceSlot)
            procs.end(proc)
            procs.endEach(talk.talkScript)
            host.clearTalkBgTilemaps()
            return
        }
        if (procs.find(talk.waitForInputScript) != null) return
        if (talk.checkTalkFlag(TalkFlags.NOFAST) == 0 && newKeys and ANY_KEY != 0) {
            talkState.instantScroll = 1
        }
    }

    private companion object {
        /** `PROC_TREE_3`: proc tree where `gProcScr_TalkSkipListener` runs. */
        const val TREE_3 = 3

        /** `B_BUTTON`. */
        const val B_BUTTON = 2

        /** `START_BUTTON`. */
        const val START = 8

        /** `DPAD_ANY | A_BUTTON | B_BUTTON`: keys that switch to instant text printing. */
        const val ANY_KEY = 0xF3
    }
}
