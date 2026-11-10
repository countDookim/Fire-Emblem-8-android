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
    /** `BATTLEMAP_KIND_STORY`: main narrative campaign story chapter (0). */
    public const val STORY: Int = 0

    /** `BATTLEMAP_KIND_DUNGEON`: post-game / optional dungeon map (Tower of Valni, Ruins of Lagdou) (1). */
    public const val DUNGEON: Int = 1

    /** `BATTLEMAP_KIND_SKIRMISH`: world map random monster skirmish encounter (2). */
    public const val SKIRMISH: Int = 2

    /** `NODE_MAX`: number of world map nodes in `gWMNodeData` (29). */
    public const val NODE_MAX: Int = 0x1D

    /** `GMAP_NODE_PLACEMENT_DUNGEON`: node placement type indicating a dungeon location (3). */
    public const val PLACEMENT_DUNGEON: Int = 3

    /** Prologue: The Fall of Renais chapter ID (0x00). */
    private const val CHAPTER_L_PROLOGUE = 0x00

    /** Chapter 1: Escape! chapter ID (0x01). */
    private const val CHAPTER_L_1 = 0x01

    /** Chapter 5x: Unbroken Heart chapter ID (0x05). */
    private const val CHAPTER_L_5X = 0x05

    /** Chapter 12 Eirika route chapter ID (0x0C). */
    private const val CHAPTER_E_12 = 0x0C

    /** Chapter 21 Eirika route chapter ID (0x15). */
    private const val CHAPTER_E_21 = 0x15

    /** Final Chapter (part 2) Eirika route chapter ID (0x16). */
    private const val CHAPTER_E_21X = 0x16

    /** Chapter 12 Ephraim route chapter ID (0x19). */
    private const val CHAPTER_I_12 = 0x19

    /** Chapter 21 Ephraim route chapter ID (0x22). */
    private const val CHAPTER_I_21 = 0x22

    /** Final Chapter (part 2) Ephraim route chapter ID (0x23). */
    private const val CHAPTER_I_21X = 0x23

    /** Tower of Valni 1F chapter ID (0x24). */
    private const val CHAPTER_T_01 = 0x24

    /** Tower of Valni 2F chapter ID (0x25). */
    private const val CHAPTER_T_02 = 0x25

    /** Ruins of Lagdou B1 chapter ID (0x2E). */
    private const val CHAPTER_R_01 = 0x2E

    /** Ruins of Lagdou B2 chapter ID (0x2F). */
    private const val CHAPTER_R_02 = 0x2F

    /** Castle Frelia cutscene chapter ID (0x38). */
    private const val CHAPTER_CASTLE_FRELIA = 0x38

    /** Cutscene chapter 0x3B. */
    private const val CHAPTER_3B = 0x3B

    /** Cutscene chapter 0x3C. */
    private const val CHAPTER_3C = 0x3C

    /** Cutscene chapter range start (0x3F). */
    private const val CHAPTER_3F = 0x3F

    /** Cutscene chapter range end (0x47). */
    private const val CHAPTER_47 = 0x47

    /**
     * Transcribed from src/worldmap_path.c: GetBattleMapKind (not verified: no ground truth for it)
     *
     * Classifies the chapter [chapterIndex] (`gPlaySt.chapterIndex`) as [STORY], [DUNGEON] or
     * [SKIRMISH], looking up the world map node of tower/ruins/other non-story chapters in [nodes].
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

        // CHAPTER_IS_TOWER / CHAPTER_IS_RUINS: unsigned `(id) - CHAPTER_x_02 < 9`.
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
     * Checks whether [chapterId] lies in the 9-chapter floor range starting from [baseChapterId]
     * (used by the decomp macros `CHAPTER_IS_TOWER` and `CHAPTER_IS_RUINS`).
     */
    private fun isIn9(chapterId: Int, baseChapterId: Int): Boolean = (chapterId - baseChapterId).toUInt() < 9u
}
