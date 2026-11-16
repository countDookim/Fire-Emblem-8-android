package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmSt
import fe8.game.bm.ManimSt
import fe8.game.bm.MapLevelUpFrame
import fe8.game.bm.MapLevelUpStats
import fe8.game.bm.UnitTable
import fe8.game.mapanim.ApProcs
import fe8.game.mapanim.MapLevelUpAnnouncement
import fe8.game.mapanim.MapLevelUpGainLabels
import fe8.game.worldmap.WorldMapApHost
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/** The real `ProcScr_ManimLevelUp` and the objects it draws through, as the game wires them. */
internal class MapLevelUpRig(
    engine: ProcEngine,
    rom: RomView,
    hardware: BgHardware,
    sound: SoundWrapper,
    bm: BmSt,
    manim: ManimSt,
    units: UnitTable,
    battle: BattleUnit,
    text: TextSystem,
    messages: MsgStrings,
    hooks: MapLevelUpAnnouncement.Hooks,
) {
    /** The sprite layers the announcement's sprites are put on. */
    val layers = SpriteLayers()

    /** The sprite animation procs. */
    val apProcs: ApProcs

    /** The gain label procs. */
    val labels: MapLevelUpGainLabels

    /** The display. */
    val announcement: MapLevelUpAnnouncement

    init {
        val player = SpriteAnimPlayer(
            { rom.u16(it) }, layers, WorldMapApHost(rom, hardware, OamBuffer()),
        )
        player.clearAll()
        apProcs = ApProcs(engine, player)
        val battles = arrayOf(battle)
        val frame = MapLevelUpFrame(
            rom, units, battles, text, messages, hardware.tilemaps[0], hardware.tilemaps[1],
            hardware.palette, hardware.vram, hardware.tileDataOffset(1),
        )
        val stats = MapLevelUpStats(rom, units, battles, text, hardware.tilemaps[0])
        labels = MapLevelUpGainLabels(engine, apProcs, hardware, rom)
        announcement = MapLevelUpAnnouncement(
            engine, hardware, rom, sound, bm, manim, apProcs, text, frame, stats, labels, hooks,
        )
    }
}
