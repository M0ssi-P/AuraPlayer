import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mossi.auraplayer.ui.AuraControlBar
import com.mossi.auraplayer.ui.AuraPlayerHost
import com.mossi.auraplayer.ui.AuraPlayerSurface
import com.mossip.auraplayer.engine.AuraPlayer

fun main() {
    application {
        val engine = remember { AuraPlayer() }
        val state = rememberWindowState()
        val  hasPlayerInitialized by engine.isInitialized.collectAsState()

        LaunchedEffect(hasPlayerInitialized) {
            if(hasPlayerInitialized) {
                engine.loadFile("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
            }
        }

        Window(
            onCloseRequest = {
                engine.release()
                exitApplication()
            },
            state = state,
            onPreviewKeyEvent = { e ->    // Preview: works even when Canvas has focus
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.F11 -> {
                        engine.toggleFullscreen(); true
                    }
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape &&
                            engine.fullscreen.value -> {
                        engine.exitFullscreen(); true
                    }
                    else -> false
                }
            }
        ) {
            AuraPlayerHost(window) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    // 3. Nest the surface anywhere, any depth. First composition
                    //    attaches the native surface and initializes mpv.
                    AuraPlayerSurface(
                        engine,
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .align(Alignment.CenterHorizontally),
                        controls = {
                            AuraControlBar(engine)
                        }
                    )
                }
            }
        }
    }
}