package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.ANIM_BIT2_COMMAND
import gbafe.anim.ANIM_BIT2_FRAME
import gbafe.anim.Anim
import gbafe.anim.AnimPool
import gbafe.anim.ScriptReader
import gbafe.anim.updateAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `AnimInterpret` (FRAME, STOP, COMMAND arms) and the `AnimUpdateAll` timer loop against the
 * pre-battle waiting loop of `ram_trace_prologue_attack`: the four anims of the pool are loaded
 * from the f1498 dump, then one `updateAll` per step is compared with the next dump (f1499..
 * f1536): `pScrCurrent`, `timer`, `state2`, `state3`, `commandQueueSize` and the queue bytes.
 *
 * Script words come from the same dump (RAM scripts) or from the ROM at runtime (no bytes
 * embedded). `BattleAIS_ExecCommands` runs after the update in the same step; only its
 * pre-battle `ANIM_CMD_WAIT_01` arm is reached here and is modelled by [drain], a test stand-in
 * (not a port): pop the queue, `pScrCurrent = pScrStart`. The display hook is a no-op.
 */
class AnimInterpretDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun addr(i: Int) = POOL + STRIDE * i

    /** Test stand-in for the pre-battle `ANIM_CMD_WAIT_01` arm of `BattleAIS_ExecCommands`. */
    private fun drain(a: Anim) {
        val type = a.state2 and CMD_MASK
        if (type and ANIM_BIT2_COMMAND != 0) {
            while (a.commandQueueSize > 0) {
                val id = a.commandQueue[a.commandQueueSize - 1]
                check(id == 1) { "unexpected queued command $id" }
                a.pScrCurrent = a.pScrStart
                a.commandQueueSize = 0
            }
            a.state2 = a.state2 and (ANIM_BIT2_COMMAND or LOW_MASK).inv()
        }
        // The FRAME arm only registers sheet graphics (not modelled) and clears its bits.
        if (type and ANIM_BIT2_FRAME != 0) {
            a.state2 = a.state2 and (ANIM_BIT2_FRAME or LOW_MASK).inv()
        }
    }

    @Test
    @VerifiesPort("AnimInterpret", "AnimUpdateAll")
    fun `waiting loop f1499 to f1536`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = romFile.readBytes()
        val capture = ref.run("ram_trace_prologue_attack")
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        fun at(s: Int) = capture.region(byStep.getValue(s), "ewram")

        var ram = at(START)
        val reader = ScriptReader { a ->
            if (a >= ROM_BASE) {
                val o = a - ROM_BASE
                (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
                    ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)
            } else {
                u32(ram, a)
            }
        }

        val pool = AnimPool()
        val anims = List(4) { i ->
            val p = addr(i)
            pool.slots[i].also {
                it.state = u16(ram, p)
                it.timer = u16(ram, p + 0x06).toShort().toInt()
                it.drawLayerPriority = u16(ram, p + 0x0A)
                it.state2 = u16(ram, p + 0x0C)
                it.state3 = u16(ram, p + 0x10)
                it.commandQueueSize = u8(ram, p + 0x14)
                it.pScrCurrent = u32(ram, p + 0x20)
                it.pScrStart = u32(ram, p + 0x24)
                it.pSpriteDataPool = u32(ram, p + 0x30)
            }
        }
        // Link the list in the dump's order (pPrev/pNext are pool addresses).
        fun slot(a: Int) = if (a == 0) null else pool.slots[(a - POOL) / STRIDE]
        for (i in 0 until 4) {
            anims[i].pPrev = slot(u32(ram, addr(i) + 0x34))
            anims[i].pNext = slot(u32(ram, addr(i) + 0x38))
        }
        pool.first = slot(u32(ram, FIRST_ANIM))

        for (step in START + 1..END) {
            // Scripts of the previous state are what the step read.
            pool.updateAll(reader)
            // Wait ids hold pScrCurrent on their own word (`pScrCurrent--`) before the drain
            // runs; BA-i3 shows the same word at the held pointer in the dump.
            for (a in anims.filter { it.commandQueueSize > 0 }) {
                val word = reader.word(a.pScrCurrent) and 0x3F0000FF
                assertEquals(0x05000000 or a.commandQueue[0], word, "f$step held command word")
            }
            anims.forEach(::drain)
            ram = at(step)
            for (i in 0 until 4) {
                val p = addr(i)
                val a = anims[i]
                val tag = "f$step anim $i"
                assertEquals(u32(ram, p + 0x20), a.pScrCurrent, "$tag pScrCurrent")
                assertEquals(u16(ram, p + 0x06).toShort().toInt(), a.timer, "$tag timer")
                assertEquals(u16(ram, p + 0x0C), a.state2, "$tag state2")
                assertEquals(u16(ram, p + 0x10), a.state3, "$tag state3")
                assertEquals(u8(ram, p + 0x14), a.commandQueueSize, "$tag queue size")
                for (q in 0 until Anim.COMMAND_QUEUE_CAPACITY) {
                    if (a.commandQueue[q] != 0 || q == 0) {
                        assertEquals(u8(ram, p + 0x15 + q), a.commandQueue[q], "$tag queue[$q]")
                    }
                }
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val ROM_BASE = 0x08000000
        const val POOL = 0x02028F78
        const val STRIDE = 0x48
        const val FIRST_ANIM = 0x02029D88
        /** `ANIM_BIT2_CMD_MASK` and the 0x08FF bits the exec loop clears with COMMAND/FRAME. */
        const val CMD_MASK = 0xF000
        const val LOW_MASK = 0x0800 or 0x00FF
        const val START = 1498
        const val END = 1536
    }
}
