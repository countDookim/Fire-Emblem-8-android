package fe8.game.worldmap

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.SpriteAnimPlayer

/**
 * The node icons and the "merge next node" flag of the world map (`src/worldmap_screen2.c`,
 * `ProcScr_GmNodeIconDisplay`): every frame it puts a sprite on each valid node (the places the
 * party can visit), and, while `merge_next_node` is set, an animated flag at node `nodeId`.
 * Like the decomp's `skip` byte, [skipGmNodeIconDisplay] / [unskipGmNodeIconDisplay] are named
 * the wrong way round for what they do: the loop draws only while `skip` is set
 * (`worldmap_screen2.c:180`), so "skip" turns the icons on and "unskip" off.
 *
 * @param engine the proc scheduler
 * @param ap the sprite animation player (`src/ap.c`)
 * @param gmData `gGMData`
 * @param hooks the unported callees
 */
public class WorldMapNodeIcons(
    private val engine: ProcEngine,
    private val ap: SpriteAnimPlayer,
    private val gmData: GmData,
    private val hooks: Hooks = Hooks(),
) {
    /**
     * Branches of [gmapScreen2Loop] that no capture runs; each defaults to doing nothing. The
     * decomp draws them inside `GmapScreen2_Loop` (src/worldmap_screen2.c:166-250).
     */
    public class Hooks(
        /**
         * The body of the node loop for a valid node (`state & 1`): the icon sprite through
         * `PutSpriteExt`. Stand-in until M6-I: no node is valid in the prologue.
         */
        public val drawNodeIcon: (proc: GmNodeIconDisplayProc, node: Int) -> Unit = { _, _ -> },
        /**
         * The `merge_next_node` flag drawn through `AP_Update`. Stand-in until M6-I: the flag is
         * never set in the prologue.
         */
        public val drawMergeFlag: (proc: GmNodeIconDisplayProc) -> Unit = {},
    )

    /**
     * `ProcScr_GmNodeIconDisplay` (proc name "GmapScreen"): init, then [gmapScreen2Loop] every
     * frame; the end callback deletes the flag animation.
     *
     * Transcribed from src/worldmap_screen2.c: ProcScr_GmNodeIconDisplay (not verified: script
     * order checked against the `procs` capture in WorldMapNodeIconCursorTest, which cannot tag
     * a script)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmNodeIconDisplay",
        listOf(
            name("GmapScreen"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapScreen2Destruct(it.state as GmNodeIconDisplayProc) }),
            call(ProcFunc { gmapScreen2Init(it.state as GmNodeIconDisplayProc) }),
            repeat(ProcFunc { gmapScreen2Loop(it.state as GmNodeIconDisplayProc) }),
            end(),
        ),
    ) { GmNodeIconDisplayProc() }

    /**
     * Ported from src/worldmap_screen2.c: StartGmNodeIconDisplay
     *
     * Starts the proc in root tree [rootIndex] and creates the flag animation (definition
     * `Sprite_08A97AEC`, animation 1, on sprite layer 11). The animation's tile base is the
     * icon tiles at `chr + 0x1000` with palette [palId] and `OAM2_LAYER(2)` (bits 10-11).
     *
     * @param chr VRAM offset of the node icon tiles
     * @param palId OBJ palette bank of the icons
     * @param unk `unk_31` in the decomp (only stored in worldmap_screen2.c: meaning unknown)
     * @param screen the world map screen proc (`pScreenProc`), whose scroll the icons follow
     */
    public fun startGmNodeIconDisplay(
        rootIndex: Int,
        chr: Int,
        palId: Int,
        unk: Int,
        screen: GmScreenProc,
    ): Proc {
        val proc = engine.start(script, rootIndex)
        val state = proc.state as GmNodeIconDisplayProc
        state.chr = chr
        state.pal = palId
        state.unk31 = unk
        state.screen = screen
        state.mergeNextNode = false
        state.nodeId = 0
        val handle = ap.create(SPRITE_FLAG, 11)
        state.ap = handle
        ap.switchAnimation(handle, 1)
        checkNotNull(handle).tileBase = ((state.chr + 0x1000) / CHR_SIZE) + (state.pal shl 12) +
            (2 shl 10)
        return proc
    }

    /**
     * Ported from src/worldmap_screen2.c: SkipGmNodeIconDisplay
     *
     * Sets `skip`, which makes the node icons draw (see the class note).
     */
    public fun skipGmNodeIconDisplay(proc: GmNodeIconDisplayProc) {
        proc.skip = true
    }

    /**
     * Ported from src/worldmap_screen2.c: UnskipGmNodeIconDisplay
     *
     * Clears `skip`, so no node icons are drawn (the big map hides them this way).
     */
    public fun unskipGmNodeIconDisplay(proc: GmNodeIconDisplayProc) {
        proc.skip = false
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Destruct
     *
     * End callback: deletes the flag animation handle.
     */
    public fun gmapScreen2Destruct(proc: GmNodeIconDisplayProc) {
        ap.delete(proc.ap)
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Init
     *
     * Clears the blend bit mask `unk_34` and sets `skip`, so the icons start out drawn.
     */
    public fun gmapScreen2Init(proc: GmNodeIconDisplayProc) {
        proc.unk34 = 0
        proc.skip = true
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Loop
     *
     * While `skip` is set, draws every valid node and the merge flag. Only the `skip` test and
     * the node loop run in the prologue capture (no node is valid, `merge_next_node` is never
     * set); the drawing bodies are the [Hooks] and are not verified.
     */
    public fun gmapScreen2Loop(proc: GmNodeIconDisplayProc) {
        if (!proc.skip) return
        for (i in 0 until NODE_COUNT) {
            if (gmData.nodeState(i) and 1 == 0) continue
            hooks.drawNodeIcon(proc, i)
        }
        if (proc.mergeNextNode) hooks.drawMergeFlag(proc)
    }

    /** ROM addresses and layout constants. */
    public companion object {
        /** `Sprite_08A97AEC`: the animation definition of the merge flag. */
        public const val SPRITE_FLAG: Int = 0x08A97AEC

        /** `NODE_MAX`. */
        private const val NODE_COUNT = 0x1D

        /** Bytes per 4bpp tile (`CHR_SIZE`). */
        private const val CHR_SIZE = 0x20

        /** `PROC_MARK_WMSTUFF`. */
        private const val PROC_MARK_WMSTUFF = 8
    }
}
