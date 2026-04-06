package Kinetic_Eco.Tracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Indigo500,
    secondary = Green500,
    tertiary = Blue500,
    background = Slate900,
    surface = Slate800,
    error = Red500,
    onPrimary = Slate50,
    onSecondary = Slate50,
    onTertiary = Slate50,
    onBackground = Slate50,
    onSurface = Slate50,
    onError = Slate50,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate300
)

// Light theme: light backgrounds, darker accents for contrast on light
private val LightColorScheme = lightColorScheme(
    primary = Indigo600,
    secondary = Green600,
    tertiary = Blue600,
    background = Slate50,
    surface = Slate200,
    error = Red500,
    onPrimary = Slate50,
    onSecondary = Slate50,
    onTertiary = Slate50,
    onBackground = Slate900,
    onSurface = Slate800,
    onError = Slate50,
    surfaceVariant = Slate300,
    onSurfaceVariant = Slate600
)

@Composable
fun KineticEcoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}



