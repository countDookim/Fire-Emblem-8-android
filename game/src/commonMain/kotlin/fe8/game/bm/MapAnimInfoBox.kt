package fe8.game.bm

import fe8.game.RomView
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
     * Hooks for callbacks and unported functions belonging to later milestones.
     */
    public class Hooks {
        /**
         * Stand-in until EP-21: `ProcMapInfoBox_OnEnd` (src/mapanim_infobox.c).
         */
        public var procMapInfoBoxOnEnd: () -> Unit = {}

        /**
         * Stand-in until EP-21: `MapInfoBoxShake` (src/mapanim_infobox.c).
         */
        public var mapInfoBoxShake: (MAInfoFrameProc) -> Unit = {}

        /**
         * Stand-in until EP-21: `sub_807BD54` (src/mapanim_infobox.c).
         */
        public var sub807BD54: (proc: MAInfoFrameProc, index: Int) -> Unit = { _, _ -> }

        /**
         * Stand-in until EP-21: `sub_807BCA8` (src/mapanim_infobox.c).
         */
        public var mapInfoBoxHpDrain: (MAInfoFrameProc) -> Unit = {}

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
            setEndCb(ProcFunc { hooks.procMapInfoBoxOnEnd() }),
            sleep(1),
            call(ProcFunc { mapInfoBoxPrepareForShake(it.state as MAInfoFrameProc) }),
            call(ProcFunc { procMapInfoBoxOnDraw(it.state as MAInfoFrameProc) }),
            repeat(ProcFunc { hooks.mapInfoBoxShake(it.state as MAInfoFrameProc) }),
            repeat(ProcFunc { hooks.mapInfoBoxHpDrain(it.state as MAInfoFrameProc) }),
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
     * Prepares the info box window for shaking by resetting [MAInfoFrameProc.unk2A] to 0,
     * performing the first shake step via [Hooks.mapInfoBoxShake], and enabling Window 0.
     *
     * Ported from src/mapanim_infobox.c: MapInfoBox_PrepareForShake
     */
    public fun mapInfoBoxPrepareForShake(proc: MAInfoFrameProc) {
        proc.unk2A = 0
        hooks.mapInfoBoxShake(proc)
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
     * [Hooks.sub807BD54].
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

        hooks.sub807BD54(proc, index)
    }

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
