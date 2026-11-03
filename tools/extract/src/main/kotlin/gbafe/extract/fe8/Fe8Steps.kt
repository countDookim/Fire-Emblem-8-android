package gbafe.extract.fe8

import gbafe.extract.DecompSources
import gbafe.extract.ExtractSources
import gbafe.extract.Json
import gbafe.extract.ManifestSources
import gbafe.extract.RecordingSources
import gbafe.extract.ExtractArgs
import gbafe.extract.Symbols
import java.io.File

/**
 * Runs the FE8 extraction steps the inputs allow and returns the paths written (relative to `args.out`).
 * Currently `data/items.json`, `data/classes.json`, `data/characters.json`, `data/terrains.json`, `data/chapters.json`, `maps/`,
 * `text/en/messages.json` and `fonts/{system,talk,special}.{png,json}`,
 * `boot/` (boot screen ROM windows), `mapsprites/` (standing + moving), `portraits/` (sheets, chibis, mouths, cards + `portraits.json`) (need `--symbols`; ids and face names need `--decomp`).
 */
public fun runFe8Steps(args: ExtractArgs): List<String> {
    val decomp = args.manifest?.let { ManifestSources(it.readText()) }
        ?: args.symbols?.let { DecompSources(Symbols(it), args.decomp) } ?: return emptyList()
    val recording = args.writeManifest?.let { RecordingSources(decomp) }
    val written = runFe8Steps(args, recording ?: decomp)
    if (recording != null) args.writeManifest.writeText(Json.write(recording.manifest()))
    return written
}

