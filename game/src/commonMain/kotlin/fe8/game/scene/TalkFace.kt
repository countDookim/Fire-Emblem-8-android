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

    /**
     * `PutSpriteExt(layer, xOam1, yOam0, sprite, oam2)`: queues [sprite] on sprite [layer]; the
     * raw OAM attribute bits in [xOam1] (OAM1) and [yOam0] (OAM0, e.g. the blend flag) are kept.
     */
    public fun putFaceSprite(
        layer: Int,
        xOam1: Int,
        yOam0: Int,
        sprite: SpriteObject,
        oam2: Int,
    ) {}

    /** `IsBattleDeamonActive`: the battle animation system is running (fight-event faces). */
    public fun isBattleDeamonActive(): Boolean

    /** `GetUnitPortraitId(gActiveUnit)`. */
    public fun activeUnitPortraitId(): Int

    /** `GetPortraitData(fid)->img`: GBA address of the LZ77 face tiles. */
    public fun portraitImage(fid: Int): Int

    /** `GetPortraitData(fid)->pal`: GBA address of the 16 face colours. */
    public fun portraitPalette(fid: Int): Int

    /**
     * Byte [offset] of `GetPortraitData(fid)` (a `struct FaceData`): mouth position `xMouth`,
     * `yMouth` at 0x14..0x15 and eye position `xEyes`, `yEyes` at 0x16..0x17 (in 8-pixel units
     * from the face's origin), and `blinkKind` at 0x18.
     */
    public fun portraitByte(fid: Int, offset: Int): Int

    /**
     * Registers a 4x2 mouth frame for transfer from portrait [fid] at [srcTile] to OBJ VRAM
     * tile [dstTile]. The production host applies the transfer at VBlank.
     */
    public fun copyMouthTiles(fid: Int, srcTile: Int, dstTile: Int)

    /** `AdvanceGetLCGRNValue()`: the next value of the game's random number generator. */
    public fun advanceLcg(): Int

    /** `StartFaceFadeIn(face)`: blacks out the face palette and fades the portrait palette in. */
    public fun startFaceFadeIn(face: Proc)

    /** `sub_80066E0(face, faceId)`: starts the proc that swaps the portrait of a loaded face. */
    public fun changeFacePortrait(face: Proc, faceId: Int)
}

/** State of a `gProcScr_E_FACE` proc (`struct FaceProc`). */
public class FaceState {
    /** Sprite composition (`sprite`, 0x38), resolved from ROM composition data. */
    public var sprite: SpriteObject? = null

    /**
     * `FACE_DISP_*` flags (`displayBits`, 0x30): bits 0..2 sprite kind and flip (bit 0 =
     * `FACE_DISP_FLIPPED`), 3 `SMILE`, 4 and 5 `TALK_1` and `TALK_2` (mouth moving), 6..9 the
     * `HLAYER` priority, 10 `BLEND`, 12 `BIT_12` (no mouth and blink procs), 13 `BIT_13` (no
     * palette loaded), 14 `HIDDEN`; bit 11 selects the 96x72 kinds (it has no name).
     */
    public var displayBits: Int = 0

    /** Horizontal screen position in pixels (`xPos`, 0x34). */
    public var xPos: Int = 0

    /** Vertical screen position in pixels (`yPos`, 0x36). */
    public var yPos: Int = 0

    /**
     * OAM2 attribute of the face's first tile (`oam2`, 0x3C): tile number, OBJ palette bank in
     * bits 12..15 and priority in bits 10..11, built by `FaceRefreshSprite`.
     */
    public var oam2: Int = 0

    /** Portrait id, an index into `portrait_data` plus 1 (`faceId`, 0x3E). */
    public var faceId: Int = 0

    /** Face slot (0..3, `gFaces` index) the face occupies (`faceSlot`, 0x40). */
    public var faceSlot: Int = 0

    /**
     * Sprite layer the face is put on (`spriteLayer`, 0x41): 5 by default, 6 behind (see
     * `SetTalkFaceLayer`).
     */
    public var spriteLayer: Int = 0

    /** The mouth child proc (`unk_44`); null when `FACE_DISP_BIT_12` is set. */
    public var mouthProc: Proc? = null

    /** The blink child proc (`pBlinkProc`, 0x48); null when `FACE_DISP_BIT_12` is set. */
    public var pBlinkProc: Proc? = null
}

/**
 * State of a `gProcScr_0859124C` mouth child. The decomp types it as a `struct FaceBlinkProc`
 * (the mouth proc reuses that layout).
 */
