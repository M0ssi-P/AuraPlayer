import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mossi.auraplayer.ui.AuraAudioSurface
import com.mossi.auraplayer.ui.AuraPlayerHost
import com.mossi.auraplayer.ui.AuraPlayerSurface
import com.mossi.auraplayer.ui.LocalPlayerBounds
import com.mossip.auraplayer.engine.AuraPlayer

fun main() {
    application {
//        val engine = remember { AuraPlayer() }
        val state = rememberWindowState()
        var playerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
//        val  hasPlayerInitialized by engine.isInitialized.collectAsState()
//        val isFullscreen by engine.fullscreen.collectAsState()

        // HLS URL to test out - https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
        // YT URL to test out https://www.youtube.com/watch?v=866LIJWme2M&list=RD866LIJWme2M&start_radio=1
//        http://sample.vodobox.net/skate_phantom_flex_4k/skate_phantom_flex_4k.m3u8

//        LaunchedEffect(hasPlayerInitialized) {
//            if(hasPlayerInitialized) {
//                engine.loadFile("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
//        https://pub-0400bba999e94d4da2149a2f1d2d06a6.r2.dev/hls/master.m3u8
//            }
//        }

        Window(
            onCloseRequest = {
                exitApplication()
            },
            state = state,
        ) {
            val player = remember { AuraPlayer() }

            LaunchedEffect(player) {
                player.loadWhenReady("https://d21kl6o5a7faj0.cloudfront.net/repo/f9c/f9cc3d4b192f7362cf697731b38ad4c9cd4bbb708df1f834a1203c190dc42f3da3ac36e691731b2ca19a46344305e631f1647e299001b10835ab4c131b278ae6.m4a")
            }

            AuraPlayerHost(window) {
                Column(Modifier.fillMaxSize()) {
                    // 3. Nest the surface anywhere, any depth. First composition
                    //    attaches the native surface and initializes mpv.
                    CompositionLocalProvider(LocalPlayerBounds provides playerCoords) {
                        AuraAudioSurface(
                            player,
                            modifier = Modifier.onGloballyPositioned { playerCoords = it }.aspectRatio(16f / 9f)
                                .align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        }
    }
}