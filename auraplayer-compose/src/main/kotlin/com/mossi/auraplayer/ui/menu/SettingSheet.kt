package com.mossi.auraplayer.ui.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mossip.auraplayer.engine.AuraPlayer

@Composable
fun SettingsSheet(player: AuraPlayer, onDismiss: () -> Unit) {
    val stack = remember { mutableStateListOf<MenuPage>(MenuPage.Root) }
    val current = stack.last()
    // depth drives direction: deeper = push (slide left), shallower = pop
    var depth by remember { mutableIntStateOf(0) }

    Surface(
        color = Color(0xF01F1F1F),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(min = 220.dp)   // let content decide the rest
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val forward = stack.size > depth
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(220)) { w -> dir * w } + fadeIn(tween(180)))
                    .togetherWith(
                        slideOutHorizontally(tween(220)) { w -> -dir * w } + fadeOut(tween(120))
                    )
                    .using(
                        // THIS is the container morph: box grows/shrinks to the new page
                        SizeTransform(clip = true) { _, _ -> tween(260) }
                    )
            },
            label = "settings-nav"
        ) { page ->
            when (page) {
                MenuPage.Root -> RootPage(
                    player,
                    onNavigate = { depth = stack.size; stack.add(it) }
                )
                MenuPage.Subtitles -> SubPage("Subtitles/CC", onBack = { depth = stack.size; stack.removeLast() }) {
                    SubtitleOptions(player)
                }
                MenuPage.Speed -> SubPage("Playback speed", onBack = { depth = stack.size; stack.removeLast() }) {
                    SpeedOptions(player)
                }
                MenuPage.Quality -> SubPage("Quality", onBack = { depth = stack.size; stack.removeLast() }) {
//                    QualityOptions(player)
                }
                MenuPage.AudioDevice -> SubPage("Audio devices", onBack = { depth = stack.size; stack.removeLast() }) {

                }
            }
        }
    }
}