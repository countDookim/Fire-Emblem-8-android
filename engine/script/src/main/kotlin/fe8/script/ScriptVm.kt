package fe8.script

/**
 * Interpreter for the human-readable event script format (`.fe8s`) that the original event scripts are
 * converted into. Scripts are parsed and compiled at load time into an `IntArray` of opcodes and
 * operands; executing them never allocates. See `docs/decisions/0001-script-format.md`.
 *
 * This interface is the milestone-1 contract; the implementation lands in milestone 5.
 */
public interface ScriptVm {
    /** Runs the active script threads until each yields (waits on text, movement, a timer...). */
    public fun step()

    /** True when no script thread is active. */
    public val idle: Boolean
}
