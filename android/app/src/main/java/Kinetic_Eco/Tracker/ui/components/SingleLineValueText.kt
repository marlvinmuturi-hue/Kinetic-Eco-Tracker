package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
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
 * Tracker / compact stat values: monospace single line. Chooses [fontSize] from available width so
 * duration, distance, and steps still fit when three stats share a row or display / font size is large.
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
    minFontSize: TextUnit = 9.sp,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val maxPx = with(density) { baseFontSize.toPx() }
    val minPx = with(density) { minFontSize.toPx() }

    BoxWithConstraints(modifier = modifier) {
        val n = text.length.coerceAtLeast(1)
        val widthPx = with(density) {
            val w: Dp = maxWidth
            if (w.isFinite && w > 0.dp) w.toPx() else 160.dp.toPx()
        }
        // ~0.62em per monospace digit/char; conservative so text fits without hard clip.
        val fitPx = (widthPx / (n * 0.62f)).coerceIn(minPx, maxPx)
        val scaledPx = if (fontScale > 1.05f) {
            (fitPx / fontScale * 1.06f).coerceIn(minPx, maxPx)
        } else {
            fitPx
        }
        val fontSp = with(density) { scaledPx.toSp() }
        Text(
            text = text,
            style = style.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSp,
                letterSpacing = 0.sp
            ),
            color = color,
            fontWeight = fontWeight,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
    }
}
