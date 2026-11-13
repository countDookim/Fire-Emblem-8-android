package fe8.game.event

import gbafe.proc.Proc

/** Event engine constants (`include/event.h`): execution types, state bits and result codes. */
public object EventConst {
    /** `EV_EXEC_WORLDMAP`: script of the world map (the `hi` command table). */
    public const val EV_EXEC_WORLDMAP: Int = 0

    /** `EV_EXEC_CUTSCENE`: chapter cutscene; locks the game while it runs. */
    public const val EV_EXEC_CUTSCENE: Int = 1

    /**
     * `EV_EXEC_GAMEPLAY`: script started by play (battle quotes, village and chest tiles, game
     * over); locks the game while it runs.
     */
    public const val EV_EXEC_GAMEPLAY: Int = 2

    /** `EV_EXEC_QUIET`: runs commands only: no locking, fade or talk cleanup at the end. */
    public const val EV_EXEC_QUIET: Int = 3

    /** `EV_EXEC_UNK4`: set when a world map script ends; the decomp calls it not used. */
    public const val EV_EXEC_UNK4: Int = 4

    /** `EV_EXEC_UNK5`: set when a cutscene script reaches its end command; closing script runs. */
    public const val EV_EXEC_UNK5: Int = 5

    /** `EV_STATE_ABORT`: queued event calls wait (decomp); set on battle engines. */
    public const val EV_STATE_ABORT: Int = 1 shl 0

    /** `EV_STATE_0002`: prevents ENDA events; set by every update. */
    public const val EV_STATE_0002: Int = 1 shl 1

    /** `EV_STATE_SKIPPING`: the event is being skipped with Start. */
    public const val EV_STATE_SKIPPING: Int = 1 shl 2

    /**
     * `EV_STATE_0008`: set by `SetDialogueSkipEvBit`; a stall command that allows it then ends
     * at once (the decomp guesses it is dialogue related).
     */
    public const val EV_STATE_0008: Int = 1 shl 3

    /** `EV_STATE_NOSKIP`: skipping with Start is forbidden (set by the EVBIT_MODIFY command). */
    public const val EV_STATE_NOSKIP: Int = 1 shl 4

    /** `EV_STATE_0020`: set by the EVBIT_MODIFY command; its meaning is unknown. */
    public const val EV_STATE_0020: Int = 1 shl 5

    /** `EV_STATE_0040`: set by EVBIT_MODIFY; stalls then no longer run faster on game speed/A. */
    public const val EV_STATE_0040: Int = 1 shl 6

    /** `EV_STATE_NOFADE`: do not fade at the end of the event (and not when skipping). */
    public const val EV_STATE_NOFADE: Int = 1 shl 7

    /**
     * `EV_STATE_FADEDIN`: the screen is faded out (decomp: "faded into white/back"); set at
     * creation when `blendY` is 0x10, by the fade commands and by skipping.
     */
    public const val EV_STATE_FADEDIN: Int = 1 shl 8

    /** `EV_STATE_UNITCAM`: the camera follows unit movement. */
    public const val EV_STATE_UNITCAM: Int = 1 shl 9

    /** `EV_STATE_CHANGECH`: moving to another chapter (decomp is unsure). */
    public const val EV_STATE_CHANGECH: Int = 1 shl 10

    /** `EV_STATE_CHANGEGM`: the game mode is changing; [EventEngine.onEnd] ends the map. */
    public const val EV_STATE_CHANGEGM: Int = 1 shl 11

    /** `EV_STATE_GFXLOCKED`: map graphics were locked by script commands 0x23 and 0x24. */
    public const val EV_STATE_GFXLOCKED: Int = 1 shl 12

    /** `EVC_ADVANCE_CONTINUE`: advance by the length nibble, keep running this frame. */
    public const val EVC_ADVANCE_CONTINUE: Int = 0

    /** `EVC_STOP_CONTINUE`: do not advance, keep running this frame. */
    public const val EVC_STOP_CONTINUE: Int = 1

    /** `EVC_ADVANCE_YIELD`: advance, then end this frame's update. */
    public const val EVC_ADVANCE_YIELD: Int = 2

    /** `EVC_STOP_YIELD`: do not advance, end this frame's update. */
    public const val EVC_STOP_YIELD: Int = 3

