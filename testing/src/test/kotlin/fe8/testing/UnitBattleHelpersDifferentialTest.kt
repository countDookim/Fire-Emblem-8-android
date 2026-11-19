package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Unit battle helpers from `src/bmunit.c`:
 * - `GetUnitSMSId`
 * - `GetUnitCurrentHp`
 * - `GetUnitLeaderCharId`
 * - `GetUnitKeyItemSlotForTerrain`
 * - `SetUnitStatus`
 * - `RefreshAllies`
 *
 * Verified against `ram_trace_prologue_clear` dumps at `f4181..4274` and `f4894`.
 */
class UnitBattleHelpersDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun loadContext(dumpName: String): Context {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom)
        val run = ref.run("ram_trace_prologue_clear")
        val ewram = run.region(dumpName, "ewram")
        table.loadFrom(ewram)
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val sprites = UnitSprites(rom, BgHardware(), BmSt())
        val handles = UnitSpriteHandles(rom, sprites, table)
        return Context(rom, table, loader, handles, ewram)
    }

    private data class Context(
        val rom: RomView,
        val table: UnitTable,
        val loader: UnitLoader,
        val handles: UnitSpriteHandles,
        val ewram: ByteArray,
    )

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    @Test
    @VerifiesPort("GetUnitSMSId")
    fun `GetUnitSMSId returns class SMSId on capture frames`() {
        // Frames 4181, 4274, 4894
        for (dump in listOf("r04181", "r04274", "r04894")) {
            val ctx = loadContext(dump)
            val table = ctx.table
            val emptyTraps = ByteArray(0)

            // Blue unit 1: Eirika, class 1 (Eirika Lord)
            val eirikaOffset = table.unitOffset(1)
            val eirikaClassPtr = u32(table.bytes, eirikaOffset + UnitTable.OFF_CLASS)
            val expectedEirikaSms = ctx.rom.u8(eirikaClassPtr + 6)
            assertEquals(expectedEirikaSms, table.getUnitSMSId(1))
            assertEquals(expectedEirikaSms, ctx.handles.getUnitSMSId(1, emptyTraps))

            // Blue unit 2: Seth, class 9 (Paladin)
            val sethOffset = table.unitOffset(2)
            val sethClassPtr = u32(table.bytes, sethOffset + UnitTable.OFF_CLASS)
            val expectedSethSms = ctx.rom.u8(sethClassPtr + 6)
            assertEquals(expectedSethSms, table.getUnitSMSId(2))
            assertEquals(expectedSethSms, ctx.handles.getUnitSMSId(2, emptyTraps))

            // Red unit 1 at r04274: O'Neill, class 0x3F (Fighter)
            if (dump == "r04274") {
                val bossOffset = table.unitOffset(0x81)
                val bossClassPtr = u32(table.bytes, bossOffset + UnitTable.OFF_CLASS)
                val expectedBossSms = ctx.rom.u8(bossClassPtr + 6)
                assertEquals(expectedBossSms, table.getUnitSMSId(0x81))
                assertEquals(expectedBossSms, ctx.handles.getUnitSMSId(0x81, emptyTraps))
            }
        }
    }

    @Test
    fun `GetUnitSMSId ridden ballista SMS synthetic test`() {
        val ctx = loadContext("r04274")
        val table = ctx.table
        val offset = table.unitOffset(1)
        table.setState(1, table.state(1) or UnitTable.US_IN_BALLISTA)
        table.bytes[offset + UnitTable.OFF_BALLISTA] = 0 // trap index 0

        // Trap 0: extra = ITEM_BALLISTA_REGULAR (0x35) -> SMS 0x58
        val traps = ByteArray(64 * UnitTable.TRAP_SIZE)
        traps[3] = UnitTable.BALLISTA_REGULAR.toByte()
        assertEquals(0x58, table.getUnitSMSId(1, traps))
        assertEquals(0x58, ctx.handles.getUnitSMSId(1, traps))

        // Trap 0: extra = ITEM_BALLISTA_LONG (0x36) -> SMS 0x59
        traps[3] = UnitTable.BALLISTA_LONG.toByte()
        assertEquals(0x59, table.getUnitSMSId(1, traps))
        assertEquals(0x59, ctx.handles.getUnitSMSId(1, traps))

        // Trap 0: extra = ITEM_BALLISTA_KILLER (0x37) -> SMS 0x5A
        traps[3] = UnitTable.BALLISTA_KILLER.toByte()
        assertEquals(0x5A, table.getUnitSMSId(1, traps))
        assertEquals(0x5A, ctx.handles.getUnitSMSId(1, traps))

        // Other item -> SMS 0
        traps[3] = 0x01
        assertEquals(0, table.getUnitSMSId(1, traps))
        assertEquals(0, ctx.handles.getUnitSMSId(1, traps))
    }

    @Test
    @VerifiesPort("GetUnitCurrentHp")
    fun `GetUnitCurrentHp returns current HP on capture frames`() {
        val ctx4274 = loadContext("r04274")
        val table = ctx4274.table
        val loader = ctx4274.loader

        // In r04274, Seth has curHP = 7, maxHP = 16
        val sethOffset = table.unitOffset(2)
        assertEquals(7, loader.getUnitCurrentHp(sethOffset))
        assertEquals(7, loader.getUnitCurrentHpById(2))
        assertEquals(16, loader.getUnitMaxHp(sethOffset))

        // Eirika has curHP = 13, maxHP = 30
        val eirikaOffset = table.unitOffset(1)
        assertEquals(13, loader.getUnitCurrentHp(eirikaOffset))
        assertEquals(13, loader.getUnitCurrentHpById(1))
        assertEquals(30, loader.getUnitMaxHp(eirikaOffset))

        // O'Neill has curHP = 23, maxHP = 23
        val bossOffset = table.unitOffset(0x81)
        assertEquals(23, loader.getUnitCurrentHp(bossOffset))
        assertEquals(23, loader.getUnitCurrentHpById(0x81))
        assertEquals(23, loader.getUnitMaxHp(bossOffset))

        // Test at r04894 (clear sequence)
        val ctx4894 = loadContext("r04894")
        assertEquals(7, ctx4894.loader.getUnitCurrentHpById(2))
        assertEquals(13, ctx4894.loader.getUnitCurrentHpById(1))
    }

    @Test
    fun `GetUnitCurrentHp clamps curHp when exceeding maxHp synthetic test`() {
        val ctx = loadContext("r04274")
        val table = ctx.table
        val loader = ctx.loader
        val sethOffset = table.unitOffset(2)

        // Set Seth's curHP above maxHP (16)
        table.bytes[sethOffset + 0x13] = 25.toByte()
        assertEquals(25, table.currentHp(2))
        // Calling getUnitCurrentHp must clamp curHP down to maxHP (16) and mutate the struct
        val clamped = loader.getUnitCurrentHp(sethOffset)
        assertEquals(16, clamped)
        assertEquals(16, table.currentHp(2))
    }

    @Test
    @VerifiesPort("GetUnitLeaderCharId")
    fun `GetUnitLeaderCharId returns 0 for player units and supports 6 for non-player units`() {
        val ctx = loadContext("r04274")
        val table = ctx.table

        // Blue unit 1 (Eirika, id 1, index 1): index & 0xC0 == 0 -> returns 0
        assertEquals(0, table.getUnitLeaderCharId(1))

        // Blue unit 2 (Seth, id 2): supports[6] has 0x14, but index & 0xC0 == 0 -> returns 0
        assertEquals(0x14, table.leaderCharId(2))
        assertEquals(0, table.getUnitLeaderCharId(2))

        // Red unit 1 (O'Neill, id 0x81): index & 0xC0 == 0x80 -> returns supports[6] (0)
        assertEquals(0, table.getUnitLeaderCharId(0x81))

        // Simulate an enemy with a leader (e.g. boss charId 0x14)
        val bossOffset = table.unitOffset(0x81)
        table.bytes[bossOffset + UnitTable.OFF_LEADER] = 0x14.toByte()
        assertEquals(0x14, table.getUnitLeaderCharId(0x81))

        // Blue unit with leader in supports[6] still returns 0
        val sethOffset = table.unitOffset(2)
        table.bytes[sethOffset + UnitTable.OFF_LEADER] = 0x14.toByte()
        assertEquals(0, table.getUnitLeaderCharId(2))

        // Check r04894
        val ctx4894 = loadContext("r04894")
        assertEquals(0, ctx4894.table.getUnitLeaderCharId(1))
        assertEquals(0, ctx4894.table.getUnitLeaderCharId(2))
    }

    @Test
    @VerifiesPort("GetUnitKeyItemSlotForTerrain")
    fun `GetUnitKeyItemSlotForTerrain returns empty slot or missing key on r04181`() {
        val ctx = loadContext("r04181")
        val table = ctx.table

        // Seth at frame 4181 has 2 items (Silver Lance, Steel Sword), slots 2..4 empty:
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_CHEST_FULL))
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_DOOR))
        // Terrain 0 -> returns first empty slot (slot 2)
        assertEquals(2, table.getUnitKeyItemSlotForTerrain(2, 0))

        // Eirika at frame 4181 has 3 items (slots 0..2), slots 3..4 empty:
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(1, UnitTable.TERRAIN_CHEST_FULL))
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(1, UnitTable.TERRAIN_DOOR))
        assertEquals(3, table.getUnitKeyItemSlotForTerrain(1, 0))
    }

    @Test
    fun `GetUnitKeyItemSlotForTerrain door and chest keys and lockpick synthetic test`() {
        val ctx = loadContext("r04181")
        val table = ctx.table
        val sethOffset = table.unitOffset(2)

        // Put ITEM_DOORKEY (0x6A) in slot 3
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 6] = UnitTable.ITEM_DOORKEY.toByte()
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 7] = 0x01 // 1 use
        assertEquals(3, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_DOOR))
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_CHEST_FULL))

        // Put ITEM_CHESTKEY_BUNDLE (0x79) in slot 4 (no regular chest key)
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 8] = UnitTable.ITEM_CHESTKEY_BUNDLE.toByte()
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 9] = 0x05 // 5 uses
        assertEquals(4, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_CHEST_FULL))

        // Now put ITEM_CHESTKEY (0x69) in slot 3 -> regular chest key takes priority
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 6] = UnitTable.ITEM_CHESTKEY.toByte()
        table.bytes[sethOffset + UnitTable.OFF_ITEMS + 7] = 0x01
        assertEquals(3, table.getUnitKeyItemSlotForTerrain(2, UnitTable.TERRAIN_CHEST_FULL))

        // Thief test: create a unit with CA_THIEF and a lockpick
        val unitCopy = table.bytes.copyOfRange(sethOffset, sethOffset + UnitTable.UNIT_SIZE)
        // Add ITEM_LOCKPICK (0x6B) in slot 1
        unitCopy[UnitTable.OFF_ITEMS + 2] = UnitTable.ITEM_LOCKPICK.toByte()
        unitCopy[UnitTable.OFF_ITEMS + 3] = 0x0F

        // Without CA_THIEF, slot for door is not lockpick:
        assertEquals(-1, table.getUnitKeyItemSlotForTerrain(unitCopy, 0, UnitTable.TERRAIN_DOOR))

        // With CA_THIEF in class attributes, lockpick in slot 1 is used for ANY terrain
        val thiefClassPtr = 0x08807164 + (0x0D - 1) * 0x54
        put32(unitCopy, UnitTable.OFF_CLASS, thiefClassPtr)
        assertEquals(1, table.getUnitKeyItemSlotForTerrain(unitCopy, 0, UnitTable.TERRAIN_DOOR))
        assertEquals(
            1,
            table.getUnitKeyItemSlotForTerrain(unitCopy, 0, UnitTable.TERRAIN_CHEST_FULL)
        )
        assertEquals(1, table.getUnitKeyItemSlotForTerrain(unitCopy, 0, 0))
    }

    @Test
    @VerifiesPort("SetUnitStatus")
    fun `SetUnitStatus clears status to 0 on blue units in r04894`() {
        val ctx4894 = loadContext("r04894")
        val table = ctx4894.table
        val offset = table.unitOffset(1)

        // In r04894, blue unit status is 0
        assertEquals(0, table.statusIndex(1))
        assertEquals(0, table.bytes[offset + UnitTable.OFF_STATUS].toInt() and 0xFF)

        // Calling setUnitStatus(1, 0) explicitly clears status and duration to 0
        table.setUnitStatus(1, 0)
        assertEquals(0, table.bytes[offset + UnitTable.OFF_STATUS].toInt() and 0xFF)
        assertEquals(0, table.statusIndex(1))

        // Compare byte at 0x30 against raw r04894 EWRAM ground truth
        val blue1Offset = UnitTable.BLUE_ADDRESS - UnitTable.EWRAM_BASE
        assertEquals(
            ctx4894.ewram[blue1Offset + UnitTable.OFF_STATUS],
            table.bytes[offset + UnitTable.OFF_STATUS],
            "status byte matches r04894 ground truth"
        )
    }

    @Test
    fun `SetUnitStatus non-zero duration and SetUnitStatusExt synthetic test`() {
        val ctx = loadContext("r04274")
        val table = ctx.table
        val offset = table.unitOffset(1)

        // Setting sleep status on Eirika sets statusIndex = 2 and statusDuration = 5
        table.setUnitStatus(1, UnitTable.STATUS_SLEEP)
        val expectedByte = (5 shl 4) or UnitTable.STATUS_SLEEP
        assertEquals(expectedByte, table.bytes[offset + UnitTable.OFF_STATUS].toInt() and 0xFF)
        assertEquals(UnitTable.STATUS_SLEEP, table.statusIndex(1))

        // SetUnitStatusExt with berserk (4) and duration 3
        table.setUnitStatusExt(1, UnitTable.STATUS_BERSERK, 3)
        val expectedExtByte = (3 shl 4) or UnitTable.STATUS_BERSERK
        assertEquals(expectedExtByte, table.bytes[offset + UnitTable.OFF_STATUS].toInt() and 0xFF)
        assertEquals(UnitTable.STATUS_BERSERK, table.statusIndex(1))

        // Clear again
        table.setUnitStatus(1, 0)
        assertEquals(0, table.statusIndex(1))
    }

    @Test
    @VerifiesPort("RefreshAllies")
    fun `RefreshAllies preserves states and matches r04901 ground truth`() {
        val ctx4894 = loadContext("r04894")
        val ctx4901 = loadContext("r04901")

        var mapRefreshed = false
        var spritesRefreshed = false

        ctx4894.table.refreshAllies(
            refreshEntityBmMaps = { mapRefreshed = true },
            refreshUnitSprites = { spritesRefreshed = true },
        )

        assertTrue(mapRefreshed, "refreshEntityBmMaps was called")
        assertTrue(spritesRefreshed, "refreshUnitSprites was called")

        // In r04894, Eirika (unit 1) has US_HIDDEN (state = 1) and Seth (unit 2) has state = 0
        assertEquals(1, ctx4894.table.state(1), "Eirika US_HIDDEN preserved")
        assertEquals(0, ctx4894.table.state(2), "Seth state 0")
        assertEquals(0, ctx4894.table.rescue(1), "Eirika rescue 0")
        assertEquals(0, ctx4894.table.rescue(2), "Seth rescue 0")
        assertEquals(0, ctx4894.table.statusIndex(1), "Eirika status 0")
        assertEquals(0, ctx4894.table.statusIndex(2), "Seth status 0")

        // Compare blue unit memory matches r04901 ground truth after RefreshAllies!
        val blueOffset = UnitTable.BLUE_ADDRESS - UnitTable.EWRAM_BASE
        val blueLen = UnitTable.BLUE_COUNT * UnitTable.UNIT_SIZE
        assertArrayEquals(
            ctx4901.ewram.copyOfRange(blueOffset, blueOffset + blueLen),
            ctx4894.table.bytes.copyOfRange(0, blueLen),
            "blue unit array matches r04901 ground truth after RefreshAllies"
        )
    }

    @Test
    fun `RefreshAllies clears unselectable, rescue and status synthetic test`() {
        val ctx = loadContext("r04274")
        val table = ctx.table

        // Set states on blue units as if mid-chapter / end-turn
        table.setState(1, table.state(1) or UnitTable.US_UNSELECTABLE or UnitTable.US_RESCUING)
        table.setRescue(1, 2)
        table.setUnitStatus(1, UnitTable.STATUS_SLEEP)

        table.setState(2, table.state(2) or UnitTable.US_UNSELECTABLE or UnitTable.US_RESCUED)
        table.setRescue(2, 1)
        table.setUnitStatus(2, UnitTable.STATUS_BERSERK)

        table.refreshAllies()

        // After RefreshAllies, blue units must have unselectable, rescuing and rescued cleared
        val clearMask = UnitTable.US_UNSELECTABLE or UnitTable.US_RESCUING or UnitTable.US_RESCUED
        for (id in 1..2) {
            val s = table.state(id)
            assertEquals(0, s and clearMask, "unit $id state mask")
            assertEquals(0, table.rescue(id), "unit $id rescue")
            assertEquals(0, table.statusIndex(id), "unit $id status")
        }
    }
}
