package fe8.testing

import fe8.game.RomView
import fe8.game.bm.Mu
import gbafe.render.ApHost
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Sanity run of `src/ap.c` on an FE8 map-unit animation (no ground-truth comparison yet). */
class SpriteAnimPlayerTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @Test
    fun walkAnimationEmitsSpritesAndAdvances() {
        assumeTrue(romFile.exists())
        val rom = RomView.ofRom(romFile.readBytes())
        val cache = HashMap<Int, SpriteObject>()
        val host = object : ApHost {
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val layers = SpriteLayers()
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        player.clearAll()

        val def = rom.u32(Mu.MU_INFO_TABLE + 8 * (1 - 1) + 4)
        val ap = checkNotNull(player.create(def, 10))
        player.switchAnimation(ap, 4)

        val seen = HashSet<Int>()
        var timerChanged = false
        var last = ap.frameTimer
        for (frame in 0 until 60) {
            layers.clear()
            player.update(ap, 100, 80)
            assertEquals(1, layers.allocIt)
            assertTrue(layers.layers[10].next?.obj != null)
            seen.add(ap.pCurrentObjData)
            if (ap.frameTimer != last) timerChanged = true
            last = ap.frameTimer
        }
        assertTrue(timerChanged, "frameTimer advances")
        assertTrue(seen.size > 1, "walk cycles through several frames")
        player.delete(ap)
        assertEquals(0, ap.pDefinition)
    }
}
