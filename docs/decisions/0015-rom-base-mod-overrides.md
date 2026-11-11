# 0015 — Game data: the ROM is the vanilla source; mods override per table

**Status.** Accepted (owner, 2026-11-11). Amends the brief's "Data" bullet and ARCHITECTURE principle 6.

**Context.** The brief asked for all game data in versioned, schema-validated JSON with none hardcoded. The
audit [M7-modding](../plans/M7-modding.md) found the opposite in practice: the app keeps the imported ROM
(0011) and about 76 files in `game/` read tables by ROM address; no production code reads the extracted files.
The differential tests compare ports against the same ROM, so ROM reads are what verification covers.

**Decision.** Option A of the audit.
1. The imported ROM stays the vanilla source for every table and asset. Ports keep reading it through `RomView`;
   M6 work does not change.
2. Mods override per table: each reader group asks a small resolver "is there a mod file for table T?" and
   falls back to the ROM. With no mods loaded the game is bit-exact as today.
3. Overrides are added in M7, group by group, starting with the tables that already have an extracted format
   (chapters, characters, classes, items, scripts, text). A group gets an override when its M7 row is done;
   until then it is ROM-only.
4. The extracted JSON/Tiled/PNG/MIDI files remain the mod file formats and the reference data for tests.

**Consequences.** Verification is unaffected. M7 is sized by the number of reader groups, not by a migration of
every reader. "None hardcoded" becomes "every table reachable by an override". ROM address constants stay in
`fe8.*` main sources (never in `gbafe.*`; M7-22 checks that). Decisions D2–D9 of the audit are still open.
