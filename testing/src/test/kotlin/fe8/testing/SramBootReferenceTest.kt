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
        "LoadBonusContentData", "EraseBonusContentData",
        "LoadAndVerfyRankData", "EraseSaveRankData",
        "LoadAndVerifySoundRoomData", "EraseSoundRoomSaveData",
        "LoadAndVerfyLinkArenaStruct2", "EraseLinkArenaStruct2", "Checksum16",
    )
    fun `boot sequence on blank sram reproduces the settled sram_boot dumps`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("sram_boot")
        assertTrue(run.hasSram, "sram_boot has no SRAM dumps")
        val names = run.dumps.keys.filter { File(ref.root, "sram_boot/$it.sram.bin").isFile }
        val settled = names.indexOfFirst { it == "s00060" }
        assertTrue(settled >= 0, "sram_boot has no s00060 dump")

        val sram = CartSram()
        BmSaveLib(sram).boot()
        val image = ByteArray(L.SRAM_SIZE)
        sram.copyOut(image)

        assertArrayEquals(run.sram(names[settled]), image, "image after boot vs s00060")
        // Later dumps: equal except where the game has since changed the sound room block
        // (UnlockSoundRoomSong, run by the scripted input long after boot). That block is
        // checked by the sound room test below, against dumps that hold real data.
        for (name in names.drop(settled + 1)) {
            val dump = run.sram(name)
            assertEquals(L.SRAM_SIZE, dump.size)
            for (i in dump.indices) {
                if (i in L.SOUND_ROOM_OFFSET until L.SOUND_ROOM_OFFSET + L.SOUND_ROOM_SIZE) continue
                assertEquals(image[i], dump[i], "$name byte 0x${i.toString(16)}")
            }
        }
    }

    private fun dump(run: String, name: String): ByteArray {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        assertTrue(r.hasSram, "$run has no SRAM dumps")
        return r.sram(name)
    }

    private fun loaded(image: ByteArray) =
        BmSaveLib(CartSram().also { it.copyIn(image) }).also { it.sramInit() }

    private fun soundRoom(image: ByteArray) =
        image.copyOfRange(L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_OFFSET + L.SOUND_ROOM_SIZE)

    @Test
    @VerifiesPort(
        "WriteSoundRoomSaveData", "LoadAndVerifySoundRoomData", "Checksum16",
        "WriteSramFast", "ReadSramFast_Core", "WriteAndVerifySramFast",
    )
    fun `sound room data of the dumps is written and verified byte for byte`() {
        // Non-trivial blocks the game wrote with UnlockSoundRoomSong: 0x06 / 0x0E at f1128 / f1214,
        // 0x1E.. with checksum 0x04CC at first_phase f19300 and prologue f19800.
        val cases = listOf(
            "ram_trace_newgame" to "s01128", "ram_trace_newgame" to "s01214",
            "ram_trace_first_phase" to "s19300", "ram_trace_prologue" to "s19800",
        )
        var maxChecksum = 0
        for ((run, name) in cases) {
            val d = dump(run, name)
            val block = soundRoom(d)
            maxChecksum = maxOf(maxChecksum, block[L.SOUND_ROOM_CHECKSUM].toInt() and 0xFF)

            // Write: blank-boot SRAM + the dump's data bytes -> the dump's block, checksum
            // included (WriteSoundRoomSaveData computes it; the buffer's checksum bytes are 0).
            val sram = CartSram()
            val lib = BmSaveLib(sram).also { it.boot() }
            val before = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
            val buf = block.copyOf()
            buf[L.SOUND_ROOM_CHECKSUM] = 0
            buf[L.SOUND_ROOM_CHECKSUM + 1] = 0
            lib.writeSoundRoomSaveData(buf)
            val after = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
            assertArrayEquals(block, soundRoom(after), "$run $name written block")
            for (i in before.indices) {
                if (i in L.SOUND_ROOM_OFFSET until L.SOUND_ROOM_OFFSET + L.SOUND_ROOM_SIZE) continue
                assertEquals(before[i], after[i], "$run $name byte 0x${i.toString(16)}")
            }

            // Read: the dump's block verifies and is returned unchanged; a flipped data byte or
            // checksum byte fails.
            val got = ByteArray(L.SOUND_ROOM_SIZE)
            assertTrue(loaded(d).loadAndVerifySoundRoomData(got), "$run $name verify")
            assertArrayEquals(block, got, "$run $name read block")
            for (flip in intArrayOf(0, L.SOUND_ROOM_CHECKSUM)) {
                val bad = d.copyOf()
                bad[L.SOUND_ROOM_OFFSET + flip] =
                    (bad[L.SOUND_ROOM_OFFSET + flip].toInt() xor 1).toByte()
                assertFalse(loaded(bad).loadAndVerifySoundRoomData(), "$run $name flip +$flip")
            }
        }
        assertTrue(maxChecksum != 0, "no dump with a non-zero sound room checksum")
    }

    @Test
    @VerifiesPort("IsValidSuspendSave", "GetLastSuspendSaveId", "GetNextSuspendSaveId")
    fun `suspend slot selection follows the dumps header and block infos`() {
        val run = "ram_trace_first_phase"
        // s19300: no suspend save, last_suspend_slot 0. s19316: the game has written one into
        // alternate entry 4 and set last_suspend_slot to 1 (header byte 0x63).
        val d0 = dump(run, "s19300")
        assertEquals(0, d0[L.HEADER_LAST_SUSPEND_SLOT].toInt())
        val none = loaded(d0)
        assertEquals(0, none.getLastSuspendSaveId())
        assertEquals(1, none.getNextSuspendSaveId())
        assertFalse(none.isValidSuspendSave(L.SAVE_ID_SUSPEND))
        assertEquals(0x7F, none.suspendSaveIdOffset)

        val d1 = dump(run, "s19316")
        assertEquals(1, d1[L.HEADER_LAST_SUSPEND_SLOT].toInt())
        val lib = loaded(d1)
        assertEquals(1, lib.getLastSuspendSaveId())
        assertEquals(0, lib.getNextSuspendSaveId())
        assertTrue(lib.isValidSuspendSave(L.SAVE_ID_SUSPEND))
        assertEquals(1, lib.suspendSaveIdOffset)
        assertFalse(lib.isValidSuspendSave(L.SAVE_ID_GAME0))
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
