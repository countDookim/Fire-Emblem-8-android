package fe8.game.bm

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.mapanim.MapLevelUpAnnouncement
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * Proc user data for `ProcScr_MapAnimExpBar` (`struct MAExpBarProc`, include/mapanim.h).
 */
public class MAExpBarProc(
    /** `expFrom` (short, 0x64): the exp shown, counted up to [expTo] modulo 100. */
    public var expFrom: Int = 0,
    /** `expTo` (short, 0x66): the exp after the battle. */
    public var expTo: Int = 0,
    /** `actorId` (short, 0x68): index into `gManimSt.actor`. */
    public var actorId: Int = 0,
    /** `timer` (short, 0x6A): half-height of the opening window band. */
    public var timer: Int = 0,
)

/**
 * The map battle exp bar (EP-26, `src/mapanim_expbar.c`): a window that opens over BG0 at
 * the middle of the screen, a bar and a two digit number that count up to the exp gained,
 * with the `SONG_74` tick while they do.
 *
 * Only the draw of the bar is shared with the info box (its digit and bar tile writers are
 * [MapAnimInfoBox.sub807BA28] and [MapAnimInfoBox.sub807BB40]).
 *
 * @param engine the proc engine (the proc runs on the parent's tree).
 * @param rom the ROM (graphics, palette, bar tile table).
 * @param infoBox the info box whose tile writers and end callback the exp bar uses.
 * @param sound the sound effect and its stop (`SONG_74`).
 * @param hardware the LCD buffer, BG 0 tile data, tilemap and palette, null headless.
 * @param levelUp the level-up display the bar starts when the exp passes 100; null only for
 *   bars that never reach 100 (the game always passes it).
 */
