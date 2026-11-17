package com.qzkt.timetable.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme

@Composable
fun LiquidGlassBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val base = if (dark) Color(0xFF0A101A) else Color(0xFFF2F6FC)

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = if (dark) {
                    listOf(Color(0xFF0B111C), Color(0xFF101A29), Color(0xFF0B1018))
                } else {
                    listOf(Color(0xFFF8FAFF), Color(0xFFEAF1FC), Color(0xFFF5F7FC))
                },
            ),
        )
        val firstCenter = Offset(size.width * 0.08f, size.height * 0.12f)
        val secondCenter = Offset(size.width * 0.94f, size.height * 0.48f)
        val thirdCenter = Offset(size.width * 0.34f, size.height * 0.96f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = if (dark) 0.22f else 0.18f), base.copy(alpha = 0f)),
                center = firstCenter,
                radius = size.minDimension * 0.72f,
            ),
            radius = size.minDimension * 0.72f,
            center = firstCenter,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = if (dark) 0.19f else 0.16f), base.copy(alpha = 0f)),
                center = secondCenter,
                radius = size.minDimension * 0.66f,
            ),
            radius = size.minDimension * 0.66f,
            center = secondCenter,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = if (dark) 0.12f else 0.10f), base.copy(alpha = 0f)),
                center = thirdCenter,
                radius = size.minDimension * 0.72f,
            ),
            radius = size.minDimension * 0.72f,
            center = thirdCenter,
        )
    }
}
