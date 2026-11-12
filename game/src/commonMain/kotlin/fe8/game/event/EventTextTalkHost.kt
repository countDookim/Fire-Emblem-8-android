package fe8.game.event

import fe8.game.bm.Mu
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
 * through `stringArray`). Members whose callee is not ported are no-ops (where the thing
 * cannot exist yet) or throw `not ported`.
 */
public class EventTextTalkHost(
    private val procs: ProcEngine,
    private val talk: Talk,
    private val bubble: TalkBubble,
    private val face: TalkFace,
    private val talkHost: TalkHost,
    private val bubbleHost: TalkBubbleHost,
    /** Reads `gPlaySt.config.textSpeed`; null: the plain [textSpeed] field below. */
    private val readTextSpeed: (() -> Int)? = null,
    /** Writes `gPlaySt.config.textSpeed`; null: the plain [textSpeed] field below. */
    private val writeTextSpeed: ((Int) -> Unit)? = null,
    /** `RefreshBMapGraphics`; the default does nothing. */
    private val refreshMapGraphics: () -> Unit = {},
    /** `BMapDispSuspend`; the default does nothing. */
    private val suspendMapDisp: () -> Unit = {},
    /** `BMapDispResume`; the default does nothing. */
    private val resumeMapDisp: () -> Unit = {},
    private val stringFromIndex: (Int) -> Int,
) : EventTextHost {
    /** The text speed used when [readTextSpeed] and [writeTextSpeed] are not given. */
    private var textSpeedField: Int = 0

    /** Per-frame function of [waitForFacesScript]: ends the wait once no face proc is left. */
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

    /** Stand-in until M6-E: CG text is not ported, so none can exist and none is ended. */
    override fun endCgText() {}

    /** Stand-in until M6-E: the box dialogue is not ported, so none can exist, nothing to end. */
    override fun endBoxDialogue() {}

    /** Stand-in (M6-E): `ChangeUnitSpritePalette` (a palette load, no state) is not ported. */
    override fun changeUnitSpritePalette(id: Int) {}

    /**
     * Ported from src/eventscr.c: Event23_DisaleMapDisp
     */
    override fun disableMapDisp(proc: EventEngineProc): Int {
        if (proc.evStateBits and EventConst.EV_STATE_GFXLOCKED == 0) {
            suspendMapDisp()
            procs.blockEachMarked(Mu.PROC_MARK_MU)
        }
        proc.evStateBits = proc.evStateBits or EventConst.EV_STATE_GFXLOCKED
        return EventConst.EVC_ADVANCE_YIELD
    }

    /** Calls the configured map graphics restoration. */
    override fun refreshBMapGraphics() {
        refreshMapGraphics()
    }

    /**
     * `BG_Fill(gBG0TilemapBuffer, 0)`, `BG_Fill(gBG1TilemapBuffer, 0)` and the two
     * `BG_EnableSyncByMask` calls. The sync goes through `TalkBgSync`, which skips it while the
     * `TALK_FLAG_SPRITE` flag is set (the original syncs unconditionally).
     */
    override fun clearTalkTilemaps() {
        talkHost.bg0Tilemap().fill(0)
        bubbleHost.bg1Tilemap().fill(0)
        talkHost.talkBgSync(1)
        talkHost.talkBgSync(2)
    }

    /**
     * Ported from src/eventscr.c: Event24_EnableMapDisp
     */
    override fun enableMapDisp(proc: EventEngineProc): Int {
        if (proc.evStateBits and EventConst.EV_STATE_GFXLOCKED != 0) {
            resumeMapDisp()
            procs.unblockEachMarked(Mu.PROC_MARK_MU)
        }
        proc.evStateBits = proc.evStateBits and EventConst.EV_STATE_GFXLOCKED.inv()
        return EventConst.EVC_ADVANCE_YIELD
    }

    /** `ClearTalkBubble`: removes the speech bubble. */
    override fun clearTalkBubble() {
        bubble.clearTalkBubble()
    }

    /**
     * `Proc_EndEach(gProcScr_E_FACE)`, `ResetFaces`, `ClearTalkFaceRefs`.
     *
     * Transcribed from src/eventscr2.c: ResetDialogueScreen (not verified: `ResetFaces` is checked
     * by WorldMapExitTest through the event end; `ClearTalkBubble` is a separate host call).
     * `ResetFaces` restores the default portrait VRAM / palette layout, which the world map
     * replaced.
     */
    override fun resetAllFaces() {
        procs.endEach(face.faceScript)
        face.resetFaces()
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

    /** `ResumeTalk`: lets a paused talk continue. */
    override fun resumeTalk() {
        talk.resumeTalk()
    }

    /** `IsTalkActive() && !IsTalkLocked()`. */
    override fun isTalkRunning(): Boolean =
        procs.find(talk.talkScript) != null && !talk.isTalkLocked()

    /** Stand-in (M6-E): CG text is not ported, so none exists. */
    override fun cgTextExists(): Boolean = false

    /** Stand-in (M6-E): the box dialogue is not ported, so none exists. */
    override fun boxDialogueExists(): Boolean = false

    /** Stand-in (M6-E): `sTalkChoiceResult` is only written by the (not ported) choice proc: 0. */
    override fun talkChoiceResult(): Int = 0

    /** `SetBlendAlpha(a, b)` through the bubble host. */
    override fun setBlendAlpha(a: Int, b: Int) {
        bubbleHost.setBlendAlpha(a, b)
    }

    /**
     * `gPlaySt.config.textSpeed` through [readTextSpeed] and [writeTextSpeed] (`Fe8Boot` passes
     * both); without them a plain field (the play state is not wired).
     */
    override var textSpeed: Int
        get() = readTextSpeed?.invoke() ?: textSpeedField
        set(value) {
            if (writeTextSpeed != null) writeTextSpeed.invoke(value) else textSpeedField = value
        }

    /** `InitTalk`: prepares the talk text layer. */
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
        val talkState = talk.state
        talkState.xText = x
        talkState.yText = y
        talkState.str = stringFromIndex(msg)
        talkState.strBackup = 0
        talkState.printColor = 1
        talkState.lineActive = 0
        talkState.unk82 = 0
        talkState.topTextNum = 0
        talkState.printDelay = talkHost.textDisplaySpeed()
        talkState.printClock = 0
        talk.setActiveTalkFace(0xFF)
        talkState.speakingFaceSlot = -1
        talkState.putLines = 0
        talkState.instantScroll = 0
        talkState.mouthMoveEnabled = 1
        talkState.faceSmileEnabled = 0
        talkState.config = 0
        talkState.unk38 = null
        talkState.invertedFlags = 0
        // Width in 8-pixel tiles, rounded up, plus two tiles of margin.
        talkState.activeWidth = (talkHost.getStrTalkLen(talkState.str, false) + 7) / 8 + 2
        procs.start(talk.talkScript, TALK_TREE)
    }

    /** `SetTalkFlag(flag)`. */
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
        /** Proc tree the talk proc is started in (`StartTalkExt` uses tree 3). */
        const val TALK_TREE = 3
    }
}
