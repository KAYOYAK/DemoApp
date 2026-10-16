package com.kayoyak.demoapp.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import com.kayoyak.demoapp.data.settings.AppTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
  )

@Composable
fun DemoAppTheme(
  theme: AppTheme = AppTheme.SYSTEM,
  /**
   * Text size as a multiple of what the system asks for.
   *
   * Applied on top of the device's own setting rather than instead of it. A user
   * who has already made text larger everywhere should not find this app smaller
   * than everything else on the phone.
   */
  fontScale: Float = 1f,
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val darkTheme =
    when (theme) {
      AppTheme.SYSTEM -> isSystemInDarkTheme()
      AppTheme.LIGHT -> false
      AppTheme.DARK -> true
    }

  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  // Every style scaled, rather than one size applied everywhere, so headings stay
  // headings and body text stays body text. Enlarging only one of them flattens
  // the hierarchy the screen is read by.
  val scaledTypography =
    if (fontScale == 1f) {
      Typography
    } else {
      Typography.copy(
        displayLarge = Typography.displayLarge.scaled(fontScale),
        displayMedium = Typography.displayMedium.scaled(fontScale),
        displaySmall = Typography.displaySmall.scaled(fontScale),
        headlineLarge = Typography.headlineLarge.scaled(fontScale),
        headlineMedium = Typography.headlineMedium.scaled(fontScale),
        headlineSmall = Typography.headlineSmall.scaled(fontScale),
        titleLarge = Typography.titleLarge.scaled(fontScale),
        titleMedium = Typography.titleMedium.scaled(fontScale),
        titleSmall = Typography.titleSmall.scaled(fontScale),
        bodyLarge = Typography.bodyLarge.scaled(fontScale),
        bodyMedium = Typography.bodyMedium.scaled(fontScale),
        bodySmall = Typography.bodySmall.scaled(fontScale),
        labelLarge = Typography.labelLarge.scaled(fontScale),
        labelMedium = Typography.labelMedium.scaled(fontScale),
        labelSmall = Typography.labelSmall.scaled(fontScale),
      )
    }

  MaterialTheme(colorScheme = colorScheme, typography = scaledTypography, content = content)
}

/**
 * The same style, larger or smaller.
 *
 * `lineHeight` is scaled with the size. Scaling only the size would leave the
 * lines spaced for the smaller text, and they collide as soon as the letters
 * grow -- which is the failure mode of every "font size" setting that only sets
 * a font size.
 */
private fun TextStyle.scaled(factor: Float): TextStyle =
  copy(
    fontSize = fontSize * factor,
    lineHeight = lineHeight * factor,
    letterSpacing = letterSpacing * factor,
  )
