package fe8.game.opanim

import fe8.game.SoundWrapper

/** Sound callback used by the opening subtitle proc. */
public class OpSubtitleSound(private val sound: SoundWrapper) {
    /** Fades out the subtitle music. Ported from src/opsubtitle.c: sub_80C5400 */
    public fun fadeOutBgm() {
        sound.fadeOutBgm(4)
    }
}
