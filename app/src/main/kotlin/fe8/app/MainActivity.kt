package fe8.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import gbafe.core.time.FixedStepClock
import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.Fe8Game
import io.github.countdookim.fe8.BuildConfig

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var playing by remember { mutableStateOf(false) }
                    if (playing) {
                        AndroidView(
                            factory = { ctx ->
                                val game = Fe8Game(rom = bundledBootRom(ctx))
                                GameSurfaceView(ctx, game).also { surface = it }
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
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
    }
}

/** Boot ROM windows from the APK's bundled `extracted/boot/` (debug/test builds only), or null. */
private fun bundledBootRom(ctx: Context): RomView? {
    if (ctx.assets.list("extracted/boot").isNullOrEmpty()) return null
    return RomView.ofBootWindows { name ->
        ctx.assets.open("extracted/boot/$name").use { it.readBytes() }
    }
}

@Composable
private fun Launcher(onPlay: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fire Emblem: The Sacred Stones — Kotlin port", style = MaterialTheme.typography.titleLarge)
        Text("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · vanilla mode · %.4f Hz logic".format(FixedStepClock.TICK_HZ))
        Text("Debug builds: Play runs the boot screens. Without bundled assets: a GL test pattern.")
        val context = LocalContext.current
        val bundled = remember { AssetLayer(context.assets).list("").size }
        Text("Bundled test assets (debug builds only): $bundled files")
        Button(onClick = onPlay) { Text("Play") }
    }
}
