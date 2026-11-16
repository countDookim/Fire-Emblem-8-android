package fe8.game.bm

import fe8.game.RomView
import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/**
 * Proc user data for `ProcScr_MapBattleInfoBox` (`struct MAInfoFrameProc`, include/mapanim.h:61).
 */
public class MAInfoFrameProc(
    /** `unk2A` (short, 0x2A): shake pixel amplitude. */
    public var unk2A: Int = 0,
    /** `unk2C` (u16, 0x2C). */
    public var unk2C: Int = 0,
    /** `x` (u8, 0x2E): window origin tile X. */
    public var x: Int = 0,
    /** `y` (u8, 0x2F): window origin tile Y. */
    public var y: Int = 0,
    /** `maMain` (struct Proc*, 0x30): parent map battle animation proc. */
    public var maMain: Proc? = null,
)

/**
 * The map animation battle info box (EP-20, `src/mapanim_infobox.c`): manages the window frame
 * graphics on BG1, character name labels on BG0, faction palettes, and the
 * `ProcScr_MapBattleInfoBox` proc lifecycle.
 */
public class MapAnimInfoBox(
    private val engine: ProcEngine,
    private val manim: ManimSt,
    private val rom: RomView,
    private val text: TextSystem,
    private val messages: MsgStrings,
    private val hardware: BgHardware? = null,
) {
    /**
     * Hooks for unported functions belonging to later milestones (EP-22, the scanline effects).
     */
    public class Hooks {
        /**
         * Stand-in until EP-22: `InitScanline` (src/mapanim_scanline.c).
         */
        public var initScanline: () -> Unit = {}

        /**
         * Stand-in until EP-22: `StartManimFrameGradientScanlineEffect` (src/mapanim_scanline.c).
         */
        public var startManimFrameGradientScanlineEffect:
            (top: Int, bottom: Int, colorA: Int, colorB: Int) -> Unit = { _, _, _, _ -> }
    }

    /** The hooks, configurable by callers. */
    public val hooks: Hooks = Hooks()

    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * `ProcScr_MapBattleInfoBox` (0x089A3688): 7 commands managing the info box shake, draw, and
     * HP bar draining loops.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_MapBattleInfoBox",
        listOf(
            setEndCb(ProcFunc { procMapInfoBoxOnEnd() }),
            sleep(1),
            call(ProcFunc { mapInfoBoxPrepareForShake(it) }),
            call(ProcFunc { procMapInfoBoxOnDraw(it.state as MAInfoFrameProc) }),
            repeat(ProcFunc { mapInfoBoxShake(it) }),
            repeat(ProcFunc { sub807BCA8(it.state as MAInfoFrameProc) }),
            end(),
        ),
        newState = { MAInfoFrameProc() },
    )

    /**
     * Starts the map battle animation info window proc on tree 3 at tile position ([x], [y])
     * with parent [parent].
     *
     * Ported from src/mapanim_infobox.c: StartMapAnimInfoWindow
     */
    public fun startMapAnimInfoWindow(x: Int, y: Int, parent: Proc): Proc {
        val proc = engine.start(script, TREE_INFOBOX)
        val state = proc.state as MAInfoFrameProc
        state.x = x
        state.y = y
        state.maMain = parent
        return proc
    }

    /**
     * Decompresses the battle box number tile graphics into BG0 tile VRAM at tile [tileNum].
     *
     * Ported from src/mapanim_infobox.c: UncompMapBattleBoxNumGfx
     */
    public fun uncompMapBattleBoxNumGfx(tileNum: Int) {
        val hw = hardware ?: return
        val vramDest = hw.tileDataOffset(0) + 0x20 * (tileNum and 0x3FF)
        rom.decompressInto(IMG_MAP_BATTLE_INFO_NUM, hw.vram, vramDest)
    }

    /**
     * Decompresses the battle box numbers, decompresses [src] (HP bar tiles) to VRAM offset
     * `0x20 * 43` (`0x560`), and applies palette bank 5 with [PAL_MAP_BATTLE_INFO_NUM].
     *
     * Ported from src/mapanim_infobox.c: PrepareMapBattleBoxNumGfx
     */
    public fun prepareMapBattleBoxNumGfx(src: Int) {
        uncompMapBattleBoxNumGfx(0x20)
        hardware?.let { hw ->
            rom.decompressInto(src, hw.vram, 0x20 * 43)
            applyPalette(PAL_MAP_BATTLE_INFO_NUM, BM_BGPAL_BANIM_UNK5)
        }
    }

    /**
     * ROM address of the faction's battle info palette: blue ([PAL_MAP_BATTLE_INFO_BLUE]),
     * red ([PAL_MAP_BATTLE_INFO_RED]), green ([PAL_MAP_BATTLE_INFO_GREEN]), or purple
     * ([PAL_MAP_BATTLE_INFO_PURPLE]).
     *
     * Verified against Prologue clear capture (blue and red factions); green and purple factions
     * are unexercised.
     *
     * Ported from src/mapanim_infobox.c: GetBattleInfoPalByFaction
     */
    public fun getBattleInfoPalByFaction(faction: Int): Int {
        return when (faction and FACTION_MASK) {
            FACTION_BLUE -> PAL_MAP_BATTLE_INFO_BLUE
            FACTION_RED -> PAL_MAP_BATTLE_INFO_RED
            FACTION_GREEN -> PAL_MAP_BATTLE_INFO_GREEN
            FACTION_PURPLE -> PAL_MAP_BATTLE_INFO_PURPLE
            else -> 0
        }
    }

    /**
     * Ends every info window proc (`Proc_EndEach(ProcScr_MapBattleInfoBox)`), which runs
     * [procMapInfoBoxOnEnd] for each.
     *
     * Ported from src/mapanim_infobox.c: EndMapAnimInfoWindow
     */
    public fun endMapAnimInfoWindow() {
        engine.endEach(script)
    }

    /**
     * End callback of the info window proc: removes the primary HBlank handler (the gradient
     * scanline effect) and clears BG0 and BG1.
     *
     * Ported from src/mapanim_infobox.c: ProcMapInfoBox_OnEnd
     */
    public fun procMapInfoBoxOnEnd() {
        val hw = hardware ?: return
        hw.hblank.setPrimary(null)
        UiFrame.clearBg0Bg1(hw)
    }

    /**
     * One shake step of the info window: sets Window 0 to a band of half-height
     * [MAInfoFrameProc.unk2A] pixels around the box's top edge, grows [MAInfoFrameProc.unk2A] by
     * 2, and past [SHAKE_LIMIT] disables Window 0 and breaks [proc].
     *
     * Ported from src/mapanim_infobox.c: MapInfoBoxShake
     */
    public fun mapInfoBoxShake(proc: Proc) {
        val state = proc.state as MAInfoFrameProc
        val mid = (state.y + 2) * 8
        hardware?.lcd?.setWin0Box(0, mid - state.unk2A, SCREEN_WIDTH, mid + state.unk2A)

        state.unk2A += 2

        if (state.unk2A > SHAKE_LIMIT) {
            hardware?.lcd?.setWinEnable(0, 0, 0)
            engine.breakProc(proc)
        }
    }

    /**
     * Prepares the info box window for shaking by resetting [MAInfoFrameProc.unk2A] to 0,
     * performing the first shake step via [mapInfoBoxShake], and enabling Window 0.
     *
     * Ported from src/mapanim_infobox.c: MapInfoBox_PrepareForShake
     */
    public fun mapInfoBoxPrepareForShake(proc: Proc) {
        (proc.state as MAInfoFrameProc).unk2A = 0
        mapInfoBoxShake(proc)
        hardware?.lcd?.let { lcd ->
            lcd.setWinEnable(1, 0, 0)
            lcd.setWin0Layers(1, 1, 1, 1, 1)
            lcd.setWOutLayers(0, 0, 1, 1, 1)
        }
    }

    /**
     * Draws the battle info box: resets BG0 and BG1 scroll offsets, decompresses frame and number
     * graphics into VRAM, displays each actor's info box, and initializes scanline gradient
     * effects.
     *
     * Ported from src/mapanim_infobox.c: ProcMapInfoBox_OnDraw
     */
    public fun procMapInfoBoxOnDraw(proc: MAInfoFrameProc) {
        hardware?.lcd?.let { lcd ->
            lcd.setBgPosition(0, 0, 0)
            lcd.setBgPosition(1, 0, 0)
        }

        hardware?.let { hw ->
            val vramDest = hw.tileDataOffset(1) + BM_BGCHR_BANIM_IFBACK * 0x20
            rom.decompressInto(IMG_MAP_BATTLE_INFO_BOX, hw.vram, vramDest)
        }

        prepareMapBattleBoxNumGfx(IMG_MAP_BATTLE_INFO_HP_BAR)

        when (manim.actorCount) {
            1 -> {
                displayBattleInfoBox(proc, 0, -5)
            }
            2 -> {
                displayBattleInfoBox(proc, 0, -1)
                displayBattleInfoBox(proc, 1, -11)
            }
        }

        hooks.initScanline()

        val pal1 = hardware?.palette?.get(1 * PALETTE_COLORS + 1)?.toInt() ?: 0
        val pal2 = hardware?.palette?.get(2 * PALETTE_COLORS + 1)?.toInt() ?: 0
        hooks.startManimFrameGradientScanlineEffect(
            manim.hpInfoY[0] * 8,
            manim.hpInfoY[0] * 8 + 0x20,
            pal1,
            pal2,
        )
    }

    /**
     * Displays the info box for actor [index] offset by [arg2] tiles from [proc]'s X position:
     * sets info box position in [ManimSt], applies faction palette, applies TSA on BG1, writes
     * the unit's name centered on BG0, initializes [ManimSt.hpDisplayedQ4], and calls
     * [sub807BD54].
     *
     * Ported from src/mapanim_infobox.c: DisplayBattleInfoBox
     */
    public fun displayBattleInfoBox(proc: MAInfoFrameProc, index: Int, arg2: Int) {
        val hpInfoX = proc.x + arg2
        val hpInfoY = proc.y
        manim.hpInfoX[index] = hpInfoX
        manim.hpInfoY[index] = hpInfoY

        val unitBytes = manim.unitBytes[index]
        val unitOffset = manim.unitOffset[index]
        val faction = if (unitBytes != null) {
            unitBytes[unitOffset + OFF_INDEX].toInt() and 0xFF
        } else {
            0
        }
        val palAddress = getBattleInfoPalByFaction(faction)
        applyPalette(palAddress, BM_BGPAL_BANIM_IFBACK + index)

        val tsaAddress = TSA_SET_MAP_BATTLE_BOX_GFX[manim.actorCount][index]
        rom.decompressInto(tsaAddress, genericBuffer, 0)

        hardware?.let { hw ->
            val tileBase = BM_BGCHR_BANIM_IFBACK or ((BM_BGPAL_BANIM_IFBACK + index) shl 12)
            val bg1Offset = hpInfoY * BgTilemap.ROW + hpInfoX
            BgTilemap.applyTsa(hw.tilemaps[1], bg1Offset, genericBuffer, 0, tileBase)
            hw.enableBgSyncByMask(1 shl 1)
        }

        if (unitBytes != null) {
            val charData = StructCodec.u32(unitBytes, unitOffset)
            val nameTextId = rom.u16(charData)
            val name = messages.getStringFromIndex(nameTextId)
            val bg0 = hardware?.tilemaps?.get(0) ?: ShortArray(1024)
            val bg0Offset = (hpInfoY + 1) * BgTilemap.ROW + hpInfoX + 2
            text.putStringCentered(bg0, bg0Offset, 0, 9, name)
            hardware?.enableBgSyncByMask(1 shl 0)
        }

        manim.hpDisplayedQ4[index] = manim.hpCur[index] * 16

        sub807BD54(proc, index)
    }

    /**
     * Steps the HP shown for each actor of the info window towards its real HP: a falling value
     * drops by one HP (16 in Q4) per frame. Clears [ManimSt.hpChanging] on a frame in which no
     * actor's value moved.
     *
     * The rising branch (`+4` per frame with the `SONG_75` tick, used by healing) is not written:
     * the Prologue capture never raises an actor's HP in the info box.
     *
     * Ported from src/mapanim_infobox.c: sub_807BCA8
     */
    public fun sub807BCA8(proc: MAInfoFrameProc) {
        var updated = false
        for (i in 0 until manim.actorCount) {
            var shown = manim.hpDisplayedQ4[i]
            val target = manim.hpCur[i] * 16

            if (shown > target) shown -= 16

            if (shown < target) {
                TODO("sub_807BCA8: rising HP (healing) is not exercised by the capture")
            }

            if (shown != manim.hpDisplayedQ4[i]) {
                manim.hpDisplayedQ4[i] = shown
                sub807BD54(proc, i)
                updated = true
            }
        }

        if (!updated && manim.hpChanging != 0) manim.hpChanging = 0
    }

    /**
     * Draws the HP of actor [a] on BG0: the shown HP as three digit tiles at (`hp_info_x + 3`,
     * `hp_info_y + 3`) and the HP bar to its right.
     *
     * Ported from src/mapanim_infobox.c: sub_807BD54
     */
    public fun sub807BD54(proc: MAInfoFrameProc, a: Int) {
        val bg0 = hardware?.tilemaps?.get(0) ?: ShortArray(BgTilemap.ROW * BgTilemap.ROW)
        val shown = manim.hpDisplayedQ4[a] / 16
        val row = (manim.hpInfoY[a] + 3) * BgTilemap.ROW
        val x = manim.hpInfoX[a]

        sub807BA28(bg0, row + x + 3, shown, tileRef(32, BM_BGPAL_BANIM_UNK5), 3, 0, shown >= 100)
        sub807BB40(bg0, row + x + 4, manim.hpMax[a], shown, 0, HP_BAR_TABLE)

        hardware?.enableBgSyncByMask(1 shl 0)
    }

    /**
     * Writes [num] right-aligned as digit tiles into [tilemap], the last digit at [index], and
     * fills the [len] tiles ending at [index] with [blankRef] where no digit stands. With
     * [overflow] set (the number has three digits) the last two tiles show the overflow glyph
     * instead. The trailing blanking loop only repeats what [sub8013168] already cleared when
     * [blankRef] is 0, so the capture cannot tell it from its absence.
     *
     * Ported from src/mapanim_infobox.c: sub_807BA28
     */
    public fun sub807BA28(
        tilemap: ShortArray,
        index: Int,
        num: Int,
        tileRef: Int,
        len: Int,
        blankRef: Int,
        overflow: Boolean,
    ) {
        val buf = CharArray(8)
        var n = num
        for (i in buf.size - 1 downTo 0) {
            buf[i] = '0' + n % 10
            n /= 10
            if (n == 0) {
                for (j in i - 1 downTo 0) buf[j] = ' '
                break
            }
        }

        sub8013168(tilemap, index, buf, buf.size - 1, tileRef, len, overflow)

        var i = len - 1
        while (i > 0 && buf[7 - i] == ' ') {
            tilemap[index - i] = blankRef.toShort()
            --i
        }
    }

    /**
     * Writes one HP bar tile at [index] of [tilemap]: tile `base + min(remaining, span)`, with
     * palette [pal], then lowers [remaining] by [span] - 1 (never below 0). Returns the new
     * [remaining].
     *
     * Ported from src/mapanim_infobox.c: sub_807BB10
     */
    public fun sub807BB10(
        tilemap: ShortArray,
        index: Int,
        remaining: Int,
        pal: Int,
        span: Int,
        base: Int,
    ): Int {
        tilemap[index] = tileRef(base + minOf(remaining, span), pal).toShort()
        return maxOf(remaining + 1 - span, 0)
    }

    /**
     * Draws the HP bar of [maxHp] and [curHp] at [index] of [tilemap] from the segment list
     * [table] (ROM address of `u16` span/base pairs ending with a zero span). C draws one
     * segment for a non-zero [curHp] that scales to none; the capture has no such HP, so that
     * case is not written.
     *
     * Ported from src/mapanim_infobox.c: sub_807BB40
     */
    public fun sub807BB40(
        tilemap: ShortArray,
        index: Int,
        maxHp: Int,
        curHp: Int,
        palIndex: Int,
        table: Int,
    ) {
        var count = 0
        var it = table
        while (rom.u16(it) != 0) {
            count -= 1 - rom.u16(it)
            it += 4
        }
        count += 1

        var filled = if (maxHp == curHp) count else (((count shl 8) / maxHp * curHp) shr 8)
        if (filled == 0 && curHp > 0) {
            TODO("sub_807BB40: the one-segment minimum is not exercised by the capture")
        }

        val pal = rom.u32(PAL_TABLE + 4 * palIndex)
        var at = index
        it = table
        while (rom.u16(it) != 0) {
            filled = sub807BB10(tilemap, at, filled, pal, rom.u16(it), rom.u16(it + 2))
            ++at
            it += 4
        }
    }

    /**
     * Writes the digit characters of [digits] (read backwards from [last] up to the first space)
     * as tiles into [tilemap], the last at [dst] and the earlier ones to its left, after blanking
     * the [len] tiles ending at [dst]. With [overflow] set, C writes the overflow glyph (tile
     * `base + 10`) into the two tiles ending at [dst] instead of digits; the Prologue capture
     * never shows an HP of 100 or more, so that branch is not written. The initial blanking of the
     * [len] tiles is covered by [sub807BA28]'s own blanking, so the capture cannot test it alone.
     *
     * Ported from src/bmlib.c: sub_8013168
     */
    public fun sub8013168(
        tilemap: ShortArray,
        dst: Int,
        digits: CharArray,
        last: Int,
        base: Int,
        len: Int,
        overflow: Boolean,
    ) {
        for (k in 0 until len) tilemap[dst - k] = 0

        var at = dst
        if (overflow) TODO("sub_8013168: the HP overflow glyph is not exercised by the capture")
        var src = last
        while (digits[src] != ' ') {
            tilemap[at--] = (digits[src].code + base - '0'.code).toShort()
            --src
        }
    }

    private fun tileRef(chr: Int, pal: Int): Int = chr + (pal shl 12)

    private fun applyPalette(palAddress: Int, bank: Int) {
        val hw = hardware ?: return
        val base = bank * PALETTE_COLORS
        for (i in 0 until PALETTE_COLORS) {
            hw.palette[base + i] = rom.u16(palAddress + 2 * i).toShort()
        }
        hw.enablePaletteSync()
    }

    /**
     * Companion constants and tables for the map anim info box.
     */
    public companion object {
        /** Root tree for info window proc (`PROC_TREE_3`). */
        public const val TREE_INFOBOX: Int = 3

        /** `MapInfoBoxShake` breaks once [MAInfoFrameProc.unk2A] exceeds this. */
        public const val SHAKE_LIMIT: Int = 0x10

        /** `SCREEN_WIDTH` in pixels: the right edge of the shake window. */
        public const val SCREEN_WIDTH: Int = 240

        /** ROM address of `gUnknown_089A3648`: the HP bar segments (span, base tile pairs). */
        public const val HP_BAR_TABLE: Int = 0x089A3648

        /** ROM address of `gUnknown_089A3668`: the HP bar palette bank per bar style. */
        public const val PAL_TABLE: Int = 0x089A3668

        /** Size of `gGenericBuffer` (src/variables.c: `u8 gGenericBuffer[0x2000]`). */
        public const val GENERIC_BUFFER_SIZE: Int = 0x2000

        /** Offset of `unit.index` in `struct Unit` (include/bmunit.h). */
        public const val OFF_INDEX: Int = 0x0B

        /** Number of 16-bit colours per GBA palette bank. */
        public const val PALETTE_COLORS: Int = 16

        /** ROM address of `Img_MapBattleInfoBox`. */
        public const val IMG_MAP_BATTLE_INFO_BOX: Int = 0x089AD500

        /** ROM address of `Pal_MapBattleInfoBlue`. */
        public const val PAL_MAP_BATTLE_INFO_BLUE: Int = 0x089AD648

        /** ROM address of `Pal_MapBattleInfoRed`. */
        public const val PAL_MAP_BATTLE_INFO_RED: Int = 0x089AD668

        /** ROM address of `Pal_MapBattleInfoGreen`. */
        public const val PAL_MAP_BATTLE_INFO_GREEN: Int = 0x089AD688

        /** ROM address of `Pal_MapBattleInfoPurple`. */
        public const val PAL_MAP_BATTLE_INFO_PURPLE: Int = 0x089AD6A8

        /** ROM address of `Tsa_MapBattleBoxGfx1`. */
        public const val TSA_MAP_BATTLE_BOX_GFX1: Int = 0x089AD6C8

        /** ROM address of `Tsa_MapBattleBoxGfx2`. */
        public const val TSA_MAP_BATTLE_BOX_GFX2: Int = 0x089AD714

        /** ROM address of `Tsa_MapBattleBoxGfx3`. */
        public const val TSA_MAP_BATTLE_BOX_GFX3: Int = 0x089AD74C

        /** ROM address of `Img_MapBattleInfoHpBar`. */
        public const val IMG_MAP_BATTLE_INFO_HP_BAR: Int = 0x089AD78C

        /** ROM address of `Img_MapBattleInfoNum`. */
        public const val IMG_MAP_BATTLE_INFO_NUM: Int = 0x089AD868

        /** ROM address of `Pal_MapBattleInfoNum`. */
        public const val PAL_MAP_BATTLE_INFO_NUM: Int = 0x08A1D79C

        /** `BM_BGCHR_BANIM_IFBACK` (include/bmio.h). */
        public const val BM_BGCHR_BANIM_IFBACK: Int = 1

        /** `BM_BGPAL_BANIM_IFBACK` (include/bmio.h). */
        public const val BM_BGPAL_BANIM_IFBACK: Int = 1

        /** `BM_BGPAL_BANIM_UNK5` (include/bmio.h). */
        public const val BM_BGPAL_BANIM_UNK5: Int = 5

        /** `FACTION_BLUE` (include/bmunit.h). */
        public const val FACTION_BLUE: Int = 0x00

        /** `FACTION_GREEN` (include/bmunit.h). */
        public const val FACTION_GREEN: Int = 0x40

        /** `FACTION_RED` (include/bmunit.h). */
        public const val FACTION_RED: Int = 0x80

        /** `FACTION_PURPLE` (include/bmunit.h). */
        public const val FACTION_PURPLE: Int = 0xC0

        /** Faction bits mask (`unit->index & 0xC0`). */
        public const val FACTION_MASK: Int = 0xC0

        /**
         * `TsaSet_MapBattleBoxGfx[3][2]` (src/mapanim_infobox.c:291).
         */
        public val TSA_SET_MAP_BATTLE_BOX_GFX: Array<IntArray> = arrayOf(
            intArrayOf(TSA_MAP_BATTLE_BOX_GFX1, TSA_MAP_BATTLE_BOX_GFX1),
            intArrayOf(TSA_MAP_BATTLE_BOX_GFX1, TSA_MAP_BATTLE_BOX_GFX1),
            intArrayOf(TSA_MAP_BATTLE_BOX_GFX3, TSA_MAP_BATTLE_BOX_GFX2),
        )
    }
}
