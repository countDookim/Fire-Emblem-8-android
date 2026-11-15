package fe8.game

/**
 * Positional sound effects of src/bmlib.c: [playSeSpacial] plays a song through [sound] and pans
 * it by the screen x it comes from (a walking MU's footsteps).
 */
public class SpacialSe(
    /** `PlaySoundEffect` and the m4a state ([SoundWrapper.sound], null without sound data). */
    private val sound: SoundWrapper,
) {
    /**
     * The pan of screen x [x]: -0x60 left of the screen, +0x5F right of it, else the position
     * scaled from the 240 pixel width to -0x60..+0x5F. Verified: the scaled pan of x 136 and 152
     * (the step sounds of the Prologue walks); not seen: the two clamps.
     *
     * Ported from src/bmlib.c: Screen2Pan
     */
    public fun screen2Pan(x: Int): Int {
        if (x < 0) return -0x60
        if (x >= DISPLAY_WIDTH) return 0x5F
        return 0xC0 * x / DISPLAY_WIDTH - 0x60
    }

    /**
     * Plays [song] (unless sound effects are off), restarts its player's tracks at once and pans
     * all of them to [screen2Pan] of [x]. Verified: the player, its song header and the pan of
     * its tracks; not seen: the track restart (`m4aMPlayImmInit`) changing anything.
     *
     * Ported from src/bmlib.c: PlaySeSpacial
     */
    public fun playSeSpacial(song: Int, x: Int) {
        sound.playSoundEffect(song)
        val m4a = sound.sound ?: return
        val player = m4a.players[m4a.songs.player(song and 0xFFFF)]
        m4a.sequencer.immInit(player)
        m4a.sequencer.panpotControl(player, 0xFFFF, screen2Pan(x))
    }

    private companion object {
        const val DISPLAY_WIDTH = 240
    }
}
