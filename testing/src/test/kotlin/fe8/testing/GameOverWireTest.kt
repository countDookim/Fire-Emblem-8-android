package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.GameOverScreen
import gba.core.sram.SaveStorage
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-8 WIRE: the lord-death game over through the real game loop ([Fe8Boot], whole ROM), against
 * `ram_trace_prologue_gameover` (PC-cap2).
 *
 * The boot resumes the Prologue suspend save and replays the captured keys to the turn 2 idle
 * (the same replay [EnemyPhaseReplayTest] validates up to `f2895`; the idle edges are re-aligned
 * to the capture's). Eirika's death in battle is not replayed (the port's enemy phase is faster
 * than the capture's, so the replay does not reach `f5831`): `EVFLAG_GAMEOVER`, which the lord's
 * death sets, is set at the idle and the captured keys of the first unit action (select, wait)
 * make `RunWaitEvents` start the game over as `f5833` does. The capture is then aligned by the
 * frame `ProcScr_BmGameOver` appears (capture step 5833); the window `f5833..6297` (event engine,
 * music and screen fade, screen, fog scroll, end, title) is compared per frame, and the captured
 * A press of `f6260` ends the screen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class GameOverWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    private fun available() = ref.available && romFile.isFile &&
        ProcArrayDump.romNames().isNotEmpty() && ref.run(RUN).hasWatch("procs")

    /** A frame callback: the boot and the capture step it is aligned to. */
    private fun interface Visitor {
        fun frame(boot: Fe8Boot, step: Int, bytes: Long)
    }

    /**
     * Runs the replay and calls [visitor] after every frame from the game over start ([GAME_OVER_STEP])
     * for [AFTER_START] frames (to the title).
     */
    private fun replay(measure: Boolean = false, visitor: Visitor) {
        val run = ref.run(RUN)
        val trace = run.watch("procs")
        val names = ProcArrayDump.romNames()
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        // The capture's idle edges (the player phase waits for a key): 1172, 1451, 2854.
        val capEdges = ArrayList<Int>()
        var wasIdle = false
        for (st in idleFrame..2900) {
            val idle = ProcArrayDump.ofWatch(trace, st, names)
                .find("gProcScr_PlayerPhase")?.scrCur == 16
            if (idle && !wasIdle) capEdges.add(st)
            wasIdle = idle
        }
        check(capEdges.size >= 3) { "capture idle edges: $capEdges" }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        var frame = 0
        var started = -1
        var shift = Int.MIN_VALUE
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (shift == Int.MIN_VALUE) pulse[if (frame % 20 in 5..8) 1 else 0]
                else if (started < 0) inputs.getOrElse(frame + shift) { 0 }
                else inputs.getOrElse(GAME_OVER_STEP + frame - started) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    save.copyInto(dest)
                    return true
                }

                override fun store(src: ByteArray) {}
            },
        )
        var edge = 0
        var portIdle = false
        while (frame < START_LIMIT && started < 0) {
            boot.frame()
            frame++
            val idle = boot.engine.find(boot.playerPhaseScript)?.scrCur == 16
            if (idle && !portIdle) {
                if (edge < 3) shift = capEdges[edge] - frame
                if (edge == 2) boot.flags.setFlag(EVFLAG_GAMEOVER)
                edge++
            }
            portIdle = idle
            if (has(boot, "ProcScr_BmGameOver")) started = frame
        }
        check(started > 0) { "the game over did not start within $START_LIMIT frames" }
        // The port's frame count since the resume differs from the capture's (the enemy phase is
        // faster); the fade-in is paced on the game clock, so the clock takes the capture's value.
        boot.clock.set(run.watch("clock").u32(GAME_OVER_STEP, 0).toLong() and 0xFFFFFFFFL)
        visitor.frame(boot, GAME_OVER_STEP, 0)
        if (measure) boot.gpuFrame
        for (k in 1..AFTER_START) {
            val before = if (measure) AllocationMeter.current() else 0L
            boot.frame()
            if (measure) boot.pack()
            val used = if (measure) AllocationMeter.current() - before else 0L
            frame++
            visitor.frame(boot, GAME_OVER_STEP + k, used)
        }
    }

    /** Whether a channel of [a] and [b] (0xRRGGBB) differs by more than three 5-bit levels. */
    private fun far(a: Int, b: Int): Boolean {
        for (shift in intArrayOf(0, 8, 16)) {
            val d = ((a shr shift) and 255) - ((b shr shift) and 255)
            if (d > CHANNEL_TOLERANCE || d < -CHANNEL_TOLERANCE) return true
        }
        return false
    }

    private fun has(boot: Fe8Boot, name: String) =
        boot.engine.procs.any { it.script?.name == name }

    @Test
    fun `lord death game over and return to the title match the capture`() {
        assumeTrue(available(), "reference data / ROM / nm file")
        val run = ref.run(RUN)
        val trace = run.watch("procs")
        val names = ProcArrayDump.romNames()
        val io = run.watch("displayio")
        val problems = ArrayList<String>()
        fun note(s: String) {
            if (problems.count { it.substringAfter(' ').take(6) == s.substringAfter(' ').take(6) } < 6) {
                problems += s
            }
        }
        var rgbCompared = 0
        var songAtTitle = -1
        var returned = false
        var counters = 0
        var maxDiff = 0
        replay { boot, step, _ ->
            val dump = ProcArrayDump.ofWatch(trace, step, names)
            for (n in WATCHED) {
                val want = dump.find(n) != null
                // f5953 is a mid-pass dump: the scroll child does not exist yet (PC-8a alignment).
                if (step == 5953 && n.endsWith("RandomScroll")) continue
                if (want != has(boot, n)) note("f$step $n capture $want")
            }
            val hw = boot.ui.env.hardware
            val screen = boot.engine.procs.firstOrNull { it.script?.name == "ProcScr_GameOverScreen" }
            val cap = dump.find("ProcScr_GameOverScreen")
            if (screen != null && cap != null && step in 5960..6290) {
                val st = screen.state as GameOverScreen.State
                if (cap.u16(0x4C).toShort().toInt() != st.counter1) note("f$step counter1")
                counters++
            }
            if (boot.control.sound.songId == SONG_MAIN_THEME && songAtTitle < 0) songAtTitle = step
            if (has(boot, "gProcScr_TitleScreen")) returned = true
            if (step in 5960..6290) {
                // The registers follow the port's buffer by two dumps.
                val regs = step + 2
                if (io.u16(regs, IO_BG2HOFS) != hw.lcd.bgHofs[2] and 0x1FF) note("f$step BG2HOFS")
                if (io.u16(regs, IO_BG2HOFS + 2) != hw.lcd.bgVofs[2] and 0x1FF) {
                    note("f$step BG2VOFS")
                }
                if (io.u16(regs, IO_BG3HOFS) != hw.lcd.bgHofs[3] and 0x1FF) note("f$step BG3HOFS")
                if (io.u16(regs, IO_BG3HOFS + 2) != hw.lcd.bgVofs[3] and 0x1FF) {
                    note("f$step BG3VOFS")
                }
                // The window enable bits keep the map's state (a unit menu left WIN0 on).
                if (io.u16(regs, IO_DISPCNT) and 0x1FFF != hw.lcd.dispcnt and 0x1FFF) {
                    note("f$step DISPCNT")
                }
            }
            if (step in 5960..6290) {
                // Palette RAM (the synced copy) equals the capture's at the same step; banks 0 and
                // 1 are the screen's own (the other banks keep the battle map's, which differ: the
                // port's map is at turn 2, the capture's at turn 4).
                val pr = run.watch("pal").step(step)
                for (i in 1 until 32) {
                    val want = (pr[2 * i].toInt() and 255) or ((pr[2 * i + 1].toInt() and 255) shl 8)
                    if (want != hw.paletteRam[i].toInt() and 0xFFFF) {
                        note("f$step palette RAM[$i]")
                        break
                    }
                }
            }
            if (step in 5960..6290) {
                // The frame drawn after the port's step is the capture's image of the next dump.
                // The fog's per-line blend differs by a level or two (see `far`).
                var best = Int.MAX_VALUE
                boot.render()
                for (k in step + 1..step + 1) {
                    val key = "f%05d".format(k)
                    if (!run.dumps.containsKey(key)) continue
                    val rgb = run.frameRgb(key)
                    var diff = 0
                    for (p in 0 until 240 * 160) {
                        val got = boot.screen.pixels[(p / 240) * boot.screen.width + p % 240]
                        val want = ((rgb[p * 3].toInt() and 255) shl 16) or
                            ((rgb[p * 3 + 1].toInt() and 255) shl 8) or (rgb[p * 3 + 2].toInt() and 255)
                        if (far(got, want)) diff++
                    }
                    best = minOf(best, diff)
                }
                if (best != Int.MAX_VALUE) {
                    maxDiff = maxOf(maxDiff, best)
                    if (best > MAX_FRAME_DIFF) note("f$step frame differs in $best pixels")
                    rgbCompared++
                }
            }
        }
        println("PROBLEMS $problems counters=$counters rgb=$rgbCompared " +
            "title=$returned song=$songAtTitle maxDiff=$maxDiff")
        assertEquals(emptyList<String>(), problems)
        assertTrue(counters > 300 && rgbCompared > 80, "compared $counters counters, $rgbCompared frames")
        assertTrue(returned, "the title proc exists after the game over")
        assertEquals(GAME_OVER_STEP + 464, songAtTitle, "the title song starts at f6297")
    }

    @Test
    fun `game over idle and fog scroll allocate nothing per frame`() {
        assumeTrue(available() && AllocationMeter.supported, "reference data / ROM / meter")
        // Two passes over the same deterministic replay; a frame counts as allocating only if it
        // allocates in both (a JIT deoptimization in one pass is gone in the other).
        val first = idleBytes()
        val second = idleBytes()
        var best = 0
        var current = 0
        for (step in IDLE_FROM..IDLE_TO) {
            val bytes = minOf(first[step - GAME_OVER_STEP], second[step - GAME_OVER_STEP])
            current = if (bytes == 0L) current + 1 else 0
            best = maxOf(best, current)
        }
        assertTrue(best >= IDLE_RUN, "longest allocation-free run $best of ${IDLE_TO - IDLE_FROM + 1}")
    }

    /** Bytes allocated by `frame()` + `pack()` per frame after the game over start. */
    private fun idleBytes(): LongArray {
        val bytes = LongArray(AFTER_START + 1)
        replay(measure = true) { _, step, used -> bytes[step - GAME_OVER_STEP] = used }
        return bytes
    }

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
        const val GAME_OVER_STEP = 5833
        const val START_LIMIT = 4000
        const val AFTER_START = 480
        const val IDLE_FROM = 6130
        const val IDLE_TO = 6250
        const val IDLE_RUN = 100
        const val EVFLAG_GAMEOVER = 0x65
        const val SONG_MAIN_THEME = 0x43
        const val IO_DISPCNT = 0x00
        const val CHANNEL_TOLERANCE = 24
        const val MAX_FRAME_DIFF = 100
        const val IO_BG2HOFS = 0x18
        const val IO_BG3HOFS = 0x1C
        val WATCHED = listOf(
            "ProcScr_StdEventEngine", "ProcScr_BmGameOver", "ProcScr_FadeToBlack",
            "ProcScr_GameOverScreen", "ProcScr_GameOverScreen_RandomScroll", "gProc_BMapMain",
            "gProcScr_TitleScreen",
        )
    }
}
