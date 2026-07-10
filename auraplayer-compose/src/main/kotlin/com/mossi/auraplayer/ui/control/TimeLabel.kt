package com.mossi.auraplayer.ui.control

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.mossip.auraplayer.engine.AuraPlayer
import kotlin.math.roundToInt

@Composable
fun TimeLabel(player: AuraPlayer, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val t by player.currentTime.collectAsState()
    val d by player.duration.collectAsState()
    Text(
        "${fmt(t)} / ${fmt(d)}",
        modifier = modifier,
        color = tint.copy(alpha = 0.85f),
        fontSize = 12.sp,
    )
}

private fun fmt(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val total = seconds.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}