package net.devemperor.parley.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import net.devemperor.parley.session.Phase

/**
 * Parley's palette, shared with the pairing page (pair/public/style.css) and the icon: the orb's
 * teal-to-violet, plus one colour per conversation state.
 */
object ParleyColors {
    val Teal = Color(0xFF2EC4B6)
    val TealBright = Color(0xFF7FF0E4)
    val Violet = Color(0xFF8B5CF6)
    val VioletBright = Color(0xFFB9A3FF)
    val Amber = Color(0xFFF5A524)
    val Coral = Color(0xFFFF7A6E)
    /** The hang-up button: the red everyone knows from phone calls, a shade darker than Coral. */
    val HangUp = Color(0xFFE5484D)
    val Muted = Color(0xFF9AA2B3)
    val Faint = Color(0x66F2F4F8)
    val Text = Color(0xFFF2F4F8)
    val Surface = Color(0xFF101218)
    val SurfaceHigh = Color(0xFF171A22)

    /** Colour of the orb's glow and the status text for each state. */
    fun forPhase(phase: Phase?): Color = when (phase) {
        null -> Teal
        Phase.CONNECTING, Phase.RECONNECTING -> Muted
        Phase.LISTENING -> Teal
        Phase.THINKING -> Violet
        Phase.SPEAKING -> Amber
        Phase.ENDED -> Muted
        Phase.ERROR -> Coral
    }
}

private val ParleyColorScheme = ColorScheme(
    primary = Color(0xFF5FE3D6),
    primaryDim = Color(0xFF2EC4B6),
    primaryContainer = Color(0xFF0D4A45),
    onPrimary = Color(0xFF03201D),
    onPrimaryContainer = Color(0xFFB0F5EE),
    secondary = Color(0xFFB9A3FF),
    secondaryDim = Color(0xFF9B83F2),
    secondaryContainer = Color(0xFF33256B),
    onSecondary = Color(0xFF1E1147),
    onSecondaryContainer = Color(0xFFE3DAFF),
    tertiary = Color(0xFFFFC56A),
    tertiaryDim = Color(0xFFE5AC55),
    tertiaryContainer = Color(0xFF5A3D00),
    onTertiary = Color(0xFF412A00),
    onTertiaryContainer = Color(0xFFFFDEAD),
    surfaceContainerLow = Color(0xFF101218),
    surfaceContainer = Color(0xFF171A22),
    surfaceContainerHigh = Color(0xFF20232D),
    onSurface = Color(0xFFF2F4F8),
    onSurfaceVariant = Color(0xFFA7AEBD),
    outline = Color(0xFF6E7585),
    outlineVariant = Color(0xFF2C303B),
    background = Color.Black,
    onBackground = Color.White,
    error = Color(0xFFFF8A80),
    errorDim = Color(0xFFE5776E),
    errorContainer = Color(0xFF7A1C17),
    onError = Color(0xFF4A0703),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Wear's type scale with hyphenation: on the narrow watch screen a long compound word ("Sprechtaste",
 * "Gesprächsverlauf") otherwise breaks wherever the line ends, without a hyphen.
 */
private val ParleyTypography = Typography().let { t ->
    fun TextStyle.hyphenated() = copy(hyphens = Hyphens.Auto)
    t.copy(
        displayLarge = t.displayLarge.hyphenated(),
        displayMedium = t.displayMedium.hyphenated(),
        displaySmall = t.displaySmall.hyphenated(),
        titleLarge = t.titleLarge.hyphenated(),
        titleMedium = t.titleMedium.hyphenated(),
        titleSmall = t.titleSmall.hyphenated(),
        labelLarge = t.labelLarge.hyphenated(),
        labelMedium = t.labelMedium.hyphenated(),
        labelSmall = t.labelSmall.hyphenated(),
        bodyLarge = t.bodyLarge.hyphenated(),
        bodyMedium = t.bodyMedium.hyphenated(),
        bodySmall = t.bodySmall.hyphenated(),
        bodyExtraSmall = t.bodyExtraSmall.hyphenated(),
    )
}

@Composable
fun ParleyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ParleyColorScheme, typography = ParleyTypography, content = content)
}
