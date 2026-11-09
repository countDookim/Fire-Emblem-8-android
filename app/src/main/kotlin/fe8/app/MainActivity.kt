/**
 * Main Android application activity and entry point.
 *
 * Coordinates the application lifecycle, Jetpack Compose launcher UI for ROM selection and asset
 * extraction, and initialisation of the runtime game container ([Fe8Game]), audio pipeline ([AudioTrackSink]),
 * and OpenGL ES presentation view ([GameSurfaceView]).
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * Single activity hosting the game.
 *
 * Jetpack Compose is used exclusively for the launcher, status display, and ROM selection UI.
 * Once the game starts, the activity content switches to a hardware-accelerated [GameSurfaceView]
 * backed by OpenGL ES 3.0, ensuring zero Compose overhead per rendered frame.
 *
 * No retail game data is distributed with the APK. Play becomes available once a retail FE8 (U) ROM
 * is imported into internal storage, backed by the verified ROM image at `base/rom/baserom.gba`.
 */
public class MainActivity : ComponentActivity() {
    private var surface: GameSurfaceView? = null
    private var sink: AudioTrackSink? = null
    private var audio: AudioThread? = null

    /** Cartridge SRAM file storage (`fe8.sav`) preserving battery saves and suspend state across app restarts. */
    private fun saveStorage(ctx: Context): FileSaveStorage =
        FileSaveStorage(File(ctx.filesDir, SAVE_FILE_NAME))

    /**
     * Initializes the [Fe8Game] instance, connecting audio mixing pipelines and hardware renderers.
     *
     * @param ctx Android application context.
     * @param full The full cartridge ROM view loaded into memory, or `null` to use bundled windows.
     * @return Fully configured [Fe8Game] runtime.
     */
    private fun createGame(ctx: Context, full: RomView?): Fe8Game {
        val bootRom = full ?: bundledBootRom(ctx)
        val soundRom = full ?: bundledSoundRom(ctx) ?: return Fe8Game(
            rom = bootRom,
            saveStorage = saveStorage(ctx),
        )
        val sound = Fe8Sound.create(soundRom)
        val out = AudioTrackSink().also { sink = it }
        // ~150 ms of input: bounds the delay if the game clock runs slightly ahead of the device.
        val pcm = PcmStream(sound.info.pcmFreq, out.sampleRate, capacity = PCM_STREAM_CAPACITY)
        audio = AudioThread(pcm, out).also { it.start() }
        return Fe8Game(rom = bootRom, sound = sound, pcm = pcm, saveStorage = saveStorage(ctx))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var playing by remember { mutableStateOf(false) }
                    // The full ROM (one 16 MB read), loaded off the main thread before the game.
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

    override fun onPause() {
        super.onPause()
        surface?.onPause()
        audio?.stop()
    }

    override fun onDestroy() {
        audio?.stop()
        sink?.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        audio?.start()
    }

    private companion object {
        /** Name of the persistent cartridge SRAM image file in internal storage. */
        private const val SAVE_FILE_NAME = "fe8.sav"

        /** Ring buffer capacity in frames (~150 ms of buffered audio). */
        private const val PCM_STREAM_CAPACITY = 2048
    }
}

/** Resolves the sound ROM window from the base layer's `sound/` directory, or null if absent. */
private fun bundledSoundRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("sound").isEmpty()) return null
    return RomView.ofSoundWindows { name ->
        base.read("sound/$name")
    }
}

/** Resolves boot ROM windows from the base layer's `boot/` directory, or null if absent. */
private fun bundledBootRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("boot").isEmpty()) return null
    return RomView.ofBootWindows { name ->
        base.read("boot/$name")
    }
}

/**
 * Jetpack Compose launcher and settings screen shown before launching the game.
 *
 * @param onPlay Callback invoked when the user starts the game, passing the loaded ROM view.
 */
@Composable
private fun Launcher(onPlay: (RomView?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        // Every build needs an imported ROM: no APK bundles game data.
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
}
