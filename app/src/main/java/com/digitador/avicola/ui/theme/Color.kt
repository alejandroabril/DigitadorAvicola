package com.digitador.avicola.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

/**
 * ============================================================================
 *  DESIGN SYSTEM — Paleta MÍNIMA, criterio marca Cargill (verde dominante)
 * ============================================================================
 *  Filosofía: pocos colores, verde de marca como protagonista.
 *
 *  ACENTOS TOTALES (además de los neutros):
 *    • Verde Cargill  → marca, acción, éxito y "peso" (crecimiento)
 *    • Verde oscuro   → texto/íconos verdes accesibles sobre claro
 *    • Rojo           → peligro y mortalidad
 *    • Ámbar          → alimento / advertencia
 *  Neutros: una sola escala slate para estructura/UI.
 *
 *  Capas:  1) PALETA CRUDA  2) ROLES (API de las pantallas)  3) COMPAT (alias).
 *  Regla: las pantallas usan SIEMPRE roles, nunca Color(0x…) ni primitivas.
 * ============================================================================
 */

// ────────────────────────────────────────────────────────────────────────────
// 1) PALETA CRUDA
// ────────────────────────────────────────────────────────────────────────────

// Verde de marca (Cargill) — rampa armónica única ----------------------------
val Green50  = Color(0xFFECF6EF)
val Green100 = Color(0xFFCFE8D7)
val Green200 = Color(0xFFA6D6B6)
val Green300 = Color(0xFF74BE90)
val Green400 = Color(0xFF46A66C)
val Green500 = Color(0xFF2E914E) // ← Primary (verde Cargill)
val Green600 = Color(0xFF23793F)
val Green700 = Color(0xFF1B6133) // ← texto/íconos verdes (contraste AA en claro)
val Green800 = Color(0xFF134A27)
val Green900 = Color(0xFF0C331B)

// Atajos de marca
val GreenBrand   = Green500
val GreenBrandDk = Green700
val GreenStrong  = Green700
val GreenDeep    = Green700
val GreenDarker  = Green900
val GreenLight   = Green200
val GreenCream   = Green50
// verdes de texto/acento usados por las pantallas (todos al verde oscuro accesible)
val GreenSuccessText = Green700
val GreenWeight      = Green700
val GreenSuccess     = Green500

// Neutros SLATE (familia única) ----------------------------------------------
val Neutral0   = Color(0xFFFFFFFF)
val Neutral50  = Color(0xFFF8FAFC)
val Neutral100 = Color(0xFFF1F5F9)
val Neutral200 = Color(0xFFE2E8F0)
val Neutral300 = Color(0xFFCBD5E0)
val Neutral400 = Color(0xFF94A3B8)
val Neutral500 = Color(0xFF64748B)
val Neutral600 = Color(0xFF475569)
val Neutral700 = Color(0xFF334155)
val Neutral800 = Color(0xFF1E293B)
val Neutral900 = Color(0xFF0F172A)

// Rojo (peligro / mortalidad) — uno solo -------------------------------------
val Danger        = Color(0xFFDC2626)
val DangerText    = Color(0xFFB91C1C) // rojo para texto pequeño (AA)
val DangerSurface = Color(0xFFFEE2E2) // fondo rojo tenue

// Ámbar (alimento / advertencia) — uno solo ----------------------------------
val Amber         = Color(0xFFD97706)
val AmberText     = Color(0xFF92400E)
val AmberSurface  = Color(0xFFFEF3C7)

// ────────────────────────────────────────────────────────────────────────────
// 2) ROLES (API pública para las pantallas)
// ────────────────────────────────────────────────────────────────────────────

// Superficies y fondos
val Surface       = Neutral0    // tarjetas, hojas, contenido de app bar
val SurfaceAlt    = Neutral50   // secciones suaves, inputs
val SurfaceMuted  = Neutral100  // chips, celdas de matriz
val AppBackground = Neutral50   // fondo general

// Bordes / divisores
val Border        = Neutral200
val BorderStrong  = Neutral300

// Texto / iconos
val TextPrimary   = Neutral800
val TextSecondary = Neutral600
val TextTertiary  = Neutral500
val TextMuted     = Neutral400  // SOLO placeholders/íconos, nunca texto de lectura
val TextDisabled  = Neutral300
val TextOnPrimary = Neutral0
val TextHeading   = Green900    // títulos de marca
val TextBrand     = Green700    // subtítulos de marca
val TextSuccess   = Green700    // texto verde accesible sobre claro

