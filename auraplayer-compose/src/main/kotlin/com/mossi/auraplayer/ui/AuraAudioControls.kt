package com.mossi.auraplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossi.auraplayer.ui.control.AuraVolumeSlider
import com.mossi.auraplayer.ui.control.PlayPause
import com.mossi.auraplayer.ui.control.TimeLabel
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun AuraAudioControls(player: AuraPlayer, modifier: Modifier = Modifier) {
    val title by player.mediaTitle.collectAsState()

    Row(modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayPause(player)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!title.isNullOrBlank()) {
                Text(title!!, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            DefaultSeekBar(player)
        }
        Spacer(Modifier.width(12.dp))
        TimeLabel(player)
        AuraVolumeSlider(player)
    }
}