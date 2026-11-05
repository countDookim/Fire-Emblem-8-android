package gbafe.save

import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.zip.CRC32

class SaveContainerTest {
    private val sram = ByteArray(SaveFormat.CORE_SIZE) { (it * 31 + 7).toByte() }
    private val modData = byteArrayOf(1, 2, 3, 4, 5)
    private val mod = SaveSection(SaveFormat.modSectionId("example.mod"), 3, modData)

    private fun sample() = SaveContainer.fromSram("fe8", sram, listOf(mod))

    @Test
    fun crc32MatchesJdk() {
        val data = ByteArray(1000) { (it * 13).toByte() }
        val jdk = CRC32().also { it.update(data) }.value.toInt()
        assertEquals(jdk, Crc32.compute(data))
        assertEquals(0xCBF43926.toInt(), Crc32.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun roundTripIsIdentical() {
        val bytes = sample().toByteArray()
        val read = SaveContainer.read(bytes)
        assertEquals("fe8", read.gameId)
        assertArrayEquals(sram, read.core)
        assertArrayEquals(modData, read.section(mod.id)!!.data)
        assertEquals(3, read.section(mod.id)!!.version)
        assertArrayEquals(bytes, read.toByteArray())
    }

    @Test
    fun unknownModSectionSurvivesRewrite() {
        val read = SaveContainer.read(sample().toByteArray())
        val zeroCore = SaveSection(SaveFormat.CORE_SECTION, 1, ByteArray(SaveFormat.CORE_SIZE))
        val rewritten = read.withSection(zeroCore)
        val again = SaveContainer.read(rewritten.toByteArray())
        assertArrayEquals(modData, again.section(mod.id)!!.data)
        assertEquals(3, again.section(mod.id)!!.version)
        assertTrue(again.core!!.all { it == 0.toByte() })
    }

    @Test
    fun migrationAppliedOncePerStep() {
        val registry = MigrationRegistry()
        val calls = ArrayList<Int>()
        registry.declareCurrent(mod.id, 5)
        registry.register(mod.id, 3) { d -> calls.add(3); d + 0x33 }
        registry.register(mod.id, 4) { d -> calls.add(4); d + 0x44 }
        val read = SaveContainer.read(sample().toByteArray(), registry = registry)
        val s = read.section(mod.id)!!
        assertEquals(5, s.version)
        assertArrayEquals(modData + byteArrayOf(0x33, 0x44), s.data)
        assertEquals(listOf(3, 4), calls)
        // An already-current section is not migrated again.
        val again = SaveContainer.read(read.toByteArray(), registry = registry)
        assertEquals(listOf(3, 4), calls)
        assertArrayEquals(s.data, again.section(mod.id)!!.data)
    }

    @Test
    fun missingMigrationIsTyped() {
        val registry = MigrationRegistry().apply { declareCurrent(mod.id, 4) }
        assertThrows<SaveException.MissingMigration> {
            SaveContainer.read(sample().toByteArray(), registry = registry)
        }
    }

    @Test
    fun newerSectionVersionThrowsTypedError() {
        val registry = MigrationRegistry().apply { declareCurrent(mod.id, 2) }
        val e = assertThrows<SaveException.NewerVersion> {
            SaveContainer.read(sample().toByteArray(), registry = registry)
        }
        assertEquals(3, e.found)
        assertEquals(2, e.supported)
    }

    @Test
    fun newerContainerVersionThrowsTypedError() {
        val bytes = sample().toByteArray()
        bytes[4] = (SaveFormat.CURRENT_VERSION + 1).toByte()
        val e = assertThrows<SaveException.NewerVersion> { SaveContainer.read(bytes) }
        assertEquals("container", e.what)
    }

    @Test
    fun corruptDataThrowsTypedError() {
        val good = sample().toByteArray()
        val badMagic = good.copyOf().also { it[0] = 0 }
        assertThrows<SaveException.Corrupt> { SaveContainer.read(badMagic) }
        val badPayload = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows<SaveException.Corrupt> { SaveContainer.read(badPayload) }
        val badHeader = good.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertThrows<SaveException.Corrupt> { SaveContainer.read(badHeader) }
        assertThrows<SaveException.Corrupt> { SaveContainer.read(good, 0, good.size - 1) }
        assertThrows<SaveException.Corrupt> { SaveContainer.read(ByteArray(3)) }
    }

    @Test
    fun readSectionIntoCopiesPayload() {
        val bytes = sample().toByteArray()
        val dst = ByteArray(SaveFormat.CORE_SIZE)
        val n = SaveContainer.readSectionInto(bytes, SaveFormat.CORE_SECTION, dst)
        assertEquals(SaveFormat.CORE_SIZE, n)
        assertArrayEquals(sram, dst)
        assertEquals(-1, SaveContainer.readSectionInto(bytes, "mod:none", dst))
    }

    @Test
    fun writeIntoPreallocatedBufferAllocatesNothing() {
        assumeTrue(AllocationMeter.supported)
        val container = sample()
        val buf = ByteArray(container.encodedSize())
        val bytes = AllocationMeter.measureMin(warmup = 2_000, iterations = 200) {
            container.writeTo(buf)
        }
        assertEquals(0L, bytes)
        assertArrayEquals(container.toByteArray(), buf)
    }
}
