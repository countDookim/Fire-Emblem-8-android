package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.Mu
import fe8.game.bm.MuCtrl
import fe8.game.bm.MuCtrlHooks
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.Reda
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * D-06: the two pairs walking outside the castle, against the original proc watch.
 *
 * Unit/class/map data and the four single REDAs are read from r12540 at runtime. The walks
 * start from their original tiles, with the original input stream from n12527 onward. The
 * first pair has no delay; the second has delay 40. Each completed proc-tree pass is compared
 * through n12600, including both controllers and all four MU positions. This isolates REDA
 * delays and movement from the event engine's earlier timing and the boot harness's A pulses.
 * The full-boot test also aligns at the first pair's movement, replays the original A input
 * and compares the four MU objects and their referenced OBJ tiles at f12600. It does not verify
 * earlier event scheduling or animation beyond that capture.
 */
class MuTimingDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `castle walking pairs match every completed original proc pass through n12600`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        // The capture can interrupt a costly controller callback across two VBlanks.
        val beforeLag = ProcArrayDump.ofWatch(trace, 12527)
        val insideLag = ProcArrayDump.ofWatch(trace, 12528)
        for (original in beforeLag.findAll(MU_SCRIPT)) {
            val next = insideLag.findAll(MU_SCRIPT).single { it.address == original.address }
            assertEquals(original.u16(0x4E), next.u16(0x4E), "n12528 has no MU movement")
        }
        val split = ProcArrayDump.ofWatch(trace, 12561).findAll(MU_SCRIPT)
        val completed = ProcArrayDump.ofWatch(trace, 12562).findAll(MU_SCRIPT)
        assertEquals(3, split.count { it.u8(0x3F) == Mu.MU_STATE_MOVEMENT })
        assertEquals(4, completed.count { it.u8(0x3F) == Mu.MU_STATE_MOVEMENT })
        for (original in split.filter { it.u8(0x3F) == Mu.MU_STATE_MOVEMENT }) {
            val next = completed.single { it.address == original.address }
            assertEquals(original.u16(0x4E), next.u16(0x4E), "n12562 finishes the same pass")
        }
        val ram = run.region("r12540", "ewram")
        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom).also { it.loadFrom(ram) }
        val bm = BmSt().also { it.decode(ram, BM_ST - GbaMem.EWRAM) }
        val play = PlaySt().also { it.decode(ram, PLAY_ST - GbaMem.EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(play.chapterIndex) }
        fun word(address: Int) = GbaMem.u32(ram, GbaMem.EWRAM, address)
        fun loadPool(pool: BmMap.Pool, address: Int) {
            val rows = word(address)
            for (y in 0 until map.sizeY) {
                val row = word(rows + y * 4)
                for (x in 0 until map.sizeX) {
                    pool[y, x] = ram[row + x - GbaMem.EWRAM].toInt() and 0xFF
                }
            }
        }
        loadPool(map.terrain, MAP_TERRAIN)
        loadPool(map.unit, MAP_UNIT)
        loadPool(map.other, MAP_OTHER)
        val engine = ProcEngine()
        val keys = KeyStatus()
        val rng = RngService()
        val mu = Mu(engine, bm, play, keys, CamMove(engine, bm).script, object : MuHooks {
            override fun classSlowWalking(jid: Int): Int = rom.u8(CLASS_DATA + (jid - 1) * 0x54 + 7)
            override fun classAttributes(jid: Int): Int =
                rom.u32(CLASS_DATA + (jid - 1) * 0x54 + 0x28)
        })
        val loader = UnitLoader(table, rom, { 0 }, rng, { false }).also { it.bmMap = map }
        val ctrl = MuCtrl(engine, mu, table, map, loader, rng, keys, object : MuCtrlHooks {})
        val seed = ProcArrayDump.ofWatch(trace, 12540)
        val originals = seed.findAll(MU_CTRL)
        assertEquals(4, originals.size, "four original controllers")
        // r12540 already reserves these destinations. Reconstruct the pre-load other map.
        for (original in originals) map.other[original.u8(0x41), original.u8(0x40)] = 0
        for (original in originals) {
            val unit = ram[original.u32(0x2C) - GbaMem.EWRAM + 0x0B].toInt() and 0xFF
            assertTrue(table.isValid(unit), "controller unit $unit")
            val reda = Reda.fromWords(original.u32(0x44), original.u32(0x48))
            ctrl.startDefinedMove(unit, listOf(reda), 1, original.u16(0x3E))
        }
        var compared = 0
        for (frame in 12527..12600) {
            keys.updateWith(run.inputs[frame - 1])
            // n12528 is within the previous pass. The delayed pair's second controller
            // straddles n12561/n12562: n12561 has only the first MU started, n12562 both.
            // Run that pass once and compare its completed state at n12562. These are fixed
            // capture boundaries, not skips inferred from whether the implementation matches.
            if (frame != 12528 && frame != 12562) engine.run(engine.root(5))
            if (frame == 12528 || frame == 12561) continue
            val expected = ProcArrayDump.ofWatch(trace, frame)
            val controllers = expected.findAll(MU_CTRL)
            assertEquals(4, controllers.size, "n$frame original controller count")
            assertEquals(4, engine.procs.count { it.script === ctrl.script },
                "n$frame host controller count")
            for (original in controllers) {
                val state = engine.procs.first {
                    it.script === ctrl.script &&
                        (it.state as MuCtrl.MuCtrlProc).muProc!!.unitAddress == original.u32(0x2C)
                }
                val got = state.state as MuCtrl.MuCtrlProc
                val label = "n$frame unit ${original.u32(0x2C).toString(16)}"
                assertEquals(original.scrCur, state.scrCur, "$label controller cursor")
                assertEquals(original.u16(0x3A), got.timer, "$label REDA index")
                assertEquals(original.u16(0x3C).toShort().toInt(), got.delayFrames, "$label delay")
                assertEquals(original.u8(0x42), got.unk42, "$label destination x")
                assertEquals(original.u8(0x43), got.unk43, "$label destination y")
                val wantMu = expected.findAll(MU_SCRIPT).single {
                    it.address == original.u32(0x30)
                }
                compareMu(label, wantMu, got.muProc!!)
            }
            compared++
        }
        assertEquals(72, compared, "completed original proc passes compared")
        val ys = mu.configs.map { it.mu!!.yQ4 }.sorted()
        val expectedYs = ProcArrayDump.ofWatch(trace, 12600).findAll(MU_SCRIPT)
            .map { it.u16(0x4E) }.sorted()
        assertEquals(4, expectedYs.size, "n12600 original MU count")
        assertEquals(expectedYs[2] - expectedYs[0], ys[2] - ys[0], "n12600 pair gap in Q4 pixels")
    }

    /** Align the live castle MUs, then replay the original inputs for completed proc passes. */
    @Test
    fun `full boot castle pair follows the original completed-pass inputs`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val first = ProcArrayDump.ofWatch(trace, 12527).findAll(MU_SCRIPT)
        val addresses = first.map { it.u32(0x2C) }.toSet()
        assertEquals(4, addresses.size, "original castle MU units")
        for (frame in 12527..12600) {
            assertEquals(0, run.inputs[frame - 1] and 1.inv(), "n$frame non-A input")
        }
        var aligned = false
        var referenceFrame = 12527
        var checked = 0
        val referenceOam = run.region("f12600", "oam")
        val referenceVram = run.region("f12600", "vram")
        // n12528 is still the preceding pass; n12561/n12562 are one split pass.
        // Its input is n12561, and its completed state is captured at n12562.
        val pb = PrologueBoot(romFile) { frame ->
            if (aligned && referenceFrame < 12600) {
                var next = referenceFrame + 1
                if (next == 12528) next++
                run.inputs[next - 1] and 1 != 0
            } else {
                frame % 20 in 5..8
            }
        }
        pb.onFrame = onFrame@{ frame ->
            if (checked >= 72) return@onFrame
            val mus = pb.boot.mu.configs.mapNotNull { it.mu }
                .filter { it.unitAddress in addresses }
            if (!aligned && mus.size == 4 && mus.count { it.state == Mu.MU_STATE_MOVEMENT } == 2) {
                aligned = true
            } else if (aligned && referenceFrame < 12600) {
                referenceFrame++
                if (referenceFrame == 12528 || referenceFrame == 12561) referenceFrame++
            }
            if (aligned && referenceFrame <= 12600) {
                val dump = ProcArrayDump.ofWatch(trace, referenceFrame)
                val expected = dump.findAll(MU_SCRIPT)
                assertEquals(4, expected.size, "n$referenceFrame original MUs")
                assertEquals(4, mus.size, "n$referenceFrame host MUs")
                val controllers = dump.findAll(MU_CTRL)
                assertEquals(4, controllers.size, "n$referenceFrame original controllers")
                val hostControllers = pb.boot.engine.procs.filter { it.state is MuCtrl.MuCtrlProc }
                assertEquals(4, hostControllers.size, "n$referenceFrame host controllers")
                for (want in controllers) {
                    val host = hostControllers.single {
                        (it.state as MuCtrl.MuCtrlProc).muProc?.unitAddress == want.u32(0x2C)
                    }
                    val state = host.state as MuCtrl.MuCtrlProc
                    val label = "n$referenceFrame controller ${want.u32(0x2C).toString(16)}"
                    assertEquals(want.scrCur, host.scrCur, "$label cursor")
                    assertEquals(want.u16(0x3A), state.timer, "$label REDA index")
                    assertEquals(want.u16(0x3C).toShort().toInt(), state.delayFrames, "$label delay")
                }
                for (want in expected) {
                    val got = mus.singleOrNull { it.unitAddress == want.u32(0x2C) }
                    assertTrue(got != null, "n$referenceFrame castle MU missing")
                    compareMu("n$referenceFrame host $frame", want, got!!)
                }
                if (referenceFrame == 12600) {
                    val oam = pb.boot.loop.oam.oam
                    val want = muObjects { GbaMem.u16(referenceOam, 0, it * 2) }
                    assertEquals(4, want.size, "f12600 original MU objects")
                    assertEquals(want, muObjects { oam[it].toInt() and 0xFFFF }, "f12600 MU OAM")
                    val hw = pb.boot.ui.env.hardware
                    assertEquals(0, hw.lcd.dispcnt and 0x40, "2D OBJ mapping")
                    val tiles = muObjectTiles(want)
                    assertEquals(64, tiles.size, "f12600 referenced MU tiles")
                    assertTrue(tiles.any { tile ->
                        referenceVram.sliceArray(0x10000 + tile * 32 until 0x10020 + tile * 32)
                            .any { it != 0.toByte() }
                    }, "MU tiles contain graphics")
                    for (tile in tiles) {
                        val start = 0x10000 + tile * 32
                        assertArrayEquals(
                            referenceVram.copyOfRange(start, start + 32),
                            hw.vram.copyOfRange(start, start + 32),
                            "f12600 OBJ tile 0x${tile.toString(16)}",
                        )
                    }
                }
                checked++
            }
        }
        assertTrue(pb.reachIdle())
        assertTrue(aligned, "castle pair movement was not reached")
        assertEquals(72, checked, "completed passes compared")
    }

    private fun muObjects(word: (Int) -> Int): List<List<Int>> = (0 until 128).mapNotNull {
        val a0 = word(it * 4)
        val a1 = word(it * 4 + 1)
        val a2 = word(it * 4 + 2)
        if (a0 and 0x300 == 0x200 || a2 and 0x3FF !in 0x380..0x3FF) null
        else listOf(a0, a1, a2)
    }.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))

    private fun compareMu(label: String, want: ProcArrayDump.Proc, got: MuProc) {
        assertEquals(want.u8(0x3F), got.state, "$label MU state")
        assertEquals(want.u8(0x40), got.hiddenB, "$label MU hidden")
        assertEquals(want.u8(0x42), got.facing, "$label facing")
        assertEquals(want.u16(0x48), got.moveClockQ4, "$label movement clock")
        assertEquals(want.u16(0x4A), got.moveConfig, "$label speed config")
        assertEquals(want.u16(0x4C), got.xQ4, "$label xQ4")
        assertEquals(want.u16(0x4E), got.yQ4, "$label yQ4")
    }

    private companion object {
        const val MU_SCRIPT = 0x089A2C48
        const val MU_CTRL = 0x089A2DB0
        const val CLASS_DATA = 0x08807164
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val MAP_UNIT = 0x0202E4D8
        const val MAP_TERRAIN = 0x0202E4DC
        const val MAP_OTHER = 0x0202E4F0
    }
}

/** Returns the 4bpp tile indices covered by the OAM objects in 2D mapping mode. */
private fun muObjectTiles(objects: List<List<Int>>): Set<Int> {
    val dimensions = listOf(
        listOf(1 to 1, 2 to 2, 4 to 4, 8 to 8),
        listOf(2 to 1, 4 to 1, 4 to 2, 8 to 4),
        listOf(1 to 2, 1 to 4, 2 to 4, 4 to 8),
    )
    return objects.flatMap { obj ->
        val shape = obj[0] ushr 14
        val size = obj[1] ushr 14
        require(shape < dimensions.size && obj[0] and 0x2000 == 0)
        val (width, height) = dimensions[shape][size]
        val first = obj[2] and 0x3FF
        (0 until height).flatMap { y -> (0 until width).map { x -> first + y * 32 + x } }
    }.toSet()
}
