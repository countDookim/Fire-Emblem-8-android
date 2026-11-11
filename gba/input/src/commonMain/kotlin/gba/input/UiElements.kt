package gba.input

/**
 * UI element contract (mod groundwork, docs/decisions/0009): each frame, screens
 * that show selectable things (menu rows, map cells, buttons) publish their on-screen bounds
 * together with the [GameAction] that selecting them corresponds to. The base game only
 * *publishes*; it never reads this list, so game logic is unchanged. A touch-UI mod hit-tests a
 * tap with [hitTest] and feeds the returned action back through its [ActionSource].
 *
 * Coordinates are GBA screen pixels (240×160 in vanilla; the front end maps touch points into
 * this space). Fixed capacity, primitive arrays, no allocation per frame. Later entries are
 * drawn on top: [hitTest] searches from the last added.
 */
public class UiElements(
    /** Maximum elements per frame; [add] drops extras. */
    public val capacity: Int = 64,
) {
    /** Elements published this frame. */
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

    /** Starts a new frame: removes all elements. */
    public fun clear() {
        for (i in 0 until count) action[i] = null
        count = 0
    }

    /** Publishes one element; returns its index, or -1 if full. */
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
