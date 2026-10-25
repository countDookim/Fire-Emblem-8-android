package fe8.app

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import gbafe.core.time.FixedStepClock
import fe8.game.Fe8Game
import io.github.countdookim.fe8.BuildConfig

/**
 * Single activity. Compose is used for the launcher/settings shell only; once the game starts the
 * content is a [GameSurfaceView] (OpenGL ES 3.0) and Compose does no per-frame work.
 *
 * Milestone-1 state: the game view shows the placeholder pattern produced by [Fe8Game.present] to prove
 * the GL path, scaling shader and fixed-rate loop work on a device.
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
                            factory = { ctx -> GameSurfaceView(ctx, Fe8Game()).also { surface = it } },
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

@Composable
private fun Launcher(onPlay: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fire Emblem: The Sacred Stones — Kotlin port", style = MaterialTheme.typography.titleLarge)
        Text("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · vanilla mode · %.4f Hz logic".format(FixedStepClock.TICK_HZ))
        Text("Milestone 1: engine skeleton. No gameplay yet — Play shows a GL test pattern.")
        Button(onClick = onPlay) { Text("Play (test pattern)") }
    }
}
