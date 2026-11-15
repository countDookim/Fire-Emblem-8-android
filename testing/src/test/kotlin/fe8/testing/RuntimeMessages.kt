package fe8.testing

import gba.data.JsonReader
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The ROM's messages as extracted into the runtime `FE8_ASSETS/extracted/text/en/messages.json`
 * (CLAUDE.md rule 3: tests compare against this file instead of embedding game text).
 */
object RuntimeMessages {
    private val file = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "extracted/text/en/messages.json",
    )

    /** Message texts by id (`MSG_xxx` index); skips the calling test when the file is absent. */
    @Suppress("UNCHECKED_CAST")
    fun load(): List<String> {
        assumeTrue(file.isFile, "extracted messages.json not present (see tools/setup.sh)")
        val doc = JsonReader.parse(file.readText()) as Map<String, Any?>
        return (doc["entries"] as List<Map<String, Any?>>).map { it["text"] as String }
    }
}
