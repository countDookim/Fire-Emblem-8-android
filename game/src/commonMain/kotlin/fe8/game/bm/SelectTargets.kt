package fe8.game.bm

/**
 * The target list of src/uiselecttarget.c: `sSelectTargetRoot`, `sSelectTargetList` (the
 * `struct SelectTarget` array of [MAX_TARGET_COUNT]) and `sSelectTargetCount`. The
 * `Make*TargetList` functions of src/bmtarget.c fill it for the unit acting and the target
 * selection ([TargetSelection]) walks it, whatever command (attack, staff, trade, ...) made the
 * list. A target is the tile and unit it holds, an `extra` byte (the trap's `extra`, 0 for a
 * unit), and the `next` and `prev` links the cursor follows with the d-pad. Targets are
 * addressed by index (`GetTarget(index)`), [NONE] standing for a NULL link. The arrays are
 * allocated once; nothing here allocates afterwards.
 */
public class SelectTargets {
    private val xs = IntArray(MAX_TARGET_COUNT)
    private val ys = IntArray(MAX_TARGET_COUNT)
    private val uids = IntArray(MAX_TARGET_COUNT)
    private val extras = IntArray(MAX_TARGET_COUNT)
    private val nexts = IntArray(MAX_TARGET_COUNT)
    private val prevs = IntArray(MAX_TARGET_COUNT)

    /**
     * `sSelectTargetRoot.x`: the tile the list is made around (the acting unit's), which orders
     * the cursor's walk ([getLinkedTargetsNear]) and measures [getFurthestTargetDistance].
     */
    public var rootX: Int = 0
        private set

    /** `sSelectTargetRoot.y`. */
    public var rootY: Int = 0
        private set

    /** `sSelectTargetCount`. */
    private var count: Int = 0

    /**
     * Ported from src/uiselecttarget.c: InitTargets
     *
     * Empties the list and sets the root tile to ([xRoot], [yRoot]).
     */
    public fun initTargets(xRoot: Int, yRoot: Int) {
        rootX = xRoot
        rootY = yRoot
        count = 0
    }

    /**
     * Ported from src/uiselecttarget.c: AddTarget
     *
     * Appends a target at tile ([x], [y]) for the unit [unitId] (0 for a trap or wall) with the
     * byte [tId] as its `extra`. Each field is stored as the signed byte it is in C.
     */
    public fun addTarget(x: Int, y: Int, unitId: Int, tId: Int) {
        check(count < MAX_TARGET_COUNT) { "target list full" }
        xs[count] = x.toByte().toInt()
        ys[count] = y.toByte().toInt()
        uids[count] = unitId.toByte().toInt()
        extras[count] = tId.toByte().toInt()
        count++
    }

    /**
     * Sets the tile of the target at [index] (a write through `GetTarget(index)->x` and `->y`,
     * each stored as the signed byte it is in C).
     */
    public fun setTargetPosition(index: Int, x: Int, y: Int) {
        xs[index] = x.toByte().toInt()
        ys[index] = y.toByte().toInt()
    }

    /** Ported from src/uiselecttarget.c: GetSelectTargetCount (inline): the number of targets. */
    public fun getSelectTargetCount(): Int = count

    /**
     * Ported from src/uiselecttarget.c: GetTarget (inline)
     *
     * Returns the target at [index] in the list.
     */
    public fun getTarget(index: Int): Target {
        require(index in 0 until MAX_TARGET_COUNT) { "target index $index out of bounds" }
        return Target(
            x = xs[index],
            y = ys[index],
            uid = uids[index],
            extra = extras[index],
            next = nexts[index],
            prev = prevs[index],
        )
    }

    /**
     * One entry of the target list (`struct SelectTarget`).
     */
    public data class Target(
        /** `SelectTarget.x` (s8). */
        public val x: Int,
        /** `SelectTarget.y` (s8). */
        public val y: Int,
        /** `SelectTarget.uid` (s8): the unit's id, 0 for a trap or wall. */
        public val uid: Int,
        /** `SelectTarget.extra` (s8). */
        public val extra: Int,
        /** `SelectTarget.next` as an index, [NONE] for NULL. */
        public val next: Int = NONE,
        /** `SelectTarget.prev` as an index, [NONE] for NULL. */
        public val prev: Int = NONE,
    )

    /** `SelectTarget.x` (s8) of target [index]. */
    public fun targetX(index: Int): Int = xs[index]

