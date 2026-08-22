package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mossip.auraplayer.engine.AuraPlayer

/**
 * The two platforms cannot share one implementation:
 *
 *  - Windows: video is rendered into a shared D3D11/D3D12 texture and drawn
 *    as ordinary Skia pixels inside a Compose Canvas. It fully participates
 *    in Compose z-order, so modals/overlays "just work" over it.
 *
 *  - macOS: video is a real AWT Canvas positioned as a native window layer
 *    (JAWT), composited by the OS window server underneath/above Skiko via
 *    enableUnderlay(). Compose never touches its pixels. This is the
 *    existing AuraHostState/AuraSurfaceEntry machinery — unchanged here.
 *
 * AuraPlayerSurface picks one at composition time; call sites never branch
 * on OS themselves.
 */

internal interface VideoSurfaceStrategy {
    @Composable
    fun Render(
        player: AuraPlayer,
        modifier: Modifier,
        controls: @Composable BoxScope.() -> Unit,
    )
}

internal val isWindows: Boolean =
    System.getProperty("os.name").lowercase().startsWith("win")

internal val isMac: Boolean =
    System.getProperty("os.name").lowercase().startsWith("mac")

internal fun platformStrategy(): VideoSurfaceStrategy =
    if (isWindows) WindowsSkiaSurfaceStrategy
    else MacSkiaSurfaceStrategy