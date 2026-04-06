package Kinetic_Eco.Tracker.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Single-line metric text so enlarged system font size does not wrap times and numbers across lines. */
@Composable
fun SingleLineValueText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/**
 * Tracker / compact stat values: scales down slightly when [LocalDensity.fontScale] is large so
 * `HH:mm:ss` and `0.00 km` stay on one line without ellipsis (avoids "00:00:...").
 */
@Composable
fun SingleLineMetricValueText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    fontWeight: FontWeight? = FontWeight.Bold,
    baseFontSize: TextUnit = 20.sp,
) {
    val fs = LocalDensity.current.fontScale
    val factor = if (fs > 1.1f) (1.15f / fs).coerceIn(0.42f, 1f) else 1f
    Text(
        text = text,
        style = style.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = baseFontSize * factor,
            letterSpacing = 0.sp
        ),
        color = color,
        fontWeight = fontWeight,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
    )
}
