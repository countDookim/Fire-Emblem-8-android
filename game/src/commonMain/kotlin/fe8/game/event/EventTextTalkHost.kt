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
 * [EventTextHost] over the ported talk code ([Talk], [TalkBubble], [TalkFace]): what the event
 * text commands (talk start, continue, clear screen, map display lock) call to run the speech
 * bubbles and portrait faces.
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

    /**
     * Per-frame function of [waitForFacesScript] (`_WhileFace6CExists`, src/eventscr.c:1215):
     * ends the wait once no portrait face proc is left.
     */
    private val whileFaceFn = ProcFunc { p -> if (!faceExists()) procs.breakProc(p) }

    /**
     * `gUnknown_08591DE8` (src/eventscr.c:1222, "face witness" in include/event.h): the blocking
     * proc that holds the event until every portrait face has faded out and ended.
     */
    public val waitForFacesScript: ProcScript = ProcScript(
        "gUnknown_08591DE8",
        listOf(repeat(whileFaceFn), yield(), end()),
    )

    /** `Proc_EndEach(gProcScr_Talk)`: ends every running talk bubble scene. */
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
     *
     * "Lock game graphics": the first time (`EV_STATE_GFXLOCKED` clear) suspends the map display
     * (`BMapDispSuspend`) and blocks every map unit proc (`LockMus` is
     * `Proc_BlockEachMarked(PROC_MARK_MU)`), so units stop animating under a full-screen scene.
     * Always sets the flag and yields.
     */
    override fun disableMapDisp(proc: EventEngineProc): Int {
        if (proc.evStateBits and EventConst.EV_STATE_GFXLOCKED == 0) {
            suspendMapDisp()
            procs.blockEachMarked(Mu.PROC_MARK_MU)
        }
        proc.evStateBits = proc.evStateBits or EventConst.EV_STATE_GFXLOCKED
        return EventConst.EVC_ADVANCE_YIELD
    }

    /** `RefreshBMapGraphics` through the injected `refreshMapGraphics` (a no-op by default). */
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
     *
     * "Resume game graphics": the inverse of [disableMapDisp]. If `EV_STATE_GFXLOCKED` is set it
     * resumes the map display (`BMapDispResume`) and unblocks the map unit procs (`ReleaseMus`);
     * always clears the flag and yields.
     */
    override fun enableMapDisp(proc: EventEngineProc): Int {
        if (proc.evStateBits and EventConst.EV_STATE_GFXLOCKED != 0) {
            resumeMapDisp()
            procs.unblockEachMarked(Mu.PROC_MARK_MU)
        }
        proc.evStateBits = proc.evStateBits and EventConst.EV_STATE_GFXLOCKED.inv()
        return EventConst.EVC_ADVANCE_YIELD
    }

    /** `ClearTalkBubble`: removes the speech bubble from the screen. */
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
     *
     * NOTE: the decomp function (src/eventscr2.c:35-41) starts with `ClearTalkBubble()`; this
     * one does not, so it is [clearTalkBubble] plus this that equals `ResetDialogueScreen`. All
     * callers (`EventText.endTextFaces`, `event22ClearScreen`, `WorldMapBoot`) pair them in that
     * order, so there is no behaviour difference today.
     */
    override fun resetAllFaces() {
        procs.endEach(face.faceScript)
        face.resetFaces()
        talk.clearTalkFaceRefs()
    }

    /** `FaceExists` (src/scene.c:2096): a portrait face proc (`gProcScr_E_FACE`) exists. */
    override fun faceExists(): Boolean = procs.find(face.faceScript) != null

    /**
     * The `else if (FaceExists())` branch of `sub_800E640` (src/eventscr.c:1206-1211) without its
     * `ClearTalkBubble()`: starts the fade-out of every portrait face
     * (`Proc_ForEach(gProcScr_E_FACE, StartFaceFadeOut)`, here every occupied face slot), then
     * blocks [proc] on [waitForFacesScript] until they are gone. The caller does the
     * `FaceExists` test and `ClearTalkBubble`.
     */
    override fun fadeOutFacesAndWait(proc: EventEngineProc) {
        for (slot in face.gFaces.indices) {
            if (face.gFaces[slot] != null) talkHost.startFaceFadeOut(TalkFace.FACE_HANDLE + slot)
        }
        procs.startBlocking(waitForFacesScript, proc.proc!!)
    }

    /** `ResumeTalk`: lets a talk that is waiting for the continue command go on. */
    override fun resumeTalk() {
        talk.resumeTalk()
    }

    /**
     * `IsTalkActive() && !IsTalkLocked()` (the first test of the text wait at
     * src/eventscr.c:1151): a talk bubble scene is running and not held by a talk lock.
     */
    override fun isTalkRunning(): Boolean =
        procs.find(talk.talkScript) != null && !talk.isTalkLocked()

    /** Stand-in (M6-E): CG text is not ported, so none exists. */
    override fun cgTextExists(): Boolean = false

    /** Stand-in (M6-E): the box dialogue is not ported, so none exists. */
    override fun boxDialogueExists(): Boolean = false

    /** Stand-in (M6-E): `sTalkChoiceResult` is only written by the (not ported) choice proc: 0. */
    override fun talkChoiceResult(): Int = 0

    /** `SetBlendAlpha(a, b)` through the bubble host: the two blend weights of the screen. */
    override fun setBlendAlpha(a: Int, b: Int) {
        bubbleHost.setBlendAlpha(a, b)
    }

    /**
     * The player's text speed option, `gPlaySt.config.textSpeed`, through [readTextSpeed] and
     * [writeTextSpeed] (`Fe8Boot` passes both); without them a plain field (the play state is
     * not wired).
     */
    override var textSpeed: Int
        get() = readTextSpeed?.invoke() ?: textSpeedField
        set(value) {
            if (writeTextSpeed != null) writeTextSpeed.invoke(value) else textSpeedField = value
        }

    /**
     * `InitTalk(chr, lines, unpackBubble)` (src/scene.c:310): prepares the talk text layer: [chr]
     * is the BG0 tile index where the text font starts, [lines] the number of text lines;
     * when [unpackBubble] is set the bubble graphics and palette are
     * loaded, and the face references are cleared (`ClearTalkFaceRefs`).
     */
    override fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean) {
        talk.initTalk(chr, lines, unpackBubble)
    }

    /**
     * `StartTalkMsg(x, y, msg)`: starts the talk scene (`gProcScr_Talk`) for message id [msg] at
     * ([x], [y]): `StartTalkExt` on the string of [msg], no parent.
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
        // `Div(GetStrTalkLen(str, 0) + 7, 8) + 2`: the text length divided by 8 rounded up, plus 2
        // (the unit of GetStrTalkLen is not stated in the decomp).
        talkState.activeWidth = (talkHost.getStrTalkLen(talkState.str, false) + 7) / 8 + 2
        procs.start(talk.talkScript, TALK_TREE)
    }

    /** `SetTalkFlag(flag)`: sets a `TALK_FLAG_*` bit of the talk scene. */
    override fun setTalkFlag(flag: Int) {
        talk.setTalkFlag(flag)
    }

    /** Not ported: throws, so a script that reaches CG text fails loudly. */
    override fun startCgText(msg: Int, flags: Int) {
        throw NotImplementedError("not ported: StartCgText")
    }

    /** Not ported: throws, `GetBoxDialogueSize` does not exist yet. */
    override fun boxDialogueSize(msg: Int): Pair<Int, Int> {
        throw NotImplementedError("not ported: GetBoxDialogueSize")
    }

    /** Not ported: throws, `sub_808AA04` does not exist yet. */
    override fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int) {
        throw NotImplementedError("not ported: sub_808AA04")
    }

    private companion object {
        /** Proc tree the talk proc is started in (`StartTalkExt` uses tree 3). */
        const val TALK_TREE = 3
    }
}
