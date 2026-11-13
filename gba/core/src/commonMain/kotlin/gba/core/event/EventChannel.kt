package gba.core.event

/**
 * A typed, allocation-free event channel: the building block of the mod event bus.
 *
 * Each channel is parameterised by a listener interface whose method takes primitive arguments,
 * e.g. `fun interface StepListener { fun onStep(unitId: Int, x: Int, y: Int) }`. Dispatching is a
 * loop over an array with an inline lambda, so **no event objects and no iterators are allocated
 * per dispatch**.
 *
 * Subscription is copy-on-write (allocates, but only at subscribe time), which also makes it safe
 * for a listener to subscribe/unsubscribe while a dispatch is in progress: the running dispatch
 * keeps its snapshot. Listeners run in ascending [priority] order; equal priorities keep
 * registration order.
 *
 * See `docs/decisions/0004-event-bus.md`.
 *
 * @param L the listener interface type.
 * @property name stable identifier shown in the debug menu and in mod documentation.
 */
public class EventChannel<L : Any>(public val name: String) {
    /** Current listeners, ordered by priority; replaced (never mutated) on every change. */
    @PublishedApi
    internal var listeners: Array<Any?> = EMPTY

    /** Priority of each entry in [listeners], same indices. */
    private var priorities: IntArray = IntArray(0)

    /** Number of current listeners (also the length of [listeners]). */
    public val size: Int get() = listeners.size

    /**
     * Adds [listener] with the given [priority] (lower runs first; the default 0 is the
     * convention for vanilla game systems, an assumption not enforced here).
     * Returns the listener for convenient chaining.
     */
    public fun subscribe(listener: L, priority: Int = 0): L {
        val oldListeners = listeners
        // Insert after every listener of equal or lower priority so registration order is kept.
        var insertAt = oldListeners.size
        while (insertAt > 0 && priorities[insertAt - 1] > priority) insertAt--
        val newListeners = arrayOfNulls<Any>(oldListeners.size + 1)
        val newPriorities = IntArray(oldListeners.size + 1)
        oldListeners.copyInto(newListeners, 0, 0, insertAt)
        priorities.copyInto(newPriorities, 0, 0, insertAt)
        newListeners[insertAt] = listener
        newPriorities[insertAt] = priority
        oldListeners.copyInto(newListeners, insertAt + 1, insertAt, oldListeners.size)
        priorities.copyInto(newPriorities, insertAt + 1, insertAt, oldListeners.size)
        priorities = newPriorities
        listeners = newListeners
        return listener
    }

    /** Removes [listener] (identity comparison). Returns false if it was not subscribed. */
    public fun unsubscribe(listener: L): Boolean {
        val oldListeners = listeners
        val removeAt = oldListeners.indexOfFirst { it === listener }
        if (removeAt < 0) return false
        val newListeners = arrayOfNulls<Any>(oldListeners.size - 1)
        val newPriorities = IntArray(oldListeners.size - 1)
        oldListeners.copyInto(newListeners, 0, 0, removeAt)
        priorities.copyInto(newPriorities, 0, 0, removeAt)
        oldListeners.copyInto(newListeners, removeAt, removeAt + 1, oldListeners.size)
        priorities.copyInto(newPriorities, removeAt, removeAt + 1, oldListeners.size)
        priorities = newPriorities
        listeners = if (newListeners.isEmpty()) EMPTY else newListeners
        return true
    }

    /**
     * Invokes [call] for every listener, in priority order. Inline: the lambda is not allocated.
     *
     * Example: `GameEvents.onStep.dispatch { it.onStep(unit, x, y) }`
     */
    public inline fun dispatch(call: (L) -> Unit) {
        // Iterate a snapshot: subscribe/unsubscribe swap the array instead of mutating it.
        val snapshot = listeners
        for (i in snapshot.indices) {
            @Suppress("UNCHECKED_CAST")
            call(snapshot[i] as L)
        }
    }

    private companion object {
        /** Shared empty listener array for channels with no listeners (never mutated). */
        private val EMPTY = arrayOfNulls<Any>(0)
    }
}
