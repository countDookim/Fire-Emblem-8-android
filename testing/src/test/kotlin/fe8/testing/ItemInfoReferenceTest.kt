package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ItemInfo
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [ItemInfo.getItemType] and [ItemInfo.getItemAttributes] against `gBattleActor` and
 * `gBattleTarget` weapon fields in `ram_trace_prologue_battle` `r14430`.
 */
class ItemInfoReferenceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("GetItemType", "GetItemAttributes")
    fun `actor and target weaponType and weaponAttributes match prologue battle r14430`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val ewram = ref.run("ram_trace_prologue_battle").region("r14430", "ewram")
        val items = ItemInfo(RomView.ofRom(romFile.readBytes()))

        val actorWeapon = u16(ewram, BATTLE_ACTOR_OFFSET + WEAPON_OFFSET)
        val actorAttrs = u32(ewram, BATTLE_ACTOR_OFFSET + WEAPON_ATTRS_OFFSET)
        val actorType = ewram[BATTLE_ACTOR_OFFSET + WEAPON_TYPE_OFFSET].toInt() and 0xFF

        val targetWeapon = u16(ewram, BATTLE_TARGET_OFFSET + WEAPON_OFFSET)
        val targetAttrs = u32(ewram, BATTLE_TARGET_OFFSET + WEAPON_ATTRS_OFFSET)
        val targetType = ewram[BATTLE_TARGET_OFFSET + WEAPON_TYPE_OFFSET].toInt() and 0xFF

        assertEquals(actorAttrs, items.getItemAttributes(actorWeapon), "actor weaponAttributes")
        assertEquals(targetAttrs, items.getItemAttributes(targetWeapon), "target weaponAttributes")
        assertEquals(actorType, items.getItemType(actorWeapon), "actor weaponType")
        assertEquals(targetType, items.getItemType(targetWeapon), "target weaponType")
        assertEquals(0xFF, items.getItemType(0), "empty item weaponType is ITYPE_ITEM (0xFF)")
    }

    // Not @VerifiesPort: every item passed in ram_trace_prologue_clear f3803..3872 and
    // CpAiFlagsDifferentialTest has useEffectId == 0, so a constant-0 implementation survives.
    @Test
    fun `getItemUseEffect reads useEffectId from ROM for prologue_clear and healing items`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val items = ItemInfo(RomView.ofRom(romFile.readBytes()))
        // Weapons exercised in ram_trace_prologue_clear f3803..3872 (ITEM_SWORD_STEEL,
        // ITEM_LANCE_SILVER, ITEM_SWORD_RAPIER, ITEM_AXE_IRON): all 0.
        for (item in intArrayOf(0x1E03, 0x1417, 0x2509, 0x2D1F)) {
            assertEquals(0, items.getItemUseEffect(item), "weapon 0x${item.toString(16)}")
        }
        // Consumable / staff use effects from ROM gItemData: ITEM_STAFF_HEAL (0x4B -> 1),
        // ITEM_VULNERARY (0x6C -> 0x21), ITEM_ELIXIR (0x6D -> 0x22).
        assertEquals(1, items.getItemUseEffect(0x0F4B), "ITEM_STAFF_HEAL")
        assertEquals(0x21, items.getItemUseEffect(0x036C), "ITEM_VULNERARY")
        assertEquals(0x22, items.getItemUseEffect(0x036D), "ITEM_ELIXIR")
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(buf: ByteArray, off: Int): Int =
        u16(buf, off) or (u16(buf, off + 2) shl 16)

    private companion object {
        const val BATTLE_ACTOR_OFFSET = 0x03A4EC
        const val BATTLE_TARGET_OFFSET = 0x03A56C
        const val WEAPON_OFFSET = 0x48
        const val WEAPON_ATTRS_OFFSET = 0x4C
        const val WEAPON_TYPE_OFFSET = 0x50
    }
}
