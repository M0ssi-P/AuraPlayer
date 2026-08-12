package com.mossi.auraplayer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossi.auraplayer.ui.logo.Check
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun SubtitleOptions(player: AuraPlayer) {
    val tracks by player.tracks.collectAsState()
    val currentTrack by player.currentTrack.collectAsState()
    val subs = tracks.filter { it.type == "sub" }
    val currentSub = currentTrack.find { it.type == "sub" }
    Column(modifier = Modifier.fillMaxWidth()) {
        OptionRow("Off", selected = false) { player.setTrack("sid", "no") }
        subs.forEach { t ->
            OptionRow(t.title.ifBlank { t.lang ?: "Track ${t.id}" }, selected = currentSub?.id == t.id) {
                player.setTrack("sid", t.id)
            }
        }
    }
}

data class SpeedOption(
    val value: Double,
    val label: String,
    val caption: String? = null,
    val premium: Boolean = false,
)

private val speedOptions = listOf(
    SpeedOption(1.0, "1.0", caption = "Normal"),
    SpeedOption(1.25, "1.25"),
    SpeedOption(1.5, "1.5"),
    SpeedOption(2.0, "2.0"),
    SpeedOption(3.0, "3.0", premium = true),
)

@Composable
fun SpeedOptions(player: AuraPlayer) {
    val current by player.speed.collectAsState()
    Column {
        Spacer(Modifier.height(30.dp))

        Text(
            text = "${"%.2f".format(current)}x",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { player.setSpeed((current.toFloat() - 0.25f).coerceAtLeast(0.25f).toDouble()) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("−", color = Color.White, fontSize = 20.sp)
            }

            Slider(
                value = current.toFloat(),
                onValueChange = {
                    player.setSpeed(it.toDouble())
                },
                valueRange = 0.25f..3.0f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                )
            )

            IconButton(
                onClick = { player.setSpeed((current.toFloat() + 0.25f).coerceAtMost(3.0f).toDouble()) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("+", color = Color.White, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            speedOptions.forEach { option ->
                SpeedChip(
                    option = option,
                    selected = current == option.value,
                    onClick = { player.setSpeed(option.value) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SpeedChip(
    option: SpeedOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .clickable(onClick = onClick)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(option.label, color = Color.White, fontSize = 13.sp)
            }

            if (option.premium) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE53935)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("P", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = option.caption.orEmpty(),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
        )
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
fun QualityOptions(player: AuraPlayer) {
    val quality by player.tracks.collectAsState()
    val currentTrack by player.currentTrack.collectAsState()
    val vids = quality.filter { it.type == "video" }
    val currentQuality = currentTrack.find { it.type == "video" }

    Column {
        OptionRow("Auto", selected = currentQuality == null) { player.setTrack("video", -1) }
        vids.forEach { d ->
            OptionRow("${d.height}p", selected = currentQuality?.id == d.id) { player.setTrack("video", d.id) }
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