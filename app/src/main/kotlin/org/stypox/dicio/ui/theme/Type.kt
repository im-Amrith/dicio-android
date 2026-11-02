package org.stypox.dicio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two voices, two faces: Jarvis speaks in a serif (headlines and answers), the interface and the
 * user's own words are set in a plain sans.
 */
private val JarvisVoice = FontFamily.Serif
private val Interface = FontFamily.SansSerif

private val base = Typography()

val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = JarvisVoice, fontWeight = FontWeight.Normal),
    headlineLarge = base.headlineLarge.copy(fontFamily = JarvisVoice, fontWeight = FontWeight.Normal),
    headlineMedium = base.headlineMedium.copy(fontFamily = JarvisVoice, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = JarvisVoice, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = base.titleLarge.copy(fontFamily = JarvisVoice, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontFamily = Interface, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = Interface),
    bodyLarge = base.bodyLarge.copy(fontFamily = Interface, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Interface, lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Interface),
    labelLarge = base.labelLarge.copy(fontFamily = Interface, letterSpacing = 0.2.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Interface),
    labelSmall = base.labelSmall.copy(fontFamily = Interface),
)
