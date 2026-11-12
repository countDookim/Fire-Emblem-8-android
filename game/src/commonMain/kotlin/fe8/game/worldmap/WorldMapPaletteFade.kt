package fe8.game.worldmap

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * Per-proc state of the world map palette fade `ProcScr_GmapPalFade`: the fields of
 * `struct GmapPalFadeProc` (src/worldmap_palette_fade.c; decomp names `unk_xx` given per field).
 * The decomp's `unk_40` (`gPaletteBuffer + unk_35`, set by `GmPalFade_Init`) is [dstIndex] here.
 */
public class GmapPalFadeProc {
    /** `unk_2c`: length of the fade in frames. */
    public var duration: Int = 0

    /** `unk_30`: frames elapsed so far. */
    public var timer: Int = 0

    /** `unk_34` (`u8`): number of colors faded. */
    public var count: Int = 0

    /** `unk_35` (`u8`): index in `gPaletteBuffer` of the first color written. */
    public var dstIndex: Int = 0

    /** `unk_38`: the start colors (BGR555), [count] of them. */
    public var from: ShortArray = ShortArray(0)

    /** `unk_3c`: the target colors (BGR555), [count] of them. */
    public var to: ShortArray = ShortArray(0)
}

/**
 * `struct GmPalFadeInput` (src/worldmap_palette_fade.c): the arguments of
 * [WorldMapPaletteFade.startGmPalFade].
 *
 * @property from `unk_00`, the start colors
 * @property to `unk_04`, the target colors
 * @property count `unk_08`, colors to fade
 * @property dstIndex `unk_09`, first palette buffer index
 * @property duration `unk_0c`, frames; below 1 the target is copied at once
 */
public class GmPalFadeInput(
    public val from: ShortArray,
    public val to: ShortArray,
    public val count: Int,
    public val dstIndex: Int,
    public val duration: Int,
)

/**
 * The world map palette fade of `src/worldmap_palette_fade.c`: blends a run of palette colors
 * from one set to another, one step per frame. The world map event commands
 * `EventAD_WmFadeToDarker` / `EventAE_WmFadeToDarkerWait` (`src/eventscr_gmap.c`) use it to fade
 * the world map's BG palette to a darker one. Everything here is transcribed (not verified: the
 * traced opening never starts `ProcScr_GmapPalFade`).
 *
 * @param engine the proc scheduler
 * @param hardware the palette buffer (`gPaletteBuffer`) and its sync flag
 */
public class WorldMapPaletteFade(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
) {
    /**
     * `ProcScr_GmapPalFade`: yields once, resets the timer (the decomp's `GmPalFade_Init`) and
     * runs [loop] each frame until the fade is done. The decomp also names the proc "Gmap Pal
     * Fade"; the name is not set here.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmapPalFade",
        listOf(
            mark(WorldMapText.PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc {}),
            yield(),
            call(ProcFunc { (it.state as GmapPalFadeProc).timer = 0 }),
            repeat(ProcFunc { loop(it) }),
            end(),
        ),
    ) { GmapPalFadeProc() }

    /**
     * Transcribed from src/worldmap_palette_fade.c: GmPalFade_Loop (not verified: not run in the
     * traced opening)
     *
     * `PROC_REPEAT` callback: each frame sets every color to the linear mix of start and target
     * per RGB channel, `from + (to - from) * scale / 0x40` with `scale = timer * 0x40 / duration`,
     * and requests a palette upload; when `timer >= duration` it copies the target exactly and
     * ends.
     */
    private fun loop(proc: Proc) {
        val s = proc.state as GmapPalFadeProc
        s.timer++
        if (s.timer < s.duration) {
            val scale = s.timer * 0x40 / s.duration
            for (i in 0 until s.count) {
                val a = s.from[i].toInt()
                val b = s.to[i].toInt()
                val red = channel(a and 0x1F, b and 0x1F, scale)
                val green = channel((a shr 5) and 0x1F, (b shr 5) and 0x1F, scale)
                val blue = channel((a shr 10) and 0x1F, (b shr 10) and 0x1F, scale)
                hardware.palette[s.dstIndex + i] = ((blue shl 10) + (green shl 5) + red).toShort()
            }
        } else {
            s.to.copyInto(hardware.palette, s.dstIndex, 0, s.count)
            engine.breakProc(proc)
        }
        hardware.enablePaletteSync()
    }

    /** One 5-bit color channel mixed [scale]/0x40 of the way from [from] to [to]. */
    private fun channel(from: Int, to: Int, scale: Int): Int = from + (to - from) * scale / 0x40

    /**
     * Transcribed from src/worldmap_palette_fade.c: StartGmPalFade (not verified: not run in the
     * traced opening)
     *
     * Starts a palette fade (or applies the target at once if `duration < 1`).
     *
     * @param input the colors, count, destination and length (`struct GmPalFadeInput`)
     * @param parent blocked until the fade ends, or null for a tree 3 root
     * @return the fade proc, null if the target was copied at once
     */
    public fun startGmPalFade(parent: Proc?, input: GmPalFadeInput): Proc? {
        if (input.duration >= 1) {
            val started = if (parent != null) {
                engine.startBlocking(script, parent)
            } else {
                engine.start(script, 3)
            }
            val proc = started ?: return null
            val s = proc.state as GmapPalFadeProc
            s.duration = input.duration
            s.count = input.count
            s.dstIndex = input.dstIndex
            s.from = input.from
            s.to = input.to
            return proc
        }
        input.to.copyInto(hardware.palette, input.dstIndex, 0, input.count)
        hardware.enablePaletteSync()
        return null
    }

    /**
     * Transcribed from src/worldmap_palette_fade.c: IsGmPalFadeActive (not verified: no
     * @VerifiesPort test names it).
     *
     * Whether a fade is running; `EventAE_WmFadeToDarkerWait` waits on it.
     */
    public fun isGmPalFadeActive(): Boolean = engine.find(script) != null

    /**
     * Transcribed from src/worldmap_palette_fade.c: EndGmPalFade (not verified: no @VerifiesPort
     * test names it): snaps the running fade to its target and ends it
     * (`EventAE_WmFadeToDarkerWait` does this when the event is skipped).
     */
    public fun endGmPalFade() {
        val proc = engine.find(script) ?: return
        val s = proc.state as GmapPalFadeProc
        s.to.copyInto(hardware.palette, s.dstIndex, 0, s.count)
        hardware.enablePaletteSync()
        engine.endEach(script)
    }

    /**
     * Transcribed from src/worldmap_palette_fade.c: StartGmPalFade_ (not verified: no @VerifiesPort
     * test names it): fades 64 colors at palette index 144 (palette banks 9-12, where
     * `worldmap_screen.c:295` loads `gUnknown_08A95FE4`) from [from] to [to]. The original takes
     * only the duration and uses the ROM palettes `gUnknown_08A95FE4` (start) and
     * `gUnknown_08A95F64` (target, the darker one: `EventAD_WmFadeToDarker`); here the caller
     * passes them. `EventAD_WmFadeToDarker` passes duration 0 when skipping.
     *
     * @param duration frames (`unk` of `StartGmPalFade_`)
     */
    public fun startGmPalFadeDefault(
        parent: Proc?,
        duration: Int,
        from: ShortArray,
        to: ShortArray,
    ): Proc? = startGmPalFade(parent, GmPalFadeInput(from, to, 64, 144, duration))
}
