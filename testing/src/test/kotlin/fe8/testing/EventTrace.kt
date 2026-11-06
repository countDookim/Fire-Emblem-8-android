package fe8.testing

import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import java.io.File

/**
 * Decoded snapshots of the FE8 event engine taken from mGBA reference runs: the `StdEventEngine`
 * proc (`struct EventEngineProc`, event.h) plus the event globals and the flag bitfields.
 *
 * Two sources: [prologue] (`ram_trace_prologue`, every emulated step from `procs`, `evglobals`
 * and `flags` watches) and [newGameBm] (`ram_trace_newgame_bm`, one snapshot per dumped frame).
 * Script positions are named through `scripts/index.json` ([ScriptNames]).
 */
class EventTrace private constructor(
    /** Steps (prologue) or frames (newgame_bm) that can be passed to [at], ascending. */
    val steps: List<Int>,
    private val reader: (Int) -> EventSnapshot,
) {
    /** The snapshot after step (or at frame) [step], which must be one of [steps]. */
    fun at(step: Int): EventSnapshot = reader(step)

    /** Snapshots for every step in [steps], computed lazily. */
    fun snapshots(): Sequence<EventSnapshot> = steps.asSequence().map(reader)

    /** Factories and layout constants. */
    companion object {
        /** `ProcScr_StdEventEngine` ROM address. */
        const val STD_EVENT_ENGINE = 0x08591AC0

        /** Address of `gEventCallQueue`, the first byte of the `evglobals` watch. */
        const val GLOBALS_ADDRESS = 0x03000438

        /** Bytes of the event globals block (call queue through active queue). */
        const val GLOBALS_SIZE = 0x178

        /** Address of `gPermanentFlagBits` (0x20 bytes, followed by 0x10 of `gChapterFlagBits`). */
        const val FLAGS_ADDRESS = 0x03005250

        /** Bytes of the `flags` watch: permanent flags then chapter flags. */
        const val FLAGS_SIZE = 0x30

        private const val RNG_WORDS = 3
        private const val EVENT_SLOT_COUNT = 14
        private const val CALL_QUEUE_ENTRIES = 16
        private const val SLOT_QUEUE_WORDS = 30
        private const val ACTIVE_QUEUE_ENTRIES = 8

        /** The per-step trace of `ram_trace_prologue`, or null when the data is absent. */
        fun prologue(
            ref: ReferenceData = ReferenceData.fromEnvironment(),
            runName: String = "ram_trace_prologue",
        ): EventTrace? {
            if (!ref.available) return null
            val run = ref.run(runName)
            if (!run.hasWatch("procs") || !run.hasWatch("evglobals")) return null
            val procs = run.watch("procs")
            val globals = run.watch("evglobals")
            val flags = if (run.hasWatch("flags")) run.watch("flags") else null
            val playSt = if (run.hasWatch("gplayst")) run.watch("gplayst") else null
            val rng = if (run.hasWatch("rng")) run.watch("rng") else null
            val names = ProcArrayDump.romNames()
            val scripts = ScriptNames.load()
            val first = maxOf(procs.firstStep, globals.firstStep, flags?.firstStep ?: 0)
            val last = minOf(procs.lastStep, globals.lastStep, flags?.lastStep ?: Int.MAX_VALUE)
            return EventTrace((first..last).toList()) { step ->
                decode(
                    step,
                    ProcArrayDump.ofWatch(procs, step, names),
                    globals.step(step),
                    flags?.step(step),
                    scripts,
                    playSt?.step(step),
                    rng?.step(step),
                )
            }
        }

        /** The per-frame dumps of `ram_trace_newgame_bm`, or null when the data is absent. */
        fun newGameBm(ref: ReferenceData = ReferenceData.fromEnvironment()): EventTrace? {
            if (!ref.available) return null
            val run = ref.run("ram_trace_newgame_bm")
            val dumps = run.dumps
            if (dumps.isEmpty()) return null
            val byFrame = dumps.entries.associate { it.value to it.key }
            val names = ProcArrayDump.romNames()
            val scripts = ScriptNames.load()
            return EventTrace(byFrame.keys.sorted()) { frame ->
                val dump = requireNotNull(byFrame[frame]) { "no dump for frame $frame" }
                val ewram = run.region(dump, "ewram")
                val iwram = run.region(dump, "iwram")
                val globalsOffset = GLOBALS_ADDRESS - GbaMem.IWRAM
                val flagsOffset = FLAGS_ADDRESS - GbaMem.IWRAM
                decode(
                    frame,
                    ProcArrayDump.ofEwram(ewram, names),
                    iwram.copyOfRange(globalsOffset, globalsOffset + GLOBALS_SIZE),
                    iwram.copyOfRange(flagsOffset, flagsOffset + FLAGS_SIZE),
                    scripts,
                )
            }
        }

        private fun decode(
            step: Int,
            procs: ProcArrayDump,
            g: ByteArray,
            flags: ByteArray?,
            scripts: ScriptNames,
            playSt: ByteArray? = null,
            rng: ByteArray? = null,
        ): EventSnapshot {
            fun u32(o: Int) = (g[o].toInt() and 0xFF) or ((g[o + 1].toInt() and 0xFF) shl 8) or
                ((g[o + 2].toInt() and 0xFF) shl 16) or ((g[o + 3].toInt() and 0xFF) shl 24)
            val engine = procs.find(STD_EVENT_ENGINE)?.let { p ->
                EngineState(
                    procSlot = p.slot,
                    pEventStart = p.u32(0x30),
                    pEventIdk = p.u32(0x34),
                    pEventCurrent = p.u32(0x38),
                    evStateBits = p.u16(0x3C),
                    evStallTimer = p.u16(0x3E),
                    execType = p.u8(0x41),
                    startScript = scripts.name(p.u32(0x30)),
                    currentScript = scripts.name(p.u32(0x38)),
                )
            }
            val callQueue = (0 until CALL_QUEUE_ENTRIES).map {
                CallEntry(u32(it * 8), g[it * 8 + 4].toInt() and 0xFF, g[it * 8 + 5].toInt())
            }
            val slots = IntArray(EVENT_SLOT_COUNT) { u32(0x80 + it * 4) }
            val slotQueue = IntArray(SLOT_QUEUE_WORDS) { u32(0xB8 + it * 4) }
            val active = (0 until ACTIVE_QUEUE_ENTRIES).map {
                ActiveEntry(u32(0x138 + it * 8), u32(0x138 + it * 8 + 4))
            }
            return EventSnapshot(
                step = step,
                engine = engine,
                callQueue = callQueue,
                slots = slots,
                slotQueue = slotQueue,
                slotCounter = u32(0x130),
                activeQueue = active,
                permanentFlags = flags?.copyOfRange(0, 0x20),
                chapterFlags = flags?.copyOfRange(0x20, 0x30),
                playSt = playSt,
                rngState = rng?.let { r ->
                    IntArray(RNG_WORDS) {
                        (r[2 * it].toInt() and 0xFF) or ((r[2 * it + 1].toInt() and 0xFF) shl 8)
                    }
                },
            )
        }
    }
}

