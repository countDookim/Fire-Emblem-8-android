package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmRouteProc
import fe8.game.worldmap.GmScreenProc
import fe8.game.worldmap.WorldMapRoute
import fe8.game.worldmap.WorldMapScreen
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `src/worldmap_path.c` `ProcScr_GMapRoute` against `ram_trace_prologue`: the proc's fields in the
 * `procs` watch at every step from 4901 until it is gone, the route tile buffer
 * (`gUnknown_02019D00`, ewram of every `r`/`f` dump in the run) and the BG2 tilemap in VRAM at the
 * `f` dumps.
 *
 * The route proc is started by the real [WorldMapScreen] (default hook), whose camera is the
 * traced `ProcScr_GMapScreen` `x`/`y` of the same step (as in WorldMapScreenTest); `gGMData`
 * (open paths) is seeded from the `r04890` dump (BG1 priority 1 is the `SetupBackgrounds`
 * default). `GmapRm` (not under test) writes the route flags; those writes are replayed.
 * Limits: `gGMData.openPaths` is empty in the whole capture, so the route buffer and the BG2
 * tilemap are all zero (compared, but they cannot tell a wrong copy from none): sub_80BBBF4,
 * sub_80BBC54, sub_80BBCC8 and sub_80BBDA4 are transcribed only. BG2's scroll is not compared
 * (the `f` dumps all lie after `GmapRm` resets it); the transition branch
 * (`MapRoute_BeginRouteTransition` and its procs) never starts in the window and is transcribed
 * only; the camera moves once (step 4908).
 */
class WorldMapRouteTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "StartGMapRoute", "sub_80BBEB8", "sub_80BBF60",
    )
    fun `route proc follows the original fields and BG2 tilemap every step`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available && romFile.isFile && names.isNotEmpty(), "data not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)

        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        hardware.setupBackgrounds()
        val gm = GmData(run.region("r04890", "iwram").copyOf(0x8000), RngService())
        val engine = ProcEngine()
        val screen = WorldMapScreen(engine, hardware, rom, gm)
        val screenAddr = names.entries.first { it.value == "ProcScr_GMapScreen" }.key
        val routeAddr = names.entries.first { it.value == "ProcScr_GMapRoute" }.key

        val proc = screen.newMapScreen(5)
        val route = checkNotNull((proc.state as GmScreenProc).gmRoute)
        val s = route.state as GmRouteProc
        fun s8(v: Int) = v.toByte().toInt()
        fun s16(v: Int) = v.toShort().toInt()

        fun compare(step: Int, full: Boolean) {
            val now = dump(step)
            val t = checkNotNull(now.find(routeAddr), { "route proc at $step" })
            assertEquals(
                listOf(t.u32(0x2C), t.u8(0x30)), listOf(s.chr, s.pal), "chr/pal at $step",
            )
            assertEquals(WorldMapRoute.OPEN_PATHS_ADDRESS, t.u32(0x40), "pOpenPaths at $step")
            assertEquals(WorldMapRoute.OPEN_PATHS_ADDRESS, s.pOpenPaths, "pOpenPaths at $step")
            assertEquals(t.u32(0x14), now.find(screenAddr)!!.address, "parent at $step")
            assertEquals(t.u32(0x14) != 0, route.parent != null, "parent at $step")
            if (!full) return
            assertEquals(t.scrCur, route.scrCur, "route script position at $step")
            val expected = listOf(
                t.u8(0x31), t.u8(0x32), t.u8(0x33), t.u8(0x34), t.u8(0x35),
                s8(t.u8(0x36)), s8(t.u8(0x37)), s8(t.u8(0x38)), s8(t.u8(0x39)), t.u8(0x3A),
            )
            val ours = listOf(
                s.flags, s.unk32, s.unk33, s.bgA, s.bgB, s.x1, s.y1, s.x2, s.y2, s.bgPriority,
            )
            assertEquals(expected, ours, "route fields at $step")
            // The transition fields keep the slot's stale value (14, 0): never written here.
            assertEquals(
                listOf(14, 0), listOf(s16(t.u16(0x3C)), s16(t.u16(0x3E))), "stale 3C/3E at $step",
            )
            assertEquals(listOf(0, 0), listOf(s.unk3c, s.unk3e), "transition idle at $step")
        }

        fun compareDumps(step: Int) {
            val dumpName = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val ewram = run.region(dumpName, "ewram")
            for (i in 0 until 60 * 40) {
                assertEquals(
                    u16(ewram, TILE_BUFFER - EWRAM + 2 * i),
                    screen.route.buffer[i].toInt() and 0xFFFF,
                    "route buffer entry $i at $dumpName",
                )
            }
            if (step % 300 != 0) return
            hardware.flushBackgrounds()
            val vram = run.region(dumpName, "vram")
            val bad = (0x7000 until 0x7800).filter { vram[it] != hardware.vram[it] }
            assertEquals(
                0, bad.size, "BG2 tilemap: ${bad.size} bytes differ, first at " +
                    "0x${bad.firstOrNull()?.toString(16)} at $dumpName",
            )
        }

        compare(4901, full = false)
        var step = 4901
        var ended = false
        while (step < 9330) {
            step++
            val t = dump(step).find(screenAddr)
            if (t == null) {
                engine.end(proc)
                assertEquals(null, dump(step).find(routeAddr), "route gone at $step")
                ended = true
                break
            }
            gm.xCamera = s16(t.u16(0x34))
            gm.yCamera = s16(t.u16(0x36))
            // `ProcScr_GmapRM` (tree 3, not under test) writes the route flags: in
            // `GmapRm_StartUpdateDirect` it clears bit 2, in `GmapRm_OnEnd` it sets 4 and 3.
            if (step == RM_START_STEP) s.flags = s.flags and 4.inv()
            // From the end of the map the route is not run for two steps (it stays at 7).
            if (step == RM_END_STEP) {
                s.flags = s.flags or 7
                route.lockCnt++
            }
            if (step == RM_END_STEP + 2) route.lockCnt--
            for (tree in 0..7) engine.run(engine.root(tree))
            compare(step, full = true)
            if (step % 30 == 0) compareDumps(step)
        }
        assertEquals(true, ended, "the capture ends the route")
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val EWRAM = 0x02000000
        const val TILE_BUFFER = 0x02019D00
        const val RM_START_STEP = 4944
        const val RM_END_STEP = 9286
    }
}
