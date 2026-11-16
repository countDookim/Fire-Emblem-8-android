package fe8.game.mapanim

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmSt
import fe8.game.bm.ManimSt
import fe8.game.bm.MapLevelUpFrame
import fe8.game.bm.MapLevelUpStats
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.text.TextSystem

/**
 * The map level-up (`ProcScr_ManimLevelUp`, `src/mapanim_lvup.c`): the window set-up, the BGM
 * dip, the level-up text sprite that stays for 70 frames, then the stat panel that scrolls in,
 * shows the stat gains one by one and scrolls out.
 *
 * @param engine the proc engine.
 * @param hardware the LCD buffer, BG buffers, OBJ VRAM and palette buffer.
 * @param rom the ROM (text graphics and palette, character data).
 * @param sound the BGM volume change and `PlaySoundEffect`.
 * @param bm `gBmSt` (the camera).
 * @param manim `gManimSt` (the actors' units).
 * @param apProcs the sprite animation procs (`APProc_Create`, `APProc_DeleteAll`).
 * @param text the text system (`ResetTextFont`).
 * @param frame the panel frame (`PutManimLevelUpFrame`).
 * @param stats the panel stat rows (`PutManimLevelUpStat`, `GetManimLevelUpStatGain`).
 * @param labels the stat gain labels.
 * @param hooks the calls outside the ported functions.
 */
