package fe8.testing

import gbafe.core.VerifiesPort
import gbafe.input.KeyStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of [KeyStatus] against mGBA RAM (`sKeyStatusBuffer`, `gKeyStatusIgnoredSt`).
 *
 * Ground truth: reference run `key_status_title` (ROM repo `harness/gen_scripts.py`): boot to the
 * title, then a RAM dump after every frame f901..f956 while holding DOWN 20f (auto-repeat), L+R
 * (A/B/L/R release detection), UP+LEFT then RIGHT then RIGHT+DOWN (combo change resets the timer),
 * SELECT (Start/Select idle counter), A+B and B. Each frame is checked as one step: load the struct
 * from dump f-1, feed frame f's harness input, compare all 0x14 bytes with dump f. A step where the
 * game ran no key update (a lag frame) shows as an unchanged buffer; the set of those is pinned.
 */
class KeyStatusDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val bufferAddr get() = sym["sKeyStatusBuffer"].also { check(it.size == KeyStatus.STRUCT_SIZE) }.address
    private val ignoreAddr get() = sym["gKeyStatusIgnoredSt"].address

    private fun struct(run: ReferenceData.Run, dump: String): ByteArray =
        run.region(dump, "ewram").copyOfRange(bufferAddr - GbaMem.EWRAM, bufferAddr - GbaMem.EWRAM + KeyStatus.STRUCT_SIZE)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    @VerifiesPort("_UpdateKeyStatus", "UpdateKeyStatus")
    fun `per-frame key status matches mGBA on the title screen`() {
        assumeTrue(ref.available && java.io.File(ref.root, "key_status_title/manifest.txt").isFile,
            "mGBA reference run key_status_title not present (run tools/setup.sh)")
        val run = ref.run("key_status_title")
        val dumps = run.dumps.entries.toList()
        val ks = KeyStatus()
        val out = ByteArray(KeyStatus.STRUCT_SIZE)
        val lagFrames = mutableListOf<Int>()
        var stepped = 0
        for (i in 1 until dumps.size) {
            val (prevName, prevFrame) = dumps[i - 1]
            val (name, frame) = dumps[i]
            assertEquals(prevFrame + 1, frame, "dumps must be consecutive")
            val before = struct(run, prevName)
            val after = struct(run, name)
            ks.readFrom(before)
            ks.ignoreMask = GbaMem.u16(run.region(prevName, "iwram"), GbaMem.IWRAM, ignoreAddr)
            ks.update(run.inputs[frame - 1])
            ks.writeTo(out)
            if (before.contentEquals(after) && !out.contentEquals(after)) {
                lagFrames += frame
                continue
            }
            assertEquals(hex(after), hex(out), "sKeyStatusBuffer after f$frame (input 0x%03x)".format(run.inputs[frame - 1]))
            stepped++
        }
        // A+B at f949 starts a title transition; the game skips one key update two frames later.
        assertEquals(listOf(951), lagFrames, "frames without a key update")
        assertTrue(stepped >= 50, "stepped $stepped frames")
    }

    @Test
    @VerifiesPort("ResetKeyStatus")
    fun `boot key status matches ResetKeyStatus plus idle updates`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_boot")
        var checked = 0
        for ((name, frame) in run.dumps) {
            if ((0 until frame).any { run.inputs[it] != 0 }) break // only the input-free health & safety part
            val expected = struct(run, name)
            // AgbMain: zeroed EWRAM, ResetKeyStatus, then one UpdateKeyStatus per game frame. The game frame
            // count (lag frames during boot loads differ from emulated frames) is TimeSinceStartSelect.
            val ks = KeyStatus()
            ks.reset()
            val updates = (expected[18].toInt() and 0xFF) or ((expected[19].toInt() and 0xFF) shl 8)
            repeat(updates) { ks.update(0) }
            val out = ByteArray(KeyStatus.STRUCT_SIZE)
            ks.writeTo(out)
            assertEquals(hex(expected), hex(out), "sKeyStatusBuffer at $name")
            checked++
        }
        assertTrue(checked >= 10, "checked $checked dumps")
    }

    @Test
    fun `update does not allocate`() {
        val ks = KeyStatus()
        ks.reset()
        var i = 0
        val bytes = AllocationMeter.measure(warmup = 50_000, iterations = 50_000) {
            ks.update((i++ * 7) and 0x3FF)
        }
        assertEquals(0L, bytes)
    }
}
