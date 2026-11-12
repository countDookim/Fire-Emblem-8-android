package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GMapScreenVSyncProc
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmScreenProc
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
 * `src/worldmap_screen.c` (the scrolling BG3 map image) against `ram_trace_prologue`: the
 * `ProcScr_GMapScreen` and `ProcScr_GMapScreenVSync` procs of the `procs` watch at every step
 * from 4900 to the step both are gone, the `r` dumps (VSync queue entries, `sub_80BAB00`'s
 * global, the decompressed palette map) and the `f` dumps (BG3 tile data and tilemap in VRAM,
 * palette banks 9-12, BG3 scroll).
 *
 * `NewMapScreen` runs at step 4900 and its script up to the repeat completes at once, as the
 * original's `WorldMap_Init` (4899-4901) is one multi-frame callback: step 4900 is mid-callback
 * (only the fields `MapScreen_Init` has set are compared), the comparison starts at step 4901.
 * Each later step runs root trees 0 to 7 once. The camera (`gGMData.xCamera/yCamera`) is an
 * input taken from the traced `x`/`y` of the same step; what is verified is the clamp and
 * everything derived from it (tile position, redraw queue, BG3 scroll, VRAM, tilemap).
 * Limits: the camera only moves once (0,0 to 32,8, step 4908), so the upper clamp, the `unk_2a`
 * redraw requests and the one-tile scroll branches of `sub_80BA8A0` are not exercised;
 * `gmroute` (a stand-in hook) is not compared.
 */
class WorldMapScreenTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "NewMapScreen", "NewMapScreenVSync", "MapScreen_Init", "MapScreen_OnDelete",
        "sub_80BA490", "sub_80BA458", "sub_80BA4D0", "GMScreenVSync_Loop",
        "sub_80BA6DC", "sub_80BA818", "sub_80BA8A0", "sub_80BAB00", "sub_80BAB0C",
    )
    fun `screen and VSync procs follow the original every step`() {
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
        var routeParent: gbafe.proc.Proc? = null
        val screen = WorldMapScreen(
            engine, hardware, rom, gm,
            WorldMapScreen.Hooks(startGMapRoute = { routeParent = it; null }),
        )
        // Poison the outputs the dumps are compared against.
        screen.queue.fill(0x5A)
        screen.unk3001E30 = 1

        val screenAddr = names.entries.first { it.value == "ProcScr_GMapScreen" }.key
        val vsyncAddr = names.entries.first { it.value == "ProcScr_GMapScreenVSync" }.key

        val proc = screen.newMapScreen(5)
        val state = proc.state as GmScreenProc
        val vsyncProc = checkNotNull(state.vsync)
        val vsync = vsyncProc.state as GMapScreenVSyncProc
        assertEquals(proc, routeParent, "StartGMapRoute is started with the screen as parent")
        var written = 0

        fun s8(v: Int) = v.toByte().toInt()
        fun s16(v: Int) = v.toShort().toInt()

        fun compareScreen(step: Int, full: Boolean) {
            val now = dump(step)
            val t = checkNotNull(now.find(screenAddr), { "screen proc at $step" })
            val ours = listOf(
                state.flags0, state.redrawRequests, state.drawnColumn, state.drawnRow,
                state.wantedColumn, state.wantedRow,
                state.unk2f, state.unk30, state.unk31, state.unk32, state.x, state.y,
                state.lastX, state.lastY,
            )
            val expected = listOf(
                t.u8(0x29) and 1 != 0, t.u8(0x2A), s8(t.u8(0x2B)), s8(t.u8(0x2C)),
                s8(t.u8(0x2D)), s8(t.u8(0x2E)), t.u8(0x2F), t.u8(0x30), t.u8(0x31), t.u8(0x32),
                s16(t.u16(0x34)), s16(t.u16(0x36)), t.u16(0x38), t.u16(0x3A),
            )
            assertEquals(expected, ours, "screen fields at $step")
            assertEquals(t.u32(0x14), proc.rootIndex, "screen root tree at $step")
            assertEquals(WorldMapScreen.IMAGE, t.u32(0x3C), "unk_3c address at $step")
            assertEquals(BG3_BUFFER, t.u32(0x40), "unk_40 address at $step")
            assertEquals(PALETTE_MAP, t.u32(0x44), "unk_44 address at $step")
            if (full) {
                assertEquals(t.scrCur, proc.scrCur, "screen script position at $step")
                assertEquals(checkNotNull(now.find(vsyncAddr)).address, t.u32(0x48), "unk_48")
            }
        }

        fun compareVsync(step: Int) {
            val t = checkNotNull(dump(step).find(vsyncAddr), { "VSync proc at $step" })
            assertEquals(t.scrCur, vsyncProc.scrCur, "VSync script position at $step")
            assertEquals(t.u32(0x14), vsyncProc.rootIndex, "VSync root tree at $step")
            assertEquals(WorldMapScreen.IMAGE, t.u32(0x2C), "unk_2c at $step")
            assertEquals(BG3_BUFFER, t.u32(0x30), "unk_30 at $step")
            assertEquals(PALETTE_MAP, t.u32(0x34), "unk_34 at $step")
            assertEquals(QUEUE, t.u32(0x3C), "unk_3c at $step")
            assertEquals(UNK_3001DE8, t.u32(0x44), "unk_44 at $step")
            assertEquals(s16(t.u16(0x38)), vsync.count, "queued rows at $step")
            assertEquals(t.u16(0x40), vsync.unk40, "unk_40 at $step")
            written = maxOf(written, vsync.count)
        }

        fun compareDumps(step: Int) {
            val dumpName = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val iwram = run.region(dumpName, "iwram")
            for (i in (0 until written * 8).filter { it % 8 < 6 }) {
                assertEquals(
                    iwram[QUEUE_OFFSET + i], screen.queue[i], "queue byte $i at $dumpName",
                )
            }
            assertEquals(iwram[0x1E30].toInt(), screen.unk3001E30, "gUnknown_03001E30 $dumpName")
            val ewram = run.region(dumpName, "ewram")
            val length = rom.decompressInto(WorldMapScreen.PALETTE_MAP, ByteArray(0x1000), 0)
            for (i in 0 until length) {
                assertEquals(
                    ewram[PALETTE_MAP - EWRAM + i], screen.paletteMap[i],
                    "palette map byte $i at $dumpName",
                )
            }
            if (step % 300 != 0) return
            hardware.flushBackgrounds()
            val vram = run.region(dumpName, "vram")
            val bad = (0x7800 until 0x10000).filter { vram[it] != hardware.vram[it] }
            assertEquals(
                0, bad.size, "BG3 tilemap/tile data: ${bad.size} bytes differ, first at " +
                    "0x${bad.firstOrNull()?.toString(16)} at $dumpName",
            )
            val pal = run.region(dumpName, "pal")
            for (i in 9 * 16 until 13 * 16) {
                val expected = u16(pal, 2 * i)
                assertEquals(
                    expected, hardware.paletteRam[i].toInt() and 0xFFFF, "pal $i at $dumpName",
                )
            }
            val io = run.region(dumpName, "io")
            assertEquals(
                u16(io, 0x1C) to u16(io, 0x1E),
                (hardware.lcd.bgHofs[3] and 0x1FF) to (hardware.lcd.bgVofs[3] and 0x1FF),
                "BG3 scroll at $dumpName",
            )
        }

        // Step 4900: `MapScreen_Init` is mid-callback in the original.
        compareScreen(4900, full = false)
        compareScreen(4901, full = true)
        compareVsync(4901)
        var step = 4901
        var ended = false
        while (step < 9330) {
            step++
            val t = dump(step).find(screenAddr)
            if (t == null) {
                engine.end(proc)
                assertEquals(null, dump(step).find(vsyncAddr), "VSync gone at $step")
                assertEquals(null, engine.find(screen.vsyncScript), "VSync ended with the screen")
                ended = true
                break
            }
            gm.xCamera = s16(t.u16(0x34))
            gm.yCamera = s16(t.u16(0x36))
            for (tree in 0..7) engine.run(engine.root(tree))
            compareScreen(step, full = true)
            compareVsync(step)
            if (step % 30 == 0) compareDumps(step)
        }
        assertEquals(true, ended, "the capture ends the screen")
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val EWRAM = 0x02000000
        const val BG3_BUFFER = 0x020244A8
        const val PALETTE_MAP = 0x020087A0
        const val QUEUE = 0x03001DA8
        const val QUEUE_OFFSET = 0x1DA8
        const val UNK_3001DE8 = 0x03001DE8
    }
}
