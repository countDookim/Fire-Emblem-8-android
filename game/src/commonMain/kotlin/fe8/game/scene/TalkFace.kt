package fe8.game.scene

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.render.SpriteObject

/** Services the talk faces use that are outside the ported functions. */
public interface TalkFaceHost {
    /** Resolves a face composition from its ROM address; absent in hosts that do not draw. */
    public fun faceSprite(address: Int): SpriteObject? = null

    /** `PutSpriteExt`, preserving the OAM flags in [yOam0]. */
    public fun putFaceSprite(
        layer: Int,
        xOam1: Int,
        yOam0: Int,
        sprite: SpriteObject,
        oam2: Int,
    ) {}

    /** `IsBattleDeamonActive`. */
    public fun isBattleDeamonActive(): Boolean

    /** `GetUnitPortraitId(gActiveUnit)`. */
    public fun activeUnitPortraitId(): Int

    /** `GetPortraitData(fid)->img`: GBA address of the LZ77 face tiles. */
    public fun portraitImage(fid: Int): Int

    /** `GetPortraitData(fid)->pal`: GBA address of the 16 face colours. */
    public fun portraitPalette(fid: Int): Int

    /**
     * Byte [offset] of `GetPortraitData(fid)` (a `struct FaceData`): mouth coordinates at
     * 0x14..0x15, eye coordinates at 0x16..0x17, and `blinkKind` at 0x18.
     */
    public fun portraitByte(fid: Int, offset: Int): Int

    /**
     * Registers a 4x2 mouth frame for transfer from portrait [fid] at [srcTile] to OBJ VRAM
     * tile [dstTile]. The production host applies the transfer at VBlank.
     */
    public fun copyMouthTiles(fid: Int, srcTile: Int, dstTile: Int)

    /** `AdvanceGetLCGRNValue()`. */
    public fun advanceLcg(): Int

    /** `StartFaceFadeIn(face)` (palette fade procs). */
    public fun startFaceFadeIn(face: Proc)

    /** `sub_80066E0(face, faceId)`: swaps the portrait of a loaded face. */
    public fun changeFacePortrait(face: Proc, faceId: Int)
}

/** State of a `gProcScr_E_FACE` proc (`struct FaceProc`). */
public class FaceState {
    /** Sprite composition (`sprite`, 0x38), resolved from ROM composition data. */
    public var sprite: SpriteObject? = null

    /** Display configuration bitmask (`displayBits`, 0x30). */
    public var displayBits: Int = 0

    /** Horizontal screen position in pixels (`xPos`, 0x34). */
    public var xPos: Int = 0

    /** Vertical screen position in pixels (`yPos`, 0x36). */
    public var yPos: Int = 0

    /** Base OAM2 attribute: palette bank, tile index, priority (`oam2`, 0x3C). */
    public var oam2: Int = 0

    /** Portrait definition ID (`faceId`, 0x3E). */
    public var faceId: Int = 0

    /** Allocated face slot index (0..3) (`faceSlot`, 0x40). */
    public var faceSlot: Int = 0

    /** Sprite rendering layer priority (5 or 6) (`spriteLayer`, 0x41). */
    public var spriteLayer: Int = 0

    /** Mouth animation child proc (`unk_44`); null when mouth animation is disabled (bit 12). */
    public var mouthProc: Proc? = null

    /** Blink animation child proc (`pBlinkProc`, 0x48); null when blinking is disabled (bit 12). */
    public var pBlinkProc: Proc? = null
}

/** State of a `gProcScr_0859124C` mouth child. */
public class FaceMouthState {
    /** Parent face (`pFaceProc`, offset 0x2C). */
    public var face: Proc? = null

    /** Mouth animation phase (`blinkControl`, offset 0x30); new Kotlin states start at zero. */
    public var phase: Int = 0

    /** Frames until the next mouth change (`unk_32`, offset 0x32). */
    public var timer: Int = 0
}

/** State of a `gProcScr_FaceBlink` proc (`struct FaceBlinkProc`). */
public class FaceBlinkState {
    /** `pFaceProc` (0x2C): the parent face proc. */
    public var pFaceProc: Proc? = null

