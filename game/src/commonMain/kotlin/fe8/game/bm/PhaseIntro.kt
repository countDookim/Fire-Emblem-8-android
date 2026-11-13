package fe8.game.bm

import fe8.game.RomView
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChild
import gbafe.render.BgHardware
import gbafe.render.HBlankHandler
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The phase-change banner of src/phasechangefx.c: the flash across the middle of the map that
 * names whose phase begins (player, enemy or other). `ProcScr_PhaseIntro` is started as a
 * blocking child of `gProc_BMapMain` (src/bm.c, label 9, after `BmMain_ChangePhase`), so the
 * turn does not go on until the banner is gone. It ends at once when the new phase has no unit
 * that can act.
 *
 * The banner is four procs: a text proc (the name tiles on BG0 in rows 9..11, drifting and
 * fading in and out, with a stretching sprite child), a squares proc (a diagonal wave of
 * squares on BG1 that grows over the map and then shrinks away), and a blend box proc (a WIN0
 * band that hides BG1 and narrows to the text rows, plus the blend weight of the squares).
 *
 * The three `PhaseIntroVMatch*` VCount interrupts become one HBlank handler, [vMatchHandler]
 * (the blend registers per scanline band). Graphics are read from the ROM at runtime.
 * Sound and the link arena check go through [hooks]. The link arena has its own caller
 * (`ProcScr_LinkArenaPhaseIntro`, src/sio_uiutils.c) that reuses the three child scripts.
 */
