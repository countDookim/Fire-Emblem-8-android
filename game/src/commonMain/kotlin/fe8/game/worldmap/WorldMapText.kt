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

/** `gWmHblankStatus`: the world map's H-blank effect flags (bit 0 text, 2 line fade). */
public class WmHblankStatus {
    /** The raw byte. */
    public var bits: Int = 0
}

/** `struct GMapTextProc`: the fields of `ProcScr_WorldMapTextHandler`. */
public class GMapTextProc {
    /** `drawAtBottom` (+0x29). */
    public var drawAtBottom: Int = 0

    /** `visible` (+0x2A). */
    public var visible: Int = 0
}

/** The talk services `StartWmTextMsg` calls; implemented over the talk system when wired. */
public interface WmTextHost {
    /** `EndTalk`. */
    public fun endTalk()

    /** `InitSpriteTalk(chr, pal, lines)`. */
    public fun initSpriteTalk(chr: Int, pal: Int, lines: Int)

    /** `StartTalkMsg(x, y, msg)`. */
    public fun startTalkMsg(x: Int, y: Int, msg: Int)

    /** `SetTalkPrintDelay(delay)`. */
    public fun setTalkPrintDelay(delay: Int)

    /** `SetTalkFlag(flag)` with a `TALK_FLAG_*` bit. */
    public fun setTalkFlag(flag: Int)
}

/**
 * The world map text box of `src/worldmap_text.c`: the sprite window the prologue's text commands
 * show and hide.
 *
 * @param engine the proc scheduler
 * @param hardware BG hardware (VRAM and the palette sync flag)
 * @param hblank `gWmHblankStatus`
 * @param layers the sprite lists the text window is put on
 * @param host the talk services of [startWmTextMsg]
 */
public class WorldMapText(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val hblank: WmHblankStatus,
    private val layers: SpriteLayers,
    private val host: WmTextHost,
) {
    /** `Sprite_WmText`: three 64x32 and one 32x32 object at OBJ tiles 0x200..0x218. */
    public val sprite: SpriteObject = SpriteObject(0x08A3EE28, SPRITE_DATA)

    /** `ProcScr_WorldMapTextHandler`. */
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
     * Zeroes the first 0x380 bytes of four 0x400-byte rows at VRAM 0x06014000 (OBJ tiles 0x200..).
     */
    public fun clearWmTextVram() {
        for (i in 0 until 4) {
            hardware.vramFill16(0, TEXT_VRAM + i * 0x400, 0x380)
        }
    }

    /**
     * Ported from src/worldmap_text.c: WmText_Init
     *
     * Draws at the bottom, hidden, text VRAM cleared, text H-blank bit off.
     */
    public fun init(proc: GMapTextProc) {
        proc.drawAtBottom = 1
        proc.visible = 0
        clearWmTextVram()
        hblank.bits = hblank.bits and 1.inv()
    }

    /**
     * Ported from src/worldmap_text.c: WmText_Loop_DrawText
     *
     * While visible puts [sprite] on layer 1 at y 111 (bottom) or 0, palette 2.
     */
    public fun loopDrawText(proc: GMapTextProc) {
        if (proc.visible == 0) return
        val y = if (proc.drawAtBottom == 1) 111 else 0
        layers.putExt(1, 0, y, sprite, OAM2_PAL_2)
    }

    /**
     * Ported from src/worldmap_text.c: ShowWmText
     *
     * @param atBottom 1 to draw at the bottom of the screen, 0 at the top
     */
    public fun showWmText(atBottom: Int) {
        val proc = engine.find(script) ?: return
        val state = proc.state as GMapTextProc
        clearWmTextVram()
        hblank.bits = hblank.bits and 1.inv()
        state.drawAtBottom = atBottom and 0xFF
        state.visible = 1
    }

    /** Ported from src/worldmap_text.c: HideWmText */
    public fun hideWmText() {
        val proc = engine.find(script) ?: return
        hblank.bits = hblank.bits and 1.inv()
        (proc.state as GMapTextProc).visible = 0
    }

    /**
     * Transcribed from src/worldmap_text.c: StartWmTextHandler (not verified: the trace shows the
     * proc exists from step 4901 but not its parent link as a call result)
     */
    public fun startWmTextHandler(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Transcribed from src/worldmap_text.c: StartWmTextMsg (not verified: it only forwards to the
     * talk system, and the glyph VRAM it produces is not compared)
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

    /** Constants of the text window. */
    public companion object {
        /** `PROC_MARK_WMSTUFF`. */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** VRAM offset of `0x06014000`, the text window's OBJ tiles. */
        public const val TEXT_VRAM: Int = 0x14000

        private const val OAM2_PAL_2 = 2 shl 12

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
         * [colorA] (index 0) towards [colorB] (index [count]).
         */
        public fun sub80C34D0(
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
