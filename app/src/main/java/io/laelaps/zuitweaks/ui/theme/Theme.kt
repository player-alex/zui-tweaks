package io.laelaps.zuitweaks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = OnIndigo,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,
    secondary = Slate40,
    onSecondary = OnSlate,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = OnSlateContainer,
    tertiary = Plum40,
    onTertiary = OnPlum,
    tertiaryContainer = PlumContainer,
    onTertiaryContainer = OnPlumContainer,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = OnIndigoDark,
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = OnIndigoContainerDark,
    secondary = Slate80,
    onSecondary = OnSlateDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = OnSlateContainerDark,
    tertiary = Plum80,
    onTertiary = OnPlumDark,
    tertiaryContainer = PlumContainerDark,
    onTertiaryContainer = OnPlumContainerDark,
)

/**
 * Dynamic colour is deliberately off.
 *
 * Colour carries meaning on this screen - a card is green-ish, amber-ish or neutral to say
 * whether something works, needs a reboot or is merely informational. Sourcing the palette
 * from the wallpaper reassigns those roles on every theme change, which is how a "module
 * active" card ended up the same colour as a warning. A fixed scheme keeps the three states
 * distinguishable, and the neutral surfaces underneath are the Material baseline either way.
 */
@Composable
fun ZuiTweaksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
