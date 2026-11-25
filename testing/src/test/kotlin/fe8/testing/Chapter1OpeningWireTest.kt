package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-6 WIRE: the Chapter 1 opening through the real game loop ([Fe8Boot], whole ROM), from the
 * Prologue's ending event and the save menu (as `PrologueEndingWireTest`) through the Chapter 1
 * intro and the beginning event (`f10298..25423`) to the first player phase, replaying the
 * inputs of `ram_trace_prologue_end` (`PC-cap1`).
 *
 * The boot has no VBlank lag, so its frame count drifts from the capture's; the inputs are
 * indexed by the boot frame plus the offset at which the beginning event engine starts, and the
 * states are compared at checkpoints that do not depend on the frame count: the idle player
 * phase at the end (`r25510`: units of all three armies, `gBmMapUnit` / `gBmMapOther`, flags,
 * `gPlaySt`) and the event's own stall points ([CHECKPOINTS]: the dump whose engine cursor the
 * boot reaches with no MU running).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1OpeningWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private var boot: Fe8Boot? = null
    private var procsWatch: WatchTrace? = null
    private var flagsWatch: WatchTrace? = null
    private var playStWatch: WatchTrace? = null

    @AfterAll
    fun release() {
        boot = null
        procsWatch = null
        flagsWatch = null
        playStWatch = null
    }

    private class Frames {
        var frame = 0
        var keys = 0
        var manual = false
    }

    private fun bootAtIdle(f: Frames): Fe8Boot {
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        val pulse = arrayOf<Any>(0, 1)
        val b = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (f.manual) f.keys else pulse[if (f.frame % 20 in 5..8) 1 else 0]
            } as () -> Int),
            RngService(),
            saveStorage = storage,
        )
        while (f.frame < 3000 && b.engine.find(b.playerPhaseScript)?.scrCur != 16) {
            b.frame()
            f.frame++
        }
        assertEquals(16, b.engine.find(b.playerPhaseScript)?.scrCur, "idle player phase")
        f.manual = true
        return b
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `the chapter 1 opening matches the capture through the real game loop`() {
        assumeTrue(
            ref.available && File(assets, "baserom.gba").isFile &&
                ProcArrayDump.romNames().isNotEmpty(),
            "reference data / ROM / decomp nm file",
        )
        val run = ref.run(RUN)
        // `Run.watch` reads the whole file: once per watch, never per frame.
        val procs = run.watch("procs").also { procsWatch = it }
        flagsWatch = run.watch("flags")
        playStWatch = run.watch("gplayst")
        val nm = ProcArrayDump.romNames()
        val ending = nm.entries.first { it.value == "EventScr_Prologue_EndingScene" }.key
        val f = Frames()
        val b = bootAtIdle(f)
        boot = b
        val stubs = java.util.TreeMap<Int, Int>()
        b.onEventStub = { code -> stubs.merge(code, 1, Int::plus) }
        val gc = b.engine.procs.first { it.script === b.control.script }
        for (stray in STRAY_PROCS) {
            b.engine.procs.filter { it.script?.name == stray }.forEach { b.engine.end(it) }
        }
        b.eventEngine.callEvent(ending, fe8.game.event.EventConst.EV_EXEC_CUTSCENE)
        var n = 0
        while (n < 2000 && gc.child?.script?.name != SAVE_MENU) {
            f.keys = if (n % 8 in 2..3) START else 0
            b.frame()
            f.frame++
            n++
        }
        assertEquals(SAVE_MENU, gc.child?.script?.name, "save menu reached")

        // The save menu with the capture's inputs (PrologueEndingWireTest.playMenu).
        n = 0
        var shift = Int.MIN_VALUE
        while (n < 700) {
            n++
            f.keys = if (shift == Int.MIN_VALUE) 0 else run.inputs[9444 + n + shift - 1]
            b.frame()
            f.frame++
            if (shift == Int.MIN_VALUE &&
                b.engine.procs.any { it.script?.name == "ProcScr_BmFadeIN" }
            ) {
                shift = FADE_IN_CAPTURE_FRAME - (9444 + n)
            }
            if (shift != Int.MIN_VALUE && gc.child?.script?.name != SAVE_MENU) break
        }
        assertTrue(gc.child?.script?.name != SAVE_MENU, "save menu ended")

        // The Chapter 1 intro, then the beginning event; the capture's frame is the boot's
        // plus [offset] (the intro starts at the same capture step as the boot's ChapterIntro).
        var capIntro = -1
        for (step in 9863..CH1_ENGINE_FRAME) {
            val live = ProcArrayDump.ofWatch(procs, step, nm).live
            if (live.any { it.name?.endsWith("ChapterIntro") == true }) {
                capIntro = step
                break
            }
        }
        assertTrue(capIntro > 0, "capture intro step")
        n = 0
        var offset = Int.MIN_VALUE
        var engineAt = -1
        while (n < 1200 && engineAt < 0) {
            n++
            f.keys = if (offset == Int.MIN_VALUE) 0 else run.inputs[n + offset - 1]
            b.frame()
            f.frame++
            val names = b.engine.procs.mapNotNull { it.script?.name }
            if (offset == Int.MIN_VALUE && names.any { it.endsWith("ChapterIntro") }) {
                offset = capIntro - n
            }
            if ("ProcScr_StdEventEngine" in names) engineAt = n
        }
        assertTrue(engineAt > 0, "beginning event engine started")
        offset = CH1_ENGINE_FRAME - engineAt

        // The boot resumes the Prologue from a suspend save that is not the capture's state at
        // f4893 (stats, RNG): seed the blue units and the RNG with the capture's f10298, so that
        // the opening's own effects are compared.
        val atStart = run.region("r$CH1_ENGINE_FRAME", "ewram")
        val want = UnitTable(RomView.ofRom(ByteArray(0))).also { it.loadFrom(atStart) }
        val blue = (1 until 0x40).filter { want.isValid(it) }
        assertTrue(blue.size >= 2, "blue units $blue")
        // `UpdatePrevDeployStates` / `ResetAllPlayerUnitState` ran (BMapMain label 1): state
        // bits, xPos -1 and status of the player units equal f10298 before the seed below.
        for (id in blue) {
            assertTrue(b.units.isValid(id), "unit $id")
            val o = b.units.unitOffset(id)
            for (i in listOf(0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x30)) {
                assertEquals(
                    want.bytes[o + i].toInt() and 255, b.units.bytes[o + i].toInt() and 255,
                    "blue unit $id +0x${i.toString(16)} at the beginning event",
                )
            }
        }
        b.units.loadFrom(atStart)
        val rngWatch = run.watch("rng")
        b.rng.primary.load(
            rngWatch.u16(CH1_ENGINE_FRAME, 0),
            rngWatch.u16(CH1_ENGINE_FRAME, 2),
            rngWatch.u16(CH1_ENGINE_FRAME, 4),
        )

        // The opening: run until the event engine is gone and the player phase idles.
        val diffs = ArrayList<String>()
        val pending = CHECKPOINTS.toMutableList()
        var stall: Stall? = null
        val notes = HashMap<Int, Stall>()
        var fadeFrames = 0
        var fadeCleared = 0
        var idleAt = -1
        var m = engineAt
        while (idleAt < 0 && m + offset < LAST_INPUT) {
            m++
            f.keys = run.inputs[m + offset - 1]
            b.frame()
            f.frame++
            if (b.mu.muDeathFadeExists()) {
                fadeFrames++
                // DISA_IF waits for the fade: the killed unit's slot is cleared only after it.
                if (!b.units.isValid(KILLED_GREEN)) fadeCleared++
            }
            val cp = pending.firstOrNull()
            if (cp != null) {
                val st = stall ?: stallOf(run, nm, cp).also { stall = it }
                notes[cp] = st
                if (reachedCheckpoint(b, st)) {
                    pending.removeAt(0)
                    stall = null
                    diffs += compare(b, run, cp, "r$cp")
                }
            }
            val phase = b.engine.find(b.playerPhaseScript)
            if (b.eventEngine.findStd() == null && phase?.scrCur == 16) idleAt = m
        }
        assertTrue(idleAt > 0, "player phase idle (boot frame $m, capture frame ${m + offset})")
        // A few more frames of the idle phase, then the final state.
        for (i in 0 until 30) {
            f.keys = 0
            b.frame()
            f.frame++
        }
        assertEquals(
            emptyList<Int>(), pending,
            "checkpoints not reached; idle at boot frame $idleAt (capture f${idleAt + offset}), " +
                "cursors ${notes.map { (k, v) -> "$k:${v.cursor.toString(16)} ${v.note}" }}; " +
                "differences so far ${diffs.take(6)}",
        )
        diffs += compare(b, run, FINAL, "f$FINAL")
        assertEquals(emptyMap<Int, Int>(), stubs.toMap(), "event commands that ran as stubs")
        // The KILL of f15526: the death fade proc exists after f15526..f15556 (capture), 31 frames.
        assertEquals(DEATH_FADE_FRAMES, fadeFrames, "frames of the death fade")
        assertEquals(0, fadeCleared, "frames the unit was cleared while its death fade ran")
        assertEquals(emptyList<String>(), diffs.take(40), "differences from the capture")

        // Idle state of f25510..25580: chapter 1, player faction, turn 1, PlayerPhase command 16.
        val ps = b.control.playSt
        assertEquals(1, ps.chapterTurnNumber, "turn")
        assertEquals(0, ps.faction, "faction (player)")
        assertEquals(16, b.engine.find(b.playerPhaseScript)?.scrCur, "PlayerPhase command")
        assertNull(b.eventEngine.findStd(), "no event engine")
    }

    /** The engine cursor and MU count of a checkpoint's capture frame, read once. */
    private class Stall(val cursor: Int, val mus: Int) {
        var note: String? = null
    }

    private fun stallOf(run: ReferenceData.Run, nm: Map<Int, String>, cp: Int): Stall {
        val want = ProcArrayDump.ofWatch(checkNotNull(procsWatch), cp, nm)
        val engine = checkNotNull(want.find("ProcScr_StdEventEngine")) { "engine at f$cp" }
        return Stall(
            engine.u32(0x38),
            want.live.count { it.name == "ProcScr_Mu" || it.name == "ProcScr_MuCtrl" },
        )
    }

    /** True when the boot is at the stall point [stall]: engine cursor equal, same MU count. */
    private fun reachedCheckpoint(b: Fe8Boot, stall: Stall): Boolean {
        val engine = b.eventEngine.findStd() ?: return false
        val state = engine.state as fe8.game.event.EventEngineProc
        if (state.pEventCurrent != stall.cursor) return false
        var mus = 0
        for (proc in b.engine.procs) {
            val name = proc.script?.name
            if (name == "ProcScr_Mu" || name == "ProcScr_MuCtrl") mus++
        }
        if (mus != stall.mus && stall.note == null) {
            stall.note = "cursor hit with $mus MUs, want ${stall.mus}"
        }
        return mus == stall.mus
    }

    private fun compare(b: Fe8Boot, run: ReferenceData.Run, step: Int, tag: String): List<String> {
        val d = ArrayList<String>()
        val ew = run.region(tag, "ewram")
        val rom = RomView.ofRom(File(assets, "baserom.gba").readBytes())
        val want = UnitTable(rom).also { it.loadFrom(ew) }
        for (id in 1..0xBF) {
            if (id == 0x40) continue
            if (want.isValid(id) != b.units.isValid(id)) {
                d += "$tag unit 0x${id.toString(16)} valid want ${want.isValid(id)}"
                continue
            }
            if (!want.isValid(id)) continue
            val o = want.unitOffset(id)
            for (i in 0 until UnitTable.UNIT_SIZE) {
                if (i in 0x3C..0x3F) continue // sprite handle pointer (RAM address, not state)
                val w = want.bytes[o + i].toInt() and 255
                val g = b.units.bytes[o + i].toInt() and 255
                if (w != g) {
                    d += "$tag unit 0x${id.toString(16)}+0x${i.toString(16)} got %02X want %02X"
                        .format(g, w)
                }
            }
        }
        val map = b.battleBmMap
        for ((name, ptrAddr, pool) in listOf(
            Triple("gBmMapUnit", ADDR_MAP_UNIT, map.unit),
            Triple("gBmMapOther", ADDR_MAP_OTHER, map.other),
        )) {
            val ptr = u32(ew, ptrAddr - EWRAM)
            for (y in 0 until map.sizeY) {
                val row = u32(ew, ptr + 4 * y - EWRAM) - EWRAM
                for (x in 0 until map.sizeX) {
                    val w = ew[row + x].toInt() and 255
                    if (w != pool[y, x]) d += "$tag $name ($x,$y) got ${pool[y, x]} want $w"
                }
            }
        }
        val capFlags = checkNotNull(flagsWatch).step(step)
        val got = ByteArray(capFlags.size)
        b.flags.copyOut(got)
        for (i in got.indices) {
            if (got[i] != capFlags[i]) {
                d += "$tag flag byte $i got %02X want %02X".format(got[i], capFlags[i])
            }
        }
        val wantPs = PlaySt().also { it.decode(checkNotNull(playStWatch).step(step), 0) }
        val gotPs = b.control.playSt
        fun fields(p: PlaySt) = listOf(
            p.chapterIndex, p.chapterTurnNumber, p.faction, p.chapterStateBits,
            p.chapterVisionRange, p.chapterWeatherId, p.tutorialCounter, p.tutorialExecType,
        )
        if (fields(wantPs) != fields(gotPs)) {
            d += "$tag gPlaySt ${fields(gotPs)} want ${fields(wantPs)}"
        }
        return d
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val START = 0x08
        const val SAVE_MENU = "gProcScr_SaveMenuPostChapter"
        const val FADE_IN_CAPTURE_FRAME = 9461
        const val CH1_ENGINE_FRAME = 10298
        const val LAST_INPUT = 25580
        const val FINAL = 25510
        const val DEATH_FADE_FRAMES = 31
        const val KILLED_GREEN = 0x42
        const val EWRAM = 0x02000000
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_OTHER = 0x0202E4F0
        val STRAY_PROCS = listOf(
            "gProcScr_TerrainDisplay", "gProcScr_GoalDisplay", "gProcScr_UnitDisplay_MinimugBox",
            "ProcScr_GreenTextColor",
        )

        /** Dumps taken while the event waits (STAL after a walk or a fight), in script order. */
        val CHECKPOINTS = listOf(13110, 15560, 16160, 20270)
    }
}