    /** `EVC_UNK4`: behaves as [EVC_STOP_CONTINUE]; the CALL command returns it after jumping. */
    public const val EVC_UNK4: Int = 4

    /** `EVC_END`: break the engine proc. */
    public const val EVC_END: Int = 5

    /**
     * `EVC_ERROR`: a command that failed. C re-runs the same command like [EVC_STOP_CONTINUE]
     * (and so hangs); this port ends the frame instead (see [EventEngine.onUpdate]).
     */
    public const val EVC_ERROR: Int = 6
}

/**
 * Event engine proc state: the per-run state of one running event script, the fields of
 * `struct EventEngineProc` (include/event.h) after the proc header. Script pointers are GBA
 * addresses of `u16` words (see [ScriptMemory]); 0 is NULL.
 */
public class EventEngineProc {
    /** The proc running this state, set by [EventEngine.create] right after the proc starts. */
    public var proc: Proc? = null

    /** `pCallback` (+2C): when set, replaces script execution (see [EventEngine.onUpdate]). */
    public var callback: EventEngineCallback? = null

    /** `pEventStart` (+30): first command of the script; nothing in the decomp changes it. */
    public var pEventStart: Int = 0

    /**
     * `pEventIdk` (+34): the start of the script part running now; the GOTO command searches for
     * its label from here, CALL moves it into the callee and END restores it (name is the
     * decomp's guess).
     */
    public var pEventIdk: Int = 0

    /** `pEventCurrent` (+38): address of the command being executed. */
    public var pEventCurrent: Int = 0

    /** `evStateBits` (+3C), u16; the `EV_STATE_*` bits of [EventConst]. */
    public var evStateBits: Int = 0

    /** `evStallTimer` (+3E), u16: frames left of the running STAL (wait) command; 0 = none. */
    public var evStallTimer: Int = 0

    /** `overwrittenTextSpeed` (+40), s8: player's text speed saved by a text command; -1 = none. */
    public var overwrittenTextSpeed: Int = 0

    /** `execType` (+41), u8: one of `EV_EXEC_*`. */
    public var execType: Int = 0

    /** `activeTextType` (+42), u8: subcommand of the last text start command (0 = none). */
    public var activeTextType: Int = 0

    /**
     * `chapterIndex` (+43), u8: the chapter running when a cutscene or gameplay engine was
     * created; 0xFF for other engines.
     */
    public var chapterIndex: Int = 0

    /** `mapSpritePalIdOverride` (+44), u16: palette set by the sprite palette command. */
    public var mapSpritePalIdOverride: Int = 0

    /** `pUnitLoadData` (+48): address of the unit definition list. */
    public var pUnitLoadData: Int = 0

    /** `unitLoadCount` (+4C), s16. */
    public var unitLoadCount: Int = 0

    /**
     * `chance` (+4E), u8: percentage 0-100 set by a unit load command and passed to
     * `GetUnitDefinitionFromEventScr`; its exact effect is not traced.
     */
    public var chance: Int = 0

    /** `unitLoadParameter` (+4F, low 7 bits): the argument of the unit load with movement. */
    public var unitLoadParameter: Int = 0

    /**
     * `diable_REDA` (+4F bit 7): set by unit load subcommand 2 and cleared after the next load;
     * its effect lives in `GetUnitDefinitionFromEventScr` (not traced).
     */
    public var disableReda: Boolean = false

    /** `unit` (+54): index of the unit the engine refers to (0 = none); a pointer in C. */
    public var unit: Int = 0

    /** Resets every field for a freshly created engine proc. */
    internal fun reset() {
        proc = null
        callback = null
        pEventStart = 0
        pEventIdk = 0
        pEventCurrent = 0
        evStateBits = 0
        evStallTimer = 0
        overwrittenTextSpeed = 0
        execType = 0
        activeTextType = 0
        chapterIndex = 0
        mapSpritePalIdOverride = 0
        pUnitLoadData = 0
        unitLoadCount = 0
        chance = 0
        unitLoadParameter = 0
        disableReda = false
        unit = 0
    }
}

/** `pCallback` of an engine proc: runs instead of the script for the frame. */
public fun interface EventEngineCallback {
    /** Runs the callback for [proc]. */
    public fun invoke(proc: EventEngineProc)
}