/** Runs the FE8 steps with the decomp answers from [symbols]; see [runFe8Steps]. */
public fun runFe8Steps(args: ExtractArgs, symbols: ExtractSources): List<String> {
    val written = ArrayList<String>()
    val rom by lazy { args.rom.readBytes() }
    val names = symbols.enumNames("include/constants/items.h", "ITEM_")
    val items = symbols["gItemData"]
    val json = gbafe.extract.fe8.items.ItemsExtractor.extractJson(
        rom, items.address, symbols.sizeOrSpan("gItemData") / gbafe.extract.fe8.items.ItemsExtractor.ENTRY_SIZE, names,
    )
    File(args.out, "data").mkdirs()
    File(args.out, "data/items.json").writeText(json)
    written += "data/items.json"
    val classNames = symbols.enumNames("include/constants/classes.h", "CLASS_")
    val classes = symbols["gClassData"]
    File(args.out, "data/classes.json").writeText(
        gbafe.extract.fe8.classes.ClassesExtractor.extractJson(
            rom, classes.address, symbols.sizeOrSpan("gClassData") / gbafe.extract.fe8.classes.ClassesExtractor.ENTRY_SIZE,
            classNames, names,
        ),
    )
    written += "data/classes.json"
    val charNames = symbols.enumNames("include/constants/characters.h", "CHARACTER_")
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
    val terrainNames = symbols.enumNames("include/constants/terrains.h", "TERRAIN_")
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
        // Unit placement: prologue only until the event converter finds every chapter's LOAD targets.
        val units = if (ch["index"] != 0) emptyList() else symbols.namesWithPrefix("UnitDef_Event_Prologue")
            .sortedBy { symbols[it].address.toUInt() }
            .map { it to gbafe.extract.fe8.units.UnitsExtractor.readGroup(rom, symbols[it].address - ROM_BASE, symbols.sizeOrSpan(it) / gbafe.extract.fe8.units.UnitsExtractor.ENTRY_SIZE) }
        File(args.out, "maps/chapter_%02d.tmj".format(ch["index"])).writeText(o.tmj(ts, "tilesets/$ts.png", units))
        maps++
    }
    written += "maps/ ($maps .tmj, ${tilesetsDone.size} tilesets)"
    val faces = symbols.faces()
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
    // Event scripts v0: the prologue's EventScr_Prologue_* lists.
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
    // Direct-sound samples -> samples/<symbol>.wav + samples/samples.json (every WaveData header field).
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
    // UI icons: item_icon_tiles (up to item_icon_palette) -> ui/icons.png + ui/icons.json.
    val iconTiles = symbols["item_icon_tiles"].address
    val iconPal = symbols["item_icon_palette"].address
    val icons = gbafe.extract.fe8.ui.IconsExtractor.extract(rom, iconTiles - ROM_BASE, (iconPal - iconTiles) / gbafe.extract.fe8.ui.IconsExtractor.ICON_BYTES, iconPal - ROM_BASE)
    File(args.out, "ui/icons.png").apply { parentFile.mkdirs() }.writeBytes(icons.png)
    File(args.out, "ui/icons.json").writeText(icons.json)
    written += "ui/icons.png (${icons.count} icons)"
    val frames = gbafe.extract.fe8.ui.WindowFramesExtractor.extract(
        rom, symbols["sUiFrameImageLookup"].address - ROM_BASE, symbols["sUiFramePaletteLookup"].address - ROM_BASE,
        symbols["sUiFrameModelTilemapLookup"].address - ROM_BASE,
    )
    for ((n, png) in frames.files) File(args.out, "ui/$n").writeBytes(png)
    File(args.out, "ui/windowframes.json").writeText(frames.json)
    written += "ui/windowframes.json (+${frames.files.size} PNG)"
    // Trig table: gSinLookup (data/trig_rodata.s), 320 s16 in 4.12 fixed point; SIN(a) = [a & 0xFF],
    // COS(a) = [0x40 + (a & 0xFF)] (include/hardware.h), hence 256 + 64 entries.
    val sinAt = symbols["gSinLookup"].address - ROM_BASE
    val sinTable = List(320) { ((rom[sinAt + 2 * it].toInt() and 0xFF) or (rom[sinAt + 2 * it + 1].toInt() shl 8)) }
    File(args.out, "data/trig.json").writeText(gbafe.extract.Json.write(linkedMapOf("format" to "gbafe.trig", "game" to "fe8",
        "version" to 1, "fractionBits" to 12, "sin" to sinTable)))
    written += "data/trig.json"
    // Health & safety screen, src/opanim-healthsafetyscreen.c PrepareHealthAndSafetyScreen: one tile set
    // at VRAM 0, BG1 and BG0 TSAs with tile base 0, palette bank 0 (OpAnimHS_BrightenPalette(.., 0, 1, ..)).
    val hs = gbafe.extract.ScreenExtractor.extract(
        rom, "fe8", "health_safety", symbols["Img_OpAnimHealthSafetyScreen"].address - ROM_BASE,
        symbols["Pal_OpAnimHealthSafetyScreen"].address - ROM_BASE, 1,
        listOf(
            gbafe.extract.ScreenExtractor.Layer(1, symbols["Tsa_OpAnimHealthSafetyScreenBG1"].address - ROM_BASE),
            gbafe.extract.ScreenExtractor.Layer(0, symbols["Tsa_OpAnimHealthSafetyScreenBG0"].address - ROM_BASE),
        ),
    )
    File(args.out, "ui/health_safety.png").writeBytes(hs.png)
    File(args.out, "ui/health_safety.json").writeText(hs.json)
    written += "ui/health_safety.json (+PNG)"
    // Intro logos, GameIntroPrepareNintendofx: Nintendo tiles at VRAM 0 on BG0 (tile base 0, palette bank 0);
    // Intelligent Systems tiles at VRAM + 0x2000 (tile 0x100) on BG1 with tile base 0x1100 (palette banks 1..3).
    for ((n, img, pal, banks, bg, tsa, base) in listOf(
        IntroLogo("intro_nintendo", "Img_GameIntroNintendo", "Pal_GameIntroNintendo", 1, 0, "Tsa_GameIntroNintendo", 0),
        IntroLogo("intro_is", "Img_IntelligentSystems", "Pal_IntelligentSystems", 3, 1, "Tsa_IntelligentSystems", 0x1100),
    )) {
        val o = gbafe.extract.ScreenExtractor.extract(
            rom, "fe8", n, symbols[img].address - ROM_BASE, symbols[pal].address - ROM_BASE, banks,
            listOf(gbafe.extract.ScreenExtractor.Layer(bg, symbols[tsa].address - ROM_BASE, base)),
            firstTile = base and 0x3FF, firstPalette = base shr 12,
        )
        File(args.out, "ui/$n.png").writeBytes(o.png)
        File(args.out, "ui/$n.json").writeText(o.json)
        written += "ui/$n.json (+PNG)"
    }
    val cursors = gbafe.extract.fe8.ui.CursorsExtractor.extract(
        rom, symbols["gGfx_MiscUiGraphics"].address - ROM_BASE, symbols["gPal_MiscUiGraphics"].address - ROM_BASE,
        symbols["sMapCursorSpriteLut"].address - ROM_BASE, symbols["sSprite_MapCursorStretched"].address - ROM_BASE,
        symbols["gImg_UiCursorHandTop"].address - ROM_BASE, symbols["gImg_UiCursorHandBottom"].address - ROM_BASE,
        listOf("gSprite_UiCursorHand_08A20B3C", "gSprite_UiCursorHand_08A20B44").map { symbols[it].address - ROM_BASE },
    )
    for ((n, png) in cursors.files) File(args.out, "ui/$n").writeBytes(png)
    File(args.out, "ui/cursors.json").writeText(cursors.json)
    written += "ui/cursors.json (+${cursors.files.size} PNG)"
    // Boot screens read their data by ROM address (fe8.game.RomView.BOOT_WINDOWS): raw ROM windows.
    File(args.out, "boot").mkdirs()
    for ((start, end) in BOOT_WINDOWS) {
        File(args.out, "boot/rom_%08X.bin".format(start)).writeBytes(rom.copyOfRange(start - ROM_BASE, end - ROM_BASE))
    }
    written += "boot/ (${BOOT_WINDOWS.size} ROM windows)"
    // m4a sound data, read by ROM address (fe8.game.RomView.SOUND_WINDOWS).
    File(args.out, "sound").mkdirs()
    for ((start, end) in SOUND_WINDOWS) {
        File(args.out, "sound/rom_%08X.bin".format(start)).writeBytes(rom.copyOfRange(start - ROM_BASE, end - ROM_BASE))
    }
    written += "sound/ (${SOUND_WINDOWS.size} ROM window)"
    return written
}

/** ROM windows (start, end exclusive) the boot screens read; same as fe8.game.RomView.BOOT_WINDOWS. */
private val BOOT_WINDOWS = listOf(0x08590F00 to 0x08591000, 0x08AA6000 to 0x08B13000)

/** ROM window of the m4a sound data; same as fe8.game.RomView.SOUND_WINDOWS. */
private val SOUND_WINDOWS = listOf(0x08207200 to 0x08587700)

private const val ROM_BASE = 0x08000000

/** One intro logo screen: output name, ROM symbols, palette banks, BG and TSA tile base. */
private data class IntroLogo(
    val name: String, val image: String, val palette: String, val banks: Int, val bg: Int, val tsa: String, val tileBase: Int,
)
