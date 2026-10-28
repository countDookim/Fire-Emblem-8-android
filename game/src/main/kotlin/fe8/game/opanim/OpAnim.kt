package fe8.game.opanim

import fe8.game.GameControl
import gbafe.data.Decompress
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.startChild
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The op anim (intro cutscene after the logos), `ProcScr_OpAnim` (decomp `src/opanim-main.c`,
 * `src/opanimfx.c`). Partial: only its start (`OpAnimInit` through the world map scene) is ported;
 * a stand-in `PROC_BLOCK` sits where `OpAnimPreparefxEphraim` would run.
 */
public class OpAnim(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware (palette buffer, VRAM, LCD control). */
    public val hardware: BgHardware,
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus,
    /** The game control proc's owner, for `SetNextGameActionId`. */
    public val gameControl: GameControl,
    /** Graphics and palettes loaded by [init]. */
    public val gfx: Gfx,
    /** World map graphics loaded by [setupWorldMap]. */
    public val worldMap: WorldMapGfx,
    /** Ephraim scene graphics loaded by [prepareEphraim] (null until a caller supplies them). */
    public val ephraim: EphraimGfx? = null,
) {
    /** ROM data used by [setupWorldMap]. */
    public class WorldMapGfx(
        /** `Img_OpAnimWorldMap`. */
        public val img: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMap`. */
        public val tsa: OpAnimHs.Blob,
        /** `Img_OpAnimWorldMapFog`. */
        public val fogImg: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMapFog`. */
        public val fogTsa: OpAnimHs.Blob,
        /** `Pal_OpAnimWorldMapFog` (16 colours). */
        public val fogPal: ShortArray,
        /** `Pal_OpAnimWorldMap` (16 colours). */
        public val pal: ShortArray,
    )

    /** ROM data used by [prepareEphraim], in `OpAnimPreparefxEphraim` order. */
    public class EphraimGfx(
        /** `Img_OpAnimCharacterBG`, `Tsa_OpAnimCharacterBG`. */
        public val characterBg: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimClose1`, `Tsa_OpAnimEphraimClose1`. */
        public val close1: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimClose2`, `Tsa_OpAnimEphraimClose2`. */
        public val close2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraim`, `Tsa_OpAnimEphraim`. */
        public val ephraim: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Pal_OpAnimEphraimBlur` (11 palettes). */
        public val blurPal: ShortArray,
        /** `Img_OpAnimEphraimBlur3`, `Tsa_OpAnimEphraimBlur3`. */
        public val blur3: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimBlur2`, `Tsa_OpAnimEphraimBlur2`. */
        public val blur2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
    )

    /** `gGenericBuffer` (0x2000 bytes used here). */
    public val genericBuffer: ByteArray = ByteArray(0x2000)

    /** `struct ProcOpAnimFadeIn`. */
    public class FadeInState {
        /** `cur`. */
        public var cur: Int = 0

        /** `max`. */
        public var max: Int = 0
    }

    /** `ProcScr_OpAnimFadeIn`: fades the screen in from black over `max` frames. */
    public val fadeInScript: ProcScript = ProcScript(
        "ProcScr_OpAnimFadeIn",
        listOf(
            yield(),
            call(ProcFunc { allBlack(it) }),
            repeat(ProcFunc { fadeInMain(it) }),
            end(),
        ),
        newState = { FadeInState() },
    )

    /** `struct ProcOpAnimBLDALPHA`. */
    public class BldAlphaState {
        /** `timer`. */
        public var timer: Int = 0
    }

    /**
     * `ProcScr_OpAnimBLDALPHA`: fades the BG palette in from black to white, waits, then blends the
     * fog in.
     */
    public val bldAlphaScript: ProcScript = ProcScript(
        "ProcScr_OpAnimBLDALPHA",
        listOf(
            call(ProcFunc { (it.state as BldAlphaState).timer = 0 }), // OpAnimBldAlphaInit
            repeat(ProcFunc { bldAlphaUpdateBgPalette(it) }),
            repeat(ProcFunc { bldAlphaDelay(it) }),
            repeat(ProcFunc { bldAlphaMain(it) }),
            end(),
        ),
        newState = { BldAlphaState() },
    )

    /** ROM data used by [init]: four compressed OBJ images and their 16-colour palettes. */
    public class Gfx(
        /**
         * `Img_OpAnimSplitLine`, `Img_opanim_unk1`, `Img_OpAnimEphEirikaName`, `Img_OpAnimDivlEye`.
         */
        public val images: List<OpAnimHs.Blob>,
        /** The matching `Pal_*` (16 colours each). */
        public val palettes: List<ShortArray>,
    )

    /** `struct ProcOpAnim` fields used so far. */
    public class State {
        /** `timer`. */
        public var timer: Int = 0

        /** `unk2C`: BG1 x scroll. */
        public var unk2C: Int = 0

        /** `unk2E`: BG1 y scroll. */
        public var unk2E: Int = 0

        /** `unk30`. */
        public var unk30: Int = 0

        /** `unk32`. */
        public var unk32: Int = 0

        /** `unk38`, `unk3A`, `unk3C`, `unk3E`, `unk46`. */
        public val unk38to46: IntArray = IntArray(5)
    }

    /** `gOpAnimSt.x`, `y`, `unk04`, `unk06`, `unk08`, `unk0A` (s16 each). */
    public val opAnimSt: IntArray = IntArray(6)

    /** `gOpAnimSt.pal`: colour forced into palette entry 0 by [copyToPal]. */
    public var pal: Int = 0

    /** `ProcScr_OpAnimfxTerminator`: ends the op anim on A, B or Start. */
    public val terminatorScript: ProcScript = ProcScript(
        "ProcScr_OpAnimfxTerminator",
        listOf(yield(), repeat(ProcFunc { terminatorMain(it) }), end()),
    )

    /** The script (partial, see the class doc); [ProcScript.newState] creates a [State]. */
    public val script: ProcScript = ProcScript(
        "ProcScr_OpAnim",
        listOf(
            yield(),
            call(ProcFunc { init(it) }),
            yield(),
            call(ProcFunc { setupWorldMap(it) }),
            startChild { bldAlphaScript },
            whileTrue(ProcBoolFunc { bldAlphaExists() }),
            call(ProcFunc { updateScreen1(it) }),
            repeat(ProcFunc { worldMapScroll(it) }),
            call(ProcFunc { setupEphraimScene(it) }),
            yield(),
            repeat(ProcFunc { prepareEphraim(it) }),
            call(ProcFunc { newProc08AA6D04() }),
            block(), // not ported: OpAnimEphraimfxFlyIn and the rest of the cutscene
            end(),
        ),
        newState = { State() },
    )

    /**
     * Resets the display, loads the cutscene's OBJ graphics and palettes, sets the next game action
     * to "played through" and starts the terminator. `Sound_FadeOutBGM` is not modelled.
     *
     * Partially ported from src/opanim-main.c: OpAnimInit
     */
    public fun init(proc: Proc) {
        val hw = hardware
        hw.setupBackgrounds()
        hw.hblank.setPrimary(null)
        (proc.state as State).timer = 0
        pal = 0
        hw.lcd.dispcnt = hw.lcd.dispcnt or DISPCNT_OBJ_1D
        hw.lcd.setDispEnable(0, 0, 0, 0, 0)
        val objOffsets = intArrayOf(0x0000, 0x0100, 0x08C0, 0x1140)
        for (i in 0..3) {
            val img = gfx.images[i]
            Decompress.into(img.data, img.offset, hw.vram, OBJ_VRAM + objOffsets[i])
        }
        for (i in 0..3) copyToPal(gfx.palettes[i], 0x200 + 0x20 * i, 0x20)
        hw.vramFill16(0xFFFF, OBJ_VRAM + 0x7800, 0x800)
        gameControl.setNextGameActionId(GameControl.GAME_ACTION_PLAYED_THROUGH)
        engine.start(terminatorScript, proc)
    }

    /**
     * Sets up BG2 (world map) and BG1 (fog, palette 2), blending and display. `StartBgmExt` is not
     * modelled.
     *
     * Partially ported from src/opanim-main.c: SetupOpAnimWorldMapfx
     */
    public fun setupWorldMap(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        setOffsets(
            intArrayOf(0x0000, 0x4000, 0x8000, 0x0000),
            intArrayOf(0xD800, 0xE800, 0xF000, 0xF800),
        )
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.setBgPosition(bg, 0, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
        }
        lcd.dispcnt = lcd.dispcnt and 0xE000.inv() // SetWinEnable(0, 0, 0)
        val w = worldMap
        Decompress.into(w.img.data, w.img.offset, hw.vram, hw.tileDataOffset(2))
        Decompress.into(w.tsa.data, w.tsa.offset, hw.vram, 0xF000)
        hw.palette.fill(0, 0, 16)
        hw.fill(hw.mapBuffer(0), 0)
        hw.enableBgSyncByMask(1)
        Decompress.into(w.fogImg.data, w.fogImg.offset, hw.vram, hw.tileDataOffset(1))
        copyToPal(w.fogPal, 0x40, 0x20)
        Decompress.into(w.fogTsa.data, w.fogTsa.offset, hw.vram, 0xE800)
        for (i in 0 until 0x400) {
            val o = 0xE800 + 2 * i
            add16(hw.vram, o, 0x2000)
        }
        hw.enablePaletteSync()
        lcd.setBlendConfig(1, 0, 0x10, 8)
        lcd.setBlendTargetA(0, 0, 0, 0, 1)
        lcd.setBlendTargetB(1, 1, 1, 1, 0)
        (proc.state as State).timer = 0
        lcd.setDispEnable(0, 0, 1, 0, 1)
    }

    /**
     * Fills BG palette 0 with a grey interpolated from black to white over 0x3E frames, then sets
     * [pal] to white, keeps only OBJ as first blend target and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaUpdateBgPalette
     */
    public fun bldAlphaUpdateBgPalette(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0, 0x1F, st.timer, 0x3E)
        val color = (v shl 10) or (v shl 5) or v
        hardware.palette.fill(color.toShort(), 0, 16)
        hardware.enablePaletteSync()
        if (st.timer == 0x3E) {
            st.timer = 0
            pal = 0x7FFF
            hardware.lcd.setBlendTargetA(0, 0, 0, 0, 1)
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Waits 8 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaDelay
     */
    public fun bldAlphaDelay(proc: Proc) {
        val st = proc.state as BldAlphaState
        if (st.timer == 8) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Moves the blend coefficients from (0x10, 0) to (0, 0x10) over 0x10 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaMain
     */
    public fun bldAlphaMain(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0x10, 0, st.timer, 0x10)
        hardware.lcd.blendCoeffA = v and 0xFF
        hardware.lcd.blendCoeffB = (0x10 - v) and 0xFF
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * True while a `ProcScr_OpAnimBLDALPHA` proc exists.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaExists
     */
    public fun bldAlphaExists(): Boolean = engine.find(bldAlphaScript) != null

    /**
     * Blends the fog (BG1) over the world map (BG2) and shows BG1, BG2 and OBJ.
     *
     * Ported from src/opanim-main.c: OpAnimUpdateScreen1
     */
    public fun updateScreen1(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setBlendConfig(1, 8, 0x10, 8)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 0, 0)
        lcd.setDispEnable(0, 1, 1, 0, 1)
        val st = proc.state as State
        st.unk2C = 0
        st.unk2E = 0
    }

    /**
     * World map scene, one frame: scrolls the fog, fades the map palette in from white and the fog
     * out, starts the screen fade-in at frame 0xEF and breaks at 0x110.
     *
     * Ported from src/opanim-main.c: sub_80C6F70
     */
    public fun worldMapScroll(proc: Proc) {
        val st = proc.state as State
        val lcd = hardware.lcd
        if (st.timer and 1 == 0) st.unk2C = (st.unk2C + 1).toShort().toInt()
        if (st.timer and 3 == 0) st.unk2E = (st.unk2E + 1).toShort().toInt()
        lcd.setBgPosition(1, st.unk2C, st.unk2E)
        if (st.timer <= 0x60) {
            val v = linear(0x10, 0, st.timer, 0x60)
            copyToPal(worldMap.pal, 0, 0x20)
            blendPalette(0, 0x7FFF, v)
        }
        if (st.timer - 0x80 in 0..0x60) {
            lcd.blendCoeffA = linear(8, 0, st.timer - 0x80, 0x60) and 0xFF
        }
        if (st.timer == 0xEF) {
            lcd.setDispEnable(0, 0, 1, 0, 1)
            newFadeIn(0x20, proc)
        }
        if (st.timer == 0x110) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Resets the BG layout for the Ephraim / Eirika scenes: offsets, priorities, empty tilemaps,
     * display off, blend setup.
     *
     * Ported from src/opanim-main.c: sub_80C7050
     */
    public fun setupEphraimScene(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        setOffsets(
            intArrayOf(0x0000, 0x0000, 0x4000, 0x4000),
            intArrayOf(0xD800, 0xE000, 0xE800, 0xF800),
        )
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.setBgPosition(bg, 0, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
        }
        lcd.setDispEnable(0, 0, 0, 0, 0)
        lcd.dispcnt = lcd.dispcnt and 0xE000.inv() // SetWinEnable(0, 0, 0)
        for (bg in 0..3) hw.fill(hw.mapBuffer(bg), 0)
        hw.enableBgSyncByMask(0xF)
        // SetBlendBackdropA(0), SetBlendBackdropB(0)
        lcd.bldcnt = lcd.bldcnt and (BLD_BACKDROP_A or BLD_BACKDROP_B).inv()
        lcd.setBlendConfig(1, 8, 8, 8)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 0)
        val st = proc.state as State
        st.timer = 0
        st.unk38to46.fill(0)
    }

    /**
     * Loads the Ephraim scene over 7 steps (by `timer`: one or two ROM blobs per step), then sets
     * up BG3 / palettes / `gOpAnimSt` and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimPreparefxEphraim
     */
    public fun prepareEphraim(proc: Proc) {
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        val hw = hardware
        val vram = hw.vram
        val buf = genericBuffer
        val st = proc.state as State
        fun dec(b: OpAnimHs.Blob, dst: ByteArray, at: Int) =
            Decompress.into(b.data, b.offset, dst, at)
        when (st.timer) {
            0 -> {
                dec(g.characterBg.first, vram, 0x2000 + hw.tileDataOffset(3))
                val t = Decompress.decode(g.characterBg.second.data, g.characterBg.second.offset)
                val map = hw.mapBuffer(3)
                for (i in 0 until minOf(t.size / 2, map.size)) {
                    map[i] = ((t[2 * i].toInt() and 0xFF) or
                        ((t[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                for (i in 1 until 16) hw.palette[0xE0 + i] = 0x7FFF
                for (i in 0 until 0x280) map[i] = (map[i] + 0xE100).toShort()
                hw.enableBgSyncByMask(8)
            }
            1 -> {
                dec(g.close1.first, vram, hw.tileDataOffset(0))
                dec(g.close1.second, buf, 0)
            }
            2 -> {
                dec(g.close2.first, vram, 0x2000 + hw.tileDataOffset(0))
                dec(g.close2.second, buf, 0x800)
            }
            3 -> {
                dec(g.ephraim.first, vram, hw.tileDataOffset(2))
                dec(g.ephraim.second, buf, 0x1000)
                copyToPal(g.blurPal, 0, 0x20 * 11)
            }
            4 -> {
                dec(g.blur3.first, vram, 0x8000)
                dec(g.blur3.second, vram, 0xC000)
            }
            5 -> {
                dec(g.blur2.first, vram, 0xA000)
                dec(g.blur2.second, vram, 0xC800)
                for (i in 0 until 0x280) add16(vram, 0xC800 + 2 * i, 0x100)
            }
            6 -> {
                for (i in 1 until 16) hw.palette[0xF0 + i] = 0
                buf.copyInto(buf, 0x1800, 0x1000, 0x1800)
                for (i in 0 until 0x280) {
                    val o = 0x1800 + 2 * i
                    val v = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
                    val n = (v and 0xFFF) or 0xF000
                    buf[o] = n.toByte()
                    buf[o + 1] = (n shr 8).toByte()
                }
                buf.copyInto(vram, 0xF000, 0x1800, 0x1D00)
                hw.enablePaletteSync()
                hw.lcd.setDispEnable(0, 1, 1, 1, 1)
                st.timer = 0
                st.unk30 = 0
                st.unk32 = 0
                opAnimSt[0] = 0
                opAnimSt[1] = 0
                opAnimSt[2] = 0
                opAnimSt[3] = 0xA0
                pal = 0
                hw.setTileDataOffset(2, 0x8000)
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Copies `gOpAnimSt.y` / `unk06` into `unk08` / `unk0A` every VBlank.
     *
     * Not verified yet (no trace check): src/opanim-main.c: Proc08AA6D04Main
     */
    public fun proc08AA6D04Main() {
        opAnimSt[4] = opAnimSt[1]
        opAnimSt[5] = opAnimSt[3]
    }

    /** `ProcScr_08AA6D04` (runs on tree 0, VSync). */
    public val proc08AA6D04Script: ProcScript = ProcScript(
        "ProcScr_08AA6D04",
        listOf(repeat(ProcFunc { proc08AA6D04Main() }), end()),
    )

    /**
     * Starts [proc08AA6D04Script] on tree 0.
     *
     * Not verified yet (no trace check): src/opanim-main.c: NewProc08AA6D04
     */
    public fun newProc08AA6D04() {
        engine.start(proc08AA6D04Script, 0)
    }

    /**
     * Blends the 16 colours of the palette buffer at entry [at] towards [color] by [blend]/16.
     *
     * Ported from src/opanimfx.c: sub_80C69B0
     */
    public fun blendPalette(at: Int, color: Int, blend: Int) {
        val p = hardware.palette
        for (i in at until at + 16) {
            val c = p[i].toInt()
            fun ch(v: Int, shift: Int): Int {
                val a = (v shr shift) and 0x1F
                val b = (color shr shift) and 0x1F
                return ((a * (0x10 - blend) + b * blend) shr 4) and 0x1F
            }
            p[i] = ((ch(c, 10) shl 10) or (ch(c, 5) shl 5) or ch(c, 0)).toShort()
        }
    }

    /**
     * Starts the fade-in proc with [max] frames as a child of [parent].
     *
     * Ported from src/opanim-main.c: NewProcOpAnimFadeIn
     */
    public fun newFadeIn(max: Int, parent: Proc) {
        (engine.start(fadeInScript, parent).state as FadeInState).max = max.toShort().toInt()
    }

    /**
     * Brightness-fade setup: effect 2, BLDY 0, all layers first target.
     *
     * Ported from src/opanim-main.c: OpAnimAllBlack
     */
    public fun allBlack(proc: Proc) {
        val lcd = hardware.lcd
        lcd.bldcnt = (lcd.bldcnt and 0xC0.inv()) or (2 shl 6)
        lcd.blendY = 0
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        (proc.state as FadeInState).cur = 0
    }

    /**
     * Fade-in frame: BLDY from 0 to 0x10 over `max` frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimFadeInMain
     */
    public fun fadeInMain(proc: Proc) {
        val st = proc.state as FadeInState
        hardware.lcd.blendY = linear(0, 0x10, st.cur, st.max) and 0xFF
        if (st.cur == st.max) engine.breakProc(proc)
        st.cur = (st.cur + 1).toShort().toInt()
    }

    /** `Interpolate(INTERPOLATE_LINEAR, ...)` (src/bmlib.c), the only method used here. */
    private fun linear(lo: Int, hi: Int, x: Int, xMax: Int): Int =
        if (xMax == 0) hi else lo + (hi - lo) * x / xMax

    private fun setOffsets(tiles: IntArray, maps: IntArray) {
        for (bg in 0..3) {
            hardware.setTileDataOffset(bg, tiles[bg])
            hardware.setMapDataOffset(bg, maps[bg])
        }
    }

    private fun add16(b: ByteArray, o: Int, d: Int) {
        val v = ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)) + d
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    /**
     * Copies [size] bytes of [src] to the palette buffer at byte [offset], requests a palette sync
     * and forces entry 0 to [pal].
     *
     * Ported from src/opanimfx.c: CopyToPalOpAnim
     */
    public fun copyToPal(src: ShortArray, offset: Int, size: Int) {
        src.copyInto(hardware.palette, offset shr 1, 0, size shr 1)
        hardware.enablePaletteSync()
        hardware.palette[0] = pal.toShort()
    }

    /**
     * On A, B or Start: sets the next game action to "user skipped", clears [pal] and jumps the op
     * anim to label 99.
     *
     * Label 99 is not ported yet: the jump is a no-op.
     *
     * Partially ported from src/opanimfx.c: OpAnimfxTerminatorMain
     */
    public fun terminatorMain(proc: Proc) {
        if (keys.newKeys and (A_BUTTON or B_BUTTON or START_BUTTON) != 0) {
            gameControl.setNextGameActionId(GameControl.GAME_ACTION_USR_SKIPPED)
            pal = 0
            engine.goto(proc.parent!!, 99)
        }
    }

    private companion object {
        const val OBJ_VRAM = 0x10000
        const val BLD_BACKDROP_A = 1 shl 5
        const val BLD_BACKDROP_B = 1 shl 13
        const val DISPCNT_OBJ_1D = 0x40
        const val A_BUTTON = 0x1
        const val B_BUTTON = 0x2
        const val START_BUTTON = 0x8
    }
}
