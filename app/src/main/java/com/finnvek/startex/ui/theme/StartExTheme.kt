package com.finnvek.startex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val StartExBackground = Color(0xFF090A0C)
val StartExSurface = Color(0xFF121519)
val StartExSurfaceRaised = Color(0xFF191D22)
val StartExOutline = Color(0xFF2A3038)
val StartExText = Color(0xFFF3F5F7)
val StartExMuted = Color(0xFFA9B1BB)
val StartExGreen = Color(0xFF48D98A)
val StartExRed = Color(0xFFFF6B73)
val StartExAmber = Color(0xFFF2B84B)
val StartExBlue = Color(0xFF6CA6FF)
internal val StartExControlOutline = Color(0xFF707A86)

private val StartExColors =
    darkColorScheme(
        primary = StartExGreen,
        onPrimary = Color(0xFF00210F),
        secondary = StartExBlue,
        onSecondary = Color(0xFF001B3E),
        secondaryContainer = StartExSurfaceRaised,
        onSecondaryContainer = StartExBlue,
        tertiary = StartExAmber,
        error = StartExRed,
        background = StartExBackground,
        onBackground = StartExText,
        surface = StartExSurface,
        onSurface = StartExText,
        surfaceVariant = StartExSurfaceRaised,
        onSurfaceVariant = StartExMuted,
        outline = StartExControlOutline,
    )

private val StartExTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.ContentOrLtr,
            ),
        headlineSmall =
            TextStyle(
                fontSize = 23.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                textDirection = TextDirection.ContentOrLtr,
            ),
        titleLarge =
            TextStyle(
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        titleMedium =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        bodyLarge =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        bodyMedium =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        bodySmall =
            TextStyle(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        labelLarge =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
        labelMedium =
            TextStyle(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.ContentOrLtr,
            ),
    )

private val StartExShapes =
    Shapes(
        small =
            androidx.compose.foundation.shape
                .RoundedCornerShape(8.dp),
        medium =
            androidx.compose.foundation.shape
                .RoundedCornerShape(12.dp),
        large =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
    )

@Composable
fun StartExTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StartExColors,
        typography = StartExTypography,
        shapes = StartExShapes,
        content = content,
    )
}
