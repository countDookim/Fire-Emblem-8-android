package fe8.game.worldmap

import fe8.game.bm.BattleMapNodes
import fe8.game.bm.PlaySt

/**
 * [BattleMapNodes] backed by `gWMNodeData` ([nodeData]), `gGMData` ([gmData]) and the current
 * route in [playSt] (`chapterModeIndex`): the world map node facts the battle map kind check
 * (`GetBattleMapKind`, `src/worldmap_path.c`) reads.
 */
public class WorldMapNodes(
    private val nodeData: WmNodeData,
    private val gmData: GmData,
    private val playSt: PlaySt,
) : BattleMapNodes {
    /** `WMLoc_GetChapterId(node)` for the Eirika or Ephraim route in [playSt]. */
    override fun chapterId(node: Int): Int = nodeData.chapterId(node, playSt.chapterModeIndex)

    /** Whether the player has cleared [node] (`GM_NODE_STATE_CLEARED`). */
    override fun isCleared(node: Int): Boolean = gmData.isNodeCleared(node)

    /** `gWMNodeData[node].encounters` (`GMAP_ENCOUNTERS_*`). */
    override fun encounters(node: Int): Int = nodeData.encounters(node)

    /** `gWMNodeData[node].placementFlag` (`GMAP_NODE_PLACEMENT_*`). */
    override fun placementFlag(node: Int): Int = nodeData.placementFlag(node)
}
