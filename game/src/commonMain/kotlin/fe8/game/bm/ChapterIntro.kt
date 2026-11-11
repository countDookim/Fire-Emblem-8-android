package fe8.game.bm

import fe8.game.RomView
import fe8.game.SoundWrapper
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChild
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.ColorFade
import gbafe.render.LcdControl
import gbafe.render.OamBuffer

/**
 * The chapter intro (`src/chapterintrofx.c`): the start of `gProcScr_ChapterIntro` up to the
 * skip to label 1, as run by `ram_trace_newgame_bm` frames 9322-9420. The rest of the script
 * (light burst, title, light explosion, fog, both fades to the map) is named stand-ins in
 * [script], each tagged with the task that fills it.
 *
 * Not wired: `BMapMain.Hooks.chapterIntro` still holds its own stand-in (D11-ci5).
 */
public class ChapterIntro(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val colorFade: ColorFade,
    private val oam: OamBuffer,
    private val rom: RomView,
    private val sound: SoundWrapper,
    private val hooks: Hooks,
) {
    /** External pieces of the intro. */
    public class Hooks(
        /** `gKeyStatusPtr->newKeys`. */
        public val newKeys: () -> Int,
        /** `BMapDispSuspend` (`BattleMapStart.bMapDispSuspend`). */
        public val bMapDispSuspend: () -> Unit,
        /** `BMapDispResume` (`BattleMapStart.bMapDispResume`). */
        public val bMapDispResume: () -> Unit,
    )

    /** `struct ChapterIntroFxProc` fields (0x2C..0x68) shared by the intro and its children. */
    public class State {
        /** `xLight` (0x2C). */
        public var xLight: Int = 0

        /** `yLight` (0x30). */
        public var yLight: Int = 0

        /** `timer` (0x4C, s16). */
        public var timer: Int = 0

        /** `unk_4E` (s16). */
        public var unk4E: Int = 0

        /** `skipTarget` (0x50, s16). */
        public var skipTarget: Int = 0

        /** `isSkipping` (0x52, u16). */
        public var isSkipping: Int = 0

        /** `unk_64` (s16). */
        public var unk64: Int = 0

        /** `unk_66` (s16). */
        public var unk66: Int = 0

        /** `unk_68` (s16). */
        public var unk68: Int = 0
    }

    /**
     * `REG_BG3HOFS`, written directly by [bg1And3ScrollLoop] (it is not part of
     * `gLCDControlBuffer`).
     */
    public var regBg3Hofs: Int = 0
        private set

    /** A named no-op for a script command filled by a later task; [name] documents the target. */
    @Suppress("UNUSED_PARAMETER")
    private fun standInFunc(name: String) = ProcFunc { }

    private fun standInScript(name: String) = ProcScript(name, listOf(end()))

    /**
     * `ProcScr_ChapterIntro_Bg1And3Scroll`. Started by [loadCommonGameBg], not by the intro script.
     *
     * Transcribed from src/chapterintrofx.c: ProcScr_ChapterIntro_Bg1And3Scroll (not verified:
     *   the script layout is only exercised through the differential test of its two callbacks)
     */
    public val bg1And3ScrollScript: ProcScript = ProcScript(
        "ProcScr_ChapterIntro_Bg1And3Scroll",
        listOf(
            call(ProcFunc { bg1And3ScrollInit(it.state as State) }),
            repeat(ProcFunc { bg1And3ScrollLoop(it.state as State) }),
            end(),
        ),
        newState = { State() },
    )

    /**
     * `ProcScr_ChapterIntro_KeyListen`.
     *
     * Transcribed from src/chapterintrofx.c: ProcScr_ChapterIntro_KeyListen (not verified:
     *   the script layout is only exercised through the differential test of its two callbacks)
     */
    public val keyListenScript: ProcScript = ProcScript(
        "ProcScr_ChapterIntro_KeyListen",
        listOf(
            call(ProcFunc { keyListenInit(it) }),
            repeat(ProcFunc { keyListenLoop(it) }),
            end(),
        ),
        newState = { State() },
    )

    /** `ProcScr_ChapterIntro_LightBurst`: stand-in until D11-ci2. */
    private val lightBurstScript = standInScript("ProcScr_ChapterIntro_LightBurst")

    /** `ProcScr_ChapterIntro_Bg2Scroll`: stand-in until D11-ci3. */
    private val bg2ScrollScript = standInScript("ProcScr_ChapterIntro_Bg2Scroll")

    /** `ProcScr_ChapterIntro_PutDecalSprite`: stand-in until D11-ci3. */
    private val putDecalScript = standInScript("ProcScr_ChapterIntro_PutDecalSprite")

    /**
     * `gProcScr_ChapterIntro`, command for command. Commands after the twin lights are stand-ins:
     * D11-ci2 (`InitBLDCNT`, light burst, title, light explosion, `SetBG_802009C`), D11-ci3
     * (Bg2Scroll, decals, fog, timer, `8020A40`/`8020A8C`, `BeginFadeToMap`, `LoopFadeToMap`),
     * D11-ci4 (label 1: `BeginFadeOut`, `LoopFadeOut`, `BeginFastFadeToMap`,
     * `LoopFastFadeToMap`; and `InitCameraYPos`, `InitMapDisplay`, `End`, which both paths run).
     *
     * Transcribed from src/chapterintrofx.c: gProcScr_ChapterIntro (not verified: the window
     *   runs commands 0-6 and the jump to label 1 only)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_ChapterIntro",
        listOf(
            call(ProcFunc { hooks.bMapDispSuspend() }),
            call(ProcFunc { init(it) }),
            sleep(60),
            startChild { keyListenScript },
            callArg(ProcArgFunc { arg, proc -> setSkipTarget(arg, proc); true }, 1),
            call(ProcFunc { twinLightsInitAndPlaySe(it) }),
            repeat(ProcFunc { twinLightsLoop(it) }),
            call(standInFunc("ChapterIntro_InitBLDCNT")),
            startChild { lightBurstScript },
            call(standInFunc("ChapterIntro_DrawChapterTitle")),
            sleep(1),
            call(standInFunc("ChapterIntro_LightExplosion_Init")),
            repeat(standInFunc("ChapterIntro_LightExplosion_Loop")),
            call(standInFunc("ChapterIntro_SetBG_802009C")),
            startChild { bg2ScrollScript },
            startChild { putDecalScript },
            call(standInFunc("ChapterIntro_InitFogGfx")),
            repeat(standInFunc("ChapterIntro_BlendFogAlpha_Loop")),
            callArg(ProcArgFunc { _, _ -> true }, 160),
            repeat(standInFunc("ChapterIntro_TickTimerMaybe")),
            call(standInFunc("ChapterIntro_8020A40")),
            repeat(standInFunc("ChapterIntro_8020A8C")),
            endEach { bg2ScrollScript },
            endEach { bg1And3ScrollScript },
            endEach { putDecalScript },
            callArg(ProcArgFunc { arg, proc -> setSkipTarget(arg, proc); true }, 0),
            call(standInFunc("ChapterIntro_InitCameraYPos")),
            call(ProcFunc { hooks.bMapDispResume() }),
            call(standInFunc("ChapterIntro_InitMapDisplay")),
            call(standInFunc("ChapterIntro_BeginFadeToMap")),
            repeat(standInFunc("ChapterIntro_LoopFadeToMap")),
            sleep(30),
            goto(99),
            label(1),
            call(standInFunc("ChapterIntro_BeginFadeOut")),
            repeat(standInFunc("ChapterIntro_LoopFadeOut")),
            sleep(60),
            call(standInFunc("ChapterIntro_InitCameraYPos")),
            call(ProcFunc { hooks.bMapDispResume() }),
            call(standInFunc("ChapterIntro_InitMapDisplay")),
            call(standInFunc("ChapterIntro_BeginFastFadeToMap")),
            repeat(standInFunc("ChapterIntro_LoopFastFadeToMap")),
            label(99),
            call(standInFunc("ChapterIntro_End")),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_KeyListen_Init
     */
    private fun keyListenInit(proc: Proc) {
        val state = proc.state as State
        (proc.parent!!.state as State).skipTarget = 0
        state.skipTarget = 0
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_KeyListen_Loop
     */
    private fun keyListenLoop(proc: Proc) {
        val state = proc.state as State
        val parent = proc.parent!!
        val parentState = parent.state as State
        if (hooks.newKeys() and (A_BUTTON or B_BUTTON or START_BUTTON) != 0) {
            if (parentState.isSkipping != 0) {
                state.skipTarget = 1
            } else {
                parentState.isSkipping = 1
            }
        }
        if (state.skipTarget != 0) {
            if (parentState.skipTarget != 0) {
                engine.goto(parent, parentState.skipTarget)
                engine.end(proc)
            }
        }
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_Bg1And3Scroll_Init
     */
    private fun bg1And3ScrollInit(state: State) {
        state.timer = 0
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_Bg1And3Scroll_Loop
     */
    private fun bg1And3ScrollLoop(state: State) {
        state.timer = (state.timer + 1).toShort().toInt()
        hardware.lcd.setBgPosition(1, state.timer / 2, 0)
        regBg3Hofs = state.timer / 2
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_SetSkipTarget
     */
    private fun setSkipTarget(targetLabel: Int, proc: Proc) {
        (proc.state as State).skipTarget = targetLabel.toShort().toInt()
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_Init
     *
     * `Decompress` and `ApplyPalettes` read the ROM image and palette data directly.
     */
    private fun init(proc: Proc) {
        val lcd = hardware.lcd
        hardware.setupBackgrounds(null)
        lcd.dispcnt = (lcd.dispcnt and 7.inv()) or 1
        setPriorities(0, 2, 1, 2)
        lcd.setDispEnable(0, 0, 0, 0, 1)
        lcd.setBgPosition(0, 0, 0)
        lcd.setBgPosition(1, 0, 0)
        lcd.setBgPosition(2, 0, 0)
        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.setTileDataOffset(2, 0x8000)
        lcd.setWinEnable(1, 0, 0)
        lcd.setWin0Layers(1, 1, 1, 1, 1)
        lcd.setWOutLayers(1, 1, 1, 1, 1)
        lcd.setWincntBit(LcdControl.WIN0_ENABLE_BLEND, true)
        lcd.setWincntBit(LcdControl.WOUT_ENABLE_BLEND, true)
        lcd.setWin0Box(0, 0, 0, 0)
        lcd.setBlendConfig(0, 0, 0, 0)
        colorFade.step.fill(0)
        sub80017B4(0, 2, 64, -1)
        colorFade.tick()
        hardware.enablePaletteSync()
        // screenSize = 1, areaOverflowMode = 1
        lcd.bgCnt[2] = (lcd.bgCnt[2] and 0xE000.inv()) or 0x4000 or 0x2000
        rom.decompressInto(IMG_LENS_FLARE, hardware.vram, 0x400 * 0x20)
        rom.pal(PAL_LENS_FLARE, 3 * 16).copyInto(hardware.palette, 0)
        BgTilemap.applyByteTsa(hardware.mapBuffer(2), lensFlareTsa(), 0, 0, 5)
        rom.decompressInto(IMG_SPRITES, hardware.vram, OBJ_VRAM + 0x200 * 0x20)
        rom.pal(PAL_SPRITES, 16).copyInto(hardware.palette, 18 * 16)
        loadCommonGameBg(proc, 14)
        hardware.enableBgSyncByMask(2 or 4)
        (proc.state as State).isSkipping = 0
    }

    private fun setPriorities(bg0: Int, bg1: Int, bg2: Int, bg3: Int) {
        val bgCnt = hardware.lcd.bgCnt
        val priorities = intArrayOf(bg0, bg1, bg2, bg3)
        for (bg in 0..3) bgCnt[bg] = (bgCnt[bg] and 3.inv()) or priorities[bg]
    }

    private fun lensFlareTsa(): ByteArray {
        val size = (rom.u8(TSA_LENS_FLARE) + 1) * (rom.u8(TSA_LENS_FLARE + 1) + 1) * 2 + 2
        return ByteArray(size) { rom.u8(TSA_LENS_FLARE + it).toByte() }
    }

    /**
     * Ported from src/hardware.c: sub_80017B4
     *
     * Needed by [init]; checked through the `gFadeComponents` and `gFadeComponentStep` dumps.
     */
    private fun sub80017B4(a: Int, b: Int, c: Int, d: Int) {
        var destOffset = a * 16
        var src = a * 16
        for (i in 0 until b) {
            colorFade.step[a + i] = d.toByte()
            for (j in 0 until 16) {
                val color = hardware.palette[src].toInt()
                colorFade.components[destOffset++] = ((color and 0x1F) + c).toByte()
                colorFade.components[destOffset++] = (((color shr 5) and 0x1F) + c).toByte()
                colorFade.components[destOffset++] = (((color shr 10) and 0x1F) + c).toByte()
                src++
            }
        }
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_8020010
     *
     * Loads the common game background screen into BG1's tile data and tilemap and starts
     * [bg1And3ScrollScript] under [proc].
     */
    private fun loadCommonGameBg(proc: Proc, palId: Int) {
        val map = hardware.mapBuffer(1)
        rom.pal(PAL_COMM_GAME_BG, 2 * 16).copyInto(hardware.palette, palId * 16)
        rom.decompressInto(IMG_COMM_GAME_BG, hardware.vram, 0)
        val refPal0 = (palId and 15) shl 12
        val refPal1 = ((palId + 1) and 15) shl 12
        var i = 0
        while (i < 224) map[i] = (i++ + refPal0).toShort()
        while (i < 416) map[i] = (i++ + refPal1).toShort()
        while (i < 640) map[i] = (i++ + refPal0).toShort()
        engine.start(bg1And3ScrollScript, proc)
    }

    /**
     * Transcribed from src/chapterintrofx.c: ChapterIntro_TwinLights_InitAndPlaySe (not
     *   verified: the fields are compared, the sound effect call is not)
     */
    private fun twinLightsInitAndPlaySe(proc: Proc) {
        val state = proc.state as State
        state.timer = 0
        state.unk4E = 0
        state.unk64 = 0
        state.unk66 = 0
        state.unk68 = 3
        sound.playSoundEffect(SONG_SYS_CHAPTER_START)
    }

    /**
     * Ported from src/chapterintrofx.c: ChapterIntro_TwinLights_Loop
     */
    private fun twinLightsLoop(proc: Proc) {
        val state = proc.state as State
        val lutX = rom.u8(LIGHT_X_LUT + state.timer)
        val lutX1 = rom.u8(LIGHT_X_LUT + state.timer + 1)
        val lutY = rom.u8(LIGHT_Y_LUT + state.timer)
        val lutY1 = rom.u8(LIGHT_Y_LUT + state.timer + 1)
        val linear = Interpolate.LINEAR
        state.xLight = Interpolate.interpolate(linear, lutX, lutX1, state.unk4E, state.unk68)
        state.yLight = Interpolate.interpolate(linear, lutY, lutY1, state.unk4E, state.unk68)
        if (state.unk64 <= 100) {
            val scale = Interpolate.interpolate(Interpolate.RSQUARE, 320, 64, state.unk64, 100)
            // COS(0) = 0x1000, SIN(0) = 0
            oam.setObjAffine(0, 0x1000 * 16 / scale, 0, 0, 0x1000 * 16 / scale)
            state.unk64 = (state.unk64 + 1).toShort().toInt()
        }
        val object32 = rom.pal(OBJECT_32X32, 4)
        oam.putHi(
            (state.xLight - 16) and 0x1FF,
            ((state.yLight - 16) and 0x1FF) or OAM0_AFFINE_ENABLE,
            object32,
            OAM2_CHR_210_PAL_2,
        )
        oam.putHi(
            (224 - state.xLight) and 0x1FF,
            ((144 - state.yLight) and 0x1FF) or OAM0_AFFINE_ENABLE,
            object32,
            OAM2_CHR_214_PAL_2,
        )
        state.unk4E = (state.unk4E + 1).toShort().toInt()
        if (state.unk4E >= state.unk68) {
            state.unk4E = 0
            state.timer = (state.timer + 1).toShort().toInt()
            state.unk68 = Interpolate.interpolate(Interpolate.LINEAR, 3, 8, state.unk66, 18)
            state.unk66 = (state.unk66 + 1).toShort().toInt()
            if (rom.u8(LIGHT_X_LUT + state.timer + 1) == 0) engine.breakProc(proc)
        }
    }

    private companion object {
        const val A_BUTTON = 0x0001
        const val B_BUTTON = 0x0002
        const val START_BUTTON = 0x0008
        const val SONG_SYS_CHAPTER_START = 0x316
        const val OAM0_AFFINE_ENABLE = 0x100
        const val OAM2_CHR_210_PAL_2 = 0x210 or (2 shl 12)
        const val OAM2_CHR_214_PAL_2 = 0x214 or (2 shl 12)
        const val OBJ_VRAM = 0x10000
        const val IMG_COMM_GAME_BG = 0x08B12DB4
        const val PAL_COMM_GAME_BG = 0x08B1754C
        const val IMG_LENS_FLARE = 0x08B17B64
        const val TSA_LENS_FLARE = 0x08B18D68
        const val PAL_LENS_FLARE = 0x08B18ED4
        const val IMG_SPRITES = 0x08B19874
        const val PAL_SPRITES = 0x08B19DEC
        const val OBJECT_32X32 = 0x08590F54
        const val LIGHT_X_LUT = 0x0859B120
        const val LIGHT_Y_LUT = 0x0859B132
    }
}
