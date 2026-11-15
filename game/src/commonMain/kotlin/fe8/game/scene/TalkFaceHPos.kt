package fe8.game.scene

/** `GetTalkFaceHPos` and its table, shared by the talk faces ([TalkFace]) and the bubble. */
public object TalkFaceHPos {
    /** `gTalkFaceHPosLut`: the tile column of each of the 8 talk face slots. */
    private val LUT = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)

    /**
     * The tile column of talk face slot [talkFace]. While the battle animation system is active
     * ([battleActive], `IsBattleDeamonActive`) the faces use the fixed fight-event columns: 4 for
     * slots 0..2 and 26 for the others.
     *
     * Ported from src/scene.c: GetTalkFaceHPos
     *
     * Seen in the captures: only slots 1 and 4 (face x of the Prologue's talks); the fight-event
     * branch is not seen.
     */
    public fun of(talkFace: Int, battleActive: Boolean): Int {
        if (battleActive) return if (talkFace < 3) 4 else 26
        return LUT[talkFace]
    }
}
