package fe8.game.ui

import fe8.game.scene.IntSupplier
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
 * The shimmer of green text (decomp `src/fontgrp.c`): text drawn in `TEXT_COLOR_SYSTEM_GREEN`
 * (= `TEXT_COLOR_0DEF`, which reads palette entries 13 to 15 of BG palette bank
 * `BGPAL_TEXT_DEFAULT`) changes its middle shade over time. One proc rewrites entry 14 of that
 * bank from the 16 colours of `Pal_GreenTextColors`, one colour per 4 frames. Started by the
 * screens that draw green text: every menu (`sProc_Menu` in uimenu.c, e.g. the unit and map
 * menus), the stat screen, the unit list, the prep-screen item lists and others.
 */
public class GreenText(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val hardware: BgHardware,
    private val gameClock: IntSupplier,
) {
    private val onLoop = ProcFunc { greenTextOnLoop() }

    /**
     * `ProcScr_GreenTextColor` (0x08588284): ends itself if another copy runs
     * (`PROC_END_IF_DUPLICATE`), otherwise calls [greenTextOnLoop] every frame.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GreenTextColor",
        listOf(endIfDuplicate(), repeat(onLoop), end()),
    )

    /**
     * Sets entry 14 of BG palette bank 0 to `Pal_GreenTextColors[(gameClock / 4) % 16]`, a
     * cycle of 64 frames, and flags the palette for upload to the display.
     *
     * Ported from src/fontgrp.c: GreenText_OnLoop
     */
    public fun greenTextOnLoop() {
        val index = (gameClock() / 4) % 16
        hardware.palette[14] = rom.u16(PAL_GREEN_TEXT_COLORS + 2 * index).toShort()
        hardware.enablePaletteSync()
    }

    /**
     * Starts the colour proc under [parent] (so it ends with it), or as a root proc of tree 3
     * (`PROC_TREE_3`) when [parent] is null. A second start while one runs ends itself at once.
     *
     * Ported from src/fontgrp.c: StartGreenText
     */
    public fun startGreenText(parent: Proc?) {
        if (parent != null) engine.start(script, parent) else engine.start(script, 3)
    }

    /**
     * Ends the colour proc (every `ProcScr_GreenTextColor`), for example when a menu closes
     * (`sProc_MenuMain`). The palette entry keeps its last colour.
     *
     * Transcribed from src/fontgrp.c: EndGreenText (not verified: only `sProc_MenuMain` calls
     * it after the menu's idle callback breaks, which no capture does)
     */
    public fun endGreenText() {
        engine.endEach(script)
    }

    private companion object {
        /** ROM address of `Pal_GreenTextColors`: 16 BGR555 colours, the shimmer's frames. */
        const val PAL_GREEN_TEXT_COLORS = 0x0859EFC0
    }
}
