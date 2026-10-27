package gbafe.core.time

/** The in-game frame clock (`gGameClock`, decomp `src/time.c`): frames since boot, shown as play time. */
public class GameClock {
    /** `gGameClock` (u32, frames). */
    public var frames: Long = 0

    /**
     * Ported from src/time.c: GetGameClock
     */
    public fun get(): Long = frames

    /**
     * Ported from src/time.c: SetGameTime
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
        frames = (frames + 1) and 0xFFFFFFFFL
        if (frames > 215999999L) frames = 213840000L
    }
}
