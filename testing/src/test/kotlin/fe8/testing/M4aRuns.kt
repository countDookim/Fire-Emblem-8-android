package fe8.testing

/**
 * M5 runs (new game, prologue, the save-based map runs) that the m4a tests do not cover yet: their
 * sequencer state diverges from our model from `ram_trace_newgame` f975 (channel 12), an open
 * lead, not a regression.
 */
internal val M4A_UNCOVERED_RUNS: Set<String> = setOf(
    "sram_boot", "sram_preload", "ram_trace_newgame", "ram_trace_newgame_bm",
    "ram_trace_prologue", "ram_trace_first_phase", "ram_trace_ch2_scroll", "ram_trace_ch6_fog",
)
