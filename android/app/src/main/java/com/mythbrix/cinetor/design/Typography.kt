package com.mythbrix.cinetor.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** System sans with a confident weight ramp — no bundled/downloaded fonts (APK stays lean). */
private val Base = Typography()

private fun TextStyle.w(weight: FontWeight) = copy(fontFamily = FontFamily.Default, fontWeight = weight)

val CinetorTypography: Typography = Typography(
    displayLarge = Base.displayLarge.w(FontWeight.ExtraBold),
    displayMedium = Base.displayMedium.w(FontWeight.ExtraBold),
    displaySmall = Base.displaySmall.w(FontWeight.Bold),
    headlineLarge = Base.headlineLarge.w(FontWeight.Bold),
    headlineMedium = Base.headlineMedium.w(FontWeight.Bold),
    headlineSmall = Base.headlineSmall.w(FontWeight.Bold),
    titleLarge = Base.titleLarge.w(FontWeight.Bold),
    titleMedium = Base.titleMedium.w(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.w(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.w(FontWeight.Normal),
    bodyMedium = Base.bodyMedium.w(FontWeight.Normal),
    bodySmall = Base.bodySmall.w(FontWeight.Normal),
    labelLarge = Base.labelLarge.w(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.w(FontWeight.SemiBold),
    labelSmall = Base.labelSmall.w(FontWeight.Medium),
)

/** Wide-tracked brand wordmark ("CINETOR"). */
val WordmarkStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Black,
    fontSize = 28.sp,
    letterSpacing = 0.32.em,
)
