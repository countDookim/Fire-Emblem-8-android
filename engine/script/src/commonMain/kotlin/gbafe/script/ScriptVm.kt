package gbafe.script

/**
 * Virtual machine interpreter interface for executing compiled event script bytecode ([ScriptImage]).
 *
 * Scripts are parsed from `.fe8s` text files and assembled at load time. Execution steps are non-allocating.
 */
public interface ScriptVm {
    /** Advances active script execution threads until each yields (e.g. waiting on timers, animations, dialog). */
    public fun step()

    /** True when no script execution threads are currently running or suspended. */
    public val idle: Boolean
}
