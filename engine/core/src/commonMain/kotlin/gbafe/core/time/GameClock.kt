package gbafe.core.time

/**
 * The in-game frame clock (`gGameClock`, decomp `src/time.c`): frames since boot, shown as play
 * time.
 */
public class GameClock {
    /** `gGameClock` (u32, frames). */
    public var frames: Long = 0

    /**
     * Returns the current frame count.
     *
     * Ported from src/time.c: GetGameClock
     */
    public fun get(): Long = frames

    /**
     * Sets the clock to [newTime] frames, truncated to 32 bits like the original `u32`.
     *
     * Transcribed from src/time.c: SetGameTime (not verified: no test checks it against a trace
     *   yet)
     */
    public fun set(newTime: Long) {
        frames = newTime and 0xFFFFFFFFL
    }

    /**
     * Adds one frame; past 999:59:59.59 (215999999) wraps back to 990:00:00 (213840000).
     *
     * Ported from src/time.c: IncrementGameClock
     */
    public fun increment() {
        // Mask to u32 as the original counter would wrap.
        frames = (frames + 1) and 0xFFFFFFFFL
        if (frames > 215999999L) frames = 213840000L
    }
}
