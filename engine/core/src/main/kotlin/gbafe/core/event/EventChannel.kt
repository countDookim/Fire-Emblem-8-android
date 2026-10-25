package gbafe.core.event

/**
 * A typed, allocation-free event channel: the building block of the mod event bus.
 *
 * Each channel is parameterised by a listener interface whose method takes primitive arguments, e.g.
 * `fun interface StepListener { fun onStep(unitId: Int, x: Int, y: Int) }`. Dispatching is a loop over
 * an array with an inline lambda, so **no event objects and no iterators are allocated per dispatch**.
 *
 * Subscription is copy-on-write (allocates, but only at subscribe time), which also makes it safe for a
 * listener to subscribe/unsubscribe while a dispatch is in progress: the running dispatch keeps its
 * snapshot. Listeners run in ascending [priority] order; equal priorities keep registration order.
 *
 * See `docs/decisions/0004-event-bus.md`.
 *
 * @param L the listener interface type.
 * @property name stable identifier shown in the debug menu and in mod documentation.
 */
public class EventChannel<L : Any>(public val name: String) {
    @PublishedApi
    internal var listeners: Array<Any?> = EMPTY

    private var priorities: IntArray = IntArray(0)

    /** Number of current listeners. */
    public val size: Int get() = listeners.size

    /**
     * Adds [listener] with the given [priority] (lower runs first; vanilla game systems use 0).
     * Returns the listener for convenient chaining.
     */
    public fun subscribe(listener: L, priority: Int = 0): L {
        val old = listeners
        var at = old.size
        while (at > 0 && priorities[at - 1] > priority) at--
        val next = arrayOfNulls<Any>(old.size + 1)
        val nextP = IntArray(old.size + 1)
        System.arraycopy(old, 0, next, 0, at)
        System.arraycopy(priorities, 0, nextP, 0, at)
        next[at] = listener
        nextP[at] = priority
        System.arraycopy(old, at, next, at + 1, old.size - at)
        System.arraycopy(priorities, at, nextP, at + 1, old.size - at)
        priorities = nextP
        listeners = next
        return listener
    }

    /** Removes [listener] (identity comparison). Returns false if it was not subscribed. */
    public fun unsubscribe(listener: L): Boolean {
        val old = listeners
        val i = old.indexOfFirst { it === listener }
        if (i < 0) return false
        val next = arrayOfNulls<Any>(old.size - 1)
        val nextP = IntArray(old.size - 1)
        System.arraycopy(old, 0, next, 0, i)
        System.arraycopy(priorities, 0, nextP, 0, i)
        System.arraycopy(old, i + 1, next, i, old.size - i - 1)
        System.arraycopy(priorities, i + 1, nextP, i, old.size - i - 1)
        priorities = nextP
        listeners = if (next.isEmpty()) EMPTY else next
        return true
    }

    /**
     * Invokes [call] for every listener, in priority order. Inline: the lambda is not allocated.
     *
     * Example: `GameEvents.onStep.dispatch { it.onStep(unit, x, y) }`
     */
    public inline fun dispatch(call: (L) -> Unit) {
        val snapshot = listeners
        for (i in snapshot.indices) {
            @Suppress("UNCHECKED_CAST")
            call(snapshot[i] as L)
        }
    }

    private companion object {
        private val EMPTY = arrayOfNulls<Any>(0)
    }
}
