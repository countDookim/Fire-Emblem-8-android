package gba.input

/**
 * UI element contract (mod groundwork, `docs/decisions/0009-vanilla-product-mod-groundwork.md`):
 * each frame, screens that show selectable things (menu rows, map cells, buttons) are meant to
 * publish their on-screen bounds together with the [GameAction] that selecting them corresponds
 * to. NOTE: no screen in the repo publishes yet (the only users of this class are its own
 * tests), so the list is always empty at runtime. The base game is meant only to *publish*; it
 * never reads this list, so game logic is unchanged. A touch-UI mod hit-tests a
 * tap with [hitTest] and feeds the returned action back through its [ActionSource].
 *
 * Coordinates are GBA screen pixels (240×160 in vanilla, `DISPLAY_WIDTH`/`DISPLAY_HEIGHT` in
 * `include/gba/defines.h:50-51`; the front end maps touch points into this space). Fixed capacity,
 * primitive arrays, no allocation per frame. Later entries are drawn on top: [hitTest] searches
 * from the last added.
 */
public class UiElements(
    /** Maximum elements per frame; [add] drops extras. */
    public val capacity: Int = 64,
) {
    /** Number of elements published this frame (indices `0 until count` are valid). */
    public var count: Int = 0
        private set

    /** Left edge (px). */
    public val x: IntArray = IntArray(capacity)

    /** Top edge (px). */
    public val y: IntArray = IntArray(capacity)

    /** Width (px). */
    public val width: IntArray = IntArray(capacity)

    /** Height (px). */
    public val height: IntArray = IntArray(capacity)

    /**
     * Action that selecting the element means (e.g. `SelectItem.of(row)`, or `Confirm` for a
     * button).
     */
    public val action: Array<GameAction?> = arrayOfNulls(capacity)

    /** Caller-defined id (e.g. menu item id or map cell `y * width + x`); not interpreted here. */
    public val id: IntArray = IntArray(capacity)

    /** Starts a new frame: removes all elements (call before screens publish). */
    public fun clear() {
        for (i in 0 until count) action[i] = null
        count = 0
    }

    /**
     * Publishes one element with top-left ([x], [y]) and size [width] x [height], selected via
     * [action] and tagged [id]. Returns its index, or -1 if full.
     */
    public fun add(x: Int, y: Int, width: Int, height: Int, action: GameAction, id: Int = 0): Int {
        if (count == capacity) return -1
        val index = count++
        this.x[index] = x; this.y[index] = y; this.width[index] = width
        this.height[index] = height
        this.action[index] = action; this.id[index] = id
        return index
    }

    /** Index of the top-most element containing point ([px], [py]), or -1. */
    public fun hitTest(px: Int, py: Int): Int {
        for (i in count - 1 downTo 0) {
            if (px >= x[i] && py >= y[i] && px < x[i] + width[i] && py < y[i] + height[i]) return i
        }
        return -1
    }
}
