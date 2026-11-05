package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [Camera] against the per-frame RAM dumps (`r<frame>`) of the scrolling reference runs.
 *
 * Isolated transitions (inputs in the dump before the camera moves, output in a later dump):
 * - `ram_trace_ch2_scroll` f1145..f1149: GameCtrl_StartResumedGame sets the camera to
 *   GetCameraCentered{X,Y}(16 * playerCursor); the map is 15x15 (cameraMax (0, 80)), the cursor is
 *   (5, 6): x clamps at 0, y (16) does not clamp.
 * - `ram_trace_newgame_bm` f9512..f9513: Event25_ChangeMap sets the camera to
 *   GetCameraCentered{X,Y}(16 * (14, 10)), the coordinates in event slot 0xB; the 22x28 prologue
 *   map has cameraMax (112, 288) and neither axis is clamped.
 * - `ram_trace_newgame_bm` f9784..f9801: EnsureCameraOntoPosition at the start cursor starts a
 *   CamMove whose target is GetCameraAdjusted{X,Y}(16 * cursor): x stays inside the margins, y
 *   clamps at 0. The proc ends with the camera exactly on its target.
 *
 * Not reached by any run: IsCameraNotWatchingPosition (bmmenu.c, bmitemuse.c) and
 * StoreAdjustedCameraPositions (sub_8015D84 CamMove, SetSomeRealCamPos), so those stay untagged.
 * The `ram_trace_ch6_fog` cursor holds only run HandleMoveCameraWithMapCursor, which does not
 * call these functions.
 */
class CameraScrollDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun frameName(frame: Int) = "r%05d".format(frame)

    private fun bmAt(run: String, frame: Int): BmSt {
        val ewram = ref.run(run).region(frameName(frame), "ewram")
        return BmSt().also { it.decode(ewram, BM_ST - EWRAM) }
    }

    private fun hasFrame(run: String, frame: Int) =
        File(ref.root, "$run/${frameName(frame)}.ewram.bin").isFile

    private fun sameCamera(a: BmSt, b: BmSt) = a.cameraX == b.cameraX && a.cameraY == b.cameraY

    /** First frame in ([from], [to]] whose camera differs from the previous frame's. */
    private fun firstCameraChange(run: String, from: Int, to: Int): Int {
        for (f in from + 1..to) {
            if (!sameCamera(bmAt(run, f - 1), bmAt(run, f))) return f
        }
        error("no camera change in $run f$from..f$to")
    }

    @Test
    @VerifiesPort("GetCameraCenteredX", "GetCameraCenteredY")
    fun `resumed chapter centres the camera on the cursor`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = "ram_trace_ch2_scroll"
        val f = firstCameraChange(run, 1100, 1200)
        val before = bmAt(run, f - 1)
        val after = bmAt(run, f)
        assertEquals(before.playerCursorX, after.playerCursorX)
        assertEquals(before.playerCursorY, after.playerCursorY)
        assertEquals(before.cameraMaxY, after.cameraMaxY)
        assertEquals(0, before.cameraY)
        assertTrue(after.cameraY > 0 && after.cameraY < after.cameraMaxY, "unclamped y")
        assertEquals(
            after.cameraX,
            Camera.getCameraCenteredX(after, 16 * after.playerCursorX),
            "camera.x at f$f",
        )
        assertEquals(
            after.cameraY,
            Camera.getCameraCenteredY(after, 16 * after.playerCursorY),
            "camera.y at f$f",
        )
    }

    @Test
    @VerifiesPort("GetCameraCenteredX", "GetCameraCenteredY")
    fun `change map event centres the camera on its event slot position`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = "ram_trace_newgame_bm"
        val f = firstCameraChange(run, 9502, 9530)
        val before = bmAt(run, f - 1)
        val after = bmAt(run, f)
        assertEquals(0, before.cameraX)
        assertEquals(0, before.cameraY)
        assertEquals(before.cameraMaxX, after.cameraMaxX)
        assertEquals(before.cameraMaxY, after.cameraMaxY)
        // Event25_ChangeMap: x = low u16, y = high u16 of event slot 0xB (gEventSlots, IWRAM).
        val iwram = ref.run(run).region(frameName(f - 1), "iwram")
        val slot = EVENT_SLOT_B - IWRAM
        val x = (iwram[slot].toInt() and 0xFF) or ((iwram[slot + 1].toInt() and 0xFF) shl 8)
        val y = (iwram[slot + 2].toInt() and 0xFF) or ((iwram[slot + 3].toInt() and 0xFF) shl 8)
        assertEquals(14, x)
        assertEquals(10, y)
        assertEquals(after.cameraX, Camera.getCameraCenteredX(after, x * 16), "camera.x at f$f")
        assertEquals(after.cameraY, Camera.getCameraCenteredY(after, y * 16), "camera.y at f$f")
        // Neither axis is clamped, so the centring itself is what is compared.
        assertTrue(after.cameraX in 1 until after.cameraMaxX, "x unclamped ${after.cameraX}")
        assertTrue(after.cameraY in 1 until after.cameraMaxY, "y unclamped ${after.cameraY}")
    }

    @Test
    @VerifiesPort("GetCameraAdjustedX", "GetCameraAdjustedY")
    fun `camera move to the start cursor ends on the adjusted camera`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = "ram_trace_newgame_bm"
        // Existing dumps (every 30th frame is missing); the cursor leaves (0, 0) when
        // EnsureCameraOntoPosition runs, before the camera has moved.
        val frames = (9519..9890).filter { hasFrame(run, it) }
        val s = frames.indexOfFirst {
            bmAt(run, it).let { b -> b.playerCursorX != 0 || b.playerCursorY != 0 }
        }
        val from = bmAt(run, frames[s])
        assertEquals(from.cameraPreviousX, from.cameraX)
        assertEquals(from.cameraPreviousY, from.cameraY)
        // CamMove has ended once the camera stays put for 4 dumps.
        var e = s + 1
        while (!(1..4).all { sameCamera(bmAt(run, frames[e]), bmAt(run, frames[e + it])) }) e++
        val to = bmAt(run, frames[e])
        val at = "f${frames[s]} -> f${frames[e]}"
        assertTrue(!sameCamera(from, to), "camera moved")
        assertTrue(to.cameraY == 0 && from.cameraY > 0, "y clamps at 0 (${from.cameraY})")
        assertEquals(to.cameraX, Camera.getCameraAdjustedX(from, 16 * from.playerCursorX), at)
        assertEquals(to.cameraY, Camera.getCameraAdjustedY(from, 16 * from.playerCursorY), at)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val EVENT_SLOT_B = 0x030004B8 + 4 * 0xB
    }
}
