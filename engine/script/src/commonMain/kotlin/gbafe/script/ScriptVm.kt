package gbafe.script

/**
 * Contract for something that runs compiled event scripts ([ScriptImage]) a step at a time.
 *
 * Nothing implements it yet (grep of the repo, 2026-11-13). FE8's real interpreter is the event
 * engine port in `fe8.game.event`, which steps the decomp's `EventEngineProc` (src/event.c) on the
 * proc scheduler and does not use this interface (ADR 0001, M5 amendment). The "threads" in the
 * member docs are the original wording of the contract, not a model the decomp has: it has one
 * standard and one battle event engine.
 *
 * A step is meant not to allocate (ADR 0001).
 */
public interface ScriptVm {
    /** Runs every active script until it yields (a wait for a timer, an animation or dialog). */
    public fun step()

    /** True when no script is running or suspended. */
    public val idle: Boolean
}
