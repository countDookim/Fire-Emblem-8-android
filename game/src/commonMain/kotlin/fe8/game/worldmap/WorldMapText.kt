package fe8.game.worldmap

import fe8.game.scene.TalkFlags
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * `struct GMapTextProc` (src/worldmap_text.c): per-proc state of the world map text box handler
 * `ProcScr_WorldMapTextHandler`.
 */
public class GMapTextProc {
    /** `drawAtBottom` (+0x29, `u8`): 1 puts the text box at the bottom of the screen, else top. */
    public var drawAtBottom: Int = 0

    /** `visible` (+0x2A, `u8`): nonzero while the text box sprite is drawn. */
    public var visible: Int = 0
}

/**
 * The talk (dialogue text) services [WorldMapText.startWmTextMsg] calls; implemented over the
 * talk system (`src/scene.c`) when wired.
 */
public interface WmTextHost {
    /** `EndTalk`: ends any running talk. */
    public fun endTalk()

    /**
     * `InitSpriteTalk` (src/scene.c:334): sets up sprite-drawn talk text at OBJ tile [chr] with
     * palette [pal] and [lines] text lines.
     *
     * NOTE: the decomp's parameter order is `(chr, lines, palId)`; this interface says `(chr, pal,
     * lines)` and the implementations swap them when forwarding. The only call
     * ([WorldMapText.startWmTextMsg]) passes 2 and 2, so the order does not matter there.
     */
    public fun initSpriteTalk(chr: Int, pal: Int, lines: Int)

    /** `StartTalkMsg(x, y, msg)`: starts the talk text of message [msg] at tile ([x], [y]). */
    public fun startTalkMsg(x: Int, y: Int, msg: Int)

    /** `SetTalkPrintDelay(delay)`: sets the delay between printed characters. */
    public fun setTalkPrintDelay(delay: Int)

    /** `SetTalkFlag(flag)` (src/scene.c) with a `TALK_FLAG_*` bit. */
    public fun setTalkFlag(flag: Int)
}

/**
 * The world map text box of `src/worldmap_text.c`: the sprite window that the world map event
 * commands `EventC6_WmDisplayText`, `EventC7_`/`EventC8_` (show at the bottom / top) and
 * `EventC9_RemoveWmText` (`src/eventscr_gmap.c`) show and hide, and the talk text is printed in.
 * [startWmTextHandler] is called when the world map main proc starts (`worldmap_main.c:534`).
 *
 * @param engine the proc scheduler
 * @param hardware BG hardware (VRAM and the palette sync flag)
 * @param hblank the scanline-effect state, whose `gWmHblankStatus` bit 0 this clears (no code in
 *   `worldmap_hb.c` reads bit 0; its meaning is unknown)
 * @param layers the sprite lists the text window is put on
 * @param host the talk services of [startWmTextMsg]
 */