public class FaceMouthState {
    /** Parent face (`pFaceProc`, offset 0x2C). */
    public var face: Proc? = null

    /**
     * Phase 0..3 of the talking mouth cycle (the `blinkControl` field, offset 0x30, reused by
     * the mouth proc); new Kotlin states start at zero.
     */
    public var phase: Int = 0

    /** Frames until the mouth changes frame while talking (`unk_32`, offset 0x32). */
    public var timer: Int = 0
}

/** State of a `gProcScr_FaceBlink` proc (`struct FaceBlinkProc`). */
public class FaceBlinkState {
    /** `pFaceProc` (0x2C): the parent face proc. */
    public var pFaceProc: Proc? = null

    /**
     * `blinkControl` (0x30, s16): the blink kind, 1..5, taken from the portrait's `blinkKind` or
     * set by `SetFaceBlinkControl`; it selects the interval in
     * [TalkFace.faceBlinkProcGenBlinkInterval].
     */
    public var blinkControl: Int = 0

    /**
     * `unk_32` (s16): the eye state asked for through `sub_80064D4`: 0 blink at random, 2 hold
     * the eyes closed, 3 hold them half closed, 4 wink once (then back to 0).
     */
    public var unk32: Int = 0

    /** `unk_34` (s16): frames into the current blink (a blink is 10 frames). */
    public var unk34: Int = 0

    /** `unk_38`: frames until the next blink; counts down, `Int.MAX_VALUE` for never. */
    public var unk38: Int = 0
}

/** State of a `gProcScr_TalkFaceMove` proc: one face sliding to another slot's column. */
public class TalkFaceMoveState {
    /** Elapsed movement frame counter (`unk58`). */
    public var timer: Int = 0

    /** Total duration of movement in frames (`unk5C`). */
    public var duration: Int = 0

    /** `gFaces` index of the face that moves (`unk64`). */
    public var slot: Int = 0

    /** Talk face slot (0..7) whose column the face moves to (`unk66`). */
    public var to: Int = 0

    /** X position in pixels the move starts from (`unk68`). */
    public var fromX: Int = 0

    /** True when another face moves the opposite way at the same time (`unk6A`); unused here. */
    public var isSwap: Boolean = false
}

/**
 * The talk face side of `src/scene.c` (load, layer, move, mouth) over the part of `src/face.c`
 * the talk needs: `gFaces`, `StartFace` (palette, tiles, fields), the sprite composition and the
 * mouth and blink procs. A face handle in [TalkState.faces] is [FACE_HANDLE] plus the face slot.
 * The fight-event layout (`FaceConfig_FightEvent`, `SetupFaceGfxDataInBanim`) is not ported.
 */