/** One decoded sample of the event engine state. Arrays are copies; equality is by identity. */
data class EventSnapshot(
    /** Emulated step (prologue) or frame (newgame_bm). */
    val step: Int,
    /** The `StdEventEngine` proc, or null if it is not running. */
    val engine: EngineState?,
    /** `gEventCallQueue` (16 entries). */
    val callQueue: List<CallEntry>,
    /** `gEventSlots` (14 words). */
    val slots: IntArray,
    /** `gEventSlotQueue` (30 words). */
    val slotQueue: IntArray,
    /** `gEventSlotCounter`. */
    val slotCounter: Int,
    /** `gEventActiveQueue` (8 entries). */
    val activeQueue: List<ActiveEntry>,
    /** `gPermanentFlagBits` (0x20 bytes), or null if the source has no flags. */
    val permanentFlags: ByteArray?,
    /** `gChapterFlagBits` (0x10 bytes), or null if the source has no flags. */
    val chapterFlags: ByteArray?,
    /** `gPlaySt` (0x4C bytes, EWRAM 0x0202BCF0), or null if the source has no `gplayst` watch. */
    val playSt: ByteArray? = null,
    /** `gRNState` (3 u16 words, IWRAM 0x03000000), or null if the source has no `rng` watch. */
    val rngState: IntArray? = null,
)

/** `struct EventEngineProc` fields of the StdEventEngine proc, pcs named via the script index. */
data class EngineState(
    /** Index in `sProcArray`. */
    val procSlot: Int,
    /** `pEventStart` (+0x30). */
    val pEventStart: Int,
    /** `pEventIdk` (+0x34), the caller script position. */
    val pEventIdk: Int,
    /** `pEventCurrent` (+0x38), the next command. */
    val pEventCurrent: Int,
    /** `evStateBits` (+0x3C). */
    val evStateBits: Int,
    /** `evStallTimer` (+0x3E). */
    val evStallTimer: Int,
    /** `execType` (+0x41). */
    val execType: Int,
    /** Script (name plus byte offset) containing [pEventStart], or null. */
    val startScript: ScriptPos?,
    /** Script (name plus byte offset) containing [pEventCurrent], or null. */
    val currentScript: ScriptPos?,
)

/** One `gEventCallQueue` entry (`struct EnqueuedEventCall`). */
data class CallEntry(val events: Int, val execType: Int, val isUsed: Int)

/** One `gEventActiveQueue` entry (`struct Struct03000570`). */
data class ActiveEntry(val evt1: Int, val evt2: Int)

/** A position inside a named event script: [name] plus [offset] bytes from its start. */
data class ScriptPos(val name: String, val offset: Int)

/** Maps ROM addresses to the `EventScr*` script containing them (`scripts/index.json`). */
class ScriptNames private constructor(private val entries: List<Triple<Int, Int, String>>) {
    /** The script containing [pc] with the byte offset, or null if no known script does. */
    fun name(pc: Int): ScriptPos? =
        entries.firstOrNull { pc >= it.first && pc < it.first + it.second }
            ?.let { ScriptPos(it.third, pc - it.first) }

    /** Loader. */
    companion object {
        private val ENTRY = Regex(
            """"name":\s*"([^"]+)".*?"address":\s*"0x([0-9A-Fa-f]+)",\s*"size":\s*(\d+)""",
        )

        /** Reads `$FE8_ASSETS/extracted/scripts/index.json`; empty when absent. */
        fun load(): ScriptNames {
            val assets =
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
            val file = File(assets, "extracted/scripts/index.json")
            if (!file.isFile) return ScriptNames(emptyList())
            return ScriptNames(
                file.readLines().mapNotNull { ENTRY.find(it) }.map {
                    val g = it.groupValues
                    Triple(g[2].toLong(16).toInt(), g[3].toInt(), g[1])
                },
            )
        }
    }
}
