package fe8.game.bm

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.startChild
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.ColorFade
import gbafe.render.HBlankHandler

/**
 * The game over screen of `src/gameoverbgfx.c`: its set-up (`GameOverScreen_Init`), the fog
 * background scroll (`ProcScr_GameOverScreen_RandomScroll`) and its start. The fades, the idle
 * wait and the teardown (PC-8b) and `GameOverScreenHBlank` (PC-8h) are behind [Hooks]. Not wired
 * into the game loop (PC-8).
 *
 * @param engine the proc engine the screen runs on.
 * @param hardware the BG, palette and LCD state the screen draws into.
 * @param colorFade the palette fade the screen sets up.
 * @param rom the ROM image and palette data are read from.
 * @param hooks the parts the screen leaves to other systems.
 */
public class GameOverScreen(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val colorFade: ColorFade,
    private val rom: RomView,
    private val hooks: Hooks = object : Hooks {},
) {
    /** What the screen leaves to other systems (stand-ins until they are ported). */
    public interface Hooks {
        /** `BMapDispSuspend`. */
        public fun bMapDispSuspend() {}

        /** `StartBgm(SONG_GAME_OVER, 0)`. */
        public fun startGameOverBgm() {}

        /** `EndAllMus`. */
        public fun endAllMus() {}

        /** `GameOverScreenHBlank` (PC-8h); a no-op by default. */
        public val hblankHandler: HBlankHandler get() = NO_HBLANK

        /** `GameOverScreen_LoopFadeIn` (PC-8b): the repeat callback; never breaks by default. */
        public fun loopFadeIn(proc: Proc) {}

        /** `GameOverScreen_End` (PC-8b): the end callback. */
        public fun end(proc: Proc) {}
    }

    /** `struct ProcGameOverScreen` past `PROC_HEADER`. */
    public class State {
        /** `counter1` (s16, 0x4C): the fade counter, 21 after the set-up. */
        public var counter1: Int = 0

        /** `counter2` (s16, 0x4E): the idle counter (PC-8b). */
        public var counter2: Int = 0
    }

    /** `struct ProcGameOverScroll` past `PROC_HEADER`. */
    public class ScrollState {
        /** `bg2_xdiff` (0x34). */
        public var bg2Xdiff: Int = 0

        /** `bg2_ydiff` (0x38). */
        public var bg2Ydiff: Int = 0

        /** `bg3_xdiff` (0x3C). */
        public var bg3Xdiff: Int = 0

        /** `bg3_ydiff` (0x40). */
        public var bg3Ydiff: Int = 0

        /** `bg2_xpos` (s16, 0x64). */
        public var bg2Xpos: Int = 0

        /** `bg2_ypos` (s16, 0x66). */
        public var bg2Ypos: Int = 0

        /** `bg3_xpos` (s16, 0x68). */
        public var bg3Xpos: Int = 0

        /** `bg3_ypos` (s16, 0x6A). */
        public var bg3Ypos: Int = 0
    }

    /**
     * `ProcScr_GameOverScreen_RandomScroll`: sets the scroll speeds and start positions, then
     * scrolls BG2 and BG3 every frame.
     *
     * Ported from src/gameoverbgfx.c: ProcScr_GameOverScreen_RandomScroll
     */
    public val scrollScript: ProcScript = ProcScript(
        "ProcScr_GameOverScreen_RandomScroll",
        listOf(
            call(ProcFunc { randomScrollInit(it.state as ScrollState) }),
            repeat(ProcFunc { randomScrollLoop(it.state as ScrollState) }),
            end(),
        ),
        ::ScrollState,
    )

    /**
     * `ProcScr_GameOverScreen`: ends through [Hooks.end], sets the screen up, ends the MUs,
     * starts the scroll and runs [Hooks.loopFadeIn].
     *
     * Transcribed from src/gameoverbgfx.c: ProcScr_GameOverScreen (not verified: only the
     * commands up to `GameOverScreen_LoopFadeIn` are written; the rest, from
     * `GameOverScreen_BeginIdle` to the end, is PC-8b and stands in as a block)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GameOverScreen",
        listOf(
            setEndCb(ProcFunc { hooks.end(it) }),
            call(ProcFunc { init(it.state as State) }),
            call(ProcFunc { hooks.endAllMus() }),
            startChild { scrollScript },
            repeat(ProcFunc { hooks.loopFadeIn(it) }),
            block(),
            end(),
        ),
        ::State,
    )

    /**
     * Starts the game over screen: blocking [parent], or on proc tree 3 without a parent.
     *
     * Ported from src/gameoverbgfx.c: StartGameOverScreen
     */
    public fun startGameOverScreen(parent: Proc?) {
        if (parent != null) engine.startBlocking(script, parent) else engine.start(script, 3)
    }

    /**
     * Sets the scroll speeds and the start positions (BG2 and BG3 are scrolled apart).
     *
     * Ported from src/gameoverbgfx.c: GameOverScreen_RandomScroll_Init
     */
    public fun randomScrollInit(state: ScrollState) {
        state.bg2Xdiff = +46
        state.bg2Ydiff = -90
        state.bg3Xdiff = -16
        state.bg3Ydiff = -53

        state.bg2Xpos = 1234
        state.bg2Ypos = 5678
        state.bg3Xpos = 6346
        state.bg3Ypos = 8536
    }

    /**
     * Moves BG2 and BG3 by their speeds and sets their scroll to the position in 1/256 pixels,
     * negated.
     *
     * Ported from src/gameoverbgfx.c: GameOverScreen_RandomScroll_Loop
     */
    public fun randomScrollLoop(state: ScrollState) {
        state.bg2Xpos = (state.bg2Xpos + state.bg2Xdiff).toShort().toInt()
        state.bg2Ypos = (state.bg2Ypos + state.bg2Ydiff).toShort().toInt()
        state.bg3Xpos = (state.bg3Xpos + state.bg3Xdiff).toShort().toInt()
        state.bg3Ypos = (state.bg3Ypos + state.bg3Ydiff).toShort().toInt()

        hardware.lcd.setBgPosition(2, -state.bg2Xpos shr 8, -state.bg2Ypos shr 8)
        hardware.lcd.setBgPosition(3, -state.bg3Xpos shr 8, -state.bg3Ypos shr 8)
    }

    /**
     * Sets the screen up: suspends the map display, starts the game over song, gives the BGs
     * priorities 0-3 (BG2 and BG3 use tile data at 0), loads the text image to BG tile 0x80 and
     * the fog image to BG tile 0x100 with their palettes (banks 4 and 0), draws the text
     * tilemap on BG0 at tile (7, 9) and the fog on BG2 and BG3, sets the alpha blend (BG2
     * against BG3, 14 and 14), starts the palette fade from black on banks 0 and 4 and runs 10
     * fade steps of it.
     *
     * Ported from src/gameoverbgfx.c: GameOverScreen_Init
     */
    public fun init(state: State) {
        val lcd = hardware.lcd
        hooks.bMapDispSuspend()

        hooks.startGameOverBgm()

        val priority = lcd.bgCnt
        for (bg in 0..3) priority[bg] = (priority[bg] and 3.inv()) or bg

        hardware.setTileDataOffset(2, 0)
        hardware.setTileDataOffset(3, 0)

        rom.decompressInto(IMG_GAME_OVER_TEXT, hardware.vram, BGCHR_GAMEOVER_TEXT * 0x20)
        rom.pal(PAL_GAME_OVER_TEXT_1, 16).copyInto(hardware.palette, BGPAL_GAMEOVER_4 * 16)
        rom.decompressInto(IMG_CHAPTER_INTRO_FOG, hardware.vram, BGCHR_BMFX_IMG * 0x20)
        rom.pal(PAL_GAME_OVER_TEXT_2, 16).copyInto(hardware.palette, BGPAL_GAMEOVER_TEXT * 16)

        lcd.setBgPosition(0, 0, 0)
        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)
        hardware.enableBgSyncByMask(3)

        // The text tilemap is an uncompressed TSA: width - 1, height - 1, then the entries.
        val tsaSize = (rom.u8(TSA_GAME_OVER_FX) + 1) * (rom.u8(TSA_GAME_OVER_FX + 1) + 1) * 2 + 2
        val tsa = ByteArray(tsaSize) { rom.u8(TSA_GAME_OVER_FX + it).toByte() }
        BgTilemap.applyTsa(hardware.mapBuffer(0), 9 * 32 + 7, tsa, 0, BGCHR_GAMEOVER_TEXT)

        ScreenFogEffect.putScreenFogEffectOverlayed(hardware.mapBuffer(2))
        ScreenFogEffect.putScreenFogEffect(hardware.mapBuffer(3))

        hardware.enableBgSyncByMask(BG2_SYNC_BIT or BG3_SYNC_BIT)

        hardware.hblank.setPrimary(hooks.hblankHandler)

        lcd.setBlendConfig(1, 14, 14, 0)

        lcd.setBlendTargetA(0, 0, 1, 0, 0)
        lcd.setBlendTargetB(0, 0, 0, 1, 0)

        colorFade.maybeResetSomePal()
        colorFade.maybeSmoothChangeSomePal(BGPAL_GAMEOVER_TEXT * 16, BGPAL_GAMEOVER_TEXT, 1, +1)
        colorFade.maybeSmoothChangeSomePal(BGPAL_GAMEOVER_4 * 16, BGPAL_GAMEOVER_4, 1, +1)

        state.counter1 = 21

        for (i in 0 until 10) colorFade.tick()

        hardware.enablePaletteSync()
    }

    /** Constants of the game over screen. */
    public companion object {
        /** `BGCHR_GAMEOVER_TEXT`. */
        public const val BGCHR_GAMEOVER_TEXT: Int = 0x80

        /** `BGCHR_BMFX_IMG`. */
        public const val BGCHR_BMFX_IMG: Int = 0x100

        /** `BGPAL_GAMEOVER_TEXT`. */
        public const val BGPAL_GAMEOVER_TEXT: Int = 0

        /** `BGPAL_GAMEOVER_4`. */
        public const val BGPAL_GAMEOVER_4: Int = 4

        /** `BG2_SYNC_BIT`. */
        public const val BG2_SYNC_BIT: Int = 4

        /** `BG3_SYNC_BIT`. */
        public const val BG3_SYNC_BIT: Int = 8

        /** `Img_ChapterIntroFog`. */
        public const val IMG_CHAPTER_INTRO_FOG: Int = 0x08A07DD8

        /** `Img_GameOverText`. */
        public const val IMG_GAME_OVER_TEXT: Int = 0x08A0AB0C

        /** `Pal_GameOverText2`. */
        public const val PAL_GAME_OVER_TEXT_2: Int = 0x08A0AE44

        /** `Pal_GameOverText1`. */
        public const val PAL_GAME_OVER_TEXT_1: Int = 0x08A0AE64

        /** `Tsa_GameOverFx`. */
        public const val TSA_GAME_OVER_FX: Int = 0x08A0AE84

        private val NO_HBLANK = HBlankHandler { _, _ -> }
    }
}
