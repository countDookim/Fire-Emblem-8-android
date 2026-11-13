package fe8.game.bm

/** The world map node data [BattleMapKind.getBattleMapKind] consults (`gGMData`, `gWMNodeData`). */
public interface BattleMapNodes {
    /** `WMLoc_GetChapterId(node)`: chapter id of world map node [node] for the current route. */
    public fun chapterId(node: Int): Int

    /** True if `gGMData.nodes[node].state` has `GM_NODE_STATE_CLEARED`. */
    public fun isCleared(node: Int): Boolean

    /** `(u8)gWMNodeData[node].encounters`: encounter data of world map node [node]. */
    public fun encounters(node: Int): Int

    /** `gWMNodeData[node].placementFlag`: node placement classification flag. */
    public fun placementFlag(node: Int): Int
}

/** Battle map kind classification of the current chapter. */
public object BattleMapKind {
    /** `BATTLEMAP_KIND_STORY`: a story chapter with its own events (0). */
    public const val STORY: Int = 0

    /** `BATTLEMAP_KIND_DUNGEON`: a tower or ruins floor reached from its world map node (1). */
    public const val DUNGEON: Int = 1

    /** `BATTLEMAP_KIND_SKIRMISH`: a world map monster skirmish; also the fallback result (2). */
    public const val SKIRMISH: Int = 2

    /** `NODE_MAX`: number of world map nodes in `gWMNodeData` (29). */
    public const val NODE_MAX: Int = 0x1D

    /** `GMAP_NODE_PLACEMENT_DUNGEON`: `placementFlag` of a tower or ruins node (3). */
    public const val PLACEMENT_DUNGEON: Int = 3

    /** `CHAPTER_L_PROLOGUE` (0x00). */
    private const val CHAPTER_L_PROLOGUE = 0x00

    /** `CHAPTER_L_1` (0x01). */
    private const val CHAPTER_L_1 = 0x01

    /** `CHAPTER_L_5X` (0x05). */
    private const val CHAPTER_L_5X = 0x05

    /** `CHAPTER_E_12`: chapter 12 of the Eirika route (0x0C). */
    private const val CHAPTER_E_12 = 0x0C

    /** `CHAPTER_E_21`: chapter 21 of the Eirika route (0x15). */
    private const val CHAPTER_E_21 = 0x15

    /** `CHAPTER_E_21X`: chapter 21x of the Eirika route (0x16). */
    private const val CHAPTER_E_21X = 0x16

    /** `CHAPTER_I_12`: chapter 12 of the Ephraim route (0x19). */
    private const val CHAPTER_I_12 = 0x19

    /** `CHAPTER_I_21`: chapter 21 of the Ephraim route (0x22). */
    private const val CHAPTER_I_21 = 0x22

    /** `CHAPTER_I_21X`: chapter 21x of the Ephraim route (0x23). */
    private const val CHAPTER_I_21X = 0x23

    /** `CHAPTER_T_01`: first tower floor; the tower's world map node uses this id (0x24). */
    private const val CHAPTER_T_01 = 0x24

    /** `CHAPTER_T_02`: second tower floor, base of `CHAPTER_IS_TOWER` (0x25). */
    private const val CHAPTER_T_02 = 0x25

    /** `CHAPTER_R_01`: first ruins floor; the ruins' world map node uses this id (0x2E). */
    private const val CHAPTER_R_01 = 0x2E

    /** `CHAPTER_R_02`: second ruins floor, base of `CHAPTER_IS_RUINS` (0x2F). */
    private const val CHAPTER_R_02 = 0x2F

    /** `CHAPTER_CASTLE_FRELIA` (0x38). */
    private const val CHAPTER_CASTLE_FRELIA = 0x38

    /** `CHAPTER_3B` (0x3B), unnamed in the decomp. */
    private const val CHAPTER_3B = 0x3B

    /** `CHAPTER_3C` (0x3C), unnamed in the decomp. */
    private const val CHAPTER_3C = 0x3C

    /** `CHAPTER_3F` (0x3F), first of the always-story range 0x3F..0x47. */
    private const val CHAPTER_3F = 0x3F

    /** `CHAPTER_47` (0x47), last of the always-story range 0x3F..0x47. */
    private const val CHAPTER_47 = 0x47

    /**
     * Transcribed from src/worldmap_path.c: GetBattleMapKind (not verified: no ground truth for it)
     *
     * Classifies the chapter [chapterIndex] (`gPlaySt.chapterIndex`) as [STORY], [DUNGEON] or
     * [SKIRMISH]. The listed chapters are always [STORY]; for every other chapter (tower and
     * ruins floors are mapped to their first floor) the world map node with that chapter id
     * decides: an uncleared node with `encounters == 3`, or a cleared dungeon-placement node, is
     * [DUNGEON]; any other cleared node is [STORY]; no usable node gives [SKIRMISH].
     *
     * NOT verified: there is no ground truth for it yet (needs an mGBA dump with world map state),
     * so it carries no `@VerifiesPort` test and does not count as ported.
     */
    public fun getBattleMapKind(chapterIndex: Int, nodes: BattleMapNodes): Int {
        var chapterId = chapterIndex
        val isStoryChapter = when (chapterId) {
            CHAPTER_L_PROLOGUE, CHAPTER_L_1, CHAPTER_L_5X, CHAPTER_E_12, CHAPTER_E_21,
            CHAPTER_E_21X, CHAPTER_I_12, CHAPTER_I_21, CHAPTER_I_21X, CHAPTER_CASTLE_FRELIA,
            CHAPTER_3B, CHAPTER_3C,
            in CHAPTER_3F..CHAPTER_47,
            -> true
            else -> false
        }
        if (isStoryChapter) return STORY

        // CHAPTER_IS_TOWER / CHAPTER_IS_RUINS: unsigned `(id) - CHAPTER_x_02 < 9`; every floor
        // shares the node of the first floor.
        if (isIn9(chapterId, CHAPTER_T_02)) {
            chapterId = CHAPTER_T_01
        } else if (isIn9(chapterId, CHAPTER_R_02)) {
            chapterId = CHAPTER_R_01
        }
        for (nodeIndex in 0 until NODE_MAX) {
            if (chapterId == nodes.chapterId(nodeIndex)) {
                if (!nodes.isCleared(nodeIndex)) {
                    if (nodes.encounters(nodeIndex) != 3) break
                } else if (nodes.placementFlag(nodeIndex) != PLACEMENT_DUNGEON) {
                    return STORY
                }
                return DUNGEON
            }
        }
        return SKIRMISH
    }

    /**
     * True if [chapterId] is in the 9 ids from [baseChapterId] (tower ids 0x25..0x2D, ruins ids
     * 0x2F..0x37: the decomp macros `CHAPTER_IS_TOWER` and `CHAPTER_IS_RUINS`).
     */
    private fun isIn9(chapterId: Int, baseChapterId: Int): Boolean =
        (chapterId - baseChapterId).toUInt() < 9u
}
