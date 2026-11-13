package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * Services the text background command of `src/eventscr.c` (`Event21_TextBg`: the picture behind
 * a conversation or CG scene) uses outside the event engine: BG loads and fade procs.
 */
public interface EventTextBgHost {
    /** `BG_SetPosition(BG_0..BG_3, 0, 0)`: scrolls all four backgrounds back to the origin. */
    public fun resetBgPositions()

    /**
     * `NextRN_N(BG_BLANK)` (`BG_BLANK` = 0x35, include/constants/backgrounds.h): a random
     * conversation background index, for the support viewers (`BG_RANDOM`).
     */
    public fun randomBackground(): Int

    /**
     * The load of `EventShowTextBgDirect`'s `EVSUBCMD_REMOVEPORTRAITS` case: `Decompress` of
     * `gConvoBackgroundData[bgIndex].gfx` to the BG3 tile data, `CallARM_FillTileRect` of its
     * tsa into `gBG3TilemapBuffer` (base 0x8000), `ApplyPalettes(pal, 8, 8)`, BG3 sync, palette
     * sync and `gPaletteBuffer[0] = 0`.
     */
    public fun loadConvoBackground(bgIndex: Int)

    /**
     * Stand-in until M6-E for the `EVSUBCMD_0x1A22` case (text type 2): `DisplayCGfx(
     * gBG3TilemapBuffer, ...)` shows CG picture [bgIndex] on BG3, then BG3 and palette sync.
     */
    public fun displayCg(bgIndex: Int)

    /**
     * Stand-in until M6-E for
     * `Proc_StartBlocking(gUnknown_08591E00/E58/EB0, proc)` with the fields set as
     * `Event21_TextBg` does (`sub_800EA84` ... `sub_800EF48`): the background fades to [bgIndex]
     * at [fadeSpeed] while the event waits. [fadeType] picks the proc: 0 `gUnknown_08591E00`
     * (text type 1 or 2 with transition kind 1 or 2), 1 `gUnknown_08591E58` (type 0, 3, 4 or 5
     * with kind 1 or 2) and 2 `gUnknown_08591EB0` (type 1 or 2 with kind 0, 3, 4 or 5);
     * [unkType] is the command's transition kind (`unkType` in the decomp; the kind values 1
     * and 2 are `EVSUBCMD_REMOVEPORTRAITS` and `EVSUBCMD_0x1A22`).
     */
    public fun startBackgroundFade(
        fadeType: Int,
        unkType: Int,
        bgIndex: Int,
        fadeSpeed: Int,
        proc: EventEngineProc,
    )

    /**
     * `Event22_ClearScreen(proc)` (the `CLEAN` command): clears the screen and re-enables the
     * map display; returns its result code.
     */
    public fun clearScreen(proc: EventEngineProc): Int

    /**
     * Stand-in (M6-E) for `NewColFadeIn(speed, 0, color, proc)`: fades the screen in from
     * [color] (a colour word, format not checked here) at [speed].
     */
    public fun colorFadeIn(speed: Int, color: Int, proc: EventEngineProc)

    /**
     * Stand-in (M6-E) for `NewColFadeOut(speed, 0, color, proc)`: fades the screen out to
     * [color] at [speed].
     */
    public fun colorFadeOut(speed: Int, color: Int, proc: EventEngineProc)
}

/**
 * The text background command (0x21, `EV_CMD_SHOWBG`, `Event21_TextBg`) of `src/eventscr.c`.
 * Sub-commands: 0 `EvtDisplayTextBg` (show a background now), 1 `EvtBgTransion` (a background
 * transition), 2 `EvtBgFadeIn` (show, then fade in from a colour) and 3 (fade out to a colour;
 * the decomp macro `EvtBgFadeOut` encodes subcode 2 and is marked "not sure",
 * include/eventscript.h:669).
 *
 * @param state the event slots (slot 2 is the default background index).
 * @param memory script words (the command arguments).
 * @param host the background loads and fades.
 */
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
     * Shows background [bgIndexIn] (a random one for `BG_RANDOM`, 0x37) straight away for the
     * text [mode] (the event proc's `activeTextType`, set by command 0x1A): mode 1
     * (`EVSUBCMD_REMOVEPORTRAITS`) loads a conversation background, 2 (`EVSUBCMD_0x1A22`) a CG.
     * Modes 0 (plain text), 3 (tutorial box), 4 (solo box) and 5 (`EVSUBCMD_0x1A25`) return an
     * error; a larger mode yields without doing anything.
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
     * Sub-command 1 chooses the fade by the text type and the transition kind (word 2) and
     * starts it with the speed (word 3); when skipping it only clears the screen (the
     * text type 1 or 2 kinds 0, 3, 4, 5) or does nothing. Sub-commands 2 and 3 fade from or to
     * a colour (word 2) at a speed (word 3) and do nothing while skipping.
     *
     * NOTE: for a transition kind above 5, or a text type of 6 or more, the decomp switch has
     * no case and falls through to code that writes to an uninitialised `otherProc`
     * (src/eventscr.c:1403-1458); this port returns an error instead.
     *
     * The background fade procs are host stand-ins (M6-E).
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
        /** `BG_RANDOM` (include/constants/backgrounds.h): "pick a random background". */
        const val BG_RANDOM = 0x37
    }
}
