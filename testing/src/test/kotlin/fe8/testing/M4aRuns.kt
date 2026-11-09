package fe8.testing

/**
 * M5 runs (new game, prologue, the save-based map runs) that the m4a tests do not cover yet: their
 * sequencer state diverges from our model from `ram_trace_newgame` f975 (channel 12), an open
 * lead, not a regression. `ram_trace_soft_reset` is excluded for another reason: its soft reset
 * clears RAM at f1002, so the sound state does not carry over between its dumps.
 *
 * `ram_trace_item_popup` shares the exact same Prologue input timeline as `ram_trace_prologue`
 * up to frame 18750 (with identical inputs and pre-popup RAM), so it inherits the same known
 * sequencer divergence at f975.
 */
internal val M4A_UNCOVERED_RUNS: Set<String> = setOf(
    "sram_boot", "sram_preload", "ram_trace_newgame", "ram_trace_newgame_bm",
    "ram_trace_prologue", "ram_trace_first_phase", "ram_trace_ch2_scroll", "ram_trace_ch6_fog",
    "sram_chapter2_boot", "sram_chapter6_boot", "ram_trace_ch6_phase_start", "ram_trace_ch6_start",
    "ram_trace_ch6_cursor_edges", "ram_trace_prologue_talk", "ram_trace_prologue_battle",
    "ram_trace_prologue_resume", "ram_trace_prologue_battle_end", "ram_trace_soft_reset",
    "ram_trace_item_popup",
)
