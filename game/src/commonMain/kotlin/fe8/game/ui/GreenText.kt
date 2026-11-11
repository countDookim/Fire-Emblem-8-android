package fe8.game.ui

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endIfDuplicate
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware

/**
 * The cycling colour of green menu text (decomp `src/fontgrp.c`): one proc rewrites BG palette
 * colour 14 from `Pal_GreenTextColors`, a new colour every 4 frames.
 */
public class GreenText(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val hardware: BgHardware,
    private val gameClock: () -> Int,
) {
    private val onLoop = ProcFunc { greenTextOnLoop() }

    /** `ProcScr_GreenTextColor` (0x08588284). */
    public val script: ProcScript = ProcScript(
        "ProcScr_GreenTextColor",
        listOf(endIfDuplicate(), repeat(onLoop), end()),
    )

    /**
     * Sets BG palette colour 14 to the colour for the current game clock and flags the palette
     * for sync.
     *
     * Ported from src/fontgrp.c: GreenText_OnLoop
     */
    public fun greenTextOnLoop() {
        val index = (gameClock() / 4) % 16
        hardware.palette[14] = rom.u16(PAL_GREEN_TEXT_COLORS + 2 * index).toShort()
        hardware.enablePaletteSync()
    }

    /**
     * Starts the colour proc under [parent], or as a root proc of tree 3 when [parent] is null.
     *
     * Ported from src/fontgrp.c: StartGreenText
     */
    public fun startGreenText(parent: Proc?) {
        if (parent != null) engine.start(script, parent) else engine.start(script, 3)
    }

    /**
     * Ends every colour proc.
     *
     * Transcribed from src/fontgrp.c: EndGreenText (not verified: only `sProc_MenuMain` calls
     * it after the menu's idle callback breaks, which no capture does)
     */
    public fun endGreenText() {
        engine.endEach(script)
    }

    private companion object {
        /** ROM address of `Pal_GreenTextColors` (16 colours). */
        const val PAL_GREEN_TEXT_COLORS = 0x0859EFC0
    }
}
