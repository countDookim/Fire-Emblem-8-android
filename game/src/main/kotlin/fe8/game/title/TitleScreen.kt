package fe8.game.title

import fe8.game.GameControl
import fe8.game.opanim.OpAnimHs
import fe8.game.opanim.blendPalette
import gbafe.core.math.Interpolate
import gbafe.data.Decompress
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
import gbafe.render.BgAffine
import gbafe.render.BgHardware
import gbafe.render.FadeCore
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The title screen `gProcScr_TitleScreen` (decomp `src/titlescreen.c`; sound calls are not
 * modelled). Started by [fe8.game.GameControl].
 */
public class TitleScreen(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware. */
    public val hardware: BgHardware,
    /** ROM graphics. */
    public val gfx: Gfx,
    /** `gObject_32x32` (the blue light sprite). */
    public val object32x32: SpriteObject? = null,
    /** The sprite lists (`src/ctc.c`). */
    public val sprites: SpriteLayers = SpriteLayers(),
    /** `gObject_8x8` (the light bubbles). */
    public val object8x8: SpriteObject? = null,
    /** `gSprite_Title_SacredStonesBanner`. */
    public val bannerSprite: SpriteObject? = null,
    /**
     * `gObject_16x16` (the TM sign), `gSprite_Title_FireEmblemLogo`, `_PressStart` and
     * `_CopyrightInfo`.
     */
    public val menuSprites: List<SpriteObject> = emptyList(),
    /** `gPal_08AADBE8`: the "Press Start" glow colours (16). */
    public val glowPal: ShortArray = ShortArray(16),
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus = KeyStatus(),
    /** The game control proc's owner, for `SetNextGameActionId` (null: not set). */
    public val gameControl: GameControl? = null,
    /** Screen fades (`StartFadeCore`). */
    public val fadeCore: FadeCore = FadeCore(engine, hardware),
) {
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

    /** `struct TitleScreenProc` fields. */
    public class State {
        /** `mode`: 1 after the op anim played through (runs the intro fx), else 0. */
        public var mode: Int = 0

        /** `timer`. */
        public var timer: Int = 0

        /** `timer_idle`. */
        public var timerIdle: Int = 0

        /** `unk_30`: the last light explosion progress (0..63). */
        public var unk30: Int = 0
    }

    private val mapBytes = ByteArray(0x800)

    /** `gProcScr_TitleScreen` (partial, see the class doc). */
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
            call(ProcFunc { fadeCore.start(3, 0x20, it) { fadeCore.darkenEnd() } }), // sub_8013F40
            yield(),
            call(ProcFunc { engine.endAllChildren(it) }), // Title_EndAllProcChildren
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
     * Clears BG2, switches to mode 0 showing BG1 + OBJ with a 16/16 blend of OBJ over BG1, and
     * blacks out BG palette 14.
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
     * Fades the main background palette in from black over 48 steps while a blue light sprite
     * flickers into a red and a blue orb that split apart. Breaks at step 48. (The BGM start at
     * step 20 is not modelled.)
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
     * Shows BG0, BG1 and OBJ with an alpha blend of BG0 (coefficient 0) over BG1. (Sound effect
     * 317 is not modelled.)
     *
     * Ported from src/titlescreen.c: Title_PrepareMainScreenWithExtendedBgm
     */
    public fun prepareMainScreen() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0100 or 0x0200 or 0x1000
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.setBlendTargetA(1, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 1, 0, 0, 0)
    }

    /**
     * Raises the BG0 blend coefficient from 0 to 13 over 12 steps. Breaks at step 12.
     *
     * Ported from src/titlescreen.c: Title_Loop_RevealGreenDragonFlashFx
     */
    public fun revealDragonFlash(proc: Proc) {
        val t = (proc.state as State).timer
        hardware.lcd.blendCoeffA = Interpolate.interpolate(Interpolate.LINEAR, 0, 13, t, 12)
        stepUntil(proc, 12)
    }

    /**
     * Fades BG palettes 2 and 14 to white over 12 steps. Breaks at step 12.
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
     * Loads the demon king image to BG2 (tiles 0x8000, palette 3).
     *
     * Ported from src/titlescreen.c: Title_LoadDemonKingOnBg2
     */
    public fun loadDemonKing() {
        val (tiles, map, pal) = gfx.demonKing
        Decompress.into(tiles.data, tiles.offset, hardware.vram, 0x8000)
        loadMap(map, 2, 0x3000)
        applyPalette(pal, 3, 1)
        hardware.enableBgSyncByMask(4)
    }

    /**
     * Shows only BG2, colour effect off.
     *
     * Ported from src/titlescreen.c: Title_ShowBg2
     */
    public fun showBg2() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0400
        lcd.bldcnt = lcd.bldcnt and 0xC0.inv()
    }

    /**
     * Shows BG1 and OBJ, alpha blend on.
     *
     * Ported from src/titlescreen.c: sub_80C5FB4
     */
    public fun showBg1Obj() {
        val lcd = hardware.lcd
        lcd.dispcnt = (lcd.dispcnt and 0x1F00.inv()) or 0x0200 or 0x1000
        lcd.bldcnt = (lcd.bldcnt and 0xC0.inv()) or 0x40
    }

    /**
     * Mode 1 with the zooming logo on BG2 (affine 256x256 at scale 0x40, palette 0), BG palettes
     * 14 and 15 white, BG0 back to tiles 0 / map 0x6800; shows BG0..2 and OBJ.
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
     * Zooms the logo from scale 32 to 256 over 16 steps. Breaks at step 16.
     *
     * Ported from src/titlescreen.c: Title_Loop_MainLogoZoom
     */
    public fun logoZoom(proc: Proc) {
        val t = (proc.state as State).timer
        logoAffine(Interpolate.interpolate(Interpolate.SQUARE, 32, 256, t, 16))
        stepUntil(proc, 16)
    }

    /**
     * BG2's affine for the logo: texture centre (0x78, 0x50) at screen (0x78, 0x50), scale
     * [scale] both ways, no rotation.
     *
     * Ported from src/titlescreen.c: sub_80C5548
     */
    public fun logoAffine(scale: Int) {
        BgAffine.set(hardware.lcd.affine, 0, 0x7800, 0x5000, 0x78, 0x50, scale, scale, 0)
    }

    /**
     * Alpha blend of nothing over BG0/BG1 at 16/16; resets `unk_30`. (Its `gGenericBuffer`
     * clear, `sub_80C60CC`, is not modelled: nothing reads that buffer here.)
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
     * Fades the blend and BG palettes 14/15 back from white over 24 steps, with the "Sacred
     * Stones" banner and the light bubbles flying out. Breaks at step 24.
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
     * Puts the 61 light bubbles (8x8, layer 4, semi-transparent) on their rays from the centre
     * at step [step] of 24.
     *
     * Ported from src/titlescreen.c: DrawTitleLightBubbleSprites
     */
    public fun drawLightBubbles(step: Int) {
        val obj = object8x8 ?: error("object8x8 not supplied")
        for (i in 0 until BUBBLES.size / 4) {
            val a = BUBBLES[4 * i]
            val b = BUBBLES[4 * i + 1]
            val c = BUBBLES[4 * i + 2]
            val m = RAYS[2 * c]
            val n = RAYS[2 * c + 1]
            val lin = Interpolate.LINEAR
            val x = Interpolate.interpolate(lin, (m * a) shr 8, (b * m) shr 8, step, 24) + 116
            val y = Interpolate.interpolate(lin, (n * a) shr 8, (b * n) shr 8, step, 24) + 56
            val tile = (BUBBLES[4 * i + 3] + 0x2A0) or 0x7400
            sprites.putExt(4, x and 0x1FF, (y and 0xFF) or 0x400, obj, tile)
        }
    }

    /** Breaks [proc] and resets its timer at step [last], else counts on. */
    private fun stepUntil(proc: Proc, last: Int) {
        val st = proc.state as State
        if (st.timer == last) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer++
        }
    }

    /** `gProcScr_SkipTitleFxKeyListener`: A, B or Start jumps the title proc to label 3. */
    public val skipListenerScript: ProcScript = ProcScript(
        "gProcScr_SkipTitleFxKeyListener",
        listOf(yield(), repeat(ProcFunc { skipListener(it) }), end()),
    )

    /**
     * Restarts the title screen (label 3) when A, B or Start is pressed.
     *
     * Ported from src/titlescreen.c: SkipTitleFx_Loop_KeyListener
     */
    public fun skipListener(proc: Proc) {
        if ((keys.newKeys and (A_BUTTON or B_BUTTON or START_BUTTON)) != 0) {
            engine.goto(proc.parent!!, 3)
        }
    }

    /**
     * Ends the skip listener.
     *
     * Ported from src/titlescreen.c: Title_EndSkipFxListener
     */
    public fun endSkipListener() {
        engine.end(engine.find(skipListenerScript))
    }

    /** `gProcScr_DrawTitleSprites` state (`unk_4c`, `unk_52` of its proc). */
    public class SpritesState {
        /** `unk_4c`: step counter (s16). */
        public var unk4c: Int = 0

        /** `unk_52`: glow phase (0..31). */
        public var unk52: Int = 0
    }

    /** `gProcScr_DrawTitleSprites`: the logo, banner, "Press Start" and copyright sprites. */
    public val drawSpritesScript: ProcScript = ProcScript(
        "gProcScr_DrawTitleSprites",
        listOf(
            call(ProcFunc { st(it).apply { unk4c = 0; unk52 = 0 } }), // DrawTitleSprites_Init
            repeat(ProcFunc { drawSprites(it) }),
            end(),
        ),
        newState = { SpritesState() },
    )

    private fun st(proc: Proc) = proc.state as SpritesState

    /**
     * Puts the title sprites and cycles the "Press Start" glow colour (OBJ palette 0 entry 8)
     * one step every 3 frames.
     *
     * Ported from src/titlescreen.c: DrawTitleSprites_Loop
     */
    public fun drawSprites(proc: Proc) {
        val st = st(proc)
        val (tm, logo, pressStart, copyright) = menuSprites
        sprites.putExt(0, 4, 48, logo, 0x2000)
        sprites.putExt(0, 220, 41, tm, 0x201E)
        sprites.putExt(2, 4, 1077, logo, 0x2080)
        sprites.putExt(1, 16, 85, bannerSprite ?: error("bannerSprite not supplied"), 0x31A0)
        sprites.putExt(1, 72, 124, pressStart, 0x1BA)
        sprites.putExt(1, 4, 148, copyright, 0x1180)
        if (st.unk4c % 3 == 0) st.unk52 = (st.unk52 + 1) and 0x1F
        val i = if ((st.unk52 and 0x10) != 0) st.unk52 and 0xF else (0x1F - st.unk52) and 0xF
        hardware.palette[0x108] = glowPal[i]
        hardware.enablePaletteSync()
        st.unk4c = (st.unk4c + 1).toShort().toInt()
    }

    /**
     * Mode 0, BG2 back to 256x256, shows BG0, BG1 and OBJ with the title's alpha blend (8/8 of
     * BG0/BG1 targets), and starts the title sprites.
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
     * Waits on the title: A or Start continues to the save menu (event return); after 815 steps
     * the class reel plays. (Sound effect 0x6D is not modelled.)
     *
     * Ported from src/titlescreen.c: Title_IDLE
     */
    public fun idle(proc: Proc) {
        val st = proc.state as State
        st.timerIdle++
        st.timer = (st.timer + 1) and 0x3F
        if ((keys.newKeys and (A_BUTTON or START_BUTTON)) != 0) {
            gameControl?.setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
            engine.breakProc(proc)
        } else if (st.timerIdle == 815) {
            gameControl?.setNextGameActionId(GameControl.GAME_ACTION_CLASS_REEL)
            engine.breakProc(proc)
        }
    }

    /**
     * Ends the children and the skip listener, black backdrop, mode 0, all layers off; restarts
     * at label 4. (The main theme restart is not modelled.)
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
    }

    /**
     * Loads the intro fx: BG0 moves to tiles 0xC000 / map 0x7800, two BG2-area images and maps,
     * two BG0 layers, the glowing orb and light bubble OBJs; white backdrop. Breaks at step 4.
     *
     * Ported from src/titlescreen.c: Title_SetupSpecialEffectGraphics
     */
    public fun setupSpecialEffectGraphics(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val f = gfx.fx
        val p = gfx.fxPals
        when (st.timer) {
            0 -> {
                hw.setTileDataOffset(0, 0xC000)
                hw.setMapDataOffset(0, 0x7800)
                Decompress.into(f[0].data, f[0].offset, hw.vram, 0x8000)
                Decompress.into(f[1].data, f[1].offset, hw.vram, 0xB000)
                applyPalette(p[0], 0, 1)
                hw.palette[0] = 0x7FFF
            }
            1 -> {
                Decompress.into(f[2].data, f[2].offset, hw.vram, 0xC000)
                loadMap(f[3], 0, 0x1000)
                applyPalette(p[1], 1, 1)
                hw.enableBgSyncByMask(1)
            }
            2 -> {
                Decompress.into(f[4].data, f[4].offset, hw.vram, 0xD000)
                loadMap(f[5], 0, 0x2080)
                applyPalette(p[2], 2, 1)
            }
            3 -> {
                Decompress.into(f[6].data, f[6].offset, hw.vram, 0x14400)
                applyPalette(p[3], 0x14, 3)
                Decompress.into(f[7].data, f[7].offset, hw.vram, 0x15400)
                applyPalette(p[4], 0x17, 1)
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
     * Switches to mode 1 with BG2 affine (256x256, no wrap) at scale 8 and only BG2 + OBJ shown;
     * brightness fade (BLDY 16 on all layers), black backdrop.
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
     * Zooms and turns BG2 over 90 steps with a blue light sprite once zoomed past 1x; fades in
     * from white, then (from step 68) out to black. Breaks at step 90.
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
     * BG2's affine: texture and screen centre (0x100, 0x100) / (0x78, 0x50), scale [scale] both
     * ways, rotation [angle] (256ths of a turn).
     *
     * Ported from src/opanim-main.c: BgAffineSetOpAnim
     */
    public fun bgAffineSetOpAnim(scale: Int, angle: Int) {
        BgAffine.set(hardware.lcd.affine, 0, 0x10000, 0x10000, 0x78, 0x50, scale, scale, angle shl 8)
    }

    /**
     * Starts the title screen as a blocking child of [parent] with [mode] (1 after the op anim
     * played through). `StartTitleScreen_WithMusic` / `_FlagFalse` use mode 0 (BGM not modelled).
     *
     * Ported from src/titlescreen.c: StartTitleScreen_FlagTrue
     */
    public fun start(parent: Proc, mode: Int): Proc? =
        engine.startBlocking(script, parent)?.also { (it.state as State).mode = mode }

    /**
     * Resets the display: mode 0, all layers and windows off, BG offsets/sizes/priorities, maps
     * cleared, default colour effects.
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
     * Loads the main background, dragon, logo and "Press Start" graphics over 5 steps; then
     * breaks.
     *
     * Ported from src/titlescreen.c: Title_SetupMainGraphics
     */
    public fun setupMainGraphics(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val g = gfx
        when (st.timer) {
            0 -> Decompress.into(g.background[0].data, g.background[0].offset, hw.vram, 0)
            1 -> {
                Decompress.into(g.background[1].data, g.background[1].offset, hw.vram, 0x3000)
                loadMap(g.background[2], 1, 0xE000)
                applyPalette(g.backgroundPal, 0xE, 1)
                hw.palette[0] = if (st.mode != 0) 0x7FFF else 0
            }
            2 -> {
                Decompress.into(g.dragon.first.data, g.dragon.first.offset, hw.vram, 0x5000)
                loadMap(g.dragon.second, 0, 0xF280)
                applyPalette(g.dragonPal, 0xF, 1)
                hw.enableBgSyncByMask(1)
            }
            3 -> Decompress.into(g.logo.data, g.logo.offset, hw.vram, 0x10000)
            4 -> {
                Decompress.into(g.pressStart.data, g.pressStart.offset, hw.vram, 0x13000)
                applyPalette(g.pressStartPal, 0x10, 4)
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer++
    }

    /**
     * Mode 1 jumps to the intro fx (label 0); otherwise starts the title sprites proc.
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
     * Shows BG0, BG1 and OBJ with an alpha blend of BG0 over BG0/BG1.
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

    /** Decompresses a 0x280-entry map into BG [bg]'s buffer and adds [add] to every entry. */
    private fun loadMap(blob: OpAnimHs.Blob, bg: Int, add: Int) {
        val n = Decompress.into(blob.data, blob.offset, mapBytes, 0)
        val map = hardware.mapBuffer(bg)
        for (i in 0 until minOf(n / 2, map.size)) {
            map[i] = ((mapBytes[2 * i].toInt() and 0xFF) or ((mapBytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        if (add != 0) for (i in 0 until 0x280) map[i] = (map[i] + add).toShort()
    }

    /** `ApplyPalettes(src, bank, count)`: copies [count] palettes into the palette buffer. */
    private fun applyPalette(src: ShortArray, bank: Int, count: Int) {
        src.copyInto(hardware.palette, bank * 16, 0, 16 * count)
        hardware.enablePaletteSync()
    }

    private companion object {
        const val A_BUTTON = 0x1
        const val B_BUTTON = 0x2
        const val START_BUTTON = 0x8

        /** `gUnknown_08AA682C`: (x, y) direction of each ray (s8 pairs). */
        val RAYS: IntArray = intArrayOf(
            0x68, 0xC4, 0x6B, 0xCA, 0x6E, 0xCF, 0x70, 0xD5, 0x72, 0xDB, 0x74, 0xE1, 0x75, 0xE7,
            0x77, 0xED, 0x77, 0xF3, 0x78, 0xFA, 0x78, 0x00, 0x78, 0x06, 0x77, 0x0D, 0x77, 0x13,
            0x75, 0x19, 0x74, 0x1F, 0x72, 0x25, 0x70, 0x2B, 0x6E, 0x31, 0x6B, 0x36, 0x68, 0x3C,
        ).map { it.toByte().toInt() }.toIntArray()

        /** `gUnknown_08AA6858` without its terminator: start, end distance (s16), ray, tile. */
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
