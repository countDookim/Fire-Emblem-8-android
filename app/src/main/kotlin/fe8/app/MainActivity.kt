package fe8.app

import android.content.Context
import android.os.Bundle
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
import androidx.compose.runtime.produceState
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
import gba.core.time.FixedStepClock
import gba.input.TouchButtons
import gba.vfs.DirectoryLayer
import gbafe.extract.fe8.Fe8Import
import io.github.countdookim.fe8.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single activity. Compose is used for the launcher/settings shell only; once the game starts the
 * content is a [GameSurfaceView] (OpenGL ES 3.0) and Compose does no per-frame work.
 *
 * Debug/test APKs bundle the extracted assets (`assets/extracted/`): the game view then runs
 * the boot sequence ([Fe8Boot]). Without them (release builds until ROM import exists) it shows
 * the placeholder pattern of [Fe8Game.present].
 */
public class MainActivity : ComponentActivity() {
    private var surface: GameSurfaceView? = null
    private var sink: AudioTrackSink? = null
    private var audio: AudioThread? = null

    /**
     * Starts the game (boot sequence, GPU renderer) with its sound: the game loop mixes each frame
     * into a [PcmStream] that the audio thread plays (debug builds with the bundled sound window).
     */
    private fun createGame(ctx: Context): Fe8Game {
        val soundRom = bundledSoundRom(ctx) ?: return Fe8Game(rom = bundledBootRom(ctx))
        val sound = Fe8Sound.create(soundRom)
        val out = AudioTrackSink().also { sink = it }
        // ~150 ms of input: bounds the delay if the game clock runs slightly ahead of the device.
        val pcm = PcmStream(sound.info.pcmFreq, out.sampleRate, capacity = 2048)
        audio = AudioThread(pcm, out).also { it.start() }
        return Fe8Game(rom = bundledBootRom(ctx), sound = sound, pcm = pcm)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var playing by remember { mutableStateOf(false) }
                    if (playing) {
                        AndroidView(
                            factory = { ctx ->
                                val game = createGame(ctx)
                                val touch = TouchButtons()
                                FrameLayout(ctx).apply {
                                    addView(GameSurfaceView(ctx, game, touch, gpu = true).also { surface = it })
                                    addView(TouchOverlayView(ctx, touch))
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Launcher(onPlay = { playing = true })
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
}

/** The sound ROM window from the base layer's `sound/` (imported ROM, else bundled assets), or null. */
private fun bundledSoundRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("sound").isEmpty()) return null
    return RomView.ofSoundWindows { name ->
        base.open("sound/$name").use { it.readBytes() }
    }
}

/** Boot ROM windows from the base layer's `boot/` (imported ROM, else bundled assets), or null. */
private fun bundledBootRom(ctx: Context): RomView? {
    val base = baseLayer(ctx)
    if (base.list("boot").isEmpty()) return null
    return RomView.ofBootWindows { name ->
        base.open("boot/$name").use { it.readBytes() }
    }
}

@Composable
private fun Launcher(onPlay: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Boolean?>(null) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var fileCount by remember { mutableStateOf<Int?>(null) }
    // Counting walks thousands of APK asset entries: off the main thread, so launch isn't blocked.
    val bundled by produceState<Int?>(null) {
        value = withContext(Dispatchers.IO) { AssetLayer(context.assets).list("").size }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ok = baseIsCurrent(context)
            if (ok) fileCount = DirectoryLayer(baseDir(context), "base").list("").size
            current = ok
        }
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
        Text("Bundled test assets (debug builds only): ${bundled ?: "counting…"} files")
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
        // Debug builds bundle assets and can play without importing.
        val canPlay = current == true || (bundled ?: 0) > 0
        Button(onClick = onPlay, enabled = canPlay && !importing) { Text("Play") }
    }
}
