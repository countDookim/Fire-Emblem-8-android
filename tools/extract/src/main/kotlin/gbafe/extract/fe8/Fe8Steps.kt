package gbafe.extract.fe8

import gbafe.extract.DecompEnums
import gbafe.extract.ExtractArgs
import gbafe.extract.Symbols
import java.io.File

/**
 * Runs the FE8 extraction steps the inputs allow and returns the paths written (relative to `args.out`).
 * Currently `data/items.json`, `data/classes.json`, `data/characters.json`, `data/terrains.json`, `data/chapters.json`, `maps/`,
 * `text/en/messages.json` and `fonts/{system,talk,special}.{png,json}`,
 * `mapsprites/` (standing + moving), `portraits/` (sheets, chibis, mouths, cards + `portraits.json`) (need `--symbols`; ids and face names need `--decomp`).
 */
public fun runFe8Steps(args: ExtractArgs): List<String> {
    val written = ArrayList<String>()
    val rom by lazy { args.rom.readBytes() }
    val symbols = args.symbols?.let(::Symbols) ?: return written
    val names = args.decomp?.let { DecompEnums.read(it, "include/constants/items.h", "ITEM_") } ?: emptyMap()
    val items = symbols["gItemData"]
    val json = gbafe.extract.fe8.items.ItemsExtractor.extractJson(
        rom, items.address, symbols.sizeOrSpan("gItemData") / gbafe.extract.fe8.items.ItemsExtractor.ENTRY_SIZE, names,
    )
    File(args.out, "data").mkdirs()
    File(args.out, "data/items.json").writeText(json)
    written += "data/items.json"
    val classNames = args.decomp?.let { DecompEnums.read(it, "include/constants/classes.h", "CLASS_") } ?: emptyMap()
    val classes = symbols["gClassData"]
    File(args.out, "data/classes.json").writeText(
        gbafe.extract.fe8.classes.ClassesExtractor.extractJson(
            rom, classes.address, symbols.sizeOrSpan("gClassData") / gbafe.extract.fe8.classes.ClassesExtractor.ENTRY_SIZE,
            classNames, names,
        ),
    )
    written += "data/classes.json"
    val charNames = args.decomp?.let { DecompEnums.read(it, "include/constants/characters.h", "CHARACTER_") } ?: emptyMap()
    val chars = symbols["gCharacterData"]
    File(args.out, "data/characters.json").writeText(
        gbafe.extract.fe8.characters.CharactersExtractor.extractJson(
            rom, chars.address, symbols.sizeOrSpan("gCharacterData") / gbafe.extract.fe8.characters.CharactersExtractor.ENTRY_SIZE,
            charNames, classNames,
        ),
    )
    written += "data/characters.json"
    val terrainTables = gbafe.extract.fe8.terrains.TerrainsExtractor.TABLE_PREFIXES
        .flatMap(symbols::namesWithPrefix).distinct()
        .filter { symbols.sizeOrSpan(it) == gbafe.extract.fe8.classes.ClassesExtractor.TERRAIN_COUNT }
        .associateWith { symbols[it].address }
    val terrainNames = args.decomp?.let { DecompEnums.read(it, "include/constants/terrains.h", "TERRAIN_") } ?: emptyMap()
    File(args.out, "data/terrains.json").writeText(
        gbafe.extract.fe8.terrains.TerrainsExtractor.extractJson(rom, terrainTables, terrainNames),
    )
    written += "data/terrains.json"
    File(args.out, "data/chapters.json").writeText(
        gbafe.extract.fe8.chapters.ChaptersExtractor.extractJson(
            rom, symbols["gChapterDataTable"].address - ROM_BASE,
            symbols.sizeOrSpan("gChapterDataTable") / gbafe.extract.fe8.chapters.ChaptersExtractor.ENTRY_SIZE,
        ),
    )
    written += "data/chapters.json"
    val assetTable = symbols["gChapterDataAssetTable"].address - ROM_BASE
    val tilesetsDone = HashSet<String>()
    var maps = 0
    for (ch in gbafe.extract.fe8.chapters.ChaptersExtractor.extract(
        rom, symbols["gChapterDataTable"].address - ROM_BASE,
        symbols.sizeOrSpan("gChapterDataTable") / gbafe.extract.fe8.chapters.ChaptersExtractor.ENTRY_SIZE,
    )) {
        val m = (ch["map"] as Map<*, *>).mapValues { it.value as Int }
        if (m.getValue("mainLayerId") == 0) continue
        val o = gbafe.extract.fe8.maps.MapsExtractor.extract(
            rom, assetTable, m.getValue("obj1Id"), m.getValue("obj2Id"), m.getValue("paletteId"),
            m.getValue("tileConfigId"), m.getValue("mainLayerId"), m.getValue("changeLayerId"),
        )
        val ts = "tileset_%d_%d_%d_%d".format(m["obj1Id"], m["obj2Id"], m["paletteId"], m["tileConfigId"])
        File(args.out, "maps/tilesets").mkdirs()
        if (tilesetsDone.add(ts)) File(args.out, "maps/tilesets/$ts.png").writeBytes(o.tilesetPng())
        // Unit placement: prologue only until the event converter (M2-15) finds every chapter's LOAD targets.
        val units = if (ch["index"] != 0) emptyList() else symbols.namesWithPrefix("UnitDef_Event_Prologue")
            .sortedBy { symbols[it].address.toUInt() }
            .map { it to gbafe.extract.fe8.units.UnitsExtractor.readGroup(rom, symbols[it].address - ROM_BASE, symbols.sizeOrSpan(it) / gbafe.extract.fe8.units.UnitsExtractor.ENTRY_SIZE) }
        File(args.out, "maps/chapter_%02d.tmj".format(ch["index"])).writeText(o.tmj(ts, "tilesets/$ts.png", units))
        maps++
    }
    written += "maps/ ($maps .tmj, ${tilesetsDone.size} tilesets)"
    val faces = args.decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
        ?.let(gbafe.extract.text.MessagesExtractor::readFaces) ?: emptyMap()
    val rootPtr = symbols["gMsgHuffmanTableRoot"].address - ROM_BASE
    val rootOffset = (rom[rootPtr].toInt() and 0xFF) or ((rom[rootPtr + 1].toInt() and 0xFF) shl 8) or
        ((rom[rootPtr + 2].toInt() and 0xFF) shl 16) or ((rom[rootPtr + 3].toInt() and 0xFF) shl 24)
    val messages = gbafe.extract.text.MessagesExtractor.extract(
        rom, symbols["gMsgHuffmanTable"].address - ROM_BASE, rootOffset - ROM_BASE,
        symbols["gMsgTable"].address - ROM_BASE, symbols.sizeOrSpan("gMsgTable") / 4, gbafe.text.MessageCodec(faces = faces),
    )
    File(args.out, "text/en").mkdirs()
    File(args.out, "text/en/messages.json").writeText(gbafe.extract.text.MessagesExtractor.toJson("fe8", messages))
    written += "text/en/messages.json"
    File(args.out, "fonts").mkdirs()
    for ((name, symbol) in listOf("system" to "TextGlyphs_System", "talk" to "TextGlyphs_Talk", "special" to "TextGlyphs_Special")) {
        val glyphs = gbafe.extract.font.FontExtractor.read(rom, symbols[symbol].address - ROM_BASE)
        File(args.out, "fonts/$name.png").writeBytes(gbafe.extract.font.FontExtractor.png(glyphs))
        File(args.out, "fonts/$name.json").writeText(gbafe.extract.font.FontExtractor.toJson("fe8", name, "$name.png", glyphs))
        written += listOf("fonts/$name.png", "fonts/$name.json")
    }
    val portraits = gbafe.extract.fe8.portraits.PortraitsExtractor.extract(
        rom, symbols["portrait_data"].address - ROM_BASE,
        symbols.sizeOrSpan("portrait_data") / gbafe.extract.fe8.portraits.PortraitsExtractor.ENTRY_SIZE,
    )
    for ((path, png) in portraits.files) File(args.out, "portraits/$path").apply { parentFile.mkdirs() }.writeBytes(png)
    File(args.out, "portraits/portraits.json").writeText(portraits.json)
    written += "portraits/portraits.json (+${portraits.files.size} PNG)"
    val sprites = gbafe.extract.fe8.mapsprites.MapSpritesExtractor.extract(
        rom, symbols["unit_icon_wait_table"].address - ROM_BASE,
        symbols.sizeOrSpan("unit_icon_wait_table") / gbafe.extract.fe8.mapsprites.MapSpritesExtractor.ENTRY_SIZE,
        symbols["gPal_MapSprite"].address - ROM_BASE,
        symbols["unit_icon_move_table"].address - ROM_BASE,
        symbols.sizeOrSpan("unit_icon_move_table") / gbafe.extract.fe8.mapsprites.MapSpritesExtractor.MOVE_ENTRY_SIZE,
    )
    for ((path, png) in sprites.files) File(args.out, "mapsprites/$path").apply { parentFile.mkdirs() }.writeBytes(png)
    File(args.out, "mapsprites/mapsprites.json").writeText(sprites.json)
    written += "mapsprites/mapsprites.json (+${sprites.files.size} PNG)"
    // Event scripts v0 (M2-15): the prologue's EventScr_Prologue_* lists.
    val romNames = symbols.romNamesByAddress()
    val scripts = symbols.namesWithPrefix("EventScr_Prologue").sortedBy { symbols[it].address.toUInt() }
        .map { it to gbafe.extract.fe8.events.EventScriptConverter.convert(rom, symbols[it].address - ROM_BASE, romNames) }
    File(args.out, "scripts/prologue.fe8s").apply { parentFile.mkdirs() }.writeText(gbafe.extract.fe8.events.EventScriptConverter.render(scripts))
    written += "scripts/prologue.fe8s (${scripts.size} scripts)"
    // m4a (M2-18/19): every gSongTable entry -> songs/songNNN.mid (once per distinct header) + songs/songs.json,
    // and the voicegroups the songs use -> voicegroups/<name>.json.
    val songTable = symbols["gSongTable"].address - ROM_BASE
    val songs = gbafe.extract.m4a.M4aSong.readSongTable(rom, songTable, symbols.sizeOrSpan("gSongTable") / 8)
    val midiByHeader = HashMap<Int, String>()
    val voicegroups = LinkedHashSet<Int>()
    val rows = songs.mapIndexed { id, e ->
        val midi = midiByHeader.getOrPut(e.header) {
            val song = gbafe.extract.m4a.M4aSong.decode(rom, e.header - ROM_BASE)
            if (song.tracks.isNotEmpty()) voicegroups += song.voicegroup
            "song%03d.mid".format(id).also { File(args.out, "songs/$it").apply { parentFile.mkdirs() }.writeBytes(gbafe.extract.m4a.M4aSong.toMidi(song)) }
        }
        linkedMapOf("id" to id, "header" to (romNames[e.header] ?: e.header), "musicPlayer" to e.musicPlayer, "unknown" to e.unknown, "midi" to midi)
    }
    File(args.out, "songs/songs.json").writeText(gbafe.extract.Json.write(linkedMapOf("format" to "gbafe.songs", "game" to "fe8", "version" to 1, "songs" to rows)))
    for (vg in voicegroups) {
        val vgName = romNames[vg] ?: "voicegroup_%08X".format(vg)
        File(args.out, "voicegroups/$vgName.json").apply { parentFile.mkdirs() }.writeText(
            gbafe.extract.Json.write(linkedMapOf("format" to "gbafe.voicegroup", "game" to "fe8", "version" to 1,
                "voices" to gbafe.extract.m4a.M4aSong.readVoicegroup(rom, vg - ROM_BASE, symbols = romNames))),
        )
    }
    // Direct-sound samples (M2-19) -> samples/<symbol>.wav + samples/samples.json (every WaveData header field).
    val waves = gbafe.extract.m4a.M4aSong.collectWaves(rom, voicegroups.map { it - ROM_BASE })
    val sampleRows = waves.map { off ->
        val w = gbafe.extract.m4a.M4aSong.readWave(rom, off)
        val name = romNames[off + ROM_BASE] ?: "wave_%08X".format(off + ROM_BASE)
        File(args.out, "samples/$name.wav").apply { parentFile.mkdirs() }.writeBytes(gbafe.extract.m4a.M4aSong.toWav(w))
        linkedMapOf("name" to name, "wav" to "$name.wav", "type" to w.type, "status" to w.status, "freq" to w.freq.toLong().and(0xFFFFFFFFL),
            "loopStart" to w.loopStart, "size" to w.data.size)
    }
    File(args.out, "samples/samples.json").writeText(gbafe.extract.Json.write(linkedMapOf("format" to "gbafe.samples", "game" to "fe8", "version" to 1, "samples" to sampleRows)))
    written += "samples/ (${sampleRows.size} WAV)"
    written += "songs/ (${rows.size} entries, ${midiByHeader.size} MIDI), voicegroups/ (${voicegroups.size})"
    // UI icons (M2-20): item_icon_tiles (up to item_icon_palette) -> ui/icons.png + ui/icons.json.
    val iconTiles = symbols["item_icon_tiles"].address
    val iconPal = symbols["item_icon_palette"].address
    val icons = gbafe.extract.fe8.ui.IconsExtractor.extract(rom, iconTiles - ROM_BASE, (iconPal - iconTiles) / gbafe.extract.fe8.ui.IconsExtractor.ICON_BYTES, iconPal - ROM_BASE)
    File(args.out, "ui/icons.png").apply { parentFile.mkdirs() }.writeBytes(icons.png)
    File(args.out, "ui/icons.json").writeText(icons.json)
    written += "ui/icons.png (${icons.count} icons)"
    return written
}

private const val ROM_BASE = 0x08000000
