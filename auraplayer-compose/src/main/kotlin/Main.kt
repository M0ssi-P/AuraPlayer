import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mossi.auraplayer.ui.AuraPlayerSurface
import com.mossip.auraplayer.engine.AuraPlayer

fun main() {
    application {
        val player = remember { AuraPlayer() }
        val playerState = player.playerState.collectAsState()
        val duration = player.duration.collectAsState()
        val current = player.currentTime.collectAsState()

        LaunchedEffect(duration.value) {
            println(duration.value)
        }

        LaunchedEffect(playerState.value) {
            println(playerState.value)
        }

        Window(onCloseRequest = {
            player.release()
            exitApplication()
        }) {
            AuraPlayerSurface(
                player
            )
        }
    }
}