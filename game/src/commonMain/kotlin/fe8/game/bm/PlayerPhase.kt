package fe8.game.bm

import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield

/**
 * The player phase of src/playerphase.c: `gProcScr_PlayerPhase` (0x0859AAD8, transcribed 1:1,
 * 66 commands, checked against the ROM by `PlayerPhaseScriptRomTest`) and the functions the
 * idle part of it runs: [playerPhaseHandleAutoEnd], [handlePlayerCursorMovement] and the
 * cursor part of [playerPhaseMainIdle], with the cursor handling of src/bm.c
 * ([handleMapCursorInput], [handleMoveMapCursor], [handleMoveCameraWithMapCursor]).
 *
 * Stand-ins ([Hooks], no-ops by default): the L/R/A/B/START/SELECT actions of the idle loop,
 * the side windows, `PlayerPhase_Suspend`, the tutorial event, `RefreshUnitSprites`, the cursor
 * sprite (`PutMapCursor`), `StartMapSongBgm`, the cursor sound effect and every call of the
 * script after the idle loop (unit selection, movement, action). A `PROC_REPEAT` with a
 * stand-in never leaves it. Nothing allocates per frame.
 */
public class PlayerPhase(
    /** The proc engine the player phase proc runs on. */
    private val engine: ProcEngine,
    /** `gBmSt`. */
    private val bm: BmSt,
    /** `gPlaySt`. */
    private val playSt: PlaySt,
    /** `gBmMapSize`, `gBmMapMovement` and the other map pools. */
    private val map: BmMap,
    /** The unit arrays (`GetPhaseAbleUnitCount`). */
    private val units: UnitTable,
    /** `ProcScr_CamMove`, waited for by the script. */
    private val camMove: CamMove,
    /** `RefreshEntityBmMaps`. */
    private val entityBmMaps: EntityBmMaps,
    /** `RenderBmMap`. */
    private val render: BmMapRender,
    /** `gKeyStatusPtr`. */
    private val keys: KeyStatus,
    /** `EventEngineExists` (`EventEngine.eventEngineExists`). */
    private val eventEngineExists: () -> Boolean,
    /** The stand-ins for the parts that are not ported. */
    public val hooks: Hooks = Hooks(),
) {
    /** `PlaySoundEffect` for the cursor sound (a stand-in until the sound wrapper is wired). */
    public fun interface SoundEffect {
        /** Plays song [song]. */
        public fun play(song: Int)
    }

    /** `PutMapCursor` (the cursor sprite). */
    public fun interface PutMapCursor {
        /** Puts the cursor sprite at pixel ([x], [y]) with sprite [kind] (0 normal, 3 hover). */
        public fun put(x: Int, y: Int, kind: Int)
    }

    /**
     * The stand-ins. Every one defaults to doing nothing (the `PROC_CALL_2` ones to true, go on;
     * `doesBmxFadeExist` to false); a stand-in for a `PROC_REPEAT` must end the wait itself.
     */
    public class Hooks {
        /** Stand-in for `PlayerPhase_Suspend` (writes the suspend save). */
        public var suspend: ProcFunc = ProcFunc {}

        /** Stand-in for `RefreshUnitSprites`. */
        public var refreshUnitSprites: ProcFunc = ProcFunc {}

        /** Stand-in for `StartPlayerPhaseStartTutorialEvent`. */
        public var startTutorialEvent: ProcFunc = ProcFunc {}

        /** Stand-in for `StartMapSongBgm`. */
        public var startMapSongBgm: ProcFunc = ProcFunc {}

        /** Stand-in for `StartPlayerPhaseSideWindows`. */
        public var startSideWindows: ProcFunc = ProcFunc {}

        /** Stand-in for `EndPlayerPhaseSideWindows`. */
        public var endSideWindows: ProcFunc = ProcFunc {}

        /** Stand-in for `ResetUnitSpriteHover`. */
        public var resetUnitSpriteHover: ProcFunc = ProcFunc {}

        /**
         * Stand-in for the part of `PlayerPhase_MainIdle` after the cursor movement: the L, R, A,
         * B, START and SELECT actions (it may `Proc_Goto` or `Proc_Break` the proc), run before
         * [putMapCursor].
         */
        public var mainIdleButtons: ProcFunc = ProcFunc {}

        /** Stand-in for `UnitSpriteHoverUpdate`, run at the end of the idle loop. */
        public var unitSpriteHoverUpdate: ProcFunc = ProcFunc {}

        /**
         * Stand-in for `PutMapCursor` (the cursor sprite); `IsUnitSpriteHoverEnabledAt` is false.
         */
        public var putMapCursor: PutMapCursor = PutMapCursor { _, _, _ -> }

        /** Stand-in for `PlaySoundEffect(SONG_65)`, the cursor move sound. */
        public var cursorSound: SoundEffect = SoundEffect {}

        /** Stand-in for `DoesBMXFADEExist` (false). */
        public var doesBmxFadeExist: ProcBoolFunc = ProcBoolFunc { false }

        /** Stand-in for `SetAllUnitNotBackSprite`. */
        public var setAllUnitNotBackSprite: ProcFunc = ProcFunc {}

        /** Stand-in for `gProcScr_0859ACE8` (the select events), a script that ends at once. */
        public var selectEventsScript: ProcScript = ProcScript("gProcScr_0859ACE8", listOf(end()))

        /** Stand-in for `PlayerPhase_InitUnitMovementSelect`. */
        public var initUnitMovementSelect: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_RangeDisplayIdle` (a `PROC_REPEAT`). */
        public var rangeDisplayIdle: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_DisplayUnitMovement`. */
        public var displayUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_WaitForUnitMovement` (a `PROC_REPEAT`). */
        public var waitForUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ApplyUnitMovement` (a `PROC_REPEAT`). */
        public var applyUnitMovement: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_PrepareAction` (a `PROC_CALL_2`). */
        public var prepareAction: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `ApplyUnitAction` (a `PROC_CALL_2`). */
        public var applyUnitAction: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `HandlePostActionTraps` (a `PROC_CALL_2`). */
        public var handlePostActionTraps: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `RunPotentialWaitEvents` (a `PROC_CALL_2`). */
        public var runPotentialWaitEvents: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `EnsureCameraOntoActiveUnitPosition` (a `PROC_CALL_2`). */
        public var ensureCameraOntoActiveUnitPosition: ProcBoolFunc = ProcBoolFunc { true }

        /** Stand-in for `PlayerPhase_FinishAction`. */
        public var finishAction: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ResumeRangeDisplay`. */
        public var resumeRangeDisplay: ProcFunc = ProcFunc {}

        /** Stand-in for `EndAllMus`. */
        public var endAllMus: ProcFunc = ProcFunc {}

        /** Stand-in for `DisplayActiveUnitEffectRange`. */
        public var displayActiveUnitEffectRange: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_DisplayDangerZone`. */
        public var displayDangerZone: ProcFunc = ProcFunc {}

        /** Stand-in for `PlayerPhase_ReReadGameSaveGfx`. */
        public var reReadGameSaveGfx: ProcFunc = ProcFunc {}

        /** Stand-in for `gProcScr_ADJUSTSFROMXI`, a script that ends at once. */
        public var adjustsFromXiScript: ProcScript =
            ProcScript("gProcScr_ADJUSTSFROMXI", listOf(end()))
    }

    /**
     * `gProcScr_PlayerPhase` (0x0859AAD8). Labels 0 (phase start), 9 (idle), 1 (unit selected),
     * 2, 7 (action), 4, 5, 10, 6, 8, 11, 12 and 3 (end of the phase) as in the C.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_PlayerPhase",
        listOf(
            name("E_PLAYERPHASE"),
            mark(PROC_MARK_MAIN),
            yield(),
            label(0),
            call(ProcFunc { hooks.suspend.invoke(it) }),
            call(ProcFunc { entityBmMaps.refreshEntityBmMaps() }),
            call(ProcFunc { render.renderBmMap() }),
            call(ProcFunc { hooks.refreshUnitSprites.invoke(it) }),
            call(ProcFunc { hooks.startTutorialEvent.invoke(it) }),
            whileTrue(ProcBoolFunc { eventEngineExists() }),
            call(ProcFunc { playerPhaseHandleAutoEnd(it) }),
            call(ProcFunc { hooks.startMapSongBgm.invoke(it) }),
            label(9),
            call(ProcFunc { hooks.startSideWindows.invoke(it) }),
            call(ProcFunc { hooks.resetUnitSpriteHover.invoke(it) }),
            repeat(ProcFunc { playerPhaseMainIdle(it) }),
            label(1),
            call(ProcFunc { hooks.endSideWindows.invoke(it) }),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            call(ProcFunc { hooks.setAllUnitNotBackSprite.invoke(it) }),
            call(ProcFunc { hooks.refreshUnitSprites.invoke(it) }),
            startChildBlocking { hooks.selectEventsScript },
            call(ProcFunc { hooks.initUnitMovementSelect.invoke(it) }),
            sleep(1),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            call(ProcFunc { hooks.displayUnitMovement.invoke(it) }),
            repeat(ProcFunc { hooks.waitForUnitMovement.invoke(it) }),
            label(2),
            repeat(ProcFunc { hooks.applyUnitMovement.invoke(it) }),
            label(7),
            whileExists { camMove.script },
            call2(ProcBoolFunc { hooks.prepareAction.invoke(it) }),
            call2(ProcBoolFunc { hooks.applyUnitAction.invoke(it) }),
            call2(ProcBoolFunc { hooks.handlePostActionTraps.invoke(it) }),
            call2(ProcBoolFunc { hooks.runPotentialWaitEvents.invoke(it) }),
            call2(ProcBoolFunc { hooks.ensureCameraOntoActiveUnitPosition.invoke(it) }),
            call(ProcFunc { hooks.finishAction.invoke(it) }),
            goto(0),
            label(4),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            goto(1),
            label(5),
            call(ProcFunc { hooks.reReadGameSaveGfx.invoke(it) }),
            label(10),
            startChildBlocking { hooks.adjustsFromXiScript },
            goto(9),
            label(6),
            call(ProcFunc { hooks.resumeRangeDisplay.invoke(it) }),
            goto(1),
            label(8),
            yield(),
            call(ProcFunc { hooks.endAllMus.invoke(it) }),
            goto(0),
            label(11),
            call(ProcFunc { hooks.endSideWindows.invoke(it) }),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            call(ProcFunc { hooks.displayActiveUnitEffectRange.invoke(it) }),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            goto(9),
            label(12),
            call(ProcFunc { hooks.displayDangerZone.invoke(it) }),
            repeat(ProcFunc { hooks.rangeDisplayIdle.invoke(it) }),
            goto(9),
            label(3),
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            end(),
        ),
    )

    private fun s16(v: Int): Int = v.toShort().toInt()

    private fun s8(v: Int): Int = v.toByte().toInt()

    /**
     * Transcribed from src/playerphase.c: PlayerPhase_HandleAutoEnd (not verified: no test checks
     *   it against a trace yet)
     *
     * Jumps [proc] to label 3 (end of the phase) if auto end turns is on and no unit of the active
     * faction can act.
     */
    public fun playerPhaseHandleAutoEnd(proc: Proc) {
        if (playSt.disableAutoEndTurns == 0 && units.getPhaseAbleUnitCount(playSt.faction) == 0) {
            engine.goto(proc, 3)
        }
    }

    /**
     * Ported from src/playerphase.c: HandlePlayerCursorMovement
     *
     * Moves the cursor from the key state: with B held and the display cursor on an 8 pixel
     * boundary, 8 pixels per frame on a new press (`newKeys2`); otherwise 4 pixels per frame on
     * auto-repeat. While the display cursor is between tiles, the A, B, START, R and L presses
     * are cleared from `newKeys`.
     */
    public fun handlePlayerCursorMovement() {
        if ((keys.heldKeys and KEY_B) != 0 && (bm.playerCursorDisplayX and 7) == 0 &&
            (bm.playerCursorDisplayY and 7) == 0
        ) {
            handleMapCursorInput(keys.newKeys2)
            handleMoveMapCursor(8)
            handleMoveCameraWithMapCursor(8)
        } else {
            handleMapCursorInput(keys.repeatedKeys)
            handleMoveMapCursor(4)
            handleMoveCameraWithMapCursor(4)
        }
        if (((bm.playerCursorDisplayX or bm.playerCursorDisplayY) and 0xF) != 0) {
            keys.newKeys = keys.newKeys and
                (KEY_A or KEY_B or KeyStatus.START_BUTTON or KEY_R or KEY_L).inv()
        }
    }

    /**
     * Partially ported from src/playerphase.c: PlayerPhase_MainIdle. Ported: the cursor movement.
     * Stand-ins: the button actions ([Hooks.mainIdleButtons]), the unit sprite hover and the
     * cursor sprite ([Hooks.putMapCursor]).
     */
    public fun playerPhaseMainIdle(proc: Proc) {
        handlePlayerCursorMovement()
        hooks.mainIdleButtons.invoke(proc)
        hooks.unitSpriteHoverUpdate.invoke(proc)
        hooks.putMapCursor.put(bm.playerCursorDisplayX, bm.playerCursorDisplayY, 0)
    }

    /**
     * Ported from src/bm.c: HandleMapCursorInput
     *
     * Moves the cursor one tile by the D-pad bits of [dirKeys] (opposite directions cancel):
     * updates `playerCursor`, `cursorPrevious` and `cursorTarget` per axis where the new position
     * is on the map. With the movement range shown (`gameStateBits` bit 1) a step off the
     * reachable area only counts when the D-pad is a new press. The cursor sound plays on a move,
     * every other call (bit 2 toggles).
     */
    public fun handleMapCursorInput(dirKeys: Int) {
        val dir = (dirKeys shr 4) and 0xF
        val dx = DIR_X[dir]
        val dy = DIR_Y[dir]
        val newX = bm.playerCursorX + dx
        val newY = bm.playerCursorY + dy

        if ((bm.gameStateBits and (1 shl 1)) != 0) {
            if (map.movement[bm.playerCursorY, bm.playerCursorX] < MAP_MOVEMENT_MAX) {
                if (map.movement[newY, newX] >= MAP_MOVEMENT_MAX) {
                    if ((dirKeys and DPAD_ANY) != (keys.newKeys and DPAD_ANY)) return
                }
            }
        }
        if (newX >= 0 && newX < map.sizeX) {
            bm.cursorTargetX = s16(bm.cursorTargetX + dx * 16)
            bm.cursorPreviousX = bm.playerCursorX
            bm.playerCursorX = newX
        }
        if (newY >= 0 && newY < map.sizeY) {
            bm.cursorTargetY = s16(bm.cursorTargetY + dy * 16)
            bm.cursorPreviousY = bm.playerCursorY
            bm.playerCursorY = newY
        }
        if ((bm.gameStateBits and (1 shl 2)) == 0) {
            if (bm.playerCursorX == bm.cursorPreviousX && bm.playerCursorY == bm.cursorPreviousY) {
                return
            }
            hooks.cursorSound.play(SONG_65)
            bm.gameStateBits = bm.gameStateBits or (1 shl 2)
        } else {
            bm.gameStateBits = bm.gameStateBits and (1 shl 2).inv()
        }
    }

    /**
     * Ported from src/bm.c: HandleMoveMapCursor
     *
     * Moves the display cursor [step] pixels per axis towards the cursor target.
     */
    public fun handleMoveMapCursor(step: Int) {
        if (bm.playerCursorDisplayX < bm.cursorTargetX) {
            bm.playerCursorDisplayX = s16(bm.playerCursorDisplayX + step)
        }
        if (bm.playerCursorDisplayX > bm.cursorTargetX) {
            bm.playerCursorDisplayX = s16(bm.playerCursorDisplayX - step)
        }
        if (bm.playerCursorDisplayY < bm.cursorTargetY) {
            bm.playerCursorDisplayY = s16(bm.playerCursorDisplayY + step)
        }
        if (bm.playerCursorDisplayY > bm.cursorTargetY) {
            bm.playerCursorDisplayY = s16(bm.playerCursorDisplayY - step)
        }
    }

    /**
     * Ported from src/bm.c: HandleMoveCameraWithMapCursor
     *
     * Scrolls the camera [step] pixels per axis when the display cursor is outside the scroll
     * margins (clamping at 0 and `cameraMax`), remembering the direction and the offset into the
     * tile in `unk32`/`unk34`/`unk36`/`unk37`; when nothing scrolled it finishes the move to the
     * next 16 pixel boundary.
     *
     * Verified on the scroll and clamp paths; the tile-finish branch and the exact `cameraMax`
     * boundary of the x clamp are not reached by any reference run (they need a direction
     * reversal or a camera stop mid-tile).
     */
    public fun handleMoveCameraWithMapCursor(step: Int) {
        var isUpdated = false
        val xc = bm.playerCursorDisplayX
        val yc = bm.playerCursorDisplayY

        if (bm.cameraX + Camera.MARGIN_LEFT > xc) {
            if (xc - Camera.MARGIN_LEFT < 0) {
                bm.cameraX = 0
            } else {
                isUpdated = true
                bm.cameraX = s16(bm.cameraX - step)
                bm.unk36 = s8(-step)
                bm.unk32 = s16(bm.cameraX and 0xF)
            }
        }
        if (bm.cameraX + Camera.MARGIN_RIGHT < xc) {
            if (xc - Camera.MARGIN_RIGHT > bm.cameraMaxX) {
                bm.cameraX = bm.cameraMaxX
            } else {
                isUpdated = true
                bm.cameraX = s16(bm.cameraX + step)
                bm.unk36 = s8(step)
                bm.unk32 = s16(bm.cameraX and 0xF)
            }
        }
        if (bm.cameraY + Camera.MARGIN_TOP > yc) {
            if (yc - Camera.MARGIN_TOP < 0) {
                bm.cameraY = 0
            } else {
                isUpdated = true
                bm.cameraY = s16(bm.cameraY - step)
                bm.unk37 = s8(-step)
                bm.unk34 = s16(bm.cameraY and 0xF)
            }
        }
        if (bm.cameraY + Camera.MARGIN_BOTTOM < yc) {
            if (yc - Camera.MARGIN_BOTTOM > bm.cameraMaxY) {
                bm.cameraY = bm.cameraMaxY
            } else {
                isUpdated = true
                bm.cameraY = s16(bm.cameraY + step)
                bm.unk37 = s8(step)
                bm.unk34 = s16(bm.cameraY and 0xF)
            }
        }
        if (!isUpdated) {
            if (bm.unk32 != 0) {
                bm.unk32 = s16((bm.unk32 + bm.unk36) and 0xF)
                bm.cameraX = s16(bm.cameraX + bm.unk36)
            }
            if (bm.unk34 != 0) {
                bm.unk34 = s16((bm.unk34 + bm.unk37) and 0xF)
                bm.cameraY = s16(bm.cameraY + bm.unk37)
            }
        }
    }

    /** Constants. */
    public companion object {
        /** `PROC_MARK_MAIN`. */
        public const val PROC_MARK_MAIN: Int = 2

        /** `MAP_MOVEMENT_MAX` (bmmap.h). */
        public const val MAP_MOVEMENT_MAX: Int = 120

        /** `SONG_65`, the cursor move sound. */
        public const val SONG_65: Int = 0x65

        private const val KEY_A = 1 shl 0
        private const val KEY_B = 1 shl 1
        private const val KEY_R = 1 shl 8
        private const val KEY_L = 1 shl 9
        private const val DPAD_ANY = 0xF0

        /** `sDirKeysToOffsetLut` x offsets by the D-pad nibble (right, left, up, down). */
        private val DIR_X = intArrayOf(0, 1, -1, 0, 0, 1, -1, 0, 0, 1, -1, 0, 0, 0, 0, 0)

        /** `sDirKeysToOffsetLut` y offsets by the D-pad nibble. */
        private val DIR_Y = intArrayOf(0, 0, 0, 0, -1, -1, -1, 0, 1, 1, 1, 0, 0, 0, 0, 0)
    }
}
