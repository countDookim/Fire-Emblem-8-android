package fe8.game.mapanim

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmSt
import fe8.game.bm.ManimSt
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The announcement part of the map level-up (`ProcScr_ManimLevelUp`, `src/mapanim_lvup.c`): the
 * window set-up, the BGM dip, and the level-up text sprite that stays for 70 frames.
 *
 * [script] holds the commands up to the music restore. The rest of `ProcScr_ManimLevelUp` (the
 * stat panel, MC-18d) is the stand-in [Hooks.restOfLevelUp], and the end callback
 * `ManimLevelUp_Clear` is the stand-in [Hooks.clear].
 *
 * @param engine the proc engine.
 * @param hardware the LCD buffer, OBJ VRAM and palette buffer.
 * @param rom the ROM (text graphics and palette).
 * @param sound the BGM volume change and `PlaySoundEffect`.
 * @param bm `gBmSt` (the camera).
 * @param manim `gManimSt` (the actors' units).
 * @param apProcs the sprite animation procs (`APProc_Create`, `APProc_DeleteAll`).
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
    private val hooks: Hooks,
) {
    /** What the announcement calls outside the ported functions. */
    public class Hooks {
        /** Stand-in (MC-18d) for `ManimLevelUp_Clear` (`ResetDialogueScreen`), the end callback. */
        public var clear: (Proc) -> Unit = {}

        /** Stand-in for the commands after `ManimLevelUp_RestoreBgm` (MC-18d). */
        public var restOfLevelUp: (Proc) -> Unit = {}
    }

    /** Fields of `struct ManimLevelUpProc` that the announcement uses. */
    public class State {
        /** `actor_id` (s16, 0x2E): index into `gManimSt.actor`. */
        public var actorId: Int = 0
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

    /** `ProcScr_ManimLevelUp` as far as the announcement runs it (see the class comment). */
    public val script: ProcScript = ProcScript(
        "ProcScr_ManimLevelUp",
        listOf(
            setEndCb(ProcFunc { hooks.clear(it) }),
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
            call(ProcFunc { hooks.restOfLevelUp(it) }),
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

        // Img_ManimLevelUpText, Pal_ManimLevelUp and gUnknown_089A5A6C in FE8U.
        const val IMG_TEXT = 0x089AC794
        const val PAL_LEVEL_UP = 0x089AC9A8
        const val AP_TEXT = 0x089A5A6C
    }
}
