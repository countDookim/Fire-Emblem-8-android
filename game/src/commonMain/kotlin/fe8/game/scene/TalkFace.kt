package fe8.game.scene

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep

/** Services the talk faces use that are outside the ported functions. */
public interface TalkFaceHost {
    /** `IsBattleDeamonActive`. */
    public fun isBattleDeamonActive(): Boolean

    /** `GetUnitPortraitId(gActiveUnit)`. */
    public fun activeUnitPortraitId(): Int

    /** `GetPortraitData(fid)->img`: GBA address of the LZ77 face tiles. */
    public fun portraitImage(fid: Int): Int

    /** `GetPortraitData(fid)->pal`: GBA address of the 16 face colours. */
    public fun portraitPalette(fid: Int): Int

    /** `StartFaceFadeIn(face)` (palette fade procs). */
    public fun startFaceFadeIn(face: Proc)

    /** `sub_80066E0(face, faceId)`: swaps the portrait of a loaded face. */
    public fun changeFacePortrait(face: Proc, faceId: Int)
}

/** State of a `gProcScr_E_FACE` proc (`struct FaceProc`). */
public class FaceState {
    /** `displayBits` (0x30). */
    public var displayBits: Int = 0

    /** `xPos` (0x34). */
    public var xPos: Int = 0

    /** `yPos` (0x36). */
    public var yPos: Int = 0

    /** `oam2` (0x3C). */
    public var oam2: Int = 0

    /** `faceId` (0x3E). */
    public var faceId: Int = 0

    /** `faceSlot` (0x40). */
    public var faceSlot: Int = 0

    /** `spriteLayer` (0x41). */
    public var spriteLayer: Int = 0
}

/** State of a `gProcScr_TalkFaceMove` proc. */
public class TalkFaceMoveState {
    /** `unk58`. */
    public var t: Int = 0

    /** `unk5C`. */
    public var duration: Int = 0

    /** `unk64`: face slot. */
    public var slot: Int = 0

    /** `unk66`: target talk face. */
    public var to: Int = 0

    /** `unk68`: start x. */
    public var fromX: Int = 0

    /** `unk6A`. */
    public var isSwap: Boolean = false
}

/**
 * The talk face side of `src/scene.c` (load, layer, move, mouth) over a minimal `src/face.c`:
 * `gFaces`, `StartFace` (palette, tiles, fields) and the `gProcScr_E_FACE` proc. Blink and
 * mouth animation procs and the face sprite output are not modelled. A face handle in
 * [TalkState.faces] is [FACE_HANDLE] plus the face slot.
 */
