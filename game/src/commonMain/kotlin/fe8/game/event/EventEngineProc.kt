package fe8.game.event

import gbafe.proc.Proc

/** Event engine constants (`include/event.h`): execution types, state bits and result codes. */
public object EventConst {
    /** `EV_EXEC_WORLDMAP`. */
    public const val EV_EXEC_WORLDMAP: Int = 0

    /** `EV_EXEC_CUTSCENE`. */
    public const val EV_EXEC_CUTSCENE: Int = 1

    /** `EV_EXEC_GAMEPLAY`. */
    public const val EV_EXEC_GAMEPLAY: Int = 2

    /** `EV_EXEC_QUIET`. */
    public const val EV_EXEC_QUIET: Int = 3

    /** `EV_EXEC_UNK4`. */
    public const val EV_EXEC_UNK4: Int = 4

    /** `EV_EXEC_UNK5`. */
    public const val EV_EXEC_UNK5: Int = 5

    /** `EV_STATE_ABORT`. */
    public const val EV_STATE_ABORT: Int = 1 shl 0

    /** `EV_STATE_0002`: prevents ENDA events; set by every update. */
    public const val EV_STATE_0002: Int = 1 shl 1

    /** `EV_STATE_SKIPPING`. */
    public const val EV_STATE_SKIPPING: Int = 1 shl 2

    /** `EV_STATE_0008`. */
    public const val EV_STATE_0008: Int = 1 shl 3

    /** `EV_STATE_NOSKIP`. */
    public const val EV_STATE_NOSKIP: Int = 1 shl 4

    /** `EV_STATE_0020`. */
    public const val EV_STATE_0020: Int = 1 shl 5

    /** `EV_STATE_0040`. */
    public const val EV_STATE_0040: Int = 1 shl 6

    /** `EV_STATE_NOFADE`. */
    public const val EV_STATE_NOFADE: Int = 1 shl 7

    /** `EV_STATE_FADEDIN`. */
    public const val EV_STATE_FADEDIN: Int = 1 shl 8

    /** `EV_STATE_UNITCAM`. */
    public const val EV_STATE_UNITCAM: Int = 1 shl 9

    /** `EV_STATE_CHANGECH`. */
    public const val EV_STATE_CHANGECH: Int = 1 shl 10

    /** `EV_STATE_CHANGEGM`. */
    public const val EV_STATE_CHANGEGM: Int = 1 shl 11

    /** `EV_STATE_GFXLOCKED`. */
    public const val EV_STATE_GFXLOCKED: Int = 1 shl 12

    /** `EVC_ADVANCE_CONTINUE`: advance by the length nibble, keep running this frame. */
    public const val EVC_ADVANCE_CONTINUE: Int = 0

    /** `EVC_STOP_CONTINUE`: do not advance, keep running this frame. */
    public const val EVC_STOP_CONTINUE: Int = 1

    /** `EVC_ADVANCE_YIELD`: advance, then end this frame's update. */
    public const val EVC_ADVANCE_YIELD: Int = 2

    /** `EVC_STOP_YIELD`: do not advance, end this frame's update. */
    public const val EVC_STOP_YIELD: Int = 3

    /** `EVC_UNK4`: behaves as [EVC_STOP_CONTINUE]. */
    public const val EVC_UNK4: Int = 4

    /** `EVC_END`: break the engine proc. */
    public const val EVC_END: Int = 5

    /** `EVC_ERROR`: behaves as [EVC_STOP_CONTINUE] (see [EventEngine.onUpdate]). */
    public const val EVC_ERROR: Int = 6
}

/**
 * Event engine proc state: the fields of `struct EventEngineProc` (include/event.h) after the proc
 * header. Script pointers are GBA addresses of `u16` words (see [ScriptMemory]); 0 is NULL.
 */
public class EventEngineProc {
    /** The proc running this state, set by [EventEngine.create] right after the proc starts. */
    public var proc: Proc? = null

    /** `pCallback` (+2C): when set, replaces script execution (see [EventEngine.onUpdate]). */
    public var callback: EventEngineCallback? = null

    /** `pEventStart` (+30). */
    public var pEventStart: Int = 0

    /** `pEventIdk` (+34). */
    public var pEventIdk: Int = 0

    /** `pEventCurrent` (+38): address of the command being executed. */
    public var pEventCurrent: Int = 0

    /** `evStateBits` (+3C), u16; the `EV_STATE_*` bits of [EventConst]. */
    public var evStateBits: Int = 0

    /** `evStallTimer` (+3E), u16. */
    public var evStallTimer: Int = 0

    /** `overwrittenTextSpeed` (+40), s8. */
    public var overwrittenTextSpeed: Int = 0

    /** `execType` (+41), u8: one of `EV_EXEC_*`. */
    public var execType: Int = 0

    /** `activeTextType` (+42), u8. */
    public var activeTextType: Int = 0

    /** `chapterIndex` (+43), u8. */
    public var chapterIndex: Int = 0

    /** `mapSpritePalIdOverride` (+44), u16. */
    public var mapSpritePalIdOverride: Int = 0

    /** `pUnitLoadData` (+48): address of the unit definition list. */
    public var pUnitLoadData: Int = 0

    /** `unitLoadCount` (+4C), s16. */
    public var unitLoadCount: Int = 0

    /** `chance` (+4E), u8. */
    public var chance: Int = 0

    /** `unitLoadParameter` (+4F, low 7 bits). */
    public var unitLoadParameter: Int = 0

    /** `diable_REDA` (+4F bit 7). */
    public var disableReda: Boolean = false

    /** `unit` (+54): index of the unit struct the engine refers to (0 = none). */
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

/** One event command (`EventFuncType`): returns an `EVC_*` code. */
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

/** Environment hooks of the engine that live outside this package; defaults do nothing. */
public interface EventEngineHooks {
    /** `DoesBMXFADEExist`: a map fade blocks the engine this frame. */
    public fun fadeExists(): Boolean = false

    /** `gKeyStatusPtr->newKeys & START_BUTTON`. */
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

    /** `_FreezeMenu`. */
    public fun freezeMenu() {}

    /** `gLCDControlBuffer.blendY`. */
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
