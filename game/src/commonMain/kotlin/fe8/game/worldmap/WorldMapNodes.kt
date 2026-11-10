package fe8.game.worldmap

import fe8.game.bm.BattleMapNodes
import fe8.game.bm.PlaySt

/**
 * [BattleMapNodes] backed by `gWMNodeData` ([nodeData]), `gGMData` ([gmData]) and the current
 * route in [playSt] (`chapterModeIndex`).
 */
public class WorldMapNodes(
    private val nodeData: WmNodeData,
    private val gmData: GmData,
    private val playSt: PlaySt,
) : BattleMapNodes {
    override fun chapterId(node: Int): Int = nodeData.chapterId(node, playSt.chapterModeIndex)

    override fun isCleared(node: Int): Boolean = gmData.isNodeCleared(node)

    override fun encounters(node: Int): Int = nodeData.encounters(node)

    override fun placementFlag(node: Int): Int = nodeData.placementFlag(node)
}
