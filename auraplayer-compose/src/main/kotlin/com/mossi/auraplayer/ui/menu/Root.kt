package com.mossi.auraplayer.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer

sealed interface MenuPage {
    data object Root : MenuPage
    data object Subtitles : MenuPage
    data object Speed : MenuPage
    data object Quality : MenuPage
    data object AudioDevice : MenuPage
}

@Composable
fun RootPage(player: AuraPlayer, onNavigate: (MenuPage) -> Unit) {
    val speed by player.speed.collectAsState()
    Column(Modifier.width(400.dp).padding(vertical = 8.dp)) {
        MenuRow("icons/caption.svg", "Subtitles/CC", "English",
            onClick = { onNavigate(MenuPage.Subtitles) })
        MenuRow("icons/speed.svg", "Playback speed",
            if (speed == 1.0) "Normal" else "${speed}x",
            onClick = { onNavigate(MenuPage.Speed) })
        MenuRow("icons/quality.svg", "Quality", "Auto (480p)",
            onClick = { onNavigate(MenuPage.Quality) })
        MenuRow("icons/speed.svg", "Audio devices",
            if (speed == 1.0) "Normal" else "${speed}x",
            onClick = { onNavigate(MenuPage.AudioDevice) })
    }
}

@Composable
private fun MenuRow(icon: String, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
//        Icon(painterResource(icon), null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Color.White.copy(alpha = 0.6f)) }
        Icon(painterResource("icons/right.svg"), null, tint = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
//            Icon(painterResource("icons/left.svg"), null, tint = Color.White)
            Spacer(Modifier.width(16.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
        }
        Divider(color = Color.White.copy(alpha = 0.1f))
        content()
    }
}