/**
 * Android entry point: the launcher screen (import the player's ROM, delete saves, press Play)
 * and the hand-over to the running game ([Fe8Game], [AudioTrackSink], [GameSurfaceView]).
 */

package fe8.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fe8.game.Fe8Boot
import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import gba.audio.PcmStream
import gba.core.sram.FileSaveStorage
import gba.core.time.FixedStepClock
import gba.input.TouchButtons
import gba.vfs.DirectoryLayer
import gbafe.extract.RomImage
import gbafe.extract.fe8.Fe8Import
import java.io.File
import io.github.countdookim.fe8.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single activity hosting the launcher and the game.
 *
 * Compose draws only the launcher. Pressing Play swaps the content for a [GameSurfaceView] (GL
 * game picture) with a [TouchOverlayView] on top; Compose does no per-frame work for the game.
 * Lifecycle: [onPause] stops the audio thread and pauses GL rendering, [onResume] undoes both,
 * [onDestroy] releases the audio output.
 *
 * No APK bundles game data (decision 0011). Play needs the player's FE8 (U) ROM imported once
 * (see `RomImport.kt`); the verified copy kept at `base/rom/baserom.gba` is what the game boots
 * from.
 */
public class MainActivity : ComponentActivity() {
    /** The game picture; null while the launcher shows. Paused and resumed with the activity. */
    private var surface: GameSurfaceView? = null

    /** Audio output device wrapper; created with the game, released in [onDestroy]. */
    private var sink: AudioTrackSink? = null

    /** Thread feeding [sink]; null if there was no sound ROM, so the game then runs silent. */
    private var audio: AudioThread? = null

    /** The cartridge SRAM file (`fe8.sav`): all save slots and the suspend save. */
    private fun saveStorage(ctx: Context): FileSaveStorage =
        saveStorageIn(ctx.filesDir)

    /**
     * Builds the game when the player presses Play; starts its audio thread if sound data exists.
     *
     * With no [full] ROM it falls back to the boot and sound windows extracted into the base layer
     * ([importedBootRom], [importedSoundRom]); if no sound window exists the game is silent.
     *
     * @param ctx Android application context.
     * @param full The player's whole ROM read into memory, or `null` when none is kept.
     * @return The game, with sound wired to a new [AudioThread] unless no sound ROM was found.
     */
    private fun createGame(ctx: Context, full: RomView?): Fe8Game {
        val bootRom = full ?: importedBootRom(ctx)
        val soundRom = full ?: importedSoundRom(ctx) ?: return Fe8Game(
            rom = bootRom,
            saveStorage = saveStorage(ctx),
        )
        val sound = Fe8Sound.create(soundRom)
        val out = AudioTrackSink().also { sink = it }
        // Playback starts only after two VBlanks of audio are queued (2 x pcmSamplesPerVBlank
        // frames), a reserve for a game tick that runs late. Capacity only caps the queue; it does
        // not create that reserve.
        val pcm = PcmStream(
            sound.info.pcmFreq,
            out.sampleRate,
            capacity = PCM_STREAM_CAPACITY,
            prefillFrames = 2 * sound.info.pcmSamplesPerVBlank,
        )
        audio = AudioThread(pcm, out).also { it.start() }
        return Fe8Game(rom = bootRom, sound = sound, pcm = pcm, saveStorage = saveStorage(ctx))
    }

    /**
     * Shows the launcher; Play creates the game (the factory runs once, on first composition of the
     * game branch) and puts its views in a [FrameLayout]. `playing` and `full` are `remember`ed,
     * not saved: the manifest declares `configChanges`, so rotation does not recreate the activity
     * (a process death returns to the launcher).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var playing by remember { mutableStateOf(false) }
                    // The player's ROM (a 16 MB file), read off the main thread by Play.
                    var full by remember { mutableStateOf<RomView?>(null) }
                    if (playing) {
                        AndroidView(
                            factory = { ctx ->
                                val game = createGame(ctx, full)
                                val touch = TouchButtons()
                                FrameLayout(ctx).apply {
                                    addView(GameSurfaceView(ctx, game, touch, gpu = true).also { surface = it })
                                    addView(TouchOverlayView(ctx, touch))
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Launcher(onPlay = { rom ->
                            full = rom
                            playing = true
                        })
                    }
                }
            }
        }
    }

    /** Activity leaves the foreground: pause GL rendering and stop feeding audio. */
    override fun onPause() {
        super.onPause()
        surface?.onPause()
        audio?.stop()
    }

    /** Activity is going away: stop the audio thread, then free the audio output. */
    override fun onDestroy() {
        audio?.stop()
        sink?.release()
        super.onDestroy()
    }

    /** Activity is back in the foreground: resume GL rendering and restart audio. */
    override fun onResume() {
        super.onResume()
        surface?.onResume()
        audio?.start()
    }

