package fe8.game

import gbafe.core.event.CoreEvents
import gbafe.core.flags.FeatureFlags
import gbafe.core.rng.RngService
import gbafe.core.time.Simulation
import gbafe.input.InputFrame
import gbafe.render.FrameBuffer

/**
 * Root of the game: owns the shared services and runs the game systems once per tick.
 *
 * Milestone-1 state: services only (RNG seeded exactly like the original boot, input, events, flags,
 * an offscreen frame). Game systems (boot sequence, title, map, battle...) plug in from milestone 4 on.
 * Everything here is Android-free so it runs headless on the JVM for tests and benchmarks.
 */
public class Fe8Game(
    /** Feature flags; vanilla by default. */
    public val flags: FeatureFlags = FeatureFlags(),
) : Simulation {
    /** Central RNG. */
    public val rng: RngService = RngService().apply { seedLikeBoot() }

    /** Swappable gameplay strategies (battle rolls, ...); all vanilla unless a mod replaces one. */
    public val mechanics: gbafe.core.mechanics.Mechanics = gbafe.core.mechanics.Mechanics()

    /** Engine-level event channels. */
    public val events: CoreEvents = CoreEvents()

    /** Reused per-tick input snapshot. */
    public val input: InputFrame = InputFrame()

    /** Offscreen frame (software path); the GPU path uploads/replaces it. */
    public val frame: FrameBuffer = FrameBuffer()

    /** Logic frames run. */
    public var tickCount: Long = 0L
        private set

    /** Source of held actions; set by the platform layer. Defaults to "nothing held". */
    public var actionSource: gbafe.input.ActionSource = gbafe.input.ActionSource { 0L }

    /** Runs one fixed logic step: polls input, fires tick-start/end events, advances [tickCount]. */
    override fun tick() {
        input.update(actionSource.poll())
        val t = tickCount
        events.onTickStart.dispatch { it.onTick(t) }
        // Game systems run here (milestone 4+).
        events.onTickEnd.dispatch { it.onTick(t) }
        tickCount = t + 1
    }

    /** Draws the current state into [frame]; [alpha] is ignored until interpolation is needed. */
    override fun present(alpha: Float) {
        // Placeholder test pattern until the renderer exists: a slow vertical gradient.
        val px = frame.pixels
        val shade = (tickCount and 0xFF).toInt()
        for (y in 0 until frame.height) {
            val c = (0xFF shl 24) or ((y + shade) and 0xFF shl 8) or 0x40
            val row = y * frame.width
            for (x in 0 until frame.width) px[row + x] = c
        }
    }
}
