package fe8.testing

import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapAutoMuProc
import fe8.game.worldmap.WorldMapAutoMu
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `src/worldmap_automu.c` against the `procs` watch of `ram_trace_prologue` (steps 8174-9129, the
 * `ProcScr_GmapAutoMu` runs).
 *
 * The event opcodes that start the proc are not wired, so each run is started at the step where
 * the original first shows it, with the input arguments read from the captured proc fields (kind,
 * flags, unit, nodes/positions, speed, delay). The MU and unit-fade callees are driven by the
 * capture: `sub_80BE12C` ("still moving") is true while the original proc still waits in
 * `GmapAutoMu_80C3350`, and `GmUnitFadeExists` while a `ProcScr_GmapUnitFade` is live; so the
 * length of those two waits is the capture's and is not checked. Script position and the fields
 * are compared every step until each run ends.
 */
class WorldMapAutoMuTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun romFile() = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        ref.available && romFile().isFile && ProcArrayDump.romNames().isNotEmpty()

    @Test
    @VerifiesPort(
        "GmapAutoMu_WaitInitialDelay", "GmapAutoMu_80C31C4", "GmapAutoMu_80C31E0",
        "GmapAutoMu_80C31FC", "GmapAutoMu_StartFadeOut",
        "GmapAutoMu_WaitForFadeEnd", "GmapAutoMu_80C3350",
    )
    fun `auto mu runs follow the original script position and fields every step`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val run = ref.run("ram_trace_prologue")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val autoMu = names.entries.first { it.value == "ProcScr_GmapAutoMu" }.key

        val engine = ProcEngine()
        val gm = GmData(run.region("r04920", "iwram").copyOf(0x8000), RngService())
        var step = FIRST_STEP
        var moving: (Int) -> Boolean = { false }
        var fade: () -> Boolean = { false }
        val calls = mutableListOf<String>()
        fun checkFade(duration: Int) {
            // The original starts its `ProcScr_GmapUnitFade` inside the same frame.
            val fade = checkNotNull(dump(step).find("ProcScr_GmapUnitFade")) { "fade at $step" }
            assertEquals(fade.u16(0x2C), duration, "fade duration at $step")
        }
        val port = WorldMapAutoMu(
            engine, gm,
            WorldMapAutoMu.Hooks(
                muMoving = { moving(it) },
                unitFadeExists = { fade() },
                showUnit = { calls += "show$it" },
                muFadeIn = { u, d -> calls += "in$u/$d"; checkFade(d) },
                muFadeOut = { u, d -> calls += "out$u/$d"; checkFade(d) },
                muMoveNodes = { calls += "nodes${it.unk00}:${it.unk06}>${it.unk08}@${it.unk0c}" },
                muMovePositions = {
                    calls += "pos${it.unk00}:${it.unk06},${it.unk08}>${it.unk0a},${it.unk0c}" +
                        "@${it.unk10}"
                },
            ),
        )
        moving = { unit ->
            // `Proc_Break` continues the script next frame, so a run that leaves the wait at
            // step + 1 was released by this frame's call.
            dump(step + 1).findAll(autoMu)
                .any { it.u16(0x2C) == unit && it.scrCur == WAIT_MOVE }
        }
        fade = {
            dump(step - 1).find("ProcScr_GmapUnitFade") != null ||
                dump(step).find("ProcScr_GmapUnitFade") != null
        }

        val live = LinkedHashMap<Int, Proc>() // captured slot -> our proc
        var started = 0
        var compared = 0
        var kind0 = 0
        var kind1 = 0
        var fadedIn = 0
        var fadedOut = 0
        while (step <= LAST_STEP) {
            engine.run(engine.root(3))
            val now = dump(step)
            for (cap in now.findAll(autoMu)) {
                if (cap.slot in live) continue
                // A run that the original holds one frame longer in its first position is started
                // at the last step it shows there (the arguments are unchanged until then).
                val next = dump(step + 1).findAll(autoMu).firstOrNull { it.slot == cap.slot }
                if (cap.scrCur == 4 && next?.scrCur == 4) continue
                val flags = cap.u8(0x2A)
                val unit = cap.u16(0x2C)
                val delay = cap.u16(0x3C).toShort().toInt()
                val speed = cap.u16(0x3A).toShort().toInt()
                val proc = if (cap.u8(0x29) == 0) {
                    kind0++
                    port.startType0(
                        WorldMapAutoMu.Type0Input(
                            unit, cap.u8(0x38), cap.u8(0x30), cap.u8(0x34), delay, speed,
                        ),
                        flags, null,
                    )
                } else {
                    kind1++
                    port.startType1(
                        WorldMapAutoMu.Type1Input(
                            unit, cap.u8(0x39), cap.u8(0x38),
                            cap.u16(0x30).toShort().toInt(), cap.u16(0x32).toShort().toInt(),
                            cap.u16(0x34).toShort().toInt(), cap.u16(0x36).toShort().toInt(),
                            delay, speed,
                        ),
                        flags, null,
                    )
                }
                live[cap.slot] = checkNotNull(proc)
                started++
            }
            val captured = now.findAll(autoMu).filter { it.slot in live }.associateBy { it.slot }
            val iter = ArrayList<Proc>()
            var p = engine.find(port.script)
            while (p != null) {
                iter += p
                p = engine.findAfter(port.script, p)
            }
            assertEquals(captured.size, iter.size, "live auto mu count at $step")
            for ((slot, cap) in captured) {
                val ours = checkNotNull(live[slot])
                val s = ours.state as GmapAutoMuProc
                assertEquals(cap.scrCur, ours.scrCur, "script position of slot $slot at $step")
                assertEquals(cap.u8(0x29), s.kind, "kind at $step")
                assertEquals(cap.u8(0x2A), s.flags, "flags at $step")
                assertEquals(cap.u16(0x2C), s.unitId, "unit at $step")
                assertEquals(cap.u16(0x3C).toShort().toInt(), s.delay, "delay at $step")
                assertEquals(cap.u16(0x3A).toShort().toInt(), s.speed, "speed at $step")
                assertEquals(cap.u8(0x38), s.unk38, "unk_38 at $step")
                assertEquals(cap.u8(0x39), s.unk39, "unk_39 at $step")
                val target = intArrayOf(s.srcA, s.srcY, s.dstA, s.dstY)
                for (i in 0 until 4) {
                    assertEquals(
                        cap.u16(0x30 + 2 * i).toShort().toInt(), target[i], "target $i at $step",
                    )
                }
                compared++
            }
            live.keys.retainAll(captured.keys)
            step++
        }
        assertTrue(started >= 2 && compared > 100, "runs=$started steps=$compared")
        // Only position runs exist in the capture, so the node mover must never be called.
        assertTrue(calls.none { it.startsWith("nodes") }, "node mover called: $calls")
        assertTrue(calls.any { it.startsWith("pos") }, "moves $calls")
        fadedIn = calls.count { it.startsWith("in") }
        fadedOut = calls.count { it.startsWith("out") }
        println("automu runs=$started type0=$kind0 type1=$kind1 compared=$compared " +
            "fadeIn=$fadedIn fadeOut=$fadedOut calls=$calls")
    }

    private companion object {
        const val FIRST_STEP = 8174
        const val LAST_STEP = 9129

        /** `scrCur` while `PROC_REPEAT(GmapAutoMu_80C3350)` (command 17) waits. */
        const val WAIT_MOVE = 18
    }
}
