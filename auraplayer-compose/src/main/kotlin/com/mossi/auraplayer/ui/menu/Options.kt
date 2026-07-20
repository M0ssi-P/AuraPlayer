package com.mossi.auraplayer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossi.auraplayer.ui.logo.Check
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun SubtitleOptions(player: AuraPlayer) {
    val tracks by player.tracks.collectAsState()
    val subs = tracks.filter { it.type == "sub" }
    Column(modifier = Modifier.fillMaxWidth()) {
        OptionRow("Off", selected = false) { player.setTrack("sid", "no") }
        subs.forEach { t ->
            OptionRow(t.title.ifBlank { t.lang ?: "Track ${t.id}" }, selected = false) {
                player.setTrack("sid", t.id)
            }
        }
    }
}

@Composable
fun SpeedOptions(player: AuraPlayer) {
    val current by player.speed.collectAsState()
    Column {
        listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).forEach { s ->
            OptionRow(if (s == 1.0) "Normal" else "${s}×", selected = current == s) {
                player.setSpeed(s)
            }
        }
    }
}

@Composable
fun AudioDeviceOptions(player: AuraPlayer) {
    // Enumerated once when the page opens; devices change rarely.
    val devices = remember { player.audioDevices() }
    Column {
        devices.forEach { d ->
            OptionRow(d.description, selected = false) { player.setAudioDevice(d) }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(
                Check(Color.White), null, tint = Color.Unspecified,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}