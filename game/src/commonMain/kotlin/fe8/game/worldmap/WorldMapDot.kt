package fe8.game.worldmap

import fe8.game.RomView
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The fields of `struct ProcWmPlaceDot` past `PROC_HEADER`: one marked point (a small 8x8 dot)
 * drawn on the world map.
 */
public class WmPlaceDotProc {
    /**
     * `flag` (+0x29, `s8`): set by event command `BD` ([WorldMapDotEvents.eventBD]) to ask the dot
     * to end; the dot then keeps drawing until the palette pulse is back at the standard colours.
     * `BE` waits until the dot has ended.
     */
    public var flag: Int = 0

    /**
     * `unk_2a` (+0x2A, `u8`): bit 0 = draw with OBJ palette 5 (the pulsing one) instead of 4, bit 1
     * = also owns a `GmapEffectProc` ([effectProc]). `EventBB` passes it as `e`, `EventBC` as
     * `eff`.
     */
    public var effects: Int = 0

    /** `unk_2b` (+0x2B, `u8`): the id the event commands `BD` / `BE` address the dot by. */
    public var index: Int = 0

    /**
     * `unk_2c` (+0x2C, `u8`): the tile offset added to OAM attribute 2 of the dot sprite
     * (`EventBC` passes its argument named `pal` here; the value is used as a tile offset, see
     * [WorldMapDot.putWmDotSprite]).
     */
    public var tile: Int = 0

    /** `x` (+0x2E, `s16`): the position on the big map (`EventBC` passes half the node's x). */
    public var x: Int = 0

    /** `y` (+0x30, `s16`): as [x], vertically. */
    public var y: Int = 0

    /** `effectProc` (+0x34): the `GmapEffectProc` started when [effects] bit 1 is set, or null. */
    public var effectProc: Proc? = null
}

/**
 * The marked points of the world map (`src/worldmap_rm.c`: `ProcScr_WmPlaceDot` and the shared
 * palette pulse `ProcScr_WmDotPalAnim` that makes highlighted dots blink), started by event
 * commands `EventBB_` (explicit position) / `EventBC_MarkPoint` (a node's position) and ended
 * through `EventBD_` (sets the dot's flag) / `EventBE_` (waits until the dot is gone). The dots
 * are drawn on sprite layer 11 against the scroll of the `GmapRM` big map proc.
 *
 * @param engine the proc scheduler
 * @param hardware the palette buffer and VRAM
 * @param rom the ROM image
 * @param sprites the sprite layers the dots are drawn on
 * @param getPosition `GmapRm_GetPosition` as `(x, y)`, null without a map proc
 * @param hooks the callees outside this file
 */
