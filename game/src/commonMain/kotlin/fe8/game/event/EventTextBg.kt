package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/** Services the text background command of `src/eventscr.c` uses outside the event engine. */
public interface EventTextBgHost {
    /** `BG_SetPosition(BG_0..BG_3, 0, 0)`. */
    public fun resetBgPositions()

    /** `NextRN_N(BG_BLANK)`: the random background of the support viewers. */
    public fun randomBackground(): Int

    /**
     * The load of `EventShowTextBgDirect`'s `EVSUBCMD_REMOVEPORTRAITS` case: `Decompress` of
     * `gConvoBackgroundData[bgIndex].gfx` to the BG3 tile data, `CallARM_FillTileRect` of its
     * tsa into `gBG3TilemapBuffer` (base 0x8000), `ApplyPalettes(pal, 8, 8)`, BG3 sync, palette
     * sync and `gPaletteBuffer[0] = 0`.
     */
    public fun loadConvoBackground(bgIndex: Int)

    /**
     * Stand-in for the `EVSUBCMD_0x1A22` case: `DisplayCGfx(gBG3TilemapBuffer, ...)`, BG3 and
     * palette sync.
     */
    public fun displayCg(bgIndex: Int)

    /**
     * Stand-in for `Proc_StartBlocking(gUnknown_08591E00/E58/EB0, proc)` with the fields set as
     * `Event21_TextBg` does (`sub_800EA84` ... `sub_800EF48`). [fadeType] is 0, 1 or 2.
     */
    public fun startBackgroundFade(
        fadeType: Int,
        unkType: Int,
        bgIndex: Int,
        fadeSpeed: Int,
        proc: EventEngineProc,
    )

    /** `Event22_ClearScreen(proc)`: returns its result code. */
    public fun clearScreen(proc: EventEngineProc): Int

    /** Stand-in for `NewColFadeIn(speed, 0, color, proc)`. */
    public fun colorFadeIn(speed: Int, color: Int, proc: EventEngineProc)

    /** Stand-in for `NewColFadeOut(speed, 0, color, proc)`. */
    public fun colorFadeOut(speed: Int, color: Int, proc: EventEngineProc)
}

/** The text background command (0x21) of `src/eventscr.c`. */
public class EventTextBg(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val host: EventTextBgHost,
) {
    /** Registers 0x21 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x21, event21TextBg)
    }

    /**
     * Ported from src/eventscr.c: EventShowTextBgDirect
     *
     * The tutorial, solo and 0x1A25 modes return an error.
     */
    public fun eventShowTextBgDirect(mode: Int, bgIndexIn: Int): Int {
        host.resetBgPositions()
        var bgIndex = bgIndexIn
        return when (mode) {
            0, 3, 4, 5 -> EVC_ERROR
            1 -> {
                if (bgIndex == BG_RANDOM) bgIndex = host.randomBackground()
                host.loadConvoBackground(bgIndex)
                EVC_ADVANCE_YIELD
            }
            2 -> {
                host.displayCg(bgIndex)
                EVC_ADVANCE_YIELD
            }
            else -> EVC_ADVANCE_YIELD
        }
    }

    /**
     * Ported from src/eventscr.c: Event21_TextBg
     *
     * The background fade procs are host stand-ins.
     */
    public val event21TextBg: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val subcode = memory.u16(currentCmd) and 0xF
        var bgIndex = memory.u16(currentCmd + 2).toShort().toInt()
        if (bgIndex < 0) bgIndex = state.slots[2]
        val skipping = proc.evStateBits and EV_STATE_SKIPPING != 0
        when (subcode) {
            0 -> return@EventHandler eventShowTextBgDirect(proc.activeTextType, bgIndex)
            1 -> {
                val transitionKind = memory.u16(currentCmd + 4)
                val fadeSpeed = memory.u16(currentCmd + 6)
                val fadeType: Int
                when (proc.activeTextType) {
                    0, 3, 4, 5 -> when (transitionKind) {
                        1, 2 -> {
                            if (skipping) return@EventHandler EVC_ADVANCE_CONTINUE
                            fadeType = 1
                        }
                        else -> return@EventHandler EVC_ERROR
                    }
                    1, 2 -> when (transitionKind) {
                        0, 3, 4, 5 -> {
                            if (skipping) return@EventHandler host.clearScreen(proc)
                            fadeType = 2
                        }
                        1, 2 -> {
                            if (skipping) return@EventHandler EVC_ADVANCE_CONTINUE
                            fadeType = 0
                        }
                        else -> return@EventHandler EVC_ERROR
                    }
                    else -> return@EventHandler EVC_ERROR
                }
                host.startBackgroundFade(fadeType, transitionKind, bgIndex, fadeSpeed, proc)
            }
            2 -> {
                val color = memory.u16(currentCmd + 4)
                val fadeSpeed = memory.u16(currentCmd + 6)
                if (skipping) return@EventHandler EVC_ADVANCE_CONTINUE
                if (eventShowTextBgDirect(proc.activeTextType, bgIndex) == EVC_ERROR) {
                    return@EventHandler EVC_ERROR
                }
                host.colorFadeIn(fadeSpeed, color, proc)
            }
            3 -> {
                val color = memory.u16(currentCmd + 4)
                val fadeSpeed = memory.u16(currentCmd + 6)
                if (skipping) return@EventHandler EVC_ADVANCE_CONTINUE
                host.colorFadeOut(fadeSpeed, color, proc)
            }
        }
        EVC_ADVANCE_YIELD
    }

    private companion object {
        /** `BG_RANDOM`. */
        const val BG_RANDOM = 0x37
    }
}