// Marca / acción primaria
val Primary       = Green500
val PrimaryDark   = Green700
val PrimaryDeep   = Green900

// Estados (verde = éxito, rojo = error, ámbar = warning)
val StateSuccess        = Green500
val StateSuccessText    = Green700
val StateSuccessSurface = Green50
val StateSuccessSurfaceAlt = Green100
val StateError          = Danger
val StateErrorText      = DangerText
val StateErrorSurface   = DangerSurface
val StateWarning        = Amber
val StateInfo           = Green500 // sin azul: la info no destructiva usa marca

// Acentos por categoría de digitación (mínimos)
val AccentMortalidad = Danger  // 🔴 rojo
val AccentPeso       = Green500 // 🟢 verde de marca (crecimiento)
val AccentAlimento   = Amber   // 🟡 ámbar
val AccentPesoGreen  = Green700 // verde de texto para el promedio de peso

// Gradientes
val PrimaryGradient  = Brush.verticalGradient(listOf(Green500, Green700))
val SuccessGradient  = Brush.horizontalGradient(listOf(Green800, Green500))
val HighTechGradient = Brush.linearGradient(listOf(Green900, Green700))

// ────────────────────────────────────────────────────────────────────────────
// 3) COMPAT — alias de nombres antiguos (mapeados a roles / paleta)
//    Mantener para no romper código existente. Preferir los ROLES de arriba.
// ────────────────────────────────────────────────────────────────────────────

// Marca (nombres antiguos)
val AvicolaPrimary        = Green500
val AvicolaPrimaryDark    = Green700
val AvicolaPrimaryDarker  = Green900
val AvicolaPrimaryLight   = Green200
val AvicolaPrimaryLighter = Green50
val AvicolaSecondary = AvicolaPrimaryDark
val AvicolaDark      = AvicolaPrimaryDarker
val AvicolaLight     = AvicolaPrimaryLight
val AvicolaCream     = AvicolaPrimaryLighter

val AvicolaPrimary50  = Green50
val AvicolaPrimary100 = Green100
val AvicolaPrimary200 = Green200
val AvicolaPrimary300 = Green300
val AvicolaPrimary400 = Green400
val AvicolaPrimary500 = Green500
val AvicolaPrimary600 = Green600
val AvicolaPrimary700 = Green700
val AvicolaPrimary800 = Green800
val AvicolaPrimary900 = Green900

// Semánticos antiguos (rojo/ámbar únicos; "success/info" = verde marca)
val Success      = Green500
val SuccessLight = Green50
val SuccessDark  = Green800
val Error      = Danger
val ErrorLight = DangerSurface
val ErrorDark  = DangerText
val Warning      = Amber
val WarningLight = AmberSurface
val WarningDark  = AmberText
val Info      = Green500
val InfoLight = Green50
val InfoDark  = Green800

// Texto (nombres antiguos)
val TextTitle     = Green900
val TextSubtitle  = Green700
val TextHint      = TextMuted
val TextLink      = Green600
val TextOnDark    = Green50

// Fondos / superficies (nombres antiguos → slate)
val Background       = AppBackground
val Surface2         = Surface
val CardDefault      = Surface
val CardHighlighted  = SurfaceAlt
val CardSelected     = Green100

// Accent / Ok / Warn / Danger (nombres antiguos)
val AccentInk   = Green500
val Accent      = Green500
val AccentLight = Green700
val AccentSoft  = Green100

val OkGreen     = Green500
val OkSoft      = Green50
val OkDark      = Green800

val WarnAmber   = Amber
val WarnSoft    = AmberSurface

val DangerRed   = Danger
val DangerSoft  = DangerSurface

// Líneas (nombres antiguos → slate)
val Line   = Border
val Line2  = Neutral100

// Ink (nombres antiguos)
val Ink   = TextPrimary
val Ink2  = TextSecondary
val Ink3  = TextTertiary
val Ink4  = TextMuted

// Azul (en desuso como acento; se conserva por compatibilidad puntual)
val BlueBase            = Color(0xFF2B6CB0)
val BlueMaterial        = Color(0xFF1976D2)
val BlueMaterialLight   = Color(0xFFE3F2FD)
val BlueMaterialDark    = Color(0xFF0D47A1)
