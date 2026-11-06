package fe8.game.save

import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventState
import gbafe.core.flags.EventFlags
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock

/**
 * The RAM state `WriteSuspendSave` reads (and the few things it changes), as the objects that
 * own it elsewhere. The only things held here are buffers that have no owner yet (the stand-ins
 * [actionData], [traps], [convoy], [menuOverride], [worldMapStuff] and [dungeonState], each
 * documented below); no global exists twice.
 *
 * `gPidStatsData` and `gChapterStats` are owned by [BmSaveLib] (`pidStatsData`,
 * `chapterStatsData`) and are not repeated here.
 *
 * @property playSt `gPlaySt` (`time_saved` is set by the save).
 * @property bmSt `gBmSt` (`just_resumed` is cleared by the save).
 * @property rng the game's one [RngService] (`gRNSeeds`).
 * @property units the raw unit arrays (`gUnitArrayBlue/Red/Green`).
 * @property flags the event flag memory (`gPermanentFlagBits`, `gChapterFlagBits`).
 * @property clock the game's one [GameClock] (`gGameClock`, read by `GetGameClock`).
 * @property eventState the event engine's globals (`gEventSlotCounter`).
 * @property actionData `gActionData`, 0x38 bytes (its first 6 bytes receive the RN state).
 * @property traps `sTrapPool`, 64 traps of 8 bytes (`GetTrap(0)`).
 * @property convoy `gConvoyItemArray` as 100 raw little-endian u16 values.
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
     * command ids, 0 = `MENU_ITEM_NONE`.
     */
    public val menuOverride: ByteArray = ByteArray(Fe8SuspendLayout.MENU_OVERRIDE_MAX)

    /**
     * What `WriteWorldMapStuff(&dest->wmStuff, &gGMData)` would encode (`WriteWorldMapStuff` is
     * not ported; the world map state is not modelled): the 0x24 bytes of `struct GMapSaveInfo`.
     */
    public val worldMapStuff: ByteArray = ByteArray(Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)

    /**
     * `gDungeonState` (`struct Struct030017A0`, 0x25 bytes): `current` at 0, `dungeon[2]` at 0x0C
     * and `type` at 0x24. `SaveDungeonState` writes `type` into `current.unk_0B_4`.
     */
    public val dungeonState: ByteArray = ByteArray(Fe8SuspendLayout.DUNGEON_STATE_SIZE)

    /** Constants. */
    public companion object {
        /** Bytes of `gConvoyItemArray`. */
        public const val CONVOY_BYTES: Int = 200
    }
}
