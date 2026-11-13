package gba.testkit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read access to the mGBA reference data produced by `mgba-harness` (in the ROM repo; the harness
 * itself is not checked here, only docs/gba-harness-reuse.md) and unpacked by
 * `tools/setup.sh` (sessions) or the CI workflow into `$FE8_ASSETS/reference/out/<script>/`.
 *
 * Each script directory holds:
 * - `manifest.txt`: `<dump_name> <frame_number>` lines
 * - `inputs.bin`: one little-endian uint16 GBA key mask per frame
 * - Per-dump snapshots: `<dump>.{ewram,iwram,vram,pal,oam,io}.bin` and `<dump>.frame.rgb`
 * - Runs may also hold `states.txt` (savestate/loadstate timeline; dump frames are monotonic emulated steps)
 *   and `frames.rgb` + `frames.txt` (screen every N steps); see the ROM repo's `harness/harness.c`.
 *
 * This is test-only infrastructure: GBA addresses appear here (to locate the original game's state in
 * the dumps) and must never leak into the port itself.
 */
public class ReferenceData(
    /** Directory holding one sub-directory per reference run (`reference/out` of the ROM assets). */
    public val root: File,
) {
    /** True if the reference data is present (tests skip — not pass — when it is not). */
    public val available: Boolean get() = File(root, "boot_to_title/manifest.txt").isFile

    /** One reference run recorded by `mgba-harness`. */
    public inner class Run(
        /** Run directory name, e.g. `boot_to_title`. */
        public val name: String,
    ) {
        private val dir = File(root, name)

        /** Dump name → frame number, in file order from `manifest.txt`. */
        public val dumps: LinkedHashMap<String, Int> by lazy {
            val manifestMap = LinkedHashMap<String, Int>()
            File(dir, "manifest.txt").forEachLine { line ->
                val (dumpName, frameNumber) = line.trim().split(' ')
                manifestMap[dumpName] = frameNumber.toInt()
            }
            manifestMap
        }

        /** GBA key masks fed to mGBA, one per emulated step (index = step - 1: not checked). */
        public val inputs: IntArray by lazy {
            val buffer = ByteBuffer.wrap(File(dir, "inputs.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(buffer.remaining() / GBA_KEY_INPUT_BYTES) { buffer.short.toInt() and 0xFFFF }
        }

        /** Raw bytes of one region of a dump: `<dump>.<region>.bin`, e.g. `region = "iwram"`. */
        public fun region(dump: String, region: String): ByteArray = File(dir, "$dump.$region.bin").readBytes()

        /** RGB888 frame of a full dump (240x160x3 bytes; top 5 bits per channel valid). */
        public fun frameRgb(dump: String): ByteArray = File(dir, "$dump.frame.rgb").readBytes()

        /** True if the run holds any `*.sram.bin` dump (harness `dumpsram NAME`). */
        public val hasSram: Boolean
            get() = dir.list()?.any { it.endsWith(".sram.bin") } == true

        /** The save RAM dumped as [dump] (`<dump>.sram.bin`, [GBA_SRAM_SIZE] bytes). */
        public fun sram(dump: String): ByteArray = File(dir, "$dump.sram.bin").readBytes()

        /** Watch specifications parsed from `watches.txt`: `<name> <hex_address> <length> <first_step>`. */
        private val watchSpecs: Map<String, List<String>> by lazy {
            val specFile = File(dir, "watches.txt")
            if (!specFile.isFile) {
                emptyMap()
            } else {
                specFile.readLines()
                    .filter { it.isNotBlank() }
                    .map { it.trim().split(Regex("\\s+")) }
                    .associateBy { it[0] }
            }
        }

        /** True if the run traces a watch called [name] (`watches.txt`). */
        public fun hasWatch(name: String): Boolean = name in watchSpecs

        /** Loads the per-step trace of watch [name] (`watches.txt` + `<name>.watch.bin`). */
        public fun watch(name: String): WatchTrace {
            val spec = requireNotNull(watchSpecs[name]) { "no watch $name in run ${this.name}" }
            val address = spec[1].toLong(16).toInt()
            val length = spec[2].toInt()
            val firstStep = spec[3].toInt()
            val watchBytes = File(dir, "$name.watch.bin").readBytes()
            return WatchTrace(name, address, length, firstStep, watchBytes)
        }

        /** True when the run was made with `--audio`. */
        public val hasAudio: Boolean get() = File(dir, "audio.s16").isFile

        /**
         * mGBA's mixed output (`--audio`): interleaved L/R signed 16-bit PCM at [AUDIO_RATE] Hz.
         * The format is the harness's; not checked here.
         */
        public val audio: ShortArray by lazy {
            val buffer = ByteBuffer.wrap(File(dir, "audio.s16").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(buffer.remaining() / 2) { buffer.short }
        }

        /** Stereo samples written by the end of step `s`: `audioEnd[s]` (`audioEnd[0]` = 0). */
        public val audioEnd: IntArray by lazy {
            val lines = File(dir, "audio.txt").readLines().filter { it.isNotBlank() }
            val end = IntArray(lines.size + 1)
            for (line in lines) {
                val (step, sampleCount) = line.trim().split(' ')
                end[step.toInt()] = sampleCount.toInt()
            }
            end
        }

        /** True when the run was made with `--psg-writes`. */
        public val hasPsgWrites: Boolean get() = File(dir, "psg_writes.txt").isFile

        /**
         * Sound register writes (`--psg-writes`) in emulation order:
         * columns are `step`, `cycleInStep`, unused, `ioOffsetHex`, `valueHex`, `widthBytes`.
         */
        public val psgWrites: List<PsgWrite> by lazy {
            File(dir, "psg_writes.txt").readLines().filter { it.isNotBlank() }.map { line ->
                val fields = line.trim().split(' ')
                val step = fields[0].toInt()
                val cycleInStep = fields[1].toLong()
                // fields[2] is unused log metadata
                val ioOffset = fields[3].toInt(16)
                val value = fields[4].toLong(16)
                val width = fields[5].toInt()
                PsgWrite(step, cycleInStep, ioOffset, value, width)
            }
        }
    }

    /**
     * One write to the sound registers in mGBA (`--psg-writes`): during [step], [cycleInStep] CPU
     * cycles after the step started, [width] bytes of [value] at IO offset [offset]. That steps
     * are 1-based and start at VBlank, and that offsets are `0x60..0xAF`, come from the harness
     * and are not checked here.
     */
    public data class PsgWrite(
        /** Step being run (1-based). */
        val step: Int,
        /** CPU cycles since the step started. */
        val cycleInStep: Long,
        /** IO register offset from `0x04000000`. */
        val offset: Int,
        /** Value written. */
        val value: Long,
        /** Access width in bytes (1, 2 or 4). */
        val width: Int,
    )

    /** Opens the run named [name] (a script stem, e.g. `ram_trace_boot`). */
    public fun run(name: String): Run = Run(name)

    /** Factory for the reference data configured by Gradle. */
    public companion object {
        /** GBA horizontal screen resolution in pixels. */
        public const val GBA_SCREEN_WIDTH: Int = 240

        /** GBA vertical screen resolution in scanlines. */
        public const val GBA_SCREEN_HEIGHT: Int = 160

        /** Bytes per RGB24 pixel. */
        public const val BYTES_PER_RGB24: Int = 3

        /** Bytes per little-endian 16-bit key input mask. */
        public const val GBA_KEY_INPUT_BYTES: Int = 2

        /** Size of battery-backed GBA static RAM in bytes. */
        public const val GBA_SRAM_SIZE: Int = 32768

        /** Sample rate of `audio.s16` in Hz (the harness's `AUDIO_RATE`; not checked here). */
        public const val AUDIO_RATE: Int = 32768

        /** System property key for assets directory. */
        public const val ASSETS_PROPERTY: String = "fe8.assets"

        /** Environment variable key for assets directory. */
        public const val ASSETS_ENV: String = "FE8_ASSETS"

        /** Default relative path to assets directory if neither property nor env is set. */
        public const val DEFAULT_ASSETS_DIR: String = ".assets"

        /**
         * Reference data from the `fe8.assets` system property / `FE8_ASSETS` env (set by Gradle);
         * those names are the host build's convention.
         */
        public fun fromEnvironment(): ReferenceData {
            val base = System.getProperty(ASSETS_PROPERTY) ?: System.getenv(ASSETS_ENV) ?: DEFAULT_ASSETS_DIR
            return ReferenceData(File(base, "reference/out"))
        }
    }
}

/** Little-endian readers over dump bytes, addressed by GBA bus address. */
public object GbaMem {
    /** Base address of IWRAM dumps (Internal Working RAM, 32KB on-chip fast memory). */
    public const val IWRAM: Int = 0x03000000

    /** Base address of EWRAM dumps (External Working RAM, 256KB on-board memory). */
    public const val EWRAM: Int = 0x02000000

    /** Reads an unsigned 16-bit little-endian value at bus [address] from a dump starting at [base]. */
    public fun u16(dump: ByteArray, base: Int, address: Int): Int {
        val offset = address - base
        return (dump[offset].toInt() and 0xFF) or ((dump[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads a 32-bit little-endian value at bus [address] from a dump starting at [base]. */
    public fun u32(dump: ByteArray, base: Int, address: Int): Int =
        u16(dump, base, address) or (u16(dump, base, address + 2) shl 16)
}