public class PhaseIntro(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val units: UnitTable,
    private val rom: RomView,
    private val hw: BgHardware,
    private val oam: OamBuffer,
    private val sprites: SpriteLayers,
) {
    /** Services from other decomp files; the defaults do nothing. */
    public class Hooks {
        /**
         * `CheckInLinkArena`: whether this is a link arena battle, where the text sits 8 pixels
         * further left than in the story chapters.
         */
        public var checkInLinkArena: () -> Boolean = { false }

        /** `GetCurrentBgmSong`: the song playing now. */
        public var currentBgmSong: () -> Int = { 0 }

        /** `GetCurrentMapMusicIndex`: the song the chapter's map is meant to play. */
        public var currentMapMusicIndex: () -> Int = { 0 }

        /** `Sound_FadeOutBGM`: fades the music out (the banner passes speed 4). */
        public var fadeOutBgm: (Int) -> Unit = {}

        /** `PlaySoundEffect`: the banner sound ([SONG_73]). */
        public var playSoundEffect: (Int) -> Unit = {}

        /** `StartMapSongBgm`: restarts the map music once the banner has finished. */
        public var startMapSongBgm: () -> Unit = {}
    }

    /** Host services. */
    public val hooks: Hooks = Hooks()

    /**
     * `struct PhaseIntroSubProc` past the header (src/phasechangefx.h): `timer` (s16, 0x4C) and
     * `stat_index` (s16, 0x4E), shared by the four child scripts.
     */
    public class SubState {
        /** `timer`: frame counter of the current stage; what it counts differs per script. */
        public var timer: Int = 0

        /** `stat_index`: stage of the banner sprite stretch (0 grow, 1 hold, 2 shrink). */
        public var statIndex: Int = 0
    }

    /** The [SubState] of the banner sub-proc [proc] (its `state`). */
    private fun stateOf(proc: Proc): SubState = proc.state as SubState

    /** Truncates [value] to a signed 16-bit number, as the C `s16` fields do. */
    private fun wrapS16(value: Int): Int = value.toShort().toInt()

    /** `gProcScr_PhaseIntroUnk` (0x0859AEE8): the banner sprite, a child of the text proc. */
    public val unkScript: ProcScript = ProcScript(
        "gProcScr_PhaseIntroUnk",
        listOf(
            call { unkInit(it) },
            repeat { unkLoop(it) },
            end(),
        ),
        ::SubState,
    )

    /**
     * `gProcScr_PhaseIntroText` (0x0859AEF8): fade in the BG0 text for 16 frames, hold 30 frames,
     * fade it out for 16 frames, then clear BG0.
     */
    public val textScript: ProcScript = ProcScript(
        "gProcScr_PhaseIntroText",
        listOf(
            call { initText(it) },
            sleep(0x06),
            call { textPutText(it) },
            startChild { unkScript },
            repeat { textInLoop(it) },
            sleep(0x1E),
            repeat { textOutLoop(it) },
            call { clearText(it) },
            end(),
        ),
        ::SubState,
    )

    /** `gProcScr_PhaseIntroSquares` (0x0859AF40): squares in (30 frames), then out (36). */
    public val squaresScript: ProcScript = ProcScript(
        "gProcScr_PhaseIntroSquares",
        listOf(
            call { squaresInit(it) },
            repeat { squaresInLoop(it) },
            repeat { squaresOutLoop(it) },
            end(),
        ),
        ::SubState,
    )

    /**
     * `gProcScr_PhaseIntroBlendBox` (0x0859AF60): narrows the WIN0 band (28 frames), then widens
     * it again (33 frames).
     */
    public val blendBoxScript: ProcScript = ProcScript(
        "gProcScr_PhaseIntroBlendBox",
        listOf(
            call { blendBoxInit(it) },
            repeat { blendBoxInLoop(it) },
            repeat { blendBoxOutLoop(it) },
            end(),
        ),
        ::SubState,
    )

    /**
     * `ProcScr_PhaseIntro` (0x0859AF80): ends at once if no unit can act, loads the graphics,
     * starts the three child procs, sets up the window and blend, waits for the children to
     * finish, then restarts the map music.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_PhaseIntro",
        listOf(
            call { endIfNoUnits(it) },
            call { initGraphics() },
            startChild { textScript },
            startChild { squaresScript },
            startChild { blendBoxScript },
            call { initDisp() },
            repeat { waitForEnd(it) },
            call { hooks.startMapSongBgm() },
            end(),
        ),
    )

    /** Starts [script] as a child of [parent]. */
    public fun start(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Transcribed from src/phasechangefx.c: PhaseIntroVMatchHi/Mid/Lo (not verified: the
     * reference dumps hold the registers only after the frame)
     *
     * One scanline handler for the three VCount interrupts. Lines 0..71 and 96..159 blend BG1
     * (the squares) over BG2/BG3/OBJ/backdrop with `altBlendBCa/Cb`; lines 72..95, the three tile
     * rows of the banner text (BG0 rows 9..11), blend BG0 (the text) over all the others with
     * `altBlendACa/Cb`. So the text fades by its own weights while the squares fade by theirs.
     */
    public val vMatchHandler: HBlankHandler = HBlankHandler { vcount, io ->
        // HBlank writes take effect on the next line; VCount interrupts switch at the line start.
        val nextLine = (vcount + 1) % 228
        val middle = nextLine in VMATCH_MID until VMATCH_LO
        val bldcnt = if (middle) BLDCNT_MID else BLDCNT_OUTER
        val coeffA = if (middle) bm.altBlendACa else bm.altBlendBCa
        val coeffB = if (middle) bm.altBlendACb else bm.altBlendBCb
        io[REG_BLDCNT] = bldcnt.toByte()
        io[REG_BLDCNT + 1] = (bldcnt shr 8).toByte()
        io[REG_BLDALPHA] = coeffA.toByte()
        io[REG_BLDALPHA + 1] = coeffB.toByte()
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_PutText
     *
     * Puts the 0x60 banner name tiles (3 rows of 32 map entries; the image holds the name of the
     * phase's faction) on BG0 from row 9, i.e. screen lines 72..95.
     */
    public fun textPutText(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        val tileMap = hw.mapBuffer(0)
        for (i in 0 until 0x60) {
            tileMap[9 * 32 + i] = tileRef(BGCHR_PHASE_CHANGE_NAME + i, 0).toShort()
        }
        hw.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /**
     * Transcribed from src/phasechangefx.c: PhaseIntroInitText (not verified: sound only)
     *
     * Fades the music out if it is not the chapter's map song (the 4 is the fade speed; its
     * scale is unknown), plays the banner sound and starts the 16-frame text fade-in timer.
     */
    public fun initText(proc: Proc) {
        if (hooks.currentBgmSong() != hooks.currentMapMusicIndex()) hooks.fadeOutBgm(4)
        hooks.playSoundEffect(SONG_73)
        stateOf(proc).timer = 15
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_InLoop
     *
     * Fades the text in: the BG0 scroll eases (`INTERPOLATE_RCUBIC`) from -8 down to -0x1C (link
     * arena: 0 down to -0x14), the text drifting right, over 16 frames while `altBlendACa` rises
     * and `altBlendACb` falls by 1 a frame
     * (text weight 0 -> 16 of 16). Breaks when the timer goes below 0.
     */
    public fun textInLoop(proc: Proc) {
        val inLinkArena = hooks.checkInLinkArena()
        val endX = if (inLinkArena) 0 else -8
        val startX = if (inLinkArena) -0x14 else -0x1C
        val state = stateOf(proc)
        val x = Interpolate.interpolate(Interpolate.RCUBIC, startX, endX, state.timer, 0x10)
        hw.lcd.setBgPosition(0, x, 0)
        bm.altBlendACa = (bm.altBlendACa + 1) and 0xFF
        bm.altBlendACb = (bm.altBlendACb - 1) and 0xFF
        state.timer = wrapS16(state.timer - 1)
        if (state.timer < 0) {
            state.timer = 15
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_OutLoop
     *
     * Fades the text out: the BG0 scroll eases (`INTERPOLATE_CUBIC`) from -0x1C down to -0x38
     * (link arena: -0x14 down to -0x30), the text drifting further right, over 16 frames while
     * the text weights fall back to 0 / 16. Breaks
     * when the timer goes below 0.
     */
    public fun textOutLoop(proc: Proc) {
        val inLinkArena = hooks.checkInLinkArena()
        val endX = if (inLinkArena) -0x14 else -0x1C
        val startX = if (inLinkArena) -0x30 else -0x38
        val state = stateOf(proc)
        val x = Interpolate.interpolate(Interpolate.CUBIC, startX, endX, state.timer, 0x10)
        hw.lcd.setBgPosition(0, x, 0)
        bm.altBlendACa = (bm.altBlendACa - 1) and 0xFF
        bm.altBlendACb = (bm.altBlendACb + 1) and 0xFF
        state.timer = wrapS16(state.timer - 1)
        if (state.timer < 0) {
            state.timer = 15
            engine.breakProc(proc)
        }
    }

    /** Ported from src/phasechangefx.c: PhaseIntroClearText: erases the banner text from BG0. */
    public fun clearText(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        hw.fill(hw.mapBuffer(0), 0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /** Ported from src/phasechangefx.c: PhaseIntroUnk_Init: starts at stage 0, frame 0. */
    public fun unkInit(proc: Proc) {
        val state = stateOf(proc)
        state.timer = 0
        state.statIndex = 0
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroUnk_Loop
     *
     * Drives the banner sprite, a 240x32 band of five affine parts at screen y 0x44 (the name
     * for this proc and its image, `Unk`, is not known in the decomp). `value` is its height
     * scale in 1/256ths: stage 0 grows it from 0x10 to 0x100 over 16 frames
     * (`INTERPOLATE_RSQUARE`), stage 1 holds 0x100 for 31 frames, stage 2 shrinks it back to
     * 0x10 over 16 frames (`INTERPOLATE_CUBIC`) and breaks. Only the vertical scale (`pd`)
     * changes. The sprite is drawn again every frame.
     */
    public fun unkLoop(proc: Proc) {
        val state = stateOf(proc)
        var value = 0
        when (state.statIndex) {
            0 -> {
                value = Interpolate.interpolate(Interpolate.RSQUARE, 0x10, 0x100, state.timer, 0xF)
                if (state.timer > 0xE) {
                    state.timer = 0
                    state.statIndex++
                } else {
                    state.timer++
                }
            }
            1 -> {
                value = 0x100
                if (state.timer <= 0x1D) {
                    state.timer++
                } else {
                    state.timer = 0
                    state.statIndex++
                }
            }
            2 -> {
                value = Interpolate.interpolate(Interpolate.CUBIC, 0x100, 0x10, state.timer, 0xF)
                if (state.timer <= 0xE) state.timer++ else engine.breakProc(proc)
            }
            else -> {}
        }
        // The C passes Div(COS(0) * 0x10, 0x100) etc.; COS(0) = 0x1000 and SIN(0) = 0 (gSinLookup
        // scale 0x1000), so pa = 0x100 (no horizontal scale), pb = pc = 0, pd = 0x10000 / value.
        oam.setObjAffine(0, 0x100, 0, 0, 0x10000 / value)
        sprites.putExt(2, 0, 0x44, bannerObject, 0x2600)
    }

    /** Ported from src/phasechangefx.c: PhaseIntroSquares_Init: the wave starts at frame 4. */
    public fun squaresInit(proc: Proc) {
        stateOf(proc).timer = 4
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroSquares_InLoop
     *
     * Fills BG1 (15 x 10 blocks of 2x2 map entries, the whole screen) with a diagonal wave of
     * squares: each block's size step `squareOffset` (0..0x10, even) rises as `timer` grows, the
     * wave moving from the bottom left to the top right. Breaks when `timer` reaches 0x22 (30
     * frames from the start value 4).
     */
    public fun squaresInLoop(proc: Proc) {
        val state = stateOf(proc)
        val tileMap = hw.mapBuffer(1)
        for (y in 10 - 1 downTo 0) {
            for (x in 15 - 1 downTo 0) {
                var squareOffset = (x - state.timer) + (0x15 - y)
                if (squareOffset > 0x10) squareOffset = 0x10
                if (squareOffset < 0) squareOffset = 0
                squareOffset = (0x10 - squareOffset) and 0xFE
                putSquare(tileMap, x * 2, y * 2, squareOffset, 0x00, 0x01, 0x20, 0x21, 0)
            }
        }
        state.timer = wrapS16(state.timer + 1)
        hw.enableBgSyncByMask(BG1_SYNC_BIT)
        if (state.timer == 0x22) {
            state.timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroSquares_OutLoop
     *
     * Same grid as the in loop, but the step falls from 0x10 to 0 and the blocks are drawn
     * mirrored left to right, so the squares shrink away; breaks when `timer` reaches 0x24
     * (36 frames from 0).
     */
    public fun squaresOutLoop(proc: Proc) {
        val state = stateOf(proc)
        val tileMap = hw.mapBuffer(1)
        for (y in 10 - 1 downTo 0) {
            for (x in 15 - 1 downTo 0) {
                var squareOffset = (1 - state.timer) + (10 + x) + (10 - y)
                if (squareOffset > 0x10) squareOffset = 0x10
                if (squareOffset < 0) squareOffset = 0
                squareOffset = squareOffset and 0xFE
                putSquare(tileMap, x * 2, y * 2, squareOffset, 0x01, 0x00, 0x21, 0x20, TILE_HFLIP)
            }
        }
        state.timer = wrapS16(state.timer + 1)
        hw.enableBgSyncByMask(0x2)
        if (state.timer == 0x24) {
            state.timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Writes one 2x2 block of map entries at ([x], [y]) on [tileMap] (32 entries a row): the four
     * characters of the square of size step [squareOffset] in the squares image, each entry with
     * [flipBits] added (`TILE_HFLIP` for the out loop, which also swaps left and right).
     */
    private fun putSquare(
        tileMap: ShortArray,
        x: Int,
        y: Int,
        squareOffset: Int,
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        flipBits: Int,
    ) {
        val base = BGCHR_PHASE_CHANGE_SQUARES + squareOffset
        tileMap[x + y * 32] = (tileRef(base + topLeft, 0) + flipBits).toShort()
        tileMap[x + 1 + y * 32] = (tileRef(base + topRight, 0) + flipBits).toShort()
        tileMap[x + (y + 1) * 32] = (tileRef(base + bottomLeft, 0) + flipBits).toShort()
        tileMap[x + 1 + (y + 1) * 32] = (tileRef(base + bottomRight, 0) + flipBits).toShort()
    }

    /** Ported from src/phasechangefx.c: PhaseIntroBlendBox_Init: starts at frame 4. */
    public fun blendBoxInit(proc: Proc) {
        stateOf(proc).timer = 4
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroBlendBox_InLoop
     *
     * Narrows the WIN0 band: it spans screen lines `8 + yoff` to `0xA0 - yoff` (`-0x60 - yoff`
     * wraps in the u8 register), with `yoff` easing from 16 to 60 (`INTERPOLATE_RCUBIC`) so the
     * band shrinks from lines 24..144 to 68..100. Inside the band BG1 (the squares) is off
     * (see [initDisp]). Also sets the squares' blend weights `altBlendBCa` = 0..7 and
     * `altBlendBCb` = 16 - that. Breaks when `timer` reaches 0x20 (28 frames from 4).
     */
    public fun blendBoxInLoop(proc: Proc) {
        val state = stateOf(proc)
        val boxYOffset = Interpolate.interpolate(Interpolate.RCUBIC, 16, 60, state.timer, 0x20)
        hw.lcd.setWin0Box(0, 8 + boxYOffset, DISPLAY_WIDTH, -0x60 - boxYOffset)
        val blendAmount = Interpolate.interpolate(Interpolate.LINEAR, 0, 7, state.timer, 0x20)
        bm.altBlendBCa = blendAmount and 0xFF
        bm.altBlendBCb = (0x10 - blendAmount) and 0xFF
        state.timer = wrapS16(state.timer + 1)
        if (state.timer == 0x20) engine.breakProc(proc)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroBlendBox_OutLoop
     *
     * Same band and blend weights as the in loop, run backwards: `timer` counts down from 0x20
     * (where the in loop left it) to -1, `yoff` easing from 60 to 0, so the band widens to lines
     * 8..160 again. Breaks when the timer goes below 0.
     */
    public fun blendBoxOutLoop(proc: Proc) {
        val state = stateOf(proc)
        val boxYOffset = Interpolate.interpolate(Interpolate.RCUBIC, 0, 60, state.timer, 0x20)
        hw.lcd.setWin0Box(0, 8 + boxYOffset, DISPLAY_WIDTH, -0x60 - boxYOffset)
        val blendAmount = Interpolate.interpolate(Interpolate.LINEAR, 0, 7, state.timer, 0x20)
        bm.altBlendBCa = blendAmount and 0xFF
        bm.altBlendBCb = (0x10 - blendAmount) and 0xFF
        state.timer = wrapS16(state.timer - 1)
        if (state.timer < 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_EndIfNoUnits
     *
     * Skips the banner when the faction whose phase begins has no unit that can act
     * (`GetPhaseAbleUnitCount` is 0; the C compares with `FACTION_BLUE`, which is 0 only by
     * coincidence of value).
     */
    public fun endIfNoUnits(proc: Proc) {
        if (units.getPhaseAbleUnitCount(playSt.faction) == FACTION_BLUE) engine.end(proc)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_InitGraphics
     * (verified for the player faction only; the enemy and other branches are not compared)
     *
     * Loads the banner images: the sprite image `Img_PhaseChangeUnk` to OBJ character 0x200
     * (BG character 0xA00, the same VRAM), the squares to BG character 0x100 and the name of the
     * faction in `gPlaySt.faction` to BG character 0x140, with that faction's palette copied to BG
     * palette bank 5 (`BGPAL_PHASE_CHANGE`) and to bank 18 (OBJ bank 2, used by the sprite).
     */
    public fun initGraphics() {
        rom.decompressInto(IMG_UNK, hw.vram, chrOffset(0xA00))
        rom.decompressInto(IMG_SQUARES, hw.vram, chrOffset(BGCHR_PHASE_CHANGE_SQUARES))
        hw.lcd.setBgPosition(0, 0, 0)
        hw.lcd.setBgPosition(1, 0, 0)
        hw.lcd.setBgPosition(2, 0, 0)
        val (imageAddr, paletteAddr) = when (playSt.faction) {
            FACTION_BLUE -> IMG_PLAYER to PAL_PLAYER
            FACTION_RED -> IMG_ENEMY to PAL_ENEMY
            FACTION_GREEN -> IMG_OTHER to PAL_OTHER
            else -> return
        }
        rom.decompressInto(imageAddr, hw.vram, chrOffset(BGCHR_PHASE_CHANGE_NAME))
        val colors = rom.pal(paletteAddr, 16)
        colors.copyInto(hw.palette, BGPAL_PHASE_CHANGE * 16)
        colors.copyInto(hw.palette, 18 * 16)
        hw.enablePaletteSync()
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_InitDisp
     *
     * Window 0 over the whole screen with BG1 off inside it and every layer on outside, alpha
     * blending enabled in both, the initial `altBlend*` weights (all layers 0 / 16, i.e. the
     * squares and the text start invisible) and BG1 as the first blend target; installs
     * [vMatchHandler] as the VCount interrupt chain (`SetInterrupt_LCDVCountMatch`).
     */
    public fun initDisp() {
        val lcd = hw.lcd
        lcd.setWinEnable(1, 0, 0)
        lcd.setWin0Box(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT)
        lcd.setWin0Layers(1, 0, 1, 1, 1)
        lcd.setWOutLayers(1, 1, 1, 1, 1)
        lcd.setWincntBit(WIN0_ENABLE_BLEND, true)
        lcd.setWincntBit(WOUT_ENABLE_BLEND, true)
        bm.altBlendBCa = 0
        bm.altBlendBCb = 0x10
        bm.altBlendACa = 0
        bm.altBlendACb = 0x10
        lcd.setBlendConfig(1, bm.altBlendBCa, bm.altBlendBCb, 0)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 1)
        hw.hblank.setPrimary(vMatchHandler)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_WaitForEnd
     * (the handler removal is not compared)
     *
     * Each frame applies the squares' blend weights; once the text, squares and blend box procs
     * are all gone clears BG0 and BG1, removes the blend handler, resets the BG0..BG2 scroll and
     * breaks, which lets the parent go on to [Hooks.startMapSongBgm].
     */
    public fun waitForEnd(proc: Proc) {
        hw.lcd.setBlendConfig(1, bm.altBlendBCa, bm.altBlendBCb, 0)
        if (engine.find(textScript) == null && engine.find(squaresScript) == null &&
            engine.find(blendBoxScript) == null
        ) {
            hw.fill(hw.mapBuffer(0), 0)
            hw.fill(hw.mapBuffer(1), 0)
            hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
            hw.hblank.setPrimary(null)
            hw.lcd.setBgPosition(0, 0, 0)
            hw.lcd.setBgPosition(1, 0, 0)
            hw.lcd.setBgPosition(2, 0, 0)
            engine.breakProc(proc)
        }
    }

    /**
     * The map-entry value for character [chrIndex] in palette bank [paletteBank], counted from
     * the banner's palette bank [BGPAL_PHASE_CHANGE].
     */
    private fun tileRef(chrIndex: Int, paletteBank: Int): Int =
        chrIndex + ((paletteBank + BGPAL_PHASE_CHANGE) shl 12)

    /** Byte offset in VRAM of the 4bpp character [chrIndex] (`BG_CHR_ADDR`, 0x20 bytes each). */
    private fun chrOffset(chrIndex: Int): Int = chrIndex * 0x20

    private companion object {
        /** `FACTION_BLUE`/`GREEN`/`RED`: the player, other (ally/NPC) and enemy factions. */
        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80

        /** `BGCHR_PHASE_CHANGE_SQUARES` and `_NAME`: BG character slots of the two images. */
        const val BGCHR_PHASE_CHANGE_SQUARES = 0x100
        const val BGCHR_PHASE_CHANGE_NAME = 0x140

        /** `BGPAL_PHASE_CHANGE`: BG palette bank of the banner. */
        const val BGPAL_PHASE_CHANGE = 5

        /** `BG0_SYNC_BIT` and `BG1_SYNC_BIT`: the `BG_EnableSyncByMask` bits. */
        const val BG0_SYNC_BIT = 1
        const val BG1_SYNC_BIT = 2

        /** `TILE_HFLIP`: the horizontal flip bit of a BG map entry. */
        const val TILE_HFLIP = 0x400

        /** `SONG_73`: the banner sound effect (the decomp has no name for song 0x73). */
        const val SONG_73 = 0x73
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160

        /** Bit numbers in WININ/WINOUT of the "blend enabled" flag of WIN0 and outside. */
        const val WIN0_ENABLE_BLEND = 5
        const val WOUT_ENABLE_BLEND = 21

        /** First scanline of the text band (BG0 row 9 * 8) and the first line after it. */
        const val VMATCH_MID = 72
        const val VMATCH_LO = 96

        /** IO offsets of `REG_BLDCNT` and `REG_BLDALPHA`. */
        const val REG_BLDCNT = 0x50
        const val REG_BLDALPHA = 0x52

        /**
         * `REG_BLDCNT` outside the text band (`PhaseIntroVMatchHi/Lo`): first target BG1, blend
         * effect, second targets BG2, BG3, OBJ and backdrop.
         */
        const val BLDCNT_OUTER = 0x3C42

        /**
         * `REG_BLDCNT` inside the text band (`PhaseIntroVMatchMid`): first target BG0, blend
         * effect, second targets BG1, BG2, BG3, OBJ and backdrop.
         */
        const val BLDCNT_MID = 0x3E41

        /** ROM addresses of the compressed banner images and palettes (`Img_PhaseChange*`). */
        const val IMG_UNK = 0x0859F020
        const val IMG_SQUARES = 0x085A06D8
        const val IMG_PLAYER = 0x0859F3F8
        const val PAL_PLAYER = 0x0859FA2C
        const val IMG_ENEMY = 0x0859FA4C
        const val PAL_ENEMY = 0x085A0068
        const val IMG_OTHER = 0x085A0088
        const val PAL_OTHER = 0x085A0698

        /**
         * `obj_0859AEC8`: the banner sprite, five affine parts side by side from x 0 (64x32 at x 0,
         * 64 and 128, 32x32 at 192, 16x32 at 224: 240 pixels wide), characters 0, 8, 0x10, 0x18,
         * 0x1C, relative to the base 0x2600 given to `putExt` (character 0x200, priority 1,
         * OBJ palette bank 2).
         */
        val bannerObject = SpriteObject(
            0x0859AEC8,
            shortArrayOf(
                5,
                0x4100.toShort(), 0xC000.toShort(), 0,
                0x4100.toShort(), 0xC040.toShort(), 0x8,
                0x4100.toShort(), 0xC080.toShort(), 0x10,
                0x0100.toShort(), 0x80C0.toShort(), 0x18,
                0x8100.toShort(), 0x80E0.toShort(), 0x1C,
            ),
        )
    }
}
