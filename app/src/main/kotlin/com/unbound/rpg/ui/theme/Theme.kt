package com.unbound.rpg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The palette is deliberately not a stock Material accent. UNBOUND should feel like a lamp-lit
 * page, not a productivity app: near-black ink, warm brass for the things that matter, and a muted
 * violet for the world's own voice.
 */
private val Brass = Color(0xFFE8C87A)
private val BrassDim = Color(0xFFB3985C)
private val Violet = Color(0xFF9B7BD4)
private val Ink = Color(0xFF0B0A0F)
private val InkRaised = Color(0xFF141221)
private val InkCard = Color(0xFF1B1826)
private val Parchment = Color(0xFFF2EDE3)
private val ParchmentDim = Color(0xFFCFC7BA)
private val Blood = Color(0xFFC75B5B)

private val DarkScheme = darkColorScheme(
    primary = Brass,
    onPrimary = Ink,
    primaryContainer = InkCard,
    onPrimaryContainer = Brass,
    secondary = Violet,
    onSecondary = Ink,
    secondaryContainer = InkCard,
    onSecondaryContainer = Violet,
    tertiary = BrassDim,
    background = Ink,
    onBackground = Parchment,
    surface = InkRaised,
    onSurface = Parchment,
    surfaceVariant = InkCard,
    onSurfaceVariant = ParchmentDim,
    outline = Color(0xFF3A3450),
    outlineVariant = Color(0xFF272238),
    error = Blood,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6B5420),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E7C4),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF5B4A8A),
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFF0EAE0),
    onSurfaceVariant = Color(0xFF4C463C),
    outline = Color(0xFFCFC6B6),
    error = Color(0xFF9B2C2C),
)

/**
 * Colours the narrative renderer uses for styled runs. Held here rather than inside the composable
 * so light and dark stay in step, and so the mapping from [com.unbound.core.narrative.NarrativeStyleKind]
 * to a colour is stated in exactly one place.
 */
object NarrativeColors {
    /** What the protagonist said. Warm, and clearly the player's own voice. */
    val playerSpeech: Color @Composable get() = if (isSystemInDarkTheme()) Brass else Color(0xFF7A5A12)

    /** What everybody else said. Present but quieter than the player's own lines. */
    val speech: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFCFC0E8) else Color(0xFF4A3A70)

    /** Texts, emails, calls, letters — anything arriving from outside the room. */
    val communication: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE59A4E) else Color(0xFFB5610A)
}

/**
 * Narrative type is set larger and looser than Material's defaults, because the player reads
 * paragraphs of prose on a phone for an hour at a time rather than glancing at a list.
 */
val NarrativeStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 17.sp,
    lineHeight = 27.sp,
    fontWeight = FontWeight.Normal,
    textAlign = TextAlign.Start,
)

val PlayerEchoStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
fun UnboundTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
