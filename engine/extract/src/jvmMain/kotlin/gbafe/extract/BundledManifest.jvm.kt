package gbafe.extract

// Only APIs available on Android too: the android target compiles this source set as well.
internal actual fun readBundledManifest(game: String): String? =
    ManifestSources::class.java.getResource("/gbafe/extract/manifest/$game.json")?.readText()
