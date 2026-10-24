package fe8.testing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read access to the mGBA reference data produced by `mgba-harness` in the ROM repo and unpacked by
 * `tools/setup.sh` (sessions) or the CI workflow into `$FE8_ASSETS/reference/out/<script>/`.
 *
 * Each script directory holds `manifest.txt` (`<dump> <frame>` lines), `inputs.bin` (one little-endian
 * uint16 GBA key mask per frame) and per-dump files `<dump>.{ewram,iwram,frame.rgb,vram,pal,oam,io}.bin`.
 *
 * This is test-only infrastructure: GBA addresses appear here (to locate the original game's state in
 * the dumps) and must never leak into the port itself.
 */
public class ReferenceData(public val root: File) {
    /** True if the reference data is present (tests skip — not pass — when it is not). */
    public val available: Boolean get() = File(root, "boot_to_title/manifest.txt").isFile

    /** One reference run. */
    public inner class Run(public val name: String) {
        private val dir = File(root, name)

        /** Dump name → frame number, in file order. */
        public val dumps: LinkedHashMap<String, Int> by lazy {
            val m = LinkedHashMap<String, Int>()
            File(dir, "manifest.txt").forEachLine { line ->
                val (n, f) = line.trim().split(' ')
                m[n] = f.toInt()
            }
            m
        }

        /** Per-frame GBA key masks fed to mGBA (index = frame - 1). */
        public val inputs: IntArray by lazy {
            val b = ByteBuffer.wrap(File(dir, "inputs.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(b.remaining() / 2) { b.short.toInt() and 0xFFFF }
        }

        /** Raw bytes of a dump region, e.g. `region = "iwram"`. */
        public fun region(dump: String, region: String): ByteArray = File(dir, "$dump.$region.bin").readBytes()

        /** RGB888 frame of a full dump (240×160×3). */
        public fun frameRgb(dump: String): ByteArray = File(dir, "$dump.frame.rgb").readBytes()
    }

    /** Opens the run named [name] (a script stem, e.g. `ram_trace_boot`). */
    public fun run(name: String): Run = Run(name)

    public companion object {
        /** Reference data from the `fe8.assets` system property / `FE8_ASSETS` env (set by Gradle). */
        public fun fromEnvironment(): ReferenceData {
            val base = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
            return ReferenceData(File(base, "reference/out"))
        }
    }
}

/** Little-endian readers over dump bytes, addressed by GBA bus address. */
public object GbaMem {
    /** Base address of IWRAM dumps. */
    public const val IWRAM: Int = 0x03000000

    /** Base address of EWRAM dumps. */
    public const val EWRAM: Int = 0x02000000

    /** Reads an unsigned 16-bit value at bus [address] from a dump starting at [base]. */
    public fun u16(dump: ByteArray, base: Int, address: Int): Int {
        val o = address - base
        return (dump[o].toInt() and 0xFF) or ((dump[o + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads a 32-bit value at bus [address] from a dump starting at [base]. */
    public fun u32(dump: ByteArray, base: Int, address: Int): Int = u16(dump, base, address) or (u16(dump, base, address + 2) shl 16)
}
