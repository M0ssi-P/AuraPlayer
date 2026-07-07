import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.onClick
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mossi.auraplayer.ui.AuraPlayerSurface
import com.mossip.auraplayer.engine.AuraPlayer
import java.awt.Canvas
import javax.swing.JFrame

@OptIn(ExperimentalFoundationApi::class)
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
        ) {

            AuraPlayerSurface(
                engine,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}