package fe8.game.title

import fe8.game.GameControl
import fe8.game.SoundWrapper
import fe8.game.opanim.OpAnimHs
import fe8.game.opanim.blendPalette
import gba.data.Decompress
import gba.render.BgAffine
import gbafe.core.math.Interpolate
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.FadeCore
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The title screen `gProcScr_TitleScreen` (decomp `src/titlescreen.c`; sound calls go to
 * [GameControl.sound]). Started by [fe8.game.GameControl] as a blocking child.
 *
 * It first loads the background, dragon, logo and "Press Start" graphics. With `mode` 1 it then
 * plays the intro effect (rotating blue lights, orbs, a flash, the demon king image, the logo
 * zooming in, a light explosion) before the idle title; with `mode` 0 it goes straight to the
 * idle title. Idle ends on A or Start (continue to the save menu) or after 815 steps (class
 * reel). A, B or Start during the first part of the intro skips it by restarting at label 4.
 */
public class TitleScreen(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware. */
    public val hardware: BgHardware,
    /** ROM graphics. */
    public val gfx: Gfx,
    /** `gObject_32x32`: the glowing orb / blue light sprite object. */
    public val object32x32: SpriteObject? = null,
    /** The sprite lists (`src/ctc.c`). */
    public val sprites: SpriteLayers = SpriteLayers(),
    /** `gObject_8x8`: the object of the light bubble sprites of the light explosion. */
    public val object8x8: SpriteObject? = null,
    /** `gSprite_Title_SacredStonesBanner`: the "Sacred Stones" banner sprite. */
    public val bannerSprite: SpriteObject? = null,
    /**
     * In this order: `gObject_16x16` (used as the TM sign), `gSprite_Title_FireEmblemLogo`,
     * `gSprite_Title_PressStart` and `gSprite_Title_CopyrightInfo`.
     */
    public val menuSprites: List<SpriteObject> = emptyList(),
    /** `gPal_08AADBE8`: the 16 colours the "Press Start" glow cycles through. */
    public val glowPal: ShortArray = ShortArray(16),
    /** `gKeyStatusPtr`: the key state read for skip, A and Start presses. */
    public val keys: KeyStatus = KeyStatus(),
    /** The game control, for `SetNextGameActionId` and the sound (null: neither is done). */
    public val gameControl: GameControl? = null,
    /** Screen fades (`StartFadeCore`): the fade in at the start and the fade out at the end. */
    public val fadeCore: FadeCore = FadeCore(engine, hardware),
) {
    /**
     * VBlanks the current step's screen load misses on hardware (its CPU time overruns the frame),
     * read and reset by the main loop after each step. Measured with mGBA
     * (`gBmSt.main_loop_ended` in the `ram_trace_title1` dumps), not computed from cycle counts.
     */
    public var missedVBlanks: Int = 0

    /** ROM data of the title screen. */
    public class Gfx(
        /** `gGfx_TitleMainBackground_1`, `_2`, `gTsa_TitleMainBackground` (compressed). */
        public val background: List<OpAnimHs.Blob>,
        /** `gPal_TitleMainBackground` (16 colours). */
        public val backgroundPal: ShortArray,
        /** `gGfx_TitleDragonForeground`, `gTsa_TitleDragonForeground` (compressed). */
        public val dragon: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `gPal_TitleDragonForeground` (16 colours). */
        public val dragonPal: ShortArray,
        /** `gGfx_FireEmblemLogo` (compressed). */
        public val logo: OpAnimHs.Blob,
        /** `gGfx_SubtitlePressStart` (compressed). */
        public val pressStart: OpAnimHs.Blob,
        /** `gPal_PressStart` (4 palettes). */
        public val pressStartPal: ShortArray,
        /**
         * The intro fx graphics: `gGfx_08AAFD14`, `gTsa_08AAFF10`, `gGfx_08AADC08`,
         * `gTsa_08AAE61C`, `gGfx_08AAE8EC`, `gTsa_08AAF928`, `gGfx_TitleLargeGlowingOrb`,
         * `gGfx_TitleSmallLightBubbles` (compressed).
         */
        public val fx: List<OpAnimHs.Blob>,
        /**
         * Their palettes: `gPal_08AB0114`, `gPal_08AAE8CC`, `gPal_08AAFCF4` (16 colours each),
         * `gPal_TitleLargeGlowingOrb` (3 palettes), `gPal_TitleSmallLightBubbles`.
         */
        public val fxPals: List<ShortArray>,
        /** `gGfx_TitleDemonKing`, `gTsa_TitleDemonKing` (compressed) and `gPal_TitleDemonKing`. */
        public val demonKing: Triple<OpAnimHs.Blob, OpAnimHs.Blob, ShortArray>,
        /** `gGfx_08AB0134`, `gTsa_08AB0A20` (compressed) and `gPal_08AB0B24`: the zooming logo. */
        public val logoZoom: Triple<OpAnimHs.Blob, OpAnimHs.Blob, ShortArray>,
    )

    /** `struct TitleScreenProc` fields (`include/opanim.h`) of the title proc. */
    public class State {
        /**
         * `mode`: 1 runs the intro fx and starts on a white backdrop (`StartTitleScreen_FlagTrue`),
         * 0 goes straight to the idle title. The decomp header only guesses it is a flag for
         * "coming from the intro sequence".
         */
        public var mode: Int = 0

        /** `timer`: step counter of the running loop step; reset to 0 when that step breaks. */
        public var timer: Int = 0

        /** `timer_idle`: steps spent idle on the title; at 815 the class reel starts. */
        public var timerIdle: Int = 0

        /**
         * `unk_30` in the decomp: the light explosion progress (0..63) of the previous step. Only
         * ever handed to the no-op `nullsub_23`, so it has no effect; kept as the decomp has it.
         */
        public var unk30: Int = 0
    }

    /** Scratch for decompressed tile maps (a 32x32 map is 0x800 bytes), see [loadMap]. */
    private val mapBytes = ByteArray(0x800)

    /**
     * `gProcScr_TitleScreen`. Labels: 4 start (load and show the main screen), 0 intro fx,
     * 1 idle on the title, 2 fade out and end, 3 restart (jumped to by the skip listener).
     * `repeat` steps run once per frame until they break.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_TitleScreen",
        listOf(
            label(4),
            yield(),
            call(ProcFunc { init(it) }),
            repeat(ProcFunc { setupMainGraphics(it) }),
            call(ProcFunc { tryJumpIntroAnim(it) }),
            call(ProcFunc { showMain() }),
            call(ProcFunc { fadeCore.start(2, 0x20, it) }), // FadeInBlackSpeed20
            yield(),
            goto(1),
            label(0),
            yield(),
            call(ProcFunc { engine.start(skipListenerScript, it) }), // Title_StartSkipFxListener
            repeat(ProcFunc { setupSpecialEffectGraphics(it) }),
            call(ProcFunc { setupBlueLights(it) }),
            repeat(ProcFunc { rotatingBlueLights(it) }),
            call(ProcFunc { showRedBlueOrbs() }),
            repeat(ProcFunc { redBlueOrbs(it) }),
            call(ProcFunc { prepareMainScreen() }),
            repeat(ProcFunc { revealDragonFlash(it) }),
            repeat(ProcFunc { flashExpand(it) }),
            yield(),
            call(ProcFunc { loadDemonKing() }),
            sleep(8),
            call(ProcFunc { showBg2() }),
            sleep(2),
            call(ProcFunc { showBg1Obj() }),
            sleep(8),
            // Title_EndSkipFxListener
            call(ProcFunc { endSkipListener() }),
            call(ProcFunc { prepareLogoZoom() }),
            repeat(ProcFunc { logoZoom(it) }),
            call(ProcFunc { prepareLightExplosion(it) }),
            repeat(ProcFunc { lightExplosion(it) }),
            call(ProcFunc { showTitle(it) }),
            label(1),
            repeat(ProcFunc { idle(it) }),
            label(2),
            call(ProcFunc { fadeCore.startFadeToDark(it) }), // sub_8013F40
            yield(),
            call(ProcFunc { endAllProcChildren(it) }),
            goto(5),
            label(3),
            call(ProcFunc { restart(it) }),
            goto(4),
            label(5),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Intro fx, after the rotating blue lights: clears BG2, switches to mode 0 showing only BG1
     * and OBJ with a 16/16 alpha blend of OBJ (first target) over BG1 (second target), and blacks
     * out BG palette 14 (the main background palette, faded in by [redBlueOrbs]).
     *
     * Ported from src/titlescreen.c: sub_80C5BD4
     */
    public fun showRedBlueOrbs() {
        val hw = hardware
        val lcd = hw.lcd
        hw.fill(hw.mapBuffer(2), 0)
        hw.enableBgSyncByMask(6)
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        lcd.setBlendConfig(1, 0x10, 0x10, 0x10)
        lcd.setBlendTargetA(0, 0, 0, 0, 1)
        lcd.setBlendTargetB(0, 1, 0, 0, 0)
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0200 or 0x1000
        hw.palette.fill(0, 0xE * 16, 0xF * 16)
        hw.enablePaletteSync()
    }

    /**
     * Fades the main background palette (BG palette 14) in from black over 48 steps. The glowing
     * orb sprite (OBJ palette 4, tile 0x220) is shown for steps 0..19, then from step 20 the two
     * orb copies of OBJ palettes 5 and 6 (tiles 0x224 and 0x228, the decomp's "red and blue
     * orbs") alternate their layers each step, and from step 24 they move apart horizontally
     * (squared ease over 8 steps, 128 px). Starts `SONG_MAIN_THEME_EXT` at step 20 (player 0 in
     * the decomp, -1 here). Breaks at step 48.
     *
     * Ported from src/titlescreen.c: Title_Loop_DrawRedBlueOrbs
     */
    public fun redBlueOrbs(proc: Proc) {
        val st = proc.state as State
        val t = st.timer
        val obj = object32x32 ?: error("object32x32 not supplied")
        applyPalette(gfx.backgroundPal, 0xE, 1)
        val fade = Interpolate.interpolate(Interpolate.LINEAR, 16, 0, t, 48)
        blendPalette(hardware.palette, 0xE * 16, 0, fade)
        if (t == 20) gameControl?.sound?.startBgmExt(SoundWrapper.SONG_MAIN_THEME_EXT, 0, -1)
        if (t < 20) {
            sprites.putExt(0, 104, 64, obj, 0x4220)
        } else if (t < 24) {
            val odd = (t and 1) != 0
            sprites.putExt(0, 104, 64, obj, if (odd) 0x6228 else 0x5224)
            sprites.putExt(1, 104, 64, obj, if (odd) 0x5224 else 0x6228)
        } else if (t - 24 < 9) {
            val d = Interpolate.interpolate(Interpolate.SQUARE, 0, 128, t - 24, 8)
            if ((t and 1) != 0) {
                sprites.putExt(0, (104 - d) and 0x1FF, 64, obj, 0x6228)
                sprites.putExt(1, (d + 104) and 0x1FF, 64, obj, 0x5224)
            } else {
                sprites.putExt(0, (d + 104) and 0x1FF, 64, obj, 0x5224)
                sprites.putExt(1, (104 - d) and 0x1FF, 64, obj, 0x6228)
            }
        }
        stepUntil(proc, 48)
    }

    /**
     * Shows BG0 (the green dragon foreground), BG1 and OBJ with an alpha blend of BG0 (first
     * target, coefficient 0 so invisible) over BG1 (second target, coefficient 16), and plays
     * sound effect `SONG_317` (0x317); what that sound is: meaning unknown.
     *
     * Ported from src/titlescreen.c: Title_PrepareMainScreenWithExtendedBgm
     */
    public fun prepareMainScreen() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x1000
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.setBlendTargetA(1, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 1, 0, 0, 0)
        gameControl?.sound?.playSoundEffect(SoundWrapper.SONG_317)
    }

    /**
     * Raises the BG0 blend coefficient from 0 to 13 (of 16) over 12 steps, which reveals the
     * dragon foreground over BG1. Breaks at step 12.
     *
     * Ported from src/titlescreen.c: Title_Loop_RevealGreenDragonFlashFx
     */
    public fun revealDragonFlash(proc: Proc) {
        val t = (proc.state as State).timer
        hardware.lcd.blendCoeffA = Interpolate.interpolate(Interpolate.LINEAR, 0, 13, t, 12)
        stepUntil(proc, 12)
    }

    /**
     * Fades BG palette 2 (the second BG0 intro layer, see [setupSpecialEffectGraphics]) and
     * BG palette 14 (main background) to white over 12 steps: the white flash. Breaks at
     * step 12.
     *
     * Ported from src/titlescreen.c: Title_Loop_FlashFxExpand
     */
    public fun flashExpand(proc: Proc) {
        val t = (proc.state as State).timer
        val res = Interpolate.interpolate(Interpolate.LINEAR, 0, 16, t, 12)
        if (t == 0) hardware.enableBgSyncByMask(1)
        applyPalette(gfx.fxPals[2], 2, 1)
        blendPalette(hardware.palette, 2 * 16, 0x7FFF, res)
        applyPalette(gfx.backgroundPal, 0xE, 1)
        blendPalette(hardware.palette, 0xE * 16, 0x7FFF, res)
        stepUntil(proc, 12)
    }

    /**
     * Loads the demon king image (`gGfx_TitleDemonKing`) to BG2: tiles at VRAM 0x8000, map
     * entries offset by 0x3000 = palette 3, which is also where its palette goes.
     *
     * Ported from src/titlescreen.c: Title_LoadDemonKingOnBg2
     */
    public fun loadDemonKing() {
        missedVBlanks = 1
        val (tiles, map, pal) = gfx.demonKing
        Decompress.into(tiles.data, tiles.offset, hardware.vram, 0x8000)
        loadMap(map, 2, 0x3000)
        applyPalette(pal, 3, 1)
        hardware.enableBgSyncByMask(4)
    }

    /**
     * Shows only BG2 (the demon king image) with the colour effect off; OBJ is hidden too.
     *
     * Ported from src/titlescreen.c: Title_ShowBg2
     */
    public fun showBg2() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0400
        lcd.bldcnt = lcd.bldcnt and 0xC0.inv()
    }

    /**
     * Hides the demon king: shows BG1 and OBJ again and sets the colour effect to alpha blend
     * (BLDCNT effect 1; the coefficients and targets still hold [prepareMainScreen]'s).
     *
     * Ported from src/titlescreen.c: sub_80C5FB4
     */
    public fun showBg1Obj() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0200 or 0x1000
        lcd.bldcnt = (lcd.bldcnt and 0xC0.inv()) or 0x40
    }

    /**
     * Prepares the logo zoom: mode 1 with the zooming logo image (`gGfx_08AB0134`, exact
     * content: meaning unknown) on BG2 (affine 256x256 at scale 0x40, palette 0), BG palettes 14
     * and 15 all white (the light explosion fades them back), BG0 back to tiles 0 / map 0x6800
     * (the title's dragon layer); shows BG0, BG1, BG2 and OBJ.
     *
     * Ported from src/titlescreen.c: Title_PrepareMainLogoZoom
     */
    public fun prepareLogoZoom() {
        val hw = hardware
        val lcd = hw.lcd
        val (tiles, map, pal) = gfx.logoZoom
        lcd.dispcnt = (lcd.dispcnt and 7.inv()) or 1
        hw.setScreenSize(2, 1)
        logoAffine(0x40)
        Decompress.into(tiles.data, tiles.offset, hw.vram, 0x8000)
        loadMap(map, 2, 0)
        applyPalette(pal, 0, 1)
        hw.enableBgSyncByMask(4)
        hw.palette.fill(0x7FFF, 0xE * 16, 0x10 * 16)
        hw.enablePaletteSync()
        hw.setTileDataOffset(0, 0)
        hw.setMapDataOffset(0, 0x6800)
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x0400 or 0x1000
    }

    /**
     * Zooms the logo: BG2's affine scale value goes from 32 to 256 (256 = 1:1, so it ends at
     * natural size) over 16 steps with a squared ease. Breaks at step 16.
     *
     * Ported from src/titlescreen.c: Title_Loop_MainLogoZoom
     */
    public fun logoZoom(proc: Proc) {
        val t = (proc.state as State).timer
        logoAffine(Interpolate.interpolate(Interpolate.SQUARE, 32, 256, t, 16))
        stepUntil(proc, 16)
    }

    /**
     * Sets BG2's affine registers (`gLCDControlBuffer.bg2affin`) for the zooming logo: texture
     * point (0x78, 0x50) (the 240x160 screen centre) at screen (0x78, 0x50), scale value [scale]
     * both ways (256 = 1:1), no rotation.
     *
     * Ported from src/titlescreen.c: sub_80C5548
     */
    public fun logoAffine(scale: Int) {
        BgAffine.set(hardware.lcd.affine, 0, 0x7800, 0x5000, 0x78, 0x50, scale, scale, 0)
    }

    /**
     * Prepares the light explosion: alpha blend at 16/16 with no first target and BG0 and BG1 as
     * second targets, which only affects the semi-transparent light bubble sprites (GBA: a
     * semi-transparent OBJ blends with the second targets). Resets `unk_30`. (The decomp's
     * `gGenericBuffer` clear, `sub_80C60CC`, is not modelled: nothing reads that buffer here.)
     *
     * Ported from src/titlescreen.c: sub_80C6104
     */
    public fun prepareLightExplosion(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setBlendConfig(1, 0x10, 0x10, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 0)
        (proc.state as State).unk30 = 0
    }

    /**
     * The light explosion that reveals the title: over 24 steps the blend coefficient goes 16
     * to 0 and BG palettes 14 and 15 (main background, dragon) fade from white back to their
     * colours, while the "Sacred Stones" banner is shown and the light bubbles fly out from the
     * centre ([drawLightBubbles]). Breaks at step 24.
     *
     * Ported from src/titlescreen.c: Title_Loop_LightExplosionFx
     */
    public fun lightExplosion(proc: Proc) {
        val st = proc.state as State
        val t = st.timer
        val hw = hardware
        hw.lcd.blendCoeffA = Interpolate.interpolate(Interpolate.LINEAR, 16, 0, t, 24)
        val res = Interpolate.interpolate(Interpolate.LINEAR, 16, 0, t, 24)
        applyPalette(gfx.backgroundPal, 0xE, 1)
        blendPalette(hw.palette, 0xE * 16, 0x7FFF, res)
        applyPalette(gfx.dragonPal, 0xF, 1)
        blendPalette(hw.palette, 0xF * 16, 0x7FFF, res)
        st.unk30 = Interpolate.interpolate(Interpolate.LINEAR, 0, 63, t, 24) // nullsub_23
        sprites.putExt(1, 16, 85, bannerSprite ?: error("bannerSprite not supplied"), 0x31A0)
        drawLightBubbles(t)
        stepUntil(proc, 24)
    }

    /**
     * Puts the 62 light bubbles of the light explosion at step [step] of 24 (8x8 sprites on
     * sprite layer 4, OBJ palette 7 = `gPal_TitleSmallLightBubbles`, semi-transparent through the
     * 0x400 in the y attribute). Each bubble moves linearly along one of 21 rays from the
     * centre (116, 56); the ray vector (s8 pair) times a distance, shifted right by 8, is its
     * offset. Tile 0x2A0 is the bubble graphics' place in OBJ VRAM (0x15400).
     *
     * Ported from src/titlescreen.c: DrawTitleLightBubbleSprites
     */
    public fun drawLightBubbles(step: Int) {
        val obj = object8x8 ?: error("object8x8 not supplied")
        for (i in 0 until BUBBLES.size / 4) {
            val startDist = BUBBLES[4 * i]
            val endDist = BUBBLES[4 * i + 1]
            val rayIndex = BUBBLES[4 * i + 2]
            val rayX = RAYS[2 * rayIndex]
            val rayY = RAYS[2 * rayIndex + 1]
            val lin = Interpolate.LINEAR
            val x = Interpolate.interpolate(lin, (rayX * startDist) shr 8, (endDist * rayX) shr 8, step, 24) + 116
            val y = Interpolate.interpolate(lin, (rayY * startDist) shr 8, (endDist * rayY) shr 8, step, 24) + 56
            val tile = (BUBBLES[4 * i + 3] + 0x2A0) or 0x7400
            sprites.putExt(4, x and 0x1FF, (y and 0xFF) or 0x400, obj, tile)
        }
    }

    /**
     * The shared tail of every timed loop step: at step [last] it breaks [proc] and resets its
     * timer, else it counts the timer up (so the step runs [last] + 1 times).
     */
    private fun stepUntil(proc: Proc, last: Int) {
        val st = proc.state as State
        if (st.timer == last) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer++
        }
    }

    /**
     * `gProcScr_SkipTitleFxKeyListener`: while the intro fx runs, A, B or Start jumps the title
     * proc to label 3 (restart), which skips the rest of the intro.
     */
    public val skipListenerScript: ProcScript = ProcScript(
        "gProcScr_SkipTitleFxKeyListener",
        listOf(yield(), repeat(ProcFunc { skipListener(it) }), end()),
    )

    /**
     * Skips the intro: jumps the title proc to label 3 ([restart]) when A, B or Start is newly
     * pressed.
     *
     * Ported from src/titlescreen.c: SkipTitleFx_Loop_KeyListener
     */
    public fun skipListener(proc: Proc) {
        if ((keys.newKeys and (A_BUTTON or B_BUTTON or KeyStatus.START_BUTTON)) != 0) {
            engine.goto(proc.parent!!, 3)
        }
    }

    /**
     * Ends the skip listener proc (at the end of the intro, just before the logo zoom, and when
     * restarting), so the intro can no longer be skipped.
     *
     * Ported from src/titlescreen.c: Title_EndSkipFxListener
     */
    public fun endSkipListener() {
        engine.end(engine.find(skipListenerScript))
    }

    /** `gProcScr_DrawTitleSprites` state (`unk_4c`, `unk_52` of its proc). */
    public class SpritesState {
        /** `unk_4c` in the decomp: frame counter (s16); the glow advances every third frame. */
        public var frameCounter: Int = 0

        /** `unk_52` in the decomp: "Press Start" glow phase (0..31), a triangle wave. */
        public var glowPhase: Int = 0
    }

    /**
     * `gProcScr_DrawTitleSprites`: every frame puts the logo, TM sign, banner, "Press Start" and
     * copyright sprites and animates the "Press Start" glow.
     */
    public val drawSpritesScript: ProcScript = ProcScript(
        "gProcScr_DrawTitleSprites",
        listOf(
            // DrawTitleSprites_Init
            call(ProcFunc { spritesState(it).apply { frameCounter = 0; glowPhase = 0 } }),
            repeat(ProcFunc { drawSprites(it) }),
            end(),
        ),
        newState = { SpritesState() },
    )

    /** [proc]'s state as a [SpritesState] (the sprites proc and the title proc differ). */
    private fun spritesState(proc: Proc): SpritesState = proc.state as SpritesState

    /**
     * Puts the title sprites and cycles the "Press Start" glow colour (OBJ palette 0 entry 8,
     * byte offset 0x210 of the palette buffer) one step every 3 frames. The logo is put twice:
     * normally on layer 0 at y 48, and again on layer 2 at y 1077 (53 plus the 0x400
     * semi-transparent flag) from tile 0x80; what that second copy shows: meaning unknown.
     *
     * Ported from src/titlescreen.c: DrawTitleSprites_Loop
     */
    public fun drawSprites(proc: Proc) {
        val st = spritesState(proc)
        val (tm, logo, pressStart, copyright) = menuSprites
        sprites.putExt(0, 4, 48, logo, 0x2000)
        sprites.putExt(0, 220, 41, tm, 0x201E)
        sprites.putExt(2, 4, 1077, logo, 0x2080)
        sprites.putExt(1, 16, 85, bannerSprite ?: error("bannerSprite not supplied"), 0x31A0)
        sprites.putExt(1, 72, 124, pressStart, 0x1BA)
        sprites.putExt(1, 4, 148, copyright, 0x1180)
        if (st.frameCounter % 3 == 0) st.glowPhase = (st.glowPhase + 1) and 0x1F
        val phase = st.glowPhase
        val glowColorIndex = if ((phase and 0x10) != 0) phase and 0xF else (0x1F - phase) and 0xF
        hardware.palette[0x108] = glowPal[glowColorIndex]
        hardware.enablePaletteSync()
        st.frameCounter = (st.frameCounter + 1).toShort().toInt()
    }

    /**
     * Shows the idle title after the light explosion: mode 0, BG2 back to a 256x256 text map,
     * BG0, BG1 and OBJ shown, and starts the title sprites proc. Alpha blend 8/8 with no first
     * target and BG0 and BG1 as second targets, which only affects the semi-transparent sprites
     * (the second logo copy).
     *
     * Ported from src/titlescreen.c: sub_80C62DC
     */
    public fun showTitle(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        hw.setScreenSize(2, 0)
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x1000
        engine.start(drawSpritesScript, proc)
        lcd.setBlendConfig(1, 8, 8, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 0)
    }

    /**
     * Waits on the title: A or Start plays sound effect `SONG_6D` (0x6D; what it is: meaning
     * unknown) and sets the next game action to `GAME_ACTION_EVENT_RETURN`, which the game
     * control turns into the save menu; after 815 idle steps it sets `GAME_ACTION_CLASS_REEL`
     * instead (the class reel demo). Either way it breaks to label 2 (fade out). `timer` cycles
     * 0..63 but nothing in the title reads it.
     *
     * Transcribed from src/titlescreen.c: Title_IDLE (not verified: the 815-step class reel branch never
     * runs in the traces and the next-action id and sound effect are not compared)
     */
    public fun idle(proc: Proc) {
        val st = proc.state as State
        st.timerIdle++
        st.timer = (st.timer + 1) and 0x3F
        if ((keys.newKeys and (A_BUTTON or KeyStatus.START_BUTTON)) != 0) {
            gameControl?.sound?.playSoundEffect(SoundWrapper.SONG_6D)
            gameControl?.setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
            engine.breakProc(proc)
        } else if (st.timerIdle == 815) {
            gameControl?.setNextGameActionId(GameControl.GAME_ACTION_CLASS_REEL)
            engine.breakProc(proc)
        }
    }

    /**
     * Ends the title's child procs (the sprites proc) once the fade out has finished.
     *
     * Ported from src/titlescreen.c: Title_EndAllProcChildren
     */
    public fun endAllProcChildren(proc: Proc) {
        engine.endAllChildren(proc)
    }

    /**
     * Label 3, after a skip: ends the children and the skip listener, black backdrop, `mode`
     * back to 0 (so the intro is not replayed), all layers off; the proc then restarts at
     * label 4. Restarts the main theme (`StartBgmExt(SONG_MAIN_THEME, 0, 0)`; the decomp's null
     * player is -1 here).
     *
     * Ported from src/titlescreen.c: Title_RestartProc
     */
    public fun restart(proc: Proc) {
        engine.endAllChildren(proc)
        endSkipListener()
        hardware.palette[0] = 0
        hardware.enablePaletteSync()
        (proc.state as State).mode = 0
        hardware.lcd.dispcnt = hardware.lcd.dispcnt and 0x1F00.inv()
        gameControl?.sound?.startBgmExt(SoundWrapper.SONG_MAIN_THEME, 0, -1)
    }

    /**
     * Loads the intro fx graphics, one group per step (0..3), then breaks at step 4: BG0 moves
     * to tiles 0xC000 / map 0x7800; the blue lights image and its map go to the BG2 tile and map
     * areas (VRAM 0x8000 / 0xB000); two images go to BG0 tile areas 0xC000 and 0xD000 with their
     * maps loaded one after the other into the same BG0 map buffer (the second replaces the
     * first; the map offsets 0x1000 / 0x2080 select palette 1 / 2 and tile 0xD000), see
     * [flashExpand] for palette 2; the glowing orb (OBJ VRAM 0x14400, OBJ palettes 4..6) and the
     * small light bubbles (0x15400, OBJ palette 7); white backdrop. What the BG0 images show:
     * meaning unknown (decomp names `gGfx_08AADC08`, `gGfx_08AAE8EC`).
     *
     * Ported from src/titlescreen.c: Title_SetupSpecialEffectGraphics
     */
    public fun setupSpecialEffectGraphics(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val fxBlobs = gfx.fx
        val fxPalettes = gfx.fxPals
        if (st.timer == 2) missedVBlanks = 1
        when (st.timer) {
            0 -> {
                hw.setTileDataOffset(0, 0xC000)
                hw.setMapDataOffset(0, 0x7800)
                Decompress.into(fxBlobs[0].data, fxBlobs[0].offset, hw.vram, 0x8000)
                Decompress.into(fxBlobs[1].data, fxBlobs[1].offset, hw.vram, 0xB000)
                applyPalette(fxPalettes[0], 0, 1)
                hw.palette[0] = 0x7FFF
            }
            1 -> {
                Decompress.into(fxBlobs[2].data, fxBlobs[2].offset, hw.vram, 0xC000)
                loadMap(fxBlobs[3], 0, 0x1000)
                applyPalette(fxPalettes[1], 1, 1)
                hw.enableBgSyncByMask(1)
            }
            2 -> {
                Decompress.into(fxBlobs[4].data, fxBlobs[4].offset, hw.vram, 0xD000)
                loadMap(fxBlobs[5], 0, 0x2080)
                applyPalette(fxPalettes[2], 2, 1)
            }
            3 -> {
                Decompress.into(fxBlobs[6].data, fxBlobs[6].offset, hw.vram, 0x14400)
                applyPalette(fxPalettes[3], 0x14, 3)
                Decompress.into(fxBlobs[7].data, fxBlobs[7].offset, hw.vram, 0x15400)
                applyPalette(fxPalettes[4], 0x17, 1)
            }
            4 -> {
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer++
    }

    /**
     * Starts the rotating blue lights: mode 1 with BG2 as a 512x512 affine layer (screen size 2)
     * without wraparound, at scale value 8, only BG2 and OBJ shown, BG2 above the other layers
     * (priorities BG0 1, BG1 2, BG2 0, BG3 3). Brightness-increase effect (BLDCNT effect 2) at
     * BLDY 16 on BG0..BG3 and OBJ, so it starts fully white, black backdrop.
     *
     * Ported from src/titlescreen.c: sub_80C5A44
     */
    public fun setupBlueLights(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = (lcd.dispcnt and 7.inv()) or 1
        hw.setScreenSize(2, 2)
        bgAffineSetOpAnim(8, 0)
        lcd.bgCnt[2] = lcd.bgCnt[2] and 0x2000.inv()
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0400 or 0x1000
        for ((bg, prio) in listOf(1, 2, 0, 3).withIndex()) lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or prio
        lcd.setBlendConfig(2, 8, 8, 0x10)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        hw.palette[0] = 0
        hw.enablePaletteSync()
        (proc.state as State).timer = 0
    }

    /**
     * The rotating blue lights: over 90 steps BG2's scale value goes 64 to 256 (1:1) in the
     * first 45 and 256 to 448 in the last 45 while it rotates by 64/256 of a turn, with the
     * glowing orb sprite (OBJ palette 4) shown from the step the scale value exceeds 0xFF. Fades
     * in from white over steps 0..22 (BLDY 16 to 0), then from step 68 switches to the
     * brightness-decrease effect (BLDCNT effect 3) and fades out to black over 22 steps. Breaks
     * at step 90.
     *
     * Ported from src/titlescreen.c: Title_Loop_DrawRotatingBlueLights
     */
    public fun rotatingBlueLights(proc: Proc) {
        val st = proc.state as State
        val lcd = hardware.lcd
        val t = st.timer
        val res = if (t < 45) {
            Interpolate.interpolate(Interpolate.LINEAR, 64, 256, t, 45)
        } else {
            Interpolate.interpolate(Interpolate.LINEAR, 256, 448, t - 45, 45)
        }
        bgAffineSetOpAnim(res, Interpolate.interpolate(Interpolate.LINEAR, 0, 64, t, 90))
        if (res > 0xFF) sprites.putExt(0, 104, 64, object32x32 ?: error("object32x32 not supplied"), 0x4220)
        if (t < 23) lcd.blendY = Interpolate.interpolate(Interpolate.LINEAR, 16, 0, t, 22)
        val time = t - 68
        if (time > -1) {
            if (time == 0) {
                lcd.bldcnt = (lcd.bldcnt and 0xC0.inv()) or (3 shl 6)
                lcd.setBlendTargetA(1, 1, 1, 1, 0)
            }
            lcd.blendY = Interpolate.interpolate(Interpolate.LINEAR, 0, 16, time, 22)
        }
        if (t == 90) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Sets BG2's affine registers for the rotating blue lights: texture point (0x100, 0x100)
     * (centre of the 512x512 layer) at screen (0x78, 0x50), scale value [scale] both ways
     * (256 = 1:1), rotation [angle] (256ths of a turn).
     *
     * Ported from src/opanim-main.c: BgAffineSetOpAnim
     */
    public fun bgAffineSetOpAnim(scale: Int, angle: Int) {
        BgAffine.set(hardware.lcd.affine, 0, 0x10000, 0x10000, 0x78, 0x50, scale, scale, angle shl 8)
    }

    /**
     * Starts the title screen as a blocking child of [parent] with [mode] (see [State.mode]; 1
     * runs the intro fx). `StartTitleScreen_WithMusic` / `_FlagFalse` use mode 0 (the former also
     * starts the BGM, see [GameControl]).
     *
     * Ported from src/titlescreen.c: StartTitleScreen_FlagTrue
     */
    public fun start(parent: Proc, mode: Int): Proc? =
        engine.startBlocking(script, parent)?.also { (it.state as State).mode = mode }

    /**
     * Label 4, start of the title: resets the display to mode 0 with all layers and windows
     * off, BG tile/map offsets (BG0 0 / 0x6800, BG1 0 / 0x7000, BG2 0x8000 / 0xB000, BG3
     * 0xC000 / 0x7800), 256x256 screen sizes, priorities (BG n has priority n), scroll 0, empty
     * maps, no backdrop blend targets and the default colour effects (no effect, coefficient 16).
     *
     * Ported from src/titlescreen.c: Title_Init
     */
    public fun init(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and (0x7 or 0x1F00 or 0xE000).inv()
        hw.setTileDataOffset(0, 0)
        hw.setTileDataOffset(1, 0)
        hw.setTileDataOffset(2, 0x8000)
        hw.setTileDataOffset(3, 0xC000)
        hw.setMapDataOffset(0, 0x6800)
        hw.setMapDataOffset(1, 0x7000)
        hw.setMapDataOffset(2, 0xB000)
        hw.setMapDataOffset(3, 0x7800)
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
            lcd.setBgPosition(bg, 0, 0)
            hw.fill(hw.mapBuffer(bg), 0)
        }
        lcd.bldcnt = lcd.bldcnt and (0x20 or 0x2000).inv() // SetBlendBackdropA/B(0)
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        hw.enableBgSyncByMask(0xF)
        val st = proc.state as State
        st.timer = 0
        st.timerIdle = 0
    }

    /**
     * Loads the main graphics, one group per step (0..4), then breaks at step 4: the main
     * background tiles (VRAM 0 and 0x3000) and map on BG1 (map offset 0xE000 = palette 14, BG
     * palette 14); the dragon foreground (tiles 0x5000, map on BG0 with offset 0xF280 = palette 15
     * and tile 0x280, BG palette 15); the Fire Emblem logo (OBJ VRAM 0x10000) and the "Press
     * Start" graphics (0x13000, OBJ palettes 0..3). The backdrop is white with `mode` 1, else
     * black.
     *
     * Ported from src/titlescreen.c: Title_SetupMainGraphics
     */
    public fun setupMainGraphics(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val gfxData = gfx
        if (st.timer <= 2) missedVBlanks = 1
        when (st.timer) {
            0 -> Decompress.into(gfxData.background[0].data, gfxData.background[0].offset, hw.vram, 0)
            1 -> {
                Decompress.into(gfxData.background[1].data, gfxData.background[1].offset, hw.vram, 0x3000)
                loadMap(gfxData.background[2], 1, 0xE000)
                applyPalette(gfxData.backgroundPal, 0xE, 1)
                hw.palette[0] = if (st.mode != 0) 0x7FFF else 0
            }
            2 -> {
                Decompress.into(gfxData.dragon.first.data, gfxData.dragon.first.offset, hw.vram, 0x5000)
                loadMap(gfxData.dragon.second, 0, 0xF280)
                applyPalette(gfxData.dragonPal, 0xF, 1)
                hw.enableBgSyncByMask(1)
            }
            3 -> Decompress.into(gfxData.logo.data, gfxData.logo.offset, hw.vram, 0x10000)
            4 -> {
                Decompress.into(gfxData.pressStart.data, gfxData.pressStart.offset, hw.vram, 0x13000)
                applyPalette(gfxData.pressStartPal, 0x10, 4)
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer++
    }

    /**
     * With `mode` not 0 jumps to the intro fx (label 0); otherwise starts the title sprites proc
     * (the intro starts it later, in [showTitle]).
     *
     * Ported from src/titlescreen.c: TitleScreenTryJumpIntroAnim
     */
    public fun tryJumpIntroAnim(proc: Proc) {
        if ((proc.state as State).mode != 0) {
            engine.goto(proc, 0)
        } else {
            engine.start(drawSpritesScript, proc)
        }
    }

    /**
     * Shows the freshly loaded main screen (the main screen is faded in right after): BG0, BG1
     * and OBJ shown, BG syncs for all four BGs requested, alpha blend 8/8 with no first target
     * and BG0 and BG1 as second targets (it only affects semi-transparent sprites).
     *
     * Ported from src/titlescreen.c: sub_80C5870
     */
    public fun showMain() {
        val lcd = hardware.lcd
        hardware.enableBgSyncByMask(0xF)
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x1000
        lcd.setBlendConfig(1, 8, 8, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 0)
    }

    /**
     * Decompresses a map (TSA) into BG [bg]'s map buffer and adds [add] to the first 0x280 entries
     * (the 32x20 visible tiles), which selects a palette bank (bits 12..15) and shifts the tile
     * numbers, as the decomp's `gBG0TilemapBuffer[i] += ...` loops do.
     */
    private fun loadMap(blob: OpAnimHs.Blob, bg: Int, add: Int) {
        val decompressedBytes = Decompress.into(blob.data, blob.offset, mapBytes, 0)
        val map = hardware.mapBuffer(bg)
        for (i in 0 until minOf(decompressedBytes / 2, map.size)) {
            map[i] = ((mapBytes[2 * i].toInt() and 0xFF) or ((mapBytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        if (add != 0) for (i in 0 until 0x280) map[i] = (map[i] + add).toShort()
    }

    /**
     * `ApplyPalettes(src, bank, count)` (`ApplyPalette` is count 1): copies [count] 16-colour
     * palettes into palette bank [bank] of the palette buffer (BG banks 0..15, OBJ 16..31).
     */
    private fun applyPalette(src: ShortArray, bank: Int, count: Int) {
        src.copyInto(hardware.palette, bank * 16, 0, 16 * count)
        hardware.enablePaletteSync()
    }

    private companion object {
        /** `A_BUTTON` key bit. */
        const val A_BUTTON = 0x1

        /** `B_BUTTON` key bit. */
        const val B_BUTTON = 0x2

        /**
         * `gUnknown_08AA682C` without its terminator: the (x, y) vector of each of the 21 bubble
         * rays (s8 pairs, 0x78 = 120 at most), a fan around the centre of the explosion.
         */
        val RAYS: IntArray = intArrayOf(
            0x68, 0xC4, 0x6B, 0xCA, 0x6E, 0xCF, 0x70, 0xD5, 0x72, 0xDB, 0x74, 0xE1, 0x75, 0xE7,
            0x77, 0xED, 0x77, 0xF3, 0x78, 0xFA, 0x78, 0x00, 0x78, 0x06, 0x77, 0x0D, 0x77, 0x13,
            0x75, 0x19, 0x74, 0x1F, 0x72, 0x25, 0x70, 0x2B, 0x6E, 0x31, 0x6B, 0x36, 0x68, 0x3C,
        ).map { it.toByte().toInt() }.toIntArray()

        /**
         * `gUnknown_08AA6858` without its terminator (62 bubbles), four entries each: start and
         * end distance along the ray (s16, 256 = the full ray vector), ray index (into [RAYS]) and
         * the bubble tile (0..3) added to the base tile 0x2A0.
         */
        val BUBBLES: IntArray = intArrayOf(
            0x0060, 0x0100, 0x00, 0x00, 0x00A0, 0x0140, 0x01, 0x01, 0x0070, 0x0180, 0x02, 0x02,
            0x0080, 0x0120, 0x03, 0x03, 0x0050, 0x0110, 0x04, 0x00, 0x0090, 0x0170, 0x05, 0x01,
            0x00C0, 0x0140, 0x06, 0x02, 0x0060, 0x0130, 0x07, 0x03, 0x00B0, 0x0100, 0x08, 0x00,
            0x0080, 0x0170, 0x09, 0x01, 0x0070, 0x0120, 0x0A, 0x02, 0x0080, 0x0110, 0x0B, 0x03,
            0x0060, 0x0120, 0x0C, 0x00, 0x00A0, 0x0160, 0x0D, 0x01, 0x0090, 0x0120, 0x0E, 0x02,
            0x0060, 0x0150, 0x0F, 0x03, 0x0070, 0x0170, 0x10, 0x00, 0x00A0, 0x0110, 0x11, 0x01,
            0x00A0, 0x0180, 0x12, 0x02, 0x0080, 0x0130, 0x13, 0x03, 0x0070, 0x0120, 0x14, 0x00,
            0x0060, 0x0160, 0x05, 0x01, 0x0080, 0x0110, 0x06, 0x02, 0x00A0, 0x0100, 0x07, 0x03,
            0x00C0, 0x0180, 0x08, 0x00, 0x00B0, 0x0160, 0x09, 0x01, 0x0080, 0x0130, 0x0A, 0x02,
            0x0060, 0x0150, 0x0B, 0x03, 0x0080, 0x0120, 0x0C, 0x00, 0x0070, 0x0140, 0x0D, 0x01,
            0x0090, 0x0180, 0x0E, 0x02, 0xFFA0, 0xFF00, 0x00, 0x00, 0xFF60, 0xFEC0, 0x01, 0x01,
            0xFF90, 0xFE80, 0x02, 0x02, 0xFF80, 0xFEE0, 0x03, 0x03, 0xFFB0, 0xFEF0, 0x04, 0x00,
            0xFF70, 0xFE90, 0x05, 0x01, 0xFF40, 0xFEC0, 0x06, 0x02, 0xFFA0, 0xFED0, 0x07, 0x03,
            0xFF50, 0xFF00, 0x08, 0x00, 0xFF80, 0xFE90, 0x09, 0x01, 0xFF90, 0xFEE0, 0x0A, 0x02,
            0xFF80, 0xFEF0, 0x0B, 0x03, 0xFFA0, 0xFEE0, 0x0C, 0x00, 0xFF60, 0xFEA0, 0x0D, 0x01,
            0xFF70, 0xFEE0, 0x0E, 0x02, 0xFFA0, 0xFEB0, 0x0F, 0x03, 0xFF90, 0xFE90, 0x10, 0x00,
            0xFF60, 0xFEF0, 0x11, 0x01, 0xFF60, 0xFE80, 0x12, 0x02, 0xFF80, 0xFED0, 0x13, 0x03,
            0xFF90, 0xFEE0, 0x14, 0x00, 0xFFA0, 0xFEA0, 0x05, 0x01, 0xFF80, 0xFEF0, 0x06, 0x02,
            0xFF60, 0xFF00, 0x07, 0x03, 0xFF40, 0xFE80, 0x08, 0x00, 0xFF50, 0xFEA0, 0x09, 0x01,
            0xFF80, 0xFED0, 0x0A, 0x02, 0xFFA0, 0xFEB0, 0x0B, 0x03, 0xFF80, 0xFEE0, 0x0C, 0x00,
            0xFF90, 0xFEC0, 0x0D, 0x01, 0xFF70, 0xFE80, 0x0E, 0x02,
        ).let { a -> IntArray(a.size) { if (it % 4 < 2) a[it].toShort().toInt() else a[it] } }
    }
}
