/**
 * The game state a mid-chapter suspend save reads and writes.
 *
 * [SuspendRam] is a bundle of references to the objects that own the state `WriteSuspendSave`
 * serializes and `ReadSuspendSave` restores (play state, units, RNG, clock, event flags), plus a
 * few raw buffers for state that is not ported yet (traps, convoy, `gActionData`, menu overrides,
 * world map and dungeon state). It is not a copy of that state.
 */

package fe8.game.save

import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventState
import gbafe.core.flags.EventFlags
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock

/**
 * The RAM state `WriteSuspendSave` reads and `ReadSuspendSave` restores (and the few things the
 * save changes), as the objects that own it elsewhere. The only things held here are buffers
 * that have no owner yet (the stand-ins [actionData] and [menuOverride] until M6-B, [traps] and
 * [convoy] until M6-H, [worldMapStuff] and [dungeonState] until M6-I, each documented below); no
 * global exists twice.
 *
 * `gPidStatsData` and `gChapterStats` are owned by [BmSaveLib] (`pidStatsData`,
 * `chapterStatsData`) and are not repeated here.
 *
 * @property playSt `gPlaySt` (`time_saved` is set by the save).
 * @property bmSt `gBmSt` (`just_resumed` is cleared by the save).
 * @property rng The game's primary [RngService] (`gRNSeeds`).
 * @property units The active unit tables (`gUnitArrayBlue/Red/Green`).
 * @property flags The event flag memory (`gPermanentFlagBits`, `gChapterFlagBits`).
 * @property clock The game's primary [GameClock] (`gGameClock`, read by `GetGameClock`).
 * @property eventState The event engine's global state (`gEventSlotCounter`).
 * @property actionData Raw `gActionData` buffer (`struct ActionData`, the pending unit action),
 *   0x38 bytes; its first 6 bytes (`_u00[3]`) receive the RN state.
 * @property traps Raw `sTrapPool` buffer, 64 map traps (`struct Trap`) of 8 bytes each
 *   (`GetTrap(0)`).
 * @property convoy Raw `gConvoyItemArray` buffer, 100 little-endian u16 item words (200 bytes;
 *   `CONVOY_ITEM_COUNT`). The save packs these into 0xB0 bytes.
 */
public class SuspendRam(
    public val playSt: PlaySt,
    public val bmSt: BmSt,
    public val rng: RngService,
    public val units: UnitTable,
    public val flags: EventFlags,
    public val clock: GameClock,
    public val eventState: EventState,
    public val actionData: ByteArray = ByteArray(Fe8SuspendLayout.ACTION_SIZE),
    public val traps: ByteArray = ByteArray(
        Fe8SuspendLayout.TRAP_MAX_COUNT * Fe8SuspendLayout.TRAP_SIZE,
    ),
    public val convoy: ByteArray = ByteArray(CONVOY_BYTES),
) {
    /**
     * The result of `GetForceDisabledMenuItems` (the `sMenuOverrides` table is not ported): 16
     * command ids of menu items the game forces to be disabled, 0 = `MENU_ITEM_NONE`.
     */
    public val menuOverride: ByteArray = ByteArray(Fe8SuspendLayout.MENU_OVERRIDE_MAX)

    /**
     * What `WriteWorldMapStuff(&dest->wmStuff, &gGMData)` would encode (`WriteWorldMapStuff` is
     * not ported; the world map state is not modelled): the 0x24 bytes of `struct GMapSaveInfo`
     * (world map nodes, paths, units, cursor, skirmishes). Zero until the world map is ported.
     */
    public val worldMapStuff: ByteArray = ByteArray(Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)

    /**
     * `gDungeonState` (`struct Struct030017A0`, 0x25 bytes, tower / ruins records): `current` at
     * 0, `dungeon[2]` at 0x0C and `type` at 0x24 (tower = 0, ruins = 1). `SaveDungeonState`
     * writes `type` into `current.unk_0B_4` after copying `current` out.
     */
    public val dungeonState: ByteArray = ByteArray(Fe8SuspendLayout.DUNGEON_STATE_SIZE)

    /** Size constant of the convoy buffer. */
    public companion object {
        /** Total size in bytes of `gConvoyItemArray` (100 item entries × 2 bytes). */
        public const val CONVOY_BYTES: Int = 200
    }
}
