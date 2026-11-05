package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [Camera] against mGBA RAM and against the C formulas.
 *
 * Ground truth: reference run `ram_trace_first_phase` (a dump per frame, chapter 0 player phase on
 * a 15x10 map, so `cameraMax` is (0, 0) and the camera never leaves (0, 0)). Only
 * `SetCursorMapPosition` has an isolable effect there: one frame snaps the cursor, its target and
 * its display position at once (the cursor otherwise glides 4 px per frame). The camera functions
 * cannot be isolated in this trace (their result is 0 for every cursor position the trace
 * visits), so here they are checked only as a consistency check and on tables over all map
 * sizes; the verifying tests are in [CameraScrollDifferentialTest].
 */
class CameraDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_first_phase"

    private fun hasDump(dump: String) = java.io.File(ref.root, "$run/$dump.ewram.bin").isFile

    private fun mapSizeOf(ewram: ByteArray, axis: Int): Int {
        val o = MAP_SIZE - EWRAM + axis * 2
        return ((ewram[o].toInt() and 0xFF) or (ewram[o + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun bmOf(ewram: ByteArray): BmSt = BmSt().also { it.decode(ewram, BM_ST - EWRAM) }

    @Test
    @VerifiesPort("SetCursorMapPosition")
    fun `cursor snap frames match SetCursorMapPosition`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        val dumps = r.dumps.entries.filter { hasDump(it.key) }
        var snaps = 0
        for (i in 1 until dumps.size) {
            val (prevName, prevFrame) = dumps[i - 1]
            val (name, frame) = dumps[i]
            if (frame != prevFrame + 1) continue
            val before = bmOf(r.region(prevName, "ewram"))
            val after = bmOf(r.region(name, "ewram"))
            // The glide moves the display 4 px per frame; a bigger jump onto the target is a snap.
            val jumped =
                Math.abs(after.playerCursorDisplayX - before.playerCursorDisplayX) > 4 ||
                    Math.abs(after.playerCursorDisplayY - before.playerCursorDisplayY) > 4
            val snapped = after.playerCursorDisplayX == after.cursorTargetX &&
                after.playerCursorDisplayY == after.cursorTargetY &&
                after.cursorTargetX == after.playerCursorX * 16 &&
                after.cursorTargetY == after.playerCursorY * 16
            if (!jumped || !snapped) continue
            // The game's argument is the new cursor position; target/display are what is checked.
            Camera.setCursorMapPosition(before, after.playerCursorX, after.playerCursorY)
            val at = "$prevName -> $name"
            assertEquals(after.playerCursorX, before.playerCursorX, "cursor.x $at")
            assertEquals(after.playerCursorY, before.playerCursorY, "cursor.y $at")
            assertEquals(after.cursorTargetX, before.cursorTargetX, "target.x $at")
            assertEquals(after.cursorTargetY, before.cursorTargetY, "target.y $at")
            assertEquals(after.playerCursorDisplayX, before.playerCursorDisplayX, "display.x $at")
            assertEquals(after.playerCursorDisplayY, before.playerCursorDisplayY, "display.y $at")
            snaps++
        }
        assertTrue(snaps >= 1, "no cursor snap frame found, $snaps")
    }

    @Test
    fun `camera helpers keep the trace camera fixed on the small map`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        var checked = 0
        for ((name, _) in r.dumps) {
            if (!hasDump(name)) continue
            val ewram = r.region(name, "ewram")
            val bm = bmOf(ewram)
            val mapX = mapSizeOf(ewram, 0)
            val cx = bm.playerCursorX
            val cy = bm.playerCursorY
            assertEquals((mapX * 16 - Camera.DISPLAY_WIDTH).coerceAtLeast(0), bm.cameraMaxX, name)
            assertEquals(bm.cameraX, Camera.getCameraAdjustedX(bm, cx * 16), name)
            assertEquals(bm.cameraY, Camera.getCameraAdjustedY(bm, cy * 16), name)
            assertFalse(Camera.isCameraNotWatchingPosition(bm, cx, cy), name)
            assertEquals(bm.cameraX, Camera.getCameraCenteredX(bm, cx * 16 + 8), name)
            assertEquals(bm.cameraY, Camera.getCameraCenteredY(bm, cy * 16 + 8), name)
            checked++
        }
        assertTrue(checked > 100, "checked $checked dumps")
    }

    private fun bm(camX: Int, camY: Int, maxX: Int, maxY: Int) = BmSt().also {
        it.cameraX = camX
        it.cameraY = camY
        it.cameraMaxX = maxX
        it.cameraMaxY = maxY
    }

    /** Independent formulation: scroll the minimum distance to bring [p] inside the margins. */
    private fun scrolled(camera: Int, p: Int, lo: Int, hi: Int, max: Int): Int = when {
        p > camera + hi -> minOf(p - hi, max)
        p < camera + lo -> maxOf(p - lo, 0)
        else -> camera
    }

    @Test
    fun `adjusted camera matches the C formula over every map size`() {
        for (mapW in 15..64) {
            for (mapH in 10..64) {
                val maxX = mapW * 16 - Camera.DISPLAY_WIDTH
                val maxY = mapH * 16 - Camera.DISPLAY_HEIGHT
                for (camX in 0..maxX step 16) {
                    for (px in -16..(mapW * 16) step 8) {
                        val bm = bm(camX, 0, maxX, maxY)
                        val want = scrolled(camX, px, Camera.MARGIN_LEFT, Camera.MARGIN_RIGHT, maxX)
                        assertEquals(want, Camera.getCameraAdjustedX(bm, px), "x $mapW $camX $px")
                    }
                }
                for (camY in 0..maxY step 16) {
                    for (py in -16..(mapH * 16) step 8) {
                        val bm = bm(0, camY, maxX, maxY)
                        val want = scrolled(camY, py, Camera.MARGIN_TOP, Camera.MARGIN_BOTTOM, maxY)
                        assertEquals(want, Camera.getCameraAdjustedY(bm, py), "y $mapH $camY $py")
                    }
                }
            }
        }
    }

    @Test
    fun `centered camera is clamped and tile aligned over every map size`() {
        for (mapW in 15..64) {
            val maxX = mapW * 16 - Camera.DISPLAY_WIDTH
            val bm = bm(0, 0, maxX, 0)
            for (px in -32..(mapW * 16 + 32)) {
                val want = (px - 120).coerceIn(0, maxX) / 16 * 16
                assertEquals(want, Camera.getCameraCenteredX(bm, px), "x $mapW p=$px")
            }
        }
        for (mapH in 10..64) {
            val maxY = mapH * 16 - Camera.DISPLAY_HEIGHT
            val bm = bm(0, 0, 0, maxY)
            for (py in -32..(mapH * 16 + 32)) {
                val want = (py - 80).coerceIn(0, maxY) / 16 * 16
                assertEquals(want, Camera.getCameraCenteredY(bm, py), "y $mapH p=$py")
            }
        }
    }

    @Test
    fun `stored adjusted positions follow the C clamps`() {
        for (mapW in 15..64) {
            for (x in 0 until mapW) {
                val want = if (maxOf(x - 7, 0) + 8 > mapW - 1) mapW - 15 else maxOf(x - 7, 0)
                assertEquals(want, Camera.storeAdjustedCameraPositionX(x, mapW), "x $mapW $x")
            }
        }
        for (mapH in 10..64) {
            for (y in 0 until mapH) {
                val want = if (maxOf(y - 5, 0) + 4 > mapH - 1) mapH - 10 else maxOf(y - 5, 0)
                assertEquals(want, Camera.storeAdjustedCameraPositionY(y, mapH), "y $mapH $y")
            }
        }
        // Spot values. The clamp tests camera + 8 (not + 15), so cursor column 8 on a 15 wide map
        // still gives camera column 1 although cameraMax.x is 0; a narrow map goes negative.
        assertEquals(1, Camera.storeAdjustedCameraPositionX(8, 15))
        assertEquals(0, Camera.storeAdjustedCameraPositionX(14, 15))
        assertEquals(-5, Camera.storeAdjustedCameraPositionX(9, 10))
        assertEquals(-6, Camera.storeAdjustedCameraPositionY(3, 4))
    }

    @Test
    fun `is camera not watching reports whether the camera must move`() {
        val bm = bm(0, 0, 160, 160)
        assertFalse(Camera.isCameraNotWatchingPosition(bm, 3, 2))
        assertTrue(Camera.isCameraNotWatchingPosition(bm, 20, 2))
        assertTrue(Camera.isCameraNotWatchingPosition(bm, 3, 12))
        bm.cameraX = 160
        bm.cameraY = 80
        assertFalse(Camera.isCameraNotWatchingPosition(bm, 14, 7))
        assertTrue(Camera.isCameraNotWatchingPosition(bm, 14, 13))
    }

    @Test
    fun `set cursor map position truncates to s16`() {
        val bm = BmSt()
        Camera.setCursorMapPosition(bm, 4, 5)
        assertEquals(
            listOf(4, 5, 64, 80, 64, 80),
            listOf(
                bm.playerCursorX, bm.playerCursorY, bm.cursorTargetX, bm.cursorTargetY,
                bm.playerCursorDisplayX, bm.playerCursorDisplayY,
            ),
        )
        Camera.setCursorMapPosition(bm, 0x0800, -1)
        assertEquals(-32768, bm.cursorTargetX)
        assertEquals(-16, bm.cursorTargetY)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BM_ST = 0x0202BCB0
        const val MAP_SIZE = 0x0202E4D4
    }
}