    /** `blinkControl` (0x30, s16): blink kind, 1..5; selects the interval in the generator. */
    public var blinkControl: Int = 0

    /** `unk_32` (s16): requested eye state (0 blinking, 2 or 3 held closed/half, see script). */
    public var unk32: Int = 0

    /** `unk_34` (s16): frame counter inside the current blink or held state. */
    public var unk34: Int = 0

    /** `unk_38`: frames until the next blink; counts down. */
    public var unk38: Int = 0
}

/** State of a `gProcScr_TalkFaceMove` proc. */
public class TalkFaceMoveState {
    /** Elapsed movement frame counter (`unk58`). */
    public var timer: Int = 0

    /** Total duration of movement in frames (`unk5C`). */
    public var duration: Int = 0

    /** Face slot index being moved (`unk64`). */
    public var slot: Int = 0

    /** Target talk face slot destination (`unk66`). */
    public var to: Int = 0

    /** Starting X position in pixels (`unk68`). */
    public var fromX: Int = 0

    /** Whether this face is swapping places with another face (`unk6A`). */
    public var isSwap: Boolean = false
}

/**
 * The talk face side of `src/scene.c` (load, layer, move, mouth) over a minimal `src/face.c`:
 * `gFaces`, `StartFace` (palette, tiles, fields), and face animation procs. A face handle in
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
    private val faceIdleFn = ProcFunc { faceOnIdle(it) }
    private val moveInitFn = ProcFunc { talkFaceMoveOnInit(it) }
    private val moveIdleFn = ProcFunc { talkFaceMoveOnIdle(it) }

    /** `gProcScr_E_FACE` (without the camera-move wait). */
    public val faceScript: ProcScript = ProcScript(
        "gProcScr_E_FACE",
        listOf(sleep(0), call(faceInitFn), call(faceIdleFn), repeat(faceIdleFn), end()),
    ) { FaceState() }

    private val mouthInitFn = ProcFunc { sub8005FD4(it) }
    private val mouthIdleFn = ProcFunc { sub8005FE0(it) }

    /** `gProcScr_0859124C`: mouth animation child. */
    public val mouthScript: ProcScript = ProcScript(
        "gProcScr_0859124C", listOf(call(mouthInitFn), repeat(mouthIdleFn), end()),
    ) { FaceMouthState() }

    private val blinkInitFn = ProcFunc { sub800623C(it) }
    private val blinkWaitFn = ProcFunc { sub8006280(it) }
    private val blinkBlinkFn = ProcFunc { sub80062B8(it) }
    private val blinkHold2InitFn = ProcFunc { sub8006324(it) }
    private val blinkHold2Fn = ProcFunc { sub800632C(it) }
    private val blinkHold3InitFn = ProcFunc { sub8006370(it) }
    private val blinkHold3Fn = ProcFunc { sub8006378(it) }
    private val blinkHold4InitFn = ProcFunc { sub80063BC(it) }
    private val blinkHold4Fn = ProcFunc { sub80063C4(it) }

    /** `gProcScr_FaceBlink`; the duplicate label 0 is as in the original. */
    public val blinkScript: ProcScript = ProcScript(
        "gProcScr_FaceBlink",
        listOf(
            label(0), call(blinkInitFn),
            label(0), repeat(blinkWaitFn),
            label(1), repeat(blinkBlinkFn),
            label(2), call(blinkHold2InitFn), repeat(blinkHold2Fn), repeat(blinkWaitFn),
            label(3), call(blinkHold3InitFn), repeat(blinkHold3Fn), repeat(blinkWaitFn),
            label(4), call(blinkHold4InitFn), repeat(blinkHold4Fn),
            label(97), repeat(blinkHold2Fn),
            end(),
        ),
    ) { FaceBlinkState() }

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
        val faceState = proc.state as FaceState
        host.decompressToVram(
            faceHost.portraitImage(faceState.faceId),
            TILE_OFFSET[faceState.faceSlot] + OBJ_VRAM,
        )
    }

    /**
     * Submits the face composition with wrapped coordinates and its blend flag.
     *
     * Reference verified for the talk's base 96x96 faces in both orientations. Hidden,
     * blend and coordinate wrapping have behavioral checks only; animation overlays and
     * palette fades are not verified.
     *
     * Ported from src/face.c: Face_OnIdle
     */
    public fun faceOnIdle(proc: Proc) {
        val faceState = proc.state as FaceState
        if (faceState.displayBits and (1 shl 14) != 0) return
        val sprite = faceState.sprite ?: return
        val blend = if (faceState.displayBits and (1 shl 10) != 0) 0x400 else 0
        faceHost.putFaceSprite(
            faceState.spriteLayer,
            faceState.xPos and 0x1FF,
            (faceState.yPos and 0xFF) + blend,
            sprite,
            faceState.oam2,
        )
    }

    /**
     * Transcribed from src/face.c: StartFace. Unless display bit 12 is set it starts the mouth
     * and blink children in that order.
     */
    public fun startFace(slot: Int, fid: Int, x: Int, y: Int, disp: Int): Proc? {
        if (gFaces[slot] != null) return null
        val proc = engine.start(faceScript, 5)
        gFaces[slot] = proc
        val faceState = proc.state as FaceState
        if (disp and (1 shl 13) == 0) {
            host.applyPalette(faceHost.portraitPalette(fid), PAL_ID[slot] + 0x10)
        }
        faceState.faceSlot = slot
        faceState.faceId = fid
        faceState.spriteLayer = 5
        faceState.xPos = x
        faceState.yPos = y
        if (disp and (1 shl 12) == 0) {
            faceState.mouthProc = engine.start(mouthScript, proc)
            faceState.pBlinkProc = engine.start(blinkScript, proc)
        }
        setFaceDisplayBits(proc, disp)
        return proc
    }

    /**
     * Initializes the mouth child with its parent and a zero timer.
     *
     * Ported from src/face.c: sub_8005FD4
     */
    public fun sub8005FD4(proc: Proc) {
        val mouth = proc.state as FaceMouthState
        mouth.face = proc.parent!!
        mouth.timer = 0
    }

    /**
     * Updates mouth tiles and submits the 32x16 mouth sprite.
     *
     * Normal and talking mouths are checked against the Prologue trace. Smile and blend paths
     * are not reference-checked. The original retains the phase from reused proc memory; newly
     * allocated Kotlin states start at zero.
     *
     * Ported from src/face.c: sub_8005FE0
     */
    public fun sub8005FE0(proc: Proc) {
        val mouth = proc.state as FaceMouthState
        val faceState = mouth.face!!.state as FaceState
        val bits = faceState.displayBits
        val source = if (bits and SMILE != 0) 0 else 24
        if (bits and (TALK_1 or TALK_2) == 0) {
            faceHost.copyMouthTiles(faceState.faceId, source + 16, (faceState.oam2 + 28) and 0x3FF)
        } else {
            mouth.timer--
            if (mouth.timer < 0) {
                mouth.timer = ((faceHost.advanceLcg() ushr 16) and 7) + 1
                mouth.phase = (mouth.phase + 1) and 3
                val frame = when (mouth.phase) {
                    1, 3 -> 8
                    2 -> 16
                    else -> 0
                }
                faceHost.copyMouthTiles(
                    faceState.faceId, source + frame, (faceState.oam2 + 28) and 0x3FF,
                )
            }
        }
        val flipped = bits and 1 != 0
        var x = 4 - faceHost.portraitByte(faceState.faceId, 0x14)
        x = if (flipped) x else -x
        x = (x * 8 + faceState.xPos - 16) and 0x1FF
        if (flipped) x += 0x1000
        val y = ((faceState.yPos + faceHost.portraitByte(faceState.faceId, 0x15) * 8) and 0xFF) +
            if (bits and (1 shl 10) != 0) 0x400 else 0
        faceHost.faceSprite(OBJECT_32X16)?.let {
            faceHost.putFaceSprite(faceState.spriteLayer, x, y, it, faceState.oam2 + 28)
        }
    }

    private fun blinkOf(proc: Proc): FaceBlinkState = proc.state as FaceBlinkState

    private fun faceOfBlink(proc: Proc): Proc = blinkOf(proc).pFaceProc!!

    /**
     * Draws the eye object over the face for eye frame [unk] (0 or 1: 32x16 closed or half;
     * 0x80 or 0x81: the 16x16 variant); any other value draws nothing.
     *
     * Transcribed from src/face.c: sub_8006134. Normal blink OAM and tiles are checked against
     * the Prologue trace in both orientations; the smaller eye object is unverified.
     */
    public fun sub8006134(proc: Proc, unk: Int) {
        val base: Int
        var small = false
        when (unk) {
            0 -> base = 88
            1 -> base = 24
            0x80 -> { base = 88; small = true }
            0x81 -> { base = 24; small = true }
            else -> return
        }
        val face = faceOfBlink(proc)
        val faceState = face.state as FaceState
        val faceId = faceState.faceId
        var oam1 = 4 - faceHost.portraitByte(faceId, 0x16)
        val flipped = faceState.displayBits and 1 != 0
        oam1 = if (flipped) oam1 else -oam1
        oam1 = (oam1 * 8 + faceState.xPos - 16) and 0x1FF
        if (flipped) oam1 += 0x1000
        var oam0 = if (faceState.displayBits and (1 shl 10) != 0) 0x400 else 0
        oam0 += (faceState.yPos + faceHost.portraitByte(faceId, 0x17) * 8) and 0xFF
        if (small) {
            if (!flipped) oam1 += 16
            faceHost.faceSprite(OBJECT_16X16)?.let {
                faceHost.putFaceSprite(
                    faceState.spriteLayer, oam1, oam0, it, faceState.oam2 + base + 2,
                )
            }
        } else {
            faceHost.faceSprite(OBJECT_32X16)?.let {
                faceHost.putFaceSprite(faceState.spriteLayer, oam1, oam0, it, faceState.oam2 + base)
            }
        }
    }

    /**
     * Blink proc start: reads the portrait's blink kind and rolls the first interval.
     *
     * Ported from src/face.c: sub_800623C
     */
    public fun sub800623C(proc: Proc) {
        val blinkState = blinkOf(proc)
        val face = proc.parent!!
        blinkState.pFaceProc = face
        blinkState.blinkControl = faceHost.portraitByte((face.state as FaceState).faceId, 0x18)
        blinkState.unk38 = faceBlinkProcGenBlinkInterval(proc)
        blinkState.unk32 = 0
        if (blinkState.blinkControl == 6) {
            blinkState.blinkControl = 5
            blinkState.unk38 = Int.MAX_VALUE
            blinkState.unk32 = 2
            blinkState.unk34 = 6
            engine.goto(proc, 97)
        }
    }

    /**
     * Counts the blink interval down; jumps to the held-eye label in `unk_32` or starts a blink.
     *
     * Ported from src/face.c: sub_8006280
     */
    public fun sub8006280(proc: Proc) {
        val blinkState = blinkOf(proc)
        blinkState.unk38--
        val requestedEyeState = blinkState.unk32
        if (requestedEyeState != 0) {
            engine.goto(proc, requestedEyeState)
            return
        }
        if (blinkState.unk38 < 0) {
            blinkState.unk38 = faceBlinkProcGenBlinkInterval(proc)
            blinkState.unk34 = requestedEyeState
            engine.goto(proc, 1)
        }
    }

    /**
     * One frame of the blink animation (closed for frames 3..5, half for 0..2 and 6..8).
     *
     * Ported from src/face.c: sub_80062B8
     */
    public fun sub80062B8(proc: Proc) {
        val blinkState = blinkOf(proc)
        var eyeFrame = 2
        when (blinkState.unk34) {
            3, 4, 5 -> eyeFrame = 0
            0, 1, 2, 6, 7, 8 -> eyeFrame = 1
            10 -> engine.goto(proc, 0)
        }
        sub8006134(proc, eyeFrame)
        blinkState.unk34++
    }

    /**
     * Transcribed from src/face.c: sub_8006324 (unverified: held-eye path).
     */
    public fun sub8006324(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_800632C (unverified: held-eye path).
     */
    public fun sub800632C(proc: Proc) {
        val blinkState = blinkOf(proc)
        if (blinkState.unk34 < 6) {
            sub80062B8(proc)
            return
        }
        sub8006134(proc, 0)
        if (blinkState.unk32 == 0) engine.goto(proc, 1)
        if (blinkState.unk32 == 3) engine.goto(proc, 3)
    }

    /**
     * Transcribed from src/face.c: sub_8006370 (unverified: held-eye path).
     */
    public fun sub8006370(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_8006378 (unverified: held-eye path).
     */
    public fun sub8006378(proc: Proc) {
        val blinkState = blinkOf(proc)
        if (blinkState.unk34 < 3) {
            sub80062B8(proc)
            return
        }
        sub8006134(proc, 1)
        if (blinkState.unk32 == 0) engine.goto(proc, 1)
        if (blinkState.unk32 == 2) engine.goto(proc, 2)
    }

    /**
     * Transcribed from src/face.c: sub_80063BC (unverified: held-eye path).
     */
    public fun sub80063BC(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_80063C4 (unverified: held-eye path).
     */
    public fun sub80063C4(proc: Proc) {
        val blinkState = blinkOf(proc)
        var eyeFrame = 2
        when (blinkState.unk34) {
            3, 4, 5 -> eyeFrame = 0
            0, 1, 2, 6, 7, 8 -> eyeFrame = 1
            10 -> {
                engine.goto(proc, 0)
                blinkState.unk32 = 0
            }
        }
        sub8006134(proc, 0x80 + eyeFrame)
        blinkState.unk34++
    }

    /**
     * Sets the blink kind of [face] (0 restores the portrait's own) and rerolls the interval.
     *
     * Transcribed from src/face.c: SetFaceBlinkControl (unverified: no reference run sets it)
     */
    public fun setFaceBlinkControl(face: Proc, blinkControl: Int) {
        val faceState = face.state as FaceState
        val blinkProc = faceState.pBlinkProc ?: return
        blinkOf(blinkProc).blinkControl =
            if (blinkControl == 0) faceHost.portraitByte(faceState.faceId, 0x18) else blinkControl
        blinkOf(blinkProc).unk38 = faceBlinkProcGenBlinkInterval(blinkProc)
    }

    /**
     * Rolls the frames until the next blink from one LCG step (the original falls off the end
     * for other kinds; 0 is returned there).
     *
     * Ported from src/face.c: FaceBlinkProc_GenBlinkInterval
     */
    public fun faceBlinkProcGenBlinkInterval(proc: Proc): Int {
        val randomValue = faceHost.advanceLcg() ushr 16
        return when (blinkOf(proc).blinkControl) {
            3 -> (randomValue shr 7) + 300
            1 -> (randomValue shr 7) + 30
            2 -> (randomValue shr 9) + 30
            4 -> 1
            5 -> Int.MAX_VALUE
            else -> 0
        }
    }

    /**
     * Transcribed from src/face.c: StartFaceAuto
     */
    public fun startFaceAuto(fid: Int, x: Int, y: Int, disp: Int): Proc? {
        val slot = gFaces.indexOfFirst { it == null }
        return if (slot < 0) null else startFace(slot, fid, x, y, disp)
    }

    /**
     * Transcribed from src/face.c: SetFaceDisplayBits (not verified independently of refresh).
     */
    public fun setFaceDisplayBits(proc: Proc, disp: Int) {
        val faceState = proc.state as FaceState
        faceState.displayBits = disp
        faceRefreshSprite(proc)
    }

    /**
     * Selects the ROM composition and the configured tile, palette and hardware priority.
     *
     * Reference verified for the talk's 96x96 and flipped 96x96 compositions and priority.
     * Other layouts have ROM-loading checks only, without reference execution coverage.
     *
     * Ported from src/face.c: FaceRefreshSprite
     */
    public fun faceRefreshSprite(proc: Proc) {
        val faceState = proc.state as FaceState
        val disp = faceState.displayBits
        val address = when (disp and 0x807) {
            0 -> 0x0859100C
            1 -> 0x08591026
            2 -> 0x08591040
            3 -> 0x08591066
            4 -> 0x0859108C
            5 -> 0x085910BE
            0x800 -> 0x085910F0
            0x801 -> 0x08591122
            else -> 0
        }
        if (address != 0) faceState.sprite = faceHost.faceSprite(address)
        val layer = when (disp and (0xF shl 6)) {
            1 shl 6 -> 0
            2 shl 6 -> 1
            8 shl 6 -> 3
            else -> 2
        }
        faceState.oam2 = TILE_OFFSET[faceState.faceSlot] / 0x20 +
            (PAL_ID[faceState.faceSlot] and 0xF) * 0x1000 + (layer shl 10)
    }

    /**
     * Ported from src/scene.c: TalkLoadFace
     */
    public fun talkLoadFace(proc: Proc) {
        val talkState = talk.state
        var faceDisp = 0
        if (talkState.activeFaceSlot == 0xFF) talk.setActiveTalkFace(1)
        check(!faceHost.isBattleDeamonActive()) { "SetupFaceGfxDataInBanim not ported" }
        faceDisp = faceDisp or 2
        if (hpos(talkState.activeFaceSlot) <= 14) faceDisp = faceDisp or 1
        var faceId = byteAt(talkState.str) or (byteAt(talkState.str + 1) shl 8)
        faceId = if (faceId == 0xFFFF) faceHost.activeUnitPortraitId() else faceId - 0x100
        val existing = faceOf(talkState.faces[talkState.activeFaceSlot])
        if (existing != null) {
            faceHost.changeFacePortrait(existing, faceId)
            return
        }
        val face = startFaceAuto(faceId, hpos(talkState.activeFaceSlot) * 8, 80, faceDisp)
        talkState.faces[talkState.activeFaceSlot] =
            if (face == null) 0 else handleOf((face.state as FaceState).faceSlot)
        if (face != null) faceHost.startFaceFadeIn(face)
        setTalkFaceLayer(talkState.activeFaceSlot, talk.checkTalkFlag(TalkFlags.FLAG_4))
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
        val savedHandle = faces[from]
        faces[from] = faces[to]
        faces[to] = savedHandle
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
        val moveState = proc.state as TalkFaceMoveState
        moveState.slot = slot
        moveState.to = to
        moveState.fromX = (face.state as FaceState).xPos
        moveState.isSwap = isSwap
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
        val moveState = proc.state as TalkFaceMoveState
        moveState.timer = 0
        moveState.duration = if (Math.abs(moveState.fromX - hpos(moveState.to) * 8) > 24) 32 else 16
    }

    /**
     * Ported from src/scene.c: TalkFaceMove_OnIdle
     */
    public fun talkFaceMoveOnIdle(proc: Proc) {
        val moveState = proc.state as TalkFaceMoveState
        val faceState = gFaces[moveState.slot]!!.state as FaceState
        if (moveState.duration > 16) {
            if (moveState.timer == moveState.duration / 8) faceState.yPos++
            if (moveState.timer == moveState.duration / 2) faceState.yPos--
            if (moveState.timer == moveState.duration * 5 / 8) faceState.yPos++
        } else {
            if (moveState.timer == moveState.duration / 2) faceState.yPos++
        }
        if (moveState.timer >= moveState.duration) {
            faceState.yPos--
            engine.breakProc(proc)
        } else {
            faceState.xPos = Interpolate.interpolate(
                Interpolate.RSQUARE,
                moveState.fromX,
                hpos(moveState.to) * 8,
                moveState.timer++,
                moveState.duration,
            )
        }
    }

    /**
     * Ported from src/scene.c: SetTalkFaceDisp
     */
    public fun setTalkFaceDisp(talkFace: Int, faceDisp: Int) {
        if (talkFace == 0xFF) return
        val face = faceOf(talk.state.faces[talkFace]) ?: return
        val faceState = face.state as FaceState
        val disp = faceState.displayBits and (SMILE or TALK_1 or TALK_2).inv()
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

    /** Face handle constants. */
    public companion object {
        /** Value of a face handle for slot 0. */
        public const val FACE_HANDLE: Int = 0x02030000
        private const val FACE_SLOTS = 4
        private const val OBJECT_16X16 = 0x08590F4C
        private const val OBJECT_32X16 = 0x08590F8C
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
