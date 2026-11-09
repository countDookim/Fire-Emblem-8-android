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
 * The "Player Phase" / "Enemy Phase" / "Other Phase" banner of src/phasechangefx.c:
 * `ProcScr_PhaseIntro` and its children (text, squares, blend box, sprite).
 *
 * The three `PhaseIntroVMatch*` VCount interrupts become one HBlank handler, [vMatchHandler]
 * (the blend registers per scanline band). Graphics are read from the ROM at runtime.
 * Sound and the link arena check go through [hooks].
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
    /** Services outside the ported file; the defaults do nothing. */
    public class Hooks {
        /** `CheckInLinkArena`. */
        public var checkInLinkArena: () -> Boolean = { false }

        /** `GetCurrentBgmSong`. */
        public var currentBgmSong: () -> Int = { 0 }

        /** `GetCurrentMapMusicIndex`. */
        public var currentMapMusicIndex: () -> Int = { 0 }

        /** `Sound_FadeOutBGM`. */
        public var fadeOutBgm: (Int) -> Unit = {}

        /** `PlaySoundEffect`. */
        public var playSoundEffect: (Int) -> Unit = {}

        /** `StartMapSongBgm`. */
        public var startMapSongBgm: () -> Unit = {}
    }

    /** Host services. */
    public val hooks: Hooks = Hooks()

    /**
     * `struct PhaseIntroSubProc` past the header: `timer` (s16, 0x4C), `stat_index`
     * (s16, 0x4E).
     */
    public class SubState {
        /** `timer`. */
        public var timer: Int = 0

        /** `stat_index`. */
        public var statIndex: Int = 0
    }

    private fun sub(proc: Proc): SubState = proc.state as SubState

    private fun s16(v: Int): Int = v.toShort().toInt()

    /** `gProcScr_PhaseIntroUnk` (0x0859AEE8). */
    public val unkScript: ProcScript = ProcScript(
        "gProcScr_PhaseIntroUnk",
        listOf(
            call { unkInit(it) },
            repeat { unkLoop(it) },
            end(),
        ),
        ::SubState,
    )

    /** `gProcScr_PhaseIntroText` (0x0859AEF8). */
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

    /** `gProcScr_PhaseIntroSquares` (0x0859AF40). */
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

    /** `gProcScr_PhaseIntroBlendBox` (0x0859AF60). */
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

    /** `ProcScr_PhaseIntro` (0x0859AF80). */
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
     * One scanline handler for the three VCount interrupts: lines 0..71 and 96.. blend BG1 over
     * BG2/BG3/OBJ/backdrop with `altBlendBCa/Cb`, lines 72..95 blend BG0 over the rest with
     * `altBlendACa/Cb`.
     */
    public val vMatchHandler: HBlankHandler = HBlankHandler { vcount, io ->
        // HBlank writes take effect on the next line; VCount interrupts switch at the line start.
        val nextLine = (vcount + 1) % 228
        val middle = nextLine in VMATCH_MID until VMATCH_LO
        val bldcnt = if (middle) BLDCNT_MID else BLDCNT_OUTER
        val ca = if (middle) bm.altBlendACa else bm.altBlendBCa
        val cb = if (middle) bm.altBlendACb else bm.altBlendBCb
        io[REG_BLDCNT] = bldcnt.toByte()
        io[REG_BLDCNT + 1] = (bldcnt shr 8).toByte()
        io[REG_BLDALPHA] = ca.toByte()
        io[REG_BLDALPHA + 1] = cb.toByte()
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_PutText
     *
     * Puts the 0x60 banner name tiles on BG0 from row 9.
     */
    public fun textPutText(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        val tm = hw.mapBuffer(0)
        for (i in 0 until 0x60) {
            tm[9 * 32 + i] = tileRef(BGCHR_PHASE_CHANGE_NAME + i, 0).toShort()
        }
        hw.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /** Transcribed from src/phasechangefx.c: PhaseIntroInitText (not verified: sound only) */
    public fun initText(proc: Proc) {
        if (hooks.currentBgmSong() != hooks.currentMapMusicIndex()) hooks.fadeOutBgm(4)
        hooks.playSoundEffect(SONG_73)
        sub(proc).timer = 15
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_InLoop
     *
     * Slides BG0 in and fades the text layer in; breaks when the timer runs out.
     */
    public fun textInLoop(proc: Proc) {
        val link = hooks.checkInLinkArena()
        val hi = if (link) 0 else -8
        val lo = if (link) -0x14 else -0x1C
        val s = sub(proc)
        val x = Interpolate.interpolate(Interpolate.RCUBIC, lo, hi, s.timer, 0x10)
        hw.lcd.setBgPosition(0, x, 0)
        bm.altBlendACa = (bm.altBlendACa + 1) and 0xFF
        bm.altBlendACb = (bm.altBlendACb - 1) and 0xFF
        s.timer = s16(s.timer - 1)
        if (s.timer < 0) {
            s.timer = 15
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroText_OutLoop
     *
     * Slides BG0 out and fades the text layer out; breaks when the timer runs out.
     */
    public fun textOutLoop(proc: Proc) {
        val link = hooks.checkInLinkArena()
        val hi = if (link) -0x14 else -0x1C
        val lo = if (link) -0x30 else -0x38
        val s = sub(proc)
        val x = Interpolate.interpolate(Interpolate.CUBIC, lo, hi, s.timer, 0x10)
        hw.lcd.setBgPosition(0, x, 0)
        bm.altBlendACa = (bm.altBlendACa - 1) and 0xFF
        bm.altBlendACb = (bm.altBlendACb + 1) and 0xFF
        s.timer = s16(s.timer - 1)
        if (s.timer < 0) {
            s.timer = 15
            engine.breakProc(proc)
        }
    }

    /** Ported from src/phasechangefx.c: PhaseIntroClearText */
    public fun clearText(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        hw.fill(hw.mapBuffer(0), 0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /** Ported from src/phasechangefx.c: PhaseIntroUnk_Init */
    public fun unkInit(proc: Proc) {
        val s = sub(proc)
        s.timer = 0
        s.statIndex = 0
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroUnk_Loop
     *
     * Drives the stretch of the banner sprite (timer, stat_index) and puts the sprite with its
     * affine matrix 0.
     */
    public fun unkLoop(proc: Proc) {
        val s = sub(proc)
        var value = 0
        when (s.statIndex) {
            0 -> {
                value = Interpolate.interpolate(Interpolate.RSQUARE, 0x10, 0x100, s.timer, 0xF)
                if (s.timer > 0xE) {
                    s.timer = 0
                    s.statIndex++
                } else {
                    s.timer++
                }
            }
            1 -> {
                value = 0x100
                if (s.timer <= 0x1D) {
                    s.timer++
                } else {
                    s.timer = 0
                    s.statIndex++
                }
            }
            2 -> {
                value = Interpolate.interpolate(Interpolate.CUBIC, 0x100, 0x10, s.timer, 0xF)
                if (s.timer <= 0xE) s.timer++ else engine.breakProc(proc)
            }
            else -> {}
        }
        // COS(0) = 0x1000 and SIN(0) = 0 (gSinLookup scale): pa = 0x1000 * 0x10 / 0x100.
        oam.setObjAffine(0, 0x100, 0, 0, 0x10000 / value)
        sprites.putExt(2, 0, 0x44, bannerObject, 0x2600)
    }

    /** Ported from src/phasechangefx.c: PhaseIntroSquares_Init */
    public fun squaresInit(proc: Proc) {
        sub(proc).timer = 4
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroSquares_InLoop
     *
     * Fills BG1 with the growing squares wave; breaks after 0x22 frames.
     */
    public fun squaresInLoop(proc: Proc) {
        val s = sub(proc)
        val tm = hw.mapBuffer(1)
        for (y in 10 - 1 downTo 0) {
            for (x in 15 - 1 downTo 0) {
                var v = (x - s.timer) + (0x15 - y)
                if (v > 0x10) v = 0x10
                if (v < 0) v = 0
                v = (0x10 - v) and 0xFE
                putSquare(tm, x * 2, y * 2, v, 0x00, 0x01, 0x20, 0x21, 0)
            }
        }
        s.timer = s16(s.timer + 1)
        hw.enableBgSyncByMask(BG1_SYNC_BIT)
        if (s.timer == 0x22) {
            s.timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroSquares_OutLoop
     *
     * Fills BG1 with the shrinking, flipped squares wave; breaks after 0x24 frames.
     */
    public fun squaresOutLoop(proc: Proc) {
        val s = sub(proc)
        val tm = hw.mapBuffer(1)
        for (iy in 10 - 1 downTo 0) {
            for (ix in 15 - 1 downTo 0) {
                var v = (1 - s.timer) + (10 + ix) + (10 - iy)
                if (v > 0x10) v = 0x10
                if (v < 0) v = 0
                v = v and 0xFE
                putSquare(tm, ix * 2, iy * 2, v, 0x01, 0x00, 0x21, 0x20, TILE_HFLIP)
            }
        }
        s.timer = s16(s.timer + 1)
        hw.enableBgSyncByMask(0x2)
        if (s.timer == 0x24) {
            s.timer = 0
            engine.breakProc(proc)
        }
    }

    private fun putSquare(
        tm: ShortArray,
        x: Int,
        y: Int,
        v: Int,
        tl: Int,
        tr: Int,
        bl: Int,
        br: Int,
        flip: Int,
    ) {
        val base = BGCHR_PHASE_CHANGE_SQUARES + v
        tm[x + y * 32] = (tileRef(base + tl, 0) + flip).toShort()
        tm[x + 1 + y * 32] = (tileRef(base + tr, 0) + flip).toShort()
        tm[x + (y + 1) * 32] = (tileRef(base + bl, 0) + flip).toShort()
        tm[x + 1 + (y + 1) * 32] = (tileRef(base + br, 0) + flip).toShort()
    }

    /** Ported from src/phasechangefx.c: PhaseIntroBlendBox_Init */
    public fun blendBoxInit(proc: Proc) {
        sub(proc).timer = 4
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroBlendBox_InLoop
     *
     * Opens the WIN0 box and raises the lower blend; breaks at timer 0x20.
     */
    public fun blendBoxInLoop(proc: Proc) {
        val s = sub(proc)
        val yoff = Interpolate.interpolate(Interpolate.RCUBIC, 16, 60, s.timer, 0x20)
        hw.lcd.setWin0Box(0, 8 + yoff, DISPLAY_WIDTH, -0x60 - yoff)
        val blend = Interpolate.interpolate(Interpolate.LINEAR, 0, 7, s.timer, 0x20)
        bm.altBlendBCa = blend and 0xFF
        bm.altBlendBCb = (0x10 - blend) and 0xFF
        s.timer = s16(s.timer + 1)
        if (s.timer == 0x20) engine.breakProc(proc)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntroBlendBox_OutLoop
     *
     * Closes the WIN0 box and lowers the lower blend; breaks when the timer goes below 0.
     */
    public fun blendBoxOutLoop(proc: Proc) {
        val s = sub(proc)
        val yoff = Interpolate.interpolate(Interpolate.RCUBIC, 0, 60, s.timer, 0x20)
        hw.lcd.setWin0Box(0, 8 + yoff, DISPLAY_WIDTH, -0x60 - yoff)
        val blend = Interpolate.interpolate(Interpolate.LINEAR, 0, 7, s.timer, 0x20)
        bm.altBlendBCa = blend and 0xFF
        bm.altBlendBCb = (0x10 - blend) and 0xFF
        s.timer = s16(s.timer - 1)
        if (s.timer < 0) engine.breakProc(proc)
    }

    /** Ported from src/phasechangefx.c: PhaseIntro_EndIfNoUnits */
    public fun endIfNoUnits(proc: Proc) {
        if (units.getPhaseAbleUnitCount(playSt.faction) == FACTION_BLUE) engine.end(proc)
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_InitGraphics
     * (verified for the player faction only; the enemy and other branches are not compared)
     *
     * Loads the banner images into BG VRAM and the faction's palette into banks 5 and 18.
     */
    public fun initGraphics() {
        rom.decompressInto(IMG_UNK, hw.vram, chrOffset(0xA00))
        rom.decompressInto(IMG_SQUARES, hw.vram, chrOffset(BGCHR_PHASE_CHANGE_SQUARES))
        hw.lcd.setBgPosition(0, 0, 0)
        hw.lcd.setBgPosition(1, 0, 0)
        hw.lcd.setBgPosition(2, 0, 0)
        val (img, pal) = when (playSt.faction) {
            FACTION_BLUE -> IMG_PLAYER to PAL_PLAYER
            FACTION_RED -> IMG_ENEMY to PAL_ENEMY
            FACTION_GREEN -> IMG_OTHER to PAL_OTHER
            else -> return
        }
        rom.decompressInto(img, hw.vram, chrOffset(BGCHR_PHASE_CHANGE_NAME))
        val colors = rom.pal(pal, 16)
        colors.copyInto(hw.palette, BGPAL_PHASE_CHANGE * 16)
        colors.copyInto(hw.palette, 18 * 16)
        hw.enablePaletteSync()
    }

    /**
     * Ported from src/phasechangefx.c: PhaseIntro_InitDisp
     *
     * Window 0 over the whole screen, blend setup and the initial `altBlend*` values; installs
     * [vMatchHandler].
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
     * Applies the blend config each frame; when the children are gone clears BG0 and BG1, removes
     * the blend handler, resets the scroll and breaks.
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

    private fun tileRef(chr: Int, pal: Int): Int = chr + ((pal + BGPAL_PHASE_CHANGE) shl 12)

    private fun chrOffset(chr: Int): Int = chr * 0x20

    private companion object {
        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
        const val BGCHR_PHASE_CHANGE_SQUARES = 0x100
        const val BGCHR_PHASE_CHANGE_NAME = 0x140
        const val BGPAL_PHASE_CHANGE = 5
        const val BG0_SYNC_BIT = 1
        const val BG1_SYNC_BIT = 2
        const val TILE_HFLIP = 0x400
        const val SONG_73 = 0x73
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160
        const val WIN0_ENABLE_BLEND = 5
        const val WOUT_ENABLE_BLEND = 21
        const val VMATCH_MID = 72
        const val VMATCH_LO = 96
        const val REG_BLDCNT = 0x50
        const val REG_BLDALPHA = 0x52
        const val BLDCNT_OUTER = 0x3C42
        const val BLDCNT_MID = 0x3E41
        const val IMG_UNK = 0x0859F020
        const val IMG_SQUARES = 0x085A06D8
        const val IMG_PLAYER = 0x0859F3F8
        const val PAL_PLAYER = 0x0859FA2C
        const val IMG_ENEMY = 0x0859FA4C
        const val PAL_ENEMY = 0x085A0068
        const val IMG_OTHER = 0x085A0088
        const val PAL_OTHER = 0x085A0698

        /**
         * `obj_0859AEC8`: five affine parts (64x32 x3, 32x32, 16x32), chr 0, 8, 0x10, 0x18,
         * 0x1C.
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
