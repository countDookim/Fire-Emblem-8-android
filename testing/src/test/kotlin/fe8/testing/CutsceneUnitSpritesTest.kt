package fe8.testing

import fe8.game.RomView
import fe8.game.bm.Mu
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * D-02: unit sprites (OBJ) are drawn while the Prologue opening events run, before the battle
 * map. The original's `ram_trace_prologue` dumps (every 300 frames) are taken
 * during those events; each has a unit table (valid units and their tiles) and hardware OAM.
 * Our headless New Game flow ([PrologueBoot]) must, for each of these dumps, have a frame with
 * the same unit table (character id and tile of every valid unit) and, on that frame with the
 * event engine still running, the same unit objects in the synced OAM: sorted attr0..attr2
 * values (y, x, tile, palette, shape and size) of every object whose tile is in the sprite sheet
 * range 128..511 (map sprites; the cursor uses tile 2, text and faces use tiles >= 512).
 * Frame numbers differ between the runs (different input timing), so frames are matched by
 * the unit table and the objects, never by number (the first frame with a dump's table can still
 * show a moving unit, e.g. f12900's two last units appear one frame before their sprites).
 * Scope: the dumps in [DUMP_FRAMES]: f09600..f12900 every 300 frames (several share a table)
 * and f18000..f19200. The other later dumps have no map-sprite objects, or have moving units
 * or other animation phases, so they are not compared; neither are the objects' draw order,
 * the affine parameters and the MU objects (tiles 0x380..0x3FF).
 */
class CutsceneUnitSpritesTest {
    private val ref = ReferenceData.fromEnvironment()

    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun table(t: UnitTable) = (1..0xFF).filter { t.isValid(it) }
        .map { "${t.charId(it)}@${t.xPos(it)},${t.yPos(it)}" }.sorted()

    /** Sorted `y,x,tile,palette` plus shape and size of every map-sprite object in [oam]. */
    private fun unitObjects(oam: (Int) -> Int): List<String> = (0 until OBJ_COUNT).mapNotNull {
        val a0 = oam(it * 4)
        val a1 = oam(it * 4 + 1)
        val a2 = oam(it * 4 + 2)
        val tile = a2 and 0x3FF
        val hidden = (a0 and 0x300) == 0x200 || (a0 and 0xFF) >= SCREEN_HEIGHT
        if (hidden || tile !in SPRITE_TILES) null
        else "${a0 and 0xFF},${a1 and 0x1FF},$tile,${a2 shr 12},${a0 shr 12},${a1 shr 14}"
    }.sorted()

    @Test
    @VerifiesPort("ClearCutsceneUnits")
    fun `unit objects of the opening events match the dumps taken during them`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run("ram_trace_prologue")
        val dumps = DUMP_FRAMES.map { f ->
            val name = "f%05d".format(f)
            val units = UnitTable(rom).also { it.loadFrom(run.region(name, "ewram")) }
            val hw = run.region(name, "oam")
            Triple(name, table(units), unitObjects { GbaMem.u16(hw, 0, it * 2) })
        }
        for ((name, tab, objs) in dumps) {
            assertTrue(tab.isNotEmpty() && objs.isNotEmpty(), "$name: no cutscene units in dump")
        }
        val pb = PrologueBoot(romFile)
        val matched = HashSet<String>()
        var drawn = 0
        pb.onFrame = {
            if (pb.boot.eventEngine.eventEngineExists()) {
                val tab = table(pb.boot.units)
                val oam = pb.boot.loop.oam.oam
                val objs = unitObjects { oam[it].toInt() and 0xFFFF }
                if (objs.isNotEmpty()) drawn++
                for ((name, want, wantObjs) in dumps) {
                    if (want == tab && wantObjs == objs) matched += name
                }
            }
        }
        assertTrue(pb.reachIdle(), "Prologue idle not reached")
        assertTrue(drawn > 0, "no unit object drawn while the events ran")
        assertEquals(dumps.map { it.first }.toSet(), matched, "dumps without a matching frame")
    }

    /** D-06: a walking unit (MU in movement) is drawn: an OBJ with a MU tile is on screen. */
    @Test
    fun `a walking MU is drawn on every frame it moves`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val pb = PrologueBoot(romFile)
        var moving = 0
        var drawn = 0
        pb.onFrame = {
            val walking = pb.boot.mu.configs.any {
                it.slot != 0 && it.mu?.state == Mu.MU_STATE_MOVEMENT && it.mu?.hiddenB == 0
            }
            if (walking) {
                moving++
                val oam = pb.boot.loop.oam.oam
                val visible = (0 until OBJ_COUNT).any {
                    val a0 = oam[it * 4].toInt() and 0xFFFF
                    val tile = oam[it * 4 + 2].toInt() and 0x3FF
                    (a0 and 0x300) != 0x200 && (a0 and 0xFF) < SCREEN_HEIGHT && tile in MU_TILES
                }
                if (visible) drawn++
            }
        }
        assertTrue(pb.reachIdle(), "Prologue idle not reached")
        assertTrue(moving > 0, "no MU walked")
        println("MU walking frames: $moving, with a MU object: $drawn")
        assertTrue(drawn * 10 >= moving * 9, "walking MU not drawn: $drawn of $moving frames")
    }

    private companion object {
        val MU_TILES = 0x380..0x3FF
        /** Dumps with map-sprite objects whose table and objects occur in our run. */
        val DUMP_FRAMES = (9600..12900 step 300).toList() + (18000..19200 step 300).toList()
        const val OBJ_COUNT = 128
        const val SCREEN_HEIGHT = 160
        val SPRITE_TILES = 128..511
    }
}
