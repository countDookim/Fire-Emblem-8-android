package fe8.testing

import fe8.game.Fe8Game
import fe8.game.RomView
import gba.core.event.TickListener
import gba.core.time.FixedStepClock
import gba.core.time.GameLoop
import gbafe.render.BgHardware
import gbafe.render.HardwareSceneLoader
import gbafe.render.OamBuffer
import gbafe.render.RenderState
import gbafe.render.TileSheets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Enforces the zero-allocations-per-frame rule for the game loop and its hot services. */
class AllocationTest {
    @Test
    fun `game tick and present allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        var sum = 0L
        game.events.onTickStart.subscribe(TickListener { sum += it })
        val bytes = AllocationMeter.measure(warmup = 20_000, iterations = 10_000) {
            game.tick()
            game.present(0f)
        }
        assertEquals(0L, bytes, "bytes allocated over 10k frames")
    }

    @Test
    fun `game loop driving by wall clock allocates nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        val loop = GameLoop(game, FixedStepClock())
        var now = 0L
        val bytes = AllocationMeter.measure(warmup = 20_000, iterations = 10_000) {
            now += 8_333_333 // 120 Hz display
            loop.frame(now)
        }
        assertEquals(0L, bytes)
    }

    @Test
    fun `booting game tick and present allocate nothing between screen loads`() {
        val rom = java.io.File(System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
        assumeTrue(AllocationMeter.supported && rom.isFile)
        val game = Fe8Game(rom = RomView.ofRom(rom.readBytes()))
        repeat(40) { game.tick() } // past the health & safety screen load
        val tick = AllocationMeter.measureMin(warmup = 10, iterations = 50) { game.tick() }
        val present = AllocationMeter.measureMin(warmup = 10, iterations = 50) { game.present(0f) }
        assertEquals(0L, tick, "tick")
        assertEquals(0L, present, "present")
    }

    @Test
    fun `hardware scene loader allocates nothing per frame`() {
        assumeTrue(AllocationMeter.supported)
        val hw = BgHardware()
        hw.lcd.dispcnt = 0x1F01 // mode 1 (BG2 affine), all BGs + OBJ on
        val oam = OamBuffer()
        for (i in 0 until 128) oam.oam[i * 4 + 1] = (i * 2).toShort()
        val loader = HardwareSceneLoader()
        val state = RenderState()
        val sheets = TileSheets()
        val bytes = AllocationMeter.measureMin(warmup = 200, iterations = 200) {
            loader.load(state, sheets, hw, oam)
        }
        assertEquals(0L, bytes)
    }

    @Test
    fun `rng draws allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        var acc = 0
        val bytes = AllocationMeter.measure(warmup = 50_000, iterations = 50_000) {
            acc += game.rng.next100()
            if (game.rng.roll2(50)) acc++
        }
        assertEquals(0L, bytes)
        assert(acc != Int.MIN_VALUE)
    }
}
