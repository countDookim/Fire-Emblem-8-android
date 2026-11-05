package gbafe.extract.fe8.events

/**
 * Grouping of the FE8 event scripts into `.fe8s` files like the decomp's `src/events` headers (one `.h` each).
 *
 * The decomp's `src/events_script.c` includes the `src/events` headers (one `.h` each) in a fixed order; its
 * `.data` spans 0x089EEA58 until 0x08A006E4 (exclusive) (decomp `fireemblem8.map`:
 * `.data 0x089eea58 0x11c8c src/events_script.o`). Each header's scripts are contiguous there, so
 * a script belongs to the last header whose first script's address is <= its own. Scripts outside
 * that range (other source files) go to `common`.
 */
public object Fe8ScriptFiles {
    /** First address of `events_script.o`'s `.data`. */
    public const val DATA_START: Int = 0x089EEA58

    /** End (exclusive) of `events_script.o`'s `.data`. */
    public const val DATA_END: Int = 0x08A006E4

    /** File stem to the first `EventScr` of its header, in header (address) order. */
    public val FIRST_SCRIPTS: List<Pair<String, String>> = listOf(
        "common" to "EventScr_9EEA58",
        "prologue" to "EventScr_Prologue_BeginningScene",
        "prologue" to "EventScr_Prologue_ExecTut",
        "ch1" to "EventScr_Ch1_BeginningScene",
        "ch1" to "EventScr_Ch1Tut_GuideWTA",
        "ch2" to "EventScr_Ch2_BeginningScene",
        "ch3" to "EventScr_Ch3_BeginningScene",
        "ch4" to "EventScr_Ch4_BeginningScene",
        "ch5" to "EventScr_Ch5_BeginningScene",
        "ch5x" to "EventScr_Ch5x_BeginningScene",
        "ch6" to "EventScr_Ch6_BeginningScene",
        "ch7" to "EventScr_Ch7_BeginningScene",
        "ch8" to "EventScr_Ch8_BeginningScene",
        "ch9a" to "EventScr_Ch9a_BeginningScene",
        "ch10a" to "EventScr_Ch10a_BeginningScene",
        "ch11a" to "EventScr_Ch11a_BeginningScene",
        "ch12a" to "EventScr_Ch12a_BeginningScene",
        "ch13a" to "EventScr_Ch13a_BeginningScene",
        "ch14a" to "EventScr_Ch14a_BeginningScene",
        "ch15a" to "EventScr_Ch15a_BeginningScene",
        "ch16a" to "EventScr_Ch16a_BeginningScene",
        "ch17a" to "EventScr_Ch17a_BeginningScene",
        "ch18a" to "EventScr_Ch18a_BeginningScene",
        "ch19a" to "EventScr_Ch19a_BeginningScene",
        "ch20a" to "EventScr_Ch20a_BeginningScene",
        "ch21a" to "EventScr_Ch21a_BeginningScene",
        "ch21xa" to "EventScr_Ch21xa_BeginningScene",
        "ch9b" to "EventScr_Ch9b_BeginningScene",
        "ch10b" to "EventScr_Ch10b_BeginningScene",
        "ch11b" to "EventScr_Ch11b_BeginningScene",
        "ch12b" to "EventScr_Ch12b_BeginningScene",
        "ch13b" to "EventScr_Ch13b_BeginningScene",
        "ch14b" to "EventScr_Ch14b_BeginningScene",
        "ch15b" to "EventScr_Ch15b_BeginningScene",
        "ch16b" to "EventScr_Ch16b_BeginningScene",
        "ch17b" to "EventScr_Ch17b_BeginningScene",
        "ch18b" to "EventScr_Ch18b_BeginningScene",
        "ch19b" to "EventScr_Ch19b_BeginningScene",
        "ch20b" to "EventScr_Ch20b_BeginningScene",
        "ch21b" to "EventScr_Ch21b_BeginningScene",
        "ch21xb" to "EventScr_Ch21x_BeginningScene",
        "tower" to "EventScr_Tower1_BeginningScene",
        "ruin" to "EventScr_Ruins1_BeginningScene",
        "lordsplit" to "EventScr_CastleFelia_BeginningScene",
        "melkaen-coast" to "EventScr_MelkaenCoast_BeginningScene",
        "unused" to "EventScr_UnkCh3B_BeginningScene",
        "messed" to "EventScr_089FFC0C",
    )

    /**
     * Groups [addresses] (script name to GBA address, all scripts to place) by file stem: names
     * sorted by address, stems in [FIRST_SCRIPTS] order with `common` first. Throws
     * [IllegalStateException] if a first script is missing or the first-script addresses are not
     * strictly increasing.
     */
    public fun group(addresses: Map<String, Int>): Map<String, List<String>> {
        val starts = FIRST_SCRIPTS.map { (stem, name) ->
            stem to (addresses[name] ?: error("first script $name of '$stem' is missing"))
        }
        for (i in 1 until starts.size) {
            check(starts[i].second.toUInt() > starts[i - 1].second.toUInt()) {
                "first script of '${starts[i].first}' is not after '${starts[i - 1].first}'"
            }
        }
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((stem, _) in starts) out.getOrPut(stem) { ArrayList() }
        val sorted = addresses.entries.sortedBy { it.value.toUInt() }
        for ((name, address) in sorted) {
            val inData = address.toUInt() >= DATA_START.toUInt() && address.toUInt() < DATA_END.toUInt()
            var stem = "common"
            if (inData) {
                for ((s, a) in starts) if (a.toUInt() <= address.toUInt()) stem = s else break
            }
            out.getValue(stem) += name
        }
        return out
    }
}