    private companion object {
        /**
         * PCM ring size in mixer frames (a power of two, as [PcmStream] requires). The mixer
         * rate is 13379 Hz (`SOUND_MODE_FREQ_13379`, `src/m4a.c:74`), so 2048 / 13379 is
         * about 153 ms of queued audio at most.
         */
        private const val PCM_STREAM_CAPACITY = 2048
    }
}

/** File name of the cartridge SRAM image inside the app's internal `filesDir`. */
private const val SAVE_FILE_NAME = "fe8.sav"

/** The cartridge SRAM store (all save slots and the suspend save) in [filesDir]. */
internal fun saveStorageIn(filesDir: File): FileSaveStorage =
    FileSaveStorage(File(filesDir, SAVE_FILE_NAME))

/**
 * The sound-data ROM windows (`RomView.SOUND_WINDOWS`) read from the base layer's `sound/` folder,
 * or null if that folder is empty. The base layer is the folder the ROM import extracted
 * (`RomImport.kt`); nothing is read from the APK itself (decision 0011).
 */
private fun importedSoundRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("sound").isEmpty()) return null
    return RomView.ofSoundWindows { name ->
        base.read("sound/$name")
    }
}

/**
 * The boot-sequence ROM windows (`RomView.BOOT_WINDOWS`: logos, opening, title) read from the base
 * layer's `boot/` folder, or null if that folder is empty. Same source as [importedSoundRom].
 */
private fun importedBootRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("boot").isEmpty()) return null
    return RomView.ofBootWindows { name ->
        base.read("boot/$name")
    }
}

/**
 * The launcher screen shown before the game: import or re-import the ROM, delete the saves, Play.
 *
 * Play is enabled once the base layer is current (an import has completed). It reads the kept ROM
 * and calls [onPlay] with a [RomView] over it, or with null if the kept ROM is missing (an install
 * from before the ROM was kept); the game then boots only from the extracted boot windows.
 *
 * NOTE: the "N files" shown right after an import is [ImportResult.Done.count], which excludes the
 * format stamp; on later launches it is counted from the folder (minus the kept ROM image), which
 * includes the stamp file, so the two differ by one.
 *
 * @param onPlay Called when Play is pressed, with the whole-ROM view or null as above.
 */
@Composable
private fun Launcher(onPlay: (RomView?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // current: is the extracted base layer up to date? null until the first check has finished.
    var current by remember { mutableStateOf<Boolean?>(null) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var fileCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ok = baseIsCurrent(context)
            if (ok) {
                val files = DirectoryLayer(baseDir(context), "base").list("")
                fileCount = files.count { it != RomImage.PATH }
            }
            current = ok
        }
    }
    var hasSave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasSave = withContext(Dispatchers.IO) { saveStorageIn(context.filesDir).file.isFile }
    }
    // Starts true so the "predates the kept ROM image" hint does not flash before the check runs.
    var hasRom by remember { mutableStateOf(true) }
    LaunchedEffect(current, importing) {
        hasRom = withContext(Dispatchers.IO) { keptRom(context) != null }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        progress = 0f
        message = null
        scope.launch {
            when (val r = importRom(context, uri) { progress = it }) {
                is ImportResult.Done -> {
                    fileCount = r.count
                    current = true
                }
                is ImportResult.WrongRom -> message = "This is not Fire Emblem: The Sacred Stones " +
                    "(U). SHA-1 must be ${Fe8Import.ROM_SHA1.take(8)}\u2026; got ${r.sha1}."
                is ImportResult.Failed -> message = "Import failed: ${r.message}"
            }
            importing = false
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fire Emblem: The Sacred Stones — Kotlin port", style = MaterialTheme.typography.titleLarge)
        Text("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · vanilla mode · %.4f Hz logic".format(FixedStepClock.TICK_HZ))
        if (importing) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Importing ROM… ${(progress * 100).toInt()}%")
        } else if (current == true) {
            Text("Game data imported: ${fileCount ?: "…"} files")
        } else if (current == false) {
            Text("Choose your Fire Emblem: The Sacred Stones (U) ROM to import it. It stays on this device.")
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import ROM") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (current == true && !importing) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Re-import ROM") }
        }
        OutlinedButton(onClick = { confirmDelete = true }, enabled = hasSave && !importing) {
            Text("Delete save")
        }
        // Every build needs an imported ROM: no APK bundles game data (decision 0011).
        val canPlay = current == true
        if (current == true && !hasRom) {
            Text(
                "Import your ROM to play past the title " +
                    "(this install predates the kept ROM image).",
            )
        }
        Button(
            onClick = {
                scope.launch {
                    val rom = withContext(Dispatchers.IO) {
                        keptRom(context)?.let { RomView.ofRom(it.readBytes()) }
                    }
                    if (rom == null) Log.i("Fe8", "Import your ROM to play past the title")
                    onPlay(rom)
                }
            },
            enabled = canPlay && !importing,
        ) { Text("Play") }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete save data?") },
            text = {
                Text(
                    "All saves and the suspend are deleted. The imported ROM is kept. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) { saveStorageIn(context.filesDir).delete() }
                            hasSave = false
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