    /** `SelectTarget.y` (s8) of target [index]. */
    public fun targetY(index: Int): Int = ys[index]

    /** `SelectTarget.uid` (s8) of target [index]: the unit's id, 0 for a trap or wall. */
    public fun targetUid(index: Int): Int = uids[index]

    /** `SelectTarget.extra` (s8) of target [index]. */
    public fun targetExtra(index: Int): Int = extras[index]

    /** `SelectTarget.next` of target [index] as an index, [NONE] for NULL. */
    public fun targetNext(index: Int): Int = nexts[index]

    /** `SelectTarget.prev` of target [index] as an index, [NONE] for NULL. */
    public fun targetPrev(index: Int): Int = prevs[index]

    /**
     * Ported from src/uiselecttarget.c: GetFurthestTargetDistance
     *
     * The greatest manhattan distance from the root tile to a target, 0 for an empty list.
     */
    public fun getFurthestTargetDistance(): Int {
        var result = 0
        for (i in 0 until count) {
            val distance = Math.abs(rootX - xs[i]) + Math.abs(rootY - ys[i])
            if (result < distance) result = distance
        }
        return result
    }

    /**
     * Ported from src/uiselecttarget.c: GetLinkedTargetsNear
     *
     * Links the targets in the order of the 13 tiles of the radius-2 diamond around the root that
     * [NEAR_LINK_ORDER] lists (the root, then the tiles from straight above it clockwise), so
     * the cursor of a close-range selection moves around the acting unit. Each target found
     * points `next` at the one found before it and that one's `prev` at it; the first and last
     * are tied together. Returns the index of the first target found, where the cursor starts.
     * The list must hold a target on one of those tiles (the original reads NULL otherwise,
     * and [getLinkedTargets] only calls this when every target is within 2 tiles).
     */
    public fun getLinkedTargetsNear(): Int {
        var first = NONE
        var last = NONE
        for (i in 0 until NEAR_LINK_ORDER.size / 2) {
            val x = rootX + NEAR_LINK_ORDER[2 * i]
            val y = rootY + NEAR_LINK_ORDER[2 * i + 1]
            for (j in 0 until count) {
                if (x == xs[j] && y == ys[j]) {
                    nexts[j] = last
                    if (last != NONE) prevs[last] = j
                    if (first == NONE) first = j
                    last = j
                }
            }
        }
        check(first != NONE) { "no target near the root" }
        nexts[first] = last
        prevs[last] = first
        return first
    }

    /**
     * Transcribed from src/uiselecttarget.c: LinkTargets (not verified: never run in the attack
     *   captures)
     *
     * Links the targets in list order into a ring: `next` of the last is the first, `prev` of
     * the first is the last. The list must not be empty.
     */
    public fun linkTargets() {
        for (i in 0 until count) {
            prevs[i] = i - 1
            nexts[i] = if (i + 1 < count) i + 1 else NONE
        }
        val last = count - 1
        prevs[0] = last
        nexts[last] = 0
    }

    /**
     * Transcribed from src/uiselecttarget.c: GetLinkedTargetsFar (not verified: never run in the
     *   attack captures)
     *
     * Links the targets in list order ([linkTargets]) and returns the first. Used for a list
     * with a target more than 2 tiles from the root (a bow or magic reach).
     */
    public fun getLinkedTargetsFar(): Int {
        linkTargets()
        return 0
    }

    /**
     * Ported from src/uiselecttarget.c: GetLinkedTargets
     *
     * Links the list for the cursor and returns the index of the target it starts on: around the
     * root when every target is within 2 tiles of it, else in list order.
     */
    public fun getLinkedTargets(): Int {
        if (getFurthestTargetDistance() > 2) return getLinkedTargetsFar()
        return getLinkedTargetsNear()
    }

    /** Constants of the target list. */
    public companion object {
        /** `MAX_TARGET_LIST_COUNT`: the capacity of the list. */
        public const val MAX_TARGET_COUNT: Int = 64

        /** A NULL link of `SelectTarget.next` or `prev`. */
        public const val NONE: Int = -1

        /** `gNearTargetLinkOrder`: the (x, y) offsets from the root, in pairs (13 tiles). */
        private val NEAR_LINK_ORDER = intArrayOf(
            0, 0, 0, -2, 0, -1, 1, -1, 1, 0, 2, 0, 1, 1, 0, 1, 0, 2, -1, 1, -1, 0, -2, 0, -1, -1,
        )
    }
}