public class MapLevelUpAnnouncement(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val sound: SoundWrapper,
    private val bm: BmSt,
    private val manim: ManimSt,
    private val apProcs: ApProcs,
    private val text: TextSystem,
    private val frame: MapLevelUpFrame,
    private val stats: MapLevelUpStats,
    private val labels: MapLevelUpGainLabels,
    private val hooks: Hooks,
) {
    /** What the level-up calls outside the ported functions. */
    public class Hooks {
        /**
         * Starts a portrait face (`StartFace(slot, portraitId, x, y, disp)` in src/face.c).
         */
        public var startFace: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> }

        /** Writes to the face's vertical position (`gFaces[0]->yPos` in src/mapanim_lvup.c). */
        public var setFaceY: (Int) -> Unit = {}

        /** Resets the dialogue screen and faces (`ResetDialogueScreen` in src/eventscr2.c). */
        public var resetDialogueScreen: () -> Unit = {}
    }

    /** Fields of `struct ManimLevelUpProc` that the announcement uses. */
    public class State {
        /** `actor_id` (s16, 0x2E): index into `gManimSt.actor`. */
        public var actorId: Int = 0

        /** `next_stat_num` (u8, 0x30): the next stat row to check for a gain. */
        public var nextStatNum: Int = 0

        /** `clock` (u8, 0x31): frames to wait before the next stat row. */
        public var clock: Int = 0

        /** `y_scroll_offset` (s16, 0x32): the BG0 and BG1 vertical scroll. */
        public var yScrollOffset: Int = 0
    }

    /**
     * Starts the level-up proc for actor [actorId], blocking [parent]; returns it (null when it
     * already ended while starting).
     *
     * Ported from src/mapanim_lvup.c: StartManimLevelUp
     */
    public fun startManimLevelUp(actorId: Int, parent: Proc): Proc? {
        val proc = engine.startBlocking(script, parent)
        (proc?.state as State?)?.actorId = actorId
        return proc
    }

    /**
     * Enables WIN0 over the top 48 rows: BG1..BG3 and the OBJ layer show inside it, and every
     * layer outside.
     *
     * Ported from src/mapanim_lvup.c: InitManimLevelUpWindow
     */
    public fun initManimLevelUpWindow() {
        val lcd = hardware.lcd
        lcd.setWinEnable(1, 0, 0)
        lcd.setWin0Box(0, 0, 240, 48)
        lcd.setWin0Layers(0, 0, 1, 1, 1)
        lcd.setWOutLayers(1, 1, 1, 1, 1)
    }

    /**
     * Dims the BGM from full (0x100) to half (0x80) volume over 16 frames, blocking [proc].
     *
     * Transcribed from src/mapanim_lvup.c: ManimLevelUp_DimBgm (not verified: the capture does
     * not record audio)
     */
    public fun dimBgm(proc: Proc) {
        sound.startBgmVolumeChange(0x100, 0x80, 0x10, proc)
    }

    /**
     * Loads the text graphics and palettes, places the text sprite at the actor's unit (kept
     * inside the screen) and plays the level-up sound.
     *
     * The sprite position and OBJ VRAM/palette loads are verified against
     * `ram_trace_prologue_levelup`; the sound is not (the capture does not record audio). The
     * clamps for a unit near the left or right screen edge are not covered.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_StartLevelUpText
     */
    public fun startLevelUpText(proc: Proc) {
        val actor = (proc.state as State).actorId
        rom.decompressInto(IMG_TEXT, hardware.vram, OBJ_VRAM0 + 0x1C0 * CHR_SIZE)
        val palette = rom.pal(PAL_LEVEL_UP, 3 * 16)
        palette.copyInto(hardware.palette, (0x10 + 3) * 16)
        hardware.enablePaletteSync()

        val bytes = checkNotNull(manim.unitBytes[actor])
        val offset = manim.unitOffset[actor]
        val tileX = bytes[offset + OFF_X_POS].toInt() - (bm.cameraX shr 4)
        val tileY = bytes[offset + OFF_Y_POS].toInt() - (bm.cameraY shr 4)
        val ix = tileX * 2
        var x = ix * 8 + 16
        val iy = tileY * 2
        var y = iy * 8 - 8
        if (tileY * 2 < 4) y += 32
        if (tileX * 2 < 4) x = 48
        if (tileX * 2 > 25) x = 208
        apProcs.create(AP_TEXT, x, y, 0x1C0 + (0x3 shl 12), 0, 2)
        sound.playSoundEffect(SONG_5B)
    }

    /**
     * Ends every sprite animation proc, which removes the level-up text.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_EndLevelUpText
     */
    public fun endLevelUpText() {
        apProcs.deleteAll()
    }

    /**
     * Restores the BGM from half (0x80) to full (0x100) volume over 16 frames, blocking [proc].
     *
     * Transcribed from src/mapanim_lvup.c: ManimLevelUp_RestoreBgm (not verified: the capture
     * does not record audio)
     */
    public fun restoreBgm(proc: Proc) {
        sound.startBgmVolumeChange(0x80, 0x100, 0x10, proc)
    }

    /**
     * Ends the window, redraws the panel on BG0 and BG1, starts the portrait and the gain
     * labels, and sets the panel's BG layers, scroll and blend state.
     *
     * Verified against `ram_trace_prologue_levelup` (BG0 and BG1 tilemaps, scroll, priorities,
     * window and blend registers, and the portrait position, through the hooks).
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_InitMainScreen
     */
    public fun initMainScreen(proc: Proc) {
        val state = proc.state as State
        text.resetTextFont()
        hardware.fill(hardware.tilemaps[0], 0)
        frame.putManimLevelUpFrame(state.actorId, 1, 1)
        for (i in 0 until 9) stats.putManimLevelUpStat(state.actorId, 1, 1, i, false)
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)

        state.nextStatNum = 0
        state.clock = 0
        state.yScrollOffset = -144

        val lcd = hardware.lcd
        for ((bg, priority) in intArrayOf(0, 1, 1, 2).withIndex()) {
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or priority
        }
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        lcd.setWinEnable(0, 0, 0)
        lcd.setBgPosition(0, 0, state.yScrollOffset)
        lcd.setBgPosition(1, 0, state.yScrollOffset)

        val character = manim.unitBytes[state.actorId]!!.let {
            val at = manim.unitOffset[state.actorId]
            (it[at].toInt() and 0xFF) or ((it[at + 1].toInt() and 0xFF) shl 8) or
                ((it[at + 2].toInt() and 0xFF) shl 16) or ((it[at + 3].toInt() and 0xFF) shl 24)
        }
        hooks.startFace(0, rom.u16(character + 6), 184, 32 - state.yScrollOffset, 0x1042)
        hooks.setFaceY(32 - state.yScrollOffset)

        labels.start(0x200, 3, 1, proc)
    }

    /**
     * Moves the panel 8 pixels down; breaks the repeat once it is at -48 or below.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_ScrollIn
     */
    public fun scrollIn(proc: Proc) {
        val state = proc.state as State
        state.yScrollOffset += 8
        scrollTo(state)
        if (state.yScrollOffset >= -48) engine.breakProc(proc)
    }

    /**
     * Moves the panel 8 pixels up; breaks the repeat once it is at -144 or above.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_ScrollOut
     */
    public fun scrollOut(proc: Proc) {
        val state = proc.state as State
        state.yScrollOffset -= 8
        scrollTo(state)
        if (state.yScrollOffset <= -144) engine.breakProc(proc)
    }

    private fun scrollTo(state: State) {
        hardware.lcd.setBgPosition(0, 0, state.yScrollOffset)
        hardware.lcd.setBgPosition(1, 0, state.yScrollOffset)
        hooks.setFaceY(32 - state.yScrollOffset)
    }

    /**
     * Every 21 frames shows the next stat row with a gain: redraws it with the gain added and
     * starts its label animation; breaks the repeat when no row is left.
     *
     * The sound effects (SONG_2CD for the level, SONG_76 for the others) are not verified: the
     * capture does not record audio.
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_PutStatGainLabels
     */
    public fun putStatGainLabels(proc: Proc) {
        val state = proc.state as State
        if (state.clock != 0) {
            state.clock--
            return
        }
        var stat = state.nextStatNum
        while (stat < 9) {
            if (stats.getManimLevelUpStatGain(state.actorId, stat) != 0) break
            stat++
        }
        if (stat >= 9) {
            engine.breakProc(proc)
            return
        }
        stats.putManimLevelUpStat(state.actorId, 1, 1, stat, true)
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)
        val label = LABEL_INFO + stat * 12
        labels.startAnim(
            rom.u8(label) * 8 + 62,
            rom.u8(label + 1) * 8 + 23 - state.yScrollOffset,
            stat,
            stats.getManimLevelUpStatGain(state.actorId, stat),
        )
        sound.playSoundEffect(if (stat == 0) SONG_2CD else SONG_76)
        state.nextStatNum = stat + 1
        state.clock = 20
    }

    /**
     * Disables the windows.
     *
     * Transcribed from src/mapanim_lvup.c: ClearManimLevelUpWindow (not verified: the windows
     * are already off when it runs, so no register in the capture depends on it)
     */
    public fun clearManimLevelUpWindow() {
        hardware.lcd.setWinEnable(0, 0, 0)
    }

    /**
     * The proc's end callback: resets the dialogue screen (the portrait).
     *
     * Ported from src/mapanim_lvup.c: ManimLevelUp_Clear
     */
    public fun clear() {
        hooks.resetDialogueScreen()
    }

    /** `ProcScr_ManimLevelUp`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_ManimLevelUp",
        listOf(
            setEndCb(ProcFunc { clear() }),
            sleep(1),
            call(ProcFunc { initManimLevelUpWindow() }),
            call(ProcFunc { dimBgm(it) }),
            yield(),
            call(ProcFunc { startLevelUpText(it) }),
            sleep(70),
            call(ProcFunc { endLevelUpText() }),
            sleep(1),
            call(ProcFunc { restoreBgm(it) }),
            yield(),
            // Hardware frame overrun investigation (MC-18d-face):
            // In ram_trace_prologue_levelup, r06519 was captured at VBlank while the main loop
            // had overran its frame (gBmSt.main_loop_ended == 0). ManimLevelUp_InitMainScreen
            // executes multiple heavy operations in one frame: three LZ77 decompressions
            // (Img_LevelUpBoxFrame, Tsa_LevelUpBoxFrame, Img_ManimLevelUpStatGain), font reset,
            // string renders for class name and 9 stat labels, and StartFace / labels setup.
            // On GBA hardware, this exceeds the ~280,000 cycle frame budget, causing VBlank to
            // interrupt OnMain right after ManimLevelUp_InitMainScreen finished. At that VBlank,
            // the proc cursor is at index 12 (PROC_YIELD). Once OnMain resumes after VBlank,
            // PROC_YIELD executes, yielding until the next VBlank (f6520, cursor 13).
            // In this cooperative ProcEngine (which does not simulate CPU cycles), call2 returning
            // false followed by yield() models this 1-frame lag precisely so differential tests
            // match the trace step-by-step.
            call2(
                ProcBoolFunc {
                    initMainScreen(it)
                    false
                },
            ),
            yield(),
            repeat(ProcFunc { scrollIn(it) }),
            sleep(30),
            repeat(ProcFunc { putStatGainLabels(it) }),
            sleep(60),
            call(ProcFunc { labels.end() }),
            sleep(1),
            repeat(ProcFunc { scrollOut(it) }),
            call(ProcFunc { clearManimLevelUpWindow() }),
            call(ProcFunc { hooks.resetDialogueScreen() }),
            sleep(4),
            end(),
        ),
        { State() },
    )

    private companion object {
        const val OBJ_VRAM0 = 0x10000
        const val CHR_SIZE = 0x20
        const val OFF_X_POS = 0x10
        const val OFF_Y_POS = 0x11
        const val SONG_5B = 0x5B
        const val SONG_2CD = 0x2CD
        const val SONG_76 = 0x76
        const val BG0_SYNC_BIT = 1
        const val LABEL_INFO = 0x089A3ED4 // gManimLevelUpLabelInfoList

        // Img_ManimLevelUpText, Pal_ManimLevelUp and gUnknown_089A5A6C in FE8U.
        const val IMG_TEXT = 0x089AC794
        const val PAL_LEVEL_UP = 0x089AC9A8
        const val AP_TEXT = 0x089A5A6C
    }
}
