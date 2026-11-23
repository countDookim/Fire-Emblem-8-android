package fe8.game

import fe8.game.bm.ItemInfo
import fe8.game.bm.UnitLoader
import gba.testkit.GbaMem
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Synthetic regression test (no magic-seal capture exists): `GetUnitWeaponUsabilityBits` uses
 * `CanUnitUseWeapon`, which ignores a position magic seal, so a sealed unit holding a magic
 * weapon still gets the weapon bit.
 */
class WeaponUsabilityBitsTest {
    @Test
    fun `magic seal on the tile does not clear the weapon bit`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "baserom.gba not present")
        val rom = RomView.ofRom(file.readBytes())
        val boot = Fe8Boot(rom, { 0 }, RngService())
        boot.bmapMain
        val wiring = checkNotNull(boot.playerWiring)
        val info = ItemInfo(rom)
        val lockMask = ItemInfo.IA_LOCK_ANY
        val magic = (1..0xFF).first {
            val a = info.getItemAttributes(it)
            (a and ItemInfo.IA_WEAPON) != 0 && (a and ItemInfo.IA_MAGIC) != 0 &&
                (a and lockMask) == 0
        }

        val id = 1
        val o = boot.units.unitOffset(id)
        val b = boot.units.bytes
        fun put32(at: Int, v: Int) {
            for (k in 0..3) b[at + k] = (v ushr (8 * k)).toByte()
        }
        put32(o + UnitLoader.OFF_CHARACTER, UnitLoader.characterDataAt(1))
        put32(o + UnitLoader.OFF_CLASS, UnitLoader.classDataAt(1))
        for (k in 0 until 8) b[o + UnitLoader.OFF_RANKS + k] = 0xFF.toByte()
        b[o + UnitLoader.OFF_STATUS] = 0
        b[o + UnitLoader.OFF_ITEMS] = magic.toByte()
        b[o + UnitLoader.OFF_ITEMS + 1] = 0x20
        for (k in 2 until 10) b[o + UnitLoader.OFF_ITEMS + k] = 0

        var sealQueried = false
        wiring.unitMenu.hooks.isPositionMagicSealed = { _, _ -> sealQueried = true; true }

        assertEquals(1, wiring.weaponUsabilityBits(id))
        assertFalse(sealQueried, "CanUnitUseWeapon must not consult the position magic seal")
    }
}
