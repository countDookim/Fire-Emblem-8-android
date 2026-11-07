package fe8.game.event

import fe8.game.scene.Talk
import fe8.game.scene.TalkBubble
import fe8.game.scene.TalkBubbleHost
import fe8.game.scene.TalkFace
import fe8.game.scene.TalkHost
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/**
 * [EventTextHost] over the ported talk code ([Talk], [TalkBubble], [TalkFace]).
 *
 * [stringFromIndex] is `GetStringFromIndex` reduced to what `StartTalkExt` needs: it makes the
 * message current and returns the GBA address of its string (the address [TalkHost] resolves
 * through `stringArray`). Members whose callee is not ported are stand-ins (no-ops where the
 * thing cannot exist yet) or throw `not ported`.
 */
public class EventTextTalkHost(
    private val procs: ProcEngine,
    private val talk: Talk,
    private val bubble: TalkBubble,
    private val face: TalkFace,
    private val talkHost: TalkHost,
    private val bubbleHost: TalkBubbleHost,
    private val stringFromIndex: (Int) -> Int,
) : EventTextHost {
    private val whileFaceFn = ProcFunc { p -> if (!faceExists()) procs.breakProc(p) }

    /** `gUnknown_08591DE8`: waits until no face proc is left. */
    public val waitForFacesScript: ProcScript = ProcScript(
        "gUnknown_08591DE8",
        listOf(repeat(whileFaceFn), yield(), end()),
    )

    /** `Proc_EndEach(gProcScr_Talk)`. */
    override fun endTalk() {
        procs.endEach(talk.talkScript)
    }

    /** Stand-in: CG text is not ported, so none can exist and there is nothing to end. */
    override fun endCgText() {}

    /** Stand-in: the box dialogue is not ported, so none can exist and there is nothing to end. */
    override fun endBoxDialogue() {}

    /** Stand-in: `ChangeUnitSpritePalette` (a palette load, no state) is not ported: no-op. */
    override fun changeUnitSpritePalette(id: Int) {}

    override fun disableMapDisp(proc: EventEngineProc): Int {
        throw NotImplementedError("not ported: Event23_DisaleMapDisp")
    }

    override fun clearTalkBubble() {
        bubble.clearTalkBubble()
    }

    /**
     * `Proc_EndEach(gProcScr_E_FACE)`, `ResetFaces`, `ClearTalkFaceRefs`.
     *
     * Stand-in: `ResetFaces` (face gfx bookkeeping of `src/face.c`) is not ported; the face slots
     * are only emptied.
     */
    override fun resetAllFaces() {
        procs.endEach(face.faceScript)
        face.gFaces.fill(null)
        talk.clearTalkFaceRefs()
    }

    /** `FaceExists`: a `gProcScr_E_FACE` proc exists. */
    override fun faceExists(): Boolean = procs.find(face.faceScript) != null

    /** `Proc_ForEach(gProcScr_E_FACE, StartFaceFadeOut)`, then the blocking wait proc. */
    override fun fadeOutFacesAndWait(proc: EventEngineProc) {
        for (slot in face.gFaces.indices) {
            if (face.gFaces[slot] != null) talkHost.startFaceFadeOut(TalkFace.FACE_HANDLE + slot)
        }
        procs.startBlocking(waitForFacesScript, proc.proc!!)
    }

    override fun resumeTalk() {
        talk.resumeTalk()
    }

    /** `IsTalkActive() && !IsTalkLocked()`. */
    override fun isTalkRunning(): Boolean =
        procs.find(talk.talkScript) != null && !talk.isTalkLocked()

    /** Stand-in: CG text is not ported, so none exists. */
    override fun cgTextExists(): Boolean = false

    /** Stand-in: the box dialogue is not ported, so none exists. */
    override fun boxDialogueExists(): Boolean = false

    /** Stand-in: `sTalkChoiceResult` is only written by the (not ported) choice proc: 0. */
    override fun talkChoiceResult(): Int = 0

    override fun setBlendAlpha(a: Int, b: Int) {
        bubbleHost.setBlendAlpha(a, b)
    }

    /** Stand-in: `gPlaySt.config.textSpeed`; the play state is not wired here. */
    override var textSpeed: Int = 0

    override fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean) {
        talk.initTalk(chr, lines, unpackBubble)
    }

    /**
     * `StartTalkMsg(x, y, msg)`: `StartTalkExt` on the string of [msg], no parent.
     *
     * Ported from src/scene.c: StartTalkMsg, StartTalkExt
     *
     * Verified without a parent proc (f9865); the `Proc_StartBlocking` branch is in no trace.
     */
    override fun startTalkMsg(x: Int, y: Int, msg: Int) {
        val s = talk.state
        s.xText = x
        s.yText = y
        s.str = stringFromIndex(msg)
        s.strBackup = 0
        s.printColor = 1
        s.lineActive = 0
        s.unk82 = 0
        s.topTextNum = 0
        s.printDelay = talkHost.textDisplaySpeed()
        s.printClock = 0
        talk.setActiveTalkFace(0xFF)
        s.speakingFaceSlot = -1
        s.putLines = 0
        s.instantScroll = 0
        s.mouthMoveEnabled = 1
        s.faceSmileEnabled = 0
        s.config = 0
        s.unk38 = null
        s.invertedFlags = 0
        s.activeWidth = (talkHost.getStrTalkLen(s.str, false) + 7) / 8 + 2
        procs.start(talk.talkScript, TALK_TREE)
    }

    override fun setTalkFlag(flag: Int) {
        talk.setTalkFlag(flag)
    }

    override fun startCgText(msg: Int, flags: Int) {
        throw NotImplementedError("not ported: StartCgText")
    }

    override fun boxDialogueSize(msg: Int): Pair<Int, Int> {
        throw NotImplementedError("not ported: GetBoxDialogueSize")
    }

    override fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int) {
        throw NotImplementedError("not ported: sub_808AA04")
    }

    private companion object {
        const val TALK_TREE = 3
    }
}