public class MapAnimExpBar(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val infoBox: MapAnimInfoBox,
    private val sound: SoundWrapper,
    private val hardware: BgHardware? = null,
    private val levelUp: MapLevelUpAnnouncement? = null,
) {
    /**
     * `ProcScr_MapAnimExpBar` (0x089A36F8): the window opens, the bar ticks up, the info box is
     * cleared, and a level-up (if the exp passed 100) is displayed.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_MapAnimExpBar",
        listOf(
            setEndCb(ProcFunc { infoBox.procMapInfoBoxOnEnd() }),
            sleep(1),
            call(ProcFunc { procMAExpBarOnDraw(it) }),
            call(ProcFunc { procMAExpBarInitDisplay(it) }),
            repeat(ProcFunc { procMAExpBarFrameAdvance(it) }),
            sleep(0x14),
            call(ProcFunc { procMAExpBarPlaySound74() }),
            sleep(2),
            repeat(ProcFunc { procMAExpBarOnIncrement(it) }),
            sleep(0x14),
            call(ProcFunc { infoBox.procMapInfoBoxOnEnd() }),
            sleep(8),
            call(ProcFunc { procMAExpBarLevelUpIfPossible(it) }),
            sleep(1),
            end(),
        ),
        newState = { MAExpBarProc() },
    )

    /**
     * Starts the exp bar as a blocking child of [parent] with the given exp and actor.
     * (The caller is `MapAnim_DisplayExpBar`.)
     */
    public fun start(parent: Proc, expFrom: Int, expTo: Int, actorId: Int) {
        val proc = engine.startBlocking(script, parent) ?: return
        val state = proc.state as MAExpBarProc
        state.expFrom = expFrom
        state.expTo = expTo
        state.actorId = actorId
    }

    /**
     * Draws the exp number (two digits) and the exp bar (99 as its full value) at tile
     * ([arg0] + 2, [arg1] + 1) and ([arg0] + 3, [arg1] + 1) of BG 0, and syncs BG 0.
     *
     * Ported from src/mapanim_expbar.c: DrawMAExpBar
     */
    public fun drawMAExpBar(arg0: Int, arg1: Int, exp: Int) {
        val bg0 = hardware?.tilemaps?.get(0) ?: ShortArray(BgTilemap.ROW * BgTilemap.ROW)
        val row = (arg1 + 1) * BgTilemap.ROW
        infoBox.sub807BA28(bg0, row + arg0 + 2, exp, NUMBER_TILE_REF, 2, NUMBER_BLANK_REF, false)
        infoBox.sub807BB40(bg0, row + arg0 + 3, EXP_MAX, exp, 0, BAR_TABLE)
        hardware?.enableBgSyncByMask(1 shl 0)
    }

    /**
     * Loads the bar, bar change and number graphics and the palette, fills in the frame of the
     * bar and draws the starting exp.
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_OnDraw
     */
    public fun procMAExpBarOnDraw(proc: Proc) {
        val state = proc.state as MAExpBarProc
        hardware?.let { hw ->
            hw.lcd.setBgPosition(0, 0, 0)
            hw.lcd.setBgPosition(1, 0, 0)

            val base = hw.tileDataOffset(0)
            copyToVram(hw, IMG_EKR_EXP_BAR, base + 512 * 0x20, 13 * 0x20)
            copyToVram(hw, IMG_EKR_EXP_BAR_CHANGE, base + 525 * 0x20, 24 * 0x20)
            copyToVram(hw, IMG_BAR_NUMFX, base + 549 * 0x20, 11 * 0x20)

            val palBase = BM_BGPAL_BANIM_UNK5 * PALETTE_COLORS
            for (i in 0 until PALETTE_COLORS) {
                hw.palette[palBase + i] = rom.u16(PAL_EXP_BAR + 2 * i).toShort()
            }
            hw.enablePaletteSync()

            val tsa = ByteArray(FRAME_TSA_SIZE) { rom.u8(FRAME_TSA + it).toByte() }
            BgTilemap.applyTsa(
                hw.tilemaps[0], 8 * BgTilemap.ROW + 6, tsa, 0,
                512 or (BM_BGPAL_BANIM_UNK5 shl 12),
            )
        }
        drawMAExpBar(6, 8, state.expFrom)
    }

    /**
     * Plays the exp tick (`SONG_74`).
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_PlaySound74
     */
    public fun procMAExpBarPlaySound74() {
        sound.playSoundEffect(SONG_74)
    }

    /**
     * Counts the shown exp up by one (wrapping at 100) and redraws; on reaching `expTo % 100` ends
     * the loop and stops the tick.
     *
     * The wrap at 100 is exercised by `ram_trace_prologue_levelup` (87 to 167).
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_OnIncrement
     */
    public fun procMAExpBarOnIncrement(proc: Proc) {
        val state = proc.state as MAExpBarProc
        state.expFrom++
        if (state.expFrom >= EXP_WRAP) state.expFrom = 0

        drawMAExpBar(6, 8, state.expFrom)

        if (state.expFrom == state.expTo % EXP_WRAP) {
            engine.breakProc(proc)
            sound.sound?.songNumStop(SONG_74)
        }
    }

    /**
     * Resets the window timer, opens the first band, and turns window 0 on over all layers (the
     * outside shows BG 2, BG 3 and the sprites).
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_InitDisplay
     */
    public fun procMAExpBarInitDisplay(proc: Proc) {
        (proc.state as MAExpBarProc).timer = 0
        procMAExpBarFrameAdvance(proc)
        hardware?.lcd?.let { lcd ->
            lcd.setWinEnable(1, 0, 0)
            lcd.setWin0Layers(1, 1, 1, 1, 1)
            lcd.setWOutLayers(0, 0, 1, 1, 1)
        }
    }

    /**
     * Sets window 0 to a band of half-height `timer` around the screen's middle (y 76), grows
     * `timer` by 2, and past 12 turns the window off and ends the loop.
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_FrameAdvance
     */
    public fun procMAExpBarFrameAdvance(proc: Proc) {
        val state = proc.state as MAExpBarProc
        hardware?.lcd?.setWin0Box(0, 76 - state.timer, SCREEN_WIDTH, 76 + state.timer)

        state.timer += 2

        if (state.timer > TIMER_LIMIT) {
            hardware?.lcd?.setWinEnable(0, 0, 0)
            engine.breakProc(proc)
        }
    }

    /**
     * Starts the level-up display ([MapLevelUpAnnouncement.startManimLevelUp]) as a blocking
     * child of the exp bar when the exp passed 100; the bar resumes when it ends. The growth
     * rolls were made in the battle.
     *
     * Ported from src/mapanim_expbar.c: ProcMAExpBar_LevelUpIfPossible
     */
    public fun procMAExpBarLevelUpIfPossible(proc: Proc) {
        val state = proc.state as MAExpBarProc
        if (state.expTo >= EXP_WRAP) levelUp?.startManimLevelUp(state.actorId, proc)
    }

    private fun copyToVram(hw: BgHardware, romSrc: Int, vramOffset: Int, size: Int) {
        for (i in 0 until size) hw.vram[vramOffset + i] = rom.u8(romSrc + i).toByte()
    }

    /** Addresses and constants of the exp bar. */
    public companion object {
        /** `SONG_74`: the exp tick. */
        public const val SONG_74: Int = 0x74

        /** ROM address of `Img_EkrExpBar`. */
        public const val IMG_EKR_EXP_BAR: Int = 0x08802D44

        /** ROM address of `Img_EkrExpBarChange`. */
        public const val IMG_EKR_EXP_BAR_CHANGE: Int = 0x088030C4

        /** ROM address of `Img_BarNumfx`. */
        public const val IMG_BAR_NUMFX: Int = 0x088033C4

        /** ROM address of `Pal_ExpBar`. */
        public const val PAL_EXP_BAR: Int = 0x08803590

        /** ROM address of `gUnknown_089AD498`: the frame TSA of the bar. */
        public const val FRAME_TSA: Int = 0x089AD498

        /** Size of the frame TSA in the ROM (its header and tile entries). */
        public const val FRAME_TSA_SIZE: Int = 0x68

        /** ROM address of `gUnknown_089A36C0`: the bar segments (span, base tile pairs). */
        public const val BAR_TABLE: Int = 0x089A36C0

        /** `BM_BGPAL_BANIM_UNK5` (include/bmio.h). */
        public const val BM_BGPAL_BANIM_UNK5: Int = 5

        private const val PALETTE_COLORS = 16
        private const val SCREEN_WIDTH = 240
        private const val TIMER_LIMIT = 12
        private const val EXP_WRAP = 100
        private const val EXP_MAX = 99
        private const val NUMBER_TILE_REF = 0x5225
        private const val NUMBER_BLANK_REF = 0x522F
    }
}
