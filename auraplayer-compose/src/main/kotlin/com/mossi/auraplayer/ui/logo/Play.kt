package com.mossi.auraplayer.ui.logo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val _play = mutableMapOf<Color, ImageVector>()

fun Play(color: Color): ImageVector {
    return _play.getOrPut(color) {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Unspecified),
            ) {
                moveTo(0f, 0f)
                horizontalLineToRelative(24f)
                verticalLineToRelative(24f)
                horizontalLineTo(0f)
                close()
            }
            path(
                fill = SolidColor(color)
            ) {
                moveTo(6f, 4f)
                verticalLineToRelative(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1.524f, 0.852f)
                lineToRelative(13f, -8f)
                arcToRelative(1f, 1f, 0f, false, false, 0f, -1.704f)
                lineToRelative(-13f, -8f)
                arcToRelative(1f, 1f, 0f, false, false, -1.524f, 0.852f)
                close()
            }
        }.build()
    }
}