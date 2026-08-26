package com.finnvek.startex.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class StartExColorContrastTest {
    private val surfaces = listOf(StartExBackground, StartExSurface, StartExSurfaceRaised)
    private val textColors = listOf(StartExText, StartExMuted, StartExGreen, StartExRed, StartExAmber, StartExBlue)

    @Test
    fun `semantic text colors meet normal text contrast on every app surface`() {
        textColors.forEach { foreground ->
            surfaces.forEach { background ->
                assertContrastAtLeast(foreground, background, 4.5f)
            }
        }
    }

    @Test
    fun `status pill text meets normal text contrast on its tinted surface`() {
        textColors.drop(1).forEach { foreground ->
            surfaces.forEach { background ->
                val tintedSurface = foreground.copy(alpha = 0.13f).compositeOver(background)
                assertContrastAtLeast(foreground, tintedSurface, 4.5f)
            }
        }
    }

    @Test
    fun `control outline meets non-text contrast on every app surface`() {
        surfaces.forEach { background ->
            assertContrastAtLeast(StartExControlOutline, background, 3f)
        }
    }

    private fun assertContrastAtLeast(
        foreground: Color,
        background: Color,
        minimum: Float,
    ) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("Expected $minimum:1 contrast, measured $ratio:1", ratio >= minimum)
    }
}
