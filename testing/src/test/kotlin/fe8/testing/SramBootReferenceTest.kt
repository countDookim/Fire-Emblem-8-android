package fe8.testing

import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Runs the SRAM part of `AgbMain` on a blank [CartSram] and compares it with mGBA's dumps. */
class SramBootReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "SramInit", "IsSramWorking", "WipeSram", "ReadGlobalSaveInfo", "WriteGlobalSaveInfo",
        "InitGlobalSaveInfodata", "EraseSramDataIfInvalid", "StringCompare",
        "LoadBonusContentData", "SaveBonusContentData", "EraseBonusContentData",
        "LoadAndVerfyRankData", "SaveRankings", "EraseSaveRankData",
        "LoadAndVerifySoundRoomData", "WriteSoundRoomSaveData", "EraseSoundRoomSaveData",
        "LoadAndVerfyLinkArenaStruct2", "WriteLinkArenaStruct2", "EraseLinkArenaStruct2",
        "IsValidSuspendSave", "GetLastSuspendSaveId", "GetNextSuspendSaveId", "Checksum16",
        "WriteSramFast", "ReadSramFast_Core", "VerifySramFast_Core", "WriteAndVerifySramFast",
    )
    fun `boot sequence on blank sram reproduces the settled sram_boot dumps`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("sram_boot")
        assumeTrue(run.hasSram, "sram_boot has no SRAM dumps")
        val names = run.dumps.keys.filter { File(ref.root, "sram_boot/$it.sram.bin").isFile }
        val settled = names.indexOfFirst { it == "s00060" }
        assumeTrue(settled >= 0, "sram_boot has no s00060 dump")

        val sram = CartSram()
        BmSaveLib(sram).boot()
        val image = ByteArray(L.SRAM_SIZE)
        sram.copyOut(image)

        assertArrayEquals(run.sram(names[settled]), image, "image after boot vs s00060")
        // Later dumps: equal except where the game has since changed the sound room block
        // (UnlockSoundRoomSong, run by the scripted input long after boot).
        for (name in names.drop(settled + 1)) {
            val dump = run.sram(name)
            assertEquals(L.SRAM_SIZE, dump.size)
            for (i in dump.indices) {
                if (i in L.SOUND_ROOM_OFFSET until L.SOUND_ROOM_OFFSET + L.SOUND_ROOM_SIZE) continue
                assertEquals(image[i], dump[i], "$name byte 0x${i.toString(16)}")
            }
        }
    }

    @Test
    fun `second boot keeps a valid image and only rewrites the probe bytes`() {
        val sram = CartSram()
        val lib = BmSaveLib(sram)
        lib.boot()
        val first = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        assertTrue(lib.readGlobalSaveInfo())
        BmSaveLib(sram).boot()
        val second = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        for (i in first.indices) {
            if (i in L.RESERVED_OFFSET until L.RESERVED_OFFSET + L.RESERVED_SIZE) continue
            assertEquals(first[i], second[i], "byte 0x${i.toString(16)}")
        }
        assertEquals(0x78, second[L.RESERVED_OFFSET].toInt())
    }

    @Test
    fun `corrupt global info is re-initialised and aux blocks are checked independently`() {
        val sram = CartSram()
        val lib = BmSaveLib(sram)
        lib.boot()
        val expected = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        sram.writeByte(L.HEADER_UNK10, 1)
        assertFalse(lib.readGlobalSaveInfo())
        lib.eraseSramDataIfInvalid()
        assertTrue(lib.readGlobalSaveInfo())
        val after = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        assertArrayEquals(expected, after)
        sram.writeByte(L.BONUS_OFFSET, 1)
        assertFalse(lib.loadBonusContentData())
    }
}
