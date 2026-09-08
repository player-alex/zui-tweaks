package io.laelaps.zuitweaks.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * One tonal family, used at the tones Material 3 expects.
 *
 * The template palette this replaced was `Purple40` / `PurpleGrey40` / `Pink40` - three
 * unrelated hues, with no container tones at all, so every coloured surface in the app had
 * to be faked by compositing an accent over the background at 10% alpha. That is why
 * nothing matched: the accents did not belong together, and alpha-blended containers drift
 * with whatever is behind them.
 *
 * These are proper light/dark pairs from a single indigo family, plus one muted plum for
 * the accent that has to read as *different* rather than *louder*. Error is Material's
 * baseline red, untouched - a warning has to look like a warning and not like a fourth
 * brand colour.
 */

// Light
val Indigo40 = Color(0xFF4A5C92)
val OnIndigo = Color(0xFFFFFFFF)
val IndigoContainer = Color(0xFFDBE1FF)
val OnIndigoContainer = Color(0xFF001944)

val Slate40 = Color(0xFF585E72)
val OnSlate = Color(0xFFFFFFFF)
val SlateContainer = Color(0xFFDDE1F9)
val OnSlateContainer = Color(0xFF151B2C)

val Plum40 = Color(0xFF74546E)
val OnPlum = Color(0xFFFFFFFF)
val PlumContainer = Color(0xFFFFD7F3)
val OnPlumContainer = Color(0xFF2B1228)

// Dark
val Indigo80 = Color(0xFFB4C5FF)
val OnIndigoDark = Color(0xFF1B2E60)
val IndigoContainerDark = Color(0xFF324578)
val OnIndigoContainerDark = Color(0xFFDBE1FF)

val Slate80 = Color(0xFFC1C5DD)
val OnSlateDark = Color(0xFF2A3042)
val SlateContainerDark = Color(0xFF404659)
val OnSlateContainerDark = Color(0xFFDDE1F9)

val Plum80 = Color(0xFFE2BAD8)
val OnPlumDark = Color(0xFF42263E)
val PlumContainerDark = Color(0xFF5A3C55)
val OnPlumContainerDark = Color(0xFFFFD7F3)