public class TalkFace(
    private val engine: ProcEngine,
    private val talk: Talk,
    private val host: TalkHost,
    private val faceHost: TalkFaceHost,
) {
    /** `gFaces`. */
    public val gFaces: Array<Proc?> = arrayOfNulls(FACE_SLOTS)

    private val faceInitFn = ProcFunc { faceOnInit(it) }
    private val faceIdleFn = ProcFunc { }
    private val moveInitFn = ProcFunc { talkFaceMoveOnInit(it) }
    private val moveIdleFn = ProcFunc { talkFaceMoveOnIdle(it) }

    /** `gProcScr_E_FACE` (without the camera-move wait; the idle draws nothing yet). */
    public val faceScript: ProcScript = ProcScript(
        "gProcScr_E_FACE",
        listOf(sleep(0), call(faceInitFn), call(faceIdleFn), repeat(faceIdleFn), end()),
    ) { FaceState() }

    /** `gProcScr_TalkFaceMove`. */
    public val moveScript: ProcScript = ProcScript(
        "gProcScr_TalkFaceMove",
        listOf(sleep(1), call(moveInitFn), repeat(moveIdleFn), end()),
    ) { TalkFaceMoveState() }

    /** The face proc of a [TalkState.faces] handle, or null. */
    public fun faceOf(handle: Int): Proc? =
        if (handle == 0) null else gFaces[handle - FACE_HANDLE]

    private fun handleOf(slot: Int): Int = FACE_HANDLE + slot

    private fun hpos(talkFace: Int): Int = FACE_HPOS[talkFace]

    /**
     * Transcribed from src/face.c: Face_OnInit
     */
    public fun faceOnInit(proc: Proc) {
        val f = proc.state as FaceState
        host.decompressToVram(faceHost.portraitImage(f.faceId), TILE_OFFSET[f.faceSlot] + OBJ_VRAM)
    }

    /**
     * Transcribed from src/face.c: StartFace (the part up to the display bits; the blink procs
     * are not started).
     */
    public fun startFace(slot: Int, fid: Int, x: Int, y: Int, disp: Int): Proc? {
        if (gFaces[slot] != null) return null
        val proc = engine.start(faceScript, 5)
        gFaces[slot] = proc
        val f = proc.state as FaceState
        if (disp and (1 shl 13) == 0) {
            host.applyPalette(faceHost.portraitPalette(fid), PAL_ID[slot] + 0x10)
        }
        f.faceSlot = slot
        f.faceId = fid
        f.spriteLayer = 5
        f.xPos = x
        f.yPos = y
        setFaceDisplayBits(proc, disp)
        return proc
    }

    /**
     * Transcribed from src/face.c: StartFaceAuto
     */
    public fun startFaceAuto(fid: Int, x: Int, y: Int, disp: Int): Proc? {
        val slot = gFaces.indexOfFirst { it == null }
        return if (slot < 0) null else startFace(slot, fid, x, y, disp)
    }

    /**
     * Transcribed from src/face.c: SetFaceDisplayBits and FaceRefreshSprite (the sprite pointer
     * is not modelled, only `oam2`).
     */
    public fun setFaceDisplayBits(proc: Proc, disp: Int) {
        val f = proc.state as FaceState
        f.displayBits = disp
        val layer = when (disp and (0xF shl 6)) {
            1 shl 6 -> 0
            2 shl 6 -> 1
            8 shl 6 -> 3
            else -> 2
        }
        f.oam2 = TILE_OFFSET[f.faceSlot] / 0x20 + (PAL_ID[f.faceSlot] and 0xF) * 0x1000 +
            (layer shl 10)
    }

    /**
     * Ported from src/scene.c: TalkLoadFace
     */
    public fun talkLoadFace(proc: Proc) {
        val s = talk.state
        var faceDisp = 0
        if (s.activeFaceSlot == 0xFF) talk.setActiveTalkFace(1)
        check(!faceHost.isBattleDeamonActive()) { "SetupFaceGfxDataInBanim not ported" }
        faceDisp = faceDisp or 2
        if (hpos(s.activeFaceSlot) <= 14) faceDisp = faceDisp or 1
        var faceId = byteAt(s.str) or (byteAt(s.str + 1) shl 8)
        faceId = if (faceId == 0xFFFF) faceHost.activeUnitPortraitId() else faceId - 0x100
        val existing = faceOf(s.faces[s.activeFaceSlot])
        if (existing != null) {
            faceHost.changeFacePortrait(existing, faceId)
            return
        }
        val face = startFaceAuto(faceId, hpos(s.activeFaceSlot) * 8, 80, faceDisp)
        s.faces[s.activeFaceSlot] =
            if (face == null) 0 else handleOf((face.state as FaceState).faceSlot)
        if (face != null) faceHost.startFaceFadeIn(face)
        setTalkFaceLayer(s.activeFaceSlot, talk.checkTalkFlag(TalkFlags.FLAG_4))
        host.startTemporaryLock(proc, 8)
    }

    /**
     * Ported from src/scene.c: SetTalkFaceLayer
     */
    public fun setTalkFaceLayer(talkFace: Int, toBack: Int) {
        if (toBack != 0) return
        val iStart = if (talkFace in 3..5) 3 else 0
        val iEnd = if (talkFace in 3..5) 5 else 2
        for (i in iStart..iEnd) {
            val face = faceOf(talk.state.faces[i]) ?: continue
            (face.state as FaceState).spriteLayer = if (i == talkFace) 5 else 6
        }
    }

    /**
     * Ported from src/scene.c: MoveTalkFace
     */
    public fun moveTalkFace(from: Int, to: Int) {
        val faces = talk.state.faces
        var isSwap = false
        if (faces[to] != 0) {
            isSwap = true
            startTalkFaceMove(to, from, true)
        }
        startTalkFaceMove(from, to, isSwap)
        val h = faces[from]
        faces[from] = faces[to]
        faces[to] = h
    }

    /**
     * Ported from src/scene.c: IsTalkFaceMoving
     */
    public fun isTalkFaceMoving(): Boolean = engine.find(moveScript) != null

    /**
     * Ported from src/scene.c: StartTalkFaceMove
     */
    public fun startTalkFaceMove(from: Int, to: Int, isSwap: Boolean) {
        val slot = faceIdByXPos(hpos(from) * 8)
        if (slot == -1) return
        val face = gFaces[slot]!!
        val proc = engine.start(moveScript, face)
        val m = proc.state as TalkFaceMoveState
        m.slot = slot
        m.to = to
        m.fromX = (face.state as FaceState).xPos
        m.isSwap = isSwap
    }

    /**
     * Ported from src/scene.c: GetFaceIdByXPos
     */
    public fun faceIdByXPos(x: Int): Int {
        for (i in 0 until 4) {
            val f = gFaces[i] ?: continue
            if ((f.state as FaceState).xPos == x) return i
        }
        return -1
    }

    /**
     * Ported from src/scene.c: TalkFaceMove_OnInit
     */
    public fun talkFaceMoveOnInit(proc: Proc) {
        val m = proc.state as TalkFaceMoveState
        m.t = 0
        m.duration = if (Math.abs(m.fromX - hpos(m.to) * 8) > 24) 32 else 16
    }

    /**
     * Ported from src/scene.c: TalkFaceMove_OnIdle
     */
    public fun talkFaceMoveOnIdle(proc: Proc) {
        val m = proc.state as TalkFaceMoveState
        val f = gFaces[m.slot]!!.state as FaceState
        if (m.duration > 16) {
            if (m.t == m.duration / 8) f.yPos++
            if (m.t == m.duration / 2) f.yPos--
            if (m.t == m.duration * 5 / 8) f.yPos++
        } else {
            if (m.t == m.duration / 2) f.yPos++
        }
        if (m.t >= m.duration) {
            f.yPos--
            engine.breakProc(proc)
        } else {
            f.xPos = Interpolate.interpolate(
                Interpolate.RSQUARE, m.fromX, hpos(m.to) * 8, m.t++, m.duration,
            )
        }
    }

    /**
     * Ported from src/scene.c: SetTalkFaceDisp
     */
    public fun setTalkFaceDisp(talkFace: Int, faceDisp: Int) {
        if (talkFace == 0xFF) return
        val face = faceOf(talk.state.faces[talkFace]) ?: return
        val f = face.state as FaceState
        val disp = f.displayBits and (SMILE or TALK_1 or TALK_2).inv()
        val smile = if (talk.state.faceSmileEnabled != 0) SMILE else 0
        setFaceDisplayBits(face, disp or faceDisp or smile)
    }

    /**
     * Transcribed from src/scene.c: SetTalkFaceMouthMove
     */
    public fun setTalkFaceMouthMove(talkFace: Int): Unit = setTalkFaceDisp(talkFace, TALK_1)

    /**
     * Transcribed from src/scene.c: SetTalkFaceNoMouthMove
     */
    public fun setTalkFaceNoMouthMove(talkFace: Int): Unit = setTalkFaceDisp(talkFace, 0)

    private fun byteAt(addr: Int): Int =
        host.stringArray(addr)[host.stringOffset(addr)].toInt() and 0xFF

    public companion object {
        /** Value of a face handle for slot 0. */
        public const val FACE_HANDLE: Int = 0x02030000
        private const val FACE_SLOTS = 4
        private const val OBJ_VRAM = 0x06010000
        private const val SMILE = 1 shl 3
        private const val TALK_1 = 1 shl 4
        private const val TALK_2 = 1 shl 5
        private val TILE_OFFSET = intArrayOf(0x20 * 0x300, 0x20 * 0x280, 0x20 * 0x200, 0x20 * 0x380)
        private val PAL_ID = intArrayOf(6, 7, 8, 9)

        /** `gTalkFaceHPosLut`. */
        private val FACE_HPOS = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)
    }
}