public class WorldMapText(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val hblank: WorldMapHblank,
    private val layers: SpriteLayers,
    private val host: WmTextHost,
) {
    /**
     * `Sprite_WmText`: the text box strip, three 64x32 and one 32x32 object side by side (x 8
     * to 232, y 8) using OBJ tiles 0x200..0x218 (`OAM2_CHR`), where the sprite talk text is
     * drawn.
     */
    public val sprite: SpriteObject = SpriteObject(0x08A3EE28, SPRITE_DATA)

    /**
     * `ProcScr_WorldMapTextHandler`: init ([init]), yield once, then every frame [loopDrawText].
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_WorldMapTextHandler",
        listOf(
            mark(PROC_MARK_WMSTUFF),
            call(ProcFunc { init(it.state as GMapTextProc) }),
            yield(),
            repeat(ProcFunc { loopDrawText(it.state as GMapTextProc) }),
            end(),
        ),
    ) { GMapTextProc() }

    /**
     * Ported from src/worldmap_text.c: ClearWmTextVram
     *
     * Empties the text box's tiles: zeroes the first 0x380 bytes of each of four 0x400-byte rows
     * at VRAM 0x06014000 (OBJ tile 0x200 on): the strip's 28 tiles (0x380 bytes) in each of its 4
     * tile rows, which sit 0x400 bytes apart (32 tiles per row, the 2D OBJ mapping).
     */
    public fun clearWmTextVram() {
        for (i in 0 until 4) {
            hardware.vramFill16(0, TEXT_VRAM + i * 0x400, 0x380)
        }
    }

    /**
     * Ported from src/worldmap_text.c: WmText_Init
     *
     * `PROC_CALL` at proc start: the box starts hidden and set to draw at the bottom, with its
     * tiles cleared and `gWmHblankStatus` bit 0 cleared.
     */
    public fun init(proc: GMapTextProc) {
        proc.drawAtBottom = 1
        proc.visible = 0
        clearWmTextVram()
        hblank.status = hblank.status and 1.inv()
    }

    /**
     * Ported from src/worldmap_text.c: WmText_Loop_DrawText
     *
     * `PROC_REPEAT` callback: while visible, puts [sprite] on sprite layer 1 at y 111 (bottom,
     * which with the strip's own y 8 is 119..151 on the 160-line screen) or y 0 (top), with
     * OBJ palette 2 (`OAM2_PAL(2)`).
     */
    public fun loopDrawText(proc: GMapTextProc) {
        if (proc.visible == 0) return
        val y = if (proc.drawAtBottom == 1) 111 else 0
        layers.putExt(1, 0, y, sprite, OAM2_PAL_2)
    }

    /**
     * Ported from src/worldmap_text.c: ShowWmText
     *
     * Shows the text box (`EventC7_` passes 1, `EventC8_` passes 0), after clearing its tiles.
     * Does nothing if the handler proc does not exist.
     *
     * @param atBottom 1 to draw at the bottom of the screen, 0 at the top
     */
    public fun showWmText(atBottom: Int) {
        val proc = engine.find(script) ?: return
        val state = proc.state as GMapTextProc
        clearWmTextVram()
        hblank.status = hblank.status and 1.inv()
        state.drawAtBottom = atBottom and 0xFF
        state.visible = 1
    }

    /**
     * Ported from src/worldmap_text.c: HideWmText
     *
     * Hides the text box (`EventC9_RemoveWmText`, and several world map exits in
     * `worldmap_main.c`). Does nothing if the handler proc does not exist.
     */
    public fun hideWmText() {
        val proc = engine.find(script) ?: return
        hblank.status = hblank.status and 1.inv()
        (proc.state as GMapTextProc).visible = 0
    }

    /**
     * Transcribed from src/worldmap_text.c: StartWmTextHandler (not verified: the trace shows the
     * proc exists from step 4901 but not its parent link as a call result)
     *
     * Starts the text box handler as a child of [parent] (the world map main proc).
     */
    public fun startWmTextHandler(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Ported from src/worldmap_text.c: StartWmTextMsg
     *
     * Prints a world map text box message (`EventC6_WmDisplayText`): ends the running talk and
     * starts [textId] as a sprite talk at tile (1, 15) (OBJ tile 0x200, 2 lines, palette 2) with
     * print delay 4 and `TALK_FLAG_SPRITE` (text drawn with OBJ tiles, not BG), `TALK_FLAG_7`
     * (meaning unknown; in `scene.c` it plays `SONG_7A` per character and changes the `[2NL]`
     * and press-key arrow handling), `TALK_FLAG_NOSKIP` (B/START cannot skip it) and
     * `TALK_FLAG_INSTANTSHIFT` (the `[2NL]` control code clears the lines at once instead of
     * shifting them).
     *
     * @param textId message id
     */
    public fun startWmTextMsg(textId: Int) {
        host.endTalk()
        host.initSpriteTalk(0x200, 2, 2)
        hardware.enablePaletteSync()
        host.startTalkMsg(1, 15, textId)
        host.setTalkPrintDelay(4)
        host.setTalkFlag(TalkFlags.SPRITE)
        host.setTalkFlag(TalkFlags.FLAG_7)
        host.setTalkFlag(TalkFlags.NOSKIP)
        host.setTalkFlag(TalkFlags.INSTANTSHIFT)
    }

    /** Constants and helpers of the text window. */
    public companion object {
        /** `PROC_MARK_WMSTUFF`: proc mark shared by the world map procs (include/constants). */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** VRAM offset of `0x06014000` (`0x06010000` + OBJ tile 0x200 * 32 bytes): text tiles. */
        public const val TEXT_VRAM: Int = 0x14000

        /** `OAM2_PAL(2)`: palette 2 in the attr2 palette field (bits 12-15). */
        private const val OAM2_PAL_2 = 2 shl 12

        /** `Sprite_WmText` (src/worldmap_text.c): the object count, then attr0/1/2 per object. */
        private val SPRITE_DATA = shortArrayOf(
            4,
            0x4008, 0xC008.toShort(), 0x200,
            0x4008, 0xC048.toShort(), 0x208,
            0x4008, 0xC088.toShort(), 0x210,
            0x0008, 0x80C8.toShort(), 0x218,
        )

        /**
         * Transcribed from src/worldmap_text.c: sub_80C34D0 (not verified: no step of the traced
         * opening calls it)
         *
         * Fills `dst[dstOffset until dstOffset + count]` with a per-channel linear gradient from
         * [colorA] (index 0) towards [colorB] (index [count]); BGR555 colors, so a palette
         * ramp between two colors. The decomp name is `sub_80C34D0`; no decomp caller of it was
         * found, so which world map effect uses it is unknown.
         *
         * @param count number of colors written (`b` in the decomp)
         */
        public fun colorGradient(
            dst: ShortArray,
            dstOffset: Int,
            count: Int,
            colorA: Int,
            colorB: Int,
        ) {
            for (i in 0 until count) {
                val k = count - i
                val r = ((k * (colorA and 0x1F) + i * (colorB and 0x1F)) / count) and 0x1F
                val g = ((k * (colorA and 0x3E0) + i * (colorB and 0x3E0)) / count) and 0x3E0
                val b = ((k * (colorA and 0x7C00) + i * (colorB and 0x7C00)) / count) and 0x7C00
                dst[dstOffset + i] = (r + g + b).toShort()
            }
        }
    }
}
