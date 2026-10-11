package com.goldorb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.goldorb.core.Dot

object Palette {
    val Background = Color(0xFF0E1116)
    val Card = Color(0xFF171B22)
    val Line = Color(0xFF262C36)
    val Text = Color(0xFFE6E9EF)
    val Muted = Color(0xFF8A93A3)
    val Gold = Color(0xFFF5B942)
    val Green = Color(0xFF22C55E)
    val LightGreen = Color(0xFF86EFAC)
    val Orange = Color(0xFFF59E0B)
    val LightRed = Color(0xFFFCA5A5)
    val Red = Color(0xFFEF4444)
    val Purple = Color(0xFFA78BFA)
    val Grey = Color(0xFF6B7280)
    val ValueArea = Color(0xFF60A5FA)
    val MarkerYellow = Color(0x80FDE047)

    fun of(dot: Dot): Color = when (dot) {
        Dot.GREEN -> Green
        Dot.LIGHT_GREEN -> LightGreen
        Dot.ORANGE -> Orange
        Dot.LIGHT_RED -> LightRed
        Dot.RED -> Red
        Dot.PURPLE -> Purple
        Dot.GREY -> Grey
    }
}

@Composable
fun GoldOrbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Gold,
            onPrimary = Color.Black,
            secondary = Palette.Gold,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Background,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Card,
            onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Card,
            surfaceContainerHigh = Palette.Card,
            outline = Palette.Line,
        ),
        content = content,
    )
}
