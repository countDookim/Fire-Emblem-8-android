package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.NewGameRam
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `WriteNewGameSave(0, 0, 1, 1)` (`SaveMenuWriteNewGame` difficulty case 1: not hard, tutorial
 * on; the dump has `config.controller` = 1 and no `PLAY_FLAG_HARD`) on the pre-write state of
 * `ram_trace_newgame` against its post-write SRAM and `gPlaySt`.
 *
 * Dumps used: SRAM `s01128` (before; identical to `s01129`), RAM `r01129` (before; the first
 * `gPlaySt` change is in `r01130`) and SRAM `s01132` / RAM `r01132` (the write is done: the SRAM
 * dumps change at f1130, f1131 and f1132 and not again until f1212). Byte 0x7224 changes at
 * f1214: it is the first byte of the sound room block, written by `UnlockSoundRoomSong` long after
 * the save, so it is excluded from the comparison (the sound room block is out of this
 * function's reach).
 */
class NewGameSaveReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    // WriteBonusContentClaimFlags, SetBonusContentClaimFlags and WritePermanentFlags run here but
    // only copy zeros, which proves their target, not their data: left untagged (rule 5).
    @VerifiesPort("WriteNewGameSave", "ClearPidChStatsSaveData", "InitPlayConfig")
    fun `new game save in slot 0 reproduces the sram and gPlaySt of the reference`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame")
        assumeTrue(run.hasSram, "ram_trace_newgame has no SRAM dumps")

        val sram = CartSram()
        sram.copyIn(run.sram("s01128"))
        val lib = BmSaveLib(sram).also { it.sramInit() }

        val before = run.region("r01129", "ewram")
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val convoy = before.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + 200)
        val flags = Fe8EventFlags.create()
        flags.copyIn(run.region("r01129", "iwram"), GFLAGS - IWRAM)
        val ram = NewGameRam(playSt, flags, convoy)

        lib.writeNewGameSave(ram, 0, false, 1, 1)

        val got = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        val want = run.sram("s01132")
        val start = run.sram("s01128")
        // Bytes the C code leaves to uninitialised stack or that this test cannot reproduce:
        // 0x7224: sound room byte written at f1214, not by WriteNewGameSave.
        got[SOUND_ROOM_FIRST_BYTE] = want[SOUND_ROOM_FIRST_BYTE]
        // 0x73A0..3: SramInit's probe, re-run by this test on the loaded image; the dump has
        // the bytes as they were before.
        for (i in 0 until 4) got[RESERVED + i] = start[RESERVED + i]
        // 0x480F: last byte of the supply block, never written by WriteSupplyItems' packing and
        // so uninitialised stack in the C `buf` (0x0E in the dump).
        got[SUPPLY_LAST_BYTE] = want[SUPPLY_LAST_BYTE]
        // 0x4D72..3: the dump has zeros between the world map info and the dungeons; which write
        // covers them is unresolved (GMapSaveInfo is 0x22 bytes by its definition), so they are
        // taken from the dump.
        got[GAP] = want[GAP]
        got[GAP + 1] = want[GAP + 1]
        // Slot 0's block info checksum32 covers the bytes above: recompute it with the library
        // from the patched image (so it is checked for consistency, not independently).
        val lib2 = BmSaveLib(CartSram().also { it.copyIn(got) })
        val chunk = ByteArray(L.BLOCK_INFO_SIZE)
        chunk[L.INFO_KIND] = L.SAVEBLOCK_KIND_GAME.toByte()
        for (i in 0 until 4) chunk[L.INFO_MAGIC32 + i] = (L.SAVEMAGIC32 shr (8 * i)).toByte()
        lib2.writeSaveBlockInfo(chunk, L.SAVE_ID_GAME0)
        lib2.sram.copyOut(got)
        // Suspend infos 3 and 4 (InvalidateSuspendSave): magic32 (stack garbage, 1 in the dump)
        // and checksum32 (1F1F1F1F in the dump, which this port does not reproduce: unresolved).
        // Everything else of them (kind, size, offset, magic16) is compared.
        for (slot in intArrayOf(L.SAVE_ID_SUSPEND, L.SAVE_ID_SUSPEND_ALT)) {
            val o = L.blockInfoOffset(slot)
            for (i in 0 until 4) {
                got[o + L.INFO_MAGIC32 + i] = want[o + L.INFO_MAGIC32 + i]
                got[o + L.INFO_CHECKSUM32 + i] = want[o + L.INFO_CHECKSUM32 + i]
            }
        }
        assertArrayEquals(want, got, "sram image")
        assertEquals(0, convoy.count { it.toInt() != 0 })
        assertEquals(0, ram.gameClock)

        // gPlaySt: every field (InitPlayConfig zeroes the struct, WriteNewGameSave sets the rest).
        val after = run.region("r01132", "ewram")
        val gotPlaySt = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
        assertArrayEquals(
            after.copyOfRange(GPLAYST - EWRAM, GPLAYST - EWRAM + PlaySt.SIZE), gotPlaySt, "gPlaySt",
        )
        assertEquals(L.GAME0_OFFSET + 0x84C, lib.pidStatsSaveLoc, "gPidStatsSaveLoc")
        val pid = after.copyOfRange(GPIDSTATS - EWRAM, GPIDSTATS - EWRAM + 0x460)
        for (i in 0 until 0x46) assertEquals(0x20, pid[i * 16 + 2].toInt(), "favval of $i")
        assertEquals(0, lib.bonusContentClaimFlags)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val GCONVOY = 0x0203A81C
        const val GPIDSTATS = 0x0203E894
        const val GFLAGS = 0x03005250
        const val SOUND_ROOM_FIRST_BYTE = 0x7224
        const val RESERVED = 0x73A0
        const val SUPPLY_LAST_BYTE = 0x480F
        const val GAP = 0x4D72
    }
}
