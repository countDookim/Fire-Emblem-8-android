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
 * `ProcScr_GmNodeIconDisplay`).
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
    /** Branches of [gmapScreen2Loop] that no capture runs; each defaults to doing nothing. */
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
     * `ProcScr_GmNodeIconDisplay`.
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
     * `Sprite_08A97AEC`, animation 1).
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
     */
    public fun skipGmNodeIconDisplay(proc: GmNodeIconDisplayProc) {
        proc.skip = true
    }

    /**
     * Ported from src/worldmap_screen2.c: UnskipGmNodeIconDisplay
     */
    public fun unskipGmNodeIconDisplay(proc: GmNodeIconDisplayProc) {
        proc.skip = false
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Destruct
     */
    public fun gmapScreen2Destruct(proc: GmNodeIconDisplayProc) {
        ap.delete(proc.ap)
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Init
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

        private const val NODE_COUNT = 0x1D
        private const val CHR_SIZE = 0x20
        private const val PROC_MARK_WMSTUFF = 8
    }
}
