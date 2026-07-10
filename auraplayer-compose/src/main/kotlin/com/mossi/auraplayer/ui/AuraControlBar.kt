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
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mossi.auraplayer.ui.control.PlayPause
import com.mossi.auraplayer.ui.control.TimeLabel
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
        IconButton(onClick = { player.toggleFullscreen() }) {
//            Icon(painterResource("icons/fullscreen.svg"), "Fullscreen", tint = Color.White)
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