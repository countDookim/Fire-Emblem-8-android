package fe8.game.opanim

import fe8.game.SoundWrapper

/** Sound callback of the opening monologue's natural end (the Start skip fades in the proc). */
public class OpSubtitleSound(private val sound: SoundWrapper) {
    /**
     * Fades out the monologue's music (`Sound_FadeOutBGM(4)`, speed 4; the Start skip uses speed
     * 1) at label 5.
     *
     * Ported from src/opsubtitle.c: sub_80C5400
     */
    public fun fadeOutBgm() {
        sound.fadeOutBgm(4)
    }
}