public class WorldMapDot(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val sprites: SpriteLayers,
    private val getPosition: () -> Pair<Int, Int>?,
    private val hooks: Hooks = Hooks(),
) {
    /**
     * The callees for a dot that owns a map effect (`StartGmapEffect`, `sub_80C13CC` of
     * `src/worldmap_effect.c`); no capture runs one, so both fail loudly by default.
     */
    public class Hooks(
        /**
         * `StartGmapEffect(0, 1)` with `flags_0 = 1`, `unk_2c = x`, `unk_2e = y` on the new proc;
         * returns that proc.
         */
        public val startEffect: (x: Int, y: Int) -> Proc = {
            _, _ -> error("not verified: no capture starts a dot with a map effect")
        },
        /** `sub_80C13CC(effectProc)`, which only ends the proc. */
        public val endEffect: (Proc) -> Unit = {
            error("not verified: no capture ends a dot with a map effect")
        },
    )

    /** `Sprite_WmPlaceDot`: a one-object 8x8 sprite. */
    private val dotSprite: SpriteObject by lazy { rom.objN(SPRITE_WM_PLACE_DOT) }

    /**
     * `ProcScr_WmDotPalAnim`: fades OBJ palette 5 (the palette of dots with
     * [WmPlaceDotProc.effects] bit 0) from the standard to the highlight colours and back, 30
     * frames each way plus 2 frames of rest after each, forever until ended.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_WmDotPalAnim (not verified: the script order is
     * checked through the proc cursor, but no unit of it is ported here)
     */
    public val palAnimScript: ProcScript = ProcScript(
        "ProcScr_WmDotPalAnim",
        listOf(
            name("Gmap RM ba pal anim"),
            mark(WorldMapRm.PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { wmDotPalAnimInit(it) }),
            sleep(1),
            label(0),
            repeat(ProcFunc { wmDotPalAnimLoop1(it) }),
            sleep(2),
            repeat(ProcFunc { wmDotPalAnimLoop2(it) }),
            sleep(2),
            goto(0),
            end(),
        ),
    ) { GmapRmBaPalAnimProc() }

    /**
     * `ProcScr_WmPlaceDot`: one marked point. The first dot (which starts the pulse) goes
     * straight to label 1; a later dot first draws until the already running pulse is at rest.
     * Then it draws until [WmPlaceDotProc.flag] is set and draws until the pulse is at rest again
     * before ending, so the dot never vanishes mid-fade.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_WmPlaceDot (not verified: the script order is
     * checked through the proc cursor, but no unit of it is ported here)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_WmPlaceDot",
        listOf(
            name("Gmap RM border"),
            mark(WorldMapRm.PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { wmPlaceDotOnEnd(it) }),
            call(ProcFunc { wmPlaceDotInit(it) }),
            repeat(ProcFunc { wmPlaceDotLoop1(it) }),
            label(1),
            repeat(ProcFunc { wmPlaceDotLoop2(it) }),
            repeat(ProcFunc { wmPlaceDotLoop1(it) }),
            end(),
        ),
    ) { WmPlaceDotProc() }

    /** The [WmPlaceDotProc] state of a [script] proc. */
    private fun dot(proc: Proc) = proc.state as WmPlaceDotProc

    /** The pulse state of a [palAnimScript] proc (the `ProcGmapRmBaPalAnim` fields). */
    private fun pulse(proc: Proc) = proc.state as GmapRmBaPalAnimProc

    /** `ApplyPalette(source, bank)`: copies the 16 colours at ROM [source] into palette [bank]. */
    private fun applyPalette(source: Int, bank: Int) {
        rom.pal(source, 16).copyInto(hardware.palette, bank * 16)
    }

    /**
     * Ported from src/worldmap_rm.c: WmDotPalAnim_Init
     *
     * Puts the standard dot colours in OBJ palette 5 (`0x15`: palettes 0-15 are BG, 16-31 OBJ, so
     * bank 0x15 is OBJ palette 5) and marks the pulse as at rest (`flag = 1`).
     *
     * Checked per step: the pulse's `flag` / `timer` and OBJ palette 5 against the capture.
     */
    public fun wmDotPalAnimInit(proc: Proc) {
        applyPalette(PAL_STANDARD, 0x15)
        hardware.enablePaletteSync()
        val s = pulse(proc)
        s.timer = 0
        s.flag = 1
    }

    /**
     * Ported from src/worldmap_rm.c: sub_80C2C80 (`blendPalette` here)
     *
     * Writes the 16 colours of [srcA] moved by the fraction [a] / [b] of the way towards [srcB]
     * to the palette buffer at colour [dst] (the dot pulse's cross-fade). Both are ROM addresses
     * of 16 BGR555 colours; the fraction is eased with `_DivArm1(a, b, 0)` and each 5-bit channel
     * is interpolated separately. `sub_80C2C80` in the decomp.
     */
    public fun blendPalette(a: Int, b: Int, srcA: Int, srcB: Int, dst: Int) {
        val coeff = Spline.divArm1(a, b, 0)
        for (i in 0 until 16) {
            val color = rom.u16(srcA + 2 * i)
            val r1 = color and 0x1F
            val g1 = (color and 0x3E0) shr 5
            val b1 = (color and 0x7C00) shr 10
            val color2 = rom.u16(srcB + 2 * i)
            val r2 = r1 + Spline.divArm(0x1000, coeff * ((color2 and 0x1F) - r1))
            val g2 = g1 + Spline.divArm(0x1000, coeff * (((color2 and 0x3E0) shr 5) - g1))
            val b2 = b1 + Spline.divArm(0x1000, coeff * (((color2 and 0x7C00) shr 10) - b1))
            hardware.palette[dst + i] = ((b2 shl 10) + (g2 shl 5) + r2).toShort()
        }
        hardware.enablePaletteSync()
    }

    /**
     * Ported from src/worldmap_rm.c: WmDotPalAnim_Loop1
     *
     * One frame of the fade from the standard to the highlight colours; breaks after 30 frames.
     * The decomp's target `Pal_WmPlaceDot_Standard - 0x10` (in `u16`s) is
     * `Pal_WmPlaceDot_Highlight`, which lies 0x20 bytes before it.
     */
    public fun wmDotPalAnimLoop1(proc: Proc) {
        val s = pulse(proc)
        s.timer++
        if (s.timer < PULSE_FRAMES) {
            blendPalette(s.timer, PULSE_FRAMES, PAL_STANDARD, PAL_HIGHLIGHT, PULSE_COLORS)
            s.flag = 0
        } else {
            applyPalette(PAL_HIGHLIGHT, 0x15)
            s.timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: WmDotPalAnim_Loop2
     *
     * One frame of the fade back to the standard colours; breaks after 30 frames and marks the
     * pulse at rest again (`flag = 1`).
     */
    public fun wmDotPalAnimLoop2(proc: Proc) {
        val s = pulse(proc)
        s.timer++
        if (s.timer < PULSE_FRAMES) {
            blendPalette(s.timer, PULSE_FRAMES, PAL_HIGHLIGHT, PAL_STANDARD, PULSE_COLORS)
        } else {
            applyPalette(PAL_STANDARD, 0x15)
            s.timer = 0
            s.flag = 1
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: StartWmDotPalAnim (the original returns nothing)
     *
     * @param parent blocked by the new proc; the proc is a root of tree 3 when null
     */
    public fun startWmDotPalAnim(parent: Proc?) {
        if (parent != null) {
            engine.startBlocking(palAnimScript, parent)
        } else {
            engine.start(palAnimScript, 3)
        }
    }

    /** Ported from src/worldmap_rm.c: EndAllWmDotPalAnim: stops the dot palette pulse. */
    public fun endAllWmDotPalAnim() {
        engine.endEach(palAnimScript)
    }

    /**
     * Ported from src/worldmap_rm.c: sub_80C2E50 (`pulseFlag` here)
     *
     * The `flag` of the pulse: 1 while it rests on the standard colours (before the first fade
     * and after each fade back), 0 while it is fading to or from the highlight colours, and 0
     * without a pulse. The dots wait for it so they stay up until the colours are back to normal.
     */
    public fun pulseFlag(): Int {
        val proc = engine.find(palAnimScript) ?: return 0
        return pulse(proc).flag
    }

    /**
     * Transcribed from src/worldmap_rm.c: IsWmDotPalAnimActive (not verified: no caller in the
     * Prologue)
     */
    public fun isWmDotPalAnimActive(): Boolean = engine.find(palAnimScript) != null

    /**
     * Ported from src/worldmap_rm.c: WmPlaceDot_OnEnd
     *
     * The last dot ends the shared palette pulse; the map effect branch (`sub_80C13CC`, for
     * a dot started with bit 1 of `e`) is not run by the capture.
     */
    public fun wmPlaceDotOnEnd(proc: Proc) {
        if (engine.count(script) == 1) endAllWmDotPalAnim()
        val s = dot(proc)
        val effect = s.effectProc
        if (s.effects and 2 != 0 && effect != null) hooks.endEffect(effect)
    }

    /**
     * Ported from src/worldmap_rm.c: WmPlaceDot_Init
     *
     * The first dot loads the dot tile (`Img_WorldMapPlaceDot` at VRAM `0x060133C0`) and the
     * standard palette into bank 0x14 (OBJ palette 4), starts the pulse and jumps to label 1 to
     * wait for [WmPlaceDotProc.flag]; later dots do nothing here (the tile and the pulse are
     * shared) and so run the first drawing phase of the script.
     */
    public fun wmPlaceDotInit(proc: Proc) {
        if (engine.count(script) > 1) return
        startWmDotPalAnim(null)
        rom.decompressInto(IMG_WORLD_MAP_PLACE_DOT, hardware.vram, DOT_TILES)
        applyPalette(PAL_STANDARD, 0x14)
        hardware.enablePaletteSync()
        engine.goto(proc, 1)
    }

    /**
     * Ported from src/worldmap_rm.c: PutWmDotSprite
     *
     * Draws the dot at its big map position minus the scroll of the big map (`GmapRm_GetPosition`;
     * the 4 centres the 8x8 sprite), only when it is on screen. The `0x21` / `0x112` / `0xC2`
     * literals are the decomp's window test: the 240x160 screen plus a margin of about 32 pixels.
     * OAM attribute 2 is the proc's tile offset plus tile `0x19E` (VRAM `0x060133C0`, where
     * [wmPlaceDotInit] loaded the dot), palette 4 or 5 and `OAM2_LAYER(3)` (`0xC00`). Without a
     * big map proc the decomp reads uninitialised coordinates; here they are 0.
     *
     * Checked at the full OAM dumps of the capture.
     */
    public fun putWmDotSprite(proc: Proc) {
        val s = dot(proc)
        val (x, y) = (getPosition() ?: (0 to 0))
        val xOam1 = (-((x.toShort().toInt()) + 4) + s.x).toShort().toInt()
        val yOam0 = (-((y.toShort().toInt()) + 4) + s.y).toShort().toInt()
        if (xOam1 + 0x21 > 0 && xOam1 + 0x21 < 0x112 && yOam0 + 0x21 > 0 && yOam0 + 0x21 < 0xC2) {
            val palId = if (s.effects and 1 != 0) 5 else 4
            val oam2 = s.tile + (palId shl 12 or 0x19E) + 0xC00
            sprites.put(DOT_LAYER, xOam1, yOam0, dotSprite, oam2)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: WmPlaceDot_Loop1: draws the dot until the pulse is at rest.
     */
    public fun wmPlaceDotLoop1(proc: Proc) {
        putWmDotSprite(proc)
        if (pulseFlag() != 0) engine.breakProc(proc)
    }

    /** Ported from src/worldmap_rm.c: WmPlaceDot_Loop2: draws the dot until its flag is set. */
    public fun wmPlaceDotLoop2(proc: Proc) {
        putWmDotSprite(proc)
        if (dot(proc).flag != 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/worldmap_rm.c: StartWmPlaceDot
     *
     * Starts one dot (`EventBB_` passes explicit coordinates, `EventBC_MarkPoint` half of a
     * node's). The decomp names the parameters `a, b, x, y, e`.
     *
     * @param index `a`: the dot's id for `BD` / `BE`
     * @param tile `b`: the tile offset added to OAM attribute 2
     * @param x the big map position, `y` likewise
     * @param effects `e`: bit 0 = pulsing palette, bit 1 = also starts a map effect
     * @param parent blocked by the new proc (a root of tree 3 when null)
     */
    public fun startWmPlaceDot(
        index: Int,
        tile: Int,
        x: Int,
        y: Int,
        effects: Int,
        parent: Proc?,
    ): Proc {
        val proc = if (parent != null) {
            checkNotNull(engine.startBlocking(script, parent))
        } else {
            engine.start(script, 3)
        }
        val s = dot(proc)
        s.index = index and 0xFF
        s.tile = tile and 0xFF
        s.x = x.toShort().toInt()
        s.y = y.toShort().toInt()
        s.effects = effects and 0xFF
        s.effectProc = if (effects and 2 != 0) hooks.startEffect(x, y) else null
        s.flag = 0
        return proc
    }

    /**
     * Transcribed from src/worldmap_rm.c: EndWmPlaceDotByIndex (not verified: no capture ends a
     * dot by index; the script ends them with `BD`)
     *
     * Ends the dot with that index; [index] < 0 ends all. The original reads past the end of the
     * list when no dot matches (`proc` is NULL); that case ends nothing here.
     */
    public fun endWmPlaceDotByIndex(index: Int) {
        if (index < 0) {
            engine.endEach(script)
            return
        }
        findDot(index)?.let { engine.end(it) }
    }

    /**
     * Ported from src/worldmap_rm.c: IsWmPlaceDotActiveAtIndex
     *
     * Whether a dot with id [index] exists ([index] < 0 asks for any dot); `EventBE_` waits
     * until it does not.
     */
    public fun isWmPlaceDotActiveAtIndex(index: Int): Boolean {
        if (index < 0) return engine.find(script) != null
        return findDot(index) != null
    }

    /**
     * Ported from src/worldmap_rm.c: SetWmPlaceDotFlagForIndex
     *
     * Sets the flag of the dot with id [index], so it ends once the pulse is at rest ([index] < 0
     * sets every dot's flag; the original then also writes through a NULL proc at the end of
     * the list, which does nothing on the GBA).
     */
    public fun setWmPlaceDotFlagForIndex(index: Int) {
        var proc = engine.find(script)
        while (proc != null) {
            if (index < 0) {
                dot(proc).flag = 1
            } else if (dot(proc).index == index) {
                dot(proc).flag = 1
                return
            }
            proc = engine.findAfter(script, proc)
        }
    }

    /** The first dot proc whose index is [index], or null. */
    private fun findDot(index: Int): Proc? {
        var proc = engine.find(script)
        while (proc != null) {
            if (dot(proc).index == index) return proc
            proc = engine.findAfter(script, proc)
        }
        return null
    }

    private companion object {
        /** `Sprite_WmPlaceDot`. */
        const val SPRITE_WM_PLACE_DOT = 0x08A3ED10

        /** `Img_WorldMapPlaceDot`. */
        const val IMG_WORLD_MAP_PLACE_DOT = 0x08AA114C

        /** `Pal_WmPlaceDot_Highlight`. */
        const val PAL_HIGHLIGHT = 0x08AA1190

        /** `Pal_WmPlaceDot_Standard`. */
        const val PAL_STANDARD = 0x08AA11B0

        /** `0x060133C0` as a VRAM offset: OBJ tile `0x19E`, where the dot tile is loaded. */
        const val DOT_TILES = 0x133C0

        /** `gPaletteBuffer + 0x150` as a colour index (OBJ palette 5). */
        const val PULSE_COLORS = 0x150

        /** Frames of one pulse direction. */
        const val PULSE_FRAMES = 30

        /** The sprite layer the dots use (`PutSprite(0xb, ...)`). */
        const val DOT_LAYER = 0xB
    }
}
