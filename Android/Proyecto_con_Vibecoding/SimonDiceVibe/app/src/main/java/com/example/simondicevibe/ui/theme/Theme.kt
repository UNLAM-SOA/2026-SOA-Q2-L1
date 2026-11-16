package com.example.simondicevibe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.simondicevibe.R
import com.example.simondicevibe.domain.Pad

// ── Palette ──────────────────────────────────────────────────────────────────
val Night = Color(0xFF0B0720)
val Indigo = Color(0xFF1B0F4D)
val DeepBlue = Color(0xFF0E2A4F)
val Violet = Color(0xFF8B7BFF)
val Magenta = Color(0xFFD35CFF)

val PadRed = Color(0xFFFF4D6D)
val PadBlue = Color(0xFF4D9DFF)
val PadGreen = Color(0xFF3DDC97)
val PadGold = Color(0xFFFFC857)

val TextPrimary = Color(0xFFF4F1FF)
val TextMuted = Color(0xFFA9A3C9)
val GlassBorder = Color.White.copy(alpha = 0.14f)
val Danger = Color(0xFFFF5A7A)
val Warning = Color(0xFFFFB347)

fun Pad.color(): Color = when (this) {
    Pad.ROJO -> PadRed
    Pad.AZUL -> PadBlue
    Pad.VERDE -> PadGreen
    Pad.SENSOR -> PadGold
}

// ── Typography ───────────────────────────────────────────────────────────────
val Bungee = FontFamily(Font(R.font.bungee_regular))
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Bungee, fontSize = 44.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = Bungee, fontSize = 34.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Bungee, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

private val DarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Night,
    secondary = PadGreen,
    tertiary = PadGold,
    background = Night,
    onBackground = TextPrimary,
    surface = Color(0xFF1A1340),
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF241B55),
    onSurfaceVariant = TextMuted,
    error = Danger,
    outline = GlassBorder,
)

/** The game is always dark: the neon pads only pop on a deep background. */
@Composable
fun SimonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
