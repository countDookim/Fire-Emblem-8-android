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
 * `ProcScr_GmNodeIconDisplay`, the `GM_ICON` proc of `WorldMapMainProc`): every frame it puts a
 * sprite on each node whose state has `GM_NODE_STATE_VALID` (the places the party can visit),
 * and, while `merge_next_node` is set, an animated flag at node `nodeId`.
 * Like the decomp's `skip` byte, [skipGmNodeIconDisplay] / [unskipGmNodeIconDisplay] are named
 * the wrong way round for what they do: the loop draws only while `skip` is set
 * (`worldmap_screen2.c:180`), so "skip" turns the icons on and "unskip" off.
 *
 * Icon choice in the decomp (`GmapScreen2_Loop`, `worldmap_screen2.c:197`): a node with
 * `GM_NODE_STATE_CLEARED` uses `iconPreClear` of its `GMapNodeData`, any other `iconPostClear`.
 * The field names look inverted, but the code is as printed, and in the decomp's `gWMNodeData`
 * all 29 nodes have equal values in both fields, so the choice makes no difference there.
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
         * The body of the node loop for a node with `GM_NODE_STATE_VALID`: puts the node's icon
         * (`gWMNodeIconData[iconPreClear or iconPostClear]`, see the class note) on sprite layer
         * 12 through `PutSpriteExt` at the node position minus the icon centre and the screen
         * scroll, translucent when the node's bit in [GmNodeIconDisplayProc.unk34] is set, with
         * tile `sheetTileId + chr / CHR_SIZE` plus palette [GmNodeIconDisplayProc.pal] and
         * `OAM2_LAYER(2)`. Stand-in until M6-I: no node is valid in the prologue.
         */
        public val drawNodeIcon: (proc: GmNodeIconDisplayProc, node: Int) -> Unit = { _, _ -> },
        /**
         * The `merge_next_node` flag: `AP_Update` of the flag animation at the icon of node
         * `nodeId` offset by the icon's `xFlagOrigin` / `yFlagOrigin`, translucent like the icon.
         * Stand-in until M6-I: the flag is never set in the prologue.
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
     * Starts the proc in root tree [rootIndex] (the decomp passes `parent` to `Proc_Start`) and
     * creates the flag animation (definition `Sprite_08A97AEC`, animation 1, on sprite layer
     * 11). The flag's tiles lie 0x1000 bytes after the icon tiles: its tile base is
     * `(chr + 0x1000) / CHR_SIZE` with palette [palId] and `OAM2_LAYER(2)` (bits 10-11).
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
     * Clears the translucency mask `unk_34` (one bit per node, `u32 unk_34[1]` in the decomp)
     * and sets `skip`, so the icons start out drawn.
     */
    public fun gmapScreen2Init(proc: GmNodeIconDisplayProc) {
        proc.unk34 = 0
        proc.skip = true
    }

    /**
     * Ported from src/worldmap_screen2.c: GmapScreen2_Loop
     *
     * While `skip` is set, draws the icon of every node with `GM_NODE_STATE_VALID` and the
     * merge flag. Only the `skip` test and the node loop run in the prologue capture (no node
     * is valid, `merge_next_node` is never set); the drawing bodies are the [Hooks] and are
     * not verified.
     */
    public fun gmapScreen2Loop(proc: GmNodeIconDisplayProc) {
        if (!proc.skip) return
        for (i in 0 until NODE_COUNT) {
            if (gmData.nodeState(i) and GM_NODE_STATE_VALID == 0) continue
            hooks.drawNodeIcon(proc, i)
        }
        if (proc.mergeNextNode) hooks.drawMergeFlag(proc)
    }

    /** ROM addresses and layout constants. */
    public companion object {
        /** `Sprite_08A97AEC`: the animation definition of the merge flag. */
        public const val SPRITE_FLAG: Int = 0x08A97AEC

        /** `NODE_MAX`: the number of world map nodes. */
        private const val NODE_COUNT = 0x1D

        /** `GM_NODE_STATE_VALID`: bit 0 of a node's state, set for nodes the party can visit. */
        private const val GM_NODE_STATE_VALID = 1

        /** Bytes per 4bpp tile (`CHR_SIZE`). */
        private const val CHR_SIZE = 0x20

        /** `PROC_MARK_WMSTUFF`: the proc mark of world map procs. */
        private const val PROC_MARK_WMSTUFF = 8
    }
}
