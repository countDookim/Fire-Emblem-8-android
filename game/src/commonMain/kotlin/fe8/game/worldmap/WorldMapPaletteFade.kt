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

/** `struct GmapPalFadeProc`. */
public class GmapPalFadeProc {
    /** `unk_2c`: duration in frames. */
    public var duration: Int = 0

    /** `unk_30`: frames elapsed. */
    public var timer: Int = 0

    /** `unk_34`: number of colors. */
    public var count: Int = 0

    /** `unk_35`: first palette buffer index written. */
    public var dstIndex: Int = 0

    /** `unk_38`: start colors. */
    public var from: ShortArray = ShortArray(0)

    /** `unk_3c`: target colors. */
    public var to: ShortArray = ShortArray(0)
}

/**
 * `struct GmPalFadeInput`.
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
 * The world map palette fade of `src/worldmap_palette_fade.c`. Everything here is transcribed
 * (not verified: the traced opening never starts `ProcScr_GmapPalFade`).
 *
 * @param engine the proc scheduler
 * @param hardware the palette buffer (`gPaletteBuffer`) and its sync flag
 */
public class WorldMapPaletteFade(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
) {
    /** `ProcScr_GmapPalFade`. */
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

    private fun channel(from: Int, to: Int, scale: Int): Int = from + (to - from) * scale / 0x40

    /**
     * Transcribed from src/worldmap_palette_fade.c: StartGmPalFade (not verified: not run in the
     * traced opening)
     *
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

    /** Transcribed from src/worldmap_palette_fade.c: IsGmPalFadeActive (not verified). */
    public fun isGmPalFadeActive(): Boolean = engine.find(script) != null

    /**
     * Transcribed from src/worldmap_palette_fade.c: EndGmPalFade (not verified): snaps the running
     * fade to its target and ends it.
     */
    public fun endGmPalFade() {
        val proc = engine.find(script) ?: return
        val s = proc.state as GmapPalFadeProc
        s.to.copyInto(hardware.palette, s.dstIndex, 0, s.count)
        hardware.enablePaletteSync()
        engine.endEach(script)
    }

    /**
     * Transcribed from src/worldmap_palette_fade.c: StartGmPalFade_ (not verified): fades 64
     * colors at palette index 144 from [from] to [to] (the two ROM palettes the original names).
     */
    public fun startGmPalFadeDefault(
        parent: Proc?,
        duration: Int,
        from: ShortArray,
        to: ShortArray,
    ): Proc? = startGmPalFade(parent, GmPalFadeInput(from, to, 64, 144, duration))
}