/** One event command (`EventFuncType`): the code of one script command; returns an `EVC_*` code. */
public fun interface EventHandler {
    /** Executes the command at `proc.pEventCurrent`; returns an `EVC_*` code. */
    public fun run(proc: EventEngineProc): Int
}

/** Read access to script words by GBA address. */
public fun interface ScriptMemory {
    /** The unsigned halfword at [address]. */
    public fun u16(address: Int): Int
}

/**
 * Script memory: ROM addresses (0x08000000..0x09FFFFFF) read through [rom], synthetic mod
 * addresses (from [MOD_BASE]) read from [mod] words.
 */
public class ScriptWords(
    private val rom: ScriptMemory,
    private val mod: ShortArray = ShortArray(0),
) : ScriptMemory {
    override fun u16(address: Int): Int =
        if (address.toUInt() >= MOD_BASE.toUInt()) {
            val wordIndex = (address - MOD_BASE) ushr 1
            if (wordIndex < mod.size) mod[wordIndex].toInt() and 0xFFFF else 0
        } else {
            rom.u16(address)
        }

    /** Constants. */
    public companion object {
        /** First synthetic address for mod scripts (docs/decisions/0001-script-format.md). */
        public const val MOD_BASE: Int = 0x0A000000
    }
}

/**
 * The game systems the event engine drives but that live outside this package (fades, menu,
 * talk, world map, LCD); the defaults do nothing, so tests run the engine alone.
 */
public interface EventEngineHooks {
    /** `DoesBMXFADEExist`: a map fade is running and the engine waits for it this frame. */
    public fun fadeExists(): Boolean = false

    /** `gKeyStatusPtr->newKeys & START_BUTTON`: Start was pressed this frame (skips the event). */
    public fun startPressed(): Boolean = false

    /** The rest of `EventEngine_CanStartSkip` (`IsBattleDeamonActive`, `ProcScr_MuDeathFade`). */
    public fun skipBlockedByOthers(): Boolean = false

    /** `WM_Exists`. */
    public fun worldMapExists(): Boolean = false

    /** `StartLockingFadeToBlack(speed, parent)`. */
    public fun startLockingFadeToBlack(speed: Int, parent: Proc) {}

    /** `sub_800D488` (`sub_80141B0` layers off, `Proc_EndEach(gProcScr_TalkOpen)`). */
    public fun skipFadedIn(proc: EventEngineProc) {}

    /** `sub_80BA424`, the world map part of `EventEngine_StartSkip`. */
    public fun worldMapSkip(proc: EventEngineProc) {}

    /** Turns on BG0-3 and OBJ in the LCD control buffer (non-worldmap update). */
    public fun showAllLayers() {}

    /** `_FreezeMenu` (`FreezeMenu`): marks an open menu frozen so it ignores input meanwhile. */
    public fun freezeMenu() {}

    /** `gLCDControlBuffer.blendY`: 0x10 is a full fade (see `EV_STATE_FADEDIN`). */
    public fun blendY(): Int = 0

    /** `gPlaySt.chapterIndex`. */
    public fun chapterIndex(): Int = 0

    /** `LockGame`. */
    public fun lockGame() {}

    /** `SetTextFont(NULL)` + `InitSystemTextFont` + `LoadUiFrameGraphics`. */
    public fun resetTextFont() {}

    /** `ReadGameSaveCoreGfx` + `UnpackChapterMapPalette` + `ChangeUnitSpritePalette(palId)`. */
    public fun restoreAfterEvent5(mapSpritePalIdOverride: Int) {}

    /** `EndAllMus` + `EndBMapMain`. */
    public fun endMapForChangeGm() {}

    /** `UnlockGame` + `ResumeMenu` + `ResetBkselPalette` + `ClearCutsceneUnits`. */
    public fun resumeGameplay() {}

    /** `EndTalk` + `EndCgText` + `sub_808BB74`. */
    public fun endEventTalk() {}

    /** `ChangeUnitSpritePalette(palId)`. */
    public fun changeUnitSpritePalette(palId: Int) {}

    /** `sub_800E640(proc)`. */
    public fun eventEndCleanup(proc: EventEngineProc) {}
}
