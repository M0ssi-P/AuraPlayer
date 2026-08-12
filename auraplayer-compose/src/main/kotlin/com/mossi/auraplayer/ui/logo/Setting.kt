package com.composables

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val _settings = mutableMapOf<Color, ImageVector>()

fun SettingsIcon(strokeColor: Color): ImageVector {
    return _settings.getOrPut(strokeColor) {
        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(strokeColor)
            ) {
            }
            group {
                path(
                    stroke = SolidColor(strokeColor),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathFillType = PathFillType.EvenOdd
                ) {
                }
            }
        }.build()
    }
}

