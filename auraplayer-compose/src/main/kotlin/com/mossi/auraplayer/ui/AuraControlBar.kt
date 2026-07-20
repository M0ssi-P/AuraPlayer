package com.mossi.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mossi.auraplayer.ui.control.PlayPause
import com.mossi.auraplayer.ui.control.TimeLabel
import com.mossi.auraplayer.ui.global.PopoverAnchored
import com.mossi.auraplayer.ui.logo.Fullscreen
import com.mossi.auraplayer.ui.menu.SettingsSheet
import com.mossip.auraplayer.engine.AuraPlayer
import com.mossip.auraplayer.engine.Segment

@Composable
fun AuraControlBar(
    player: AuraPlayer,
    modifier: Modifier = Modifier,
    segments: List<Segment> = emptyList(),
    seekBar: @Composable () -> Unit = { DefaultSeekBar(player, segments = segments) },
    leading: @Composable RowScope.() -> Unit = {
        PlayPause(player); Spacer(Modifier.width(8.dp)); TimeLabel(player)
    },
    trailing: @Composable RowScope.() -> Unit = {
        PopoverAnchored(
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ),
            hoverEnabled = true,
            popup = { bool ->
                SettingsSheet(player, onDismiss = { })
            }
        ) {
            IconButton(onClick = {}) {
                Icon(imageVector = Fullscreen(Color.White), "Settings", tint = Color.Unspecified)
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { player.toggleFullscreen() }) {
            Icon(imageVector = Fullscreen(Color.White), "Fullscreen", tint = Color.Unspecified)
        }
    },
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("HIIIIIIIII", color = Color.Red)
        seekBar()
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}