public class TalkFace(
    private val engine: ProcEngine,
    private val talk: Talk,
    private val host: TalkHost,
    private val faceHost: TalkFaceHost,
) {
    /** `gFaces`: the face proc in each of the 4 face slots, null when free. */
    public val gFaces: Array<Proc?> = arrayOfNulls(FACE_SLOTS)

    /** `sFaceConfig[].tileOffset`: byte offset of each slot's tiles in OBJ VRAM. */
    private val tileOffsets = DEFAULT_TILE_OFFSETS.copyOf()

    /** `sFaceConfig[].paletteId`: OBJ palette bank of each slot. */
    private val paletteIds = DEFAULT_PALETTE_IDS.copyOf()

    /**
     * Replaces the per-slot VRAM tile offsets and OBJ palette banks, as `SetupFaceGfxData(config)`
     * does (the defaults are `gDefaultFaceConfig`).
     *
     * Transcribed from src/face.c: SetupFaceGfxData (not verified: only checked together with the
     * faces that use it; the world map's `gUnknown_08A3D728` is checked through the `oam2` of its
     * faces)
     */
    public fun setupFaceGfxData(tileOffset: IntArray, paletteId: IntArray) {
        for (i in 0 until FACE_SLOTS) {
            tileOffsets[i] = tileOffset[i]
            paletteIds[i] = paletteId[i]
        }
    }

    /** `sFaceConfig[slot].paletteId`. */
    public fun paletteIdOf(slot: Int): Int = paletteIds[slot]

    /** `sFaceConfig[slot].tileOffset`. */
    public fun tileOffsetOf(slot: Int): Int = tileOffsets[slot]

    /**
     * Ends every face slot that holds a face and restores `gDefaultFaceConfig`, undoing the
     * world map's own portrait layout.
     *
     * Ported from src/face.c: ResetFaces
     *
     * `EndFaceById` on an empty slot would read the NULL face proc's `faceSlot` in the original;
     * the slot is skipped here (the callers end every face first, so no slot is full).
     */
    public fun resetFaces() {
        for (slot in 0 until FACE_SLOTS) {
            val proc = gFaces[slot] ?: continue
            gFaces[slot] = null
            engine.end(proc)
        }
        setupFaceGfxData(DEFAULT_TILE_OFFSETS, DEFAULT_PALETTE_IDS)
    }

    private val faceInitFn = ProcFunc { faceOnInit(it) }
    private val faceIdleFn = ProcFunc { faceOnIdle(it) }
    private val moveInitFn = ProcFunc { talkFaceMoveOnInit(it) }
    private val moveIdleFn = ProcFunc { talkFaceMoveOnIdle(it) }

    /**
     * `gProcScr_E_FACE`: the face proc. Loads the portrait tiles ([faceOnInit]), then submits the
     * face sprite every frame ([faceOnIdle]). Without the camera-move wait of the original.
     */
    public val faceScript: ProcScript = ProcScript(
        "gProcScr_E_FACE",
        listOf(sleep(0), call(faceInitFn), call(faceIdleFn), repeat(faceIdleFn), end()),
    ) { FaceState() }

    private val mouthInitFn = ProcFunc { sub8005FD4(it) }
    private val mouthIdleFn = ProcFunc { sub8005FE0(it) }

    /** `gProcScr_0859124C`: the mouth child of a face: animates and draws the mouth. */
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

    /**
     * `gProcScr_FaceBlink`: the blink child. Label 0 waits for the next blink (kind from the
     * portrait), 1 plays a blink, 2 and 3 hold the eyes closed or half closed, 4 plays a wink and
     * 97 holds the eyes closed for good (portrait blink kind 6). The duplicate label 0 is as in
     * the original.
     */
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

    /** `gProcScr_TalkFaceMove`: slides a face to another slot's column, hopping on the way. */
    public val moveScript: ProcScript = ProcScript(
        "gProcScr_TalkFaceMove",
        listOf(sleep(1), call(moveInitFn), repeat(moveIdleFn), end()),
    ) { TalkFaceMoveState() }

    /** The face proc of a [TalkState.faces] handle ([FACE_HANDLE] + slot), or null for 0. */
    public fun faceOf(handle: Int): Proc? =
        if (handle == 0) null else gFaces[handle - FACE_HANDLE]

    /** The [TalkState.faces] handle of face slot [slot]. */
    private fun handleOf(slot: Int): Int = FACE_HANDLE + slot

    /** `GetTalkFaceHPos(talkFace)` without the fight-event case: the face's tile column. */
    private fun hpos(talkFace: Int): Int = FACE_HPOS[talkFace]

    /**
     * Decompresses the portrait's tiles into the face slot's place in OBJ VRAM.
     *
     * Transcribed from src/face.c: Face_OnInit (not verified: no @VerifiesPort test names it)
     */
    public fun faceOnInit(proc: Proc) {
        val faceState = proc.state as FaceState
        host.decompressToVram(
            faceHost.portraitImage(faceState.faceId),
            tileOffsets[faceState.faceSlot] + OBJ_VRAM,
        )
    }

    /**
     * Submits the face composition every frame unless it is hidden (`FACE_DISP_HIDDEN`), with
     * x wrapped to 9 bits, y to 8 bits and the blend flag (`OAM0_BLEND`, 0x400).
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
     * Starts the face proc in [slot] for portrait [fid] at pixel ([x], [y]) with `FACE_DISP_*`
     * flags [disp], loading its palette unless `FACE_DISP_BIT_13` is set; returns null when the
     * slot is taken. Unless `FACE_DISP_BIT_12` is set it starts the mouth and blink children, in
     * that order.
     *
     * Transcribed from src/face.c: StartFace (not verified: no @VerifiesPort test names it).
     */
    public fun startFace(slot: Int, fid: Int, x: Int, y: Int, disp: Int): Proc? {
        if (gFaces[slot] != null) return null
        val proc = engine.start(faceScript, 5)
        gFaces[slot] = proc
        val faceState = proc.state as FaceState
        if (disp and (1 shl 13) == 0) {
            host.applyPalette(faceHost.portraitPalette(fid), paletteIds[slot] + 0x10)
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
     * Initializes the mouth child: remembers its parent face and zeroes the change timer.
     *
     * Ported from src/face.c: sub_8005FD4
     */
    public fun sub8005FD4(proc: Proc) {
        val mouth = proc.state as FaceMouthState
        mouth.face = proc.parent!!
        mouth.timer = 0
    }

    /**
     * Updates mouth tiles and submits the 32x16 mouth sprite. The mouth frames are 4x2-tile
     * pictures in the portrait's mouth sheet, at tile offset +0 for the smiling set
     * (`FACE_DISP_SMILE`) or +24 otherwise. Frame +16 is shown while the face is not talking; the
     * talking cycle goes through +8, +16, +8, +0, each held for a random 1..8 frames.
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
     * Draws the eye object over the face for eye frame [unk]: 0 closed or 1 half closed (a 32x16
     * object whose tiles are at 88 and 24 tiles on from the face's `oam2`), or 0x80 / 0x81 for
     * the same with the 16x16 object (one eye, used by the wink); any other value (the open
     * eyes) draws nothing.
     *
     * Transcribed from src/face.c: sub_8006134 (not verified: the smaller eye object). Normal blink
     * OAM and tiles are checked against the Prologue trace in both orientations.
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
     * Blink proc start: reads the portrait's blink kind and rolls the first interval. Kind 6
     * means eyes always closed: it becomes kind 5 (never blink) held closed (label 97).
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
     * Counts the blink interval down. A requested eye state in `unk_32` (2, 3 or 4) jumps to the
     * script label of that number; otherwise a blink (label 1) starts when the interval is over.
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
     * One frame of the 10-frame blink (eyes closed for frames 3..5, half closed for 0..2 and 6..8,
     * open for 9), then back to the waiting label 0.
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
     * Transcribed from src/face.c: sub_8006324 (not verified: held-eye path).
     */
    public fun sub8006324(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_800632C (not verified: held-eye path).
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
     * Transcribed from src/face.c: sub_8006370 (not verified: held-eye path).
     */
    public fun sub8006370(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_8006378 (not verified: held-eye path).
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
     * Transcribed from src/face.c: sub_80063BC (not verified: held-eye path).
     */
    public fun sub80063BC(proc: Proc) {
        blinkOf(proc).unk34 = 0
    }

    /**
     * Transcribed from src/face.c: sub_80063C4 (not verified: held-eye path).
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
     * Used by the codes `0x80 0x16..0x1B` of the dialogue.
     *
     * Transcribed from src/face.c: SetFaceBlinkControl (not verified: no reference run sets it)
     */
    public fun setFaceBlinkControl(face: Proc, blinkControl: Int) {
        val faceState = face.state as FaceState
        val blinkProc = faceState.pBlinkProc ?: return
        blinkOf(blinkProc).blinkControl =
            if (blinkControl == 0) faceHost.portraitByte(faceState.faceId, 0x18) else blinkControl
        blinkOf(blinkProc).unk38 = faceBlinkProcGenBlinkInterval(blinkProc)
    }

    /**
     * Rolls the frames until the next blink from one LCG step (a 16-bit random value `r`): kind 1
     * `(r >> 7) + 30`, kind 2 `(r >> 9) + 30`, kind 3 `(r >> 7) + 300`, kind 4 always 1 and
     * kind 5 never (`INT32_MAX`). The original falls off the end of the function for other
     * kinds; 0 is returned there.
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
     * `StartFace` in the first free slot (`FindFreeFaceSlot`); null when all 4 slots are taken.
     *
     * Transcribed from src/face.c: StartFaceAuto (not verified: no @VerifiesPort test names it)
     */
    public fun startFaceAuto(fid: Int, x: Int, y: Int, disp: Int): Proc? {
        val slot = gFaces.indexOfFirst { it == null }
        return if (slot < 0) null else startFace(slot, fid, x, y, disp)
    }

    /**
     * Stores the `FACE_DISP_*` flags and refreshes the sprite composition and `oam2`.
     *
     * Transcribed from src/face.c: SetFaceDisplayBits (not verified: independently of refresh).
     */
    public fun setFaceDisplayBits(proc: Proc, disp: Int) {
        val faceState = proc.state as FaceState
        faceState.displayBits = disp
        faceRefreshSprite(proc)
    }

    /**
     * Selects the ROM composition from the kind and flip bits (`displayBits and 0x807`) and
     * builds `oam2` from the slot's configured tile and palette and the `HLAYER` bits (the
     * hardware priority, `OAM2_LAYER`).
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
        faceState.oam2 = tileOffsets[faceState.faceSlot] / 0x20 +
            (paletteIds[faceState.faceSlot] and 0xF) * 0x1000 + (layer shl 10)
    }

    /**
     * Handles `[LoadFace]`: reads the 2-byte face id at the string cursor (0xFFFF is the active
     * unit's portrait, otherwise the id minus 0x100), then either swaps the portrait of the face
     * already in the active slot or starts a new face there at the slot's column, y 80, faded in,
     * and locks the talk for 8 frames. The face is the 96x80 kind (`FACE_DISP_KIND(FACE_96x80)`)
     * and is flipped (`FACE_DISP_FLIPPED`) when its column is 14 or less. The fight-event layout
     * is not ported and fails.
     *
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
     * Gives the face in [talkFace] sprite layer 5 and the other faces of its group (slots 0..2 or
     * 3..5) layer 6; does nothing when [toBack] is non-zero.
     *
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
     * Moves the face of slot [from] to slot [to] (`[Move*]` codes): starts the slide of that face
     * and, when [to] is occupied, of its face the other way (a swap), then swaps the two
     * handles.
     *
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
     * True while any face is sliding; the dialogue waits for it.
     *
     * Ported from src/scene.c: IsTalkFaceMoving
     */
    public fun isTalkFaceMoving(): Boolean = engine.find(moveScript) != null

    /**
     * Starts the slide of the face standing at the column of talk slot [from] to the column of
     * slot [to]; nothing if no face stands there.
     *
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
     * The `gFaces` index of the face whose x position is [x], or -1.
     *
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
     * Picks the slide length: 32 frames when the face has more than 24 pixels to go, else 16.
     *
     * Ported from src/scene.c: TalkFaceMove_OnInit
     */
    public fun talkFaceMoveOnInit(proc: Proc) {
        val moveState = proc.state as TalkFaceMoveState
        moveState.timer = 0
        moveState.duration = if (Math.abs(moveState.fromX - hpos(moveState.to) * 8) > 24) 32 else 16
    }

    /**
     * One frame of the slide: x follows an `RSQUARE` interpolation to the target column while the
     * face dips one pixel at fixed fractions of the duration (once for the 16-frame slide, twice
     * for the 32-frame one; the last frame undoes the last dip). Breaks when the duration is
     * over.
     *
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
     * Sets the mouth bits of the face in [talkFace] to [faceDisp] (0 or `FACE_DISP_TALK_1`) and
     * its smile bit from [TalkState.faceSmileEnabled]; nothing for slot 0xFF or an empty slot.
     *
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
     * Transcribed from src/scene.c: SetTalkFaceMouthMove (not verified: no @VerifiesPort test names
     * it)
     */
    public fun setTalkFaceMouthMove(talkFace: Int): Unit = setTalkFaceDisp(talkFace, TALK_1)

    /**
     * Transcribed from src/scene.c: SetTalkFaceNoMouthMove (not verified: no @VerifiesPort test
     * names it)
     */
    public fun setTalkFaceNoMouthMove(talkFace: Int): Unit = setTalkFaceDisp(talkFace, 0)

    private fun byteAt(addr: Int): Int =
        host.stringArray(addr)[host.stringOffset(addr)].toInt() and 0xFF

    /** Face handle constants. */
    public companion object {
        /**
         * The handle of face slot 0 in [TalkState.faces]. Not a decomp address: it only makes a
         * face a non-zero value (the decomp stores a `FaceProc` pointer, NULL for none).
         */
        public const val FACE_HANDLE: Int = 0x02030000

        /** `FACE_SLOT_COUNT`: the number of face slots (`gFaces`). */
        private const val FACE_SLOTS = 4

        /** `gDefaultFaceConfig[].tileOffset`: byte offset of each slot's tiles in OBJ VRAM. */
        private val DEFAULT_TILE_OFFSETS =
            intArrayOf(0x20 * 0x300, 0x20 * 0x280, 0x20 * 0x200, 0x20 * 0x380)

        /** `gDefaultFaceConfig[].paletteId`: the OBJ palette bank of each slot. */
        private val DEFAULT_PALETTE_IDS = intArrayOf(6, 7, 8, 9)

        /** `gObject_16x16` (ROM), the one-eye object of the wink. */
        private const val OBJECT_16X16 = 0x08590F4C

        /** `gObject_32x16` (ROM), the mouth and eye object. */
        private const val OBJECT_32X16 = 0x08590F8C

        /** Start of the OBJ tile data in VRAM. */
        private const val OBJ_VRAM = 0x06010000

        /** `FACE_DISP_SMILE`. */
        private const val SMILE = 1 shl 3

        /** `FACE_DISP_TALK_1`: the mouth moves. */
        private const val TALK_1 = 1 shl 4

        /** `FACE_DISP_TALK_2`. */
        private const val TALK_2 = 1 shl 5

        /** `gTalkFaceHPosLut`: the tile column of each of the 8 talk face slots. */
        private val FACE_HPOS = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)
    }
}
