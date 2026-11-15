package fe8.game.bm

import fe8.game.RomView

/**
 * The spell association table `gSpellAssocData` of src/spellassoc.c: per weapon or spell item
 * the number of map-animation characters, the map animation script, and the facing and flash
 * colour settings. A ROM table scan; nothing is allocated.
 *
 * Entry layout (`struct SpellAssoc`, 0x10 bytes): `item` u16 (0xFFFF ends the table), `count` u8
 * at +2, `efx` s16 at +4, `pcmd_manim` pointer at +8, `stat` u8 at +0xC, `facing` u8 at +0xD,
 * `flash_color` u8 at +0xE.
 *
 * @param rom the ROM holding the table.
 */
public class SpellAssoc(private val rom: RomView) {
    /**
     * Transcribed from src/spellassoc.c: GetSpellAssocStructPtr (not verified: every captured
     *   weapon's entry has the same values as the first entry and its neighbours, so a scan that
     *   returns the wrong entry passes; needs a battle with a magic weapon, M6-H)
     *
     * The ROM address of the entry of [item] (its item index, `item & 0xFF`); the terminating entry
     * where the table has none. The field offsets of the getters below are verified; which entry an
     * item selects is not.
     */
    public fun getSpellAssocStructPtr(item: Int): Int {
        val index = item and 0xFF
        var entry = TABLE
        var id = rom.u16(entry)
        if (id != END) {
            while (id != index) {
                entry += ENTRY_SIZE
                id = rom.u16(entry)
                if (id == END) break
            }
        }
        return entry
    }

    /** Ported from src/spellassoc.c: GetSpellAssocCharCount. The actors of [item]'s animation. */
    public fun getSpellAssocCharCount(item: Int): Int = rom.u8(getSpellAssocStructPtr(item) + 2)

    /**
     * Ported from src/spellassoc.c: GetSpellAssocMapAnimProcScript
     *
     * The ROM address of [item]'s special map animation script, 0 for none.
     */
    public fun getSpellAssocMapAnimProcScript(item: Int): Int =
        rom.u32(getSpellAssocStructPtr(item) + 8)

    /** Ported from src/spellassoc.c: GetSpellAssocFacing. The `MA_FACING_*` of [item]. */
    public fun getSpellAssocFacing(item: Int): Int = rom.u8(getSpellAssocStructPtr(item) + 0xD)

    /**
     * The `efx` halfword of [item]'s entry (`struct SpellAssoc` +4): the spell animation id that
     * `GetSpellAnimId` (src/banim-battleparse.c, [BanimPrepare]) turns into the animation index.
     */
    public fun getSpellAssocEfx(item: Int): Int = rom.u16(getSpellAssocStructPtr(item) + 4)

    /**
     * Transcribed from src/spellassoc.c: GetSpellAssocReturnBool (not verified: its caller, the
     *   info box (EP-20), is not written, so no captured value shows the result)
     *
     * The `stat` byte of [item]'s entry.
     */
    public fun getSpellAssocReturnBool(item: Int): Int = rom.u8(getSpellAssocStructPtr(item) + 0xC)

    /**
     * Transcribed from src/spellassoc.c: GetSpellAssocFlashColor (not verified: its caller, the hit
     *   flash (EP-25), is not written, so no captured value shows the result)
     *
     * The hit flash colour setting of [item]'s entry.
     */
    public fun getSpellAssocFlashColor(item: Int): Int = rom.u8(getSpellAssocStructPtr(item) + 0xE)

    private companion object {
        const val TABLE = 0x088AFBD8
        const val ENTRY_SIZE = 0x10
        const val END = 0xFFFF
    }
}
