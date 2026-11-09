package com.physicalactivitygate

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Watercolor palette: soft teal, rose, ochre and lavender washes on warm
// paper (light) or deep night-ink paper (dark). Text stays dark/light enough
// for high contrast; the painterly feel lives in the backgrounds and cards.

private val PagPrimary = Color(0xFF2F8087)      // deep teal wash
private val PagWarning = Color(0xFFE0894F)      // warm ochre/coral
private val PagError = Color(0xFFD9576A)        // dusty rose-red

val PagLightColors = lightColorScheme(
    primary = PagPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBE8),
    onPrimaryContainer = Color(0xFF123F43),
    secondary = PagWarning,
    onSecondary = Color.White,
    background = Color(0xFFFBF6EC),
    onBackground = Color(0xFF2B3A42),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF2B3A42),
    surfaceVariant = Color(0xFFF6E7D3),
    onSurfaceVariant = Color(0xFF62707A),
    outline = Color(0xFFE2D5C2),
    error = PagError,
    onError = Color.White,
    errorContainer = Color(0xFFFBDDE0),
    onErrorContainer = Color(0xFF6E1F2C)
)

val PagDarkColors = darkColorScheme(
    primary = Color(0xFF8CCFC9),
    onPrimary = Color(0xFF0B2F33),
    primaryContainer = Color(0xFF244B50),
    onPrimaryContainer = Color(0xFFD3EBE8),
    secondary = Color(0xFFF0A670),
    onSecondary = Color(0xFF2A1405),
    background = Color(0xFF14222B),
    onBackground = Color(0xFFF1EBDD),
    surface = Color(0xFF1E303B),
    onSurface = Color(0xFFF1EBDD),
    surfaceVariant = Color(0xFF2B4150),
    onSurfaceVariant = Color(0xFFB9C4CA),
    outline = Color(0xFF3B5160),
    error = Color(0xFFF08A98),
    onError = Color(0xFF3A0A14),
    errorContainer = Color(0xFF6E1F2C),
    onErrorContainer = Color(0xFFFBDDE0)
)

/** Colors that mean the same thing regardless of light/dark theme (status,
 * not surface) — used sparingly for blocked/warning states. */
val PagWarningColor = PagWarning
val PagErrorColor = PagError

private val Display = FontFamily.Serif

val PagTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Display, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleSmall = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
)
