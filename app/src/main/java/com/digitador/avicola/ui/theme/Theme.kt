package com.digitador.avicola.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Esquema único — la app fuerza modo claro por consistencia con la marca Cargill
private val AppColors = lightColorScheme(
    primary             = Primary,
    onPrimary           = TextOnPrimary,
    primaryContainer    = Green100,
    onPrimaryContainer  = Green900,
    secondary           = PrimaryDark,
    onSecondary         = TextOnPrimary,
    tertiary            = StateWarning,
    background          = AppBackground,
    surface             = Surface,
    surfaceTint         = Color.Transparent, // sin tinte en superficies elevadas (diálogos/tarjetas)
    onBackground        = TextPrimary,
    onSurface           = TextPrimary,
    // Familia de superficies: SIN definirlas, M3 usa su morado/rosado por defecto
    // (el AlertDialog usa surfaceContainerHigh como fondo → se veía rosado).
    surfaceVariant          = SurfaceMuted,
    onSurfaceVariant        = TextSecondary, // texto del cuerpo de diálogos
    surfaceContainerLowest  = Surface,
    surfaceContainerLow     = SurfaceAlt,
    surfaceContainer        = Surface,
    surfaceContainerHigh    = Surface,       // ← fondo del AlertDialog (ahora blanco)
    surfaceContainerHighest = SurfaceAlt,
    surfaceBright           = Surface,
    surfaceDim              = SurfaceAlt,
    inverseSurface          = Neutral800,
    inverseOnSurface        = Neutral50,
    outline                 = Border,
    outlineVariant          = Border,
    error                   = StateError,
    onError                 = TextOnPrimary,
    errorContainer          = StateErrorSurface,
    onErrorContainer        = StateErrorText
)

val AppTypography = Typography(
    bodyLarge   = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium  = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall   = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge  = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelSmall  = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge  = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, fontWeight = FontWeight.Black),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, fontWeight = FontWeight.Black),
    displaySmall  = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Black)
)

@Composable
fun DigitadorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography  = AppTypography,
        content     = content
    )
}
